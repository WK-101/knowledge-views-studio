import type { Row } from "../model";
import { isVirtualField } from "../fields";

/**
 * How strictly a source table's headers must match the view's configured columns
 * before its rows are aggregated in. "loose" keeps every row in scope (the
 * historical behaviour); "contains" requires that every configured (non-virtual)
 * column be present as a header; "exact" additionally forbids extra headers.
 */
export type ColumnMatchMode = "loose" | "contains" | "exact";

export function rowMatchesColumns(row: Row, columnNames: readonly string[], mode: ColumnMatchMode): boolean {
  if (mode === "loose") return true;
  const required = columnNames.filter((name) => !isVirtualField(name)).map((name) => name.toLowerCase());
  if (required.length === 0) return true;
  const present = new Set(Object.keys(row.cells).map((key) => key.toLowerCase()));
  if (!required.every((name) => present.has(name))) return false;
  return mode === "contains" ? true : present.size === required.length;
}
