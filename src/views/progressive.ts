import type { Component } from "obsidian";

/**
 * Progressive rendering for the card-shaped layouts (Cards, Gallery).
 *
 * The table virtualizes with measured heights and a scroll window, which works because one row is one item
 * of known-ish height. Cards don't fit that model: they sit in an `auto-fill` CSS grid, so the number of
 * columns changes with the pane width, and their heights genuinely vary (a card only draws the fields that
 * aren't empty). Measured windowing there means tracking grid rows across resizes and correcting estimates —
 * a lot of machinery whose failure mode is visible scroll jumping.
 *
 * The cost that actually hurts is the *first* paint: building hundreds of cards, each running cell renderers
 * and markdown, blocks the main thread before anything appears. So instead of a moving window we render in
 * chunks and grow on demand — a first chunk immediately, then another whenever a sentinel below the grid
 * comes near the viewport. Scrolling stays smooth because nothing is ever torn down or re-measured.
 *
 * The trade-off, stated plainly: the DOM grows as you scroll rather than staying fixed. That's bounded by the
 * row cap (`maxRows`), so the worst case is the cap rather than the whole dataset, and reaching it requires
 * scrolling through everything. If measurements ever show the grown DOM is the bottleneck, this is the seam
 * where true windowing would go.
 */

/** Items rendered per chunk. Big enough to fill a tall pane, small enough to paint fast. */
export const DEFAULT_CHUNK = 48;

/** Whether a list is long enough that chunking is worth the sentinel. */
export function shouldChunk(total: number, chunkSize: number): boolean {
  return chunkSize > 0 && total > chunkSize;
}

/** How many items should be rendered after the next chunk is drawn. */
export function nextChunkEnd(rendered: number, total: number, chunkSize: number): number {
  if (chunkSize <= 0) return total;
  return Math.min(total, Math.max(rendered, 0) + chunkSize);
}

export interface ProgressiveOptions<T> {
  readonly items: readonly T[];
  /** Draws one item. Called in order, exactly once per item. */
  readonly renderItem: (item: T, index: number) => void;
  /** Where the sentinel goes — must be OUTSIDE the grid, or it becomes a grid cell. */
  readonly sentinelHost: HTMLElement;
  /** Owns the observer's lifetime; the observer is disconnected when the view unloads. */
  readonly component: Component;
  readonly chunkSize?: number;
}

/**
 * Render `items` a chunk at a time, drawing more as the sentinel approaches the viewport. Falls back to
 * rendering everything at once when the list is short or `IntersectionObserver` is unavailable.
 */
export function renderProgressively<T>(options: ProgressiveOptions<T>): void {
  const { items, renderItem, sentinelHost, component } = options;
  const chunkSize = options.chunkSize ?? DEFAULT_CHUNK;
  const total = items.length;

  const drawTo = (end: number, from: number): void => {
    for (let i = from; i < end; i++) {
      const item = items[i];
      if (item !== undefined) renderItem(item, i);
    }
  };

  const observerCtor = typeof IntersectionObserver === "function" ? IntersectionObserver : null;
  if (!shouldChunk(total, chunkSize) || observerCtor === null) {
    drawTo(total, 0);
    return;
  }

  let rendered = nextChunkEnd(0, total, chunkSize);
  drawTo(rendered, 0);

  const sentinel = sentinelHost.createDiv({ cls: "kvs-more-sentinel" });
  const observer = new observerCtor(
    (entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      // Stop observing while we draw, then observe again: if the sentinel is still in view (a tall pane, or
      // a chunk that didn't fill it) a fresh observation fires another round, so the grid keeps filling
      // instead of stalling on an entry that never changes state.
      observer.unobserve(sentinel);
      const from = rendered;
      rendered = nextChunkEnd(rendered, total, chunkSize);
      drawTo(rendered, from);
      if (rendered >= total) {
        observer.disconnect();
        sentinel.remove();
        return;
      }
      observer.observe(sentinel);
    },
    // Start the next chunk before the sentinel is actually on screen, so scrolling rarely meets a gap.
    { rootMargin: "800px 0px" },
  );
  observer.observe(sentinel);
  component.register(() => observer.disconnect());
}
