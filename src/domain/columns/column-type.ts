import type { FieldRole } from "./field-role";
import type { FilterOperator } from "./operators";

export type ComparableKind = "number" | "string";

/** A value reduced to something sortable / comparable. */
export interface Comparable {
  readonly kind: ComparableKind;
  readonly value: number | string;
}

export interface EnumOption {
  readonly value: string;
  readonly label?: string;
  readonly color?: string;
}

/**
 * Per-column user configuration inside a profile. This single shape replaces
 * the legacy HeaderDefinition + headerRoles + targetHeaders + fieldKind sprawl.
 */
export interface ColumnConfig {
  /** Source header name to match (case-insensitive). */
  readonly name: string;
  /** Column type id; resolved against the ColumnTypeRegistry. */
  readonly type: string;
  readonly label?: string;
  readonly width?: number;
  readonly visible?: boolean;
  readonly editable?: boolean;
  readonly options?: readonly EnumOption[];
  /** Optional semantic role (title/status/date/tags/priority) for smarter view defaults. */
  readonly role?: FieldRole;
  /** Value pre-filled into this column when a new row is added. Supports {{today}}/{{now}}/{{time}}. */
  readonly defaultValue?: string;
  /**
   * Bind this column to one row source (an extractor id). Its header is then matched only against that
   * source, which disambiguates a header that several sources define. Unset = any source (the default).
   */
  readonly source?: string;
}

/**
 * A column type owns the *pure* behaviour of a kind of column: emptiness,
 * comparison/sorting, plain-text projection, validation, and which operators it
 * offers. Rendering and inline-editing (DOM concerns) are layered on top later
 * and keyed by `id`, so this stays Obsidian-free and unit-testable.
 */
export interface ColumnType {
  readonly id: string;
  readonly label: string;
  readonly operators: readonly FilterOperator[];
  isEmpty(raw: string): boolean;
  toComparable(raw: string): Comparable;
  toPlainText(raw: string): string;
  /** Return an error message, or null when the value is acceptable. */
  validate(raw: string, config: ColumnConfig): string | null;
}

/** Three-way comparison between two reduced values (numbers sort numerically). */
export function compareComparable(a: Comparable, b: Comparable): number {
  if (a.kind === "number" && b.kind === "number") {
    return (a.value as number) - (b.value as number);
  }
  return String(a.value).localeCompare(String(b.value), undefined, {
    numeric: true,
    sensitivity: "base",
  });
}
