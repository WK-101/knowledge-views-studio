import { describe, it, expect } from "vitest";
import { applyCellEdits, appendRow, deleteRows } from "../src/services/write/source-writer";
import { tableExtractor } from "../src/domain/index";
import type { SourceFileMeta } from "../src/domain/index";

const file: SourceFileMeta = {
  filePath: "Notes/N.md",
  fileName: "N",
  folderPath: "Notes",
  createdMs: 0,
  modifiedMs: 0,
  sizeBytes: 0,
};

const CONTENT = [
  "# Notes", // 0
  "", // 1
  "| Title | Year | Status |", // 2
  "| --- | --- | --- |", // 3
  "| [[Alpha]] | 2021 | open |", // 4
  "| [[Bravo]] | 2019 | done |", // 5
].join("\n");

const rows = tableExtractor.extract({ file, content: CONTENT });
const alpha = rows[0]!;
const bravo = rows[1]!;

describe("applyCellEdits", () => {
  it("rewrites only the target cell, preserving the rest of the row", () => {
    const result = applyCellEdits(CONTENT, [{ provenance: alpha.provenance, column: "Status", value: "closed" }]);
    expect(result.applied).toBe(1);
    expect(result.failures).toEqual([]);
    expect(result.content).toContain("| [[Alpha]] | 2021 | closed |");
    expect(result.content).toContain("| [[Bravo]] | 2019 | done |"); // untouched
  });

  it("escapes pipes in the new value so the table cannot break", () => {
    const result = applyCellEdits(CONTENT, [{ provenance: alpha.provenance, column: "Title", value: "A|B" }]);
    expect(result.content).toContain("| A\\|B | 2021 | open |");
  });

  it("applies multiple edits to the same row at once", () => {
    const result = applyCellEdits(CONTENT, [
      { provenance: alpha.provenance, column: "Year", value: "2099" },
      { provenance: alpha.provenance, column: "Status", value: "archived" },
    ]);
    expect(result.applied).toBe(2);
    expect(result.content).toContain("| [[Alpha]] | 2099 | archived |");
  });

  it("reports a failure for a column that is not in the table", () => {
    const result = applyCellEdits(CONTENT, [{ provenance: alpha.provenance, column: "Nope", value: "x" }]);
    expect(result.applied).toBe(0);
    expect(result.failures[0]?.reason).toMatch(/not in the source table/i);
  });


  it("pads a short row so a trailing column becomes writable", () => {
    // Header has 4 columns; the row supplies only 2 cells — writing "Note" (last) must pad first.
    const short = [
      "| Title | Year | Status | Note |",
      "| --- | --- | --- | --- |",
      "| [[Gamma]] | 2020 |",
    ].join("\n");
    const grows = tableExtractor.extract({ file, content: short });
    const gamma = grows[0]!;
    const result = applyCellEdits(short, [{ provenance: gamma.provenance, column: "Note", value: "[[Gamma note]]" }]);
    expect(result.failures).toHaveLength(0);
    expect(result.applied).toBe(1);
    const line = result.content.split("\n")[2]!;
    expect(line).toContain("[[Gamma note]]");
    // Re-extracting the written row should now expose the Note cell.
    const re = tableExtractor.extract({ file, content: result.content })[0]!;
    expect(re.cells["Note"]).toBe("[[Gamma note]]");
    expect(re.cells["Title"]).toBe("[[Gamma]]");
  });

  it("relocates by fingerprint when the row index is stale", () => {
    // Remove the Alpha row, so Bravo moves from rowIndex 1 to rowIndex 0.
    const reduced = CONTENT.split("\n").filter((l) => !l.includes("[[Alpha]]")).join("\n");
    // Use Bravo's ORIGINAL provenance (rowIndex 1) against the reduced content.
    const result = applyCellEdits(reduced, [{ provenance: bravo.provenance, column: "Status", value: "archived" }]);
    expect(result.applied).toBe(1);
    expect(result.content).toContain("| [[Bravo]] | 2019 | archived |");
  });
});

describe("deleteRows", () => {
  it("removes the located row and leaves the rest", () => {
    const result = deleteRows(CONTENT, [alpha.provenance]);
    expect(result.ok).toBe(true);
    expect(result.content).not.toContain("[[Alpha]]");
    expect(result.content).toContain("[[Bravo]]");
  });
});

describe("appendRow", () => {
  it("adds a row to the reference row's table", () => {
    const result = appendRow(CONTENT, alpha.provenance, { Title: "[[Cain]]", Year: "2022", Status: "new" });
    expect(result.ok).toBe(true);
    expect(result.content).toContain("| [[Cain]] | 2022 | new |");
    const lines = result.content.split("\n");
    expect(lines.indexOf("| [[Cain]] | 2022 | new |")).toBeGreaterThan(lines.findIndex((l) => l.includes("[[Bravo]]")));
  });
});
