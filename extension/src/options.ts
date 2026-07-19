import {
  api,
  fetchSchema,
  bridgeReachable,
  DEFAULT_BASE_URL,
  discoverBridge,
  loadConnection,
  pair,
  saveConnection,
} from "./lib/bridge-client";
import { parsePairingInput } from "../../shared/protocol";
import { loadPreferences, savePreferences, type Preferences } from "./lib/preferences";
import { isUsableRule, type DomainRule } from "../../shared/rules";
import type { SchemaView } from "../../shared/protocol";
import {
  hasSearchAccess,
  registerSearchScript,
  requestSearchAccess,
  unregisterSearchScript,
} from "./lib/serp-permission";
import { readQueue, writeQueue } from "./lib/queue-store";

/**
 * Pairing and connection settings.
 *
 * The pairing flow is deliberately plain: a code shown in Obsidian, typed here once. No account, no service,
 * nothing leaves the machine. The wording tries to make that legible rather than merely true — someone
 * granting a browser extension access to their notes deserves to understand exactly what they're granting.
 */

const byId = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

/** Ask for the optional `tabs` permission. Returns false if it isn't granted, including when refused. */
async function requestTabsPermission(): Promise<boolean> {
  const g = globalThis as unknown as {
    browser?: { permissions?: { request(p: { permissions: string[] }): Promise<boolean> } };
    chrome?: { permissions?: { request(p: { permissions: string[] }): Promise<boolean> } };
  };
  const permissions = g.browser?.permissions ?? g.chrome?.permissions;
  if (permissions === undefined) return false;
  try {
    return await permissions.request({ permissions: ["tabs"] });
  } catch {
    return false;
  }
}

function status(message: string, kind: "info" | "error" | "ok" = "info"): void {
  const el = byId("status");
  el.textContent = message;
  el.className = `status ${kind}`;
}

/**
 * Look for the vault and say what was found.
 *
 * Doing this on open, rather than waiting to fail at the moment someone tries to connect, means the port
 * question mostly stops existing — and when it can't be answered, the reason is on screen before anyone has
 * typed anything.
 */
async function locate(): Promise<void> {
  const found = byId("found");
  const connection = await loadConnection();

  if (await bridgeReachable(connection.baseUrl)) {
    found.textContent = `Found Obsidian at ${connection.baseUrl}`;
    found.className = "status ok";
    return;
  }
  found.textContent = "Looking for Obsidian…";
  found.className = "status";

  const discovered = await discoverBridge();
  if (discovered !== null) {
    await saveConnection({ baseUrl: discovered });
    byId<HTMLInputElement>("baseUrl").value = discovered;
    found.textContent = `Found Obsidian at ${discovered}`;
    found.className = "status ok";
    return;
  }
  found.textContent = "Obsidian isn't reachable yet — open it and turn the browser bridge on.";
  found.className = "status error";
}

async function refresh(): Promise<void> {
  const connection = await loadConnection();
  byId<HTMLInputElement>("baseUrl").value = connection.baseUrl;
  byId("paired").textContent = connection.token === null ? "Not connected" : "Connected to a vault";
  byId("unpair").toggleAttribute("hidden", connection.token === null);

  const stored = await api().storage.local.get(["recallBadge", "serpMarks", "popupSize"]);
  byId<HTMLSelectElement>("popupSize").value =
    typeof stored["popupSize"] === "string" ? stored["popupSize"] : "medium";
  byId<HTMLInputElement>("recallBadge").checked = stored["recallBadge"] === true;
  // Only shown as on when the access it needs is actually held, so the checkbox can't claim a feature
  // that silently isn't running.
  byId<HTMLInputElement>("serpMarks").checked = stored["serpMarks"] === true && (await hasSearchAccess());

  const queue = await readQueue();
  byId("queue").textContent =
    queue.length === 0
      ? "Nothing waiting."
      : `${String(queue.length)} capture(s) waiting for your vault.`;
  byId("clearQueue").toggleAttribute("hidden", queue.length === 0);
}

async function doPair(): Promise<void> {
  const raw = byId<HTMLInputElement>("code").value;
  const parsed = parsePairingInput(raw);
  if (parsed === null) {
    status("Paste the connection link from Obsidian, or the six-digit code.", "error");
    return;
  }

  // A link carries the port; otherwise use whatever was found, falling back to the default.
  let baseUrl = byId<HTMLInputElement>("baseUrl").value.trim() || DEFAULT_BASE_URL;
  if (parsed.port !== undefined) baseUrl = `http://127.0.0.1:${String(parsed.port)}`;
  else if (!(await bridgeReachable(baseUrl))) baseUrl = (await discoverBridge()) ?? baseUrl;

  status("Connecting…");
  try {
    const result = await pair(baseUrl, parsed.code);
    await saveConnection({ baseUrl, token: result.token });
    byId<HTMLInputElement>("code").value = "";
    status(`Connected to “${result.vault}”. You can capture straight away.`, "ok");
    await locate();
    await refresh();
  } catch (error) {
    status(error instanceof Error ? error.message : "Couldn't connect.", "error");
  }
}



// ---- Views, rules, and the rest of the settings -------------------------

let views: readonly SchemaView[] = [];

/**
 * Read the view list from the vault.
 *
 * The extension caches it, because asking on every popup open would make the popup slow for a list that
 * changes rarely. But it *does* change — a new view, a renamed one — and without a way to re-read it a rule
 * would silently point at something that no longer exists. Hence a visible refresh.
 */
async function loadViews(): Promise<void> {
  const note = byId("schemaNote");
  try {
    const connection = await loadConnection();
    if (connection.token === null) {
      note.textContent = "Connect to a vault first to choose views.";
      return;
    }
    const schema = await fetchSchema(connection);
    views = schema.views;
    note.textContent = `${String(views.length)} view(s) available from “${schema.vault}”.`;
    fillViewPickers();
  } catch {
    note.textContent = "Couldn't read your views — is Obsidian running?";
  }
}

function fillViewPickers(): void {
  const options = (includeBlank: boolean): HTMLOptionElement[] => {
    const list: HTMLOptionElement[] = [];
    if (includeBlank) {
      const blank = document.createElement("option");
      blank.value = "";
      blank.textContent = "— first available —";
      list.push(blank);
    }
    for (const view of views) {
      const option = document.createElement("option");
      option.value = view.id;
      option.textContent = view.name;
      list.push(option);
    }
    return list;
  };
  const def = byId<HTMLSelectElement>("defaultView");
  const chosen = def.value;
  def.replaceChildren(...options(true));
  def.value = chosen;
  byId<HTMLSelectElement>("ruleView").replaceChildren(...options(false));
}

/** A view's name, or a plain statement that it's gone — never a bare identifier. */
function viewName(id: string): string {
  return views.find((v) => v.id === id)?.name ?? "(view no longer exists)";
}

function drawRules(prefs: Preferences): void {
  const host = byId("rules");
  host.replaceChildren();
  if (prefs.rules.length === 0) {
    const empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "No site rules yet.";
    host.appendChild(empty);
    return;
  }
  for (const [index, rule] of prefs.rules.entries()) {
    const row = document.createElement("div");
    row.className = "rule-row";

    const domain = document.createElement("span");
    domain.className = "rule-domain";
    domain.textContent = rule.domain;
    row.appendChild(domain);

    const detail = document.createElement("span");
    detail.className = "rule-detail";
    detail.textContent = [
      `→ ${viewName(rule.viewId)}`,
      rule.shape !== undefined ? `as a ${rule.shape}` : "",
      rule.tags !== undefined && rule.tags !== "" ? `· ${rule.tags}` : "",
    ]
      .filter((part) => part !== "")
      .join(" ");
    row.appendChild(detail);

    const remove = document.createElement("button");
    remove.className = "rule-remove";
    remove.textContent = "Remove";
    remove.addEventListener("click", () => {
      void (async () => {
        const next = prefs.rules.filter((_, i) => i !== index);
        const saved = await savePreferences({ rules: next });
        drawRules(saved);
        status(`Rule for ${rule.domain} removed.`, "info");
      })();
    });
    row.appendChild(remove);
    host.appendChild(row);
  }
}

async function wirePreferences(): Promise<void> {
  const prefs = await loadPreferences();

  byId<HTMLSelectElement>("defaultView").value = prefs.defaultViewId;
  byId<HTMLInputElement>("rememberLastView").checked = prefs.rememberLastView;
  byId<HTMLInputElement>("includeContent").checked = prefs.includeContent;
  byId<HTMLSelectElement>("selectionStyle").value = prefs.selectionStyle;
  byId<HTMLInputElement>("alwaysTags").value = prefs.alwaysTags;
  byId<HTMLSelectElement>("searchMode").value = prefs.searchMode;
  drawRules(prefs);

  const bind = (id: string, read: () => Partial<Preferences>, event = "change"): void => {
    byId(id).addEventListener(event, () => {
      void savePreferences(read()).then(() => status("Saved.", "ok"));
    });
  };
  bind("defaultView", () => ({ defaultViewId: byId<HTMLSelectElement>("defaultView").value }));
  bind("rememberLastView", () => ({ rememberLastView: byId<HTMLInputElement>("rememberLastView").checked }));
  bind("includeContent", () => ({ includeContent: byId<HTMLInputElement>("includeContent").checked }));
  bind("selectionStyle", () => ({
    selectionStyle: byId<HTMLSelectElement>("selectionStyle").value === "plain" ? "plain" : "quote",
  }));
  bind("alwaysTags", () => ({ alwaysTags: byId<HTMLInputElement>("alwaysTags").value.trim() }));
  bind("searchMode", () => {
    const value = byId<HTMLSelectElement>("searchMode").value;
    return { searchMode: value === "meaning" || value === "ask" ? value : "keyword" };
  });

  byId("ruleAdd").addEventListener("click", () => {
    void (async () => {
      const shape = byId<HTMLSelectElement>("ruleShape").value;
      const tags = byId<HTMLInputElement>("ruleTags").value.trim();
      const candidate: Partial<DomainRule> = {
        domain: byId<HTMLInputElement>("ruleDomain").value.trim(),
        viewId: byId<HTMLSelectElement>("ruleView").value,
        ...(shape === "row" || shape === "note" ? { shape } : {}),
        ...(tags !== "" ? { tags } : {}),
      };
      // Refused rather than stored, so the list never fills with rules that quietly never fire.
      if (!isUsableRule(candidate)) {
        status("A rule needs a site and a view.", "error");
        return;
      }
      const existing = await loadPreferences();
      const withoutDuplicate = existing.rules.filter(
        (r) => r.domain.toLowerCase() !== candidate.domain.toLowerCase(),
      );
      const saved = await savePreferences({ rules: [...withoutDuplicate, candidate] });
      drawRules(saved);
      byId<HTMLInputElement>("ruleDomain").value = "";
      byId<HTMLInputElement>("ruleTags").value = "";
      status(`Captures from ${candidate.domain} will go to ${viewName(candidate.viewId)}.`, "ok");
    })();
  });

  byId("refreshSchema").addEventListener("click", () => {
    void loadViews().then(() => {
      status("Views re-read from Obsidian.", "ok");
      void loadPreferences().then(drawRules);
    });
  });
}

document.addEventListener("DOMContentLoaded", () => {
  void locate();
  void refresh();
  void wirePreferences();
  void loadViews();
  byId("pair").addEventListener("click", () => void doPair());
  byId("unpair").addEventListener("click", () => {
    void saveConnection({ token: "" }).then(() => {
      status("Unpaired. Your vault still holds its own token — revoke it there too if you want it gone.", "info");
      return refresh();
    });
  });
  byId("recallBadge").addEventListener("change", () => {
    void (async () => {
      const box = byId<HTMLInputElement>("recallBadge");
      if (!box.checked) {
        await api().storage.local.set({ recallBadge: false });
        status("The toolbar mark is off.", "info");
        return;
      }
      // Ask for the permission at the moment it's wanted, from this click — which is the only time a
      // browser will allow the prompt, and the only time it makes sense to a person.
      const granted = await requestTabsPermission();
      if (!granted) {
        box.checked = false;
        status("That needs permission to see which page you're on. Nothing was changed.", "error");
        return;
      }
      await api().storage.local.set({ recallBadge: true });
      status("The toolbar will now mark pages already in your vault.", "ok");
    })();
  });
  byId("popupSize").addEventListener("change", () => {
    const value = byId<HTMLSelectElement>("popupSize").value;
    const size = value === "small" || value === "large" ? value : "medium";
    void savePreferences({ popupSize: size }).then(() => {
      status("Popup size saved — it applies next time you open it.", "ok");
    });
  });

  byId("serpMarks").addEventListener("change", () => {
    const box = byId<HTMLInputElement>("serpMarks");

    if (!box.checked) {
      void (async () => {
        await savePreferences({ serpMarks: false });
        await unregisterSearchScript();
        status("Search results will no longer be marked.", "info");
      })();
      return;
    }

    // Asked for FIRST, before anything is awaited.
    //
    // A browser only allows a permission prompt while it can still see the click that led to it, and any
    // await beforehand ends that. The previous version checked whether access was already held before
    // asking for it, which meant the request always arrived one tick too late and was refused outright —
    // so the box simply sprang back with an error and no prompt ever appeared.
    const pending = requestSearchAccess();

    void (async () => {
      const granted = await pending;
      if (!granted) {
        box.checked = false;
        status(
          "Access to search sites wasn't granted, so nothing changed. If no prompt appeared, your browser may have blocked it — try again from a click.",
          "error",
        );
        return;
      }
      await savePreferences({ serpMarks: true });
      await registerSearchScript();
      status("Search results will now show what you already have.", "ok");
    })();
  });
  byId("clearQueue").addEventListener("click", () => {
    void writeQueue([]).then(() => {
      status("Cleared what was waiting.", "info");
      return refresh();
    });
  });
});
