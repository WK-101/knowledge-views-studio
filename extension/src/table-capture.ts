/**
 * On-page table capture.
 *
 * A data table on a web page — a comparison, a bibliography, a leaderboard — is already a set of rows;
 * every clipper flattens it into one note because a note is all it can make. Here, hovering such a table
 * surfaces a small action to send its rows straight into a view, or copy it as Markdown, without opening
 * the popup at all. It's the same "capture as rows" idea as the popup's Rows tab, brought to where the
 * table is.
 *
 * Everything is additive and unobtrusive: the action appears only on hover, only on tables that actually
 * look like data, and lives in a closed shadow root so no page can restyle it and it can't leak out. When
 * the feature is off, this script does nothing at all.
 */

import { looksLikeData } from "../../shared/extract-rows";
import type { RawTable } from "../../shared/extract";
import { tableToMarkdown } from "../../shared/table-markdown";
import { inPageTheme } from "../../shared/in-page-ui";

interface Messenger {
  runtime: { sendMessage(message: unknown): Promise<unknown> };
  storage?: { local: { get(keys: string[]): Promise<Record<string, unknown>> } };
}
function api(): Messenger | null {
  const g = globalThis as unknown as { browser?: Messenger; chrome?: Messenger };
  return g.browser ?? g.chrome ?? null;
}

const dark = (): boolean => window.matchMedia("(prefers-color-scheme: dark)").matches;

/** Read one DOM table into the header/rows shape the rest of the pipeline speaks. */
function parseTable(table: HTMLTableElement): RawTable | null {
  const rowNodes = Array.from(table.querySelectorAll("tr"));
  if (rowNodes.length < 2) return null;
  const cellsOf = (tr: Element): string[] =>
    Array.from(tr.querySelectorAll("th, td")).map((c) => (c.textContent ?? "").replace(/\s+/g, " ").trim());
  const headerRow = rowNodes.find((tr) => tr.querySelector("th") !== null) ?? rowNodes[0];
  if (headerRow === undefined) return null;
  const headers = cellsOf(headerRow);
  if (headers.length === 0) return null;
  const rows = rowNodes.filter((tr) => tr !== headerRow).slice(0, 300).map(cellsOf);
  const caption = (table.querySelector("caption")?.textContent ?? "").replace(/\s+/g, " ").trim();
  return { headers, rows, ...(caption !== "" ? { caption } : {}) };
}

// ------------------------------------------------------------------ the pill

let shell: HTMLElement | null = null;
let shadow: ShadowRoot | null = null;
function ensureShell(): ShadowRoot {
  if (shadow !== null) return shadow;
  shell = document.createElement("div");
  shell.style.position = "absolute";
  shell.style.zIndex = "2147483645";
  shell.style.top = "0";
  shell.style.left = "0";
  shell.style.width = "0";
  shell.style.height = "0";
  shadow = shell.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = pillStyles();
  shadow.appendChild(style);
  document.documentElement.appendChild(shell);
  return shadow;
}

let pill: HTMLElement | null = null;
let pillFor: HTMLTableElement | null = null;
let overPill = false;

/** Position and show the action pill at a table's top-right corner. */
function showPill(table: HTMLTableElement, data: RawTable): void {
  const root = ensureShell();
  if (pill === null) {
    pill = document.createElement("div");
    pill.className = "kvs-tc-pill";
    pill.addEventListener("mouseenter", () => {
      overPill = true;
    });
    pill.addEventListener("mouseleave", () => {
      overPill = false;
      window.setTimeout(hidePillIfAway, 120);
    });
    root.appendChild(pill);
  }
  pillFor = table;
  pill.textContent = "";

  const label = document.createElement("span");
  label.className = "kvs-tc-label";
  label.textContent = `${String(data.rows.length)} rows`;
  pill.appendChild(label);

  const capture = document.createElement("button");
  capture.className = "kvs-tc-btn kvs-tc-primary";
  capture.type = "button";
  capture.textContent = "Capture";
  capture.title = "Save these rows into a view";
  capture.addEventListener("click", () => {
    void doCapture(data, capture);
  });
  pill.appendChild(capture);

  const copy = document.createElement("button");
  copy.className = "kvs-tc-btn";
  copy.type = "button";
  copy.textContent = "Copy";
  copy.title = "Copy the table as Markdown";
  copy.addEventListener("click", () => {
    void doCopy(data, copy);
  });
  pill.appendChild(copy);

  const rect = table.getBoundingClientRect();
  const top = window.scrollY + rect.top - 14;
  const right = window.scrollX + rect.right;
  pill.style.top = `${String(Math.max(window.scrollY + 4, top))}px`;
  pill.style.left = `${String(Math.max(4, right - pill.offsetWidth - 4))}px`;
  pill.style.display = "flex";
  // Left needs the real width, which only exists once shown; correct it on the next frame.
  window.requestAnimationFrame(() => {
    if (pill === null) return;
    pill.style.left = `${String(Math.max(4, window.scrollX + rect.right - pill.offsetWidth - 4))}px`;
  });
}

let overTable: HTMLTableElement | null = null;
function hidePillIfAway(): void {
  if (overPill || overTable !== null) return;
  if (pill !== null) pill.style.display = "none";
  pillFor = null;
}

async function doCopy(data: RawTable, button: HTMLButtonElement): Promise<void> {
  const md = tableToMarkdown(data.headers, data.rows);
  try {
    await navigator.clipboard.writeText(md);
    flash(button, "Copied ✓");
  } catch {
    flash(button, "Copy failed");
  }
}

async function doCapture(data: RawTable, button: HTMLButtonElement): Promise<void> {
  const messenger = api();
  if (messenger === null) return;
  const original = button.textContent ?? "Capture";
  button.textContent = "Saving…";
  button.setAttribute("disabled", "");
  try {
    const reply = (await messenger.runtime.sendMessage({
      type: "kvs-capture-table",
      url: location.href,
      headers: data.headers,
      rows: data.rows,
    })) as { ok?: boolean; written?: number; viewName?: string; reason?: string } | undefined;
    if (reply?.ok === true) {
      toast(`Saved ${String(reply.written ?? data.rows.length)} rows${reply.viewName !== undefined ? ` to ${reply.viewName}` : ""}.`);
      if (pill !== null) pill.style.display = "none";
    } else {
      toast(reply?.reason ?? "Couldn't capture this table.");
    }
  } catch {
    toast("Couldn't reach your vault — is Obsidian running?");
  } finally {
    button.textContent = original;
    button.removeAttribute("disabled");
  }
}

function flash(button: HTMLButtonElement, text: string): void {
  const original = button.textContent ?? "";
  button.textContent = text;
  window.setTimeout(() => {
    button.textContent = original;
  }, 1400);
}

/** A brief message near the bottom-left, for capture outcomes. */
function toast(message: string): void {
  const root = ensureShell();
  const box = document.createElement("div");
  box.className = "kvs-tc-toast";
  box.textContent = message;
  root.appendChild(box);
  window.setTimeout(() => {
    if (box.parentNode !== null) box.parentNode.removeChild(box);
  }, 5000);
}

// ----------------------------------------------------------------- behaviour

const qualified = new WeakSet<HTMLTableElement>();
const parsedCache = new WeakMap<HTMLTableElement, RawTable>();

/** Decide once whether a table is worth offering, and remember it. */
function qualifies(table: HTMLTableElement): RawTable | null {
  if (qualified.has(table)) return parsedCache.get(table) ?? null;
  const data = parseTable(table);
  // Not-data is remembered too (as absence) so we don't re-parse a layout table on every hover.
  if (data === null || !looksLikeData(data)) return null;
  qualified.add(table);
  parsedCache.set(table, data);
  return data;
}

function wire(): void {
  document.addEventListener(
    "mouseover",
    (event) => {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const table = target.closest("table");
      if (!(table instanceof HTMLTableElement)) return;
      if (table === pillFor) return;
      const data = qualifies(table);
      if (data === null) return;
      overTable = table;
      showPill(table, data);
    },
    true,
  );

  document.addEventListener(
    "mouseout",
    (event) => {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const table = target.closest("table");
      if (table === null) return;
      const to = (event as MouseEvent).relatedTarget;
      if (to instanceof Node && table.contains(to)) return;
      overTable = null;
      window.setTimeout(hidePillIfAway, 120);
    },
    true,
  );

  // Reposition on scroll while a pill is showing, so it tracks its table.
  window.addEventListener(
    "scroll",
    () => {
      if (pill === null || pillFor === null || pill.style.display === "none") return;
      const rect = pillFor.getBoundingClientRect();
      pill.style.top = `${String(Math.max(window.scrollY + 4, window.scrollY + rect.top - 14))}px`;
      pill.style.left = `${String(Math.max(4, window.scrollX + rect.right - pill.offsetWidth - 4))}px`;
    },
    { passive: true },
  );
}

function pillStyles(): string {
  const t = inPageTheme(dark());
  // Shares the annotator's design tokens so the two read as one tool. Kept deliberately small — a compact
  // chip at the table's corner, not a bar.
  return `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: ${t.font}; -webkit-font-smoothing: antialiased; }
    .kvs-tc-pill {
      position: absolute; display: none; align-items: center; gap: 4px; padding: 3px 4px;
      background: ${t.bg}; color: ${t.fg}; border: 1px solid ${t.line}; border-radius: ${t.radius};
      box-shadow: ${t.shadow}; font-size: 11.5px;
    }
    .kvs-tc-label { color: ${t.muted}; font-size: 10.5px; padding: 0 3px; }
    .kvs-tc-btn {
      border: 0; background: none; color: ${t.fg}; font: inherit; font-weight: 550;
      padding: 3px 8px; border-radius: ${t.radiusSmall}; cursor: pointer; transition: background 0.12s ease;
    }
    .kvs-tc-btn:hover { background: ${t.hover}; }
    .kvs-tc-btn[disabled] { opacity: 0.6; cursor: default; }
    .kvs-tc-primary { background: ${t.accent}; color: ${t.accentInk}; }
    .kvs-tc-primary:hover { background: ${t.accentHover}; }
    .kvs-tc-toast {
      position: fixed; left: 16px; bottom: 16px; max-width: 320px; padding: 10px 13px;
      background: ${t.bg}; color: ${t.fg}; border: 1px solid ${t.line}; border-radius: ${t.radius};
      box-shadow: ${t.shadow}; font-size: 12.5px; line-height: 1.45;
    }
  `;
}

// ---------------------------------------------------------------------- init

const marker = "__kvsTableCaptureReady";
const scope = window as unknown as Record<string, boolean>;
if (scope[marker] !== true) {
  scope[marker] = true;
  // Respect the toggle: read it once, and only wire up if the feature is on. (Registration already gates
  // this on most navigations; the read covers the injected-into-open-tabs case.)
  const messenger = api();
  const storage = messenger?.storage;
  if (storage !== undefined) {
    void storage.local
      .get(["preferences"])
      .then((stored) => {
        const prefs = stored["preferences"] as { tableCapture?: boolean } | undefined;
        // This script is only registered when the feature is on, so the check is a secondary guard for the
        // just-injected-into-open-tabs case; require it explicitly on.
        if (prefs?.tableCapture === true) wire();
      })
      .catch(() => wire());
  } else {
    wire();
  }
}
