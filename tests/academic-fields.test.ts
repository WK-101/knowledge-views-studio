import { describe, it, expect } from "vitest";
import { resolveFieldColumn, MAPPABLE_FIELDS } from "../src/domain/columns/academic-fields";

const cols = [
  { name: "Cite key", type: "citekey" },
  { name: "Authors", type: "authors" },
  { name: "Year", type: "number" },
  { name: "Paper Title", type: "text" },
  { name: "DOI", type: "doi" },
];

describe("academic field resolution", () => {
  it("resolves type-based fields regardless of name", () => {
    expect(resolveFieldColumn(cols, "doi")?.name).toBe("DOI");
    expect(resolveFieldColumn(cols, "citekey")?.name).toBe("Cite key");
    expect(resolveFieldColumn(cols, "authors")?.name).toBe("Authors");
  });

  it("resolves name-based fields via heuristics", () => {
    expect(resolveFieldColumn(cols, "year")?.name).toBe("Year");
  });

  it("does not match a renamed generic column by heuristic, but the field map fixes it", () => {
    // "Paper Title" isn't matched by the title heuristic (^title|paper$)…
    expect(resolveFieldColumn(cols, "title")).toBeUndefined();
    // …until it's pinned via the field map.
    expect(resolveFieldColumn(cols, "title", { title: "Paper Title" })?.name).toBe("Paper Title");
  });

  it("field map takes precedence over heuristics", () => {
    expect(resolveFieldColumn(cols, "year", { year: "DOI" })?.name).toBe("DOI");
  });

  it("exposes the mappable (generic-typed) fields", () => {
    expect([...MAPPABLE_FIELDS]).toEqual(["title", "year", "venue", "summary"]);
  });
});
