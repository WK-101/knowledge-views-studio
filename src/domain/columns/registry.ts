import type { ColumnType } from "./column-type";

/**
 * Registry of column types. Constructed with a guaranteed fallback (text) so
 * `get()` is always total — an unknown type id can never crash a render.
 */
export class ColumnTypeRegistry {
  private readonly types = new Map<string, ColumnType>();
  private readonly fallback: ColumnType;

  constructor(fallback: ColumnType) {
    this.fallback = fallback;
    this.register(fallback);
  }

  register(type: ColumnType): this {
    this.types.set(type.id, type);
    return this;
  }

  get(id: string | undefined): ColumnType {
    if (id) {
      const found = this.types.get(id);
      if (found) return found;
    }
    return this.fallback;
  }

  has(id: string): boolean {
    return this.types.has(id);
  }

  all(): ColumnType[] {
    return [...this.types.values()];
  }
}
