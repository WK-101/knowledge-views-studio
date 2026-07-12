import type { ColumnConfig } from "./column-type";

const pad = (n: number): string => (n < 10 ? `0${n}` : String(n));
const isoDate = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const isoDateTime = (d: Date): string => `${isoDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
const timeStr = (d: Date): string => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

/**
 * Resolve a column default's dynamic tokens against the current time. Supported tokens (case- and
 * space-insensitive): {{today}} → YYYY-MM-DD, {{now}} → YYYY-MM-DD HH:mm, {{time}} → HH:mm. Anything
 * else is treated as a literal default value.
 */
export function resolveDefaultTokens(raw: string, now: Date = new Date()): string {
  return raw
    .replace(/\{\{\s*today\s*\}\}/gi, isoDate(now))
    .replace(/\{\{\s*now\s*\}\}/gi, isoDateTime(now))
    .replace(/\{\{\s*time\s*\}\}/gi, timeStr(now));
}

/**
 * Build the initial cell values for a new row from the columns' configured defaults, keyed by column
 * name (which matches the source table header). Columns without a default are omitted. This is the
 * automation behind "add row": instead of an empty row you edit cell-by-cell, common fields
 * (status, priority, a created date via {{today}}) arrive pre-filled.
 */
export function resolveRowDefaults(columns: readonly ColumnConfig[], now: Date = new Date()): Record<string, string> {
  const values: Record<string, string> = {};
  for (const column of columns) {
    const raw = column.defaultValue;
    if (raw != null && raw.trim() !== "") values[column.name] = resolveDefaultTokens(raw, now);
  }
  return values;
}
