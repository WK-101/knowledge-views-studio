import { HIGHLIGHT_COLORS, type HighlightColor } from "./annotations";

/**
 * The sticky-note model.
 *
 * A sticky note is the highlighter's opposite number: where a highlight is *anchored to text* and painted
 * where those words are, a sticky note is *pinned to a place* on the page — a free-floating card you drag
 * where you want it, at whatever size you want, holding a scrap of rich markdown. It shares the highlighter's
 * palette (the same eight Zotero colours) and its storage discipline (a sidecar is the machine's truth; a
 * chosen row cell gets a readable copy), but not its anchoring: nothing about a sticky note depends on the
 * page's text, so it survives edits to the page that would move or orphan a highlight.
 *
 * Two copies, deliberately — one fewer than a highlight:
 *
 *  - the **sidecar**, structured JSON keyed by URL, which is what re-pins every note on revisit;
 *  - the page's **row cell**, in whichever column the person chose, where the note's text lands as a
 *    one-line copy carrying a hidden id so an edit updates exactly its own line and a delete removes it.
 *
 * There is no dedicated-note copy: a sticky note is a margin scribble, not a literature note, and forcing it
 * into `## Annotations` alongside quoted highlights would misrepresent what it is.
 */

/** One sticky note, pinned to a page at a position and size, holding markdown. */
export interface StickyNote {
  readonly id: string;
  /** The page it's pinned to. */
  readonly url: string;
  /** One of the eight palette colours — the same set highlights use. */
  readonly color: HighlightColor;
  /** The note's rich-markdown body. */
  readonly body: string;
  /** Document x/y of the note's top-left, in page pixels (so it pins to the page, not the viewport). */
  readonly x: number;
  readonly y: number;
  /** The note's size in pixels, clamped to sane bounds on read. */
  readonly w: number;
  readonly h: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** The size bounds a note is held within — a guard against a corrupt or hostile stored value, not a UI limit. */
export const STICKY_MIN_W = 180;
export const STICKY_MIN_H = 110;
export const STICKY_MAX_W = 760;
export const STICKY_MAX_H = 900;
export const STICKY_DEFAULT_W = 260;
export const STICKY_DEFAULT_H = 200;

/** A page's sticky notes, as the sidecar holds them. */
export interface PageStickies {
  readonly url: string;
  readonly notes: readonly StickyNote[];
}

/** A short random id — enough to tell a page's notes apart. */
export function stickyId(random: () => number = Math.random): string {
  return Array.from({ length: 10 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(random() * 36)]).join(
    "",
  );
}

function coerceColor(raw: unknown): HighlightColor {
  return typeof raw === "string" && (HIGHLIGHT_COLORS as readonly string[]).includes(raw)
    ? (raw as HighlightColor)
    : "yellow";
}

/** A finite number clamped to [lo, hi]; the fallback when it isn't a usable number at all. */
function clampNumber(raw: unknown, lo: number, hi: number, fallback: number): number {
  return typeof raw === "number" && Number.isFinite(raw) ? Math.max(lo, Math.min(hi, raw)) : fallback;
}

/**
 * Read one stored sticky note into the model, or null if it can't be trusted.
 *
 * The sidecar is a file that can be hand-edited, synced with conflicts, or written by a future version. A
 * note with no id, no url, or no body is dropped rather than allowed to render as an empty ghost; position
 * and size are clamped rather than rejected, so a note that merely drifted off-screen still comes back.
 */
export function coerceStickyNote(raw: unknown): StickyNote | null {
  if (raw === null || typeof raw !== "object") return null;
  const entry = raw as Record<string, unknown>;
  if (typeof entry["id"] !== "string" || entry["id"] === "") return null;
  if (typeof entry["url"] !== "string" || entry["url"] === "") return null;
  const body = typeof entry["body"] === "string" ? entry["body"] : "";
  if (body.trim() === "") return null;
  const created = typeof entry["createdAt"] === "string" ? entry["createdAt"] : new Date(0).toISOString();
  return {
    id: entry["id"],
    url: entry["url"],
    color: coerceColor(entry["color"]),
    body,
    x: clampNumber(entry["x"], 0, 1_000_000, 24),
    y: clampNumber(entry["y"], 0, 10_000_000, 24),
    w: clampNumber(entry["w"], STICKY_MIN_W, STICKY_MAX_W, STICKY_DEFAULT_W),
    h: clampNumber(entry["h"], STICKY_MIN_H, STICKY_MAX_H, STICKY_DEFAULT_H),
    createdAt: created,
    updatedAt: typeof entry["updatedAt"] === "string" ? entry["updatedAt"] : created,
  };
}

/** Add a note to a page's set, replacing any earlier one with the same id. */
export function withStickyNote(page: PageStickies, note: StickyNote): PageStickies {
  const kept = page.notes.filter((n) => n.id !== note.id);
  return { url: page.url, notes: [...kept, note] };
}

/** Remove one note by id. Removing something already gone is not an error. */
export function withoutStickyNote(page: PageStickies, id: string): PageStickies {
  return { url: page.url, notes: page.notes.filter((n) => n.id !== id) };
}

/** Fold any whitespace runs into single spaces — a cell copy must stay on one line. */
function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/**
 * The hidden marker that ties a cell line to its sticky note.
 *
 * An HTML comment, so it doesn't render in the cell, and carries the note's id so an edit updates exactly
 * its own line and a delete removes it — regardless of how the body text has changed since. This is what
 * lets a sticky note's cell copy stay in sync through edits without the whole-line matching a highlight
 * uses (a highlight's text is fixed; a sticky note's is not).
 */
export function stickyMarker(id: string): string {
  return `<!--kvs-sticky:${id}-->`;
}

/**
 * The note as it joins the row's cell: the hidden id marker, then the body collapsed to one line so it sits
 * as a single `<br>`-separated entry alongside anything else in the column. Inline markdown (bold, links,
 * code) survives; hard line breaks fold to spaces, because a cell is one line by construction.
 */
export function stickyCellText(note: StickyNote): string {
  return `${stickyMarker(note.id)}${oneLine(note.body)}`;
}
