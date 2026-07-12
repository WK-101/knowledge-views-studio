import { getField, VIRTUAL_FIELDS } from "../fields";
import type { Row } from "../model";

/**
 * A quick free-text filter across a row's data cells and its note name — the
 * equivalent of the search box in a Bases toolbar. Empty/whitespace queries match
 * everything. Pure and case-insensitive.
 */
export function searchRows(rows: readonly Row[], query: string): Row[] {
  const needle = query.trim().toLowerCase();
  if (needle === "") return [...rows];
  return rows.filter((row) => searchText(row).includes(needle));
}

// Each row's searchable text is lowercased once and cached. Rows are immutable
// and recreated when their file changes, so a WeakMap both stays correct and lets
// the cache be collected with the rows. Without this, every distinct query would
// re-lowercase every cell of every row (O(rows x cells) per keystroke-batch).
const searchTextCache = new WeakMap<Row, string>();

function searchText(row: Row): string {
  const cached = searchTextCache.get(row);
  if (cached !== undefined) return cached;
  const parts: string[] = [];
  for (const key of Object.keys(row.cells)) parts.push(row.cells[key] ?? "");
  for (const field of VIRTUAL_FIELDS) parts.push(getField(row, field));
  const text = parts.join("\n").toLowerCase();
  searchTextCache.set(row, text);
  return text;
}
