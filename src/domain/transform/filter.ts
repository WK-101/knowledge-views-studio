import type { Row } from "../model";
import { getField } from "../fields";
import type { ColumnType } from "../columns/column-type";
import { compareComparable } from "../columns/column-type";
import type { FilterOperator } from "../columns/operators";
import type { FieldTypeResolver } from "./field-type";

export interface FilterCondition {
  readonly field: string;
  readonly operator: FilterOperator;
  readonly value?: string;
}

export type FilterCombinator = "and" | "or" | "none";

export interface FilterGroup {
  readonly combinator: FilterCombinator;
  readonly conditions: readonly FilterCondition[];
  readonly groups: readonly FilterGroup[];
}

/**
 * The single operator evaluator. All 13 operators are handled here in one place,
 * driven by the column type's own semantics. The legacy grid implemented only 6
 * of 13 (silently matching-all for the rest, bug 3.3) and the settings UI wrote
 * deprecated `eq`/`neq` names (bug 3.4); both classes of defect cannot recur
 * because there is exactly one code path and one operator vocabulary.
 */
export function applyOperator(
  type: ColumnType,
  rawValue: string,
  operator: FilterOperator,
  compareValue: string,
): boolean {
  switch (operator) {
    case "is-empty":
      return type.isEmpty(rawValue);
    case "is-not-empty":
      return !type.isEmpty(rawValue);
    case "regex":
      try {
        return new RegExp(compareValue, "i").test(type.toPlainText(rawValue));
      } catch {
        return false;
      }
    case "contains":
    case "not-contains":
    case "starts-with":
    case "ends-with": {
      const haystack = type.toPlainText(rawValue).toLowerCase();
      const needle = type.toPlainText(compareValue).toLowerCase();
      if (operator === "contains") return haystack.includes(needle);
      if (operator === "not-contains") return !haystack.includes(needle);
      if (operator === "starts-with") return haystack.startsWith(needle);
      return haystack.endsWith(needle);
    }
    case "equals":
    case "not-equals":
    case "gt":
    case "gte":
    case "lt":
    case "lte": {
      const cmp = compareComparable(type.toComparable(rawValue), type.toComparable(compareValue));
      switch (operator) {
        case "equals":
          return cmp === 0;
        case "not-equals":
          return cmp !== 0;
        case "gt":
          return cmp > 0;
        case "gte":
          return cmp >= 0;
        case "lt":
          return cmp < 0;
        case "lte":
          return cmp <= 0;
      }
    }
  }
}

export function evaluateCondition(
  row: Row,
  condition: FilterCondition,
  resolver: FieldTypeResolver,
): boolean {
  const type = resolver.get(condition.field);
  const rawValue = getField(row, condition.field);
  return applyOperator(type, rawValue, condition.operator, condition.value ?? "");
}

/** Evaluate a (possibly nested) filter group. An empty group matches everything. */
export function evaluateFilterGroup(
  row: Row,
  group: FilterGroup,
  resolver: FieldTypeResolver,
): boolean {
  const results: boolean[] = [];
  for (const condition of group.conditions) {
    results.push(evaluateCondition(row, condition, resolver));
  }
  for (const nested of group.groups) {
    results.push(evaluateFilterGroup(row, nested, resolver));
  }
  if (results.length === 0) return true;
  if (group.combinator === "and") return results.every(Boolean);
  if (group.combinator === "or") return results.some(Boolean);
  return !results.some(Boolean); // "none": the row passes when none of the children match
}
