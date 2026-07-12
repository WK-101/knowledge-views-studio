import { sourceLabel } from "./extract/combine";
import type { Row } from "./model";

/**
 * Virtual fields are derived from a row's file metadata rather than its table
 * cells. They are addressable in filters, sorts, and views just like data
 * columns, via a single case-insensitive accessor.
 */
export const VIRTUAL_FIELDS = ["note", "path", "folder", "created", "modified"] as const;
export type VirtualField = (typeof VIRTUAL_FIELDS)[number];

const VIRTUAL_SET: ReadonlySet<string> = new Set(VIRTUAL_FIELDS);

export function isVirtualField(name: string): boolean {
  return VIRTUAL_SET.has(name.trim().toLowerCase());
}

function toIsoDate(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "";
  return new Date(ms).toISOString().slice(0, 10);
}

/**
 * Resolve any field name — virtual or data column, case-insensitive — to its
 * raw string value. This single function replaces the legacy code's three
 * divergent, defensively-coded lookup paths.
 */
export function getField(row: Row, name: string): string {
  const key = name.trim().toLowerCase();
  switch (key) {
    case "note":
      return row.file.fileName;
    case "path":
      return row.file.filePath;
    case "folder":
      return row.file.folderPath;
    case "created":
      return toIsoDate(row.file.createdMs);
    case "modified":
      return toIsoDate(row.file.modifiedMs);
    case "source": {
      // `source` is a *fallback* virtual field, not an override: if the data itself has a column called
      // "source", that value wins. Only when no such column exists does it report which extractor
      // produced the row. Real data is never shadowed.
      for (const [col, value] of Object.entries(row.cells)) {
        if (col.trim().toLowerCase() === "source") return value ?? "";
      }
      return sourceLabel(row.provenance.extractor);
    }
    default:
      break;
  }
  for (const [col, value] of Object.entries(row.cells)) {
    if (col.trim().toLowerCase() === key) return value ?? "";
  }
  return "";
}
