import { getField, type Row } from "../../domain/index";
import { parseNumber } from "../../domain/columns/types/number";

export type AggregateKind = "count" | "sum" | "avg" | "min" | "max";

export interface Aggregate {
  readonly kind: AggregateKind;
  readonly field?: string;
}

export interface PivotResult {
  readonly rowField: string;
  readonly columnField: string | null;
  readonly rowKeys: string[];
  readonly columnKeys: string[];
  readonly values: number[][];
  readonly rowTotals: number[];
  readonly columnTotals: number[];
  readonly grandTotal: number;
}

function distinct(values: Iterable<string>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const value of values) {
    if (!seen.has(value)) {
      seen.add(value);
      out.push(value);
    }
  }
  return out;
}

/** Reduce a set of rows to a single number under the chosen aggregate. */
export function aggregate(rows: readonly Row[], agg: Aggregate): number {
  if (agg.kind === "count") return rows.length;
  const field = agg.field ?? "";
  const nums: number[] = [];
  for (const row of rows) {
    const n = parseNumber(getField(row, field));
    if (n !== null) nums.push(n);
  }
  if (nums.length === 0) return 0;
  switch (agg.kind) {
    case "sum":
      return nums.reduce((a, b) => a + b, 0);
    case "avg":
      return nums.reduce((a, b) => a + b, 0) / nums.length;
    case "min":
      return Math.min(...nums);
    case "max":
      return Math.max(...nums);
  }
}

/**
 * Build a pivot/summary table: group rows by `rowField` (and optionally
 * `columnField`) and aggregate. Totals are computed over the full row/column
 * group — not by summing cells — so avg/min/max stay correct.
 */
export function buildPivot(
  rows: readonly Row[],
  rowField: string,
  columnField: string | null,
  agg: Aggregate,
): PivotResult {
  const rowKeys = distinct(rows.map((r) => getField(r, rowField).trim()));
  const columnKeys = columnField ? distinct(rows.map((r) => getField(r, columnField).trim())) : [""];

  const inRow = (r: Row, rk: string): boolean => getField(r, rowField).trim() === rk;
  const inCol = (r: Row, ck: string): boolean => !columnField || getField(r, columnField).trim() === ck;

  const values = rowKeys.map((rk) =>
    columnKeys.map((ck) => aggregate(rows.filter((r) => inRow(r, rk) && inCol(r, ck)), agg)),
  );
  const rowTotals = rowKeys.map((rk) => aggregate(rows.filter((r) => inRow(r, rk)), agg));
  const columnTotals = columnField
    ? columnKeys.map((ck) => aggregate(rows.filter((r) => inCol(r, ck)), agg))
    : [aggregate(rows, agg)];

  return {
    rowField,
    columnField,
    rowKeys,
    columnKeys,
    values,
    rowTotals,
    columnTotals,
    grandTotal: aggregate(rows, agg),
  };
}
