import { extractFields, findDoi, type PageSnapshot } from "../../../shared/extract";
import type { CaptureRequest, SchemaResponse, SchemaView } from "../../../shared/protocol";
import { BridgeError, capture, fetchSchema, loadConnection, lookup, annotationsFor } from "./bridge-client";
import { mountSearch } from "./search-panel";
import { mountRows } from "./rows-panel";
import { mountNote } from "./note-panel";
import { mountEdit } from "./edit-panel";
import { mountAnnotations } from "./annotations-panel";
import { mountDashboard } from "./dashboard-panel";
import { loadPreferences, savePreferences, type Preferences } from "./preferences";
import { matchRule, mergeTags } from "../../../shared/rules";
import { hasPageAccess, requestPageAccess } from "./page-access";
import { pluginIsCurrent, outdatedPluginMessage } from "./version";
import { mountStatusCard } from "./status-card";
import { prefillFor } from "./prefill";
import { cached, remember, forget, statusKey, SCHEMA_KEY } from "./answer-cache";
import { promote } from "./bridge-client";
import { queueCapture } from "./queue-store";

/**
 * The capture window.
 *
 * The form here is built from the vault's own schema, which is the thing that makes this different from a
 * clipper. Nothing about any particular view is hard-coded: the columns, their types and the values a choice
 * column already uses all arrive from `/schema`, so a view created five minutes ago gets a correct form with
 * nothing written by hand — no template, no JSON, no mapping file.
 */

interface Tab {
  readonly id?: number;
  readonly url?: string;
}
interface ChromeLike {
  tabs: {
    query(q: object): Promise<Tab[]>;
    sendMessage(tabId: number, message: unknown): Promise<unknown>;
  };
  scripting: { executeScript(o: object): Promise<{ result?: unknown }[]> };
  runtime: { openOptionsPage(): void; onMessage?: { addListener(fn: (message: unknown) => void): void } };
  storage: { local: { get(k: string[] | null): Promise<Record<string, unknown>>; set(i: Record<string, unknown>): Promise<void> } };
}
const browserApi = (): ChromeLike => {
  const g = globalThis as unknown as { browser?: ChromeLike; chrome?: ChromeLike };
  const found = g.browser ?? g.chrome;
  if (!found) throw new Error("No extension API available.");
  return found;
};

const root = (): HTMLElement => document.getElementById("app") as HTMLElement;

function show(message: string, kind: "info" | "error" | "ok" = "info"): void {
  const el = document.getElementById("status");
  if (el === null) return;
  el.textContent = message;
  el.className = `status ${kind}`;
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

let snapshot: PageSnapshot | null = null;
let schema: SchemaResponse | null = null;
let current: SchemaView | null = null;
/** Whatever the reader had selected when they opened the popup. */
let selectionText = "";

/** Which surface this is. The sidebar persists and can afford the whole vault; the popup is a glance. */
export type SurfaceMode = "popup" | "sidebar";
let mode: SurfaceMode = "popup";
let prefs: Preferences | null = null;

/** The columns a highlight lands in by default, matched when no per-view column is declared. */
const ANNOTATION_COLUMN_GUESS = ["annotations", "highlights", "quotes", "notes"];

/**
 * Which column in a view holds highlight text.
 *
 * The person's per-view declaration wins when the named column still exists; otherwise the same guess the
 * plugin uses. Returned so the capture form can show that column as a live mirror of the page's highlights
 * rather than an editable field — because highlights are managed by the annotator, not by Save to vault.
 */
function annotationColumnName(view: SchemaView): string | null {
  const declared = prefs?.viewColumns[view.id]?.annotationColumn ?? "";
  if (declared !== "" && view.columns.some((c) => c.name.toLowerCase() === declared.toLowerCase())) {
    return view.columns.find((c) => c.name.toLowerCase() === declared.toLowerCase())?.name ?? null;
  }
  for (const wanted of ANNOTATION_COLUMN_GUESS) {
    const found = view.columns.find((c) => c.name.trim().toLowerCase() === wanted);
    if (found !== undefined) return found.name;
  }
  return null;
}

/** One line of an annotation's text, the way the vault composes the row cell. */
function annotationLine(exact: string, note: string): string {
  const quote = exact.replace(/\s+/g, " ").trim();
  const trimmedNote = note.replace(/\s+/g, " ").trim();
  return trimmedNote === "" ? `==${quote}==` : `==${quote}== — ${trimmedNote}`;
}

/** Fill the annotation mirror (if the current form has one) from the page's live highlights. */
async function refreshAnnotationMirror(): Promise<void> {
  const mirror = document.querySelector("[data-annotation-mirror]");
  if (!(mirror instanceof HTMLElement) || snapshot === null) return;
  try {
    const connection = await loadConnection();
    const { annotations } = await annotationsFor(connection, { url: snapshot.url });
    if (annotations.length === 0) {
      mirror.textContent = "No highlights yet — select text on the page to add some.";
      mirror.classList.add("mirror-empty");
      return;
    }
    mirror.classList.remove("mirror-empty");
    mirror.replaceChildren();
    for (const annotation of annotations) {
      const line = annotationLine(annotation.anchor.exact, annotation.note ?? "");
      mirror.appendChild(el("div", { class: "mirror-line" }, line));
    }
  } catch {
    mirror.textContent = "Couldn't read this page's highlights.";
  }
}
/** Redraws the status card; set once the card exists. The card answers "what's here?", so anything that
 * changes what's here — a saved row, a saved note — must call this or the card lies about the new state. */
let refreshStatusCard: (() => void) | null = null;
/** Re-mounts the highlight list; set once it exists, called when a highlight lands while open. */
let remountAnnotations: (() => void) | null = null;

/** Ask the active tab for its page snapshot. */
async function readPage(): Promise<PageSnapshot> {
  const api = browserApi();
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  if (tab?.id === undefined) throw new Error("No active tab.");

  // Inject the content script, then ask it. Reading a file's return value doesn't work — a bundled file is
  // wrapped in an IIFE whose value never comes back — and the extraction libraries are far too large to
  // serialize as an injected function, so messaging is the only route that supports both.
  await api.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
  const reply = (await api.tabs.sendMessage(tab.id, { type: "kvs-read-page" })) as
    | { ok: true; snapshot: PageSnapshot }
    | { ok: false; error: string }
    | undefined;
  if (reply === undefined) throw new Error("The page didn't answer.");
  if (!reply.ok) throw new Error(reply.error);
  return reply.snapshot;
}

/**
 * Draw the form for a view.
 *
 * Values already extracted from the page are pre-filled, a choice column becomes a real dropdown of the
 * terms that column already uses, and anything the page offered that no column wanted is listed rather than
 * silently discarded — the person can see what was left behind.
 */
function renderForm(view: SchemaView, prefill: Record<string, string>, unmatched: readonly string[]): void {
  const form = document.getElementById("form") as HTMLElement;
  form.replaceChildren();

  const annotationCol = annotationColumnName(view);

  for (const column of view.columns) {
    const field = el("div", { class: "field" });
    field.appendChild(el("label", { for: `f-${column.name}` }, column.name));

    // The annotation column is managed by highlighting, not by this form — so it's shown as a live,
    // read-only mirror of the page's highlights, carrying `data-annotation-mirror` (not `data-column`) so
    // Save to vault leaves it alone. It fills itself now and refreshes whenever a highlight lands.
    if (annotationCol !== null && column.name === annotationCol) {
      const mirror = el("div", { class: "annotation-mirror", "data-annotation-mirror": column.name });
      mirror.textContent = "Loading highlights…";
      field.appendChild(mirror);
      field.appendChild(el("p", { class: "hint mirror-hint" }, "Managed by highlighting — select text on the page to add."));
      form.appendChild(field);
      void refreshAnnotationMirror();
      continue;
    }

    if (column.options !== undefined && column.options.length > 0) {
      const select = el("select", { id: `f-${column.name}`, "data-column": column.name });
      select.appendChild(el("option", { value: "" }, "—"));
      for (const option of column.options) {
        const item = el("option", { value: option }, option);
        if ((prefill[column.name] ?? "").toLowerCase() === option.toLowerCase()) item.setAttribute("selected", "");
        select.appendChild(item);
      }
      field.appendChild(select);
    } else {
      const long = column.typeId === "markdown" || column.name.toLowerCase().includes("description");
      const input = long
        ? el("textarea", { id: `f-${column.name}`, "data-column": column.name, rows: "3" })
        : el("input", {
            id: `f-${column.name}`,
            "data-column": column.name,
            type: column.typeId === "number" ? "number" : column.typeId === "date" ? "date" : "text",
          });
      const typed = input as HTMLInputElement | HTMLTextAreaElement;
      const suggestion = prefill[column.name] ?? "";

      // A suggestion is shown, not committed: faded in the field, accepted with a click or by typing over
      // it, dismissed with a click. The old behaviour dropped the guessed value straight into the field as
      // though the person had entered it — so a wrong guess had to be noticed and deleted, and a right one
      // gave no signal it was a guess at all. Showing it as a proposal makes the guess honest.
      if (suggestion !== "") {
        typed.placeholder = suggestion;
        typed.classList.add("has-suggestion");
        const accept = (): void => {
          if (typed.value.trim() === "") {
            typed.value = suggestion;
            typed.classList.remove("has-suggestion");
            chip.classList.add("hidden");
          }
        };
        typed.addEventListener("focus", () => {
          // Focusing to type over the suggestion clears the faded hint; focusing and leaving keeps it.
        });
        typed.addEventListener("input", () => {
          chip.classList.toggle("hidden", typed.value.trim() !== "" || suggestion === "");
        });

        const row = el("div", { class: "with-action" });
        row.appendChild(typed);
        const chip = el("span", { class: "suggest-chip" });
        const take = el("button", { class: "mini suggest-take", type: "button", title: "Use this suggestion" }, "✓");
        take.addEventListener("click", accept);
        const drop = el("button", { class: "mini suggest-drop", type: "button", title: "Dismiss this suggestion" }, "✕");
        drop.addEventListener("click", () => {
          typed.placeholder = "";
          typed.classList.remove("has-suggestion");
          chip.classList.add("hidden");
        });
        chip.append(take, drop);
        row.appendChild(chip);
        field.appendChild(row);
      } else if (selectionText !== "") {
        const row = el("div", { class: "with-action" });
        row.appendChild(typed);
        const use = el("button", { class: "mini", type: "button", title: "Use the text you selected" }, "Use selection");
        use.addEventListener("click", () => {
          const existing = typed.value.trim();
          typed.value = existing === "" ? selectionText : `${existing}\n${selectionText}`;
        });
        row.appendChild(use);
        field.appendChild(row);
      } else {
        field.appendChild(typed);
      }
    }
    form.appendChild(field);
  }

  if (unmatched.length > 0) {
    form.appendChild(
      el("p", { class: "hint" }, `Also found, with no matching column: ${unmatched.join(", ")}`),
    );
  }
}

/** Pre-fill by asking the vault what each field means, rather than guessing here. */


async function warnIfAlreadySaved(): Promise<void> {
  if (snapshot === null) return;
  try {
    const connection = await loadConnection();
    const doi = findDoi(snapshot, extractFields(snapshot));
    const result = await lookup(connection, { url: snapshot.url, ...(doi !== null ? { doi } : {}) });
    const first = result.matches[0];
    if (first !== undefined) {
      show(`Already in “${first.viewName}” — matched on ${first.on}. You can still capture it.`, "info");
      // Revisiting something you have is the moment you want to change it, not file it again.
      const editHost = document.getElementById("tab-edit");
      const editTab = document.querySelector('[data-tab="edit"]');
      if (editHost !== null && editTab !== null && first.rowRef !== undefined) {
        mountEdit(first, {
          host: editHost,
          view: () => schema?.views.find((v) => v.id === first.viewId) ?? current,
          vaultName: () => schema?.vault ?? "",
          setStatus: (message, kind) => show(message, kind),
        });
        editTab.classList.remove("hidden");
      }
    }
  } catch {
    // A failed check must never block a capture; the worst case is a duplicate the person can remove.
  }
}

/**
 * Explain what the sidebar needs, and offer it in one click.
 *
 * The button matters as much as the words: the request has to be made from a real click, so it can't be
 * done automatically on open even if that would be more convenient.
 */
function offerPageAccess(): void {
  show("The sidebar needs permission to read pages.", "error");
  const explain = el(
    "p",
    { class: "hint" },
    "The popup borrows this from the toolbar button each time you click it. The sidebar stays open across pages, so it has to ask once.",
  );
  const button = el("button", { class: "primary" }, "Allow reading pages");
  button.addEventListener("click", () => {
    const pending = requestPageAccess();
    void pending.then((granted) => {
      if (!granted) {
        show("Permission wasn't granted, so the sidebar can't read this page.", "error");
        return;
      }
      // Start over now that it can actually read anything.
      root().replaceChildren();
      void start();
    });
  });
  root().append(explain, button);
}

async function start(): Promise<void> {
  const connection = await loadConnection();
  if (connection.token === null) {
    show("Not paired with a vault yet.", "error");
    const button = el("button", { class: "primary" }, "Open settings to pair");
    button.addEventListener("click", () => browserApi().runtime.openOptionsPage());
    root().appendChild(button);
    return;
  }

  try {
    snapshot = await readPage();
  } catch (error) {
    // The sidebar has no `activeTab` to fall back on, so a failure here is usually a missing permission
    // rather than an unreadable page. Saying so, with the way to fix it, beats a dead end.
    if (mode === "sidebar" && !(await hasPageAccess())) {
      offerPageAccess();
      return;
    }
    const detail = error instanceof Error ? error.message : "";
    show(
      detail.includes("answer")
        ? "This page didn't respond. Try reloading it, then reopen this."
        : "Couldn't read this page. Some browser pages can't be captured.",
      "error",
    );
    return;
  }

  try {
    // Schema straight from cache when fresh — the view list changes rarely, and the settings page has an
    // explicit refresh for when it does. This is most of the popup's opening wait.
    const priorSchema = await cached<SchemaResponse>(SCHEMA_KEY);
    if (priorSchema !== null && priorSchema.fresh) {
      schema = priorSchema.value;
    } else {
      schema = await fetchSchema(connection);
      void remember(SCHEMA_KEY, schema);
    }
  } catch (error) {
    const message = error instanceof BridgeError ? error.message : "Couldn't reach your vault.";
    show(message, "error");
    return;
  }

  // The mismatch that cost three sessions: extension and plugin ship separately, and an old plugin makes
  // every new endpoint 404 — highlights vanish, captures follow old rules, and nothing says why. Now the
  // vault names its version and an out-of-date one is called out before anything else can confuse.
  if (!pluginIsCurrent(schema.pluginVersion)) {
    show(outdatedPluginMessage(schema.pluginVersion), "error");
    return;
  }

  const writable = schema.views.filter((v) => v.capture.writable);
  if (writable.length === 0) {
    // Three different situations used to share one sentence here, which made this undiagnosable: the wrong
    // vault answering, writing turned off, and views without capture targets all look identical unless the
    // message says which vault it reached and what each view said.
    show(`Connected to “${schema.vault}”, but no view there can receive captures.`, "error");
    const detail = el("div", { class: "hint" });
    if (schema.views.length === 0) {
      detail.appendChild(
        el("p", {}, "That vault exposes no views to the browser at all — check “Views the browser may see” in the plugin's Browser bridge settings."),
      );
    } else {
      for (const view of schema.views) {
        detail.appendChild(el("p", {}, `${view.name}: ${view.capture.reason ?? "can't receive captures."}`));
      }
    }
    detail.appendChild(el("p", {}, "If that isn't the vault you meant, check the Connection tab in the companion's settings."));
    root().appendChild(detail);
    return;
  }

  const picker = document.getElementById("view") as HTMLSelectElement;
  picker.replaceChildren();
  for (const view of writable) picker.appendChild(el("option", { value: view.id }, view.name));

  // Which view this page should go to, in order of how deliberately it was chosen: a rule written for this
  // site, then whatever was used last, then a stated default. Each only applies if that view still exists,
  // so a deleted or renamed view degrades to the next choice rather than to an error.
  prefs = await loadPreferences();
  const rule = matchRule(prefs.rules, snapshot.url);
  const preferred = [rule?.viewId, prefs.rememberLastView ? prefs.lastViewId : "", prefs.defaultViewId]
    .filter((id): id is string => typeof id === "string" && id !== "")
    .find((id) => writable.some((v) => v.id === id));
  if (preferred !== undefined) picker.value = preferred;

  if (rule !== null) {
    const named = writable.find((v) => v.id === rule.viewId);
    if (named !== undefined) show(`Following your rule for this site — ${named.name}`, "info");
  }

  // Remember the choice, so the next capture from anywhere starts where this one left off.
  picker.addEventListener("change", () => {
    void savePreferences({ lastViewId: picker.value });
  });

  selectionText = snapshot.selection ?? "";
  let fields = extractFields(snapshot);
  // A "created"/"captured" column is about when this entry was made, not anything on the page, so it's
  // supplied here as today's date — offered as a suggestion like everything else, never forced.
  const today = new Date().toISOString().slice(0, 10);
  if (!fields.some((f) => f.key === "created")) fields = [...fields, { key: "created", value: today }];
  const draw = (): void => {
    current = writable.find((v) => v.id === picker.value) ?? writable[0] ?? null;
    if (current === null) return;
    const prefill = prefillFor(current, fields);
    const claimed = new Set(Object.values(prefill));
    const unmatched = fields.filter((f) => !claimed.has(f.value) && f.key !== "url").map((f) => f.key);
    renderForm(current, prefill, unmatched.slice(0, 6));
  };
  picker.addEventListener("change", draw);
  draw();

  // The popup reads the page at the moment it opens — which is right after you selected something. The
  // sidebar started long ago: whatever you've selected or navigated to since isn't in its snapshot, which
  // is why it never offered "Use selection" and went stale after navigation. Re-reading is one click.
  if (mode === "sidebar") {
    const controls = document.getElementById("controls");
    const refresh = el("button", { class: "mini", type: "button", title: "Read the page again — picks up your current selection and the page you're on now" }, "↻ Re-read page");
    refresh.addEventListener("click", () => {
      void (async () => {
        try {
          const fresh = await readPage();
          snapshot = fresh;
          selectionText = fresh.selection ?? "";
          fields = extractFields(fresh);
          if (!fields.some((f) => f.key === "created")) fields = [...fields, { key: "created", value: today }];
          draw();
          show(selectionText !== "" ? "Re-read — your selection is available below." : "Re-read this page.", "ok");
        } catch {
          show("Couldn't re-read the page. Try reloading it.", "error");
        }
      })();
    });
    controls?.insertBefore(refresh, controls.firstChild);
  }

  // Status first, machinery second: the card answers "do I already have this?" before any form appears.
  // Adding reveals the controls; everything on the card that exists gets its actions, and nothing shows a
  // button for a state it isn't in.
  const controls = document.getElementById("controls");
  const destination = document.getElementById("destination");
  const cardHost = el("div", {});
  root().appendChild(cardHost);

  // One compact line under the card: the picker carries "into this view…" and the buttons finish the
  // sentence. Always present when anything is writable — adding shouldn't hide behind the card's state.
  destination?.classList.remove("hidden");
  const addRowButton = document.getElementById("addRow");
  const addPageButton = document.getElementById("addPage");

  const revealAdd = (shape: "row" | "note"): void => {
    addRowButton?.classList.toggle("active", shape === "row");
    addPageButton?.classList.toggle("active", shape === "note");
    if (shape === "note") {
      // Forced visible: a page without an article is still a page — properties and selection make a
      // perfectly good note, and a button that dead-clicks a hidden tab reads as broken because it is.
      const noteTab = document.querySelector('[data-tab="note"]');
      noteTab?.classList.remove("hidden");
      if (noteHost !== null && snapshot !== null) {
        mountNote(snapshot, {
          host: noteHost,
          view: () => current,
          setStatus: (message, kind) => show(message, kind),
          onSaved: () => void forget([statusKey(snapshot?.url ?? "")]).then(() => refreshStatusCard?.()),
        });
      }
      (noteTab as HTMLElement | null)?.click();
      controls?.classList.add("hidden");
      return;
    }
    (document.querySelector('[data-tab="capture"]') as HTMLElement | null)?.click();
    controls?.classList.remove("hidden");
    // The form is the point of the click; land the cursor in it.
    window.setTimeout(() => {
      (document.querySelector("#form [data-column]") as HTMLElement | null)?.focus();
    }, 30);
  };
  addRowButton?.addEventListener("click", () => revealAdd("row"));
  addPageButton?.addEventListener("click", () => revealAdd("note"));
  const drawCard = (): void => {
    const doi = fields.find((f) => f.key.trim().toLowerCase() === "doi")?.value ?? "";
    // A DOI is the stabler identity for a paper — the same work sits behind many publisher URLs. When the
    // page has one, matching leads with it, so capturing the RSC page and the journal page recognise each
    // other. Passed to the card, which passes it to lookup.
    void mountStatusCard(
      cardHost,
      snapshot?.url ?? "",
      schema?.vault ?? "",
      schema as SchemaResponse,
      {
        onAdd: revealAdd,
        onEdit: () => {
          (document.querySelector('[data-tab="edit"]') as HTMLElement | null)?.click();
        },
        refresh: drawCard,
        setStatus: (message, kind) => show(message, kind),
      },
      {
        title: snapshot?.title ?? "",
        url: snapshot?.url ?? "",
        ...(doi !== "" ? { doi } : {}),
        ...(snapshot?.excerpt !== undefined && snapshot.excerpt !== "" ? { abstract: snapshot.excerpt } : {}),
      },
    );
  };
  drawCard();
  refreshStatusCard = drawCard;

  // "Create its note" buttons on the card promote through the bridge; delegated, since the card redraws.
  cardHost.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const viewId = target.getAttribute("data-promote");
    if (viewId === null) return;
    void (async () => {
      try {
        const connection = await loadConnection();
        const looked = await lookup(connection, { url: snapshot?.url ?? "" });
        const match = looked.matches.find((m) => m.viewId === viewId);
        if (match?.rowRef === undefined) return;
        const result = await promote(connection, { viewId, rowRef: match.rowRef });
        show(
          result.ok
            ? result.created === true
              ? `Note created: ${result.path ?? ""}`
              : `Found its existing note: ${result.path ?? ""}`
            : (result.reason ?? "Couldn't create the note."),
          result.ok ? "ok" : "error",
        );
        drawCard();
      } catch (error) {
        show(error instanceof Error ? error.message : "Couldn't create the note.", "error");
      }
    })();
  });

  // The rows tab only appears when the page actually holds a set of rows, so it never advertises a
  // capability this particular page can't offer.
  // A view whose target is note-shaped gets the note panel instead of the column form; that shape was
  // reachable before but produced a note with no body, which looked like it had worked.
  const noteHost = document.getElementById("tab-note");
  const noteTab = document.querySelector('[data-tab="note"]');
  const syncShape = (): void => {
    const prefersNote = current?.capture.shape === "note";
    // Offered whenever the page has an article to keep — not only for views someone already configured as
    // note-shaped. Keeping a whole page is the thing every clipper does, and it was previously unreachable
    // for anyone whose views were all row-shaped.
    const canNote = (snapshot?.article?.markdown ?? "") !== "" || prefersNote;
    noteTab?.classList.toggle("hidden", !canNote);
    document.getElementById("controls")?.classList.toggle("hidden", prefersNote);
    if (canNote && noteHost !== null) {
      mountNote(snapshot as PageSnapshot, {
        host: noteHost,
        view: () => current,
        setStatus: (message, kind) => show(message, kind),
        onSaved: () => void forget([statusKey(snapshot?.url ?? "")]).then(() => refreshStatusCard?.()),
      });
      // Jump straight there only when the view is meant for notes; otherwise it's an option, not the plan.
      if (prefersNote) (noteTab as HTMLElement | null)?.click();
    }
  };
  picker.addEventListener("change", syncShape);
  syncShape();

  const rowsHost = document.getElementById("tab-rows");
  const rowsTab = document.querySelector('[data-tab="rows"]');
  if (rowsHost !== null && rowsTab !== null) {
    const found = mountRows(snapshot, {
      host: rowsHost,
      view: () => current,
      setStatus: (message, kind) => show(message, kind),
    });
    rowsTab.classList.toggle("hidden", !found);
  }

  // The Highlight tab is a place to review this page's highlights — so it appears only when there are
  // some. Highlights are made on the page itself; a tab offering to manage what doesn't exist is clutter.
  // The panel still mounts (cheap, and needed the moment a highlight lands); only the tab waits.
  const annotHost = document.getElementById("tab-annotate");
  if (annotHost !== null && snapshot !== null) {
    const mountList = (): void => {
      mountAnnotations(snapshot as PageSnapshot, {
        host: annotHost,
        view: () => current,
        setStatus: (message, kind) => show(message, kind),
        onCount: (count) => {
          document.querySelector('[data-tab="annotate"]')?.classList.toggle("hidden", count === 0);
        },
      });
    };
    mountList();
    remountAnnotations = mountList;
  }

  // A highlight made on the page (while the sidebar is open) changes what's here: the status card, the
  // highlight list, and the annotation-column mirror all reflect the vault, so all three are refreshed
  // when the background reports a change for this page. The popup never receives this — clicking the page
  // to annotate closes it — which is exactly why the sidebar is the surface that needs it.
  const messaging = browserApi().runtime.onMessage;
  if (messaging !== undefined) {
    messaging.addListener((message: unknown) => {
      const changed = message as { type?: string; url?: string } | null;
      if (changed?.type !== "kvs-annotation-changed") return;
      if (snapshot === null || changed.url !== snapshot.url) return;
      void forget([statusKey(snapshot.url)]).then(() => {
        refreshStatusCard?.();
        remountAnnotations?.();
        void refreshAnnotationMirror();
      });
    });
  }

  // Dashboards only in the sidebar. A popup closes on the first click elsewhere, which makes working
  // through a list of rows in one actively unpleasant.
  const dashHost = document.getElementById("tab-dashboard");
  if (mode === "sidebar" && dashHost !== null) {
    mountDashboard({
      host: dashHost,
      schema: () => schema,
      setStatus: (message, kind) => show(message, kind),
    });
    document.querySelector('[data-tab="dashboard"]')?.classList.remove("hidden");
  }

  show(`Capturing from ${new URL(snapshot.url).hostname}`);
  void warnIfAlreadySaved();
}

/** Tags a capture should carry regardless of the page: a site rule's, plus any set for everything. */
export function extraTags(url: string): string {
  if (prefs === null) return "";
  const rule = matchRule(prefs.rules, url);
  return mergeTags(prefs.alwaysTags, rule?.tags);
}

function collect(): CaptureRequest | null {
  if (current === null || snapshot === null) return null;
  const inputs = Array.from(document.querySelectorAll("[data-column]"));
  const fields = inputs
    .map((node) => {
      const field = node as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
      const typedValue = field.value.trim();
      // An untouched field still showing its suggestion counts as accepting it — the faded value is a
      // proposal the person left standing, and saving should honour what they saw. A field they cleared
      // has an empty placeholder, so nothing is contributed.
      const suggested = "placeholder" in field ? (field as HTMLInputElement).placeholder.trim() : "";
      return {
        key: node.getAttribute("data-column") ?? "",
        value: typedValue !== "" ? typedValue : suggested,
      };
    })
    .filter((f) => f.key !== "" && f.value !== "");
  if (fields.length === 0) return null;

  // Tags from a site rule and from the always-add setting, folded into whatever the form already has so
  // neither overwrites the other.
  const extra = extraTags(snapshot.url);
  if (extra !== "") {
    const tagField = fields.find((f) => f.key.toLowerCase() === "tags");
    if (tagField === undefined) fields.push({ key: "tags", value: extra });
    else tagField.value = mergeTags(tagField.value, extra);
  }
  return { viewId: current.id, fields, url: snapshot.url };
}

async function submit(): Promise<void> {
  const request = collect();
  if (request === null) {
    show("Nothing to capture — fill in at least one field.", "error");
    return;
  }
  const button = document.getElementById("save") as HTMLButtonElement;
  button.disabled = true;
  show("Saving…");

  try {
    const connection = await loadConnection();
    const result = await capture(connection, request);
    if (!result.ok) {
      show(result.reason ?? "Couldn't save that.", "error");
      button.disabled = false;
      return;
    }
    // A warning means it was written but won't be seen — worth more attention than a success, and the
    // popup must not close itself before it can be read.
    if (result.warning !== undefined) {
      show(result.warning, "error");
      button.disabled = false;
      void forget([statusKey(snapshot?.url ?? "")]).then(() => refreshStatusCard?.());
      return;
    }
    const notes: string[] = ["Saved"];
    if (result.createdTable === true) notes.push("(created the table)");
    if (result.duplicate !== undefined) notes.push(`· also matched an existing row on ${result.duplicate.on}`);
    show(notes.join(" "), "ok");
    void forget([statusKey(snapshot?.url ?? "")]).then(() => refreshStatusCard?.());
    if (mode === "popup") window.setTimeout(() => window.close(), 1200);
  } catch (error) {
    if (error instanceof BridgeError && error.offline) {
      // Obsidian isn't running. Hold onto it rather than losing what they meant to keep.
      await queueCapture(request);
      show("Your vault isn't reachable — saved to send when it is.", "info");
      window.setTimeout(() => window.close(), 1800);
      return;
    }
    show(error instanceof BridgeError ? error.message : "Couldn't save that.", "error");
    button.disabled = false;
  }
}

/**
 * Capture and search share one window.
 *
 * Both are the same question — "what does my vault know about this page?" — asked in two directions, so
 * splitting them across separate surfaces would only add a click.
 */
function wireTabs(): void {
  const buttons = Array.from(document.querySelectorAll("[data-tab]"));

  // Only panels that actually exist on this page.
  //
  // The popup has no dashboard, and registering one regardless put a null into this map — so the first tab
  // click threw as soon as the loop reached it. Everything registered after that point, which included
  // Search, was never shown and never mounted. A missing element must be absent from the map, not present
  // as nothing.
  const panels = new Map<string, HTMLElement>();
  for (const name of ["capture", "rows", "note", "edit", "annotate", "dashboard", "search"]) {
    const panel = document.getElementById(`tab-${name}`);
    if (panel !== null) panels.set(name, panel);
  }
  let searchMounted = false;

  for (const button of buttons) {
    button.addEventListener("click", () => {
      const wanted = button.getAttribute("data-tab") ?? "capture";
      if (!panels.has(wanted)) return;
      for (const other of buttons) other.classList.toggle("active", other === button);
      for (const [name, panel] of panels) panel.classList.toggle("hidden", name !== wanted);

      const searchHost = panels.get("search");
      if (wanted === "search" && !searchMounted && searchHost !== undefined) {
        searchMounted = true;
        mountSearch({
          host: searchHost,
          vaultName: () => schema?.vault ?? "",
          setStatus: (message, kind) => show(message, kind),
        });
      }
    });
  }
}

/**
 * Start a surface.
 *
 * Both the popup and the sidebar run this same code; what differs is which panels are worth showing and how
 * much room there is to show them. Sharing the implementation is what keeps the two from drifting into
 * subtly different behaviour for the same action.
 */
export function startSurface(surface: SurfaceMode): void {
  mode = surface;
  document.body.classList.add(`is-${surface}`);
  document.getElementById("save")?.addEventListener("click", () => void submit());
  document.getElementById("settings")?.addEventListener("click", () => browserApi().runtime.openOptionsPage());
  wireTabs();
  void applyDensity();
  void start();
}

/**
 * The popup's size is a preference.
 *
 * Some people want a glance and nothing more; others do most of their filing from it. One fixed size makes
 * one of those groups unhappy, and the cost of offering three is a class name.
 */
async function applyDensity(): Promise<void> {
  // The mode class is what every width and density rule keys on. It was never being added — the body
  // carried only `size-medium`, so `body.is-popup.size-*` matched nothing and the popup's width was
  // uncontrolled, which is why the size preference appeared to do nothing at all. Add both, always.
  document.body.classList.add(mode === "popup" ? "is-popup" : "is-sidebar");
  if (mode !== "popup") return;
  let size = "medium";
  try {
    const stored = await browserApi().storage.local.get(["popupSize"]);
    const value = stored["popupSize"];
    if (value === "small" || value === "medium" || value === "large") size = value;
  } catch {
    // Preference unreadable; the middle size is a reasonable thing to fall back on.
  }
  document.body.classList.add(`size-${size}`);
}
