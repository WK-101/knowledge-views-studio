import { type App } from "obsidian";
import { normalizeUrl } from "../../../shared/protocol";
import {
  coerceStickyNote,
  withStickyNote,
  withoutStickyNote,
  type PageStickies,
  type StickyNote,
} from "../../../shared/sticky";

/**
 * Where sticky notes live in the vault.
 *
 * The same shape and discipline as the web-annotation sidecar next door: one JSON file in the plugin's own
 * directory, keyed by normalized URL, holding the machine's copy of every note. It's the only source the
 * page reads when it re-pins notes on revisit — the row cell is a human copy nobody parses back. A file in
 * the plugin directory syncs wherever the vault syncs, and being JSON (not a note) keeps it out of reach of
 * the hand-edits that would corrupt it.
 */

/** Parse whatever the sidecar file holds, dropping what can't be trusted. */
export function parseStickyStore(raw: string): Map<string, PageStickies> {
  const out = new Map<string, PageStickies>();
  if (raw.trim() === "") return out;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    // A corrupt sidecar loses its notes; it must not lose the vault's ability to take new ones.
    return out;
  }
  if (parsed === null || typeof parsed !== "object") return out;
  for (const [url, value] of Object.entries(parsed as Record<string, unknown>)) {
    const list = (value as { notes?: unknown } | null)?.notes;
    if (!Array.isArray(list)) continue;
    const notes = list.map(coerceStickyNote).filter((n): n is StickyNote => n !== null);
    if (notes.length > 0) out.set(url, { url, notes });
  }
  return out;
}

export function serializeStickyStore(store: ReadonlyMap<string, PageStickies>): string {
  const out: Record<string, PageStickies> = {};
  for (const [url, page] of store) {
    if (page.notes.length > 0) out[url] = page;
  }
  return JSON.stringify(out, null, 2);
}

export interface StickyNoteDeps {
  readonly app: App;
  /** Absolute vault-relative path of the sidecar file. */
  readonly storePath: string;
}

export class StickyNoteService {
  private cache: Map<string, PageStickies> | null = null;

  constructor(private readonly deps: StickyNoteDeps) {}

  private async load(): Promise<Map<string, PageStickies>> {
    if (this.cache !== null) return this.cache;
    try {
      const exists = await this.deps.app.vault.adapter.exists(this.deps.storePath);
      const raw = exists ? await this.deps.app.vault.adapter.read(this.deps.storePath) : "";
      this.cache = parseStickyStore(raw);
    } catch {
      this.cache = new Map();
    }
    return this.cache;
  }

  private async persist(): Promise<void> {
    if (this.cache === null) return;
    await this.deps.app.vault.adapter.write(this.deps.storePath, serializeStickyStore(this.cache));
  }

  /** Every note pinned to a page, matched the way the rest of the bridge matches URLs. */
  async list(url: string): Promise<readonly StickyNote[]> {
    const store = await this.load();
    return store.get(normalizeUrl(url))?.notes ?? [];
  }

  async save(note: StickyNote): Promise<void> {
    const store = await this.load();
    const key = normalizeUrl(note.url);
    const page = store.get(key) ?? { url: key, notes: [] };
    store.set(key, withStickyNote(page, note));
    await this.persist();
  }

  /** Remove by id, returning what was removed so its cell copy can be cleaned up too. */
  async remove(url: string, id: string): Promise<StickyNote | null> {
    const store = await this.load();
    const key = normalizeUrl(url);
    const page = store.get(key);
    if (page === undefined) return null;
    const removed = page.notes.find((n) => n.id === id) ?? null;
    store.set(key, withoutStickyNote(page, id));
    await this.persist();
    return removed;
  }

  /** Remove every note for a page. Returns how many went. */
  async removeAll(url: string): Promise<number> {
    const store = await this.load();
    const key = normalizeUrl(url);
    const count = store.get(key)?.notes.length ?? 0;
    if (count > 0) {
      store.delete(key);
      await this.persist();
    }
    return count;
  }
}
