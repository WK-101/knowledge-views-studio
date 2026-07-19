import { extractFields, findDoi, type PageSnapshot } from "../../shared/extract";
import type { CaptureRequest, SchemaResponse, SchemaView } from "../../shared/protocol";
import { BridgeError, capture, fetchSchema, loadConnection, lookup } from "./lib/bridge-client";
import { mountSearch } from "./lib/search-panel";
import { mountRows } from "./lib/rows-panel";
import { mountNote } from "./lib/note-panel";
import { queueCapture } from "./lib/queue-store";

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
  runtime: { openOptionsPage(): void };
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

  for (const column of view.columns) {
    const field = el("div", { class: "field" });
    field.appendChild(el("label", { for: `f-${column.name}` }, column.name));

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
      (input as HTMLInputElement | HTMLTextAreaElement).value = prefill[column.name] ?? "";

      // Highlight-to-field: with text selected on the page, any column can take it with one click. The
      // popup can't watch the page's selection live — opening the popup ends it — so the selection is
      // captured with the snapshot and offered here, which is the honest version of the same idea.
      if (selectionText !== "") {
        const row = el("div", { class: "with-action" });
        row.appendChild(input);
        const use = el("button", { class: "mini", type: "button", title: "Use the text you selected" }, "Use selection");
        use.addEventListener("click", () => {
          (input as HTMLInputElement | HTMLTextAreaElement).value = selectionText;
        });
        row.appendChild(use);
        field.appendChild(row);
      } else {
        field.appendChild(input);
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
function prefillFor(view: SchemaView, fields: readonly { key: string; value: string }[]): Record<string, string> {
  const out: Record<string, string> = {};
  const byKey = new Map(fields.map((f) => [f.key.toLowerCase(), f.value]));
  for (const column of view.columns) {
    const direct = byKey.get(column.name.toLowerCase());
    if (direct !== undefined) out[column.name] = direct;
  }
  return out;
}

async function warnIfAlreadySaved(): Promise<void> {
  if (snapshot === null) return;
  try {
    const connection = await loadConnection();
    const doi = findDoi(snapshot, extractFields(snapshot));
    const result = await lookup(connection, { url: snapshot.url, ...(doi !== null ? { doi } : {}) });
    if (result.matches.length > 0) {
      const first = result.matches[0];
      if (first !== undefined) {
        show(`Already in “${first.viewName}” — matched on ${first.on}. You can still capture it.`, "info");
      }
    }
  } catch {
    // A failed check must never block a capture; the worst case is a duplicate the person can remove.
  }
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
  } catch {
    show("Couldn't read this page. Some browser pages can't be captured.", "error");
    return;
  }

  try {
    schema = await fetchSchema(connection);
  } catch (error) {
    const message = error instanceof BridgeError ? error.message : "Couldn't reach your vault.";
    show(message, "error");
    return;
  }

  const writable = schema.views.filter((v) => v.capture.writable);
  if (writable.length === 0) {
    show("No view in this vault can receive captures yet. Set a capture target in a view's settings.", "error");
    return;
  }

  const picker = document.getElementById("view") as HTMLSelectElement;
  picker.replaceChildren();
  for (const view of writable) picker.appendChild(el("option", { value: view.id }, view.name));

  selectionText = snapshot.selection ?? "";
  const fields = extractFields(snapshot);
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

  document.getElementById("controls")?.classList.remove("hidden");

  // The rows tab only appears when the page actually holds a set of rows, so it never advertises a
  // capability this particular page can't offer.
  // A view whose target is note-shaped gets the note panel instead of the column form; that shape was
  // reachable before but produced a note with no body, which looked like it had worked.
  const noteHost = document.getElementById("tab-note");
  const noteTab = document.querySelector('[data-tab="note"]');
  const syncShape = (): void => {
    const isNote = current?.capture.shape === "note";
    noteTab?.classList.toggle("hidden", !isNote);
    document.getElementById("controls")?.classList.toggle("hidden", isNote);
    if (isNote && noteHost !== null) {
      mountNote(snapshot as PageSnapshot, {
        host: noteHost,
        view: () => current,
        setStatus: (message, kind) => show(message, kind),
      });
      (noteTab as HTMLElement | null)?.click();
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

  show(`Capturing from ${new URL(snapshot.url).hostname}`);
  void warnIfAlreadySaved();
}

function collect(): CaptureRequest | null {
  if (current === null || snapshot === null) return null;
  const inputs = Array.from(document.querySelectorAll("[data-column]"));
  const fields = inputs
    .map((node) => ({
      key: node.getAttribute("data-column") ?? "",
      value: (node as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value.trim(),
    }))
    .filter((f) => f.key !== "" && f.value !== "");
  if (fields.length === 0) return null;
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
    const notes: string[] = ["Saved"];
    if (result.createdTable === true) notes.push("(created the table)");
    if (result.duplicate !== undefined) notes.push(`· also matched an existing row on ${result.duplicate.on}`);
    show(notes.join(" "), "ok");
    window.setTimeout(() => window.close(), 1200);
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
  const panels = new Map<string, HTMLElement>([
    ["capture", document.getElementById("tab-capture") as HTMLElement],
    ["rows", document.getElementById("tab-rows") as HTMLElement],
    ["note", document.getElementById("tab-note") as HTMLElement],
    ["search", document.getElementById("tab-search") as HTMLElement],
  ]);
  let searchMounted = false;

  for (const button of buttons) {
    button.addEventListener("click", () => {
      const wanted = button.getAttribute("data-tab") ?? "capture";
      for (const other of buttons) other.classList.toggle("active", other === button);
      for (const [name, panel] of panels) panel.classList.toggle("hidden", name !== wanted);
      if (wanted === "search" && !searchMounted) {
        searchMounted = true;
        mountSearch({
          host: panels.get("search") as HTMLElement,
          vaultName: () => schema?.vault ?? "",
          setStatus: (message, kind) => show(message, kind),
        });
      }
    });
  }
}

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("save")?.addEventListener("click", () => void submit());
  document.getElementById("settings")?.addEventListener("click", () => browserApi().runtime.openOptionsPage());
  wireTabs();
  void start();
});
