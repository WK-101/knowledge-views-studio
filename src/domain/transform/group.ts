import type { Row } from "../model";
import { getField } from "../fields";
import { compareComparable } from "../columns/column-type";
import type { FieldTypeResolver } from "./field-type";

export interface GroupSpec {
  readonly field: string;
  readonly direction?: "asc" | "desc";
}

export interface RowGroup {
  readonly key: string;
  readonly rows: Row[];
}

const EMPTY_GROUP_KEY = "(empty)";

/** Partition rows by a field's plain-text value, with groups sorted by the field. */
export function groupRows(
  rows: readonly Row[],
  spec: GroupSpec,
  resolver: FieldTypeResolver,
): RowGroup[] {
  const type = resolver.get(spec.field);
  const buckets = new Map<string, Row[]>();
  const order: string[] = [];

  for (const row of rows) {
    const key = type.toPlainText(getField(row, spec.field)) || EMPTY_GROUP_KEY;
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = [];
      buckets.set(key, bucket);
      order.push(key);
    }
    bucket.push(row);
  }

  const groups = order.map((key) => ({ key, rows: buckets.get(key) ?? [] }));
  groups.sort((a, b) => {
    const cmp = compareComparable(type.toComparable(a.key), type.toComparable(b.key));
    return spec.direction === "desc" ? -cmp : cmp;
  });
  return groups;
}

/** Total rows across every group. */
export function countGroupedRows(groups: readonly RowGroup[]): number {
  let n = 0;
  for (const g of groups) n += g.rows.length;
  return n;
}

/**
 * Cap a grouped result to at most `maxRows` rows in total.
 *
 * Grouping deliberately bypasses pagination (a page of groups is a confusing unit), which used to mean a
 * grouped view had *nothing* bounding it — no page, no cap, and, outside the virtualized table, no windowing
 * either. A grouped Gallery over a large vault would try to build a DOM node per row. This is the backstop:
 * groups are kept whole while the budget lasts, the group that straddles the limit is trimmed, and the rest
 * are dropped. Groups stay in their sorted order, so what you see is the start of the result, not a
 * scattered sample.
 */
export function capGroups(groups: readonly RowGroup[], maxRows: number): RowGroup[] {
  if (maxRows <= 0) return [...groups];
  const out: RowGroup[] = [];
  let budget = maxRows;
  for (const group of groups) {
    if (budget <= 0) break;
    if (group.rows.length <= budget) {
      out.push(group);
      budget -= group.rows.length;
    } else {
      out.push({ key: group.key, rows: group.rows.slice(0, budget) });
      budget = 0;
    }
  }
  return out;
}
