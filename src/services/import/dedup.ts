import type { Row } from "../../domain/index";
import { getField } from "../../domain/index";
import { normalizeDoi } from "./doi-lookup";

export interface DuplicateGroup {
  /** Normalised DOI shared by the rows. */
  readonly doi: string;
  /** Rows sharing this DOI, richest-first (most filled cells), so the first is the natural keeper. */
  readonly rows: readonly Row[];
}

/** How many of a row's cells are non-empty — used to suggest which duplicate to keep. */
export function rowCompleteness(row: Row): number {
  return Object.values(row.cells).filter((v) => v.trim() !== "").length;
}

/** Group rows by normalised DOI and return only the groups with more than one row (duplicates). */
export function findDuplicateDois(rows: readonly Row[], doiColumn: string): DuplicateGroup[] {
  const byDoi = new Map<string, Row[]>();
  for (const row of rows) {
    const doi = normalizeDoi(getField(row, doiColumn)).toLowerCase();
    if (doi === "") continue;
    const bucket = byDoi.get(doi);
    if (bucket) bucket.push(row);
    else byDoi.set(doi, [row]);
  }
  const groups: DuplicateGroup[] = [];
  for (const [doi, group] of byDoi) {
    if (group.length < 2) continue;
    const sorted = [...group].sort((a, b) => rowCompleteness(b) - rowCompleteness(a));
    groups.push({ doi, rows: sorted });
  }
  // Most-duplicated first.
  return groups.sort((a, b) => b.rows.length - a.rows.length);
}
