import { getField, type Row } from "../../domain/index";

export interface KanbanColumn {
  readonly key: string;
  readonly label: string;
  readonly rows: Row[];
}

export interface KanbanBoard {
  readonly field: string;
  readonly columns: KanbanColumn[];
}

export interface KanbanOptions {
  /** Explicit column order (e.g. from a select column's options); empty columns still show. */
  readonly order?: readonly string[];
  readonly emptyLabel?: string;
}

const EMPTY_KEY = "";

/**
 * Bucket rows into board columns by a field's value. Columns from `order` appear
 * first (even when empty), then any other values in first-seen order, then a
 * trailing column for rows with no value.
 */
export function buildKanbanBoard(rows: readonly Row[], field: string, options: KanbanOptions = {}): KanbanBoard {
  const emptyLabel = options.emptyLabel ?? "(none)";
  const buckets = new Map<string, Row[]>();
  const seen: string[] = [];
  const ensure = (key: string): Row[] => {
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = [];
      buckets.set(key, bucket);
      seen.push(key);
    }
    return bucket;
  };

  const provided = (options.order ?? []).map((k) => k.trim());
  for (const key of provided) ensure(key);
  for (const row of rows) ensure(getField(row, field).trim()).push(row);

  const providedSet = new Set(provided);
  const discovered = seen.filter((k) => !providedSet.has(k) && k !== EMPTY_KEY);
  const keys = [...provided.filter((k) => k !== EMPTY_KEY), ...discovered];

  const columns: KanbanColumn[] = keys.map((key) => ({
    key,
    label: key,
    rows: buckets.get(key) ?? [],
  }));
  if (buckets.has(EMPTY_KEY)) {
    columns.push({ key: EMPTY_KEY, label: emptyLabel, rows: buckets.get(EMPTY_KEY) ?? [] });
  }
  return { field, columns };
}
