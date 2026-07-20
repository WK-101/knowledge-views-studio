import { buildAnchor } from "../../shared/anchor";
import { locateAnchor } from "../../shared/anchor-locate";
import type { WireAnnotation } from "../../shared/protocol";

/**
 * The annotator, on the page.
 *
 * What the reference tools (Web Highlights, WuCai, Hypothesis) established as the shape of this feature:
 * select text and a small toolbar appears; pick a colour and the highlight is painted immediately; come
 * back to the page and every highlight is painted again; click one to note or remove it. This implements
 * that shape, with the vault as the store.
 *
 * Three disciplines keep it trustworthy on pages we don't control:
 *
 *  - **All UI lives in a shadow root.** The page's CSS cannot restyle the toolbar and ours cannot leak out;
 *    the only thing added to the page's own tree are the highlight spans, styled inline.
 *  - **Painting is anchor-based, never position-based.** On restore, the saved quote is located in the
 *    page's raw text (whitespace-tolerant, context-scored) and painted where it is *now* — a highlight that
 *    can't be confidently located is skipped, because wrong paint teaches distrust of every mark.
 *  - **The token never enters this script.** Everything goes through the background worker; a script
 *    injected into an arbitrary page is the last place a credential belongs.
 */

type Color = "yellow" | "green" | "blue" | "red" | "purple" | "orange";
type Style = "highlight" | "underline";

const PAINT: Record<Color, { light: string; dark: string; solid: string }> = {
  yellow: { light: "rgba(255, 213, 0, 0.38)", dark: "rgba(255, 213, 0, 0.30)", solid: "#e6c200" },
  green: { light: "rgba(76, 217, 100, 0.34)", dark: "rgba(76, 217, 100, 0.28)", solid: "#3fae5a" },
  blue: { light: "rgba(90, 160, 255, 0.34)", dark: "rgba(90, 160, 255, 0.30)", solid: "#4a8fe0" },
  red: { light: "rgba(255, 105, 120, 0.34)", dark: "rgba(255, 105, 120, 0.28)", solid: "#e05a6a" },
  purple: { light: "rgba(175, 120, 255, 0.32)", dark: "rgba(175, 120, 255, 0.28)", solid: "#9a6ae0" },
  orange: { light: "rgba(255, 160, 70, 0.36)", dark: "rgba(255, 160, 70, 0.28)", solid: "#e08a3a" },
};

/** The colour and style used last, so the next highlight starts from what you actually use. */
let lastChoice: { color: Color; style: Style } = { color: "yellow", style: "highlight" };

interface ChoiceStorage {
  local: { get(keys: string[]): Promise<Record<string, unknown>>; set(items: Record<string, unknown>): Promise<void> };
}
function choiceStorage(): ChoiceStorage | null {
  const g = globalThis as unknown as { browser?: { storage?: ChoiceStorage }; chrome?: { storage?: ChoiceStorage } };
  return g.browser?.storage ?? g.chrome?.storage ?? null;
}
void choiceStorage()
  ?.local.get(["annotatorLast"])
  .then((stored) => {
    const raw = stored["annotatorLast"] as { color?: string; style?: string } | undefined;
    if (raw?.color !== undefined && raw.color in PAINT) lastChoice = { ...lastChoice, color: raw.color as Color };
    if (raw?.style === "underline") lastChoice = { ...lastChoice, style: "underline" };
  })
  .catch(() => undefined);
function rememberChoice(color: Color, style: Style): void {
  lastChoice = { color, style };
  void choiceStorage()?.local.set({ annotatorLast: lastChoice }).catch(() => undefined);
}

const HL_ATTR = "data-kvs-hl";

interface Messenger {
  runtime: { sendMessage(message: unknown): Promise<unknown> };
}
const messenger = (): Messenger | null => {
  const g = globalThis as unknown as { browser?: Messenger; chrome?: Messenger };
  return g.browser ?? g.chrome ?? null;
};

const dark = (): boolean => window.matchMedia("(prefers-color-scheme: dark)").matches;

// ---------------------------------------------------------------- text index

interface TextIndex {
  readonly text: string;
  readonly nodes: readonly { readonly node: Text; readonly start: number }[];
}

/** Concatenate the page's raw text, remembering where each text node begins. */
function buildIndex(): TextIndex {
  const nodes: { node: Text; start: number }[] = [];
  let text = "";
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const parent = node.parentElement;
      if (parent === null) return NodeFilter.FILTER_REJECT;
      const tag = parent.tagName;
      if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEXTAREA") {
        return NodeFilter.FILTER_REJECT;
      }
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    nodes.push({ node: node as Text, start: text.length });
    text += (node as Text).nodeValue ?? "";
  }
  return { text, nodes };
}

/** The raw-text offset of a point inside a text node. */
function offsetOf(index: TextIndex, node: Text, offset: number): number {
  const entry = index.nodes.find((n) => n.node === node);
  return entry === undefined ? -1 : entry.start + offset;
}

/** The text nodes a raw-text span [start, end) touches, with local offsets. */
function segmentsIn(
  index: TextIndex,
  start: number,
  end: number,
): { node: Text; from: number; to: number }[] {
  const out: { node: Text; from: number; to: number }[] = [];
  for (const { node, start: nodeStart } of index.nodes) {
    const length = (node.nodeValue ?? "").length;
    const nodeEnd = nodeStart + length;
    if (nodeEnd <= start || nodeStart >= end) continue;
    out.push({ node, from: Math.max(0, start - nodeStart), to: Math.min(length, end - nodeStart) });
  }
  return out;
}

// ------------------------------------------------------------------ painting

/** Wrap one span of one text node in a highlight mark. */
function styleMark(mark: HTMLElement, color: Color, style: Style): void {
  if (style === "underline") {
    mark.style.backgroundColor = "transparent";
    mark.style.borderBottom = `2px solid ${PAINT[color].solid}`;
    mark.style.paddingBottom = "1px";
  } else {
    mark.style.backgroundColor = PAINT[color][dark() ? "dark" : "light"];
    mark.style.borderBottom = "none";
    mark.style.paddingBottom = "0";
  }
  mark.style.color = "inherit";
  mark.style.cursor = "pointer";
}

function wrapSegment(node: Text, from: number, to: number, id: string, color: Color, style: Style): void {
  if (to <= from) return;
  const target = from === 0 ? node : node.splitText(from);
  if (to < (node.nodeValue ?? "").length + from) target.splitText(to - from);
  const mark = document.createElement("mark");
  mark.setAttribute(HL_ATTR, id);
  styleMark(mark, color, style);
  target.parentNode?.replaceChild(mark, target);
  mark.appendChild(target);
}

/** Restyle an existing highlight in place — recolouring shouldn't re-anchor anything. */
function restyle(id: string, color: Color, style: Style): void {
  for (const mark of Array.from(document.querySelectorAll(`[${HL_ATTR}="${id}"]`))) {
    styleMark(mark as HTMLElement, color, style);
  }
}

/** Paint an annotation wherever its anchor confidently locates. Returns whether it painted. */
function paint(annotation: WireAnnotation): boolean {
  const index = buildIndex();
  const located = locateAnchor(index.text, annotation.anchor);
  if (located === null) return false;
  const color = (annotation.color in PAINT ? annotation.color : "yellow") as Color;
  const style: Style = annotation.style === "underline" ? "underline" : "highlight";
  // Wrap in document order; splitting mutates nodes, so segments are recomputed from a fresh index each
  // time a node is split. Simpler: collect segments first, then wrap back-to-front so earlier offsets
  // stay valid.
  const segments = segmentsIn(index, located.start, located.end);
  for (const segment of segments.reverse()) {
    wrapSegment(segment.node, segment.from, segment.to, annotation.id, color, style);
  }
  return segments.length > 0;
}

/** Remove an annotation's paint, merging the text back together. */
function unpaint(id: string): void {
  for (const mark of Array.from(document.querySelectorAll(`[${HL_ATTR}="${id}"]`))) {
    const parent = mark.parentNode;
    if (parent === null) continue;
    while (mark.firstChild !== null) parent.insertBefore(mark.firstChild, mark);
    parent.removeChild(mark);
    parent.normalize();
  }
}

// ------------------------------------------------------------------- shadow UI

let shell: HTMLElement | null = null;
let shadow: ShadowRoot | null = null;

function ensureShell(): ShadowRoot {
  if (shadow !== null) return shadow;
  shell = document.createElement("div");
  shell.style.position = "fixed";
  shell.style.zIndex = "2147483647";
  shell.style.top = "0";
  shell.style.left = "0";
  shadow = shell.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = `
    .bar {
      position: fixed;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 8px;
      background: ${dark() ? "#2a2a2e" : "#ffffff"};
      color: ${dark() ? "#e6e6e6" : "#1a1a1a"};
      border: 1px solid ${dark() ? "#48484d" : "#d5d5da"};
      border-radius: 8px;
      box-shadow: 0 4px 18px rgba(0,0,0,0.22);
      font: 12px/1.4 system-ui, sans-serif;
    }
    .swatch {
      width: 18px; height: 18px;
      border-radius: 50%;
      border: 1.5px solid rgba(0,0,0,0.18);
      cursor: pointer;
      padding: 0;
    }
    .swatch:hover { transform: scale(1.15); }
    .divider { width: 1px; height: 16px; background: ${dark() ? "#48484d" : "#e0e0e4"}; }
    button.action {
      border: 0; background: none; cursor: pointer;
      color: inherit; font: inherit; padding: 2px 4px; border-radius: 4px;
    }
    button.action:hover { background: ${dark() ? "#3a3a3f" : "#f0f0f3"}; }
    textarea {
      width: 220px; min-height: 52px;
      border: 1px solid ${dark() ? "#48484d" : "#d5d5da"};
      border-radius: 6px;
      background: ${dark() ? "#1f1f23" : "#fafafa"};
      color: inherit; font: inherit; padding: 5px 7px;
      resize: vertical;
    }
    .col { display: flex; flex-direction: column; gap: 6px; }
    .row { display: flex; gap: 6px; justify-content: flex-end; }
  `;
  shadow.appendChild(style);
  document.documentElement.appendChild(shell);
  return shadow;
}

function clearUi(): void {
  const root = ensureShell();
  for (const child of Array.from(root.children)) {
    if (child.tagName !== "STYLE") root.removeChild(child);
  }
}

function placeNear(el: HTMLElement, rect: DOMRect): void {
  const margin = 8;
  el.style.left = `${String(Math.max(margin, Math.min(rect.left, window.innerWidth - 260)))}px`;
  el.style.top = `${String(Math.min(window.innerHeight - 60, rect.bottom + margin))}px`;
}

// ----------------------------------------------------------------- behaviour

/** What we know about every highlight currently painted, id → wire copy. */
const live = new Map<string, WireAnnotation>();

function pageMetadataFields(): { key: string; value: string }[] {
  const meta = (name: string): string =>
    document.querySelector(`meta[property="${name}"], meta[name="${name}"]`)?.getAttribute("content") ?? "";
  const fields = [
    { key: "title", value: document.title },
    { key: "url", value: location.href },
    { key: "description", value: meta("og:description") || meta("description") },
    { key: "author", value: meta("author") || meta("citation_author") },
  ];
  return fields.filter((f) => f.value.trim() !== "");
}

async function saveAnnotation(annotation: WireAnnotation): Promise<boolean> {
  const api = messenger();
  if (api === null) return false;
  try {
    const reply = (await api.runtime.sendMessage({
      type: "kvs-annotate",
      url: location.href,
      annotation,
      fields: pageMetadataFields(),
    })) as { ok?: boolean } | undefined;
    return reply?.ok === true;
  } catch {
    return false;
  }
}

async function removeAnnotation(id: string): Promise<void> {
  const api = messenger();
  if (api === null) return;
  try {
    await api.runtime.sendMessage({ type: "kvs-annotate-remove", url: location.href, id });
  } catch {
    // The paint is already gone locally; the vault copy is cleaned next time it's reachable.
  }
}

function newId(): string {
  return Array.from({ length: 10 }, () =>
    "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)],
  ).join("");
}

/** Create, paint and save a highlight from the current selection. */
function highlightSelection(color: Color, style: Style, note?: string): void {
  const selection = window.getSelection();
  if (selection === null || selection.isCollapsed || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(0);
  const index = buildIndex();

  const startNode = range.startContainer;
  const endNode = range.endContainer;
  if (!(startNode instanceof Text) || !(endNode instanceof Text)) return;
  const start = offsetOf(index, startNode, range.startOffset);
  const end = offsetOf(index, endNode, range.endOffset);
  if (start < 0 || end <= start) return;

  const exact = index.text.slice(start, end);
  const anchor = buildAnchor(index.text, exact, start);
  const annotation: WireAnnotation = {
    id: newId(),
    anchor,
    color,
    style,
    createdAt: new Date().toISOString(),
    ...(note !== undefined && note.trim() !== "" ? { note: note.trim() } : {}),
  };
  rememberChoice(color, style);

  // Paint immediately — waiting for the vault would make the toolbar feel broken — then save; failure
  // unpaints, so the page never shows a highlight the vault refused.
  const segments = segmentsIn(index, start, end);
  for (const segment of segments.reverse()) {
    wrapSegment(segment.node, segment.from, segment.to, annotation.id, color, style);
  }
  live.set(annotation.id, annotation);
  selection.removeAllRanges();
  clearUi();

  void saveAnnotation(annotation).then((ok) => {
    if (!ok) {
      unpaint(annotation.id);
      live.delete(annotation.id);
    }
  });
}

/** The selection toolbar: the colours, the style, and a note behind one more click. */
function showToolbar(rect: DOMRect): void {
  const root = ensureShell();
  clearUi();
  const bar = document.createElement("div");
  bar.className = "bar";

  let style: Style = lastChoice.style;

  const swatches: HTMLButtonElement[] = [];
  const drawSwatches = (): void => {
    for (const swatch of swatches) {
      const color = swatch.dataset["color"] as Color;
      swatch.style.backgroundColor = PAINT[color].solid;
      swatch.style.borderRadius = style === "underline" ? "3px" : "50%";
      swatch.style.height = style === "underline" ? "6px" : "18px";
      swatch.style.marginTop = style === "underline" ? "6px" : "0";
    }
  };

  for (const color of Object.keys(PAINT) as Color[]) {
    const swatch = document.createElement("button");
    swatch.className = "swatch";
    swatch.dataset["color"] = color;
    swatch.title = `${style === "underline" ? "Underline" : "Highlight"} ${color}`;
    swatch.addEventListener("mousedown", (event) => {
      event.preventDefault();
      highlightSelection(color, style);
    });
    swatches.push(swatch);
    bar.appendChild(swatch);
  }

  bar.appendChild(Object.assign(document.createElement("div"), { className: "divider" }));

  const styleToggle = document.createElement("button");
  styleToggle.className = "action";
  const drawToggle = (): void => {
    styleToggle.textContent = style === "underline" ? "U̲" : "H";
    styleToggle.title = style === "underline" ? "Switch to highlight" : "Switch to underline";
  };
  styleToggle.addEventListener("mousedown", (event) => {
    event.preventDefault();
    style = style === "underline" ? "highlight" : "underline";
    drawToggle();
    drawSwatches();
  });
  bar.appendChild(styleToggle);

  const withNote = document.createElement("button");
  withNote.className = "action";
  withNote.textContent = "＋ note";
  withNote.title = "Highlight with a note";
  withNote.addEventListener("mousedown", (event) => {
    event.preventDefault();
    showNoteEditor(rect, (note, color) => highlightSelection(color, style, note));
  });
  bar.appendChild(withNote);

  drawToggle();
  drawSwatches();
  placeNear(bar, rect);
  root.appendChild(bar);
}

/** A small note editor, used both at creation and when annotating an existing highlight. */
function showNoteEditor(
  rect: DOMRect,
  onDone: (note: string, color: Color) => void,
  initial = "",
): void {
  const root = ensureShell();
  clearUi();
  const box = document.createElement("div");
  box.className = "bar col";

  const input = document.createElement("textarea");
  input.placeholder = "Your note…";
  input.value = initial;
  box.appendChild(input);

  const rowEl = document.createElement("div");
  rowEl.className = "row";
  let chosen: Color = lastChoice.color;
  for (const color of Object.keys(PAINT) as Color[]) {
    const swatch = document.createElement("button");
    swatch.className = "swatch";
    swatch.style.backgroundColor = PAINT[color].solid;
    swatch.style.outline = color === chosen ? "2px solid #888" : "none";
    swatch.addEventListener("click", () => {
      chosen = color;
      for (const other of Array.from(rowEl.querySelectorAll(".swatch"))) {
        (other as HTMLElement).style.outline = "none";
      }
      swatch.style.outline = "2px solid #888";
    });
    rowEl.appendChild(swatch);
  }
  const save = document.createElement("button");
  save.className = "action";
  save.textContent = "Save";
  save.addEventListener("click", () => onDone(input.value, chosen));
  rowEl.appendChild(save);
  box.appendChild(rowEl);

  placeNear(box, rect);
  root.appendChild(box);
  input.focus();
}

/** Clicking a painted highlight: its note, and everything you can do to it. */
function showHighlightMenu(id: string, rect: DOMRect): void {
  const annotation = live.get(id);
  const root = ensureShell();
  clearUi();
  const bar = document.createElement("div");
  bar.className = "bar col";

  if (annotation?.note !== undefined) {
    const note = document.createElement("div");
    note.textContent = annotation.note;
    note.style.maxWidth = "240px";
    bar.appendChild(note);
  }

  // Recolour and restyle in place — the anchor doesn't change, so nothing re-anchors.
  const swatchRow = document.createElement("div");
  swatchRow.className = "row";
  for (const color of Object.keys(PAINT) as Color[]) {
    const swatch = document.createElement("button");
    swatch.className = "swatch";
    swatch.style.backgroundColor = PAINT[color].solid;
    swatch.title = `Recolour ${color}`;
    swatch.addEventListener("click", () => {
      if (annotation === undefined) return;
      const style: Style = annotation.style === "underline" ? "underline" : "highlight";
      const updated: WireAnnotation = { ...annotation, color };
      live.set(id, updated);
      restyle(id, color, style);
      rememberChoice(color, style);
      void saveAnnotation(updated);
    });
    swatchRow.appendChild(swatch);
  }
  bar.appendChild(swatchRow);

  const rowEl = document.createElement("div");
  rowEl.className = "row";

  const styleButton = document.createElement("button");
  styleButton.className = "action";
  styleButton.textContent = annotation?.style === "underline" ? "As highlight" : "As underline";
  styleButton.addEventListener("click", () => {
    if (annotation === undefined) return;
    const nextStyle: Style = annotation.style === "underline" ? "highlight" : "underline";
    const color = (annotation.color in PAINT ? annotation.color : "yellow") as Color;
    const updated: WireAnnotation = { ...annotation, style: nextStyle };
    live.set(id, updated);
    restyle(id, color, nextStyle);
    clearUi();
    void saveAnnotation(updated);
  });
  rowEl.appendChild(styleButton);

  const copyButton = document.createElement("button");
  copyButton.className = "action";
  copyButton.textContent = "Copy";
  copyButton.title = "Copy the highlighted text";
  copyButton.addEventListener("click", () => {
    const text = annotation?.anchor.exact ?? "";
    void navigator.clipboard.writeText(text).catch(() => undefined);
    clearUi();
  });
  rowEl.appendChild(copyButton);

  const noteButton = document.createElement("button");
  noteButton.className = "action";
  noteButton.textContent = annotation?.note === undefined ? "Add note" : "Edit note";
  noteButton.addEventListener("click", () => {
    showNoteEditor(
      rect,
      (note) => {
        if (annotation === undefined) return;
        const updated: WireAnnotation = { ...annotation, ...(note.trim() !== "" ? { note: note.trim() } : {}) };
        live.set(id, updated);
        clearUi();
        void saveAnnotation(updated);
      },
      annotation?.note ?? "",
    );
  });
  rowEl.appendChild(noteButton);

  const removeButton = document.createElement("button");
  removeButton.className = "action";
  removeButton.textContent = "Remove";
  removeButton.addEventListener("click", () => {
    unpaint(id);
    live.delete(id);
    clearUi();
    void removeAnnotation(id);
  });
  rowEl.appendChild(removeButton);

  bar.appendChild(rowEl);
  placeNear(bar, rect);
  root.appendChild(bar);
}

// -------------------------------------------------------------------- restore

async function restore(): Promise<void> {
  const api = messenger();
  if (api === null) return;
  let annotations: WireAnnotation[] = [];
  try {
    const reply = (await api.runtime.sendMessage({ type: "kvs-annotations-for", url: location.href })) as
      | { annotations?: WireAnnotation[] }
      | undefined;
    annotations = reply?.annotations ?? [];
  } catch {
    return;
  }
  for (const annotation of annotations) {
    if (paint(annotation)) live.set(annotation.id, annotation);
    // Skipped paints are deliberate: a highlight that can't be confidently located stays unpainted rather
    // than landing on the wrong words. It's still in the vault, listed in the companion.
  }
}

// ---------------------------------------------------------------------- wire

const marker = "__kvsAnnotatorReady";
const scope = window as unknown as Record<string, boolean>;
if (scope[marker] !== true) {
  scope[marker] = true;

  document.addEventListener("mouseup", (event) => {
    // Our own UI must not retrigger or dismiss itself mid-click.
    if (shell !== null && event.composedPath().includes(shell)) return;
    window.setTimeout(() => {
      const selection = window.getSelection();
      if (selection === null || selection.isCollapsed || selection.toString().trim() === "") {
        clearUi();
        return;
      }
      const rect = selection.getRangeAt(0).getBoundingClientRect();
      showToolbar(rect);
    }, 10);
  });

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const mark = target.closest(`[${HL_ATTR}]`);
    if (mark === null) return;
    const id = mark.getAttribute(HL_ATTR);
    if (id === null) return;
    event.preventDefault();
    showHighlightMenu(id, mark.getBoundingClientRect());
  });

  void restore();
}
