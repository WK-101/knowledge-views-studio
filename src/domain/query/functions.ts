/**
 * Runtime values for the expression language and the coercion rules that keep
 * it predictable. Everything in a row is stored as text, so coercion is where
 * "2021" becomes comparable to the number 2020.
 */
export type ExprValue = string | number | boolean | null;

/** Strict numeric coercion: the *entire* value must be a number (so a date is not). */
export function toNumber(value: ExprValue): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (value === null) return null;
  const s = value.trim().replace(/,/g, "");
  if (s === "" || !/^-?\d*\.?\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/** Date coercion requires date punctuation so bare integers are not read as dates. */
export function parseDateValue(value: ExprValue): number | null {
  if (typeof value !== "string") return null;
  const s = value.trim();
  if (!/[-/]/.test(s)) return null;
  const ms = Date.parse(s);
  return Number.isFinite(ms) ? ms : null;
}

export function toBoolean(value: ExprValue): boolean {
  if (typeof value === "boolean") return value;
  if (typeof value === "number") return value !== 0;
  if (value === null) return false;
  const s = value.trim().toLowerCase();
  return s !== "" && s !== "false" && s !== "0" && s !== "no";
}

function formatNumber(n: number): string {
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)));
}

export function toStringValue(value: ExprValue): string {
  if (value === null) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return formatNumber(value);
  return value;
}

/** Equality: numeric when both are numbers, boolean when either is, else exact (trimmed) text. */
export function equalsValues(a: ExprValue, b: ExprValue): boolean {
  const an = toNumber(a);
  const bn = toNumber(b);
  if (an !== null && bn !== null) return an === bn;
  if (typeof a === "boolean" || typeof b === "boolean") return toBoolean(a) === toBoolean(b);
  return toStringValue(a).trim() === toStringValue(b).trim();
}

/** Ordering: numeric, then date, then lexicographic. Never returns null. */
export function compareValues(a: ExprValue, b: ExprValue): number {
  const an = toNumber(a);
  const bn = toNumber(b);
  if (an !== null && bn !== null) return Math.sign(an - bn);
  const ad = parseDateValue(a);
  const bd = parseDateValue(b);
  if (ad !== null && bd !== null) return Math.sign(ad - bd);
  const as = toStringValue(a).trim();
  const bs = toStringValue(b).trim();
  return as < bs ? -1 : as > bs ? 1 : 0;
}

export interface FunctionContext {
  readonly now: number;
}

export type QueryFunction = (args: readonly ExprValue[], ctx: FunctionContext) => ExprValue;

const arg = (args: readonly ExprValue[], i: number): ExprValue => args[i] ?? null;
const numbersOf = (args: readonly ExprValue[]): number[] =>
  args.map((a) => toNumber(a)).filter((n): n is number => n !== null);

/**
 * The standard library. Names are matched case-insensitively. Extra functions
 * can be merged in by callers, which keeps the language open without edits here.
 */
export const BUILT_IN_FUNCTIONS: Readonly<Record<string, QueryFunction>> = {
  lower: (a) => toStringValue(arg(a, 0)).toLowerCase(),
  upper: (a) => toStringValue(arg(a, 0)).toUpperCase(),
  trim: (a) => toStringValue(arg(a, 0)).trim(),
  length: (a) => toStringValue(arg(a, 0)).length,
  concat: (a) => a.map(toStringValue).join(""),
  contains: (a) =>
    toStringValue(arg(a, 0)).toLowerCase().includes(toStringValue(arg(a, 1)).toLowerCase()),
  startswith: (a) =>
    toStringValue(arg(a, 0)).toLowerCase().startsWith(toStringValue(arg(a, 1)).toLowerCase()),
  endswith: (a) =>
    toStringValue(arg(a, 0)).toLowerCase().endsWith(toStringValue(arg(a, 1)).toLowerCase()),
  empty: (a) => toStringValue(arg(a, 0)).trim() === "",
  notempty: (a) => toStringValue(arg(a, 0)).trim() !== "",
  if: (a) => (toBoolean(arg(a, 0)) ? arg(a, 1) : arg(a, 2)),
  coalesce: (a) => {
    for (const v of a) if (toStringValue(v).trim() !== "") return v;
    return "";
  },
  number: (a) => toNumber(arg(a, 0)),
  abs: (a) => {
    const n = toNumber(arg(a, 0));
    return n === null ? null : Math.abs(n);
  },
  round: (a) => {
    const n = toNumber(arg(a, 0));
    if (n === null) return null;
    const digits = toNumber(arg(a, 1)) ?? 0;
    const factor = Math.pow(10, digits);
    return Math.round(n * factor) / factor;
  },
  min: (a) => {
    const ns = numbersOf(a);
    return ns.length > 0 ? Math.min(...ns) : null;
  },
  max: (a) => {
    const ns = numbersOf(a);
    return ns.length > 0 ? Math.max(...ns) : null;
  },
  year: (a) => {
    const ms = parseDateValue(arg(a, 0));
    return ms === null ? null : new Date(ms).getUTCFullYear();
  },
  month: (a) => {
    const ms = parseDateValue(arg(a, 0));
    return ms === null ? null : new Date(ms).getUTCMonth() + 1;
  },
  day: (a) => {
    const ms = parseDateValue(arg(a, 0));
    return ms === null ? null : new Date(ms).getUTCDate();
  },
  dayssince: (a, ctx) => {
    const ms = parseDateValue(arg(a, 0));
    return ms === null ? null : Math.floor((ctx.now - ms) / 86_400_000);
  },
  replace: (a) => {
    try {
      return toStringValue(arg(a, 0)).replace(
        new RegExp(toStringValue(arg(a, 1)), "g"),
        toStringValue(arg(a, 2)),
      );
    } catch {
      return toStringValue(arg(a, 0));
    }
  },
  regexmatch: (a) => {
    try {
      return new RegExp(toStringValue(arg(a, 1))).test(toStringValue(arg(a, 0)));
    } catch {
      return false;
    }
  },
};
