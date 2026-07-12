/**
 * The canonical filter operator set. There is exactly one list, used by both
 * the (future) settings UI and the transform engine. The legacy `eq` / `neq`
 * aliases are gone from the model; a migration maps old saved data onto these.
 */
export type FilterOperator =
  | "contains"
  | "not-contains"
  | "equals"
  | "not-equals"
  | "starts-with"
  | "ends-with"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "is-empty"
  | "is-not-empty"
  | "regex";

export const OPERATOR_LABELS: Readonly<Record<FilterOperator, string>> = {
  contains: "contains",
  "not-contains": "does not contain",
  equals: "equals",
  "not-equals": "does not equal",
  "starts-with": "starts with",
  "ends-with": "ends with",
  gt: "greater than",
  gte: "greater than or equal",
  lt: "less than",
  lte: "less than or equal",
  "is-empty": "is empty",
  "is-not-empty": "is not empty",
  regex: "matches regex",
};

/** Operators that compare against no value (the value input can be hidden). */
export const NO_VALUE_OPERATORS: ReadonlySet<FilterOperator> = new Set([
  "is-empty",
  "is-not-empty",
]);

/** Map a legacy operator string onto a canonical one (used by data migration). */
export function canonicalizeOperator(op: string): FilterOperator {
  const v = String(op ?? "").trim().toLowerCase();
  switch (v) {
    case "eq":
      return "equals";
    case "neq":
    case "ne":
      return "not-equals";
    case "gt":
    case "gte":
    case "lt":
    case "lte":
    case "contains":
    case "not-contains":
    case "equals":
    case "not-equals":
    case "starts-with":
    case "ends-with":
    case "is-empty":
    case "is-not-empty":
    case "regex":
      return v;
    default:
      return "contains";
  }
}
