import {
  BUILT_IN_COLUMN_TYPES,
  VIRTUAL_FIELDS,
  type ColumnConfig,
  type ColumnTypeRegistry,
  type EnumOption,
  type FilterOperator,
  type Row,
} from "../domain/index";
import { createProfile, type Profile } from "../services/index";
import { resolveColumns } from "../views/view-model";

export { BUILT_IN_COLUMN_TYPES };

/** Discover columns from rows with inferred types (reuses the view discovery). */
export function suggestColumns(rows: readonly Row[]): ColumnConfig[] {
  return resolveColumns(createProfile(), rows).map((c) => ({
    name: c.name,
    type: c.typeId,
  }));
}

/** Append discovered columns that are not already configured (case-insensitive). */
export function mergeDiscovered(
  existing: readonly ColumnConfig[],
  discovered: readonly ColumnConfig[],
): ColumnConfig[] {
  const have = new Set(existing.map((c) => c.name.trim().toLowerCase()));
  const additions = discovered.filter((c) => !have.has(c.name.trim().toLowerCase()));
  return [...existing, ...additions];
}

export interface FieldOption {
  readonly name: string;
  readonly typeId: string;
}

const VIRTUAL_TYPES = new Map<string, string>([
  ["note", "link"],
  ["path", "text"],
  ["folder", "text"],
  ["created", "date"],
  ["modified", "date"],
  ["source", "text"],
]);

/** Addressable fields for the filter/sort builders: configured columns + virtual fields. */
export function fieldOptions(columns: readonly ColumnConfig[]): FieldOption[] {
  const seen = new Set<string>();
  const out: FieldOption[] = [];
  for (const column of columns) {
    const key = column.name.trim().toLowerCase();
    if (column.name.trim() !== "" && !seen.has(key)) {
      seen.add(key);
      out.push({ name: column.name, typeId: column.type });
    }
  }
  for (const field of VIRTUAL_FIELDS) {
    if (!seen.has(field)) {
      seen.add(field);
      out.push({ name: field, typeId: VIRTUAL_TYPES.get(field) ?? "text" });
    }
  }
  return out;
}

export function operatorsForType(
  typeId: string,
  registry: ColumnTypeRegistry,
): readonly FilterOperator[] {
  return registry.get(typeId).operators;
}

export function parseOptions(value: string): EnumOption[] {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "")
    .map((v) => ({ value: v }));
}

export function formatOptions(options: readonly EnumOption[] | undefined): string {
  return (options ?? []).map((o) => o.value).join(", ");
}

/** Immutable move of an array item; out-of-range requests are a no-op. */
export function moveItem<T>(items: readonly T[], from: number, to: number): T[] {
  const copy = [...items];
  if (from < 0 || from >= copy.length || to < 0 || to >= copy.length) return copy;
  const [item] = copy.splice(from, 1);
  if (item !== undefined) copy.splice(to, 0, item);
  return copy;
}

export type ProfileJsonResult =
  | { readonly ok: true; readonly profile: Profile }
  | { readonly ok: false; readonly error: string };

/** Validate pasted JSON and normalize it into a Profile (with a fresh id). */
export function validateProfileJson(json: string): ProfileJsonResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (error) {
    return { ok: false, error: `Invalid JSON: ${error instanceof Error ? error.message : String(error)}` };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { ok: false, error: "Expected a JSON object describing a view." };
  }
  return { ok: true, profile: createProfile({ ...(parsed as Partial<Profile>), id: undefined }) };
}
