import { describe, it, expect } from "vitest";
import {
  createDefaultColumnTypeRegistry,
  BUILT_IN_COLUMN_TYPES, ACADEMIC_COLUMN_TYPES,
} from "../src/domain/columns/index";
import { inferColumnType } from "../src/domain/columns/infer";
import { compareComparable, type ColumnConfig } from "../src/domain/columns/column-type";

const registry = createDefaultColumnTypeRegistry();
const cfg = (over: Partial<ColumnConfig> = {}): ColumnConfig => ({
  name: "X",
  type: "text",
  ...over,
});

describe("registry", () => {
  it("registers all built-ins and falls back to text for unknown ids", () => {
    expect(registry.all().length).toBe(BUILT_IN_COLUMN_TYPES.length + ACADEMIC_COLUMN_TYPES.length);
    expect(registry.get("number").id).toBe("number");
    expect(registry.get("nope").id).toBe("text");
    expect(registry.get(undefined).id).toBe("text");
  });
});

describe("type behaviours", () => {
  it("number parses, compares, and validates", () => {
    const n = registry.get("number");
    expect(n.toComparable("1,234")).toEqual({ kind: "number", value: 1234 });
    expect(n.toComparable("abc").kind).toBe("string");
    expect(n.validate("abc", cfg({ type: "number" }))).toMatch(/number/i);
    expect(n.validate("42", cfg({ type: "number" }))).toBeNull();
  });

  it("date reduces to a timestamp for sorting", () => {
    const d = registry.get("date");
    const a = d.toComparable("2021-01-01");
    const b = d.toComparable("2020-01-01");
    expect(a.kind).toBe("number");
    expect(compareComparable(a, b)).toBeGreaterThan(0);
  });

  it("checkbox and rating reduce to numbers", () => {
    expect(registry.get("checkbox").toComparable("x")).toEqual({ kind: "number", value: 1 });
    expect(registry.get("checkbox").toComparable("")).toEqual({ kind: "number", value: 0 });
    expect(registry.get("rating").toComparable("★★★")).toEqual({ kind: "number", value: 3 });
    expect(registry.get("rating").toComparable("4")).toEqual({ kind: "number", value: 4 });
  });

  it("select validates against configured options", () => {
    const s = registry.get("select");
    const config = cfg({ type: "select", options: [{ value: "Open" }, { value: "Done" }] });
    expect(s.validate("Done", config)).toBeNull();
    expect(s.validate("Nope", config)).toMatch(/not one of/i);
    expect(s.validate("anything", cfg({ type: "select" }))).toBeNull();
  });

  it("tags and link project to clean plain text", () => {
    expect(registry.get("tags").toPlainText("#a, #b; c")).toBe("a, b, c");
    expect(registry.get("link").toPlainText("[[Note|Alias]]")).toBe("Alias");
    expect(registry.get("link").toPlainText("[[Note]]")).toBe("Note");
  });

  it("every built-in offers at least one operator", () => {
    for (const type of BUILT_IN_COLUMN_TYPES) {
      expect(type.operators.length).toBeGreaterThan(0);
    }
  });
});

describe("inferColumnType", () => {
  it("uses header-name hints first", () => {
    expect(inferColumnType("Year", [])).toBe("number");
    expect(inferColumnType("Created", [])).toBe("date");
    expect(inferColumnType("Status", [])).toBe("select");
    expect(inferColumnType("Notes", [])).toBe("markdown");
    expect(inferColumnType("Title", [])).toBe("link");
  });

  it("falls back to the data when the name is ambiguous", () => {
    expect(inferColumnType("Field", ["1", "2", "3"])).toBe("number");
    expect(inferColumnType("Flag", ["TRUE", "FALSE", "true"])).toBe("checkbox"); // e.g. Excel booleans
    expect(inferColumnType("Field", ["2021-01-01", "2022-02-02"])).toBe("date");
    expect(inferColumnType("Field", ["[[A]]", "[[B]]"])).toBe("link");
    expect(inferColumnType("Field", ["hello", "world"])).toBe("text");
    expect(inferColumnType("Field", [])).toBe("text");
  });
});
