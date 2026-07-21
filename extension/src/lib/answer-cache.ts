/**
 * A small cache for the answers the popup needs the instant it opens.
 *
 * Opening the popup asked the vault three questions from scratch — schema, lookup, annotations — every
 * single time, even seconds after the last opening on the same unchanged page. The wait read as "the
 * plugin starts very slow", and it was: nothing about the page had changed, and everything was asked again.
 *
 * Stale-while-revalidate: the last known answer paints immediately, the fresh one replaces it when it
 * arrives, and anything that *writes* (a capture, a delete, a highlight) invalidates the page's entry so
 * the next paint can't show the world from before the change. Entries live in session storage — gone when
 * the browser closes, which is the right lifetime for "what did the vault say a moment ago".
 */

interface StorageArea {
  get(keys: string[]): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
  remove(keys: string[]): Promise<void>;
}

function area(): StorageArea | null {
  const g = globalThis as unknown as {
    browser?: { storage?: { session?: StorageArea; local?: StorageArea } };
    chrome?: { storage?: { session?: StorageArea; local?: StorageArea } };
  };
  const storage = g.browser?.storage ?? g.chrome?.storage;
  return storage?.session ?? storage?.local ?? null;
}

interface Entry<T> {
  readonly at: number;
  readonly value: T;
}

/** How long an answer is worth painting before it's only a placeholder. */
const FRESH_MS = 90_000;

export async function cached<T>(key: string): Promise<{ value: T; fresh: boolean } | null> {
  const store = area();
  if (store === null) return null;
  try {
    const raw = (await store.get([`cache:${key}`]))[`cache:${key}`] as Entry<T> | undefined;
    if (raw === undefined || typeof raw.at !== "number") return null;
    return { value: raw.value, fresh: Date.now() - raw.at < FRESH_MS };
  } catch {
    return null;
  }
}

export async function remember<T>(key: string, value: T): Promise<void> {
  const store = area();
  if (store === null) return;
  try {
    await store.set({ [`cache:${key}`]: { at: Date.now(), value } });
  } catch {
    // A cache that can't write is just a slow day, not an error.
  }
}

/** Forget a page's answers — called by every write, because a cache that survives its own change lies. */
export async function forget(keys: readonly string[]): Promise<void> {
  const store = area();
  if (store === null) return;
  try {
    await store.remove(keys.map((key) => `cache:${key}`));
  } catch {
    // Same: nothing useful to do.
  }
}

export const statusKey = (url: string): string => `status:${url}`;
export const SCHEMA_KEY = "schema";
