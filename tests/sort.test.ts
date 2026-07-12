import { describe, it, expect } from "vitest";
import { sortRows } from "../src/domain/transform/sort";
import { FieldTypeResolver } from "../src/domain/transform/field-type";
import { createDefaultColumnTypeRegistry, type ColumnConfig } from "../src/domain/index";
import { makeRow } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();
const resolver = (cols: ColumnConfig[]): FieldTypeResolver => new FieldTypeResolver(registry, cols);

describe("sortRows (decorate-sort-undecorate)", () => {
  it("sorts numerically by a number column, both directions", () => {
    const rows = [makeRow({ n: "10" }), makeRow({ n: "2" }), makeRow({ n: "30" })];
    const r = resolver([{ name: "n", type: "number" }]);
    expect(sortRows(rows, [{ field: "n", direction: "asc" }], r).map((x) => x.cells.n)).toEqual(["2", "10", "30"]);
    expect(sortRows(rows, [{ field: "n", direction: "desc" }], r).map((x) => x.cells.n)).toEqual(["30", "10", "2"]);
  });

  it("applies keys in priority order (primary then secondary)", () => {
    const rows = [makeRow({ g: "b", n: "1" }), makeRow({ g: "a", n: "2" }), makeRow({ g: "a", n: "1" })];
    const r = resolver([{ name: "g", type: "text" }, { name: "n", type: "number" }]);
    const out = sortRows(rows, [{ field: "g", direction: "asc" }, { field: "n", direction: "asc" }], r);
    expect(out.map((x) => `${x.cells.g}${x.cells.n}`)).toEqual(["a1", "a2", "b1"]);
  });

  it("is stable when all keys compare equal", () => {
    const rows = [makeRow({ n: "1", id: "A" }), makeRow({ n: "1", id: "B" }), makeRow({ n: "1", id: "C" })];
    const r = resolver([{ name: "n", type: "number" }]);
    expect(sortRows(rows, [{ field: "n", direction: "asc" }], r).map((x) => x.cells.id)).toEqual(["A", "B", "C"]);
  });

  it("returns a copy and leaves the input untouched", () => {
    const rows = [makeRow({ n: "2" }), makeRow({ n: "1" })];
    const r = resolver([{ name: "n", type: "number" }]);
    const out = sortRows(rows, [{ field: "n", direction: "asc" }], r);
    expect(out).not.toBe(rows);
    expect(rows.map((x) => x.cells.n)).toEqual(["2", "1"]); // original order intact
  });
});
