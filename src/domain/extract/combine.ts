import type { ColumnConfig } from "../columns/column-type";
import type { Row } from "../model";
import { FRONTMATTER_EXTRACTOR_ID } from "./frontmatter-extractor";
import { INLINE_EXTRACTOR_ID } from "./inline-field-extractor";
import { TABLE_EXTRACTOR_ID } from "./table-extractor";
import { TASK_EXTRACTOR_ID } from "./task-extractor";

/**
 * How rows from several sources combine within one note.
 *
 * The distinction that matters is *what a row represents*:
 *   - item-level sources (table rows, tasks, worksheet rows) yield **many rows per note**;
 *   - note-level sources (properties, inline fields) describe **the note as a whole**.
 *
 * "separate" keeps every source's rows independent — a note with a 3-row table plus properties yields
 * four rows. "enrich" instead folds the note-level values into each item row from the same note, so the
 * table's three rows each gain the note's author. Nothing is ever silently overwritten: where both
 * define the same field, the item row wins, because it is the more specific statement.
 */
export type RowMerge = "separate" | "enrich";

/** Sources that describe the note as a whole (one row per note). */
export const NOTE_LEVEL_EXTRACTORS: ReadonlySet<string> = new Set([FRONTMATTER_EXTRACTOR_ID, INLINE_EXTRACTOR_ID]);

/** Human labels for the `source` virtual field. */
export const SOURCE_LABELS: Readonly<Record<string, string>> = {
  [TABLE_EXTRACTOR_ID]: "Table row",
  [FRONTMATTER_EXTRACTOR_ID]: "Note properties",
  [TASK_EXTRACTOR_ID]: "Task",
  [INLINE_EXTRACTOR_ID]: "Inline field",
  xlsx: "Excel row",
};

export function sourceLabel(extractorId: string): string {
  return SOURCE_LABELS[extractorId] ?? extractorId;
}

export function isNoteLevel(extractorId: string): boolean {
  return NOTE_LEVEL_EXTRACTORS.has(extractorId);
}

/** True when the selection mixes a note-level source with an item-level one — the only case where the
 *  combination mode changes anything. */
export function canEnrich(extractorIds: readonly string[]): boolean {
  return extractorIds.some(isNoteLevel) && extractorIds.some((id) => !isNoteLevel(id));
}

/** Add `extra` cells to `base`, keeping `base` where a field is defined in both (case-insensitively). */
function fillGaps(base: Readonly<Record<string, string>>, extra: Readonly<Record<string, string>>): Record<string, string> {
  const have = new Set(Object.keys(base).map((k) => k.trim().toLowerCase()));
  const out: Record<string, string> = { ...base };
  for (const [key, value] of Object.entries(extra)) {
    if (!have.has(key.trim().toLowerCase())) out[key] = value;
  }
  return out;
}

/**
 * Combine one file's rows from all its sources.
 *
 * In "enrich" mode the note-level rows stop being rows of their own and become context on the item rows
 * from the same note. If the note has no item rows, its note-level row is still emitted — enriching
 * nothing must never make a note disappear.
 */
export function combineRows(rows: readonly Row[], mode: RowMerge): Row[] {
  if (mode !== "enrich") return [...rows];

  const noteRows = rows.filter((r) => isNoteLevel(r.provenance.extractor));
  const itemRows = rows.filter((r) => !isNoteLevel(r.provenance.extractor));
  if (noteRows.length === 0 || itemRows.length === 0) return [...rows];

  // Merge the note-level sources into one bag of context (earlier sources win on a clash).
  let context: Record<string, string> = {};
  for (const noteRow of noteRows) context = fillGaps(context, noteRow.cells);

  return itemRows.map((row) => {
    // Keep what each source actually said, so a column bound to one source can still read its value
    // even where the merged view resolved the clash in favour of the item row.
    const bySource: Record<string, Readonly<Record<string, string>>> = { [row.provenance.extractor]: row.cells };
    for (const noteRow of noteRows) bySource[noteRow.provenance.extractor] = noteRow.cells;
    return { ...row, cells: fillGaps(row.cells, context), bySource };
  });
}

/** Case-insensitive cell lookup within one bag. */
function pick(bag: Readonly<Record<string, string>>, name: string): string | undefined {
  const key = name.trim().toLowerCase();
  for (const [col, value] of Object.entries(bag)) {
    if (col.trim().toLowerCase() === key) return value ?? "";
  }
  return undefined;
}

/** The cells a given source contributed to a row, or undefined if it contributed none. */
function cellsFrom(row: Row, sourceId: string): Readonly<Record<string, string>> | undefined {
  const bag = row.bySource?.[sourceId];
  if (bag) return bag;
  // Not a folded row: all of its cells came from the one extractor that produced it.
  return row.provenance.extractor === sourceId ? row.cells : undefined;
}

/**
 * Which sources supplied each header, across a sample of rows. Keyed by the lowercased header, so
 * `Author` and `author` are recognised as the same field. This is what lets "Discover from vault"
 * pre-fill a column's source binding — and, where a header comes from several sources at once, say so
 * rather than guessing.
 */
export function discoverHeaderSources(rows: readonly Row[]): Map<string, string[]> {
  const out = new Map<string, Set<string>>();
  for (const row of rows) {
    // A folded row carries each source's cells; an unfolded one came wholly from its own extractor.
    const bags: [string, Readonly<Record<string, string>>][] = row.bySource
      ? Object.entries(row.bySource)
      : [[row.provenance.extractor, row.cells]];
    for (const [sourceId, cells] of bags) {
      for (const header of Object.keys(cells)) {
        const key = header.trim().toLowerCase();
        if (key === "") continue;
        const set = out.get(key) ?? new Set<string>();
        set.add(sourceId);
        out.set(key, set);
      }
    }
  }
  return new Map([...out].map(([key, set]) => [key, [...set]]));
}

/**
 * Resolve columns that are bound to a specific source: the column's header is matched only within that
 * source's cells, so a header several sources define is no longer ambiguous. A row the bound source did
 * not contribute to leaves the column empty.
 *
 * The bound value is also marked read-only whenever it did not come from the row's own source — the row
 * writes back to the file its extractor came from, and editing there would put the value in the wrong
 * place.
 */
export function applySourceBindings(rows: readonly Row[], columns: readonly ColumnConfig[]): Row[] {
  const bound = columns.filter((c) => typeof c.source === "string" && c.source !== "" && c.name.trim() !== "");
  if (bound.length === 0) return [...rows];

  return rows.map((row) => {
    const cells: Record<string, string> = { ...row.cells };
    const readOnly = new Set(row.provenance.readOnlyFields ?? []);
    let touched = false;

    for (const column of bound) {
      const sourceId = column.source!;
      const bag = cellsFrom(row, sourceId);
      const value = bag ? (pick(bag, column.name) ?? "") : "";
      // Drop any same-named cell from another source, then set the bound value under the column's name.
      for (const key of Object.keys(cells)) {
        if (key.trim().toLowerCase() === column.name.trim().toLowerCase()) delete cells[key];
      }
      cells[column.name] = value;
      if (row.provenance.extractor !== sourceId) readOnly.add(column.name);
      touched = true;
    }
    if (!touched) return row;
    return { ...row, cells, provenance: { ...row.provenance, readOnlyFields: [...readOnly] } };
  });
}
