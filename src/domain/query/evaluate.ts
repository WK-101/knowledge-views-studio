import { QueryError, type BinaryOp, type Expr } from "./ast";
import { parseExpression } from "./parser";
import type { Row } from "../model";
import { getField } from "../fields";
import {
  BUILT_IN_FUNCTIONS,
  compareValues,
  equalsValues,
  toBoolean,
  toNumber,
  toStringValue,
  type ExprValue,
  type QueryFunction,
} from "./functions";

export interface EvalContext {
  readonly row: Row;
  readonly now: number;
  readonly functions: Readonly<Record<string, QueryFunction>>;
}

function evaluateBinary(op: BinaryOp, a: ExprValue, b: ExprValue): ExprValue {
  switch (op) {
    case "+": {
      const an = toNumber(a);
      const bn = toNumber(b);
      return an !== null && bn !== null ? an + bn : toStringValue(a) + toStringValue(b);
    }
    case "-": {
      const x = toNumber(a);
      const y = toNumber(b);
      return x === null || y === null ? null : x - y;
    }
    case "*": {
      const x = toNumber(a);
      const y = toNumber(b);
      return x === null || y === null ? null : x * y;
    }
    case "/": {
      const x = toNumber(a);
      const y = toNumber(b);
      return x === null || y === null || y === 0 ? null : x / y;
    }
    case "%": {
      const x = toNumber(a);
      const y = toNumber(b);
      return x === null || y === null || y === 0 ? null : x % y;
    }
    case "==":
      return equalsValues(a, b);
    case "!=":
      return !equalsValues(a, b);
    case ">":
      return compareValues(a, b) > 0;
    case ">=":
      return compareValues(a, b) >= 0;
    case "<":
      return compareValues(a, b) < 0;
    case "<=":
      return compareValues(a, b) <= 0;
  }
}

/** Evaluate an AST node against a row. Signature is `(node, context)` — fixed and tested. */
export function evaluateExpression(node: Expr, ctx: EvalContext): ExprValue {
  switch (node.kind) {
    case "literal":
      return node.value;
    case "field":
      return getField(ctx.row, node.name);
    case "unary": {
      const value = evaluateExpression(node.operand, ctx);
      if (node.op === "not") return !toBoolean(value);
      const n = toNumber(value);
      return n === null ? null : -n;
    }
    case "logical": {
      const left = toBoolean(evaluateExpression(node.left, ctx));
      if (node.op === "and") return left ? toBoolean(evaluateExpression(node.right, ctx)) : false;
      return left ? true : toBoolean(evaluateExpression(node.right, ctx));
    }
    case "conditional":
      return toBoolean(evaluateExpression(node.test, ctx))
        ? evaluateExpression(node.consequent, ctx)
        : evaluateExpression(node.alternate, ctx);
    case "binary":
      return evaluateBinary(
        node.op,
        evaluateExpression(node.left, ctx),
        evaluateExpression(node.right, ctx),
      );
    case "call": {
      const fn = ctx.functions[node.name.toLowerCase()];
      if (!fn) throw new QueryError(`Unknown function "${node.name}"`);
      return fn(
        node.args.map((a) => evaluateExpression(a, ctx)),
        { now: ctx.now },
      );
    }
  }
}

export interface CompiledExpression {
  readonly source: string;
  readonly ast: Expr;
  evaluate(row: Row, now?: number): ExprValue;
  test(row: Row, now?: number): boolean;
}

function collectCallNames(node: Expr, into: Set<string>): void {
  switch (node.kind) {
    case "call":
      into.add(node.name.toLowerCase());
      node.args.forEach((a) => collectCallNames(a, into));
      break;
    case "unary":
      collectCallNames(node.operand, into);
      break;
    case "binary":
    case "logical":
      collectCallNames(node.left, into);
      collectCallNames(node.right, into);
      break;
    case "conditional":
      collectCallNames(node.test, into);
      collectCallNames(node.consequent, into);
      collectCallNames(node.alternate, into);
      break;
    case "literal":
    case "field":
      break;
  }
}

/** Parse + validate (unknown functions are rejected here, not at run time). */
export function compileExpression(
  source: string,
  functions: Readonly<Record<string, QueryFunction>> = BUILT_IN_FUNCTIONS,
): CompiledExpression {
  const ast = parseExpression(source);
  const names = new Set<string>();
  collectCallNames(ast, names);
  for (const name of names) {
    if (!functions[name]) throw new QueryError(`Unknown function "${name}"`);
  }
  return {
    source,
    ast,
    evaluate: (row, now = Date.now()) => evaluateExpression(ast, { row, now, functions }),
    test: (row, now = Date.now()) => toBoolean(evaluateExpression(ast, { row, now, functions })),
  };
}

export type ValidationResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly error: string; readonly position?: number };

export function validateExpression(
  source: string,
  functions: Readonly<Record<string, QueryFunction>> = BUILT_IN_FUNCTIONS,
): ValidationResult {
  try {
    compileExpression(source, functions);
    return { ok: true };
  } catch (error) {
    if (error instanceof QueryError) {
      return error.position !== undefined
        ? { ok: false, error: error.message, position: error.position }
        : { ok: false, error: error.message };
    }
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
}
