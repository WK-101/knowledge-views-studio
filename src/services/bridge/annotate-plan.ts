import type { Row } from "../../domain/index";
import { getField } from "../../domain/fields";
import { normalizeUrl } from "../../../shared/protocol";
import {
  annotationCellText,
  annotationNoteBlock,
  coerceAnnotation,
  type StoredAnnotation,
} from "../../../shared/annotations";

/**
 * The decisions behind `/annotate`, kept out of the route so they can be tested.
 *
 * An annotation touches up to four things — sidecar, row cell, dedicated note, and possibly a brand-new
 * row — and each step has a judgement in it: which row is the page's, which column takes the text, what a
 * removal must strip from a cell without touching what a person wrote around it. Those judgements live
 * here; the route just executes them.
 */

/** The names an annotations column answers to, most specific first. */
const ANNOTATION_COLUMNS = ["annotations", "highlights", "quotes", "notes"];

/** Which column of a view takes annotation text, or null when the view has nowhere for it. */
export function annotationColumn(
  columns: readonly { readonly name: string }[],
  declaredColumn?: string,
): string | null {
  const declared = (declaredColumn ?? "").trim();
  if (declared !== "") {
    const named = columns.find((c) => c.name.trim().toLowerCase() === declared.toLowerCase());
    if (named !== undefined) return named.name;
    // A declaration that no longer matches a column falls through to the guess rather than silently
    // dropping every annotation on the floor.
  }
  for (const wanted of ANNOTATION_COLUMNS) {
    const found = columns.find((c) => c.name.trim().toLowerCase() === wanted);
    if (found !== undefined) return found.name;
  }
  return null;
}

/** The row whose URL cell names this page, matched the way the rest of the bridge matches URLs. */
export function rowForUrl(
  rows: readonly Row[],
  columns: readonly { readonly name: string; readonly typeId?: string }[],
  url: string,
  declaredColumn?: string,
): Row | null {
  const wanted = normalizeUrl(url);
  if (wanted === "") return null;

  // A declared column is the person's decision and overrides the guess entirely — but only if the view
  // actually has it; a stale declaration (a renamed column) falls back rather than matching nothing.
  const declared = (declaredColumn ?? "").trim();
  const urlColumns =
    declared !== "" && columns.some((c) => c.name.trim().toLowerCase() === declared.toLowerCase())
      ? columns.filter((c) => c.name.trim().toLowerCase() === declared.toLowerCase())
      : columns.filter((c) => c.typeId === "url" || /^(url|link|source)$/i.test(c.name.trim()));

  for (const row of rows) {
    for (const column of urlColumns) {
      if (normalizeUrl(getField(row, column.name)) === wanted) return row;
    }
  }
  return null;
}

/** Read a wire annotation into the stored shape, refusing what couldn't paint. */
export function readWireAnnotation(raw: unknown, url: string): StoredAnnotation | null {
  if (raw === null || typeof raw !== "object") return null;
  return coerceAnnotation({ ...raw, url });
}

/**
 * A cell with one annotation's line removed.
 *
 * Cells hold history `<br>`-separated, and people edit them. Removal strips exactly the line this
 * annotation wrote — matched whole, not as a substring — and leaves everything else, including lines a
 * person reworded, which by definition no longer match and therefore survive. Returning null means the
 * cell doesn't contain the line and shouldn't be rewritten at all.
 */
export function cellWithoutAnnotation(cell: string, annotation: StoredAnnotation): string | null {
  // Either form the writer might have used — plain or bulleted — so removal finds the line whichever the
  // person had switched on when they made the highlight.
  const plain = annotationCellText(annotation, false);
  const bulleted = annotationCellText(annotation, true);
  const parts = cell.split("<br>").map((part) => part.trim());
  if (!parts.includes(plain) && !parts.includes(bulleted)) return null;
  const kept = parts.filter((part) => part !== plain && part !== bulleted && part !== "");
  return kept.join("<br>");
}

/**
 * A note with one annotation's blockquote removed.
 *
 * Same contract as the cell version: the block is matched whole — a blockquote someone has edited no longer
 * matches and survives, because their writing outranks our bookkeeping. Null means the note doesn't contain
 * the block and shouldn't be rewritten at all.
 */
export function noteWithoutAnnotation(content: string, annotation: StoredAnnotation): string | null {
  const block = annotationNoteBlock(annotation);
  // Matched on line boundaries, never as a substring: the block is a prefix of any hand-extended version
  // of itself, and a substring match would carve our text out of the middle of theirs — mangling exactly
  // the edit this function exists to protect.
  let from = 0;
  while (true) {
    const at = content.indexOf(block, from);
    if (at < 0) return null;
    const startsLine = at === 0 || content[at - 1] === "\n";
    const afterEnd = content.slice(at + block.length, at + block.length + 2);
    const endsLine = afterEnd === "" || afterEnd.startsWith("\n") || afterEnd.startsWith("\r\n");
    if (startsLine && endsLine) {
      const before = content.slice(0, at);
      // The block leaves with one of its surrounding blank lines, so removals don't accumulate gaps —
      // and don't erase the gap that belongs to the neighbour either.
      const after = content.slice(at + block.length).replace(/^(\r?\n){1,2}/, "");
      return before + after;
    }
    from = at + 1;
  }
}
