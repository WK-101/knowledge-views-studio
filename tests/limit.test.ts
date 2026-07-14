import { describe, it, expect } from "vitest";
import { limitRows, moreLabel } from "../src/domain/transform/limit";
import type { Row } from "../src/domain/model";

const file = { fileName: "N", filePath: "N.md", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
const rows = (n: number): Row[] =>
  Array.from({ length: n }, (_, i) => ({
    cells: { A: String(i) },
    file,
    provenance: { filePath: "N.md", extractor: "table", locator: { rowIndex: i }, fingerprint: `f${i}` },
  }));

describe("per-group row limits", () => {
  it("trims to the limit but never lies about the true total", () => {
    const r = limitRows(rows(500), 10);
    expect(r.rows).toHaveLength(10);
    expect(r.total).toBe(500); // the count shown to the user is the REAL one
    expect(r.hidden).toBe(490);
  });

  it("shows everything when expanded", () => {
    const r = limitRows(rows(500), 10, true);
    expect(r.rows).toHaveLength(500);
    expect(r.hidden).toBe(0);
  });

  it("a limit of 0 means unlimited", () => {
    expect(limitRows(rows(500), 0).rows).toHaveLength(500);
  });

  it("a group smaller than the limit is untouched, with no 'show more'", () => {
    const r = limitRows(rows(3), 10);
    expect(r.rows).toHaveLength(3);
    expect(r.hidden).toBe(0);
  });

  it("exactly at the limit shows no 'show more'", () => {
    expect(limitRows(rows(10), 10).hidden).toBe(0);
  });

  it("says plainly how many are hidden", () => {
    expect(moreLabel(490)).toBe("Show 490 more");
  });
});
