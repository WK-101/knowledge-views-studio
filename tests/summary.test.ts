import { describe, it, expect } from "vitest";
import { summarizeColumn, hasSummaries, SUMMARY_FUNCTIONS } from "../src/domain/transform/summary";
import type { Row } from "../src/domain/model";

const file = { fileName: "N", filePath: "N.md", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
const row = (cells: Record<string, string>): Row => ({
  cells,
  file,
  provenance: { filePath: "N.md", extractor: "table", locator: { rowIndex: 0 }, fingerprint: "f" },
});

const rows = [
  row({ Task: "A", Hours: "3", Status: "Done" }),
  row({ Task: "B", Hours: "4.5", Status: "Done" }),
  row({ Task: "C", Hours: "", Status: "Todo" }),
  row({ Task: "D", Hours: "2", Status: "" }),
];
const col = (name: string) => ({ name, type: "text" });

describe("column summaries", () => {
  it("counts, fills and empties describe what is on screen", () => {
    expect(summarizeColumn(rows, col("Hours"), "count-all")).toBe("4");
    expect(summarizeColumn(rows, col("Hours"), "count")).toBe("3"); // one is blank
    expect(summarizeColumn(rows, col("Hours"), "empty")).toBe("1");
    expect(summarizeColumn(rows, col("Status"), "percent-filled")).toBe("75%");
    expect(summarizeColumn(rows, col("Status"), "unique")).toBe("2"); // Done, Todo
  });

  it("does the arithmetic, ignoring blanks", () => {
    expect(summarizeColumn(rows, col("Hours"), "sum")).toBe("9.5");
    expect(summarizeColumn(rows, col("Hours"), "avg")).toBe("3.17");
    expect(summarizeColumn(rows, col("Hours"), "range")).toBe("2.5"); // 4.5 - 2
  });

  it("falls back to numeric min/max without a type resolver", () => {
    expect(summarizeColumn(rows, col("Hours"), "min")).toBe("2");
    expect(summarizeColumn(rows, col("Hours"), "max")).toBe("4.5");
  });

  it("says nothing rather than inventing a number for a column with no values", () => {
    const blank = [row({ X: "" }), row({ X: "" })];
    expect(summarizeColumn(blank, col("X"), "sum")).toBe("");
    expect(summarizeColumn(blank, col("X"), "avg")).toBe("");
    expect(summarizeColumn(blank, col("X"), "min")).toBe("");
    // but counting nothing is still a meaningful answer
    expect(summarizeColumn(blank, col("X"), "empty")).toBe("2");
  });

  it("summarising no rows at all is silent, not zero", () => {
    expect(summarizeColumn([], col("Hours"), "sum")).toBe("");
    expect(summarizeColumn([], col("Hours"), "count-all")).toBe("");
  });

  it("'none' means none", () => {
    expect(summarizeColumn(rows, col("Hours"), "none")).toBe("");
  });

  it("the footer is drawn only when a column asks for one", () => {
    expect(hasSummaries([{}])).toBe(false);
    expect(hasSummaries([{ summary: "none" }])).toBe(false);
    expect(hasSummaries([{ summary: "sum" }])).toBe(true);
  });

  it("every advertised function actually computes", () => {
    for (const f of SUMMARY_FUNCTIONS) {
      expect(() => summarizeColumn(rows, col("Hours"), f.id)).not.toThrow();
    }
  });
});
