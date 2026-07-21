import { stickyCellText, stickyMarker, type StickyNote } from "../../../shared/sticky";

/**
 * The decisions behind the sticky-note routes, kept out of the route so they can be tested.
 *
 * A sticky note touches the sidecar (its machine truth) and one row cell (its readable copy). The cell is
 * the interesting part: unlike a highlight, a note's text changes, so its cell line is found by the note's
 * hidden id marker rather than by matching the text whole. That makes an update a replace-by-id and a delete
 * a drop-by-id — both stable through however much the body was reworded.
 *
 * Row-matching itself is shared with the highlighter (`rowForUrl` in annotate-plan), because "which row is
 * this page's" is the same question whichever feature asks it.
 */

/** The names a sticky-notes column answers to when the person hasn't declared one, most specific first. */
const STICKY_COLUMNS = ["sticky notes", "sticky", "notes", "annotations"];

/** Which column of a view takes sticky-note text, or null when the view has nowhere for it. */
export function stickyColumn(
  columns: readonly { readonly name: string }[],
  declaredColumn?: string,
): string | null {
  const declared = (declaredColumn ?? "").trim();
  if (declared !== "") {
    const named = columns.find((c) => c.name.trim().toLowerCase() === declared.toLowerCase());
    if (named !== undefined) return named.name;
    // A stale declaration (a renamed column) falls through to the guess rather than dropping the note.
  }
  for (const wanted of STICKY_COLUMNS) {
    const found = columns.find((c) => c.name.trim().toLowerCase() === wanted);
    if (found !== undefined) return found.name;
  }
  return null;
}

/** The `<br>`-separated parts of a cell, trimmed, blanks dropped. */
function parts(cell: string): string[] {
  return cell
    .split("<br>")
    .map((part) => part.trim())
    .filter((part) => part !== "");
}

/** True when a cell part is this sticky note's line (carries its id marker). */
function lineForId(part: string, id: string): boolean {
  return part.startsWith(stickyMarker(id));
}

/**
 * A cell with this note's line inserted or replaced in place.
 *
 * Upsert by id: an existing line for the note (found by its hidden marker) is swapped for the new text,
 * keeping its position among the cell's other lines; a note with no line yet is appended. Returns the whole
 * new cell — the route hands it to the same guarded writer every other edit goes through.
 */
export function upsertStickyLine(cell: string, note: StickyNote): string {
  const line = stickyCellText(note);
  const existing = parts(cell);
  const at = existing.findIndex((part) => lineForId(part, note.id));
  if (at >= 0) {
    existing[at] = line;
  } else {
    existing.push(line);
  }
  return existing.join("<br>");
}

/**
 * A cell with this note's line removed, or null when the cell has no line for it (so the route knows not to
 * rewrite a cell it wouldn't change). Matched by id marker, so a reworded note is still found and stripped.
 */
export function cellWithoutSticky(cell: string, id: string): string | null {
  const existing = parts(cell);
  if (!existing.some((part) => lineForId(part, id))) return null;
  return existing.filter((part) => !lineForId(part, id)).join("<br>");
}
