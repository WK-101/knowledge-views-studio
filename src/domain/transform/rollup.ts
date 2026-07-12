import type { Row } from "../model";
import { getField } from "../fields";
import { parseWikiLinks } from "../columns/types/link";
import { parseNumber } from "../columns/types/number";
import { compareComparable } from "../columns/column-type";
import type { FieldTypeResolver } from "./field-type";

/** How a rollup reduces the target field across the related rows. */
export type RollupAggregate =
  | "count" // number of related rows
  | "count-unique" // number of distinct target values
  | "sum"
  | "avg"
  | "min"
  | "max"
  | "list" // all target values, joined
  | "unique"; // distinct target values, joined

/** How a relation link is matched to source notes. */
export type RollupMatch = "either" | "name" | "path";

/**
 * A derived column that follows a relation field's `[[links]]` to other notes and
 * aggregates one of their fields. Rollups are computed over the *full* dataset
 * (before the view's own filter), matching how Notion rollups see every related
 * record regardless of the current view.
 */
export interface RollupColumn {
  readonly name: string;
  /** Field holding the `[[note]]` links to follow. */
  readonly relationField: string;
  /** Field to aggregate from the related rows (ignored for `count`). */
  readonly targetField: string;
  readonly aggregate: RollupAggregate;
  /** Result column type id (defaults to number for numeric aggregates, else text). */
  readonly type?: string;
  /** Link-matching strategy (defaults to matching either the note name or its path). */
  readonly matchBy?: RollupMatch;
  /** If set, the rolled-up value is written back into this source table column on demand. */
  readonly materializeTo?: string;
}

/** The column type a rollup produces when the profile does not override it. */
export function defaultRollupType(aggregate: RollupAggregate): string {
  switch (aggregate) {
    case "count":
    case "count-unique":
    case "sum":
    case "avg":
      return "number";
    case "min":
    case "max":
    case "list":
    case "unique":
      return "text";
  }
}

const normalizeTarget = (target: string): string => target.trim().replace(/\.md$/i, "").toLowerCase();

interface NoteIndex {
  readonly byName: Map<string, Row[]>;
  readonly byPath: Map<string, Row[]>;
}

/** Index every row by its source note's name and path, so links resolve in O(1). */
export function buildNoteIndex(rows: readonly Row[]): NoteIndex {
  const byName = new Map<string, Row[]>();
  const byPath = new Map<string, Row[]>();
  const add = (map: Map<string, Row[]>, key: string, row: Row): void => {
    const bucket = map.get(key);
    if (bucket) bucket.push(row);
    else map.set(key, [row]);
  };
  for (const row of rows) {
    add(byName, row.file.fileName.toLowerCase(), row);
    add(byPath, normalizeTarget(row.file.filePath), row);
  }
  return { byName, byPath };
}

/** Rows belonging to the notes a relation cell points to (de-duplicated). */
function relatedRows(raw: string, index: NoteIndex, match: RollupMatch): Row[] {
  const seen = new Set<Row>();
  const out: Row[] = [];
  for (const link of parseWikiLinks(raw)) {
    const key = normalizeTarget(link.target);
    const buckets: Row[][] = [];
    if (match !== "path") buckets.push(index.byName.get(key) ?? []);
    if (match !== "name") buckets.push(index.byPath.get(key) ?? []);
    for (const bucket of buckets) {
      for (const row of bucket) {
        if (!seen.has(row)) {
          seen.add(row);
          out.push(row);
        }
      }
    }
  }
  return out;
}

function formatNumber(n: number): string {
  if (!Number.isFinite(n)) return "";
  // Trim floating-point noise from averages without imposing a hard precision.
  return String(Math.round(n * 1e6) / 1e6);
}

function aggregate(
  rollup: RollupColumn,
  related: readonly Row[],
  resolver: FieldTypeResolver,
): string {
  if (rollup.aggregate === "count") return String(related.length);

  const values = related.map((row) => getField(row, rollup.targetField));

  switch (rollup.aggregate) {
    case "count-unique":
      return String(new Set(values.map((v) => v.trim()).filter((v) => v !== "")).size);
    case "list":
      return values.filter((v) => v.trim() !== "").join(", ");
    case "unique": {
      const seen = new Set<string>();
      const out: string[] = [];
      for (const v of values) {
        const t = v.trim();
        if (t !== "" && !seen.has(t)) {
          seen.add(t);
          out.push(t);
        }
      }
      return out.join(", ");
    }
    case "sum":
    case "avg": {
      const nums = values.map(parseNumber).filter((n): n is number => n !== null);
      if (nums.length === 0) return "";
      const total = nums.reduce((a, b) => a + b, 0);
      return formatNumber(rollup.aggregate === "sum" ? total : total / nums.length);
    }
    case "min":
    case "max": {
      const type = resolver.get(rollup.targetField);
      let best: { row: string; cmp: ReturnType<typeof type.toComparable> } | null = null;
      for (const v of values) {
        if (v.trim() === "") continue;
        const cmp = type.toComparable(v);
        if (best === null) {
          best = { row: v, cmp };
          continue;
        }
        const order = compareComparable(cmp, best.cmp);
        if ((rollup.aggregate === "min" && order < 0) || (rollup.aggregate === "max" && order > 0)) {
          best = { row: v, cmp };
        }
      }
      return best?.row ?? "";
    }
  }
}

/**
 * Evaluate every rollup for every row, writing the result into a new cell. Rows
 * are rebuilt immutably; an empty rollup list is a zero-copy pass-through.
 */
export function applyRollups(
  rows: readonly Row[],
  rollups: readonly RollupColumn[],
  resolver: FieldTypeResolver,
): Row[] {
  if (rollups.length === 0) return rows as Row[];
  const index = buildNoteIndex(rows);
  return rows.map((row) => {
    const cells: Record<string, string> = { ...row.cells };
    for (const rollup of rollups) {
      const related = relatedRows(getField(row, rollup.relationField), index, rollup.matchBy ?? "either");
      cells[rollup.name] = aggregate(rollup, related, resolver);
    }
    return { ...row, cells };
  });
}
