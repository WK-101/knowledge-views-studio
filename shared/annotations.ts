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

export type HighlightColor = "yellow" | "green" | "blue" | "red";

export const HIGHLIGHT_COLORS: readonly HighlightColor[] = ["yellow", "green", "blue", "red"];

export interface StoredAnnotation extends Annotation {
  readonly id: string;
  readonly color: HighlightColor;
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
    createdAt: typeof entry["createdAt"] === "string" ? entry["createdAt"] : new Date(0).toISOString(),
    ...(typeof entry["note"] === "string" && entry["note"].trim() !== "" ? { note: entry["note"] } : {}),
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
export function annotationCellText(annotation: StoredAnnotation): string {
  const quote = oneLine(annotation.anchor.exact);
  const note = oneLine(annotation.note ?? "");
  return note === "" ? `==${quote}==` : `==${quote}== — ${note}`;
}

/**
 * The annotation as it lands in the dedicated note, under `## Annotations`.
 *
 * A blockquote for the highlight, the note beneath it as a nested line — the shape a literature note would
 * use anyway, so promoted notes read as written rather than as imported.
 */
export function annotationNoteBlock(annotation: StoredAnnotation): string {
  const quote = annotation.anchor.exact.trim().split(/\r?\n/).map((line) => `> ${line}`).join("\n");
  const note = (annotation.note ?? "").trim();
  return note === "" ? quote : `${quote}\n>\n> — ${note}`;
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
