import { getField } from "../fields";
import type { Row } from "../model";
import type { FieldTypeResolver } from "./field-type";
import { compareComparable } from "../columns/column-type";

/**
 * Column summaries — the footer row every database has and we didn't.
 *
 * The arithmetic already existed inside rollups, which aggregate across the notes a relation points to.
 * A summary is the same arithmetic pointed at a much simpler question: "what does this column say about
 * the rows I can currently see?" It respects the filter, so it answers about what's on screen, not about
 * the whole vault — which is the only answer anyone actually wants.
 */
export type SummaryFn =
  | "none"
  | "count" // rows with a value
  | "count-all" // every row, empty or not
  | "empty"
  | "filled"
  | "percent-filled"
  | "unique"
  | "sum"
  | "avg"
  | "min"
  | "max"
  | "range";

export const SUMMARY_FUNCTIONS: readonly { id: SummaryFn; label: string; numeric: boolean }[] = [
  { id: "none", label: "None", numeric: false },
  { id: "count-all", label: "Count all", numeric: false },
  { id: "count", label: "Count values", numeric: false },
  { id: "filled", label: "Filled", numeric: false },
  { id: "empty", label: "Empty", numeric: false },
  { id: "percent-filled", label: "Percent filled", numeric: false },
  { id: "unique", label: "Unique", numeric: false },
  { id: "sum", label: "Sum", numeric: true },
  { id: "avg", label: "Average", numeric: true },
  { id: "min", label: "Minimum", numeric: false },
  { id: "max", label: "Maximum", numeric: false },
  { id: "range", label: "Range (max − min)", numeric: true },
];

function toNumber(raw: string): number | null {
  const cleaned = raw.replace(/[,\s]/g, "").replace(/[^0-9.\-+eE]/g, "");
  if (cleaned === "") return null;
  const n = Number(cleaned);
  return Number.isFinite(n) ? n : null;
}

function fmt(n: number): string {
  if (Number.isInteger(n)) return String(n);
  return String(Math.round(n * 100) / 100);
}

/**
 * Summarise one column across the given rows. Returns an empty string when there is nothing to say —
 * a summary that invents a number for a column of empty cells is worse than no summary at all.
 */
export function summarizeColumn(
  rows: readonly Row[],
  column: { readonly name: string },
  fn: SummaryFn,
  resolver?: FieldTypeResolver,
): string {
  if (fn === "none" || rows.length === 0) return "";

  const values = rows.map((row) => getField(row, column.name).trim());
  const filled = values.filter((v) => v !== "");

  switch (fn) {
    case "count-all":
      return String(rows.length);
    case "count":
    case "filled":
      return String(filled.length);
    case "empty":
      return String(values.length - filled.length);
    case "percent-filled":
      return `${Math.round((filled.length / values.length) * 100)}%`;
    case "unique":
      return String(new Set(filled).size);
    case "sum":
    case "avg": {
      const nums = filled.map(toNumber).filter((n): n is number => n !== null);
      if (nums.length === 0) return "";
      const total = nums.reduce((a, b) => a + b, 0);
      return fmt(fn === "sum" ? total : total / nums.length);
    }
    case "range": {
      const nums = filled.map(toNumber).filter((n): n is number => n !== null);
      if (nums.length === 0) return "";
      return fmt(Math.max(...nums) - Math.min(...nums));
    }
    case "min":
    case "max": {
      if (filled.length === 0) return "";
      // Compare with the column's own type, so dates sort as dates and numbers as numbers -- not as text.
      const type = resolver?.get(column.name);
      if (!type) {
        const nums = filled.map(toNumber).filter((n): n is number => n !== null);
        if (nums.length === 0) return "";
        return fmt(fn === "min" ? Math.min(...nums) : Math.max(...nums));
      }
      let best = filled[0]!;
      let bestCmp = type.toComparable(best);
      for (const v of filled.slice(1)) {
        const cmp = type.toComparable(v);
        const order = compareComparable(cmp, bestCmp);
        if ((fn === "min" && order < 0) || (fn === "max" && order > 0)) {
          best = v;
          bestCmp = cmp;
        }
      }
      return best;
    }
  }
}

/** True when any column in the view asks for a summary — i.e. whether to draw the footer at all. */
export function hasSummaries(columns: readonly { readonly summary?: string }[]): boolean {
  return columns.some((c) => c.summary !== undefined && c.summary !== "none");
}
