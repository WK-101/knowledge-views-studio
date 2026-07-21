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
import { hasPageAccess, requestPageAccess, registerAnnotator, unregisterAnnotator, injectAnnotatorIntoOpenTabs } from "./lib/page-access";
import { pluginIsCurrent, outdatedPluginMessage } from "./lib/version";
import { zoteroStatus, zoteroCollections } from "./lib/zotero-client";
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

/** A tiny element helper for the settings-only DOM. */
function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls = "", text = ""): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (cls !== "") node.className = cls;
  if (text !== "") node.textContent = text;
  return node;
}

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
    if (!pluginIsCurrent(schema.pluginVersion)) {
      note.textContent = outdatedPluginMessage(schema.pluginVersion);
      return;
    }
    views = schema.views;
    const writable = views.filter((v) => v.capture.writable).length;
    // The counts are the diagnosis: "5 views, 0 can receive captures" says in one line what a bare view
    // count hides completely.
    note.textContent =
      views.length === 0
        ? `“${schema.vault}” exposes no views to the browser — check the plugin's Browser bridge settings.`
        : `${String(views.length)} view(s) from “${schema.vault}”, ${String(writable)} can receive captures.`;
    if (views.length > 0 && writable === 0) {
      const reasons = views
        .map((v) => `${v.name}: ${v.capture.reason ?? "can't receive captures"}`)
        .join(" · ");
      note.textContent += ` ${reasons}`;
    }
    fillViewPickers();
    void drawViewColumns();
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
  def.replaceChildren(...options(true));
  byId<HTMLSelectElement>("ruleView").replaceChildren(...options(false));
  const annView = byId<HTMLSelectElement>("annotationView");
  const annBlank = document.createElement("option");
  annBlank.value = "";
  annBlank.textContent = "— decide per site (rules, then default) —";
  annView.replaceChildren(annBlank, ...options(false));

  // The saved choices are re-applied AFTER the options exist. Setting a select's value before its options
  // arrive silently does nothing — which made every saved view choice look like it hadn't stuck: the
  // preference was saved fine and displayed as "— first available —" anyway.
  void loadPreferences().then((prefs) => {
    def.value = prefs.defaultViewId;
    annView.value = prefs.annotationViewId;
  });
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

/**
 * Per-view column choices.
 *
 * The plugin guesses which column is the URL and which takes annotations, and the guess is usually right.
 * This is for when it isn't — a view whose link lives in a column called something the heuristic doesn't
 * know. Each picker offers that view's actual columns plus "choose automatically", so the override is
 * always visibly optional.
 */
async function drawViewColumns(): Promise<void> {
  const host = byId("viewColumns");
  host.replaceChildren();
  if (views.length === 0) {
    host.appendChild(el("p", "hint", "Connect and refresh views to map columns."));
    return;
  }
  const prefs = await loadPreferences();
  for (const view of views) {
    const current = prefs.viewColumns[view.id] ?? {};
    const block = el("div", "vc-view");
    block.appendChild(el("div", "vc-name", view.name));

    const picker = (label: string, chosen: string | undefined, onPick: (value: string) => void): HTMLElement => {
      const wrap = el("label", "vc-field");
      wrap.appendChild(el("span", "", label));
      const select = document.createElement("select");
      const auto = document.createElement("option");
      auto.value = "";
      auto.textContent = "choose automatically";
      select.appendChild(auto);
      for (const column of view.columns) {
        const option = document.createElement("option");
        option.value = column.name;
        option.textContent = column.name;
        select.appendChild(option);
      }
      select.value = chosen ?? "";
      select.addEventListener("change", () => onPick(select.value));
      wrap.appendChild(select);
      return wrap;
    };

    const save = async (patch: { urlColumn?: string; annotationColumn?: string }): Promise<void> => {
      const latest = await loadPreferences();
      const entry = { ...(latest.viewColumns[view.id] ?? {}), ...patch };
      // An empty string means "automatic" — store it as absence, not as a blank override.
      const cleaned: { urlColumn?: string; annotationColumn?: string } = {};
      if (entry.urlColumn !== undefined && entry.urlColumn !== "") cleaned.urlColumn = entry.urlColumn;
      if (entry.annotationColumn !== undefined && entry.annotationColumn !== "") cleaned.annotationColumn = entry.annotationColumn;
      const next = { ...latest.viewColumns };
      if (cleaned.urlColumn === undefined && cleaned.annotationColumn === undefined) delete next[view.id];
      else next[view.id] = cleaned;
      await savePreferences({ viewColumns: next });
      status("Saved.", "ok");
    };

    block.appendChild(picker("URL column", current.urlColumn, (v) => void save({ urlColumn: v })));
    block.appendChild(picker("Annotations column", current.annotationColumn, (v) => void save({ annotationColumn: v })));
    host.appendChild(block);
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
  byId<HTMLInputElement>("annotations").checked = prefs.annotations;
  byId<HTMLSelectElement>("annotationView").value = prefs.annotationViewId;
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
  bind("annotationView", () => ({ annotationViewId: byId<HTMLSelectElement>("annotationView").value }));
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
      void drawViewColumns();
    });
  });
}

/** One pane at a time, the way the plugin's own settings work. */
function wirePanes(): void {
  const tabs = Array.from(document.querySelectorAll(".opt-tab"));
  for (const tab of tabs) {
    tab.addEventListener("click", () => {
      const wanted = tab.getAttribute("data-pane") ?? "connection";
      for (const other of tabs) other.classList.toggle("active", other === tab);
      for (const pane of Array.from(document.querySelectorAll(".opt-pane"))) {
        (pane as HTMLElement).hidden = pane.id !== `pane-${wanted}`;
      }
    });
  }
}

/**
 * The sidebar, made ready in one tick.
 *
 * All the sidebar actually *needs* is the page-reading permission; the rest of making it work is knowing
 * where your browser hides it. So the checkbox requests the permission (from the click, before any await —
 * the gesture rule), and success reveals the where-to-find-it instructions.
 */
/** The Zotero pane: a toggle, an honest status line, and the collection saves land in. */
function wireZotero(): void {
  const box = byId<HTMLInputElement>("zoteroOn");
  const statusLine = byId("zoteroStatus");
  const picker = byId<HTMLSelectElement>("zoteroCollection");

  const probe = async (): Promise<void> => {
    statusLine.textContent = "Looking for Zotero…";
    const found = await zoteroStatus();
    if (!found.running) {
      statusLine.textContent = "Zotero isn't reachable. Start it, then reopen this page.";
      return;
    }
    statusLine.textContent = found.searchable
      ? "Zotero found — saving and searching both available."
      : "Zotero found — saving works; searching needs Zotero 7 or newer.";
    if (found.searchable) {
      const collections = await zoteroCollections();
      const keep = picker.value;
      picker.replaceChildren();
      const blank = document.createElement("option");
      blank.value = "";
      blank.textContent = "— Zotero's currently selected collection —";
      picker.appendChild(blank);
      for (const c of collections) {
        const option = document.createElement("option");
        option.value = c.key;
        option.textContent = `${"\u2003".repeat(c.depth)}${c.name}`;
        picker.appendChild(option);
      }
      picker.value = keep;
      void loadPreferences().then((prefs) => {
        picker.value = prefs.zoteroCollectionKey;
      });
    }
  };

  void loadPreferences().then((prefs) => {
    box.checked = prefs.zotero;
    if (prefs.zotero) void probe();
  });

  box.addEventListener("change", () => {
    void (async () => {
      await savePreferences({ zotero: box.checked });
      if (box.checked) {
        await probe();
        status("Zotero integration is on.", "ok");
      } else {
        statusLine.textContent = "";
        status("Zotero integration is off.", "info");
      }
    })();
  });

  picker.addEventListener("change", () => {
    void savePreferences({ zoteroCollectionKey: picker.value }).then(() => status("Saved.", "ok"));
  });
}

function wireSidebarSetup(): void {
  const box = byId<HTMLInputElement>("sidebarSetup");
  const steps = byId("sidebarSteps");

  void hasPageAccess().then((held) => {
    box.checked = held;
    steps.hidden = !held;
  });

  box.addEventListener("change", () => {
    if (!box.checked) {
      // Permissions can't be dropped from here; unticking just hides the instructions.
      steps.hidden = true;
      status("The sidebar keeps working; permissions can be removed in your browser's extension settings.", "info");
      return;
    }
    const pending = requestPageAccess();
    void (async () => {
      const granted = (await pending) || (await hasPageAccess());
      if (!granted) {
        box.checked = false;
        status("The sidebar needs permission to read pages; nothing was changed.", "error");
        return;
      }
      steps.hidden = false;
      status("The sidebar is ready — see below for where to open it.", "ok");
    })();
  });
}

document.addEventListener("DOMContentLoaded", () => {
  wirePanes();
  wireSidebarSetup();
  wireZotero();
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

  byId("annotations").addEventListener("change", () => {
    const box = byId<HTMLInputElement>("annotations");

    if (!box.checked) {
      void (async () => {
        await savePreferences({ annotations: false });
        await unregisterAnnotator();
        status("Highlighting on pages is off.", "info");
      })();
      return;
    }

    // Requested before anything is awaited — the same gesture rule the search toggle learned the hard way.
    const pending = requestPageAccess();

    void (async () => {
      const granted = (await pending) || (await hasPageAccess());
      if (!granted) {
        box.checked = false;
        status("Highlighting needs permission to read pages; nothing was changed.", "error");
        return;
      }
      await savePreferences({ annotations: true });
      await registerAnnotator();
      // Reach tabs already open, so highlights on the page you're looking at appear without a reload.
      await injectAnnotatorIntoOpenTabs();
      status("Select text on any page to highlight it. Existing pages will show their highlights now.", "ok");
    })();
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
