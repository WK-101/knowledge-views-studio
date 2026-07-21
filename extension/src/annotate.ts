import { buildAnchor } from "../../shared/anchor";
import { inPageTheme, highlightAlpha } from "../../shared/in-page-ui";
import { ZOTERO_PALETTE, type HighlightColor, type HighlightIntensity } from "../../shared/annotations";
import { locateAnchor } from "../../shared/anchor-locate";
import type { WireAnnotation } from "../../shared/protocol";
import {
  DEFAULT_ISLAND_ACTIONS,
  normalizeIslandActions,
  type IslandAction,
  type IslandActionId,
} from "./lib/island-actions";
import {
  DEFAULT_ISLAND_SETTINGS,
  ISLAND_SIZE_SCALE,
  normalizeIslandSettings,
  type IslandSettings,
} from "./lib/island-settings";
import { formatCopy, type CopyFormat } from "./lib/copy-formats";
import {
  DEFAULT_SEARCH_TARGETS,
  normalizeSearchTargets,
  resolveEngine,
  searchUrl,
  type DisplayHit,
  type SearchTargets,
} from "./lib/search-targets";
import {
  STICKY_DEFAULT_H,
  STICKY_DEFAULT_W,
  STICKY_MAX_H,
  STICKY_MAX_W,
  STICKY_MIN_H,
  STICKY_MIN_W,
  stickyId,
  type StickyNote,
} from "../../shared/sticky";
import { renderMarkdown } from "./lib/markdown-mini";
import { matchAutoAction, normalizeSiteAutoActions, type SiteAutoAction } from "./lib/auto-actions";

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

type Color = HighlightColor;
type Style = "highlight" | "underline";
type Intensity = HighlightIntensity;

/**
 * What each colour looks like in the page — the canonical Zotero palette, so a highlight painted here matches
 * the same colour in Zotero and in the PDF annotator, and reads identically once imported into the vault.
 * `rgb` feeds the transparency-weighted fill; `solid` (the palette hex) is the underline stroke and the swatch
 * border. Built straight from ZOTERO_PALETTE so all eight — magenta and gray included — stay in lockstep, and
 * the swatch order (via Object.keys) follows Zotero's own toolbar.
 */
const PAINT: Record<Color, { rgb: readonly [number, number, number]; solid: string }> = Object.fromEntries(
  ZOTERO_PALETTE.map((c) => [c.name, { rgb: c.rgb, solid: c.hex }]),
) as Record<Color, { rgb: readonly [number, number, number]; solid: string }>;

/**
 * Adopt a palette pushed by the plugin — the vault's own highlight colours, Zotero's or a custom override — so
 * a highlight painted here matches what the vault draws. Mutates PAINT in place (the swatch loops read it live
 * at render time), and falls back per-slot to the colour already loaded if an entry is malformed, so a bad
 * push can never blank a swatch. No push, or an older plugin, leaves the built-in Zotero defaults untouched.
 */
function applyPalette(
  palette: readonly { name?: string; hex?: string; rgb?: readonly [number, number, number] }[],
): void {
  for (const c of palette) {
    if (
      typeof c.name === "string" &&
      c.name in PAINT &&
      typeof c.hex === "string" &&
      Array.isArray(c.rgb) &&
      c.rgb.length === 3 &&
      c.rgb.every((n) => typeof n === "number")
    ) {
      PAINT[c.name as Color] = { rgb: [c.rgb[0]!, c.rgb[1]!, c.rgb[2]!], solid: c.hex };
    }
  }
}

/** An annotation's transparency, defaulting to medium. */
function intensityOf(a: WireAnnotation | undefined): Intensity {
  return a?.intensity === "light" || a?.intensity === "strong" ? a.intensity : "medium";
}

/** The fill for a highlight of a colour at a transparency level, in the page's colour scheme. */
function fillFor(color: Color, intensity: HighlightIntensity): string {
  const [r, g, b] = PAINT[color].rgb;
  return `rgba(${String(r)}, ${String(g)}, ${String(b)}, ${String(highlightAlpha(intensity, dark()))})`;
}

/** The colour, style, and transparency used last, so the next highlight starts from what you actually use. */
let lastChoice: { color: Color; style: Style; intensity: Intensity } = {
  color: "yellow",
  style: "highlight",
  intensity: "medium",
};

/** Which toolbar actions to show, and in what order. Read from preferences on load; defaults to all on. */
let islandActions: readonly IslandAction[] = DEFAULT_ISLAND_ACTIONS;

/** The toolbar's appearance and behaviour. Read from preferences on load; defaults to today's behaviour. */
let islandSettings: IslandSettings = DEFAULT_ISLAND_SETTINGS;

/** Where the Search action sends a selection. Read from preferences on load; defaults to vault + engines. */
let searchTargets: SearchTargets = DEFAULT_SEARCH_TARGETS;

/** Whether the "drop a sticky note" launcher button is shown. Read from preferences on load. */
let stickyLauncherEnabled = false;

/** The per-site auto-action for this page, if any — resolved once on load from the site rules. */
let activeAutoAction: SiteAutoAction | null = null;

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
    const raw = stored["annotatorLast"] as { color?: string; style?: string; intensity?: string } | undefined;
    if (raw?.color !== undefined && raw.color in PAINT) lastChoice = { ...lastChoice, color: raw.color as Color };
    if (raw?.style === "underline") lastChoice = { ...lastChoice, style: "underline" };
    if (raw?.intensity === "light" || raw?.intensity === "strong") lastChoice = { ...lastChoice, intensity: raw.intensity };
  })
  .catch(() => undefined);
function rememberChoice(color: Color, style: Style, intensity: Intensity = lastChoice.intensity): void {
  lastChoice = { color, style, intensity };
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

const dark = (): boolean =>
  islandSettings.theme === "dark"
    ? true
    : islandSettings.theme === "light"
      ? false
      : window.matchMedia("(prefers-color-scheme: dark)").matches;

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
function styleMark(mark: HTMLElement, color: Color, style: Style, intensity: Intensity): void {
  if (style === "underline") {
    mark.style.backgroundColor = "transparent";
    mark.style.borderBottom = `2px solid ${PAINT[color].solid}`;
    mark.style.paddingBottom = "1px";
  } else {
    mark.style.backgroundColor = fillFor(color, intensity);
    mark.style.borderBottom = "none";
    mark.style.paddingBottom = "0";
  }
  mark.style.color = "inherit";
  mark.style.cursor = "pointer";
}

function wrapSegment(node: Text, from: number, to: number, id: string, color: Color, style: Style, intensity: Intensity): void {
  if (to <= from) return;
  const target = from === 0 ? node : node.splitText(from);
  if (to < (node.nodeValue ?? "").length + from) target.splitText(to - from);
  const mark = document.createElement("mark");
  mark.setAttribute(HL_ATTR, id);
  mark.title = "Click for options · Alt+click to remove";
  styleMark(mark, color, style, intensity);
  target.parentNode?.replaceChild(mark, target);
  mark.appendChild(target);
}

/** Restyle an existing highlight in place — recolouring shouldn't re-anchor anything. */
function restyle(id: string, color: Color, style: Style, intensity: Intensity): void {
  for (const mark of Array.from(document.querySelectorAll(`[${HL_ATTR}="${id}"]`))) {
    styleMark(mark as HTMLElement, color, style, intensity);
  }
}

/** Paint an annotation wherever its anchor confidently locates. Returns whether it painted. */
function paint(annotation: WireAnnotation): boolean {
  const index = buildIndex();
  const located = locateAnchor(index.text, annotation.anchor);
  if (located === null) return false;
  const color = (annotation.color in PAINT ? annotation.color : "yellow") as Color;
  const style: Style = annotation.style === "underline" ? "underline" : "highlight";
  const intensity: Intensity = annotation.intensity === "light" || annotation.intensity === "strong" ? annotation.intensity : "medium";
  // Wrap in document order; splitting mutates nodes, so segments are recomputed from a fresh index each
  // time a node is split. Simpler: collect segments first, then wrap back-to-front so earlier offsets
  // stay valid.
  const segments = segmentsIn(index, located.start, located.end);
  for (const segment of segments.reverse()) {
    wrapSegment(segment.node, segment.from, segment.to, annotation.id, color, style, intensity);
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
  const t = inPageTheme(dark());
  const style = document.createElement("style");
  style.textContent = `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: ${t.font}; -webkit-font-smoothing: antialiased; }
    .bar {
      position: fixed;
      display: flex;
      align-items: center;
      gap: 5px;
      padding: 5px 7px;
      background: ${t.bg};
      color: ${t.fg};
      border: 1px solid ${t.line};
      border-radius: ${t.radius};
      box-shadow: ${t.shadow};
      font-size: 12.5px; line-height: 1.4;
      transform: scale(${ISLAND_SIZE_SCALE[islandSettings.size]});
      transform-origin: top left;
    }
    .swatch {
      width: 17px; height: 17px;
      border-radius: 50%;
      border: 2px solid ${dark() ? "rgba(255,255,255,0.14)" : "rgba(0,0,0,0.10)"};
      cursor: pointer;
      padding: 0;
      transition: transform 0.12s ease, box-shadow 0.12s ease;
    }
    .swatch:hover { transform: scale(1.18); box-shadow: 0 0 0 3px ${dark() ? "rgba(143,116,255,0.25)" : "rgba(124,92,255,0.18)"}; }
    .swatch.selected { box-shadow: 0 0 0 3px ${t.accent}; }
    .swatch-group { display: inline-flex; align-items: center; gap: 5px; }
    .divider { width: 1px; align-self: stretch; background: ${t.line}; margin: 2px 1px; }
    button.action {
      border: 0; background: none; cursor: pointer;
      color: ${t.fg}; font: inherit; font-weight: 550; padding: 4px 7px; border-radius: ${t.radiusSmall};
      transition: background 0.12s ease; white-space: nowrap;
    }
    button.action:hover { background: ${t.hover}; }
    button.action.on { background: ${t.accent}; color: ${t.accentInk}; }
    .icon-btn {
      display: inline-flex; align-items: center; justify-content: center; min-width: 24px; height: 22px;
      border: 1px solid ${t.line}; background: ${t.bg}; color: ${t.fg}; cursor: pointer;
      border-radius: ${t.radiusSmall}; font: inherit; font-size: 11px; font-weight: 600; padding: 0 6px;
      transition: background 0.12s ease, border-color 0.12s ease;
    }
    .icon-btn:hover { background: ${t.hover}; border-color: ${t.muted}; }
    textarea {
      width: 240px; min-height: 52px;
      border: 1px solid ${t.line};
      border-radius: ${t.radiusSmall};
      background: ${dark() ? "#1c1c1f" : "#faf9f8"};
      color: ${t.fg}; font: inherit; padding: 6px 8px;
      resize: vertical;
    }
    input.tags-field {
      width: 240px; border: 1px solid ${t.line}; border-radius: ${t.radiusSmall};
      background: ${dark() ? "#1c1c1f" : "#faf9f8"}; color: ${t.fg}; font: inherit; padding: 6px 8px;
    }
    .field-label { font-size: 11px; color: ${t.muted}; font-weight: 550; margin-bottom: 2px; }
    .tag-row { display: flex; flex-wrap: wrap; gap: 5px; max-width: 260px; }
    .tag-chip {
      display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 550;
      padding: 2px 8px; border-radius: 999px; background: ${t.accent}1f; color: ${dark() ? "#c7b8ff" : "#5b3fd6"};
      border: 1px solid ${t.accent}33;
    }
    .menu-quote { max-width: 260px; color: ${t.muted}; font-size: 11.5px; line-height: 1.45;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .hit { display: block; max-width: 300px; padding: 6px 8px; border-radius: ${t.radiusSmall};
      text-decoration: none; color: ${t.fg}; transition: background 0.12s ease; }
    a.hit:hover { background: ${t.hover}; }
    .hit-head { display: flex; align-items: baseline; gap: 6px; }
    .hit-title { font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .hit-src { flex: 0 0 auto; font-size: 10px; font-weight: 600; color: ${t.muted};
      border: 1px solid ${t.line}; border-radius: 999px; padding: 0 6px; text-transform: uppercase; }
    .hit-snippet { color: ${t.muted}; font-size: 11px; line-height: 1.4; margin-top: 2px;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .hit-list { display: flex; flex-direction: column; gap: 2px; max-height: 300px; overflow-y: auto; }
    .col { display: flex; flex-direction: column; gap: 7px; }
    .row { display: flex; gap: 5px; align-items: center; flex-wrap: wrap; }
    .row.end { justify-content: flex-end; }
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

/** True when the selection sits inside a rich-text editable region (a contenteditable host). */
function selectionInEditable(selection: Selection): boolean {
  const node = selection.anchorNode;
  const elt = node instanceof Element ? node : (node?.parentElement ?? null);
  const host = elt?.closest("[contenteditable]");
  return host instanceof HTMLElement && host.isContentEditable;
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
function highlightSelection(color: Color, style: Style, note?: string, intensity: Intensity = lastChoice.intensity, tags?: readonly string[]): void {
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
  const cleanTags = (tags ?? []).map((t) => t.trim().replace(/^#+/, "").trim()).filter((t) => t !== "");
  const annotation: WireAnnotation = {
    id: newId(),
    anchor,
    color,
    style,
    intensity,
    createdAt: new Date().toISOString(),
    ...(note !== undefined && note.trim() !== "" ? { note: note.trim() } : {}),
    ...(cleanTags.length > 0 ? { tags: cleanTags } : {}),
  };
  rememberChoice(color, style, intensity);

  // Paint immediately — waiting for the vault would make the toolbar feel broken — then save; failure
  // unpaints, so the page never shows a highlight the vault refused.
  const segments = segmentsIn(index, start, end);
  for (const segment of segments.reverse()) {
    wrapSegment(segment.node, segment.from, segment.to, annotation.id, color, style, intensity);
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
  // Nothing to show if every action is turned off — better to show no toolbar than an empty one.
  if (!islandActions.some((a) => a.enabled)) return;

  const bar = document.createElement("div");
  bar.className = "bar";

  let style: Style = lastChoice.style;
  let intensity: Intensity = lastChoice.intensity;
  // Captured now, while the selection is live, so the copy action has the text even after a click.
  const selectionText = window.getSelection()?.toString() ?? "";

  // Shared across the colour swatches and the two toggles: changing style or transparency repaints the
  // swatch previews. A no-op when the colours action is off (nothing in `swatches`), so the toggles still work.
  const swatches: HTMLButtonElement[] = [];
  const drawSwatches = (): void => {
    for (const swatch of swatches) {
      const color = swatch.dataset["color"] as Color;
      // Preview the actual look: a solid dot for highlight (weighted by transparency), a bar for underline.
      swatch.style.backgroundColor = style === "underline" ? PAINT[color].solid : fillFor(color, intensity);
      swatch.style.borderColor = PAINT[color].solid;
      swatch.style.borderRadius = style === "underline" ? "3px" : "50%";
      swatch.style.height = style === "underline" ? "6px" : "17px";
      swatch.style.marginTop = style === "underline" ? "6px" : "0";
    }
  };

  // One builder per action: it creates its own controls and appends them to the bar. The toolbar renders
  // whichever actions are enabled, in the order the person arranged them — nothing here is positional.
  const builders: Record<IslandActionId, () => void> = {
    colors: () => {
      const group = document.createElement("div");
      group.className = "swatch-group";
      for (const color of Object.keys(PAINT) as Color[]) {
        const swatch = document.createElement("button");
        swatch.className = "swatch";
        swatch.dataset["color"] = color;
        swatch.title = `${style === "underline" ? "Underline" : "Highlight"} ${color}`;
        swatch.addEventListener("mousedown", (event) => {
          event.preventDefault();
          highlightSelection(color, style, undefined, intensity);
        });
        swatches.push(swatch);
        group.appendChild(swatch);
      }
      bar.appendChild(group);
      drawSwatches();
    },
    style: () => {
      const styleToggle = document.createElement("button");
      styleToggle.className = "icon-btn";
      const drawToggle = (): void => {
        styleToggle.textContent = style === "underline" ? "U̲" : "H";
        styleToggle.title =
          style === "underline" ? "Shape: underline (tap for highlight)" : "Shape: highlight (tap for underline)";
      };
      styleToggle.addEventListener("mousedown", (event) => {
        event.preventDefault();
        style = style === "underline" ? "highlight" : "underline";
        drawToggle();
        drawSwatches();
      });
      drawToggle();
      bar.appendChild(styleToggle);
    },
    intensity: () => {
      const order: Intensity[] = ["light", "medium", "strong"];
      const glyph: Record<Intensity, string> = { light: "░", medium: "▒", strong: "▓" };
      const alphaToggle = document.createElement("button");
      alphaToggle.className = "icon-btn";
      const drawAlpha = (): void => {
        alphaToggle.textContent = glyph[intensity];
        alphaToggle.title = `Transparency: ${intensity} (tap to change)`;
      };
      alphaToggle.addEventListener("mousedown", (event) => {
        event.preventDefault();
        intensity = order[(order.indexOf(intensity) + 1) % order.length] ?? "medium";
        drawAlpha();
        drawSwatches();
        rememberChoice(lastChoice.color, style, intensity);
      });
      drawAlpha();
      bar.appendChild(alphaToggle);
    },
    note: () => {
      const withNote = document.createElement("button");
      withNote.className = "action";
      withNote.textContent = "＋ note";
      withNote.title = "Highlight with a note and tags";
      withNote.addEventListener("mousedown", (event) => {
        event.preventDefault();
        showNoteEditor(rect, (note, color, tags) => highlightSelection(color, style, note, intensity, tags));
      });
      bar.appendChild(withNote);
    },
    copy: () => {
      const btn = document.createElement("button");
      btn.className = "action";
      btn.textContent = "⧉ copy";
      btn.title = "Copy the selection as a quote, blockquote, or link";
      btn.addEventListener("mousedown", (event) => {
        event.preventDefault();
        showCopyMenu(rect, selectionText);
      });
      bar.appendChild(btn);
    },
    search: () => {
      // With every target turned off there is nothing the menu could offer — no button beats a dead one.
      if (!searchTargets.vault && !searchTargets.engines.some((e) => e.enabled && resolveEngine(e) !== null)) return;
      const btn = document.createElement("button");
      btn.className = "action";
      btn.textContent = "⌕ search";
      btn.title = "Search the selection in your vault or on the web";
      btn.addEventListener("mousedown", (event) => {
        event.preventDefault();
        showSearchMenu(rect, selectionText);
      });
      bar.appendChild(btn);
    },
    sticky: () => {
      const btn = document.createElement("button");
      btn.className = "action";
      btn.textContent = "▢ note";
      btn.title = "Pin a sticky note to the page, seeded with the selection";
      btn.addEventListener("mousedown", (event) => {
        event.preventDefault();
        // Drop the note just below the selection, in document coordinates, seeded with the selected text.
        clearUi();
        createStickyNote(window.scrollX + rect.left, window.scrollY + rect.bottom + 8, selectionText.trim());
      });
      bar.appendChild(btn);
    },
  };

  for (const action of islandActions) {
    if (action.enabled) builders[action.id]();
  }

  placeNear(bar, rect);
  root.appendChild(bar);
}

/** A small menu offering the three copy formats for the current selection. */
function showCopyMenu(rect: DOMRect, text: string): void {
  const root = ensureShell();
  clearUi();
  const bar = document.createElement("div");
  bar.className = "bar col";

  // A clamped preview of what's on the clipboard's way, so it's clear what's being copied.
  const preview = document.createElement("div");
  preview.className = "menu-quote";
  preview.textContent = text;
  bar.appendChild(preview);

  const options: { label: string; format: CopyFormat }[] = [
    { label: "Quote", format: "quote" },
    { label: "Blockquote", format: "blockquote" },
    { label: "Markdown link", format: "markdown-link" },
  ];
  const row = document.createElement("div");
  row.className = "row";
  for (const option of options) {
    const button = document.createElement("button");
    button.className = "action";
    button.textContent = option.label;
    button.title = `Copy as ${option.label.toLowerCase()}`;
    button.addEventListener("mousedown", (event) => {
      event.preventDefault();
      const formatted = formatCopy(option.format, text, location.href);
      void navigator.clipboard.writeText(formatted).then(
        () => toast(`Copied as ${option.label.toLowerCase()}.`),
        () => toast("Couldn't copy to the clipboard."),
      );
    });
    row.appendChild(button);
  }
  bar.appendChild(row);

  placeNear(bar, rect);
  root.appendChild(bar);
}

/**
 * The search menu: the selection, and everywhere it can be looked up. Web engines open in a new tab; the
 * vault is asked through the background worker (the token stays out of this script, as always) and answers
 * right here in the menu — "have I noted this already?" without leaving the page.
 */
function showSearchMenu(rect: DOMRect, text: string): void {
  const root = ensureShell();
  clearUi();
  const bar = document.createElement("div");
  bar.className = "bar col";

  const preview = document.createElement("div");
  preview.className = "menu-quote";
  preview.textContent = text;
  bar.appendChild(preview);

  const row = document.createElement("div");
  row.className = "row";

  if (searchTargets.vault) {
    const vaultButton = document.createElement("button");
    vaultButton.className = "action";
    vaultButton.textContent = "Your vault";
    vaultButton.title = "Search your vault for this";
    vaultButton.addEventListener("mousedown", (event) => {
      event.preventDefault();
      showVaultResults(bar, text);
    });
    row.appendChild(vaultButton);
  }

  for (const choice of searchTargets.engines) {
    if (!choice.enabled) continue;
    const engine = resolveEngine(choice);
    if (engine === null) continue;
    const button = document.createElement("button");
    button.className = "action";
    button.textContent = engine.label;
    button.title = `Search ${engine.label} for this`;
    button.addEventListener("mousedown", (event) => {
      event.preventDefault();
      window.open(searchUrl(engine.template, text), "_blank", "noopener");
      clearUi();
    });
    row.appendChild(button);
  }

  bar.appendChild(row);
  placeNear(bar, rect);
  root.appendChild(bar);
}

/** Ask the vault (via the background) and replace the menu's body with what it found. */
function showVaultResults(bar: HTMLElement, text: string): void {
  const status = document.createElement("div");
  status.className = "menu-quote";
  status.textContent = "Searching your vault…";
  bar.replaceChildren(status);

  const api = messenger();
  if (api === null) {
    status.textContent = "The extension isn't available on this page.";
    return;
  }
  void api.runtime
    .sendMessage({ type: "kvs-vault-search", query: text })
    .then((reply) => {
      const result = reply as { ok?: boolean; reason?: string; hits?: DisplayHit[] } | undefined;
      if (result?.ok !== true) {
        status.textContent = result?.reason ?? "Couldn't search your vault.";
        return;
      }
      const hits = result.hits ?? [];
      if (hits.length === 0) {
        status.textContent = "Nothing in your vault matches this.";
        return;
      }
      const list = document.createElement("div");
      list.className = "hit-list";
      for (const hit of hits) {
        // A hit with somewhere to go is a link; one without (no URL, no path) is shown but goes nowhere.
        const item = document.createElement(hit.href !== "" ? "a" : "div");
        item.className = "hit";
        if (item instanceof HTMLAnchorElement) {
          item.href = hit.href;
          item.target = "_blank";
          item.rel = "noreferrer";
        }
        const head = document.createElement("div");
        head.className = "hit-head";
        const title = document.createElement("span");
        title.className = "hit-title";
        title.textContent = hit.title;
        head.appendChild(title);
        const src = document.createElement("span");
        src.className = "hit-src";
        src.textContent = hit.source;
        head.appendChild(src);
        item.appendChild(head);
        const detail = hit.snippet !== "" ? hit.snippet : hit.location;
        if (detail !== "") {
          const snippet = document.createElement("div");
          snippet.className = "hit-snippet";
          snippet.textContent = detail;
          item.appendChild(snippet);
        }
        list.appendChild(item);
      }
      bar.replaceChildren(list);
    })
    .catch(() => {
      status.textContent = "Couldn't reach the extension.";
    });
}

/** A small note editor, used both at creation and when annotating an existing highlight. */
function showNoteEditor(
  rect: DOMRect,
  onDone: (note: string, color: Color, tags: string[]) => void,
  initial = "",
  initialTags: readonly string[] = [],
): void {
  const root = ensureShell();
  clearUi();
  const box = document.createElement("div");
  box.className = "bar col";

  const input = document.createElement("textarea");
  input.placeholder = "Your note…";
  input.value = initial;
  box.appendChild(input);

  const tagsWrap = document.createElement("div");
  const tagsLabel = document.createElement("div");
  tagsLabel.className = "field-label";
  tagsLabel.textContent = "Tags";
  const tagsInput = document.createElement("input");
  tagsInput.className = "tags-field";
  tagsInput.placeholder = "comma or space separated";
  tagsInput.value = initialTags.join(", ");
  tagsWrap.appendChild(tagsLabel);
  tagsWrap.appendChild(tagsInput);
  box.appendChild(tagsWrap);

  const rowEl = document.createElement("div");
  rowEl.className = "row";
  let chosen: Color = lastChoice.color;
  for (const color of Object.keys(PAINT) as Color[]) {
    const swatch = document.createElement("button");
    swatch.className = color === chosen ? "swatch selected" : "swatch";
    swatch.style.backgroundColor = PAINT[color].solid;
    swatch.addEventListener("click", () => {
      chosen = color;
      for (const other of Array.from(rowEl.querySelectorAll(".swatch"))) {
        (other as HTMLElement).classList.remove("selected");
      }
      swatch.classList.add("selected");
    });
    rowEl.appendChild(swatch);
  }
  box.appendChild(rowEl);

  const actions = document.createElement("div");
  actions.className = "row end";
  const parseTags = (): string[] => tagsInput.value.split(/[\s,]+/).map((x) => x.trim()).filter((x) => x !== "");
  const save = document.createElement("button");
  save.className = "action on";
  save.textContent = "Save";
  save.addEventListener("click", () => onDone(input.value, chosen, parseTags()));
  actions.appendChild(save);
  box.appendChild(actions);

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

  const rerender = (): void => showHighlightMenu(id, rect);
  const currentStyle: Style = annotation?.style === "underline" ? "underline" : "highlight";
  const currentColor = (annotation?.color !== undefined && annotation.color in PAINT ? annotation.color : "yellow") as Color;
  const currentIntensity = intensityOf(annotation);

  // The quote itself, clamped, so you know which highlight you're on.
  if (annotation?.anchor.exact !== undefined) {
    const quote = document.createElement("div");
    quote.className = "menu-quote";
    quote.textContent = annotation.anchor.exact;
    bar.appendChild(quote);
  }
  if (annotation?.note !== undefined && annotation.note.trim() !== "") {
    const note = document.createElement("div");
    note.style.maxWidth = "260px";
    note.textContent = annotation.note;
    bar.appendChild(note);
  }
  if (annotation?.tags !== undefined && annotation.tags.length > 0) {
    const tagRow = document.createElement("div");
    tagRow.className = "tag-row";
    for (const tag of annotation.tags) {
      const chip = document.createElement("span");
      chip.className = "tag-chip";
      chip.textContent = `#${tag}`;
      tagRow.appendChild(chip);
    }
    bar.appendChild(tagRow);
  }

  // Recolour in place — the anchor doesn't change, so nothing re-anchors.
  const swatchRow = document.createElement("div");
  swatchRow.className = "row";
  for (const color of Object.keys(PAINT) as Color[]) {
    const swatch = document.createElement("button");
    swatch.className = color === currentColor ? "swatch selected" : "swatch";
    swatch.style.backgroundColor = PAINT[color].solid;
    swatch.title = `Recolour ${color}`;
    swatch.addEventListener("click", () => {
      if (annotation === undefined) return;
      const updated: WireAnnotation = { ...annotation, color };
      live.set(id, updated);
      restyle(id, color, currentStyle, currentIntensity);
      rememberChoice(color, currentStyle, currentIntensity);
      void saveAnnotation(updated);
      rerender();
    });
    swatchRow.appendChild(swatch);
  }
  bar.appendChild(swatchRow);

  // Shape and transparency, matching the toolbar's controls.
  const controls = document.createElement("div");
  controls.className = "row";

  const shapeBtn = document.createElement("button");
  shapeBtn.className = "icon-btn";
  shapeBtn.textContent = currentStyle === "underline" ? "U̲" : "H";
  shapeBtn.title = currentStyle === "underline" ? "Shape: underline" : "Shape: highlight";
  shapeBtn.addEventListener("click", () => {
    if (annotation === undefined) return;
    const nextStyle: Style = currentStyle === "underline" ? "highlight" : "underline";
    const updated: WireAnnotation = { ...annotation, style: nextStyle };
    live.set(id, updated);
    restyle(id, currentColor, nextStyle, currentIntensity);
    void saveAnnotation(updated);
    rerender();
  });
  controls.appendChild(shapeBtn);

  const order: Intensity[] = ["light", "medium", "strong"];
  const glyph: Record<Intensity, string> = { light: "░", medium: "▒", strong: "▓" };
  const alphaBtn = document.createElement("button");
  alphaBtn.className = "icon-btn";
  alphaBtn.textContent = glyph[currentIntensity];
  alphaBtn.title = `Transparency: ${currentIntensity}`;
  alphaBtn.addEventListener("click", () => {
    if (annotation === undefined) return;
    const next = order[(order.indexOf(currentIntensity) + 1) % order.length] ?? "medium";
    const updated: WireAnnotation = { ...annotation, intensity: next };
    live.set(id, updated);
    restyle(id, currentColor, currentStyle, next);
    rememberChoice(currentColor, currentStyle, next);
    void saveAnnotation(updated);
    rerender();
  });
  controls.appendChild(alphaBtn);

  const copyButton = document.createElement("button");
  copyButton.className = "action";
  copyButton.textContent = "Copy";
  copyButton.title = "Copy the highlighted text";
  copyButton.addEventListener("click", () => {
    void navigator.clipboard.writeText(annotation?.anchor.exact ?? "").catch(() => undefined);
    clearUi();
  });
  controls.appendChild(copyButton);
  bar.appendChild(controls);

  // Note + tags editor, and removal.
  const rowEl = document.createElement("div");
  rowEl.className = "row end";

  const noteButton = document.createElement("button");
  noteButton.className = "action";
  noteButton.textContent = annotation?.note === undefined || annotation.note.trim() === "" ? "＋ note & tags" : "Edit note & tags";
  noteButton.addEventListener("click", () => {
    showNoteEditor(
      rect,
      (note, _color, tags) => {
        if (annotation === undefined) return;
        const updated: WireAnnotation = {
          ...annotation,
          ...(note.trim() !== "" ? { note: note.trim() } : {}),
          ...(tags.length > 0 ? { tags } : {}),
        };
        live.set(id, updated);
        clearUi();
        void saveAnnotation(updated);
      },
      annotation?.note ?? "",
      annotation?.tags ?? [],
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
      | {
          annotations?: WireAnnotation[];
          palette?: readonly { name?: string; hex?: string; rgb?: readonly [number, number, number] }[];
        }
      | undefined;
    // Adopt the vault's palette (if the plugin sent one) before painting, so restored highlights and the
    // toolbar swatches use the vault's colours from the first frame rather than the built-in defaults.
    if (reply?.palette) applyPalette(reply.palette);
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
  // Re-pin sticky notes after highlights, so the palette pushed with the highlights is already in force.
  void restoreStickies();
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
    const prefs = stored["preferences"] as
      | {
          annotationSidebar?: boolean;
          stickyLauncher?: boolean;
          islandActions?: unknown;
          islandSettings?: unknown;
          searchTargets?: unknown;
          autoActions?: unknown;
        }
      | undefined;
    sidebarEnabled = prefs?.annotationSidebar === true;
    stickyLauncherEnabled = prefs?.stickyLauncher === true;
    islandActions = normalizeIslandActions(prefs?.islandActions);
    islandSettings = normalizeIslandSettings(prefs?.islandSettings);
    searchTargets = normalizeSearchTargets(prefs?.searchTargets);
    activeAutoAction = matchAutoAction(normalizeSiteAutoActions(prefs?.autoActions), location.href);
    if (sidebarEnabled) mountLauncher();
    if (stickyLauncherEnabled) mountStickyLauncher();
    applyPageLoadAutoActions();
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
  if (annotation.tags !== undefined && annotation.tags.length > 0) {
    const tagRow = document.createElement("div");
    tagRow.className = "kvs-sb-tags";
    for (const tag of annotation.tags) {
      const chip = document.createElement("span");
      chip.className = "kvs-sb-chip";
      chip.textContent = `#${tag}`;
      tagRow.appendChild(chip);
    }
    body.appendChild(tagRow);
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
  const t = inPageTheme(isDark);
  const bg = t.bg;
  const fg = t.fg;
  const muted = t.muted;
  const line = t.line;
  const hover = t.hover;
  const accent = t.accent;
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
    .kvs-sb-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
    .kvs-sb-chip {
      font-size: 10.5px; font-weight: 550; padding: 1px 7px; border-radius: 999px;
      background: ${accent}1f; color: ${isDark ? "#c7b8ff" : "#5b3fd6"}; border: 1px solid ${accent}33;
    }
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

// ----------------------------------------------------------------- sticky notes
//
// A sticky note is the highlighter's opposite number: not anchored to text, but pinned to a place on the
// page — a draggable, resizable card holding rich markdown, in one of the same eight palette colours. It
// lives in its own persistent shadow host (absolutely positioned at the document origin, so notes scroll
// with the page they're pinned to), is saved to the vault through the background worker, and re-pins itself
// on revisit. Everything here is additive: the highlighter and sidebar behave identically whether or not a
// single sticky note exists.

let stickyShell: HTMLElement | null = null;
let stickyShadow: ShadowRoot | null = null;
/** Live notes on this page, id → its model and its card element. */
const stickies = new Map<string, { note: StickyNote; card: HTMLElement }>();
/** Per-note debounce timers for auto-save, so typing doesn't hammer the vault. */
const stickySaveTimers = new Map<string, number>();

/** The persistent shadow host for sticky notes — absolute at the document origin, so cards scroll with the page. */
function ensureStickyShell(): ShadowRoot {
  if (stickyShadow !== null) return stickyShadow;
  stickyShell = document.createElement("div");
  stickyShell.style.position = "absolute";
  stickyShell.style.zIndex = "2147483645";
  stickyShell.style.top = "0";
  stickyShell.style.left = "0";
  stickyShell.style.width = "0";
  stickyShell.style.height = "0";
  stickyShadow = stickyShell.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = stickyStyles();
  stickyShadow.appendChild(style);
  document.documentElement.appendChild(stickyShell);
  return stickyShadow;
}

/** ISO timestamp helper — one place, so created/updated read consistently. */
function nowIso(): string {
  return new Date().toISOString();
}

/**
 * Create a new sticky note at a document position, seeded with optional markdown, and open it for editing.
 * Nothing is saved until the note has a non-empty body (an empty note left behind simply vanishes).
 */
function createStickyNote(x: number, y: number, seed = ""): void {
  const note: StickyNote = {
    id: stickyId(),
    url: location.href,
    color: lastChoice.color,
    body: seed,
    // Keep a fresh note fully on-screen even if dropped near an edge.
    x: Math.max(8, Math.min(x, window.scrollX + window.innerWidth - STICKY_DEFAULT_W - 8)),
    y: Math.max(8, y),
    w: STICKY_DEFAULT_W,
    h: STICKY_DEFAULT_H,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
  renderStickyCard(note, { editing: true });
  refreshStickyLauncher();
}

/** Update a note's model in place and re-persist it (debounced) — the single mutation path. */
function updateSticky(id: string, patch: Partial<StickyNote>, opts: { immediate?: boolean } = {}): void {
  const entry = stickies.get(id);
  if (entry === undefined) return;
  const next: StickyNote = { ...entry.note, ...patch, updatedAt: nowIso() };
  entry.note = next;
  if (opts.immediate === true) {
    flushStickySave(id);
  } else {
    scheduleStickySave(id);
  }
}

/** Debounce a save for a note; a burst of edits collapses to one write shortly after typing stops. */
function scheduleStickySave(id: string): void {
  const existing = stickySaveTimers.get(id);
  if (existing !== undefined) window.clearTimeout(existing);
  stickySaveTimers.set(
    id,
    window.setTimeout(() => flushStickySave(id), 800),
  );
}

/** Write a note to the vault now (if it has a body worth saving). */
function flushStickySave(id: string): void {
  const timer = stickySaveTimers.get(id);
  if (timer !== undefined) {
    window.clearTimeout(timer);
    stickySaveTimers.delete(id);
  }
  const entry = stickies.get(id);
  if (entry === undefined || entry.note.body.trim() === "") return;
  const api = messenger();
  if (api === null) return;
  const note = entry.note;
  void api.runtime
    .sendMessage({
      type: "kvs-sticky-save",
      url: location.href,
      note: {
        id: note.id,
        color: note.color,
        body: note.body,
        x: note.x,
        y: note.y,
        w: note.w,
        h: note.h,
        createdAt: note.createdAt,
        updatedAt: note.updatedAt,
      },
      fields: pageMetadataFields(),
    })
    .then((reply) => {
      const result = reply as { ok?: boolean; reason?: string } | undefined;
      if (result?.ok !== true) toast(`Sticky note not saved: ${result?.reason ?? "your vault refused it."}`);
    })
    .catch(() => undefined);
}

/** Remove a note: its card, its local state, and its vault copy (sidecar + cell line). */
function deleteSticky(id: string): void {
  const entry = stickies.get(id);
  if (entry === undefined) return;
  entry.card.remove();
  stickies.delete(id);
  const timer = stickySaveTimers.get(id);
  if (timer !== undefined) window.clearTimeout(timer);
  stickySaveTimers.delete(id);
  refreshStickyLauncher();
  const api = messenger();
  // Only ask the vault to forget it if it was ever saved (a body-less note never reached the vault).
  if (api !== null && entry.note.body.trim() !== "") {
    void api.runtime.sendMessage({ type: "kvs-sticky-remove", url: location.href, id }).catch(() => undefined);
  }
}

/** The rgba tint for a sticky card of a colour, at a chosen alpha, in the page's scheme. */
function stickyTint(color: Color, alpha: number): string {
  const [r, g, b] = PAINT[color].rgb;
  return `rgba(${String(r)}, ${String(g)}, ${String(b)}, ${String(alpha)})`;
}

/**
 * Draw (or redraw) a note's card: header with drag handle + copy + delete, a body that renders markdown and
 * switches to a textarea to edit, and a footer with the eight colour dots, a Save button, and a resize grip.
 */
function renderStickyCard(note: StickyNote, opts: { editing?: boolean } = {}): void {
  const root = ensureStickyShell();
  const existing = stickies.get(note.id);
  const card = existing?.card ?? document.createElement("div");
  stickies.set(note.id, { note, card });
  card.className = `sticky ${dark() ? "dark" : ""}`;
  card.textContent = "";

  const applyBox = (): void => {
    card.style.left = `${String(note.x)}px`;
    card.style.top = `${String(note.y)}px`;
    card.style.width = `${String(note.w)}px`;
    card.style.height = `${String(note.h)}px`;
    card.style.background = stickyTint(note.color, dark() ? 0.16 : 0.14);
    card.style.borderColor = PAINT[note.color].solid;
  };
  applyBox();

  // Header — drag handle, spacer, copy, delete.
  const header = document.createElement("div");
  header.className = "sticky-head";
  header.style.background = stickyTint(note.color, dark() ? 0.28 : 0.24);
  const grip = document.createElement("span");
  grip.className = "sticky-grip";
  grip.textContent = "⠿";
  grip.title = "Drag to move";
  header.appendChild(grip);
  const spacer = document.createElement("span");
  spacer.className = "sticky-spacer";
  header.appendChild(spacer);

  const copyBtn = document.createElement("button");
  copyBtn.className = "sticky-icon";
  copyBtn.textContent = "⧉";
  copyBtn.title = "Copy the note's markdown";
  copyBtn.addEventListener("mousedown", (event) => event.stopPropagation());
  copyBtn.addEventListener("click", () => {
    void navigator.clipboard.writeText(stickies.get(note.id)?.note.body ?? "").then(
      () => toast("Sticky note copied."),
      () => toast("Couldn't copy to the clipboard."),
    );
  });
  header.appendChild(copyBtn);

  const delBtn = document.createElement("button");
  delBtn.className = "sticky-icon sticky-del";
  delBtn.textContent = "×";
  delBtn.title = "Delete this note";
  delBtn.addEventListener("mousedown", (event) => event.stopPropagation());
  delBtn.addEventListener("click", () => deleteSticky(note.id));
  header.appendChild(delBtn);
  card.appendChild(header);

  // Body — rendered markdown, or a textarea while editing. Click the rendered body to edit; blur to render.
  const body = document.createElement("div");
  body.className = "sticky-body";

  const showRendered = (): void => {
    body.textContent = "";
    const view = document.createElement("div");
    view.className = "sticky-md";
    const current = stickies.get(note.id)?.note.body ?? "";
    if (current.trim() === "") {
      view.innerHTML = '<p class="sticky-empty">Empty note — click to write. Markdown works.</p>';
    } else {
      view.innerHTML = renderMarkdown(current);
    }
    view.addEventListener("click", (event) => {
      // A click that's selecting a link shouldn't drop into edit mode.
      if (event.target instanceof HTMLAnchorElement) return;
      showEditor();
    });
    body.appendChild(view);
  };

  const showEditor = (): void => {
    body.textContent = "";
    const textarea = document.createElement("textarea");
    textarea.className = "sticky-input";
    textarea.value = stickies.get(note.id)?.note.body ?? "";
    textarea.placeholder = "Write in markdown…";
    textarea.addEventListener("input", () => updateSticky(note.id, { body: textarea.value }));
    textarea.addEventListener("blur", () => {
      // Persist immediately on blur, then render — unless the note is empty and never saved, in which case
      // it stays editable (removing it is the delete button's job, not an accidental blur's).
      flushStickySave(note.id);
      showRendered();
    });
    body.appendChild(textarea);
    textarea.focus();
    // Cursor to the end so seeded text is ready to extend.
    textarea.setSelectionRange(textarea.value.length, textarea.value.length);
  };

  card.appendChild(body);

  // Footer — the eight colour dots, a Save button, and the resize grip.
  const footer = document.createElement("div");
  footer.className = "sticky-foot";
  const dots = document.createElement("div");
  dots.className = "sticky-colors";
  for (const color of Object.keys(PAINT) as Color[]) {
    const dot = document.createElement("button");
    dot.className = color === note.color ? "sticky-dot selected" : "sticky-dot";
    dot.style.background = PAINT[color].solid;
    dot.title = `Colour: ${color}`;
    dot.addEventListener("mousedown", (event) => event.stopPropagation());
    dot.addEventListener("click", () => {
      updateSticky(note.id, { color }, { immediate: true });
      rememberChoice(color, lastChoice.style, lastChoice.intensity);
      const live = stickies.get(note.id);
      if (live !== undefined) renderStickyCard(live.note, { editing: body.querySelector(".sticky-input") !== null });
    });
    dots.appendChild(dot);
  }
  footer.appendChild(dots);

  const saveBtn = document.createElement("button");
  saveBtn.className = "sticky-save";
  saveBtn.textContent = "Save";
  saveBtn.title = "Save to your vault now";
  saveBtn.addEventListener("mousedown", (event) => event.stopPropagation());
  saveBtn.addEventListener("click", () => {
    const area = body.querySelector(".sticky-input");
    if (area instanceof HTMLTextAreaElement) updateSticky(note.id, { body: area.value });
    flushStickySave(note.id);
    toast("Sticky note saved.");
    showRendered();
  });
  footer.appendChild(saveBtn);

  const resize = document.createElement("div");
  resize.className = "sticky-resize";
  resize.title = "Drag to resize";
  footer.appendChild(resize);
  card.appendChild(footer);

  if (opts.editing === true) showEditor();
  else showRendered();

  makeStickyDraggable(note.id, card, header);
  makeStickyResizable(note.id, card, resize);

  if (existing === undefined) root.appendChild(card);
}

/** Drag a note by its header, in document coordinates, persisting the new position when the drag ends. */
function makeStickyDraggable(id: string, card: HTMLElement, handle: HTMLElement): void {
  handle.addEventListener("mousedown", (event) => {
    if (event.target instanceof HTMLButtonElement) return; // header buttons aren't drag starts
    event.preventDefault();
    const start = stickies.get(id)?.note;
    if (start === undefined) return;
    const originX = start.x;
    const originY = start.y;
    const startClientX = event.clientX;
    const startClientY = event.clientY;
    const onMove = (move: MouseEvent): void => {
      const x = Math.max(0, originX + (move.clientX - startClientX));
      const y = Math.max(0, originY + (move.clientY - startClientY));
      card.style.left = `${String(x)}px`;
      card.style.top = `${String(y)}px`;
      const entry = stickies.get(id);
      if (entry !== undefined) entry.note = { ...entry.note, x, y };
    };
    const onUp = (): void => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      updateSticky(id, {}, { immediate: true });
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  });
}

/** Resize a note from its corner grip, clamped to the model's bounds, persisting when the drag ends. */
function makeStickyResizable(id: string, card: HTMLElement, handle: HTMLElement): void {
  handle.addEventListener("mousedown", (event) => {
    event.preventDefault();
    event.stopPropagation();
    const start = stickies.get(id)?.note;
    if (start === undefined) return;
    const originW = start.w;
    const originH = start.h;
    const startClientX = event.clientX;
    const startClientY = event.clientY;
    const onMove = (move: MouseEvent): void => {
      const w = Math.max(STICKY_MIN_W, Math.min(STICKY_MAX_W, originW + (move.clientX - startClientX)));
      const h = Math.max(STICKY_MIN_H, Math.min(STICKY_MAX_H, originH + (move.clientY - startClientY)));
      card.style.width = `${String(w)}px`;
      card.style.height = `${String(h)}px`;
      const entry = stickies.get(id);
      if (entry !== undefined) entry.note = { ...entry.note, w, h };
    };
    const onUp = (): void => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      updateSticky(id, {}, { immediate: true });
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  });
}

/** Re-pin every sticky note the vault holds for this page. Called once on load, after highlights restore. */
async function restoreStickies(): Promise<void> {
  const api = messenger();
  if (api === null) return;
  try {
    const reply = (await api.runtime.sendMessage({ type: "kvs-stickies-for", url: location.href })) as
      | {
          ok?: boolean;
          notes?: (Partial<StickyNote> & { id: string; body: string })[];
          palette?: readonly { name?: string; hex?: string; rgb?: readonly [number, number, number] }[];
        }
      | undefined;
    if (reply?.palette) applyPalette(reply.palette);
    for (const raw of reply?.notes ?? []) {
      const note: StickyNote = {
        id: raw.id,
        url: location.href,
        color: (raw.color !== undefined && raw.color in PAINT ? raw.color : "yellow") as Color,
        body: raw.body,
        x: typeof raw.x === "number" ? raw.x : 24,
        y: typeof raw.y === "number" ? raw.y : 24,
        w: typeof raw.w === "number" ? raw.w : STICKY_DEFAULT_W,
        h: typeof raw.h === "number" ? raw.h : STICKY_DEFAULT_H,
        createdAt: raw.createdAt ?? nowIso(),
        updatedAt: raw.updatedAt ?? nowIso(),
      };
      renderStickyCard(note);
    }
    refreshStickyLauncher();
  } catch {
    // Vault unreachable — notes re-pin next time it is. Nothing shown; the page is still fully usable.
  }
}

// The launcher: a small button that drops a fresh note in the middle of the current view.
let stickyLauncher: HTMLButtonElement | null = null;
function mountStickyLauncher(): void {
  if (stickyLauncher !== null) return;
  const root = ensureStickyShell();
  stickyLauncher = document.createElement("button");
  stickyLauncher.className = "kvs-sticky-launcher";
  stickyLauncher.type = "button";
  stickyLauncher.title = "Pin a new sticky note to this page";
  stickyLauncher.textContent = "▢";
  stickyLauncher.addEventListener("click", () => {
    // Drop it near the top-left of the current viewport, in document coordinates.
    createStickyNote(window.scrollX + 40, window.scrollY + 90);
  });
  root.appendChild(stickyLauncher);
  refreshStickyLauncher();
}

/** Keep the launcher's badge in step with how many notes are on the page. */
function refreshStickyLauncher(): void {
  if (stickyLauncher === null) return;
  stickyLauncher.setAttribute("data-count", String(stickies.size));
}

/** The stylesheet for the sticky cards and their launcher — theme-aware, in the shadow root. */
function stickyStyles(): string {
  const isDark = dark();
  const t = inPageTheme(isDark);
  return `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: ${t.font}; -webkit-font-smoothing: antialiased; }
    .sticky {
      position: absolute; display: flex; flex-direction: column;
      border: 1px solid; border-radius: 12px; overflow: hidden;
      box-shadow: 0 6px 22px rgba(0,0,0,${isDark ? "0.45" : "0.18"});
      color: ${t.fg}; font-size: 13px; line-height: 1.5;
      backdrop-filter: blur(2px);
    }
    .sticky-head {
      display: flex; align-items: center; gap: 6px; padding: 5px 8px; cursor: move; user-select: none;
      border-bottom: 1px solid ${t.line};
    }
    .sticky-grip { color: ${t.muted}; font-size: 14px; letter-spacing: -2px; line-height: 1; }
    .sticky-spacer { flex: 1 1 auto; }
    .sticky-icon {
      border: none; background: transparent; color: ${t.muted}; cursor: pointer; font-size: 15px;
      line-height: 1; padding: 2px 5px; border-radius: 6px; transition: background 0.12s ease, color 0.12s ease;
    }
    .sticky-icon:hover { background: ${t.hover}; color: ${t.fg}; }
    .sticky-del:hover { background: ${isDark ? "#3a2a2e" : "#fdeaee"}; color: #e0526a; }
    .sticky-body { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 8px 10px; }
    .sticky-body::-webkit-scrollbar { width: 8px; }
    .sticky-body::-webkit-scrollbar-thumb { background: ${t.line}; border-radius: 8px; border: 2px solid transparent; background-clip: content-box; }
    .sticky-md { overflow-wrap: anywhere; }
    .sticky-md p { margin: 0 0 8px; }
    .sticky-md h1, .sticky-md h2, .sticky-md h3, .sticky-md h4 { margin: 6px 0; line-height: 1.25; }
    .sticky-md h1 { font-size: 17px; } .sticky-md h2 { font-size: 15.5px; } .sticky-md h3 { font-size: 14px; }
    .sticky-md ul, .sticky-md ol { margin: 4px 0 8px; padding-left: 20px; }
    .sticky-md li { margin: 2px 0; }
    .sticky-md a { color: ${t.accent}; text-decoration: underline; }
    .sticky-md code { background: ${t.hover}; padding: 1px 5px; border-radius: 5px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
    .sticky-md pre { background: ${isDark ? "#15151a" : "#f4f3f2"}; padding: 8px 10px; border-radius: 8px; overflow-x: auto; margin: 6px 0; }
    .sticky-md pre code { background: none; padding: 0; }
    .sticky-md blockquote { margin: 6px 0; padding-left: 10px; border-left: 3px solid ${t.line}; color: ${t.muted}; }
    .sticky-md hr { border: none; border-top: 1px solid ${t.line}; margin: 8px 0; }
    .sticky-empty { color: ${t.muted}; font-style: italic; }
    .sticky-input {
      width: 100%; height: 100%; min-height: 60px; border: none; outline: none; resize: none;
      background: transparent; color: ${t.fg}; font: inherit; padding: 0;
    }
    .sticky-foot {
      display: flex; align-items: center; gap: 6px; padding: 5px 8px; border-top: 1px solid ${t.line};
      position: relative;
    }
    .sticky-colors { display: flex; gap: 4px; flex: 1 1 auto; flex-wrap: wrap; }
    .sticky-dot {
      width: 13px; height: 13px; border-radius: 50%; border: 1px solid ${isDark ? "rgba(255,255,255,0.18)" : "rgba(0,0,0,0.14)"};
      cursor: pointer; padding: 0; transition: transform 0.1s ease;
    }
    .sticky-dot:hover { transform: scale(1.22); }
    .sticky-dot.selected { box-shadow: 0 0 0 2px ${t.accent}; }
    .sticky-save {
      border: 1px solid ${t.line}; background: ${t.accent}; color: ${t.accentInk}; cursor: pointer;
      font: inherit; font-weight: 600; font-size: 11.5px; padding: 3px 10px; border-radius: 7px;
    }
    .sticky-save:hover { filter: brightness(1.05); }
    .sticky-resize {
      width: 14px; height: 14px; flex: 0 0 auto; cursor: nwse-resize; margin-left: 2px;
      background:
        linear-gradient(135deg, transparent 0 55%, ${t.muted} 55% 62%, transparent 62% 74%, ${t.muted} 74% 82%, transparent 82%);
      border-radius: 0 0 6px 0;
    }
    .kvs-sticky-launcher {
      position: fixed; right: 18px; bottom: 70px; width: 44px; height: 44px; border-radius: 50%;
      border: none; background: ${t.accent}; color: #fff; cursor: pointer; font-size: 20px; line-height: 1;
      display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 14px rgba(0,0,0,0.25);
      transition: transform 0.15s ease, box-shadow 0.15s ease;
    }
    .kvs-sticky-launcher:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(0,0,0,0.3); }
  `;
}

// ------------------------------------------------------------- per-site auto-actions
//
// A per-site rule can make a chosen thing happen without a click: fire an action the moment text is
// selected, and/or bring the sidebar or sticky launcher up when a matching page loads. The matching (by
// host, most-specific-wins) and the rules live in ./lib/auto-actions; this is the half that acts on them,
// wiring each effect to the annotator function that already does it.

/** Run the on-selection action for the active site rule, on the current selection. */
function performAutoSelect(rect: DOMRect, text: string): void {
  const rule = activeAutoAction;
  if (rule === null) return;
  switch (rule.onSelect) {
    case "highlight":
      highlightSelection(rule.color ?? "yellow", rule.style ?? "highlight", undefined, rule.intensity ?? "medium");
      break;
    case "copy": {
      const formatted = formatCopy(rule.copyFormat ?? "quote", text, location.href);
      void navigator.clipboard.writeText(formatted).then(
        () => toast(`Copied as ${(rule.copyFormat ?? "quote").replace("-", " ")}.`),
        () => toast("Couldn't copy to the clipboard."),
      );
      break;
    }
    case "sticky":
      clearUi();
      createStickyNote(window.scrollX + rect.left, window.scrollY + rect.bottom + 8, text.trim());
      // A sticky note opens its own editor; showing the toolbar on top would fight it.
      return;
    case "note":
      showNoteEditor(rect, (note, color, tags) =>
        highlightSelection(color, rule.style ?? "highlight", note, rule.intensity ?? "medium", tags),
      );
      // The note editor is the UI now; don't also raise the toolbar.
      return;
    case "none":
      return;
  }
  // For highlight/copy, the expert per-rule choice decides whether the toolbar still appears afterwards.
  if (rule.alsoShowToolbar === true) showToolbar(rect);
}

/** Apply the active site rule's page-load effects: open the sidebar and/or show the sticky launcher. */
function applyPageLoadAutoActions(): void {
  const rule = activeAutoAction;
  if (rule === null) return;
  // A site rule is more specific than the global toggles, so it opens these even when they're off globally.
  if (rule.openSidebar === true) {
    mountLauncher();
    openSidebar();
  }
  if (rule.showStickyLauncher === true) mountStickyLauncher();
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
    const altHeld = event.altKey; // captured now — the event is stale inside the timeout below
    window.setTimeout(() => {
      const selection = window.getSelection();
      const text = selection?.toString().trim() ?? "";
      // The behaviour settings decide whether this selection earns a toolbar; anything else dismisses it.
      const show =
        selection !== null &&
        !selection.isCollapsed &&
        text !== "" &&
        islandSettings.trigger !== "off" &&
        (islandSettings.trigger !== "hold-alt" || altHeld) &&
        text.length >= islandSettings.minChars &&
        (islandSettings.inEditable || !selectionInEditable(selection));
      if (!show || selection === null) {
        clearUi();
        return;
      }
      const rect = selection.getRangeAt(0).getBoundingClientRect();
      // A per-site rule can act on the selection directly instead of raising the toolbar.
      if (activeAutoAction !== null && activeAutoAction.onSelect !== "none") {
        performAutoSelect(rect, text);
        return;
      }
      showToolbar(rect);
    }, 10);
  });

  // The toolbar is pinned in place, so it lingers when the page scrolls out from under it. Dismiss it on
  // scroll when the person has asked for that; otherwise leave it (the default, and the old behaviour).
  window.addEventListener(
    "scroll",
    () => {
      if (islandSettings.hideOnScroll) clearUi();
    },
    { passive: true },
  );

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
