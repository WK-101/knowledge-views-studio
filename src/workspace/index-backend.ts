import type { App } from "obsidian";
import { decodeIndex, encodeIndex } from "../services/index";

/**
 * Where the search index lives.
 *
 * By default it lives in IndexedDB — fast, invisible, and **per-device**. Which means: index your vault
 * on the laptop, open it on the phone, and the phone starts from nothing. On a large vault with
 * attachments that is not a minor inconvenience; it is the difference between search working on mobile
 * and not.
 *
 * The alternative is to put the index in the vault, as an ordinary file, and let whatever already syncs
 * your notes carry it too. Obsidian Sync, iCloud, Dropbox, Syncthing — none of them need to know what it
 * is. This is the trick Obsidian Seek uses, and it is the right one.
 *
 * It is *one* file, deliberately. Several files would sync independently, and a sync that delivered a new
 * postings list beside an old text store would leave the index quietly wrong — pointing at snippets that
 * no longer exist. One file is atomic: you either have the new index or the old one, never half of each.
 */

export interface IndexPayload {
  /** The keyword index, its file signatures, and the path→doc-id map. */
  readonly main?: unknown;
  /** Attachment text, for snippets. Keyed by doc id. */
  readonly text?: Record<string, string>;
  /** The built-in semantic model. */
  readonly semantic?: unknown;
  /** The neural vector index. */
  readonly neural?: unknown;
}

export interface IndexBackend {
  load(): Promise<IndexPayload | undefined>;
  save(payload: IndexPayload): Promise<void>;
  clear(): Promise<void>;
  /** Bytes on disk, for telling the user what this costs them. Undefined when not knowable. */
  size(): Promise<number | undefined>;
  readonly kind: "local" | "vault";
}

// ---------------------------------------------------------------- IndexedDB

function idbOpen(name: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, 2);
    req.onupgradeneeded = (): void => {
      const db = req.result;
      if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
      if (!db.objectStoreNames.contains("text")) db.createObjectStore("text");
    };
    req.onsuccess = (): void => resolve(req.result);
    req.onerror = (): void => reject(req.error ?? new Error("indexedDB open failed"));
  });
}

function idbGet<T>(db: IDBDatabase, store: string, key: string): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const req = db.transaction(store, "readonly").objectStore(store).get(key);
    req.onsuccess = (): void => resolve(req.result as T | undefined);
    req.onerror = (): void => reject(req.error ?? new Error("indexedDB get failed"));
  });
}

function idbPut(db: IDBDatabase, store: string, key: string, value: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readwrite");
    tx.objectStore(store).put(value, key);
    tx.oncomplete = (): void => resolve();
    tx.onerror = (): void => reject(tx.error ?? new Error("indexedDB put failed"));
  });
}

/** The default: fast, invisible, and confined to this device. */
export class LocalIndexBackend implements IndexBackend {
  readonly kind = "local";
  private db: IDBDatabase | null = null;

  constructor(private readonly dbName: string) {}

  private async open(): Promise<IDBDatabase> {
    this.db ??= await idbOpen(this.dbName);
    return this.db;
  }

  async load(): Promise<IndexPayload | undefined> {
    try {
      const db = await this.open();
      const main = await idbGet<unknown>(db, "kv", "main");
      const semantic = await idbGet<unknown>(db, "kv", "semantic");
      const neural = await idbGet<unknown>(db, "kv", "neural");
      const text = await idbGet<Record<string, string>>(db, "kv", "textmap");
      if (main === undefined && semantic === undefined && neural === undefined) return undefined;
      return {
        ...(main !== undefined ? { main } : {}),
        ...(semantic !== undefined ? { semantic } : {}),
        ...(neural !== undefined ? { neural } : {}),
        ...(text !== undefined ? { text } : {}),
      };
    } catch (error) {
      console.error("[KVS index] local load failed:", error);
      return undefined;
    }
  }

  async save(payload: IndexPayload): Promise<void> {
    try {
      const db = await this.open();
      if (payload.main !== undefined) await idbPut(db, "kv", "main", payload.main);
      if (payload.semantic !== undefined) await idbPut(db, "kv", "semantic", payload.semantic);
      if (payload.neural !== undefined) await idbPut(db, "kv", "neural", payload.neural);
      if (payload.text !== undefined) await idbPut(db, "kv", "textmap", payload.text);
    } catch (error) {
      console.error("[KVS index] local save failed:", error);
    }
  }

  async clear(): Promise<void> {
    try {
      const db = await this.open();
      for (const key of ["main", "semantic", "neural", "textmap"]) {
        await new Promise<void>((resolve) => {
          const tx = db.transaction("kv", "readwrite");
          tx.objectStore("kv").delete(key);
          tx.oncomplete = (): void => resolve();
          tx.onerror = (): void => resolve();
        });
      }
    } catch (error) {
      console.error("[KVS index] local clear failed:", error);
    }
  }

  size(): Promise<number | undefined> {
    // IndexedDB does not report per-database size; navigator.storage reports the whole origin, which
    // would be a misleading number to show for one index.
    return Promise.resolve(undefined);
  }
}

// ---------------------------------------------------------------- in-vault file

/**
 * The index as a file in the vault, so whatever syncs your notes syncs your index.
 *
 * Written through the vault adapter rather than the Vault API, because this is not a note: it should not
 * appear in search results, the file explorer's Markdown views, or backlinks. It is machinery.
 */
export class VaultIndexBackend implements IndexBackend {
  readonly kind = "vault";

  constructor(
    private readonly app: App,
    private readonly folder: string,
  ) {}

  private get path(): string {
    return `${this.folder.replace(/\/+$/, "")}/search-index.kvsidx`;
  }

  async load(): Promise<IndexPayload | undefined> {
    try {
      const adapter = this.app.vault.adapter;
      if (!(await adapter.exists(this.path))) return undefined;
      const bytes = new Uint8Array(await adapter.readBinary(this.path));
      const decoded = decodeIndex(bytes);
      if (decoded === undefined) {
        // A file we cannot read is a file we do not trust. Say so, and rebuild rather than guess.
        console.warn(`[KVS index] ${this.path} could not be read (wrong version, or truncated by a sync). Rebuilding.`);
        return undefined;
      }
      return decoded as IndexPayload;
    } catch (error) {
      console.error("[KVS index] vault load failed:", error);
      return undefined;
    }
  }

  async save(payload: IndexPayload): Promise<void> {
    try {
      const adapter = this.app.vault.adapter;
      const dir = this.folder.replace(/\/+$/, "");
      if (!(await adapter.exists(dir))) await adapter.mkdir(dir);
      const bytes = encodeIndex(payload);
      // Write whole. A partial write here would be an index that lies about the vault.
      await adapter.writeBinary(this.path, bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
    } catch (error) {
      console.error("[KVS index] vault save failed:", error);
    }
  }

  async clear(): Promise<void> {
    try {
      const adapter = this.app.vault.adapter;
      if (await adapter.exists(this.path)) await adapter.remove(this.path);
    } catch (error) {
      console.error("[KVS index] vault clear failed:", error);
    }
  }

  async size(): Promise<number | undefined> {
    try {
      const adapter = this.app.vault.adapter;
      if (!(await adapter.exists(this.path))) return 0;
      const stat = await adapter.stat(this.path);
      return stat?.size;
    } catch {
      return undefined;
    }
  }
}
