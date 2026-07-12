import type { Row } from "../model";
import { compileExpression, toStringValue } from "../query/index";

export interface ComputedColumn {
  readonly name: string;
  readonly expression: string;
  /** Column type id for the derived value (defaults to text). */
  readonly type?: string;
  /** If set, the computed value is written back into this source table column on demand. */
  readonly materializeTo?: string;
}

/**
 * Derive new columns from expressions, in order, so a later computed column may
 * reference an earlier one. Rows are rebuilt immutably. Throws QueryError if any
 * expression is invalid (callers validate first via the query module).
 */
export function applyComputedColumns(
  rows: readonly Row[],
  computed: readonly ComputedColumn[],
  now: number,
): Row[] {
  // No computed columns: return the input as-is. Downstream stages (filter/sort/
  // paginate) all build new arrays, and rows are treated immutably, so copying the
  // entire dataset here would just be wasted O(n) allocation on every query.
  if (computed.length === 0) return rows as Row[];
  const compiled = computed.map((c) => ({ name: c.name, fn: compileExpression(c.expression) }));

  return rows.map((row) => {
    let cells: Record<string, string> = { ...row.cells };
    let current: Row = { ...row, cells };
    for (const { name, fn } of compiled) {
      cells = { ...cells, [name]: toStringValue(fn.evaluate(current, now)) };
      current = { ...row, cells };
    }
    return current;
  });
}
