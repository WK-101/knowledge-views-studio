import { getField, type Row } from "../../domain/index";
import { extractImageEmbeds } from "../../util/markdown";

export interface GalleryItem<C extends { name: string }> {
  readonly embed: string;
  readonly row: Row;
  readonly column: C;
}

/**
 * Collect every individual image embed across the given columns. A single cell can hold multiple
 * embeds (both `![[internal]]` and `![](external)`), and a row can carry images in several columns —
 * each embed becomes its own item. Stops once `limit` items are gathered.
 */
export function collectGalleryImages<C extends { name: string }>(
  rows: readonly Row[],
  columns: readonly C[],
  limit: number,
): GalleryItem<C>[] {
  const items: GalleryItem<C>[] = [];
  for (const row of rows) {
    for (const column of columns) {
      const value = getField(row, column.name);
      if (value.trim() === "") continue;
      for (const embed of extractImageEmbeds(value)) {
        items.push({ embed, row, column });
        if (items.length >= limit) return items;
      }
    }
  }
  return items;
}
