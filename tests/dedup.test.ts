import { describe, it, expect } from "vitest";
import { findDuplicateDois, rowCompleteness } from "../src/services/import/dedup";
import { tableExtractor } from "../src/domain/index";
import type { SourceFileMeta } from "../src/domain/index";

const file: SourceFileMeta = { filePath: "L.md", fileName: "L", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };

const CONTENT = [
  "| Title | DOI | Year |",
  "| --- | --- | --- |",
  "| Attention | 10.5555/3295222 | 2017 |", // richest (3 cells)
  "| attention (dup) | https://doi.org/10.5555/3295222 |  |", // same DOI, different form, 2 cells
  "| BERT | 10.18653/v1/N19-1423 | 2019 |",
  "| Unique |  | 2020 |", // no DOI — ignored
].join("\n");

const rows = tableExtractor.extract({ file, content: CONTENT });

describe("findDuplicateDois", () => {
  it("groups by normalised DOI across URL/bare forms, richest row first", () => {
    const groups = findDuplicateDois(rows, "DOI");
    expect(groups).toHaveLength(1);
    expect(groups[0]!.doi).toBe("10.5555/3295222");
    expect(groups[0]!.rows).toHaveLength(2);
    // The fuller row (3 non-empty cells) sorts first as the natural keeper.
    expect(rowCompleteness(groups[0]!.rows[0]!)).toBeGreaterThan(rowCompleteness(groups[0]!.rows[1]!));
  });

  it("ignores rows without a DOI and unique DOIs", () => {
    const groups = findDuplicateDois(rows, "DOI");
    expect(groups.every((g) => g.rows.length > 1)).toBe(true);
    expect(groups.flatMap((g) => g.rows).some((r) => r.cells["Title"] === "BERT")).toBe(false);
  });
});
