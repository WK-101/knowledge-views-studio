import { describe, it, expect } from "vitest";
import { buildRowsFromBasesData, BASES_EXTRACTOR_ID, type ExtractedBasesEntry } from "../src/services/bases/bases-row";
import { getField } from "../src/domain/index";

const entry = (index: number, cells: Record<string, string>): ExtractedBasesEntry => ({
  filePath: `Notes/Item ${index}.md`,
  fileName: `Item ${index}`,
  folderPath: "Notes",
  createdMs: Date.parse("2026-01-01"),
  modifiedMs: Date.parse("2026-02-01"),
  index,
  cells,
});

describe("buildRowsFromBasesData", () => {
  it("maps each Bases entry to one read-only KVS row keyed by property id", () => {
    const entries = [
      entry(0, { "note.status": "Doing", "note.points": "5" }),
      entry(1, { "note.status": "Done", "note.points": "3" }),
    ];
    const rows = buildRowsFromBasesData(entries, ["note.status", "note.points"]);

    expect(rows).toHaveLength(2);
    expect(getField(rows[0]!, "note.status")).toBe("Doing");
    // virtual fields resolve from the synthesized file metadata
    expect(getField(rows[0]!, "note")).toBe("Item 0");
    expect(getField(rows[0]!, "folder")).toBe("Notes");
    // provenance marks these as Bases-sourced (no table cell to write back to)
    expect(rows[0]!.provenance.extractor).toBe(BASES_EXTRACTOR_ID);
    expect(rows[0]!.provenance.locator).toEqual({ entryIndex: 0 });
  });

  it("gives rows with identical content distinct fingerprints only when content differs", () => {
    const same = buildRowsFromBasesData([entry(0, { "note.x": "a" }), entry(1, { "note.x": "a" })], ["note.x"]);
    const diff = buildRowsFromBasesData([entry(0, { "note.x": "a" }), entry(1, { "note.x": "b" })], ["note.x"]);
    expect(same[0]!.provenance.fingerprint).toBe(same[1]!.provenance.fingerprint);
    expect(diff[0]!.provenance.fingerprint).not.toBe(diff[1]!.provenance.fingerprint);
  });
});
