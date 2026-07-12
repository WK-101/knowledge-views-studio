import type { Row } from "../model";
import { getField } from "../fields";
import { compareComparable, type Comparable } from "../columns/column-type";
import type { FieldTypeResolver } from "./field-type";

export interface SortKey {
  readonly field: string;
  readonly direction: "asc" | "desc";
}

/** Stable multi-key sort using each field's column type for comparison. */
export function sortRows(
  rows: readonly Row[],
  keys: readonly SortKey[],
  resolver: FieldTypeResolver,
): Row[] {
  if (keys.length === 0) return [...rows];

  // Decorate: resolve each key's type once, and coerce every row's comparable
  // values a single time up front. A naive comparator would call toComparable()
  // O(n log n) times per key (re-parsing dates/numbers on every comparison); this
  // makes it O(n) coercions plus cheap comparisons — a large win on big datasets.
  const types = keys.map((key) => resolver.get(key.field));
  const decorated = rows.map((row, index) => {
    const values = new Array<Comparable>(keys.length);
    for (let k = 0; k < keys.length; k++) {
      values[k] = types[k]!.toComparable(getField(row, keys[k]!.field));
    }
    return { row, index, values };
  });

  decorated.sort((a, b) => {
    for (let k = 0; k < keys.length; k++) {
      const cmp = compareComparable(a.values[k]!, b.values[k]!);
      if (cmp !== 0) return keys[k]!.direction === "desc" ? -cmp : cmp;
    }
    return a.index - b.index; // stable tiebreak
  });

  return decorated.map((entry) => entry.row);
}
