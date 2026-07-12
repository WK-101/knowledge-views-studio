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
