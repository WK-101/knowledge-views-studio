import { TFile, type App, type Plugin } from "obsidian";
import { SearchIndex, SemanticModel, noteToDocs, questionTerms, rowsToDocs, scorePassageKeyword, splitPassages, tokenize, type IndexSnapshot, type SearchOptions, type SearchResult, type SemanticSnapshot } from "../services/index";
import { fileToSearchDocs, indexableExtensions, indexableFiles, type IndexScope } from "./search-extract";

/** A cheap change signature — mtime+size avoids reading/hashing large PDFs just to detect a change. */
function signature(file: TFile): string {
  return `${file.stat.mtime}:${file.stat.size}`;
}

/** Decide, from current vs stored file signatures, which paths to (re)index and which to drop. Pure. */
export function reconcilePlan(current: ReadonlyMap<string, string>, stored: ReadonlyMap<string, string>): { index: string[]; remove: string[] } {
  const index: string[] = [];
  const remove: string[] = [];
  for (const [path, sig] of current) if (stored.get(path) !== sig) index.push(path);
  for (const path of stored.keys()) if (!current.has(path)) remove.push(path);
  return { index, remove };
}

interface PersistRecord {
  readonly v: 1;
  readonly snapshot: IndexSnapshot;
  readonly sigs: [string, string][];
  readonly idsByPath: [string, string[]][];
  readonly builtAt: number;
}

export interface IndexStatus {
  readonly ready: boolean;
  readonly building: boolean;
  readonly docCount: number;
  readonly fileCount: number;
  readonly progress?: { done: number; total: number };
}

// ---- minimal promise wrapper over IndexedDB (Electron renderer has it) ----

function idbOpen(name: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, 1);
    req.onupgradeneeded = (): void => {
      const db = req.result;
      if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
      if (!db.objectStoreNames.contains("text")) db.createObjectStore("text");
    };
    req.onsuccess = (): void => resolve(req.result);
    req.onerror = (): void => reject(req.error ?? new Error("indexedDB open failed"));
  });
}

function idbGet<T>(db: IDBDatabase, key: string): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const req = db.transaction("kv", "readonly").objectStore("kv").get(key);
    req.onsuccess = (): void => resolve(req.result as T | undefined);
    req.onerror = (): void => reject(req.error ?? new Error("indexedDB get failed"));
  });
}

function idbPut(db: IDBDatabase, key: string, value: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction("kv", "readwrite");
    tx.objectStore("kv").put(value, key);
    tx.oncomplete = (): void => resolve();
    tx.onerror = (): void => reject(tx.error ?? new Error("indexedDB put failed"));
  });
}

function idbGetFrom<T>(db: IDBDatabase, store: string, key: string): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const req = db.transaction(store, "readonly").objectStore(store).get(key);
    req.onsuccess = (): void => resolve(req.result as T | undefined);
    req.onerror = (): void => reject(req.error ?? new Error("indexedDB get failed"));
  });
}

/** Batch many writes/deletes into one transaction — far cheaper than one transaction per doc. */
function idbBatch(db: IDBDatabase, store: string, puts: [string, unknown][], deletes: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readwrite");
    const os = tx.objectStore(store);
    for (const [k, v] of puts) os.put(v, k);
    for (const k of deletes) os.delete(k);
    tx.oncomplete = (): void => resolve();
    tx.onerror = (): void => reject(tx.error ?? new Error("indexedDB batch failed"));
  });
}

const sleep = (ms: number): Promise<void> => new Promise((r) => window.setTimeout(r, ms));

/** Sources whose text we persist for snippets (notes/rows are re-read cheaply instead). */
const ATTACHMENT_SOURCES = new Set(["pdf", "docx", "xlsx", "pptx", "epub"]);

/**
 * Owns the live search index: loads it from IndexedDB on start, keeps it in sync with the vault
 * (incremental, signature-gated so unchanged files are skipped), persists it (debounced), and answers
 * queries. Built for scale: chunked background build that yields to the UI, per-file debounce so rapid
 * saves don't thrash, and O(1) targeted removal via a path→docIds map.
 */
export interface AnswerPassage {
  readonly text: string;
  readonly docId: string;
  readonly source: string;
  readonly location?: string;
  readonly meta?: Readonly<Record<string, string | number>>;
  readonly score: number;
}

export class SearchIndexer {
  private index = new SearchIndex();
  private readonly sigs = new Map<string, string>();
  private readonly idsByPath = new Map<string, string[]>();
  private db: IDBDatabase | null = null;
  private building = false;
  private dirty = false;
  private loaded = false;
  private progress: { done: number; total: number } | undefined;
  private persistTimer: number | undefined;
  private readonly pendingReindex = new Map<string, number>();
  private textPuts: [string, string][] = [];
  private textDeletes: string[] = [];
  private semanticModel: SemanticModel | null = null;
  private buildingSemantic = false;

  constructor(
    private readonly app: App,
    private readonly getScope: () => IndexScope,
  ) {}

  /** What this indexer is currently allowed to read. */
  private scope(): IndexScope {
    return this.getScope();
  }

  /** Whether attachment full text is currently in the index. */
  get indexesAttachments(): boolean {
    return this.scope().attachments;
  }

  /** Turn attachment indexing on and bring the index up to date. Set by the caller (settings own the
   *  flag); the indexer just re-reads the vault under the new scope. */
  private onEnableAttachments?: () => void;

  setEnableAttachments(fn: () => void): void {
    this.onEnableAttachments = fn;
  }

  async enableAttachments(): Promise<void> {
    this.onEnableAttachments?.();
    await this.rebuild();
  }

  get hasSemantic(): boolean {
    return this.semanticModel !== null && this.semanticModel.size > 0;
  }
  get semanticBuilding(): boolean {
    return this.buildingSemantic;
  }

  /** Semantic search (offline distributional vectors). Empty until buildSemantic() has run. */
  semanticSearch(query: string, limit = 100): SearchResult[] {
    if (!this.semanticModel) return [];
    const out: SearchResult[] = [];
    for (const h of this.semanticModel.search(tokenize(query), limit)) {
      const r = this.index.resultFor(h.id, h.score);
      if (r) out.push(r);
    }
    return out;
  }

  /**
   * Extractive answer: retrieve candidate docs (keyword + semantic), split them into passages, and rank
   * passages against the question. Returns the best passages with their sources — no text generation.
   */
  async answer(question: string, limit = 8): Promise<AnswerPassage[]> {
    const qTerms = questionTerms(question);
    if (qTerms.length === 0) return [];
    const qTokens = tokenize(question);

    // Candidate docs: union of top keyword + semantic hits (semantic finds topical matches without overlap).
    const cand = new Map<string, SearchResult>();
    for (const r of this.index.search(question, { limit: 30, matchMode: "any", fuzzy: true })) cand.set(r.id, r);
    for (const r of this.semanticSearch(question, 30)) if (!cand.has(r.id)) cand.set(r.id, r);

    const scored: AnswerPassage[] = [];
    const noteCache = new Map<string, string>();
    for (const r of [...cand.values()].slice(0, 50)) {
      const path = typeof r.meta?.["path"] === "string" ? (r.meta["path"]) : undefined;
      if (!path) continue;
      const text = await this.docText(r.id, r.source, path, noteCache);
      if (text === "") continue;
      for (const p of splitPassages(text)) {
        const pTokens = tokenize(p.text);
        const kw = scorePassageKeyword(qTerms, pTokens, (t) => this.index.termIdf(t));
        if (kw === 0 && !this.semanticModel) continue;
        const sem = this.semanticModel ? this.semanticModel.similarity(qTokens, pTokens) : 0;
        const score = kw + 2.5 * sem;
        if (score <= 0) continue;
        scored.push({
          text: p.text,
          docId: r.id,
          source: r.source,
          score,
          ...(r.location ? { location: r.location } : {}),
          ...(r.meta ? { meta: r.meta } : {}),
        });
      }
    }
    scored.sort((a, b) => b.score - a.score || a.docId.localeCompare(b.docId));
    // De-duplicate near-identical passages and cap.
    const seen = new Set<string>();
    const out: AnswerPassage[] = [];
    for (const p of scored) {
      const key = p.text.slice(0, 80).toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(p);
      if (out.length >= limit) break;
    }
    return out;
  }

  /** Text of a single indexed doc: attachments from the text store, notes/rows re-derived from the file. */
  private async docText(id: string, source: string, path: string, noteCache: Map<string, string>): Promise<string> {
    if (source === "note" || source === "row") {
      let content = noteCache.get(path);
      if (content === undefined) {
        const f = this.app.vault.getAbstractFileByPath(path);
        content = f instanceof TFile ? await this.app.vault.cachedRead(f) : "";
        noteCache.set(path, content);
      }
      if (content === "") return "";
      if (source === "row") return rowsToDocs(path, content).find((d) => d.id === id)?.text ?? "";
      return noteToDocs(path, content).find((d) => d.id === id)?.text ?? "";
    }
    return (await this.getText(id)) ?? "";
  }

  /** Build the semantic vector index from every indexable file (two passes: co-occurrence, then vectors). */
  async buildSemantic(onProgress?: (done: number, total: number) => void): Promise<void> {
    if (this.buildingSemantic) return;
    this.buildingSemantic = true;
    try {
      const files = indexableFiles(this.app, this.scope());
      const model = new SemanticModel();
      const collected: { id: string; tokens: string[] }[] = [];
      let done = 0;
      for (const file of files) {
        try {
          for (const d of await fileToSearchDocs(this.app, file)) {
            const toks = tokenize(d.text);
            if (toks.length === 0) continue;
            collected.push({ id: d.id, tokens: toks });
            model.observe(toks);
          }
        } catch (error) {
          console.error(`[KVS semantic] failed on ${file.path}:`, error);
        }
        if (++done % 20 === 0) {
          onProgress?.(done, files.length);
          await sleep(0);
        }
      }
      for (const c of collected) model.addDocVector(c.id, c.tokens);
      this.semanticModel = model;
      onProgress?.(files.length, files.length);
      await this.persistSemantic();
    } finally {
      this.buildingSemantic = false;
    }
  }

  private async persistSemantic(): Promise<void> {
    if (!this.db || !this.semanticModel) return;
    try {
      await idbPut(this.db, "semantic", this.semanticModel.toSnapshot());
    } catch (error) {
      console.error("[KVS semantic] persist failed:", error);
    }
  }

  private async loadSemantic(): Promise<void> {
    if (!this.db) return;
    try {
      const snap = await idbGet<SemanticSnapshot>(this.db, "semantic");
      if (snap && snap.version === 1) this.semanticModel = SemanticModel.fromSnapshot(snap);
    } catch (error) {
      console.error("[KVS semantic] load failed:", error);
    }
  }

  status(): IndexStatus {
    return {
      ready: this.loaded,
      building: this.building,
      docCount: this.index.size,
      fileCount: this.idsByPath.size,
      ...(this.progress ? { progress: this.progress } : {}),
    };
  }

  search(query: string, options?: SearchOptions): SearchResult[] {
    return this.index.search(query, options);
  }

  /** Wire vault events for incremental maintenance. */
  register(plugin: Plugin): void {
    plugin.registerEvent(plugin.app.vault.on("modify", (f) => f instanceof TFile && this.onChanged(f)));
    plugin.registerEvent(plugin.app.vault.on("create", (f) => f instanceof TFile && this.onChanged(f)));
    plugin.registerEvent(plugin.app.vault.on("delete", (f) => this.removeFile(f.path)));
    plugin.registerEvent(
      plugin.app.vault.on("rename", (f, oldPath) => {
        this.removeFile(oldPath);
        if (f instanceof TFile) this.onChanged(f);
      }),
    );
  }

  /** Load a saved index from IndexedDB (fast startup); safe if none exists. */
  async load(): Promise<void> {
    try {
      this.db = await idbOpen(`kvs-search-${this.app.vault.getName()}`);
      const rec = await idbGet<PersistRecord>(this.db, "main");
      if (rec?.snapshot) {
        this.index = SearchIndex.fromSnapshot(rec.snapshot);
        for (const [k, v] of rec.sigs) this.sigs.set(k, v);
        for (const [k, v] of rec.idsByPath) this.idsByPath.set(k, v);
      }
      await this.loadSemantic();
    } catch (error) {
      console.error("[KVS search] load failed:", error);
    } finally {
      this.loaded = true;
    }
  }

  /** Full pass over the vault: index new/changed files, drop deleted ones (unchanged files skipped). */
  async buildAll(onProgress?: (done: number, total: number) => void): Promise<void> {
    if (this.building) return;
    this.building = true;
    try {
      const files = indexableFiles(this.app, this.scope());
      const byPath = new Map(files.map((f) => [f.path, f] as const));
      const current = new Map([...byPath].map(([p, f]) => [p, signature(f)] as const));
      const plan = reconcilePlan(current, this.sigs);
      for (const path of plan.remove) {
        this.dropDocs(path);
        this.sigs.delete(path);
      }
      let done = 0;
      const total = plan.index.length;
      for (const path of plan.index) {
        const file = byPath.get(path);
        if (file) await this.reindexFile(file);
        done++;
        this.progress = { done, total };
        if (done % 20 === 0) {
          onProgress?.(done, total);
          await this.flushText();
          await sleep(0); // yield to keep the UI responsive on large vaults
        }
      }
      onProgress?.(total, total);
      await this.flushText();
      this.dirty = true;
      await this.persist();
    } catch (error) {
      console.error("[KVS search] build failed:", error);
    } finally {
      this.building = false;
      this.progress = undefined;
    }
  }

  /** Discard everything and rebuild from scratch. */
  async rebuild(onProgress?: (done: number, total: number) => void): Promise<void> {
    this.index = new SearchIndex();
    this.sigs.clear();
    this.idsByPath.clear();
    await this.buildAll(onProgress);
  }

  private onChanged(file: TFile): void {
    if (!indexableExtensions(this.scope()).has(file.extension.toLowerCase())) return;
    const existing = this.pendingReindex.get(file.path);
    if (existing) window.clearTimeout(existing);
    this.pendingReindex.set(
      file.path,
      window.setTimeout(() => {
        this.pendingReindex.delete(file.path);
        if (this.sigs.get(file.path) !== signature(file)) void this.reindexFile(file).then(() => this.schedulePersist());
      }, 1500),
    );
  }

  /** Text of an indexed doc, for snippets: buffered writes first, then the IndexedDB text store. Notes
   *  and rows aren't stored (re-read them via the vault instead). */
  async getText(docId: string): Promise<string | undefined> {
    for (let i = this.textPuts.length - 1; i >= 0; i--) if (this.textPuts[i]![0] === docId) return this.textPuts[i]![1];
    if (!this.db) return undefined;
    try {
      return await idbGetFrom<string>(this.db, "text", docId);
    } catch {
      return undefined;
    }
  }

  private async reindexFile(file: TFile): Promise<void> {
    try {
      this.dropDocs(file.path);
      const docs = await fileToSearchDocs(this.app, file);
      const ids: string[] = [];
      for (const doc of docs) {
        this.index.add(doc);
        ids.push(doc.id);
        if (ATTACHMENT_SOURCES.has(doc.source)) this.textPuts.push([doc.id, doc.text]);
      }
      if (ids.length > 0) this.idsByPath.set(file.path, ids);
      this.sigs.set(file.path, signature(file));
      this.dirty = true;
    } catch (error) {
      console.error(`[KVS search] failed to index ${file.path}:`, error);
    }
  }

  private dropDocs(path: string): void {
    const ids = this.idsByPath.get(path) ?? [];
    for (const id of ids) {
      this.index.remove(id);
      this.textDeletes.push(id);
    }
    this.idsByPath.delete(path);
  }

  private async flushText(): Promise<void> {
    if (!this.db || (this.textPuts.length === 0 && this.textDeletes.length === 0)) return;
    const puts = this.textPuts;
    const dels = this.textDeletes;
    this.textPuts = [];
    this.textDeletes = [];
    try {
      await idbBatch(this.db, "text", puts, dels);
    } catch (error) {
      console.error("[KVS search] text persist failed:", error);
    }
  }

  private removeFile(path: string): void {
    this.dropDocs(path);
    this.sigs.delete(path);
    this.dirty = true;
    if (this.index.wastedFraction > 0.3) this.index.compact();
    this.schedulePersist();
  }

  private schedulePersist(): void {
    if (this.persistTimer) window.clearTimeout(this.persistTimer);
    this.persistTimer = window.setTimeout(() => void this.persist(), 5000);
  }

  /** Write the current index to IndexedDB (debounced via schedulePersist; also called after builds). */
  async persist(): Promise<void> {
    await this.flushText();
    if (!this.dirty || !this.db) return;
    this.dirty = false;
    try {
      const record: PersistRecord = {
        v: 1,
        snapshot: this.index.toSnapshot(),
        sigs: [...this.sigs],
        idsByPath: [...this.idsByPath],
        builtAt: Date.now(),
      };
      await idbPut(this.db, "main", record);
    } catch (error) {
      this.dirty = true;
      console.error("[KVS search] persist failed:", error);
    }
  }
}
