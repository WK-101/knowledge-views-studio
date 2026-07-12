/**
 * Pure geometry for variable-height row virtualization.
 *
 * The table view measures the rows it actually renders and feeds their heights
 * back here. These helpers turn a list of (possibly estimated) row heights into
 * cumulative offsets, decide which rows fall inside the viewport, and report how
 * far to shift the scroll position when an earlier estimate is corrected — which
 * is what keeps the visible rows from jumping as real heights are learned.
 */

/** prefix[i] = combined height of rows [0, i); prefix[n] is the full height. */
export function buildPrefix(heights: readonly number[]): number[] {
  const prefix = new Array<number>(heights.length + 1);
  prefix[0] = 0;
  for (let i = 0; i < heights.length; i++) {
    prefix[i + 1] = (prefix[i] ?? 0) + Math.max(0, heights[i] ?? 0);
  }
  return prefix;
}

/** Total content height (0 for an empty prefix). */
export function totalHeight(prefix: readonly number[]): number {
  return prefix.length > 0 ? (prefix[prefix.length - 1] ?? 0) : 0;
}

/**
 * Index of the row that contains `offset`: the largest i with prefix[i] <= offset,
 * clamped to a valid row index. Runs in O(log n) via binary search.
 */
export function findRowAt(prefix: readonly number[], offset: number): number {
  const target = Math.max(0, offset);
  let lo = 0;
  let hi = prefix.length - 1;
  let ans = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if ((prefix[mid] ?? 0) <= target) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return Math.min(ans, Math.max(0, prefix.length - 2));
}

export interface RowWindow {
  readonly start: number;
  readonly end: number;
}

/**
 * The half-open row range [start, end) to render for a viewport of height
 * `viewport` scrolled to `scrollTop`, padded by `overscan` rows on each side.
 */
export function computeWindow(
  prefix: readonly number[],
  scrollTop: number,
  viewport: number,
  overscan: number,
): RowWindow {
  const n = Math.max(0, prefix.length - 1);
  if (n === 0) return { start: 0, end: 0 };
  const first = findRowAt(prefix, scrollTop);
  const start = Math.max(0, first - overscan);
  const bottom = scrollTop + Math.max(0, viewport);
  let end = first;
  while (end < n && (prefix[end] ?? 0) < bottom) end++;
  end = Math.min(n, end + overscan);
  return { start, end };
}

/**
 * How much to add to scrollTop so the row at `anchorIndex` stays visually fixed
 * after heights change from `oldPrefix` to `newPrefix`.
 */
export function anchorShift(
  oldPrefix: readonly number[],
  newPrefix: readonly number[],
  anchorIndex: number,
): number {
  return (newPrefix[anchorIndex] ?? 0) - (oldPrefix[anchorIndex] ?? 0);
}
