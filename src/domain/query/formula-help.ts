import type { Expr } from "./ast";
import { BUILT_IN_FUNCTIONS, toStringValue, type ExprValue, type QueryFunction } from "./functions";
import type { Row } from "../model";

/**
 * The teaching layer for formulas.
 *
 * A formula box is only as good as what it tells you when the formula is wrong — and a bare textarea
 * tells you nothing. Two things fix that, and both are pure, so both can be tested:
 *
 *   - a *reference*: every function, its signature, what it does, and an example you can click;
 *   - a *trace*: not just the answer, but how the answer was reached, one substitution at a time.
 *
 * The trace is the part that turns "why is this blank?" into "ah — `Hours` is empty on this row".
 */

export interface FunctionDoc {
  readonly name: string;
  readonly signature: string;
  readonly description: string;
  readonly example: string;
  readonly category: "Text" | "Number" | "Date" | "Logic";
}

export const FUNCTION_DOCS: readonly FunctionDoc[] = [
  // Text
  { name: "lower", signature: "lower(text)", description: "Lower-case.", example: 'lower([Status])', category: "Text" },
  { name: "upper", signature: "upper(text)", description: "Upper-case.", example: 'upper([Status])', category: "Text" },
  { name: "trim", signature: "trim(text)", description: "Remove surrounding spaces.", example: "trim([Title])", category: "Text" },
  { name: "length", signature: "length(text)", description: "Number of characters.", example: "length([Title])", category: "Text" },
  { name: "concat", signature: "concat(a, b, ...)", description: "Join values into one string.", example: 'concat([First], " ", [Last])', category: "Text" },
  { name: "replace", signature: "replace(text, find, with)", description: "Replace every occurrence.", example: 'replace([Tags], ",", " · ")', category: "Text" },
  { name: "contains", signature: "contains(text, search)", description: "True if the text contains the search.", example: 'contains([Title], "draft")', category: "Text" },
  { name: "startswith", signature: "startswith(text, prefix)", description: "True if it starts with the prefix.", example: 'startswith([Title], "RFC")', category: "Text" },
  { name: "endswith", signature: "endswith(text, suffix)", description: "True if it ends with the suffix.", example: 'endswith([File], ".md")', category: "Text" },

  // Number
  { name: "number", signature: "number(value)", description: "Read a value as a number.", example: "number([Hours])", category: "Number" },
  { name: "abs", signature: "abs(n)", description: "Absolute value.", example: "abs([Delta])", category: "Number" },
  { name: "round", signature: "round(n, digits)", description: "Round to a number of digits.", example: "round([Score], 1)", category: "Number" },
  { name: "floor", signature: "floor(n)", description: "Round down to a whole number.", example: "floor([Hours])", category: "Number" },
  { name: "ceiling", signature: "ceiling(n)", description: "Round up to a whole number.", example: "ceiling([Hours])", category: "Number" },
  { name: "min", signature: "min(a, b, ...)", description: "Smallest value.", example: "min([Est], [Actual])", category: "Number" },
  { name: "max", signature: "max(a, b, ...)", description: "Largest value.", example: "max([Est], [Actual])", category: "Number" },

  // Date
  { name: "today", signature: "today()", description: "Today's date.", example: "days([Due], today())", category: "Date" },
  { name: "now", signature: "now()", description: "The current date and time.", example: "now()", category: "Date" },
  { name: "year", signature: "year(date)", description: "The year.", example: "year([Published])", category: "Date" },
  { name: "month", signature: "month(date)", description: "The month (1–12).", example: "month([Due])", category: "Date" },
  { name: "day", signature: "day(date)", description: "The day of the month.", example: "day([Due])", category: "Date" },
  { name: "days", signature: "days(start, end)", description: "Whole days between two dates.", example: "days([Start], [Due])", category: "Date" },
  { name: "daysfromnow", signature: "daysfromnow(date)", description: "Days from today (negative if past).", example: "daysfromnow([Due])", category: "Date" },
  { name: "dayssince", signature: "dayssince(date)", description: "Days since a date.", example: "dayssince([Created])", category: "Date" },
  { name: "adddays", signature: "adddays(date, n)", description: "Shift a date by n days.", example: "adddays([Start], 14)", category: "Date" },
  { name: "dateadd", signature: 'dateadd(date, n, "days")', description: 'Shift by days, weeks, months or years.', example: 'dateadd([Start], 2, "weeks")', category: "Date" },

  // Logic
  { name: "if", signature: "if(test, then, else)", description: "Pick one of two values.", example: 'if([Hours] > 8, "Long", "Short")', category: "Logic" },
  { name: "coalesce", signature: "coalesce(a, b, ...)", description: "The first value that isn't empty.", example: 'coalesce([Nickname], [Name])', category: "Logic" },
  { name: "empty", signature: "empty(value)", description: "True if the value is blank.", example: "empty([Due])", category: "Logic" },
  { name: "notempty", signature: "notempty(value)", description: "True if the value has something in it.", example: "notempty([Due])", category: "Logic" },
];

export function functionDoc(name: string): FunctionDoc | undefined {
  return FUNCTION_DOCS.find((f) => f.name === name.toLowerCase());
}

// ---------------------------------------------------------------- trace

export interface TraceStep {
  /** The sub-expression, re-printed from the AST (so it is canonical, not the user's spacing). */
  readonly expr: string;
  /** What it evaluated to. */
  readonly value: string;
  /** Nesting depth — the UI indents by this. */
  readonly depth: number;
  /** Set when this step is the reason the whole thing is blank or wrong. */
  readonly note?: string;
}

/** Re-print an AST node as source. Canonical, so the trace lines up with what actually ran. */
export function printExpr(expr: Expr): string {
  switch (expr.kind) {
    case "literal":
      return typeof expr.value === "string" ? JSON.stringify(expr.value) : String(expr.value);
    case "field":
      return `[${expr.name}]`;
    case "unary":
      return expr.op === "not" ? `not ${printExpr(expr.operand)}` : `-${printExpr(expr.operand)}`;
    case "binary":
      return `${printExpr(expr.left)} ${expr.op} ${printExpr(expr.right)}`;
    case "logical":
      return `${printExpr(expr.left)} ${expr.op} ${printExpr(expr.right)}`;
    case "conditional":
      return `${printExpr(expr.test)} ? ${printExpr(expr.consequent)} : ${printExpr(expr.alternate)}`;
    case "call":
      return `${expr.name}(${expr.args.map(printExpr).join(", ")})`;
  }
}

function show(value: ExprValue): string {
  if (value === null) return "(empty)";
  if (typeof value === "string") return value === "" ? "(empty)" : `"${value}"`;
  return String(value);
}

/**
 * Walk the expression bottom-up, recording what each piece evaluated to.
 *
 * This is the answer to "why is my formula blank?", which a bare result can never give. Literals are
 * skipped — nobody needs to be told that `2` is `2` — and a field that resolved to nothing is called out
 * by name, because an empty field is the cause of a blank formula nine times in ten.
 */
export function traceExpression(
  expr: Expr,
  row: Row,
  now = Date.now(),
  functions: Readonly<Record<string, QueryFunction>> = BUILT_IN_FUNCTIONS,
): TraceStep[] {
  const steps: TraceStep[] = [];

  const walk = (node: Expr, depth: number): ExprValue => {
    switch (node.kind) {
      case "literal":
        return node.value; // not worth a step

      case "field": {
        const raw = row.cells[node.name] ?? findCaseInsensitive(row, node.name);
        const value: ExprValue = raw === undefined || raw === "" ? null : raw;
        steps.push({
          expr: printExpr(node),
          value: show(value),
          depth,
          ...(value === null ? { note: `“${node.name}” is empty on this row` } : {}),
        });
        return value;
      }

      case "unary": {
        const operand = walk(node.operand, depth + 1);
        const value = evalNode(node, [operand], functions, now, row);
        steps.push({ expr: printExpr(node), value: show(value), depth });
        return value;
      }

      case "binary":
      case "logical": {
        const left = walk(node.left, depth + 1);
        const right = walk(node.right, depth + 1);
        const value = evalNode(node, [left, right], functions, now, row);
        steps.push({ expr: printExpr(node), value: show(value), depth });
        return value;
      }

      case "conditional": {
        const test = walk(node.test, depth + 1);
        // Only the branch actually taken is traced — showing the untaken one implies it ran, and it didn't.
        const taken = truthy(test) ? node.consequent : node.alternate;
        const value = walk(taken, depth + 1);
        steps.push({
          expr: printExpr(node),
          value: show(value),
          depth,
          note: truthy(test) ? "test was true — took the first branch" : "test was false — took the second branch",
        });
        return value;
      }

      case "call": {
        const args = node.args.map((a) => walk(a, depth + 1));
        const fn = functions[node.name.toLowerCase()];
        if (!fn) {
          steps.push({ expr: printExpr(node), value: "(unknown function)", depth, note: `There is no function called “${node.name}”` });
          return null;
        }
        let value: ExprValue = null;
        try {
          value = fn(args, { now });
        } catch (error) {
          steps.push({ expr: printExpr(node), value: "(error)", depth, note: error instanceof Error ? error.message : String(error) });
          return null;
        }
        // `if` and `coalesce` are ordinary function calls, so every argument really is evaluated — they
        // do not short-circuit. Saying *which* argument was returned is therefore both truthful about
        // what ran and the thing the reader actually wants to know.
        const chose = choiceNote(node.name.toLowerCase(), args, value);
        steps.push({ expr: printExpr(node), value: show(value), depth, ...(chose ? { note: chose } : {}) });
        return value;
      }
    }
  };

  walk(expr, 0);
  return steps.reverse(); // outermost first: the answer, then how it was reached
}

function findCaseInsensitive(row: Row, name: string): string | undefined {
  const key = name.trim().toLowerCase();
  for (const [col, value] of Object.entries(row.cells)) {
    if (col.trim().toLowerCase() === key) return value;
  }
  return undefined;
}

/** For `if`/`coalesce`, say which argument came back — the reader's real question. */
function choiceNote(name: string, args: readonly ExprValue[], value: ExprValue): string | undefined {
  if (name === "if") {
    return truthy(args[0] ?? null)
      ? "the test was true, so the second argument was returned"
      : "the test was false, so the third argument was returned";
  }
  if (name === "coalesce") {
    const i = args.findIndex((a) => a !== null && a !== "");
    return i < 0 ? "every argument was empty" : `the first non-empty argument was #${i + 1}`;
  }
  void value;
  return undefined;
}

function truthy(v: ExprValue): boolean {
  if (v === null || v === false) return false;
  if (v === "" || v === 0) return false;
  return true;
}

/** Evaluate a single node given its already-evaluated children. Mirrors the real evaluator's semantics. */
function evalNode(
  node: Expr,
  children: readonly ExprValue[],
  _functions: Readonly<Record<string, QueryFunction>>,
  _now: number,
  _row: Row,
): ExprValue {
  const num = (v: ExprValue): number => {
    const n = Number(toStringValue(v));
    return Number.isFinite(n) ? n : 0;
  };
  if (node.kind === "unary") {
    const [a] = children as [ExprValue];
    return node.op === "not" ? !truthy(a) : -num(a);
  }
  if (node.kind === "logical") {
    const [a, b] = children as [ExprValue, ExprValue];
    return node.op === "and" ? truthy(a) && truthy(b) : truthy(a) || truthy(b);
  }
  if (node.kind === "binary") {
    const [a, b] = children as [ExprValue, ExprValue];
    switch (node.op) {
      case "+": {
        if (typeof a === "number" || typeof b === "number") return num(a) + num(b);
        const sa = toStringValue(a);
        const sb = toStringValue(b);
        const na = Number(sa);
        const nb = Number(sb);
        if (sa !== "" && sb !== "" && Number.isFinite(na) && Number.isFinite(nb)) return na + nb;
        return sa + sb;
      }
      case "-":
        return num(a) - num(b);
      case "*":
        return num(a) * num(b);
      case "/":
        return num(b) === 0 ? null : num(a) / num(b);
      case "%":
        return num(b) === 0 ? null : num(a) % num(b);
      case "==":
        return toStringValue(a) === toStringValue(b);
      case "!=":
        return toStringValue(a) !== toStringValue(b);
      case ">":
        return num(a) > num(b);
      case ">=":
        return num(a) >= num(b);
      case "<":
        return num(a) < num(b);
      case "<=":
        return num(a) <= num(b);
    }
  }
  return null;
}
