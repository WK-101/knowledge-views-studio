import type { Row } from "../model";

export interface PageSpec {
  readonly size: number;
  readonly index: number;
}

export interface PageInfo {
  readonly size: number;
  readonly index: number;
  readonly count: number;
}

/** Clamp the page request to valid bounds and slice out that page of rows. */
export function paginate(rows: readonly Row[], page: PageSpec): { rows: Row[]; info: PageInfo } {
  const size = Math.max(1, Math.floor(page.size));
  const count = Math.max(1, Math.ceil(rows.length / size));
  const index = Math.min(Math.max(0, Math.floor(page.index)), count - 1);
  const start = index * size;
  return { rows: rows.slice(start, start + size), info: { size, index, count } };
}
