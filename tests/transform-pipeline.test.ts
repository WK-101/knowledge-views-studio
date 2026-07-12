import { describe, it, expect } from "vitest";
import { runTransform, type TransformSpec } from "../src/domain/transform/pipeline";
import { createDefaultColumnTypeRegistry } from "../src/domain/columns/index";
import { makeRow, NOW } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();
const options = { registry, now: NOW };

const dataset = [
  makeRow({ Title: "Alpha", Year: "2021", Status: "Open" }, { fileName: "Alpha" }),
  makeRow({ Title: "Bravo", Year: "2019", Status: "Done" }, { fileName: "Bravo" }),
  makeRow({ Title: "Cain", Year: "2022", Status: "Open" }, { fileName: "Cain" }),
  makeRow({ Title: "Delta", Year: "2018", Status: "Done" }, { fileName: "Delta" }),
];

describe("runTransform", () => {
  it("is an identity transform for an empty spec", () => {
    const result = runTransform(dataset, {}, options);
    expect(result.rows).toHaveLength(4);
    expect(result.groups).toBeNull();
    expect(result.total).toBe(4);
  });

  it("reports gathered rows so an empty result can be diagnosed (filtered-out vs nothing found)", () => {
    // A query that matches nothing: rows were gathered, but all filtered out.
    const filtered = runTransform(dataset, { advancedQuery: 'Status == "zzz"' }, options);
    expect(filtered.total).toBe(0);
    expect(filtered.gathered).toBe(4);
    // No source rows at all.
    const nothing = runTransform([], {}, options);
    expect(nothing.total).toBe(0);
    expect(nothing.gathered).toBe(0);
  });

  it("computes a column, filters on it, and sorts descending", () => {
    const spec: TransformSpec = {
      columns: [{ name: "Year", type: "number" }],
      computed: [{ name: "Recent", expression: "Year >= 2020 ? \"yes\" : \"no\"" }],
      advancedQuery: "Recent == \"yes\"",
      sort: [{ field: "Year", direction: "desc" }],
    };
    const result = runTransform(dataset, spec, options);
    expect(result.rows.map((r) => r.cells.Title)).toEqual(["Cain", "Alpha"]);
    expect(result.rows[0]!.cells.Recent).toBe("yes");
  });

  it("applies structured filters with a typed comparison", () => {
    const spec: TransformSpec = {
      columns: [{ name: "Year", type: "number" }],
      filter: {
        combinator: "and",
        conditions: [{ field: "Year", operator: "gte", value: "2020" }],
        groups: [],
      },
      sort: [{ field: "Title", direction: "asc" }],
    };
    const result = runTransform(dataset, spec, options);
    expect(result.rows.map((r) => r.cells.Title)).toEqual(["Alpha", "Cain"]);
  });

  it("groups by a field with groups sorted", () => {
    const result = runTransform(
      dataset,
      { group: { field: "Status", direction: "asc" } },
      options,
    );
    expect(result.groups).not.toBeNull();
    const groups = result.groups!;
    expect(groups.map((g) => g.key)).toEqual(["Done", "Open"]);
    expect(groups[0]!.rows).toHaveLength(2);
  });

  it("paginates and clamps out-of-range pages", () => {
    const spec: TransformSpec = {
      sort: [{ field: "Title", direction: "asc" }],
      page: { size: 2, index: 5 },
    };
    const result = runTransform(dataset, spec, options);
    expect(result.total).toBe(4);
    expect(result.page).toEqual({ size: 2, index: 1, count: 2 });
    expect(result.rows.map((r) => r.cells.Title)).toEqual(["Cain", "Delta"]);
  });

  it("reports non-empty fields across the full result (drives hide-empty-columns)", () => {
    const rows = [
      makeRow({ Title: "A", Notes: "hi", Blank: "" }, { fileName: "A" }),
      makeRow({ Title: "B", Notes: "", Blank: "" }, { fileName: "B" }),
      makeRow({ Title: "C", Notes: "  ", Blank: "" }, { fileName: "C" }),
    ];
    const result = runTransform(rows, {}, options);
    const nonEmpty = new Set(result.nonEmptyFields ?? []);
    expect(nonEmpty.has("title")).toBe(true); // has values
    expect(nonEmpty.has("notes")).toBe(true); // "hi" in one row
    expect(nonEmpty.has("blank")).toBe(false); // blank/whitespace in every row
  });

  it("computes non-empty fields over all matching rows, not just a page", () => {
    const rows = [
      makeRow({ Title: "A", Rare: "" }, { fileName: "A" }),
      makeRow({ Title: "B", Rare: "found" }, { fileName: "B" }), // only the 2nd row has Rare
    ];
    // Even paginated to 1/page, Rare counts as non-empty (full-result, page-independent).
    const result = runTransform(rows, { page: { size: 1, index: 0 } }, options);
    expect(new Set(result.nonEmptyFields ?? []).has("rare")).toBe(true);
  });
});