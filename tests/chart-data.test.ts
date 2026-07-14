import { describe, it, expect } from "vitest";
import { buildChartSeries, bucketRows, describeSeries } from "../src/views/chart/chart-data";
import type { Row } from "../src/domain/model";

const file = { fileName: "N", filePath: "N.md", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
const row = (cells: Record<string, string>, i = 0): Row => ({
  cells,
  file,
  provenance: { filePath: "N.md", extractor: "table", locator: { rowIndex: i }, fingerprint: `f${i}` },
});

const rows = [
  row({ Status: "Done", Hours: "3" }, 0),
  row({ Status: "Done", Hours: "5" }, 1),
  row({ Status: "Todo", Hours: "2" }, 2),
  row({ Status: "", Hours: "4" }, 3),
];

describe("chart aggregation", () => {
  it("buckets by a field, keeping first-seen order and naming the blanks", () => {
    const { labels, buckets } = bucketRows(rows, "Status");
    expect(labels).toEqual(["Done", "Todo", "(empty)"]); // blanks are shown, not silently dropped
    expect(buckets[0]).toHaveLength(2);
  });

  it("counts rows per bucket", () => {
    const s = buildChartSeries(rows, "Status", "count", "");
    expect(s.labels).toEqual(["Done", "Todo", "(empty)"]);
    expect(s.values).toEqual([2, 1, 1]);
  });

  it("sums and averages a value column per bucket", () => {
    expect(buildChartSeries(rows, "Status", "sum", "Hours").values).toEqual([8, 2, 4]);
    expect(buildChartSeries(rows, "Status", "avg", "Hours").values).toEqual([4, 2, 4]);
  });

  it("a bucket with nothing numeric is 0, not missing — a missing bar lies about the data", () => {
    const r = [row({ S: "A", V: "x" }), row({ S: "B", V: "7" })];
    const s = buildChartSeries(r, "S", "sum", "V");
    expect(s.labels).toEqual(["A", "B"]);
    expect(s.values).toEqual([0, 7]); // A is present, and zero
  });

  it("keeps the rows behind each bar, so a click can drill into them", () => {
    const s = buildChartSeries(rows, "Status", "count", "");
    expect(s.buckets[0]!.map((r) => r.cells["Hours"])).toEqual(["3", "5"]);
  });

  it("caps a runaway number of buckets, keeping the largest", () => {
    const many = Array.from({ length: 100 }, (_, i) => row({ K: `k${i}`, V: String(i) }, i));
    const s = buildChartSeries(many, "K", "sum", "V", 5);
    expect(s.labels).toHaveLength(5);
    expect(s.values).toEqual([95, 96, 97, 98, 99]); // the five biggest, still in the user's order
  });

  it("says nothing when there is nothing to chart", () => {
    expect(buildChartSeries([], "S", "count", "").labels).toEqual([]);
    expect(buildChartSeries(rows, "", "count", "").labels).toEqual([]);
  });

  it("describes what the chart is actually showing", () => {
    expect(describeSeries("count", "", "Status")).toBe("Count by Status");
    expect(describeSeries("sum", "Hours", "Status")).toBe("Sum of Hours by Status");
  });
});
