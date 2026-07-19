import type { Row } from "../../domain/index";
import { getField } from "../../domain/fields";
import { normalizeUrl } from "../../../shared/protocol";
import {
  annotationCellText,
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
export function annotationColumn(columns: readonly { readonly name: string }[]): string | null {
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
): Row | null {
  const wanted = normalizeUrl(url);
  if (wanted === "") return null;
  const urlColumns = columns.filter(
    (c) => c.typeId === "url" || /^(url|link|source)$/i.test(c.name.trim()),
  );
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
  const line = annotationCellText(annotation);
  const parts = cell.split("<br>").map((part) => part.trim());
  if (!parts.includes(line)) return null;
  const kept = parts.filter((part) => part !== line && part !== "");
  return kept.join("<br>");
}
