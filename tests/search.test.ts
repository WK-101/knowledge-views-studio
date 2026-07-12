import { describe, it, expect } from "vitest";
import { searchRows } from "../src/domain/transform/search";
import { makeRow } from "./_helpers";

describe("searchRows", () => {
  const rows = [
    makeRow({ Task: "Write report", Owner: "Mara" }, { fileName: "Q3" }),
    makeRow({ Task: "Review code", Owner: "Devin" }, { fileName: "Sprint" }),
  ];
  it("returns all rows for an empty query", () => {
    expect(searchRows(rows, "   ")).toHaveLength(2);
  });
  it("matches across any cell, case-insensitively", () => {
    expect(searchRows(rows, "mara").map((r) => r.cells.Owner)).toEqual(["Mara"]);
    expect(searchRows(rows, "REVIEW")).toHaveLength(1);
  });
  it("matches the note name via a virtual field", () => {
    expect(searchRows(rows, "sprint")).toHaveLength(1);
  });
});
