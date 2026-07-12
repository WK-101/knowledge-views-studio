import { describe, it, expect } from "vitest";
import { tableExtractor, TABLE_EXTRACTOR_ID } from "../src/domain/extract/table-extractor";
import type { SourceFileMeta } from "../src/domain/model";

const file: SourceFileMeta = {
  filePath: "Research/Smith 2021.md",
  fileName: "Smith 2021",
  folderPath: "Research",
  createdMs: Date.parse("2021-03-01"),
  modifiedMs: Date.parse("2021-04-01"),
  sizeBytes: 100,
};

const content = [
  "| Source | Year |",
  "| --- | --- |",
  "| [[Paper A]] | 2021 |",
  "| [[Paper B]] | 2019 |",
].join("\n");

describe("tableExtractor", () => {
  it("emits one row per body row with cells, provenance, and fingerprint", () => {
    const rows = tableExtractor.extract({ file, content });
    expect(rows).toHaveLength(2);

    const row = rows[0]!;
    expect(row.cells).toEqual({ Source: "[[Paper A]]", Year: "2021" });
    expect(row.file).toBe(file);
    expect(row.provenance.extractor).toBe(TABLE_EXTRACTOR_ID);
    expect(row.provenance.locator).toMatchObject({ tableIndex: 0, rowIndex: 0, line: 2 });
    expect(row.provenance.fingerprint).toBeTruthy();
  });

  it("gives different rows different fingerprints", () => {
    const rows = tableExtractor.extract({ file, content });
    expect(rows[0]!.provenance.fingerprint).not.toBe(rows[1]!.provenance.fingerprint);
  });

  it("returns nothing for note content with no tables", () => {
    expect(tableExtractor.extract({ file, content: "# Just prose\n\nNo tables." })).toEqual([]);
  });
});
