import type { Row } from "../model";

/**
 * Per-group row limits.
 *
 * A board grouped by a field with five thousand rows renders five thousand cards, and nobody has ever
 * read the five thousandth. Worse, the browser has to lay every one of them out before showing you the
 * first. Limiting each group to a handful, with an honest count of what is hidden and a way to see it,
 * costs nothing and fixes both problems.
 *
 * The count is always the *true* count, never the trimmed one: a group header that says "12" when there
 * are 500 is a lie, and lying about how much data someone has is the one thing a data tool must not do.
 */
export interface LimitedRows {
  /** The rows to draw. */
  readonly rows: readonly Row[];
  /** How many rows the group really has, regardless of what we drew. */
  readonly total: number;
  /** How many were withheld. Zero when everything is shown. */
  readonly hidden: number;
}

export function limitRows(rows: readonly Row[], limit: number, expanded = false): LimitedRows {
  const total = rows.length;
  if (expanded || limit <= 0 || total <= limit) return { rows, total, hidden: 0 };
  return { rows: rows.slice(0, limit), total, hidden: total - limit };
}

/** The label for the control that reveals the rest — plain about how many there are. */
export function moreLabel(hidden: number): string {
  return `Show ${hidden} more`;
}
