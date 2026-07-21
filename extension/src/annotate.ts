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

/**
 * Turn any selection boundary — text node or element — into a raw-text position.
 *
 * A text-node boundary is a direct character offset. An element boundary is a *child index*: the selection
 * sits before its Nth child. For a start we want the beginning of the first text at or after that point;
 * for an end, the end of the last text at or before it. This is the difference between honouring what was
 * selected and reading an element's child count as though it were a character count.
 */
function resolveBoundary(
  index: TextIndex,
  node: Node,
  offset: number,
  edge: "start" | "end",
): number {
  if (node instanceof Text) return offsetOf(index, node, offset);

  // An element boundary: find the text nodes that fall inside this element, and take the edge nearest the
  // child index the boundary named.
  const contained = index.nodes.filter((n) => node.contains(n.node));
  if (contained.length === 0) return -1;
  const child = (node.childNodes[offset] ?? null) as Node | null;

  if (edge === "start") {
    // The first text at or after the boundary's child.
    for (const entry of contained) {
      if (child === null || child.compareDocumentPosition(entry.node) & Node.DOCUMENT_POSITION_FOLLOWING || child === entry.node || child.contains(entry.node)) {
        return entry.start;
      }
    }
    return contained[0]?.start ?? -1;
  }
  // end: the end of the last text before the boundary's child.
  let last = contained[0];
  for (const entry of contained) {
    if (child !== null && (child.compareDocumentPosition(entry.node) & Node.DOCUMENT_POSITION_FOLLOWING) === 0 && child !== entry.node) {
      last = entry;
    }
  }
  return last === undefined ? -1 : last.start + (last.node.nodeValue ?? "").length;
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
  mark.title = "Click for options · Alt+click to remove";
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

interface SaveResult {
  readonly ok: boolean;
  readonly reason?: string;
}

async function saveAnnotation(annotation: WireAnnotation): Promise<SaveResult> {
  const api = messenger();
  if (api === null) return { ok: false, reason: "The extension isn't available on this page." };
  try {
    const reply = (await api.runtime.sendMessage({
      type: "kvs-annotate",
      url: location.href,
      annotation,
      fields: pageMetadataFields(),
    })) as { ok?: boolean; reason?: string } | undefined;
    return { ok: reply?.ok === true, ...(reply?.reason !== undefined ? { reason: reply.reason } : {}) };
  } catch {
    return { ok: false, reason: "Couldn't reach the extension." };
  }
}

/**
 * A brief message on the page, for when something didn't work.
 *
 * The alternative was what actually shipped first: the highlight silently unpainting itself, which reads as
 * a glitch and hides a fixable cause. Unpainting is right — the page must never show a highlight the vault
 * refused — but doing it wordlessly turned every configuration problem into a mystery.
 */
function toast(message: string): void {
  const root = ensureShell();
  clearUi();
  const box = document.createElement("div");
  box.className = "bar";
  box.style.maxWidth = "340px";
  box.textContent = message;
  box.style.left = "16px";
  box.style.bottom = "16px";
  box.style.top = "auto";
  root.appendChild(box);
  window.setTimeout(() => {
    if (box.parentNode !== null) box.parentNode.removeChild(box);
  }, 6000);
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

  // Selection boundaries don't always land on text nodes: selecting to the end of a paragraph puts the
  // end boundary on the element, and `endContainer` is then an element with a child-offset, not a Text
  // node with a character-offset. Reading that child-offset as a character position is what stretched a
  // five-word selection out to the whole sentence — the offset pointed past the words into the element's
  // structure. Resolving each boundary to the nearest real text position fixes it at the source.
  const startPos = resolveBoundary(index, range.startContainer, range.startOffset, "start");
  const endPos = resolveBoundary(index, range.endContainer, range.endOffset, "end");
  if (startPos < 0 || endPos <= startPos) return;
  const start = startPos;
  const end = endPos;

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
  refreshSidebar();
  selection.removeAllRanges();
  clearUi();

  void saveAnnotation(annotation).then((result) => {
    if (!result.ok) {
      unpaint(annotation.id);
      live.delete(annotation.id);
      toast(`Highlight not saved: ${result.reason ?? "your vault refused it."} It was removed from the page so it doesn't pretend to exist.`);
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
    refreshSidebar();
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
  // The sidebar counts what's actually here; update it once the page's highlights are loaded.
  refreshSidebar();
}

// ------------------------------------------------------------ in-page sidebar
//
// An optional, draggable panel that lists this page's highlights — WuCai's idea, and a genuinely useful
// one: the highlights you made, in one place, click to jump to any of them. It lives in its own persistent
// shadow host (the transient toolbar host gets emptied on every click; this must not), is off by default,
// and appears only when the person turns it on. Everything here is additive — the highlighter works
// exactly as before whether the sidebar is open, closed, or disabled.

let sidebarShell: HTMLElement | null = null;
let sidebarShadow: ShadowRoot | null = null;
let sidebarOpen = false;
let sidebarEnabled = false;

/** Read the one preference this content script cares about: is the sidebar turned on? */
void choiceStorage()
  ?.local.get(["preferences"])
  .then((stored) => {
    const prefs = stored["preferences"] as { annotationSidebar?: boolean } | undefined;
    sidebarEnabled = prefs?.annotationSidebar === true;
    if (sidebarEnabled) mountLauncher();
  })
  .catch(() => undefined);

/** The floating pill that opens the panel — small, out of the way, showing the count. */
let launcher: HTMLButtonElement | null = null;
function mountLauncher(): void {
  if (launcher !== null) return;
  const root = ensureSidebarShell();
  launcher = document.createElement("button");
  launcher.className = "kvs-launcher";
  launcher.type = "button";
  launcher.title = "Show this page's highlights";
  launcher.addEventListener("click", () => {
    sidebarOpen ? closeSidebar() : openSidebar();
  });
  root.appendChild(launcher);
  refreshLauncher();
}

function refreshLauncher(): void {
  if (launcher === null) return;
  const count = live.size;
  launcher.textContent = "";
  const icon = document.createElement("span");
  icon.className = "kvs-launcher-icon";
  icon.textContent = "✎";
  launcher.appendChild(icon);
  if (count > 0) {
    const badge = document.createElement("span");
    badge.className = "kvs-launcher-badge";
    badge.textContent = String(count);
    launcher.appendChild(badge);
  }
  launcher.classList.toggle("kvs-launcher-empty", count === 0);
}

/** The persistent shadow host for the sidebar + launcher (distinct from the transient toolbar host). */
function ensureSidebarShell(): ShadowRoot {
  if (sidebarShadow !== null) return sidebarShadow;
  sidebarShell = document.createElement("div");
  sidebarShell.style.position = "fixed";
  sidebarShell.style.zIndex = "2147483646";
  sidebarShell.style.top = "0";
  sidebarShell.style.left = "0";
  sidebarShell.style.width = "0";
  sidebarShell.style.height = "0";
  sidebarShadow = sidebarShell.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = sidebarStyles();
  sidebarShadow.appendChild(style);
  document.documentElement.appendChild(sidebarShell);
  return sidebarShadow;
}

let panel: HTMLElement | null = null;
function openSidebar(): void {
  const root = ensureSidebarShell();
  if (panel === null) {
    panel = document.createElement("div");
    panel.className = `kvs-sidebar ${dark() ? "kvs-dark" : ""}`;
    root.appendChild(panel);
    makeDraggable(panel);
  }
  panel.style.display = "flex";
  sidebarOpen = true;
  renderSidebar();
}

function closeSidebar(): void {
  if (panel !== null) panel.style.display = "none";
  sidebarOpen = false;
}

/** One line of an annotation's text for the list. */
function quoteOf(annotation: WireAnnotation): string {
  return (annotation.anchor.exact || "").replace(/\s+/g, " ").trim();
}

/** Relative time, the way every modern list shows it. */
function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return "";
  const secs = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (secs < 45) return "just now";
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${String(mins)}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${String(hours)}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${String(days)}d ago`;
  return new Date(then).toLocaleDateString();
}

function renderSidebar(): void {
  if (panel === null) return;
  panel.textContent = "";

  // Header — draggable handle, title, count, close.
  const header = document.createElement("div");
  header.className = "kvs-sb-head";
  const title = document.createElement("div");
  title.className = "kvs-sb-title";
  title.textContent = "Highlights";
  const count = document.createElement("span");
  count.className = "kvs-sb-count";
  count.textContent = String(live.size);
  title.appendChild(count);
  header.appendChild(title);
  const close = document.createElement("button");
  close.className = "kvs-sb-close";
  close.type = "button";
  close.title = "Close";
  close.textContent = "×";
  close.addEventListener("click", () => closeSidebar());
  header.appendChild(close);
  panel.appendChild(header);

  // List.
  const list = document.createElement("div");
  list.className = "kvs-sb-list";
  const items = [...live.values()].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
  if (items.length === 0) {
    const empty = document.createElement("div");
    empty.className = "kvs-sb-empty";
    empty.textContent = "No highlights on this page yet. Select text to add one.";
    list.appendChild(empty);
  }
  for (const annotation of items) {
    list.appendChild(sidebarItem(annotation));
  }
  panel.appendChild(list);
}

function sidebarItem(annotation: WireAnnotation): HTMLElement {
  const item = document.createElement("div");
  item.className = "kvs-sb-item";

  const bar = document.createElement("span");
  bar.className = "kvs-sb-bar";
  bar.style.backgroundColor = (PAINT[annotation.color as Color] ?? PAINT.yellow).solid;
  item.appendChild(bar);

  const body = document.createElement("div");
  body.className = "kvs-sb-body";
  const quote = document.createElement("div");
  quote.className = "kvs-sb-quote";
  quote.textContent = quoteOf(annotation);
  body.appendChild(quote);
  if (annotation.note !== undefined && annotation.note.trim() !== "") {
    const note = document.createElement("div");
    note.className = "kvs-sb-note";
    note.textContent = annotation.note.trim();
    body.appendChild(note);
  }
  const meta = document.createElement("div");
  meta.className = "kvs-sb-meta";
  meta.textContent = relativeTime(annotation.createdAt);
  body.appendChild(meta);
  item.appendChild(body);

  const del = document.createElement("button");
  del.className = "kvs-sb-del";
  del.type = "button";
  del.title = "Remove highlight";
  del.textContent = "×";
  del.addEventListener("click", (event) => {
    event.stopPropagation();
    unpaint(annotation.id);
    live.delete(annotation.id);
    void removeAnnotation(annotation.id);
    refreshSidebar();
  });
  item.appendChild(del);

  // Click the row → scroll to the mark and flash it.
  item.addEventListener("click", () => {
    const mark = document.querySelector(`[${HL_ATTR}="${annotation.id}"]`);
    if (mark instanceof HTMLElement) {
      mark.scrollIntoView({ behavior: "smooth", block: "center" });
      flashMark(annotation.id);
    }
  });
  return item;
}

/** A brief pulse on a mark, so clicking it in the list points the eye to it on the page. */
function flashMark(id: string): void {
  for (const mark of Array.from(document.querySelectorAll(`[${HL_ATTR}="${id}"]`))) {
    if (!(mark instanceof HTMLElement)) continue;
    mark.style.transition = "box-shadow 0.2s ease";
    mark.style.boxShadow = "0 0 0 3px rgba(124, 92, 255, 0.55)";
    window.setTimeout(() => {
      mark.style.boxShadow = "";
    }, 1400);
  }
}

/** Keep the sidebar and launcher in sync after a highlight is added or removed. */
function refreshSidebar(): void {
  refreshLauncher();
  if (sidebarOpen) renderSidebar();
}

/** Drag by the header, staying within the viewport. */
function makeDraggable(el: HTMLElement): void {
  let startX = 0;
  let startY = 0;
  let originLeft = 0;
  let originTop = 0;
  let dragging = false;

  const onMove = (event: MouseEvent): void => {
    if (!dragging) return;
    const left = Math.min(window.innerWidth - 60, Math.max(0, originLeft + (event.clientX - startX)));
    const top = Math.min(window.innerHeight - 40, Math.max(0, originTop + (event.clientY - startY)));
    el.style.left = `${String(left)}px`;
    el.style.top = `${String(top)}px`;
    el.style.right = "auto";
  };
  const onUp = (): void => {
    dragging = false;
    document.removeEventListener("mousemove", onMove);
    document.removeEventListener("mouseup", onUp);
  };
  el.addEventListener("mousedown", (event) => {
    const target = event.target;
    if (!(target instanceof Element) || target.closest(".kvs-sb-head") === null) return;
    if (target.closest(".kvs-sb-close") !== null) return;
    dragging = true;
    const rect = el.getBoundingClientRect();
    startX = event.clientX;
    startY = event.clientY;
    originLeft = rect.left;
    originTop = rect.top;
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
    event.preventDefault();
  });
}

function sidebarStyles(): string {
  const isDark = dark();
  const bg = isDark ? "#1f1f22" : "#ffffff";
  const fg = isDark ? "#e8e8ea" : "#1a1a1c";
  const muted = isDark ? "#9a9aa2" : "#77777f";
  const line = isDark ? "#34343a" : "#ececf0";
  const hover = isDark ? "#2a2a30" : "#f6f6f8";
  const accent = "#7c5cff";
  return `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    .kvs-launcher {
      position: fixed; right: 18px; bottom: 18px; width: 44px; height: 44px; border-radius: 50%;
      border: none; background: ${accent}; color: #fff; cursor: pointer; display: flex;
      align-items: center; justify-content: center; box-shadow: 0 4px 14px rgba(0,0,0,0.25);
      transition: transform 0.15s ease, box-shadow 0.15s ease;
    }
    .kvs-launcher:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(0,0,0,0.3); }
    .kvs-launcher-empty { background: ${isDark ? "#3a3a42" : "#c9c9d2"}; }
    .kvs-launcher-icon { font-size: 18px; line-height: 1; }
    .kvs-launcher-badge {
      position: absolute; top: -3px; right: -3px; min-width: 18px; height: 18px; padding: 0 5px;
      border-radius: 9px; background: #ff5470; color: #fff; font-size: 11px; font-weight: 700;
      display: flex; align-items: center; justify-content: center; border: 2px solid ${bg};
    }
    .kvs-sidebar {
      position: fixed; top: 12px; right: 68px; width: 340px; max-height: calc(100vh - 24px);
      background: ${bg}; color: ${fg}; border: 1px solid ${line}; border-radius: 14px;
      box-shadow: 0 8px 30px rgba(0,0,0,0.18); display: flex; flex-direction: column;
      overflow: hidden; font-size: 13px; line-height: 1.5;
    }
    .kvs-sb-head {
      display: flex; align-items: center; justify-content: space-between; padding: 12px 14px;
      border-bottom: 1px solid ${line}; cursor: move; user-select: none;
    }
    .kvs-sb-title { font-weight: 600; font-size: 14px; display: flex; align-items: center; gap: 8px; }
    .kvs-sb-count {
      font-size: 11px; font-weight: 600; color: ${muted}; background: ${hover};
      padding: 1px 8px; border-radius: 10px;
    }
    .kvs-sb-close {
      border: none; background: transparent; color: ${muted}; font-size: 20px; line-height: 1;
      cursor: pointer; padding: 0 4px; border-radius: 6px;
    }
    .kvs-sb-close:hover { background: ${hover}; color: ${fg}; }
    .kvs-sb-list { overflow-y: auto; padding: 6px; }
    .kvs-sb-list::-webkit-scrollbar { width: 8px; }
    .kvs-sb-list::-webkit-scrollbar-thumb { background: ${line}; border-radius: 8px; border: 2px solid transparent; background-clip: content-box; }
    .kvs-sb-empty { padding: 22px 16px; text-align: center; color: ${muted}; font-size: 12px; }
    .kvs-sb-item {
      display: flex; gap: 10px; padding: 9px 10px; border-radius: 9px; cursor: pointer; position: relative;
      transition: background 0.12s ease;
    }
    .kvs-sb-item:hover { background: ${hover}; }
    .kvs-sb-bar { flex: 0 0 3px; border-radius: 2px; align-self: stretch; }
    .kvs-sb-body { flex: 1 1 auto; min-width: 0; }
    .kvs-sb-quote {
      color: ${fg}; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
      overflow: hidden; overflow-wrap: anywhere;
    }
    .kvs-sb-note { color: ${muted}; font-size: 12px; margin-top: 3px; overflow-wrap: anywhere; }
    .kvs-sb-meta { color: ${muted}; font-size: 11px; margin-top: 4px; }
    .kvs-sb-del {
      position: absolute; top: 6px; right: 6px; border: none; background: transparent; color: ${muted};
      font-size: 16px; line-height: 1; cursor: pointer; opacity: 0; padding: 2px 6px; border-radius: 6px;
      transition: opacity 0.12s ease;
    }
    .kvs-sb-item:hover .kvs-sb-del { opacity: 1; }
    .kvs-sb-del:hover { background: ${isDark ? "#3a2a2e" : "#fdeaee"}; color: #e0526a; }
  `;
}

// ---------------------------------------------------------------------- wire

const marker = "__kvsAnnotatorReady";
const scope = window as unknown as Record<string, boolean>;
if (scope[marker] !== true) {
  scope[marker] = true;

  document.addEventListener("mouseup", (event) => {
    // Our own UI must not retrigger or dismiss itself mid-click.
    if (shell !== null && event.composedPath().includes(shell)) return;
    if (sidebarShell !== null && event.composedPath().includes(sidebarShell)) return;
    // Clicking a highlight is the click handler's business. The 10ms clear here used to race the menu that
    // click was about to open — menu up, menu gone, reading as a flicker and a broken Remove.
    if (event.target instanceof Element && event.target.closest(`[${HL_ATTR}]`) !== null) return;
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
    // Alt+click: gone, no menu — the fastest honest delete there is. The menu's Remove stays for everyone
    // who doesn't know the shortcut.
    if (event.altKey) {
      unpaint(id);
      live.delete(id);
      refreshSidebar();
      clearUi();
      void removeAnnotation(id);
      return;
    }
    showHighlightMenu(id, mark.getBoundingClientRect());
  });

  void restore();
}
