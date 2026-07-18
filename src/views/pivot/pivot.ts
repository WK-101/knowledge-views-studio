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
 *
 * Bucketed in a single pass. The obvious formulation — for every (row key, column key) pair, filter the whole
 * dataset — costs rowKeys × columnKeys × rows, so a pivot over a high-cardinality field degrades quadratically
 * and can block the UI for seconds. Here each row is visited once and dropped into its cell, row and column
 * buckets; aggregation then runs per bucket. That's O(rows + cells), and it produces identical output: keys
 * still appear in first-encounter order, and totals are still aggregated over whole groups rather than summed
 * from cells.
 */
export function buildPivot(
  rows: readonly Row[],
  rowField: string,
  columnField: string | null,
  agg: Aggregate,
): PivotResult {
  const rowKeys: string[] = [];
  const rowIndex = new Map<string, number>();
  const rowBuckets: Row[][] = [];
  const columnKeys: string[] = [];
  const colIndex = new Map<string, number>();
  const colBuckets: Row[][] = [];
  const cells = new Map<string, Row[]>();

  for (const row of rows) {
    const rk = getField(row, rowField).trim();
    let ri = rowIndex.get(rk);
    if (ri === undefined) {
      ri = rowKeys.length;
      rowIndex.set(rk, ri);
      rowKeys.push(rk);
      rowBuckets.push([]);
    }
    const ck = columnField ? getField(row, columnField).trim() : "";
    let ci = colIndex.get(ck);
    if (ci === undefined) {
      ci = columnKeys.length;
      colIndex.set(ck, ci);
      columnKeys.push(ck);
      colBuckets.push([]);
    }
    rowBuckets[ri]?.push(row);
    colBuckets[ci]?.push(row);
    const cellKey = `${ri}\u0000${ci}`;
    let cell = cells.get(cellKey);
    if (cell === undefined) {
      cell = [];
      cells.set(cellKey, cell);
    }
    cell.push(row);
  }

  // With no column field there is a single unnamed column, matching the previous behaviour.
  if (columnKeys.length === 0) {
    columnKeys.push("");
    colBuckets.push([]);
  }

  const values = rowKeys.map((_, ri) => columnKeys.map((__, ci) => aggregate(cells.get(`${ri}\u0000${ci}`) ?? [], agg)));
  const rowTotals = rowBuckets.map((bucket) => aggregate(bucket, agg));
  const columnTotals = columnField ? colBuckets.map((bucket) => aggregate(bucket, agg)) : [aggregate(rows, agg)];

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
