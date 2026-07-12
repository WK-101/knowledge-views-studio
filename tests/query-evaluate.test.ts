import { describe, it, expect } from "vitest";
import { compileExpression } from "../src/domain/query/evaluate";
import { makeRow, NOW } from "./_helpers";

const row = makeRow(
  { Year: "2021", Status: "Open", Tags: "urgent, backend", Author: "Ada" },
  { created: "2021-01-01" },
);

const test = (expr: string): boolean => compileExpression(expr).test(row, NOW);
const value = (expr: string): unknown => compileExpression(expr).evaluate(row, NOW);

describe("evaluate — comparisons coerce by type", () => {
  it("compares numbers numerically even though cells are strings", () => {
    expect(test("Year >= 2020")).toBe(true);
    expect(test("Year < 2000")).toBe(false);
  });

  it("string equality is case-sensitive; lower() makes it insensitive", () => {
    expect(test("Status == \"Open\"")).toBe(true);
    expect(test("Status == \"open\"")).toBe(false);
    expect(test("lower(Status) == \"open\"")).toBe(true);
  });
});

describe("evaluate — logical, ternary, arithmetic", () => {
  it("handles and/or/not", () => {
    expect(test("Year >= 2020 and contains(Tags, \"urgent\")")).toBe(true);
    expect(test("Year < 2000 or Status == \"Open\"")).toBe(true);
    expect(test("not empty(Author)")).toBe(true);
  });

  it("ternary selects a branch", () => {
    expect(value("Year >= 2020 ? \"new\" : \"old\"")).toBe("new");
  });

  it("does arithmetic and string concat with +", () => {
    expect(value("Year + 1")).toBe(2022);
    expect(value("Author + \" (\" + Year + \")\"")).toBe("Ada (2021)");
  });
});

describe("evaluate — functions", () => {
  it("daysSince uses the injected clock", () => {
    expect(value("daysSince(Created)")).toBe(10);
  });

  it("year/coalesce/round behave", () => {
    expect(value("year(Created)")).toBe(2021);
    expect(value("coalesce(Missing, Author)")).toBe("Ada");
    expect(value("round(3.14159, 2)")).toBe(3.14);
  });
});
