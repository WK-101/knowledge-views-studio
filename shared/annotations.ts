import type { Annotation, TextAnchor } from "./protocol";

/**
 * The annotation model.
 *
 * One annotation lives in three places, each serving a different reader:
 *
 *  - the **sidecar**, structured JSON the plugin keeps, which is what repaints highlights on revisit — the
 *    machine's copy, carrying anchors and colours no person should have to look at;
 *  - the page's **row**, where the highlight text (and note) joins the annotations cell — the glanceable
 *    copy, readable in any table view;
 *  - the row's **dedicated note**, when there is one, where it lands under `## Annotations` as a proper
 *    blockquote — the writing-up copy.
 *
 * Splitting them is the design. The alternatives are worse in both directions: structured metadata in a
 * cell means anchors and colour codes staring out of every table; markdown as the machine's source of truth
 * means parsing prose to repaint a page, which breaks the first time someone edits their own note.
 */

export type HighlightColor = "yellow" | "green" | "blue" | "red" | "purple" | "orange";

export const HIGHLIGHT_COLORS: readonly HighlightColor[] = [
  "yellow",
  "green",
  "blue",
  "red",
  "purple",
  "orange",
];

/** How a highlight is drawn: painted over, or underlined beneath. */
export type HighlightStyle = "highlight" | "underline";

/** How strongly a highlight is painted — the transparency choice. */
export type HighlightIntensity = "light" | "medium" | "strong";

export const HIGHLIGHT_INTENSITIES: readonly HighlightIntensity[] = ["light", "medium", "strong"];

export interface StoredAnnotation extends Annotation {
  readonly id: string;
  readonly color: HighlightColor;
  readonly style: HighlightStyle;
}

/** A page's annotations, as the sidecar holds them. */
export interface PageAnnotations {
  readonly url: string;
  readonly annotations: readonly StoredAnnotation[];
}

/** A short random id — enough to tell a page's highlights apart, not a secret. */
export function annotationId(random: () => number = Math.random): string {
  return Array.from({ length: 10 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(random() * 36)]).join(
    "",
  );
}

function coerceColor(raw: unknown): HighlightColor {
  return typeof raw === "string" && (HIGHLIGHT_COLORS as readonly string[]).includes(raw)
    ? (raw as HighlightColor)
    : "yellow";
}

function coerceStyle(raw: unknown): HighlightStyle {
  return raw === "underline" ? "underline" : "highlight";
}

function coerceIntensity(raw: unknown): HighlightIntensity {
  return raw === "light" || raw === "strong" ? raw : "medium";
}

/** Clean a stored tag list: strings only, trimmed, de-duplicated, blanks and a leading # dropped. */
function coerceTags(raw: unknown): readonly string[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of raw) {
    if (typeof item !== "string") continue;
    const tag = item.trim().replace(/^#+/, "").trim();
    if (tag === "" || seen.has(tag.toLowerCase())) continue;
    seen.add(tag.toLowerCase());
    out.push(tag);
  }
  return out;
}

function coerceAnchor(raw: unknown): TextAnchor | null {
  if (raw === null || typeof raw !== "object") return null;
  const anchor = raw as Record<string, unknown>;
  if (typeof anchor["exact"] !== "string" || anchor["exact"].trim() === "") return null;
  return {
    exact: anchor["exact"],
    ...(typeof anchor["prefix"] === "string" && anchor["prefix"] !== "" ? { prefix: anchor["prefix"] } : {}),
    ...(typeof anchor["suffix"] === "string" && anchor["suffix"] !== "" ? { suffix: anchor["suffix"] } : {}),
  };
}

/**
 * Read one stored annotation from whatever is actually in the file.
 *
 * The sidecar is a file on disk in someone's vault; it can be hand-edited, synced with conflicts, or
 * written by a future version. A malformed entry is dropped rather than allowed to break every other
 * highlight on the page.
 */
export function coerceAnnotation(raw: unknown): StoredAnnotation | null {
  if (raw === null || typeof raw !== "object") return null;
  const entry = raw as Record<string, unknown>;
  const anchor = coerceAnchor(entry["anchor"]);
  if (anchor === null) return null;
  if (typeof entry["id"] !== "string" || entry["id"] === "") return null;
  if (typeof entry["url"] !== "string" || entry["url"] === "") return null;
  return {
    id: entry["id"],
    url: entry["url"],
    anchor,
    color: coerceColor(entry["color"]),
    style: coerceStyle(entry["style"]),
    createdAt: typeof entry["createdAt"] === "string" ? entry["createdAt"] : new Date(0).toISOString(),
    ...(typeof entry["note"] === "string" && entry["note"].trim() !== "" ? { note: entry["note"] } : {}),
    ...(coerceTags(entry["tags"]).length > 0 ? { tags: coerceTags(entry["tags"]) } : {}),
    intensity: coerceIntensity(entry["intensity"]),
  };
}

/** Fold any whitespace runs into single spaces — a table cell must stay on one line. */
function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/**
 * The annotation as it joins the row's cell.
 *
 * Readable at a glance in any table: the quote marked as a highlight, the note after a dash. Never the
 * anchor context or the colour — a table full of machine bookkeeping would make the view unusable, and the
 * sidecar already remembers those.
 */
export interface CellTextOptions {
  /** Prefix each highlight as a list item. Off by default. */
  readonly bullet?: boolean;
  /** Include the note after the quote. On by default. */
  readonly note?: boolean;
  /** Append the tags as Obsidian #hashtags. Off by default — a table stays readable without them. */
  readonly tags?: boolean;
}

/** Tags rendered as Obsidian hashtags, spaces folded to hyphens so each is a single valid tag. */
function tagHashtags(annotation: StoredAnnotation): string {
  return (annotation.tags ?? []).map((t) => `#${t.replace(/\s+/g, "-")}`).join(" ");
}

/**
 * The annotation as it joins the row's cell.
 *
 * Readable at a glance in any table: the quote marked as a highlight, the note after a dash, and — only when
 * asked — the tags as hashtags. Never the anchor context, colour, or transparency; the sidecar remembers
 * those, and a table full of machine bookkeeping would be unusable. The second argument accepts a bare
 * boolean for backward compatibility, read as `{ bullet }`.
 */
export function annotationCellText(annotation: StoredAnnotation, opts: CellTextOptions | boolean = {}): string {
  const o: CellTextOptions = typeof opts === "boolean" ? { bullet: opts } : opts;
  const quote = oneLine(annotation.anchor.exact);
  const note = o.note === false ? "" : oneLine(annotation.note ?? "");
  const tags = o.tags === true ? tagHashtags(annotation) : "";
  let body = `==${quote}==`;
  if (note !== "") body += ` — ${note}`;
  if (tags !== "") body += ` ${tags}`;
  return o.bullet === true ? `- ${body}` : body;
}

/**
 * The annotation as it lands in the dedicated note, under `## Annotations`.
 *
 * A blockquote for the highlight, the note beneath it as a nested line — the shape a literature note would
 * use anyway, so promoted notes read as written rather than as imported.
 */
export interface NoteBlockOptions {
  /** Include the note under the quote. On by default. */
  readonly note?: boolean;
  /** Include the tags as an inline #hashtag line. On by default. */
  readonly tags?: boolean;
}

export function annotationNoteBlock(annotation: StoredAnnotation, opts: NoteBlockOptions = {}): string {
  const quote = annotation.anchor.exact.trim().split(/\r?\n/).map((line) => `> ${line}`).join("\n");
  const note = opts.note === false ? "" : (annotation.note ?? "").trim();
  const tagList = opts.tags === false ? "" : tagHashtags(annotation);
  const base = note === "" ? quote : `${quote}\n>\n> — ${note}`;
  // Tags on their own line under the quote, as Obsidian hashtags, so the vault's own tag index finds them.
  return tagList === "" ? base : `${base}\n>\n> ${tagList}`;
}

/** Add an annotation to a page's set, replacing any earlier one with the same id. */
export function withAnnotation(
  page: PageAnnotations,
  annotation: StoredAnnotation,
): PageAnnotations {
  const kept = page.annotations.filter((a) => a.id !== annotation.id);
  return { url: page.url, annotations: [...kept, annotation] };
}

/** Remove one annotation by id. Removing something already gone is not an error. */
export function withoutAnnotation(page: PageAnnotations, id: string): PageAnnotations {
  return { url: page.url, annotations: page.annotations.filter((a) => a.id !== id) };
}
