import { applySourceBindings } from "../extract/combine";
import type { Dataset, Row } from "../model";
import type { ColumnConfig } from "../columns/column-type";
import type { ColumnTypeRegistry } from "../columns/registry";
import { FieldTypeResolver } from "./field-type";
import { evaluateFilterGroup, type FilterGroup } from "./filter";
import { sortRows, type SortKey } from "./sort";
import { groupRows, type GroupSpec, type RowGroup } from "./group";
import { applyComputedColumns, type ComputedColumn } from "./compute";
import { paginate, type PageInfo, type PageSpec } from "./paginate";
import { searchRows } from "./search";
import { rowMatchesColumns, type ColumnMatchMode } from "./column-match";
import { compileExpression, type CompiledExpression } from "../query/index";
import { applyRollups, defaultRollupType, type RollupColumn } from "./rollup";

/**
 * A complete, declarative description of how to turn a Dataset into a view's
 * worth of rows. Every field is optional, so a bare `{}` is a valid identity
 * transform. The same spec shape will back the (future) settings UI.
 */
export interface TransformSpec {
  readonly columns?: readonly ColumnConfig[];
  readonly computed?: readonly ComputedColumn[];
  /** Derived columns that aggregate a field across the rows of linked notes. */
  readonly rollups?: readonly RollupColumn[];
  readonly filter?: FilterGroup | null;
  readonly advancedQuery?: string | null;
  readonly sort?: readonly SortKey[];
  readonly group?: GroupSpec | null;
  readonly page?: PageSpec | null;
  /** Transient free-text search applied after filtering (not persisted). */
  readonly search?: string;
  /** Header-matching strictness against `columns` (defaults to loose). */
  readonly columnMatch?: ColumnMatchMode;
}

export interface TransformResult {
  /** When grouped, this is every matching row (sorted); otherwise the page slice. */
  readonly rows: Row[];
  readonly groups: RowGroup[] | null;
  /** Total matching rows before pagination. */
  readonly total: number;
  /** Rows gathered from the source (after extraction, before filter/match) — lets an empty view
   *  distinguish "nothing found in scope" from "everything filtered out". */
  readonly gathered: number;
  /** Lowercased names of cell fields that hold a non-blank value in at least one matching row.
   *  Drives "hide empty columns"; computed over the full result, so it's page-independent. */
  readonly nonEmptyFields?: readonly string[];
  readonly page: PageInfo | null;
}

export interface TransformOptions {
  readonly registry: ColumnTypeRegistry;
  /** Injectable clock for deterministic `daysSince(...)` etc. (defaults to now). */
  readonly now?: number;
}

/**
 * Pipeline order: compute -> filter -> sort -> (group | paginate). Computed
 * columns run first so filters and sorts can reference them. Throws QueryError
 * if `advancedQuery` or a computed expression is invalid.
 */
export function runTransform(
  dataset: Dataset,
  spec: TransformSpec,
  options: TransformOptions,
): TransformResult {
  const now = options.now ?? Date.now();
  const computed = spec.computed ?? [];
  const gathered = dataset.length;
  const baseColumns = spec.columns ?? [];

  const rollups = spec.rollups ?? [];
  const effectiveColumns: ColumnConfig[] = [
    ...baseColumns,
    ...computed.map((c) => ({ name: c.name, type: c.type ?? "text" })),
    ...rollups.map((r) => ({ name: r.name, type: r.type ?? defaultRollupType(r.aggregate) })),
  ];
  const resolver = new FieldTypeResolver(options.registry, effectiveColumns);

  // Resolve source-bound columns first, so everything downstream — computed cells, rollups, filters,
  // sorts, search and the views — sees the value the bound source actually supplied.
  let rows: Row[] = applySourceBindings(dataset, baseColumns);

  // Compute derived cells, then rollups over the *full* dataset (before filtering),
  // so a rollup sees every related row and can itself be filtered/sorted on.
  rows = applyComputedColumns(rows, computed, now);
  rows = applyRollups(rows, rollups, resolver);

  if (spec.columnMatch && spec.columnMatch !== "loose" && baseColumns.length > 0) {
    const names = baseColumns.map((c) => c.name);
    rows = rows.filter((row) => rowMatchesColumns(row, names, spec.columnMatch ?? "loose"));
  }

  const advanced = (spec.advancedQuery ?? "").trim();
  const compiled: CompiledExpression | null = advanced ? compileExpression(advanced) : null;
  const filterGroup = spec.filter ?? null;

  if (filterGroup || compiled) {
    rows = rows.filter((row) => {
      if (filterGroup && !evaluateFilterGroup(row, filterGroup, resolver)) return false;
      if (compiled && !compiled.test(row, now)) return false;
      return true;
    });
  }

  if (spec.search && spec.search.trim() !== "") {
    rows = searchRows(rows, spec.search);
  }

  if (spec.sort && spec.sort.length > 0) {
    rows = sortRows(rows, spec.sort, resolver);
  }

  const total = rows.length;

  // Fields with a non-blank value somewhere in the full result (page-independent) — for hide-empty.
  const nonEmpty = new Set<string>();
  for (const row of rows) {
    for (const key in row.cells) {
      const value = row.cells[key];
      if (value != null && String(value).trim() !== "") nonEmpty.add(key.toLowerCase());
    }
  }
  const nonEmptyFields = [...nonEmpty];

  if (spec.group) {
    return { rows, groups: groupRows(rows, spec.group, resolver), total, gathered, nonEmptyFields, page: null };
  }

  if (spec.page) {
    const { rows: pageRows, info } = paginate(rows, spec.page);
    return { rows: pageRows, groups: null, total, gathered, nonEmptyFields, page: info };
  }

  return { rows, groups: null, total, gathered, nonEmptyFields, page: null };
}
