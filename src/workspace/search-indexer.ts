import { TFile, type App, type Plugin } from "obsidian";
import { SearchIndex, SemanticModel, noteToDocs, questionTerms, rowsToDocs, scorePassageKeyword, splitPassages, tokenize, type IndexDoc, type IndexSnapshot, type SearchOptions, type SearchResult, type SemanticSnapshot,
  VectorIndex,
  normalizeWeights,
  applyRecency,
  fuseRankings,
  type RelevanceWeights,
} from "../services/index";
import { fileToSearchDocs, indexableExtensions, indexableFiles, type IndexScope } from "./search-extract";
import { NeuralEmbedder } from "./neural-embedder";
import { LocalIndexBackend, type IndexBackend, type IndexPayload } from "./index-backend";
import type { OcrPipeline } from "../services/search/ocr/pipeline";
import { imageToDoc } from "../services/index";

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

const sleep = (ms: number): Promise<void> => new Promise((r) => window.setTimeout(r, ms));

/** Sources whose text we persist for snippets (notes/rows are re-read cheaply instead). */
const ATTACHMENT_SOURCES = new Set(["pdf", "docx", "xlsx", "pptx", "epub", "image"]);

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
  /** Ids of documents from the external (Zotero) source, so they can be cleared and rebuilt as a group. */
  private externalIds: string[] = [];
  /** Where the index is kept. Swappable, because the choice is the user's. */
  private backend!: IndexBackend;
  /** Attachment text, held in memory and written out with the rest of the index as one unit. */
  private textMap = new Map<string, string>();
  private building = false;
  private dirty = false;
  private ocr?: OcrPipeline;
  private loaded = false;
  private progress: { done: number; total: number } | undefined;
  private persistTimer: number | undefined;
  private readonly pendingReindex = new Map<string, number>();
  private semanticModel: SemanticModel | null = null;
  private neural: VectorIndex | null = null;
  private embedder: NeuralEmbedder | null = null;
  private buildingSemantic = false;

  constructor(
    private readonly app: App,
    private readonly getScope: () => IndexScope,
    backend?: IndexBackend,
    /**
     * An optional source of extra documents that don't come from vault files — currently the Zotero
     * library and its annotations. Kept as an opaque async callback so the indexer has no Zotero-specific
     * knowledge: it just asks for "extra docs, with the id prefix that identifies them" and folds them into
     * the same index. Returns null/empty to contribute nothing (e.g. when the feature is off or Zotero is
     * unreachable), so this never blocks or breaks a build.
     */
    private readonly externalDocs?: () => Promise<{ prefix: string; docs: IndexDoc[] } | null>,
  ) {
    this.backend = backend ?? new LocalIndexBackend(`kvs-search-${app.vault.getName()}`);
  }

  /** What this indexer is currently allowed to read. */
  private scope(): IndexScope {
    return this.getScope();
  }

  /** The relevance weights in force. Clamped, so a hand-edited config cannot produce nonsense. */
  get weights(): RelevanceWeights {
    return normalizeWeights(this.scope().relevance);
  }

  /**
   * Apply the recency bonus to a set of results.
   *
   * Recency is orthogonal to *how* a result was found, so it is applied to every mode -- keyword,
   * semantic, hybrid and Ask -- rather than being a quirk of one of them.
   */
  private withRecency(results: SearchResult[], w: RelevanceWeights): SearchResult[] {
    if (w.recencyWeight <= 0) return results;
    const now = Date.now();
    const out = results.map((r) => ({
      ...r,
      score: applyRecency(r.score, typeof r.meta?.["mtime"] === "number" ? (r.meta["mtime"]) : undefined, now, w),
    }));
    out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    return out;
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
    if (this.scope().semanticEngine === "neural") return this.neural !== null && this.neural.size > 0;
    return this.semanticModel !== null && this.semanticModel.size > 0;
  }
  get semanticBuilding(): boolean {
    return this.buildingSemantic;
  }

  /**
   * Semantic search. Empty until buildSemantic() has run.
   *
   * If the query uses words the vault has never contained, the semantic model has nothing to say about
   * it — every vector component is zero, and it can only return an empty list. That is worse than
   * useless: it tells the user "there is nothing here" when keyword search would have found the note.
   * So in that case we fall back to keyword rather than pretend.
   */
  semanticSearch(query: string, limit = 100): SearchResult[] {
    // The neural engine embeds asynchronously, so its query path is neuralSearch(); this is the
    // built-in engine's synchronous path.
    if (!this.semanticModel) return [];
    const tokens = tokenize(query);
    if (!this.semanticModel.canAnswer(tokens)) {
      return this.index.search(query, { limit, matchMode: "any", fuzzy: true });
    }
    const out: SearchResult[] = [];
    for (const h of this.semanticModel.search(tokens, limit)) {
      const r = this.index.resultFor(h.id, h.score);
      if (r) out.push(r);
    }
    return out;
  }

  /** Semantic search with the neural engine (async, because embedding the query is a model call). */
  async neuralSearch(query: string, limit = 100): Promise<SearchResult[]> {
    if (!this.neural || !this.embedder) return [];
    const vec = await this.embedder.embed(query);
    const out: SearchResult[] = [];
    for (const h of this.neural.search(vec, limit)) {
      const r = this.index.resultFor(h.id, h.score);
      if (r) out.push(r);
    }
    return out;
  }

  /** Notes most like the given one, for the Related notes panel. Excludes the note itself. */
  relatedTo(path: string, limit = 12): SearchResult[] {
    if (this.neural && this.scope().semanticEngine === "neural") return this.relatedNeural(path, limit);
    if (!this.semanticModel) return [];
    // A note is several docs (one per heading). Score every other note against each of them and keep
    // that note's best match, so a long note isn't unfairly diluted by its weakest section.
    const own = (this.idsByPath.get(path) ?? []).filter((id) => id.startsWith("note:"));
    if (own.length === 0) return [];
    const best = new Map<string, { score: number; id: string }>();
    for (const id of own) {
      for (const hit of this.semanticModel.similarTo(id, 200)) {
        const otherPath = this.pathOfDoc(hit.id);
        if (!otherPath || otherPath === path) continue;
        const prev = best.get(otherPath);
        if (!prev || hit.score > prev.score) best.set(otherPath, { score: hit.score, id: hit.id });
      }
    }
    const ranked = [...best.values()].sort((a, b) => b.score - a.score).slice(0, limit);
    const out: SearchResult[] = [];
    for (const r of ranked) {
      const res = this.index.resultFor(r.id, r.score);
      if (res) out.push(res);
    }
    return out;
  }

  private relatedNeural(path: string, limit: number): SearchResult[] {
    const neural = this.neural;
    if (!neural) return [];
    const own = (this.idsByPath.get(path) ?? []).filter((id) => id.startsWith("note:"));
    if (own.length === 0) return [];
    const best = new Map<string, { score: number; id: string }>();
    for (const id of own) {
      for (const hit of neural.similarTo(id, 200)) {
        const otherPath = this.pathOfDoc(hit.id);
        if (!otherPath || otherPath === path) continue;
        const prev = best.get(otherPath);
        if (!prev || hit.score > prev.score) best.set(otherPath, { score: hit.score, id: hit.id });
      }
    }
    const out: SearchResult[] = [];
    for (const r of [...best.values()].sort((a, b) => b.score - a.score).slice(0, limit)) {
      const res = this.index.resultFor(r.id, r.score);
      if (res) out.push(res);
    }
    return out;
  }

  private pathOfDoc(docId: string): string | undefined {
    const meta = this.index.resultFor(docId, 0)?.meta?.["path"];
    return typeof meta === "string" ? meta : undefined;
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

  /** Build the semantic index with whichever engine the user chose. */
  async buildSemantic(onProgress?: (done: number, total: number) => void): Promise<void> {
    if (this.buildingSemantic) return;
    this.buildingSemantic = true;
    try {
      if (this.scope().semanticEngine === "neural") await this.buildNeural(onProgress);
      else await this.buildBuiltin(onProgress);
    } finally {
      this.buildingSemantic = false;
    }
  }

  /**
   * The neural engine: embed every document with the sentence-transformer.
   *
   * This is much slower than the built-in engine — it is running a real model over every document —
   * so it reports progress honestly and yields to keep Obsidian responsive.
   */
  private async buildNeural(onProgress?: (done: number, total: number) => void): Promise<void> {
    this.embedder ??= new NeuralEmbedder();
    await this.embedder.load();

    const files = indexableFiles(this.app, this.scope());
    const index = new VectorIndex();
    let done = 0;
    for (const file of files) {
      try {
        for (const doc of await fileToSearchDocs(this.app, file)) {
          const text = doc.text.trim();
          if (text === "") continue;
          const vec = await this.embedder.embed(text);
          if (vec.length > 0) index.add(doc.id, vec);
        }
      } catch (error) {
        console.error(`[KVS neural] failed on ${file.path}:`, error);
      }
      onProgress?.(++done, files.length);
      await sleep(0);
    }
    this.neural = index;
    await this.persistSemantic();
  }

  private async buildBuiltin(onProgress?: (done: number, total: number) => void): Promise<void> {
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
  }

  private async persistSemantic(): Promise<void> {
    await this.persist();
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
    const w = this.weights;
    return this.withRecency(
      this.index.search(query, {
        ...options,
        fieldBoosts: { title: w.titleBoost, heading: w.headingBoost, tag: w.tagBoost },
      }),
      w,
    );
  }

  /** Blend keyword and semantic rankings using the user's weight, then apply recency. */
  hybridSearch(query: string, options?: SearchOptions): SearchResult[] {
    const w = this.weights;
    const keyword = this.index.search(query, {
      ...options,
      fieldBoosts: { title: w.titleBoost, heading: w.headingBoost, tag: w.tagBoost },
    });
    const semantic = this.semanticSearch(query, options?.limit ?? 100);
    const fused = fuseRankings(keyword, semantic, w);
    const byId = new Map<string, SearchResult>();
    for (const r of [...keyword, ...semantic]) if (!byId.has(r.id)) byId.set(r.id, r);
    const out: SearchResult[] = [];
    for (const f of fused) {
      const base = byId.get(f.id);
      if (base) out.push({ ...base, score: f.score });
    }
    return this.withRecency(out, w);
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

  /** Load a saved index. Missing, foreign, or corrupt: we start clean rather than guess. */
  async load(): Promise<void> {
    try {
      const payload = await this.backend.load();
      if (payload?.main) {
        const rec = payload.main as PersistRecord;
        if (rec.snapshot) {
          this.index = SearchIndex.fromSnapshot(rec.snapshot);
          for (const [k, v] of rec.sigs) this.sigs.set(k, v);
          for (const [k, v] of rec.idsByPath) this.idsByPath.set(k, v);
        }
      }
      if (payload?.text) for (const [k, v] of Object.entries(payload.text)) this.textMap.set(k, v);
      if (payload?.semantic) {
        const snap = payload.semantic as SemanticSnapshot;
        if (snap.version === 1) this.semanticModel = SemanticModel.fromSnapshot(snap);
      }
      if (payload?.neural) {
        const n = payload.neural as { ids: string[]; vecs: Float32Array[] };
        if (n.ids?.length) {
          this.neural = VectorIndex.fromSnapshot(n);
          if (this.scope().semanticEngine === "neural") this.embedder ??= new NeuralEmbedder();
        }
      }
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
          await sleep(0); // yield to keep the UI responsive on large vaults
        }
      }
      onProgress?.(total, total);
      await this.indexExternalDocs();
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

  /**
   * Refresh only the external (Zotero) documents, leaving the file index untouched. Lets a user pull newly
   * added Zotero items and annotations into search without the cost of a full rebuild — indexExternalDocs
   * already clears the prior Zotero batch before adding the current one, so this is a clean swap. Returns
   * the number of external docs now indexed.
   */
  async refreshExternalDocs(): Promise<number> {
    await this.indexExternalDocs();
    this.dirty = true;
    await this.persist();
    return this.externalIds.length;
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
  getText(docId: string): Promise<string | undefined> {
    return Promise.resolve(this.textMap.get(docId));
  }

  private async reindexFile(file: TFile): Promise<void> {
    try {
      this.dropDocs(file.path);
      const docs = await fileToSearchDocs(this.app, file);
      const ids: string[] = [];
      for (const doc of docs) {
        this.index.add(doc);
        ids.push(doc.id);
        if (ATTACHMENT_SOURCES.has(doc.source)) this.textMap.set(doc.id, doc.text);
      }
      if (ids.length > 0) this.idsByPath.set(file.path, ids);
      this.sigs.set(file.path, signature(file));
      this.dirty = true;
      // Images: kick off (or reuse cached) OCR so their text becomes searchable. No-op unless OCR is on.
      if (this.ocr?.handles(file)) this.ocr.consider(file, "low");
    } catch (error) {
      console.error(`[KVS search] failed to index ${file.path}:`, error);
    }
  }

  /** Attach the OCR pipeline (owned by the plugin). Its recognised text arrives via {@link indexImageText}. */
  setOcr(pipeline: OcrPipeline): void {
    this.ocr = pipeline;
  }

  /** (Re)index an image's document with its OCR text — called by the OCR pipeline when recognition lands. */
  indexImageText(file: TFile, text: string): void {
    this.dropDocs(file.path);
    const doc = imageToDoc(file.path, text);
    this.index.add({ ...doc, meta: { ...(doc.meta ?? {}), mtime: file.stat.mtime } });
    this.idsByPath.set(file.path, [doc.id]);
    this.textMap.set(doc.id, text);
    this.dirty = true;
    this.schedulePersist();
  }

  /**
   * Fold in documents from the external source (Zotero library + annotations), if one is configured. Clears
   * the previous batch first so a rebuild reflects the current library. Wrapped so that Zotero being off,
   * absent, or unreachable contributes nothing and never fails the build — vault search stands alone.
   */
  private async indexExternalDocs(): Promise<void> {
    if (!this.externalDocs) return;
    let result: { prefix: string; docs: IndexDoc[] } | null = null;
    try {
      result = await this.externalDocs();
    } catch (error) {
      console.warn("[KVS search] external (Zotero) indexing skipped:", error);
      return;
    }
    // Clear the prior external batch regardless — if the source is now off, its docs should disappear.
    for (const id of this.externalIds) {
      this.index.remove(id);
      this.textMap.delete(id);
    }
    this.externalIds = [];
    if (!result || result.docs.length === 0) return;
    for (const doc of result.docs) {
      this.index.add(doc);
      this.externalIds.push(doc.id);
      // Retain the body text so the results list can show a content snippet for Zotero hits, the same way
      // it does for attachments. Without this, getText() returns nothing and the hit shows no preview.
      if (doc.text !== "") this.textMap.set(doc.id, doc.text);
    }
  }

  private dropDocs(path: string): void {
    const ids = this.idsByPath.get(path) ?? [];
    for (const id of ids) {
      this.index.remove(id);
      this.textMap.delete(id);
    }
    this.idsByPath.delete(path);
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
  /**
   * Write the whole index out as one payload.
   *
   * One unit, deliberately: with an in-vault index, separate writes would sync separately, and an index
   * whose postings had arrived but whose text had not would produce results with no snippets and no
   * explanation. Either the new index lands, or the old one stays.
   */
  async persist(): Promise<void> {
    if (!this.dirty) return;
    try {
      const payload: IndexPayload = {
        main: {
          v: 1,
          snapshot: this.index.toSnapshot(),
          sigs: [...this.sigs],
          idsByPath: [...this.idsByPath],
          builtAt: Date.now(),
        } satisfies PersistRecord,
        text: Object.fromEntries(this.textMap),
        ...(this.semanticModel ? { semantic: this.semanticModel.toSnapshot() } : {}),
        ...(this.neural ? { neural: this.neural.toSnapshot() } : {}),
      };
      await this.backend.save(payload);
      this.dirty = false;
    } catch (error) {
      console.error("[KVS search] persist failed:", error);
    }
  }

  /** Bytes the index occupies, when that is knowable (i.e. when it is a file). */
  size(): Promise<number | undefined> {
    return this.backend.size();
  }

  /** Move the index between IndexedDB and the vault, carrying what we already have. */
  async relocate(backend: IndexBackend): Promise<void> {
    const old = this.backend;
    this.backend = backend;
    this.dirty = true;
    await this.persist();
    if (old.kind !== backend.kind) await old.clear();
  }
}
