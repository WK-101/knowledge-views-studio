import { getField } from "../../domain/index";
import type { Row } from "../../domain/index";
import { summarizeColumn, type SummaryFn } from "../../domain/index";

/**
 * Turning rows into a chart is two questions: what do we group by, and what do we measure?
 *
 * Everything else — the colours, the axes, the legend — is presentation. Keeping the answer to those two
 * questions pure means the part that could actually be *wrong* (the arithmetic) is testable, and the part
 * that can only look wrong (the canvas) is not pretending to be.
 *
 * The aggregation deliberately reuses `summarizeColumn`, the same code behind the table's summary
 * footer, so a chart and a footer can never disagree about what the sum of a column is.
 */

export interface ChartSeries {
  readonly labels: string[];
  readonly values: number[];
  /** Rows behind each bar/slice, so a click can drill down to them. */
  readonly buckets: Row[][];
}

const BLANK = "(empty)";

/** Group rows by a field's value, preserving first-seen order — which is usually the sort the user chose. */
export function bucketRows(rows: readonly Row[], groupBy: string): { labels: string[]; buckets: Row[][] } {
  const order: string[] = [];
  const map = new Map<string, Row[]>();
  for (const row of rows) {
    const raw = getField(row, groupBy).trim();
    const key = raw === "" ? BLANK : raw;
    let bucket = map.get(key);
    if (!bucket) {
      bucket = [];
      map.set(key, bucket);
      order.push(key);
    }
    bucket.push(row);
  }
  return { labels: order, buckets: order.map((k) => map.get(k)!) };
}

/**
 * Build a chart series.
 *
 * `count` needs no value field — it measures how many rows fell in each bucket. Every other aggregate
 * measures a column, and a bucket whose column holds nothing numeric contributes 0 rather than being
 * silently dropped: a missing bar is a lie about the data, a zero bar is the truth about it.
 */
export function buildChartSeries(
  rows: readonly Row[],
  groupBy: string,
  aggregate: SummaryFn,
  valueField: string,
  limit = 40,
): ChartSeries {
  if (rows.length === 0 || groupBy === "") return { labels: [], values: [], buckets: [] };

  const { labels, buckets } = bucketRows(rows, groupBy);
  const values = buckets.map((bucket) => {
    if (aggregate === "count" || aggregate === "count-all" || valueField === "") return bucket.length;
    const raw = summarizeColumn(bucket, { name: valueField }, aggregate);
    const n = Number(String(raw).replace(/[^0-9.\-+eE]/g, ""));
    return Number.isFinite(n) ? n : 0;
  });

  // A chart with 400 bars communicates nothing. Keep the largest, and be honest that we did.
  if (labels.length > limit) {
    const idx = labels.map((_, i) => i).sort((a, b) => values[b]! - values[a]!).slice(0, limit);
    idx.sort((a, b) => a - b); // restore the user's ordering among the survivors
    return {
      labels: idx.map((i) => labels[i]!),
      values: idx.map((i) => values[i]!),
      buckets: idx.map((i) => buckets[i]!),
    };
  }
  return { labels, values, buckets };
}

/** Aggregates that make sense on a chart's value axis. */
export const CHART_AGGREGATES: readonly { id: SummaryFn; label: string; needsField: boolean }[] = [
  { id: "count", label: "Count of rows", needsField: false },
  { id: "sum", label: "Sum", needsField: true },
  { id: "avg", label: "Average", needsField: true },
  { id: "min", label: "Minimum", needsField: true },
  { id: "max", label: "Maximum", needsField: true },
  { id: "unique", label: "Unique values", needsField: true },
];

/** A readable label for what the chart is actually showing. */
export function describeSeries(aggregate: SummaryFn, valueField: string, groupBy: string): string {
  const agg = CHART_AGGREGATES.find((a) => a.id === aggregate);
  const measure = !agg || !agg.needsField || valueField === "" ? "Count" : `${agg.label} of ${valueField}`;
  return groupBy === "" ? measure : `${measure} by ${groupBy}`;
}
