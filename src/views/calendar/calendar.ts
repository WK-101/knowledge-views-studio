import { getField, type Row } from "../../domain/index";
import { parseDateMs } from "../../domain/columns/types/date";

export interface CalendarDay {
  readonly iso: string;
  readonly day: number;
  readonly inMonth: boolean;
  readonly rows: Row[];
}

export interface CalendarMonth {
  readonly year: number;
  readonly month: number; // 0-based
  readonly weeks: CalendarDay[][];
}

export interface CalendarOptions {
  readonly weekStartsOn?: 0 | 1; // 0 Sunday, 1 Monday
}

const DAY_MS = 86_400_000;
const pad = (n: number): string => String(n).padStart(2, "0");

function isoOf(ms: number): string {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/**
 * Lay rows onto a 6×7 month grid by a date field. All arithmetic is in UTC so a
 * date-only value lands on the same day regardless of the viewer's timezone.
 */
export function buildCalendarMonth(
  rows: readonly Row[],
  dateField: string,
  year: number,
  month: number,
  options: CalendarOptions = {},
): CalendarMonth {
  const weekStartsOn = options.weekStartsOn ?? 0;
  const buckets = new Map<string, Row[]>();
  for (const row of rows) {
    const ms = parseDateMs(getField(row, dateField));
    if (ms === null) continue;
    const iso = isoOf(ms);
    const bucket = buckets.get(iso);
    if (bucket) bucket.push(row);
    else buckets.set(iso, [row]);
  }

  const firstWeekday = new Date(Date.UTC(year, month, 1)).getUTCDay();
  const offset = weekStartsOn === 1 ? (firstWeekday + 6) % 7 : firstWeekday;
  const start = Date.UTC(year, month, 1 - offset);

  const weeks: CalendarDay[][] = [];
  for (let w = 0; w < 6; w++) {
    const days: CalendarDay[] = [];
    for (let d = 0; d < 7; d++) {
      const ms = start + (w * 7 + d) * DAY_MS;
      const date = new Date(ms);
      const iso = isoOf(ms);
      days.push({
        iso,
        day: date.getUTCDate(),
        inMonth: date.getUTCMonth() === month,
        rows: buckets.get(iso) ?? [],
      });
    }
    weeks.push(days);
  }
  return { year, month, weeks };
}
