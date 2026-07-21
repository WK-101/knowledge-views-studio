import { next, retryDelayMs } from "../../shared/queue";
import { BridgeError, annotate, annotateRemove, annotationsFor, capture, fetchSchema, known, loadConnection, lookup } from "./lib/bridge-client";
import { loadPreferences } from "./lib/preferences";
import { matchRule } from "../../shared/rules";
import { hasPageAccess, registerAnnotator, injectAnnotatorIntoOpenTabs } from "./lib/page-access";
import { forget, statusKey } from "./lib/answer-cache";
import { api } from "./lib/bridge-client";
import { dropFromQueue, pruneQueue, readQueue, recordFailure } from "./lib/queue-store";
import { hasSearchAccess, registerSearchScript } from "./lib/serp-permission";

/**
 * Draining what couldn't be delivered.
 *
 * Runs on a modest alarm rather than a tight loop: the usual reason a capture is waiting is that Obsidian
 * simply isn't open yet, which resolves on the scale of minutes. Retrying harder wouldn't make it resolve
 * sooner, and would spend the machine's battery insisting.
 *
 * One capture is attempted per wake-up. If the vault is unreachable the rest are certainly unreachable too,
 * so there's nothing to gain from trying them all — and it keeps the failure count honest, since a queue of
 * twenty shouldn't burn through its retry budget in a single offline moment.
 */

interface AlarmsApi {
  create(name: string, info: { periodInMinutes?: number; delayInMinutes?: number }): void;
  onAlarm: { addListener(fn: (alarm: { name: string }) => void): void };
}
interface RuntimeApi {
  onInstalled: { addListener(fn: () => void): void };
  onStartup?: { addListener(fn: () => void): void };
}

const ALARM = "kvs-drain";

const alarms = (): AlarmsApi | null => {
  const g = globalThis as unknown as { browser?: { alarms?: AlarmsApi }; chrome?: { alarms?: AlarmsApi } };
  return g.browser?.alarms ?? g.chrome?.alarms ?? null;
};
const runtime = (): RuntimeApi | null => {
  const g = globalThis as unknown as { browser?: { runtime?: RuntimeApi }; chrome?: { runtime?: RuntimeApi } };
  return g.browser?.runtime ?? g.chrome?.runtime ?? null;
};

async function drain(): Promise<void> {
  await pruneQueue();
  const queue = await readQueue();
  const entry = next(queue);
  if (entry === null) return;

  // Respect the backoff: an entry that just failed shouldn't be retried on the very next wake-up.
  const due = entry.attempts === 0 || Date.now() - entry.queuedAt > retryDelayMs(entry.attempts);
  if (!due) return;

  try {
    const connection = await loadConnection();
    if (connection.token === null) return;
    const result = await capture(connection, entry.request);
    if (result.ok) {
      await dropFromQueue(entry.id);
      return;
    }
    // A refusal the vault understood — retrying won't change the answer, so stop asking.
    await dropFromQueue(entry.id);
  } catch (error) {
    const message = error instanceof BridgeError ? error.message : "Unknown error";
    if (error instanceof BridgeError && !error.offline) {
      // The vault answered and said no. Keep the record but don't retry forever.
      await recordFailure(entry.id, message);
      return;
    }
    await recordFailure(entry.id, message);
  }
}

const alarmApi = alarms();
if (alarmApi !== null) {
  alarmApi.create(ALARM, { periodInMinutes: 1 });
  alarmApi.onAlarm.addListener((alarm) => {
    if (alarm.name === ALARM) void drain();
  });
}

const runtimeApi = runtime();
runtimeApi?.onInstalled.addListener(() => void drain());
runtimeApi?.onStartup?.addListener(() => void drain());


/**
 * The recall badge.
 *
 * Marks the toolbar icon when the page you're on is already in your vault — the quiet half of a companion,
 * useful precisely when you *aren't* capturing. It answers "have I read this already?" without you having
 * to ask.
 *
 * Off by default and deliberately so: it means every page you visit is checked against your vault. That
 * check never leaves the machine, but it's still a thing to be asked rather than assumed, and someone who
 * only wants a capture button should not be quietly opted into it.
 */

interface ActionApi {
  setBadgeText(details: { text: string; tabId?: number }): void;
  setBadgeBackgroundColor?(details: { color: string }): void;
  setTitle(details: { title: string; tabId?: number }): void;
}
interface TabsApi {
  onUpdated: {
    addListener(fn: (tabId: number, info: { status?: string }, tab: { url?: string }) => void): void;
  };
}

const action = (): ActionApi | null => {
  const g = globalThis as unknown as {
    browser?: { action?: ActionApi; browserAction?: ActionApi };
    chrome?: { action?: ActionApi; browserAction?: ActionApi };
  };
  return g.browser?.action ?? g.browser?.browserAction ?? g.chrome?.action ?? g.chrome?.browserAction ?? null;
};
const tabs = (): TabsApi | null => {
  const g = globalThis as unknown as { browser?: { tabs?: TabsApi }; chrome?: { tabs?: TabsApi } };
  return g.browser?.tabs ?? g.chrome?.tabs ?? null;
};

/** Short-lived memory of what we've already asked about, so revisits don't re-query the vault. */
const recallCache = new Map<string, { at: number; found: boolean }>();
const RECALL_TTL_MS = 5 * 60 * 1000;

async function badgeEnabled(): Promise<boolean> {
  const stored = await api().storage.local.get(["recallBadge"]);
  return stored["recallBadge"] === true;
}

/** The search-page marks are a separate choice from the toolbar badge; both are off until asked for. */
async function serpEnabled(): Promise<boolean> {
  const stored = await api().storage.local.get(["serpMarks"]);
  return stored["serpMarks"] === true;
}

async function updateBadge(tabId: number, url: string): Promise<void> {
  const ui = action();
  if (ui === null) return;
  if (!/^https?:\/\//i.test(url)) {
    ui.setBadgeText({ text: "", tabId });
    return;
  }
  if (!(await badgeEnabled())) return;

  const cached = recallCache.get(url);
  const fresh = cached !== undefined && Date.now() - cached.at < RECALL_TTL_MS;
  let found = cached?.found ?? false;

  if (!fresh) {
    try {
      const connection = await loadConnection();
      if (connection.token === null) return;
      const result = await lookup(connection, { url });
      found = result.matches.length > 0;
      recallCache.set(url, { at: Date.now(), found });
    } catch {
      // Obsidian closed, or reading not granted. Silence is right here: a badge is a convenience, and
      // nagging about it on every page would be worse than its absence.
      return;
    }
  }

  ui.setBadgeText({ text: found ? "\u2713" : "", tabId });
  ui.setBadgeBackgroundColor?.({ color: "#6b46c1" });
  ui.setTitle({
    title: found ? "Already in your vault — click to capture again or search" : "Capture to Knowledge Views",
    tabId,
  });
}

/**
 * Watch navigation only once the `tabs` permission has actually been granted.
 *
 * The permission is optional and asked for at the moment someone enables the badge, so at install time the
 * browser never says "read your browsing history" — a prompt that would undercut the one thing this tool is
 * trying to be trusted about. Until then the listener simply isn't attached.
 */
function watchNavigation(): void {
  const api = tabs();
  if (api === null || navigationWatched) return;
  navigationWatched = true;
  api.onUpdated.addListener((tabId, info, tab) => {
    if (info.status !== "complete" || tab.url === undefined) return;
    void updateBadge(tabId, tab.url);
  });
}

let navigationWatched = false;

const permissionsApi = ((): { contains(p: { permissions: string[] }): Promise<boolean> } | null => {
  const g = globalThis as unknown as {
    browser?: { permissions?: { contains(p: { permissions: string[] }): Promise<boolean> } };
    chrome?: { permissions?: { contains(p: { permissions: string[] }): Promise<boolean> } };
  };
  return g.browser?.permissions ?? g.chrome?.permissions ?? null;
})();

void (async () => {
  if (permissionsApi === null) {
    watchNavigation();
    return;
  }
  try {
    if (await permissionsApi.contains({ permissions: ["tabs"] })) watchNavigation();
  } catch {
    // Nothing granted, nothing to watch.
  }
})();


/**
 * Answer the search-page script's question on its behalf.
 *
 * The content script runs inside a page it shares with whatever else that page loads, so it must never hold
 * the vault token. It asks here; this asks the vault; only the list of recognised URLs goes back.
 */
interface RuntimeMessaging {
  onMessage: {
    addListener(
      fn: (message: unknown, sender: unknown, sendResponse: (response: unknown) => void) => boolean,
    ): void;
  };
}
const runtimeMessaging = ((): RuntimeMessaging | null => {
  const g = globalThis as unknown as {
    browser?: { runtime?: RuntimeMessaging };
    chrome?: { runtime?: RuntimeMessaging };
  };
  return g.browser?.runtime ?? g.chrome?.runtime ?? null;
})();

runtimeMessaging?.onMessage.addListener((message, _sender, sendResponse) => {
  const request = message as { type?: string; urls?: unknown } | null;
  if (request?.type !== "kvs-known") return false;
  void (async () => {
    try {
      if (!(await serpEnabled())) {
        sendResponse({ known: [] });
        return;
      }
      const connection = await loadConnection();
      if (connection.token === null || !Array.isArray(request.urls)) {
        sendResponse({ known: [] });
        return;
      }
      const result = await known(connection, request.urls as string[]);
      sendResponse({ known: result.known });
    } catch {
      sendResponse({ known: [] });
    }
  })();
  return true;
});

/**
 * Which view a highlight lands in — a rule for the site, the default, the last-used, then the first
 * writable view. Decided here, once, so the content script never has to know views exist. The same order
 * a capture uses, because an annotation IS a capture that happens to start from a selection.
 */
async function viewForAnnotation(url: string): Promise<string | null> {
  const prefs = await loadPreferences();
  const rule = matchRule(prefs.rules, url);
  // An explicitly chosen annotation view outranks everything — that's what "explicit" means.
  const chosen = prefs.annotationViewId;
  const connection = await loadConnection();
  if (connection.token === null) return null;
  try {
    const schema = await fetchSchema(connection);
    // `capture` is always present in schema entries; writability is the real question.
    const writable = schema.views.filter((v) => v.capture.writable);
    const preferred = [chosen, rule?.viewId, prefs.defaultViewId, prefs.rememberLastView ? prefs.lastViewId : ""]
      .filter((id): id is string => typeof id === "string" && id !== "")
      .find((id) => writable.some((v) => v.id === id));
    return preferred ?? writable[0]?.id ?? null;
  } catch {
    return null;
  }
}

runtimeMessaging?.onMessage.addListener((message, _sender, sendResponse) => {
  const request = message as { type?: string; url?: unknown; annotation?: unknown; fields?: unknown; id?: unknown } | null;
  if (request?.type !== "kvs-annotate" && request?.type !== "kvs-annotations-for" && request?.type !== "kvs-annotate-remove") {
    return false;
  }
  void (async () => {
    try {
      const connection = await loadConnection();
      const url = typeof request.url === "string" ? request.url : "";
      if (connection.token === null || url === "") {
        sendResponse(request.type === "kvs-annotations-for" ? { annotations: [] } : { ok: false });
        return;
      }

      if (request.type === "kvs-annotations-for") {
        const result = await annotationsFor(connection, { url });
        sendResponse({ annotations: result.annotations });
        return;
      }

      if (request.type === "kvs-annotate-remove") {
        const viewId = await viewForAnnotation(url);
        const removePrefs = await loadPreferences();
        const removeCols = viewId !== null ? removePrefs.viewColumns[viewId] : undefined;
        await annotateRemove(connection, {
          url,
          id: String(request.id ?? ""),
          ...(viewId !== null ? { viewId } : {}),
          ...(removeCols?.annotationColumn !== undefined ? { annotationColumn: removeCols.annotationColumn } : {}),
          ...(removeCols?.urlColumn !== undefined ? { urlColumn: removeCols.urlColumn } : {}),
        });
        void forget([statusKey(url)]);
        sendResponse({ ok: true });
        return;
      }

      const viewId = await viewForAnnotation(url);
      if (viewId === null) {
        sendResponse({ ok: false, reason: "No view can take this highlight." });
        return;
      }
      const fields = Array.isArray(request.fields)
        ? (request.fields as { key: string; value: string }[])
        : [];
      const prefs = await loadPreferences();
      const cols = prefs.viewColumns[viewId];
      const result = await annotate(connection, {
        viewId,
        url,
        annotation: request.annotation as never,
        fields,
        ...(cols?.annotationColumn !== undefined ? { annotationColumn: cols.annotationColumn } : {}),
        ...(cols?.urlColumn !== undefined ? { urlColumn: cols.urlColumn } : {}),
      });
      if (result.ok) void forget([statusKey(url)]);
      sendResponse({ ok: result.ok, ...(result.reason !== undefined ? { reason: result.reason } : {}) });
    } catch (error) {
      // The reason is the difference between a fixable setting and a mystery; it must reach the page.
      const reason = error instanceof BridgeError ? error.message : "Couldn't reach your vault.";
      sendResponse(request.type === "kvs-annotations-for" ? { annotations: [] } : { ok: false, reason });
    }
  })();
  return true;
});


// Re-register page scripts after a browser restart, when their permissions are already held.
void (async () => {
  try {
    if ((await serpEnabled()) && (await hasSearchAccess())) await registerSearchScript();
  } catch {
    // Nothing to do; the feature stays off until enabled again.
  }
  try {
    const prefs = await loadPreferences();
    if (prefs.annotations && (await hasPageAccess())) {
      await registerAnnotator();
      // Tabs restored from the last session predate this run's registration; inject so their highlights
      // repaint without a manual reload.
      await injectAnnotatorIntoOpenTabs();
    }
  } catch {
    // Same: off until enabled again.
  }
})();
