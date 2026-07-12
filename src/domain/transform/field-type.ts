import type { ColumnConfig, ColumnType } from "../columns/column-type";
import type { ColumnTypeRegistry } from "../columns/registry";

/** Default types for the virtual fields exposed by every dataset. */
const VIRTUAL_TYPES: ReadonlyMap<string, string> = new Map([
  ["note", "link"],
  ["path", "text"],
  ["folder", "text"],
  ["created", "date"],
  ["modified", "date"],
]);

/**
 * Resolves a field name (data column or virtual field) to its ColumnType, so
 * filters, sorts, and grouping all share one notion of a column's behaviour.
 */
export class FieldTypeResolver {
  private readonly byName = new Map<string, string>();

  constructor(
    private readonly registry: ColumnTypeRegistry,
    columns: readonly ColumnConfig[],
  ) {
    for (const column of columns) {
      this.byName.set(column.name.trim().toLowerCase(), column.type);
    }
  }

  typeIdFor(field: string): string {
    const key = field.trim().toLowerCase();
    return this.byName.get(key) ?? VIRTUAL_TYPES.get(key) ?? "text";
  }

  get(field: string): ColumnType {
    return this.registry.get(this.typeIdFor(field));
  }
}
