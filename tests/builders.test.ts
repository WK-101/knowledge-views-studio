import { describe, it, expect } from "vitest";
import {
  fieldOptions,
  formatOptions,
  mergeDiscovered,
  moveItem,
  operatorsForType,
  parseOptions,
  suggestColumns,
  validateProfileJson,
} from "../src/settings/builders";
import { createDefaultColumnTypeRegistry } from "../src/domain/index";
import { createProfile } from "../src/services/profile/profile";
import { makeRow } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();

describe("suggestColumns", () => {
  it("discovers columns in order with inferred types", () => {
    const rows = [makeRow({ Title: "[[A]]", Year: "2021" }), makeRow({ Title: "[[B]]", Tags: "x" })];
    expect(suggestColumns(rows)).toEqual([
      { name: "Title", type: "link" },
      { name: "Year", type: "number" },
      { name: "Tags", type: "tags" },
    ]);
  });
});

describe("mergeDiscovered", () => {
  it("appends only columns not already configured (case-insensitive)", () => {
    const existing = [{ name: "Title", type: "link" }];
    const discovered = [
      { name: "title", type: "text" },
      { name: "Year", type: "number" },
    ];
    expect(mergeDiscovered(existing, discovered)).toEqual([
      { name: "Title", type: "link" },
      { name: "Year", type: "number" },
    ]);
  });
});

describe("fieldOptions", () => {
  it("includes configured columns plus virtual fields, configured winning", () => {
    const options = fieldOptions([
      { name: "Year", type: "number" },
      { name: "created", type: "text" }, // overrides the virtual "created"
    ]);
    expect(options).toContainEqual({ name: "Year", typeId: "number" });
    expect(options).toContainEqual({ name: "created", typeId: "text" });
    expect(options).toContainEqual({ name: "note", typeId: "link" });
    // "created" appears once (configured), not duplicated as a virtual
    expect(options.filter((o) => o.name.toLowerCase() === "created")).toHaveLength(1);
  });
});

describe("operatorsForType", () => {
  it("returns the column type's operators", () => {
    expect(operatorsForType("number", registry)).toContain("gt");
    expect(operatorsForType("text", registry)).toContain("contains");
    expect(operatorsForType("unknown", registry)).toContain("contains"); // text fallback
  });
});

describe("parseOptions / formatOptions", () => {
  it("round-trips a comma-separated option list", () => {
    const options = parseOptions("Open, In progress , Done");
    expect(options).toEqual([{ value: "Open" }, { value: "In progress" }, { value: "Done" }]);
    expect(formatOptions(options)).toBe("Open, In progress, Done");
    expect(formatOptions(undefined)).toBe("");
  });
});

describe("moveItem", () => {
  it("moves an item and ignores out-of-range requests", () => {
    expect(moveItem(["a", "b", "c"], 0, 2)).toEqual(["b", "c", "a"]);
    expect(moveItem(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"]);
    expect(moveItem(["a", "b"], 0, 5)).toEqual(["a", "b"]);
  });
});

describe("validateProfileJson", () => {
  it("accepts a valid object and assigns a fresh id", () => {
    const source = createProfile({ name: "Exported", advancedQuery: "Year >= 2020" });
    const result = validateProfileJson(JSON.stringify(source));
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.profile.name).toBe("Exported");
      expect(result.profile.id).not.toBe(source.id);
    }
  });

  it("rejects invalid JSON and non-objects", () => {
    expect(validateProfileJson("{ not json").ok).toBe(false);
    expect(validateProfileJson("[1,2,3]").ok).toBe(false);
    expect(validateProfileJson("42").ok).toBe(false);
  });
});
