import { next, retryDelayMs } from "../../shared/queue";
import { BridgeError, capture, loadConnection, lookup } from "./lib/bridge-client";
import { api } from "./lib/bridge-client";
import { dropFromQueue, pruneQueue, readQueue, recordFailure } from "./lib/queue-store";

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
