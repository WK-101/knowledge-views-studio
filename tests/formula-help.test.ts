import { describe, it, expect } from "vitest";
import { traceExpression, printExpr, FUNCTION_DOCS, functionDoc } from "../src/domain/query/formula-help";
import { parseExpression } from "../src/domain/query/parser";
import { BUILT_IN_FUNCTIONS } from "../src/domain/query/functions";
import type { Row } from "../src/domain/model";

const file = { fileName: "N", filePath: "N.md", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
const row = (cells: Record<string, string>): Row => ({
  cells,
  file,
  provenance: { filePath: "N.md", extractor: "table", locator: { rowIndex: 0 }, fingerprint: "f" },
});
const trace = (src: string, cells: Record<string, string>) =>
  traceExpression(parseExpression(src), row(cells), Date.parse("2026-01-15T00:00:00Z"));

describe("the formula reference is honest about what exists", () => {
  it("every documented function is actually implemented", () => {
    for (const doc of FUNCTION_DOCS) {
      expect(BUILT_IN_FUNCTIONS[doc.name], `${doc.name} is documented but missing`).toBeDefined();
    }
  });
  it("every function has a signature, a description, and an example", () => {
    for (const d of FUNCTION_DOCS) {
      expect(d.signature).toContain(d.name);
      expect(d.description.length).toBeGreaterThan(4);
      expect(d.example).toContain(d.name);
    }
  });
  it("looks up by name, case-insensitively", () => {
    expect(functionDoc("IF")?.category).toBe("Logic");
    expect(functionDoc("nope")).toBeUndefined();
  });
});

describe("printExpr re-prints canonically, so the trace matches what ran", () => {
  it("fields, calls and operators", () => {
    expect(printExpr(parseExpression("[Hours] * 2"))).toBe("[Hours] * 2");
    expect(printExpr(parseExpression('if([A] > 1, "big", "small")'))).toBe('if([A] > 1, "big", "small")');
  });
});

describe("traceExpression — how the answer was reached", () => {
  it("shows each step, outermost answer first", () => {
    const steps = trace("[Hours] * 2", { Hours: "3" });
    expect(steps[0]!.expr).toBe("[Hours] * 2");
    expect(steps[0]!.value).toBe("6");
    // and the field it came from
    expect(steps.some((s) => s.expr === "[Hours]" && s.value === '"3"')).toBe(true);
  });

  it("names the empty field that made the whole formula blank — the point of the whole feature", () => {
    const steps = trace("[Hours] * 2", { Hours: "" });
    const blame = steps.find((s) => s.note !== undefined);
    expect(blame?.expr).toBe("[Hours]");
    expect(blame?.note).toContain("empty");
  });

  it("says which argument if() returned — it is a function call, so both branches really do evaluate", () => {
    const steps = trace('if([H] > 8, "Long", "Short")', { H: "10" });
    expect(steps[0]!.value).toBe('"Long"');
    expect(steps[0]!.note).toContain("second argument");
    const low = trace('if([H] > 8, "Long", "Short")', { H: "2" });
    expect(low[0]!.value).toBe('"Short"');
    expect(low[0]!.note).toContain("third argument");
  });

  it("coalesce says which argument it fell through to", () => {
    const steps = trace("coalesce([A], [B])", { A: "", B: "fallback" });
    expect(steps[0]!.value).toBe('"fallback"');
    expect(steps[0]!.note).toContain("#2");
  });

  it("reports an unknown function by name instead of failing silently", () => {
    const steps = trace("nosuchfn([A])", { A: "1" });
    expect(steps[0]!.note).toContain("no function");
  });

  it("indents nested work so the shape of the formula is visible", () => {
    const steps = trace("round([A] / [B], 1)", { A: "10", B: "3" });
    expect(steps[0]!.depth).toBe(0);
    expect(steps.some((s) => s.depth > 0)).toBe(true);
    expect(steps[0]!.value).toBe("3.3");
  });
});

describe("the new date functions", () => {
  const now = Date.parse("2026-01-15T00:00:00Z");
  const ctx = { now };
  it("days() counts whole days, and carries the sign", () => {
    expect(BUILT_IN_FUNCTIONS["days"]!(["2026-01-01", "2026-01-15"], ctx)).toBe(14);
    expect(BUILT_IN_FUNCTIONS["days"]!(["2026-01-15", "2026-01-01"], ctx)).toBe(-14);
  });
  it("dateadd() adds a month the way a calendar does", () => {
    expect(BUILT_IN_FUNCTIONS["dateadd"]!(["2026-01-31", 1, "months"], ctx)).toContain("2026-03");
  });
  it("adddays() shifts by days", () => {
    expect(BUILT_IN_FUNCTIONS["adddays"]!(["2026-01-01", 14], ctx)).toBe("2026-01-15");
  });
  it("today() is a date, floor/ceiling are whole numbers", () => {
    expect(String(BUILT_IN_FUNCTIONS["today"]!([], ctx))).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(BUILT_IN_FUNCTIONS["floor"]!([2.7], ctx)).toBe(2);
    expect(BUILT_IN_FUNCTIONS["ceiling"]!([2.1], ctx)).toBe(3);
  });
});
