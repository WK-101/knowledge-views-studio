import type { ZoteroLibraryItem, ZoteroProvider } from "./provider";

/**
 * A small, time-bounded cache of the whole Zotero library, shared across the operations that need to scan it
 * — "Fill from Zotero", Zotero-aware "Promote to note", and search indexing. Each of those has to find a
 * paper by DOI, which means listing the library (the search endpoint proved unreliable, so we match against
 * the same `/items/top` list the library view uses). Without a cache, every one of those operations
 * re-fetches the entire library; with it, the first pays the cost and the rest are instant for a short
 * window.
 *
 * Deliberately simple and conservative:
 *  - Short TTL (default 60s): papers don't change second to second, but we never want to serve badly stale
 *    data, so the window is small. Anything past it re-fetches.
 *  - Explicit {@link invalidate}: the "Refresh Zotero" commands clear it, so a user who just changed Zotero
 *    can force freshness.
 *  - The live library *view* deliberately does NOT use this — it fetches directly, because "live" is its
 *    whole point. The cache is only for the incidental library scans the other features do.
 */
export class ZoteroLibraryCache {
  private items: ZoteroLibraryItem[] | null = null;
  private fetchedAt = 0;
  private inflight: Promise<ZoteroLibraryItem[]> | null = null;

  constructor(private readonly ttlMs = 60_000) {}

  /** Get the library, from cache when fresh, otherwise fetching once (concurrent callers share the fetch). */
  async getItems(provider: ZoteroProvider): Promise<ZoteroLibraryItem[]> {
    if (this.items && Date.now() - this.fetchedAt < this.ttlMs) return this.items;
    // Coalesce concurrent misses so two operations firing at once don't both fetch the whole library.
    if (this.inflight) return this.inflight;
    this.inflight = provider
      .listItems()
      .then((items) => {
        this.items = items;
        this.fetchedAt = Date.now();
        return items;
      })
      .finally(() => {
        this.inflight = null;
      });
    return this.inflight;
  }

  /** Find a library item by DOI, using the cache. Returns null when there's no match. */
  async findByDoi(provider: ZoteroProvider, normalizedDoi: string, normalize: (s: string) => string): Promise<ZoteroLibraryItem | null> {
    if (normalizedDoi === "") return null;
    const items = await this.getItems(provider);
    return items.find((it) => normalize(it.doi) === normalizedDoi) ?? null;
  }

  /** Drop the cache so the next read re-fetches (called by the "Refresh Zotero" commands). */
  invalidate(): void {
    this.items = null;
    this.fetchedAt = 0;
  }
}
