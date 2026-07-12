/**
 * Per-view UI state that survives re-renders — selection, bulk-edit draft, scroll offset, calendar
 * month — keyed by a view's `viewKey`. Centralised here so it can be (a) pruned precisely when a view
 * or layout is deleted, and (b) hard-capped so it can never grow without bound even for deletions we
 * can't observe (e.g. removing a view from the settings list).
 */

export interface MonthState {
  year: number;
  month: number;
}

export const selectionStore = new Map<string, Set<string>>();
export const bulkDraftStore = new Map<string, { column: string; value: string }>();
export const scrollStore = new Map<string, number>();
export const monthState = new Map<string, MonthState>();

/** Structural view over any of the stores — enough to prune and cap without knowing the value type. */
type Prunable = { keys(): IterableIterator<string>; delete(key: string): boolean; readonly size: number };
const ALL_STORES: readonly Prunable[] = [selectionStore, bulkDraftStore, scrollStore, monthState];

/** Absolute ceiling per store — far above any realistic count of open view×layout combinations. */
const MAX_PER_STORE = 500;

/** Evict oldest-inserted entries once a store exceeds the cap. Call right after inserting a key. */
export function capViewState<V>(store: Map<string, V>): void {
  while (store.size > MAX_PER_STORE) {
    const oldest = store.keys().next().value;
    if (oldest === undefined) break;
    store.delete(oldest);
  }
}

/** Forget all per-view state whose key starts with `prefix` — used when a view or layout is removed. */
export function forgetViewState(prefix: string): void {
  for (const store of ALL_STORES) {
    for (const key of [...store.keys()]) {
      if (key.startsWith(prefix)) store.delete(key);
    }
  }
}
