import {
  fileInScope,
  paginate,
  runTransform,
  type Dataset,
  type ExtractionInput,
  type Row,
  type RowGroup,
  type SourceFileMeta,
  type TransformResult,
  type TransformSpec,
  type ColumnTypeRegistry,
  type ExtractorRegistry,
  type ScopeConfig,
  combineRows,
  type RowMerge,
} from "../domain/index";
import type { ZoteroProvider } from "./zotero/provider";
import { zoteroItemsToRows } from "./zotero/zotero-rows";
import { Emitter, type Unsubscribe } from "../util/emitter";
import { debounce, type Debounced } from "../util/debounce";
import {
  profileToTransformSpec,
  type GlobalSettings,
  type Profile,
} from "./profile/profile";
import type { VaultChangeEvent, VaultFileRef, VaultGateway } from "./ports/vault-gateway";

export interface DataChange {
  /** Paths whose data changed since the last (debounced) notification. */
  readonly paths: string[];
}

export interface DataServiceOptions {
  readonly gateway: VaultGateway;
  readonly registry: ColumnTypeRegistry;
  readonly extractors: ExtractorRegistry;
  readonly getSettings: () => GlobalSettings;
  /** Called when a single source can't be read/parsed, so it can be skipped without breaking the view. */
  readonly onSourceWarning?: (path: string, error: unknown) => void;
  /**
   * Supplies a Zotero provider on demand, for profiles scoped to a Zotero library. Optional: without it,
   * a Zotero-scoped profile simply yields no rows (rather than erroring), so the data service has no hard
   * dependency on Zotero being configured.
   */
  readonly zoteroProvider?: () => ZoteroProvider | null;
}

interface CachedFile {
  modifiedMs: number;
  sizeBytes: number;
  /** Rows keyed by extractor-set signature, so different profiles can coexist. */
  readonly byExtractors: Map<string, Row[]>;
}

/** The assembled, scope-filtered dataset for one (scope + extractors) key. */
interface AssembledDataset {
  epoch: number;
  rows: Row[];
}

/** A memoized pre-pagination transform result (the expensive filter+sort work). */
interface PreparedResult {
  epoch: number;
  rows: Row[];
  groups: RowGroup[] | null;
  total: number;
  gathered: number;
  nonEmptyFields: readonly string[];
}

/** Files read per chunk when (re)assembling a dataset, to bound concurrency. */
const READ_CHUNK = 64;
/** Most memoized transform results to keep before evicting the oldest. */
const PREPARED_MAX = 16;

function toFileMeta(ref: VaultFileRef): SourceFileMeta {
  return {
    filePath: ref.path,
    fileName: ref.basename,
    folderPath: ref.folder,
    createdMs: ref.createdMs,
    modifiedMs: ref.modifiedMs,
    sizeBytes: ref.sizeBytes,
  };
}

const signatureOf = (extractorIds: readonly string[]): string => [...extractorIds].sort().join("|");

const extOf = (path: string): string => (path.split(".").pop() ?? "").toLowerCase();

type SourceOptions = Readonly<Record<string, Readonly<Record<string, string>>>>;

/** Signature of the per-source options relevant to the given extractors (sheet/header row, …). */
function optionsSignature(extractorIds: readonly string[], sourceOptions: SourceOptions): string {
  const relevant: Record<string, unknown> = {};
  for (const id of extractorIds) {
    const o = sourceOptions[id];
    if (o) relevant[id] = o;
  }
  return JSON.stringify(relevant);
}

/** Stable key for the transform-affecting parts of a spec (everything but pagination). */
function transformSignature(spec: TransformSpec, search: string | undefined): string {
  return JSON.stringify({
    columns: spec.columns ?? null,
    computed: spec.computed ?? null,
    filter: spec.filter ?? null,
    advancedQuery: spec.advancedQuery ?? null,
    sort: spec.sort ?? null,
    group: spec.group ?? null,
    columnMatch: spec.columnMatch ?? null,
    search: search ?? "",
  });
}

/**
 * Runs the pipeline against the vault and keeps a per-file row cache so a query
 * only re-reads files that actually changed (O(changed files), not O(vault)).
 * It also turns the legacy plugin's *dead* auto-refresh setting into a real one:
 * vault changes invalidate the cache immediately, and — when auto-refresh is on —
 * emit a debounced, path-tagged notification so open views can re-render. Views
 * decide whether a change is in their scope, keeping invalidation scope-aware.
 */
export class DataService {
  private readonly cache = new Map<string, CachedFile>();
  /** Assembled datasets by (scope + extractors) key; reused until a file changes. */
  private readonly assembled = new Map<string, AssembledDataset>();
  /** Memoized filter+sort results by (dataset + transform) key; page changes reuse them. */
  private readonly prepared = new Map<string, PreparedResult>();
  /** Bumped whenever any cached input changes, invalidating assembled + prepared caches. */
  private epoch = 0;
  private readonly emitter = new Emitter<DataChange>();
  private readonly unsubscribe: Unsubscribe;
  private readonly debouncedEmit: Debounced<[]>;
  private readonly pendingPaths = new Set<string>();

  constructor(private readonly options: DataServiceOptions) {
    const wait = options.getSettings().refreshDebounceMs;
    this.debouncedEmit = debounce(() => {
      const paths = [...this.pendingPaths];
      this.pendingPaths.clear();
      this.emitter.emit({ paths });
    }, wait);
    this.unsubscribe = options.gateway.onChange((event) => this.handleChange(event));
  }

  /** Gather rows from every in-scope file using the profile's extractors. */
  async buildDataset(profile: Profile): Promise<Dataset> {
    // A Zotero-scoped profile draws its rows from the live Zotero library rather than vault files. Once
    // mapped to Rows, the entire downstream pipeline — compute, filter, search, sort, and every one of the
    // seven layouts — treats them identically to file-derived rows. That is what makes the Zotero library a
    // first-class source and not a bolted-on panel. We don't use the file cache here: Zotero is the source
    // of truth and can change outside the vault, so each build fetches fresh (the query-level prepared
    // cache still spares re-filtering on a plain re-render).
    if (profile.scope.mode === "zotero") {
      return this.buildZoteroDataset(profile.scope);
    }

    const key = this.datasetKey(profile);
    const cached = this.assembled.get(key);
    if (cached && cached.epoch === this.epoch) return cached.rows;

    // Markdown always; office files (e.g. xlsx) only when the feature is enabled AND this profile
    // actually uses an office extractor — so it's completely inert otherwise.
    const officeExts = this.officeExtensionsFor(profile);
    const discovered =
      officeExts.length > 0
        ? this.options.gateway.listMarkdownFiles().concat(this.options.gateway.listFilesByExtension(officeExts))
        : this.options.gateway.listMarkdownFiles();
    // Never treat KVS's own Excel backups as a data source.
    const refs = discovered.filter(
      (ref) => !ref.path.startsWith("_kvs-backups/") && fileInScope(ref.path, profile.scope),
    );
    const sourceOptions = profile.sourceOptions ?? {};

    // Read/extract in bounded-concurrency chunks. Unchanged files hit the per-file
    // cache and resolve immediately; only cache misses actually touch the vault, and
    // they now proceed in parallel instead of one slow await after another.
    const rows: Row[] = [];
    for (let i = 0; i < refs.length; i += READ_CHUNK) {
      const slice = refs.slice(i, i + READ_CHUNK);
      const perFile = await Promise.all(slice.map((ref) => this.rowsForFile(ref, profile.extractors, sourceOptions, profile.rowMerge)));
      for (const fileRows of perFile) {
        for (const row of fileRows) rows.push(row);
      }
    }

    this.assembled.set(key, { epoch: this.epoch, rows });
    return rows;
  }

  /**
   * Fetch the live Zotero library and map it to rows. Degrades to an empty dataset — never throws — when
   * no provider is configured or Zotero is unreachable, so a Zotero-scoped view on a machine without Zotero
   * running simply shows nothing rather than breaking. The write backend is threaded through so the rows'
   * read-only state reflects the current (today: read-only) write capability.
   */
  private async buildZoteroDataset(scope: ScopeConfig): Promise<Dataset> {
    const provider = this.options.zoteroProvider?.() ?? null;
    if (!provider) return [];
    try {
      // Scope to a collection when set; otherwise the whole library. Then, if the profile pins a specific
      // set of item keys (a dashboard built from a selection in the library view), keep only those.
      const items = await provider.listItems(scope.zoteroCollectionKey ? { collectionKey: scope.zoteroCollectionKey } : {});
      const keySet = scope.zoteroItemKeys && scope.zoteroItemKeys.length > 0 ? new Set(scope.zoteroItemKeys) : null;
      const scoped = keySet ? items.filter((it) => keySet.has(it.key)) : items;
      return zoteroItemsToRows(scoped, provider.writes);
    } catch (error) {
      this.options.onSourceWarning?.("zotero://library", error);
      return [];
    }
  }

  /** Non-md extensions this profile's active extractors need — only when the feature is enabled. */
  private officeExtensionsFor(profile: Profile): string[] {
    if (!this.options.getSettings().enableExcelSources) return [];
    const exts = new Set<string>();
    for (const id of profile.extractors) {
      const extractor = this.options.extractors.get(id);
      for (const e of extractor?.extensions ?? ["md"]) if (e !== "md") exts.add(e.toLowerCase());
    }
    return [...exts];
  }

  /** Build the dataset and run the profile's transform over it. */
  async query(profile: Profile, options: { search?: string; page?: number } = {}): Promise<TransformResult> {
    const dataset = await this.buildDataset(profile);
    const base = profileToTransformSpec(profile);

    // Memoize the pre-pagination result (compute -> filter -> search -> sort). That
    // is the O(n log n) part; a page change or a plain re-render with the same query
    // reuses it and only re-slices, instead of re-filtering and re-sorting everything.
    const preparedKey = `${this.datasetKey(profile)}\u00A7${transformSignature(base, options.search)}`;
    let prep = this.prepared.get(preparedKey);
    if (!prep || prep.epoch !== this.epoch) {
      const full = runTransform(
        dataset,
        { ...base, search: options.search, page: null },
        { registry: this.options.registry },
      );
      prep = { epoch: this.epoch, rows: full.rows, groups: full.groups, total: full.total, gathered: full.gathered, nonEmptyFields: full.nonEmptyFields ?? [] };
      this.setPrepared(preparedKey, prep);
    }

    if (prep.groups) {
      return { rows: prep.rows, groups: prep.groups, total: prep.total, gathered: prep.gathered, nonEmptyFields: prep.nonEmptyFields, page: null };
    }
    const pageSpec = base.page && options.page !== undefined ? { ...base.page, index: options.page } : base.page;
    if (pageSpec) {
      const { rows, info } = paginate(prep.rows, pageSpec);
      return { rows, groups: null, total: prep.total, gathered: prep.gathered, nonEmptyFields: prep.nonEmptyFields, page: info };
    }
    return { rows: prep.rows, groups: null, total: prep.total, gathered: prep.gathered, nonEmptyFields: prep.nonEmptyFields, page: null };
  }

  /** Subscribe to data-changed notifications (debounced, path-tagged). */
  onChange(listener: (change: DataChange) => void): Unsubscribe {
    return this.emitter.on(listener);
  }

  /** Whether a changed path affects a given scope (for callers filtering events). */
  affectsScope(path: string, scope: ScopeConfig): boolean {
    return fileInScope(path, scope);
  }

  /** Drop the cached rows for one file (e.g. right after writing to it). */
  invalidate(path: string): void {
    this.cache.delete(path);
    this.bumpEpoch();
  }

  clearCache(): void {
    this.cache.clear();
    this.assembled.clear();
    this.bumpEpoch();
  }

  dispose(): void {
    this.unsubscribe();
    this.debouncedEmit.cancel();
    this.emitter.clear();
    this.cache.clear();
    this.assembled.clear();
    this.prepared.clear();
  }

  private datasetKey(profile: Profile): string {
    // The Excel toggle changes which files are discovered, so it must invalidate cached datasets.
    const excel = this.options.getSettings().enableExcelSources ? "x1" : "x0";
    // The merge mode changes the assembled rows, so it belongs in the key too.
    return `${JSON.stringify(profile.scope)}\u0000${signatureOf(profile.extractors)}\u0000${optionsSignature(profile.extractors, profile.sourceOptions ?? {})}\u0000${excel}\u0000${profile.rowMerge}`;
  }

  private setPrepared(key: string, value: PreparedResult): void {
    this.prepared.set(key, value);
    if (this.prepared.size > PREPARED_MAX) {
      const oldest = this.prepared.keys().next().value;
      if (oldest !== undefined) this.prepared.delete(oldest);
    }
  }

  /** Invalidate assembled + prepared caches (cheap; forces a rebuild on next query). */
  private bumpEpoch(): void {
    this.epoch++;
    this.prepared.clear();
  }

  private async rowsForFile(
    ref: VaultFileRef,
    extractorIds: readonly string[],
    sourceOptions: SourceOptions,
    merge: RowMerge,
  ): Promise<Row[]> {
    // The merge mode changes the rows themselves, so it must be part of the cache key.
    const signature = `${signatureOf(extractorIds)}\u0000${optionsSignature(extractorIds, sourceOptions)}\u0000${merge}`;
    const cached = this.cache.get(ref.path);

    if (cached && cached.modifiedMs === ref.modifiedMs && cached.sizeBytes === ref.sizeBytes) {
      const hit = cached.byExtractors.get(signature);
      if (hit) return hit;
      const rows = await this.readAndExtract(ref, extractorIds, sourceOptions, merge);
      cached.byExtractors.set(signature, rows);
      return rows;
    }

    const rows = await this.readAndExtract(ref, extractorIds, sourceOptions, merge);
    this.cache.set(ref.path, {
      modifiedMs: ref.modifiedMs,
      sizeBytes: ref.sizeBytes,
      byExtractors: new Map([[signature, rows]]),
    });
    return rows;
  }

  private async readAndExtract(
    ref: VaultFileRef,
    extractorIds: readonly string[],
    sourceOptions: SourceOptions,
    merge: RowMerge,
  ): Promise<Row[]> {
    try {
      // Anything that isn't a Markdown note is an office/binary source: read bytes, not text
      // (cachedRead would decode xlsx bytes as UTF-8 and mangle them).
      if (extOf(ref.path) !== "md") {
        const bytes = await this.options.gateway.readBinary(ref.path);
        return this.extract(ref, extractorIds, "", bytes, sourceOptions, merge);
      }
      const content = await this.options.gateway.read(ref.path);
      return this.extract(ref, extractorIds, content, undefined, sourceOptions, merge);
    } catch (error) {
      // A single unreadable/corrupt source (e.g. a non-.xlsx file with an .xlsx name) must not
      // break the whole view — skip it, report it, and let the rest render.
      this.options.onSourceWarning?.(ref.path, error);
      return [];
    }
  }

  private extract(
    ref: VaultFileRef,
    extractorIds: readonly string[],
    content: string,
    bytes: ArrayBuffer | undefined,
    sourceOptions: SourceOptions,
    merge: RowMerge,
  ): Row[] {
    const file = toFileMeta(ref);
    const ext = extOf(ref.path);
    const rows: Row[] = [];
    for (const id of extractorIds) {
      const extractor = this.options.extractors.get(id);
      if (!extractor) continue;
      // Run each extractor only on files whose extension it declares (default md).
      if (!(extractor.extensions ?? ["md"]).includes(ext)) continue;
      const input: ExtractionInput = {
        file,
        content,
        ...(bytes ? { bytes } : {}),
        options: sourceOptions[id] ?? {},
      };
      rows.push(...extractor.extract(input));
    }
    // Sources are combined per file: kept separate, or note-level values folded into the item rows.
    return combineRows(rows, merge);
  }

  private handleChange(event: VaultChangeEvent): void {
    this.cache.delete(event.path);
    if (event.oldPath) this.cache.delete(event.oldPath);
    // Invalidate assembled/prepared caches regardless of auto-refresh, so the next
    // query reflects the change even when views don't auto-render.
    this.bumpEpoch();

    if (!this.options.getSettings().autoRefresh) return;

    this.pendingPaths.add(event.path);
    if (event.oldPath) this.pendingPaths.add(event.oldPath);
    this.debouncedEmit();
  }
}
