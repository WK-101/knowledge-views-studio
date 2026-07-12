import { describe, it, expect } from "vitest";
import { getField, isVirtualField } from "../src/domain/fields";
import type { Row } from "../src/domain/model";

const row: Row = {
  cells: { Source: "[[Paper A]]", Year: "2021" },
  file: {
    filePath: "Research/Smith 2021.md",
    fileName: "Smith 2021",
    folderPath: "Research",
    createdMs: Date.parse("2021-03-01"),
    modifiedMs: Date.parse("2021-04-01"),
    sizeBytes: 100,
  },
  provenance: { filePath: "Research/Smith 2021.md", extractor: "table", locator: {}, fingerprint: "x" },
};

describe("getField", () => {
  it("resolves virtual fields from file metadata", () => {
    expect(getField(row, "note")).toBe("Smith 2021");
    expect(getField(row, "folder")).toBe("Research");
    expect(getField(row, "path")).toBe("Research/Smith 2021.md");
    expect(getField(row, "Created")).toBe("2021-03-01");
    expect(getField(row, "modified")).toBe("2021-04-01");
  });

  it("resolves data columns case-insensitively and returns '' for unknowns", () => {
    expect(getField(row, "source")).toBe("[[Paper A]]");
    expect(getField(row, "YEAR")).toBe("2021");
    expect(getField(row, "missing")).toBe("");
  });

  it("knows which names are virtual", () => {
    expect(isVirtualField("Created")).toBe(true);
    expect(isVirtualField("Source")).toBe(false);
  });
});
