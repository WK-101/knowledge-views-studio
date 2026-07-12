import { describe, it, expect } from "vitest";
import { parseExpression } from "../src/domain/query/parser";
import { validateExpression } from "../src/domain/query/evaluate";
import { QueryError } from "../src/domain/query/ast";

describe("parseExpression", () => {
  it("builds a precedence-correct tree", () => {
    const ast = parseExpression("Year >= 2020 and Status == \"open\"");
    expect(ast.kind).toBe("logical");
    if (ast.kind === "logical") {
      expect(ast.op).toBe("and");
      expect(ast.left.kind).toBe("binary");
      expect(ast.right.kind).toBe("binary");
    }
  });

  it("parses bracketed field names with spaces", () => {
    const ast = parseExpression("[First Name] == \"Ada\"");
    expect(ast.kind).toBe("binary");
    if (ast.kind === "binary") expect(ast.left).toEqual({ kind: "field", name: "First Name" });
  });

  it("parses calls and ternaries", () => {
    expect(parseExpression("contains(Tags, \"x\")").kind).toBe("call");
    expect(parseExpression("Year >= 2020 ? \"new\" : \"old\"").kind).toBe("conditional");
  });

  it("throws QueryError on malformed input", () => {
    expect(() => parseExpression("Year >=")).toThrow(QueryError);
    expect(() => parseExpression("")).toThrow(QueryError);
    expect(() => parseExpression("(a == b")).toThrow(QueryError);
  });
});

describe("validateExpression", () => {
  it("accepts valid expressions and rejects unknown functions", () => {
    expect(validateExpression("lower(Status) == \"x\"")).toEqual({ ok: true });
    const bad = validateExpression("bogus(1)");
    expect(bad.ok).toBe(false);
    if (!bad.ok) expect(bad.error).toMatch(/unknown function/i);
  });

  it("reports a position for syntax errors", () => {
    const result = validateExpression("Year >=");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(typeof result.position).toBe("number");
  });
});
