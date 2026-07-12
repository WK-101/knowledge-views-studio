import { describe, it, expect } from "vitest";
import { applyOperator, evaluateFilterGroup, type FilterGroup } from "../src/domain/transform/filter";
import { FieldTypeResolver } from "../src/domain/transform/field-type";
import { createDefaultColumnTypeRegistry } from "../src/domain/columns/index";
import { makeRow } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();
const text = registry.get("text");
const number = registry.get("number");
const date = registry.get("date");
const checkbox = registry.get("checkbox");

describe("applyOperator covers all 13 operators", () => {
  it("string operators", () => {
    expect(applyOperator(text, "Hello World", "contains", "world")).toBe(true);
    expect(applyOperator(text, "Hello World", "not-contains", "xyz")).toBe(true);
    expect(applyOperator(text, "Hello", "starts-with", "he")).toBe(true);
    expect(applyOperator(text, "Hello", "ends-with", "lo")).toBe(true);
    expect(applyOperator(text, "abc123", "regex", "\\d+")).toBe(true);
  });

  it("equality and ordering, type-aware", () => {
    expect(applyOperator(number, "2021", "gt", "2020")).toBe(true);
    expect(applyOperator(number, "5", "lt", "10")).toBe(true);
    expect(applyOperator(number, "5", "lte", "5")).toBe(true);
    expect(applyOperator(date, "2021-05-01", "gte", "2021-01-01")).toBe(true);
    expect(applyOperator(text, "Open", "equals", "open")).toBe(true);
    expect(applyOperator(text, "Open", "not-equals", "closed")).toBe(true);
    expect(applyOperator(checkbox, "x", "equals", "true")).toBe(true);
  });

  it("emptiness", () => {
    expect(applyOperator(text, "", "is-empty", "")).toBe(true);
    expect(applyOperator(text, "x", "is-not-empty", "")).toBe(true);
  });
});

describe("evaluateFilterGroup", () => {
  const resolver = new FieldTypeResolver(registry, [
    { name: "Year", type: "number" },
    { name: "Status", type: "select" },
  ]);
  const row = makeRow({ Year: "2021", Status: "Open" });

  it("AND requires all, OR requires any, empty matches all", () => {
    const and: FilterGroup = {
      combinator: "and",
      conditions: [
        { field: "Year", operator: "gte", value: "2020" },
        { field: "Status", operator: "equals", value: "Open" },
      ],
      groups: [],
    };
    expect(evaluateFilterGroup(row, and, resolver)).toBe(true);

    const or: FilterGroup = {
      combinator: "or",
      conditions: [
        { field: "Year", operator: "lt", value: "2000" },
        { field: "Status", operator: "equals", value: "Open" },
      ],
      groups: [],
    };
    expect(evaluateFilterGroup(row, or, resolver)).toBe(true);

    const empty: FilterGroup = { combinator: "and", conditions: [], groups: [] };
    expect(evaluateFilterGroup(row, empty, resolver)).toBe(true);
  });

  it("evaluates nested groups", () => {
    const nested: FilterGroup = {
      combinator: "and",
      conditions: [{ field: "Year", operator: "gte", value: "2020" }],
      groups: [
        {
          combinator: "or",
          conditions: [
            { field: "Status", operator: "equals", value: "Closed" },
            { field: "Status", operator: "equals", value: "Open" },
          ],
          groups: [],
        },
      ],
    };
    expect(evaluateFilterGroup(row, nested, resolver)).toBe(true);
  });

  it("NONE passes only when no child matches", () => {
    const noneMatch: FilterGroup = {
      combinator: "none",
      conditions: [
        { field: "Status", operator: "equals", value: "Closed" },
        { field: "Year", operator: "lt", value: "2000" },
      ],
      groups: [],
    };
    expect(evaluateFilterGroup(row, noneMatch, resolver)).toBe(true);

    const oneMatches: FilterGroup = {
      combinator: "none",
      conditions: [{ field: "Status", operator: "equals", value: "Open" }],
      groups: [],
    };
    expect(evaluateFilterGroup(row, oneMatches, resolver)).toBe(false);
  });

  it("evaluates a nested group whose combinator is none", () => {
    // Year >= 2020 AND (none of: Status == Closed)
    const group: FilterGroup = {
      combinator: "and",
      conditions: [{ field: "Year", operator: "gte", value: "2020" }],
      groups: [
        { combinator: "none", conditions: [{ field: "Status", operator: "equals", value: "Closed" }], groups: [] },
      ],
    };
    expect(evaluateFilterGroup(row, group, resolver)).toBe(true);
  });
});
