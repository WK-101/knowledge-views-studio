import { Notice, requestUrl } from "obsidian";
import type { App } from "obsidian";
import {
  getField,
  resolveFieldColumn,
  type AcademicField,
  type Row,
  type RowProvenance,
} from "../domain/index";
import { escapeTableCell } from "../util/markdown";
import {
  fetchDoiMetadata,
  fetchDoiMetadataResult,
  findDuplicateDois,
  normalizeDoi,
  resolveOpenAlexIds,
  fetchReferencedIds,
  type DoiMetadata,
  type ImportedRef,
  type DoiLookupResult,
} from "../services/index";
import type { Profile } from "../services/profile/profile";
import type { ProcessorDeps } from "../codeblock/processor";
import { DedupModal, type DedupResolution } from "./dedup-modal";
import { ShardModal, type ShardField } from "./shard-modal";
import { LocalApiZoteroProvider } from "../services/zotero/local-api-provider";
import { createZoteroFetcher, createZoteroPoster } from "./zotero-transport";
import { bbtEndpointFromApiBase, fetchBbtCiteKey } from "../services/zotero/bbt-citekey";
import { fetchZoteroAnnotations } from "../services/annotations/zotero-client";
import type { ZoteroLibraryItem } from "../services/zotero/provider";
import { renderAnnotationsMarkdown } from "../services/annotations/render";
import { parseThemeMap } from "../services/annotations/themes";

/**
 * What the academic controller needs from the dashboard it serves.
 *
 * The academic kit — DOI capture and fill, duplicate detection, citation-graph linking, library
 * sharding, BibTeX/CSV reference import — was ~540 lines living *inside* `DashboardView`, a class that
 * also does toolbar building, tab management, layout switching, saving, and rendering. That is the god
 * object the rest of this codebase avoids: a view class that knows how to talk to Crossref. Pulling the
 * kit out behind this interface makes the boundary explicit and the view ~17% shorter, and it does so
 * without changing a line of behaviour — the methods are the same, they just reach the view through
 * these seven members instead of `this`.
 *
 * Deliberately narrow: everything else the kit needs (the DOI clients, the dedup detector, the shard
 * writer) it now owns. The host only supplies the live view state the kit cannot know on its own.
 */
export interface AcademicHost {
  readonly app: App;
  readonly deps: ProcessorDeps;
  /** The profile as currently rendered (may lag the active one mid-edit); falls back to the active one. */
  renderedProfile(): Profile | undefined;
  /** The active profile — the source of truth for settings and column config. */
  currentProfile(): Profile | undefined;
  /** The rows currently on screen, for reading existing values and resolving the write target. */
  lastRows(): Row[];
  /** The live search string, so a capture lands in the same filtered context the user is looking at. */
  search(): string;
  /** Re-run the query and redraw — called after any write so the change appears immediately. */
  renderActive(): void;
  /**
   * Write cell edits with undo, invalidate, and redraw. Shared write infrastructure that also serves
   * non-academic view features (note promotion), so it stays on the view and the kit calls into it,
   * rather than the kit owning a second copy.
   */
  applyRowEdits(
    path: string,
    edits: readonly { provenance: Row["provenance"]; column: string; value: string }[],
    label: string,
  ): Promise<number>;
  /** Resolve where a newly-captured row should be appended, honouring the view's configured target. */
  appendTargetFor(fallback: Row["provenance"]): Row["provenance"];
}

/**
 * The academic-research kit, extracted whole from DashboardView. Owns the DOI/citation/shard logic and
 * the one piece of state that logic carries (the citation-key index). Reaches the view only through
 * {@link AcademicHost}.
 */
export class AcademicController {
  /** Cached citation-key map (DOI -> cite key) for the current library, with the epoch it was built at. */
  private citeIndex: { map: Map<string, string>; at: number } | null = null;

  constructor(private readonly host: AcademicHost) {}

  /** Clear the citation index (called when the view force-refreshes, so a stale map is not reused). */
  resetCiteIndex(): void {
    this.citeIndex = null;
  }

  fieldCol(cols: readonly { name: string; type: string }[], field: AcademicField): { name: string; type: string } | undefined {
    return resolveFieldColumn(cols, field, (this.host.renderedProfile() ?? this.host.currentProfile())?.fieldMap);
  }

  async fetchDoiValues(doi: string): Promise<Record<string, string> | null> {
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return null;
    }
    const res = await this.lookupDoi(doi);
    if (!res.ok) {
      new Notice(res.reason);
      return null;
    }
    const cols = (this.host.renderedProfile() ?? this.host.currentProfile())?.columns ?? [];
    return this.doiRowValues(cols, res.meta, doi);
  }

  private fetchDoi(doi: string): Promise<DoiMetadata | null> {
    return fetchDoiMetadata(doi, (url) => this.lookupFetch(url));
  }

  private lookupDoi(doi: string): Promise<DoiLookupResult> {
    return fetchDoiMetadataResult(doi, (url) => this.lookupFetch(url));
  }

  private async lookupFetch(url: string): Promise<{ status: number; json?: unknown; text?: string }> {
    const email = this.host.deps.store.getSettings().researchEmail.trim();
    const full = email !== "" ? `${url}${url.includes("?") ? "&" : "?"}mailto=${encodeURIComponent(email)}` : url;
    const res = await requestUrl({ url: full, headers: { Accept: "application/json" } });
    return { status: res.status, json: res.json, text: res.text };
  }

  private doiEdits(row: Row, cols: readonly { name: string; type: string }[], meta: DoiMetadata): { provenance: Row["provenance"]; column: string; value: string }[] {
    const edits: { provenance: Row["provenance"]; column: string; value: string }[] = [];
    const put = (field: AcademicField, value: string): void => {
      if (value.trim() === "") return;
      const col = this.fieldCol(cols, field);
      if (col && getField(row, col.name).trim() === "") edits.push({ provenance: row.provenance, column: col.name, value });
    };
    put("authors", meta.authors);
    put("title", meta.title);
    put("year", meta.year);
    put("venue", meta.venue);
    const surname = meta.authors ? (meta.authors.split(/[;,]/)[0] ?? "").trim().toLowerCase().replace(/[^a-z]/g, "") : "";
    if (surname && meta.year) put("citekey", `${surname}${meta.year}`);
    return edits;
  }

  async fillFromDoi(row: Row, profile: Profile): Promise<void> {
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const doiCol = this.fieldCol(cols, "doi");
    const doi = doiCol ? getField(row, doiCol.name).trim() : "";
    if (doi === "") {
      new Notice("This row has no DOI to look up.");
      return;
    }
    new Notice("Looking up DOI…");
    const res = await this.lookupDoi(doi);
    if (!res.ok) {
      new Notice(res.reason);
      return;
    }
    const edits = this.doiEdits(row, cols, res.meta);
    if (edits.length === 0) {
      new Notice("Those fields are already filled.");
      return;
    }
    await this.host.applyRowEdits(row.provenance.filePath, edits, `Fill ${edits.length} field(s) from DOI`);
    new Notice(`Filled ${edits.length} field(s) from DOI.`);
  }

  /** Turn a Zotero item's fields (including tags and cite key — richer than Crossref) into row edits. */
  private zoteroEdits(row: Row, cols: readonly { name: string; type: string }[], item: ZoteroLibraryItem, exactCiteKey: string): { provenance: Row["provenance"]; column: string; value: string }[] {
    const edits: { provenance: Row["provenance"]; column: string; value: string }[] = [];
    const put = (field: AcademicField, value: string): void => {
      if (value.trim() === "") return;
      const col = this.fieldCol(cols, field);
      if (col && getField(row, col.name).trim() === "") edits.push({ provenance: row.provenance, column: col.name, value });
    };
    put("authors", item.creators);
    put("title", item.title);
    put("year", item.year);
    put("venue", item.publication);
    if (item.tags.length > 0) put("tags", item.tags.join(", "));
    // The cite key is Better BibTeX's to own, so it's authoritative: we write BBT's exact key even if the
    // cell already holds something (e.g. a value from before the paper was in Zotero) — but only when it
    // actually differs, and only if the column exists. This is the one field we overwrite; everything above
    // is fill-empty-only so we never clobber a user's edits.
    const citeCol = this.fieldCol(cols, "citekey");
    if (citeCol && exactCiteKey.trim() !== "" && getField(row, citeCol.name).trim() !== exactCiteKey.trim()) {
      edits.push({ provenance: row.provenance, column: citeCol.name, value: exactCiteKey.trim() });
    }
    return edits;
  }

  /**
   * Fill a row's fields from Zotero, when its DOI matches an item in the local library. This is a richer
   * sibling of "fill from DOI": instead of Crossref, it reads the paper you already have in Zotero — so it
   * can bring across your cite key and tags, not just the bibliographic basics. Falls back with a clear
   * notice when Zotero isn't reachable or the DOI isn't in the library.
   */
  async fillFromZotero(row: Row, profile: Profile): Promise<void> {
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const doiCol = this.fieldCol(cols, "doi");
    const doi = doiCol ? getField(row, doiCol.name).trim() : "";
    if (doi === "") {
      new Notice("This row has no DOI to look up in Zotero.");
      return;
    }
    const base = this.host.deps.store.getSettings().zoteroApiBase;
    const fetcher = createZoteroFetcher();
    new Notice("Looking up this DOI in Zotero…");
    // Reachability, using the EXACT request the settings "Test" button uses and succeeds with. A failure
    // here means Zotero genuinely can't be reached, and we report the real reason (ECONNREFUSED, timeout…).
    const probe = await fetcher(`${base.replace(/\/+$/, "")}/items?limit=1&format=json`);
    if (probe.status === 0) {
      new Notice(`Couldn't connect to Zotero at ${base} (${probe.reason ?? "no response"}). This is the same URL the settings "Test" button uses — if Test works but this doesn't, please report this exact message.`);
      return;
    }
    // Match the DOI against the library items from listItems() — the SAME endpoint (/items/top) the Zotero
    // library view uses successfully. Earlier this used a /items?q=…&qmode=everything full-text search,
    // which fails or times out on some libraries even when listing works; matching against the proven
    // endpoint is reliable wherever the library view is.
    const provider = new LocalApiZoteroProvider(base, fetcher);
    let item: ZoteroLibraryItem | null;
    try {
      const target = normalizeDoi(doi);
      const cache = this.host.deps.zoteroLibraryCache;
      // Match against the library via the shared cache when available (so repeated fills don't each
      // re-fetch the whole library), else fetch directly. Either way uses the working /items/top list.
      item = cache
        ? await cache.findByDoi(provider, target, (s) => normalizeDoi(s))
        : (await provider.listItems()).find((it) => normalizeDoi(it.doi) === target && target !== "") ?? null;
    } catch {
      new Notice("Reached Zotero, but couldn't read the library to match the DOI.");
      return;
    }
    if (!item) {
      new Notice("Reached Zotero, but that DOI isn't in your library.");
      return;
    }
    // Ask Better BibTeX for this item's exact citation key (its formula is user-configured and its key is
    // often not in the standard API). This guarantees the cite key we write matches BBT byte-for-byte. If
    // BBT isn't reachable, fall back to the pinned key the item already carries, else leave it blank.
    const exactCiteKey = (await fetchBbtCiteKey(bbtEndpointFromApiBase(base), item.key, createZoteroPoster())) || item.citeKey;
    const edits = this.zoteroEdits(row, cols, item, exactCiteKey);
    if (edits.length === 0) {
      new Notice("Those fields are already filled.");
      return;
    }
    await this.host.applyRowEdits(row.provenance.filePath, edits, `Fill ${edits.length} field(s) from Zotero`);
    new Notice(`Filled ${edits.length} field(s) from Zotero.`);
  }

  /**
   * If a DOI is in the Zotero library, return the extra fields that enrich a promoted note: the paper's
   * abstract, its annotations rendered to markdown, its Zotero key, and the item itself (so the caller can
   * prefer Zotero's richer metadata). Returns null when Zotero is unreachable or the DOI isn't found. The
   * promoted note is then rendered from the SAME template as a non-Zotero promotion — these fields just fill
   * the Abstract/Annotations sections and the zotero-key — so promoted notes look identical either way.
   */
  async zoteroPromoteEnrichment(doi: string): Promise<{ item: ZoteroLibraryItem; abstract: string; annotations: string; zoteroKey: string; citeKey: string } | null> {
    try {
      const settings = this.host.deps.store.getSettings();
      const base = settings.zoteroApiBase;
      const fetcher = createZoteroFetcher();
      const provider = new LocalApiZoteroProvider(base, fetcher);
      const target = normalizeDoi(doi);
      if (target === "") return null;
      const cache = this.host.deps.zoteroLibraryCache;
      const item = cache
        ? await cache.findByDoi(provider, target, (s) => normalizeDoi(s))
        : (await provider.listItems()).find((it) => normalizeDoi(it.doi) === target) ?? null;
      if (!item) return null;
      const citeKey = (await fetchBbtCiteKey(bbtEndpointFromApiBase(base), item.key, createZoteroPoster())) || item.citeKey;
      let annotationsMd = "";
      try {
        const annotations = await fetchZoteroAnnotations(base, [item.key], fetcher);
        if (annotations.length > 0) annotationsMd = renderAnnotationsMarkdown(annotations, { themeMap: parseThemeMap(settings.annotationThemes) });
      } catch {
        annotationsMd = "";
      }
      return { item, abstract: item.extra["abstract"] ?? "", annotations: annotationsMd, zoteroKey: item.key, citeKey };
    } catch {
      return null;
    }
  }

  async bulkFillFromDoi(): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile?.academicKit) {
      new Notice("Open an Academic Research kit view to use this.");
      return;
    }
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const doiCol = this.fieldCol(cols, "doi");
    if (!doiCol) {
      new Notice("This view has no DOI column.");
      return;
    }
    const metaCols = new Set(
      (["authors", "title", "year", "venue"] as AcademicField[]).map((f) => this.fieldCol(cols, f)?.name).filter((n): n is string => !!n),
    );
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, { search: this.host.search() });
    const targets = result.rows.filter(
      (row) => getField(row, doiCol.name).trim() !== "" && [...metaCols].some((n) => getField(row, n).trim() === ""),
    );
    if (targets.length === 0) {
      new Notice("Every paper with a DOI is already filled in.");
      return;
    }
    const delay = this.host.deps.store.getSettings().researchRequestDelayMs;
    const notice = new Notice(`Filling ${targets.length} paper(s) from DOI…`, 0);
    const allEdits: { provenance: Row["provenance"]; column: string; value: string }[] = [];
    let failed = 0;
    let lastReason = "";
    for (let i = 0; i < targets.length; i++) {
      const res = await this.lookupDoi(getField(targets[i]!, doiCol.name));
      if (res.ok) allEdits.push(...this.doiEdits(targets[i]!, cols, res.meta));
      else {
        failed++;
        lastReason = res.reason;
      }
      notice.setMessage(`Filling from DOI… ${i + 1}/${targets.length}`);
      if (i < targets.length - 1) await new Promise((r) => window.setTimeout(r, delay));
    }
    notice.hide();
    if (allEdits.length > 0) {
      const paths = [...new Set(allEdits.map((e) => e.provenance.filePath))];
      const snapshot = await this.host.deps.writer.snapshot(paths);
      const res = await this.host.deps.writer.editCells(allEdits);
      if (res.applied > 0) {
        this.host.deps.undo.push({
          label: `Fill ${res.applied} field(s) from DOI`,
          undo: async () => {
            await this.host.deps.writer.restore(snapshot);
            for (const p of paths) this.host.deps.dataService.invalidate(p);
            void this.host.renderActive();
          },
        });
      }
      for (const p of paths) this.host.deps.dataService.invalidate(p);
      void this.host.renderActive();
    }
    new Notice(`Done: ${targets.length - failed} looked up, ${failed} failed.${failed > 0 && lastReason ? ` Last error: ${lastReason}` : ""}`);
  }

  async captureByDoi(dois: readonly string[]): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile?.academicKit) {
      new Notice("Open an Academic Research kit view to capture papers.");
      return;
    }
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const reference = this.host.lastRows()[0]?.provenance;
    if (!reference) {
      new Notice("Add one paper row first, then capture more by DOI.");
      return;
    }
    const delay = this.host.deps.store.getSettings().researchRequestDelayMs;
    const notice = new Notice(`Fetching ${dois.length} DOI(s)…`, 0);
    let added = 0;
    let failed = 0;
    for (let i = 0; i < dois.length; i++) {
      const doi = normalizeDoi(dois[i]!);
      const meta = await this.fetchDoi(doi);
      try {
        const res = await this.host.deps.writer.appendRow(reference, this.doiRowValues(cols, meta, doi));
        if (res.ok) added++;
        else failed++;
      } catch {
        failed++;
      }
      notice.setMessage(`Capturing… ${i + 1}/${dois.length}`);
      if (i < dois.length - 1) await new Promise((r) => window.setTimeout(r, delay));
    }
    notice.hide();
    this.host.deps.dataService.invalidate(reference.filePath);
    void this.host.renderActive();
    new Notice(`Captured ${added} paper(s)${failed ? `, ${failed} failed` : ""}.`);
  }

  private doiRowValues(cols: readonly { name: string; type: string }[], meta: DoiMetadata | null, doi: string): Record<string, string> {
    const values: Record<string, string> = {};
    const put = (field: AcademicField, value: string): void => {
      if (value.trim() === "") return;
      const col = this.fieldCol(cols, field);
      if (col) values[col.name] = value;
    };
    const surname = meta?.authors ? (meta.authors.split(/[;,]/)[0] ?? "").trim().toLowerCase().replace(/[^a-z]/g, "") : "";
    put("citekey", surname && meta?.year ? `${surname}${meta.year}` : doi);
    if (meta) {
      put("authors", meta.authors);
      put("title", meta.title);
      put("year", meta.year);
      put("venue", meta.venue);
    }
    put("doi", doi);
    return values;
  }

  async findDuplicateDois(): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile?.academicKit) {
      new Notice("Open an Academic Research kit view to use this.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const doiCol = this.fieldCol(cols, "doi");
    if (!doiCol) {
      new Notice("This view has no DOI column to check.");
      return;
    }
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, {});
    const groups = findDuplicateDois(result.rows, doiCol.name);
    if (groups.length === 0) {
      new Notice("No duplicate DOIs found — your library is clean.");
      return;
    }
    const titleCol = this.fieldCol(cols, "title");
    const keyCol = this.fieldCol(cols, "citekey");
    new DedupModal(this.host.app, groups, titleCol?.name ?? null, keyCol?.name ?? null, (res) => void this.resolveDuplicates(res)).open();
  }

  private async resolveDuplicates(res: DedupResolution): Promise<void> {
    const paths = [...new Set([...res.remove.map((r) => r.provenance.filePath), ...res.mergeEdits.map((e) => e.provenance.filePath)])];
    if (paths.length === 0) return;
    const snapshot = await this.host.deps.writer.snapshot(paths);
    // Merge first (fill keepers), then delete the duplicate rows.
    if (res.mergeEdits.length > 0) await this.host.deps.writer.editCells(res.mergeEdits.map((e) => ({ provenance: e.provenance, column: e.column, value: e.value })));
    const del = await this.host.deps.writer.deleteRows(res.remove.map((r) => r.provenance));
    if (!del.ok) {
      new Notice(`Couldn't remove duplicates: ${del.reason ?? "unknown error"}`);
      await this.host.deps.writer.restore(snapshot);
    } else {
      this.host.deps.undo.push({
        label: `Remove ${res.remove.length} duplicate(s)`,
        undo: async () => {
          await this.host.deps.writer.restore(snapshot);
          for (const p of paths) this.host.deps.dataService.invalidate(p);
          void this.host.renderActive();
        },
      });
      new Notice(`Removed ${res.remove.length} duplicate paper(s)${res.mergeEdits.length > 0 ? ` and merged ${res.mergeEdits.length} field(s) into the kept copies` : ""}.`);
    }
    for (const p of paths) this.host.deps.dataService.invalidate(p);
    void this.host.renderActive();
  }

  private async citationLibraryMap(doiCol: { name: string }, citeCol: { name: string }, force: boolean): Promise<Map<string, string>> {
    if (!force && this.citeIndex && Date.now() - this.citeIndex.at < 300_000) return this.citeIndex.map;
    const profile = this.host.renderedProfile() ?? this.host.currentProfile();
    const result = profile ? await this.host.deps.dataService.query({ ...profile, pageSize: null }, {}) : { rows: [] as Row[] };
    const withDoi = result.rows.filter((r) => getField(r, doiCol.name).trim() !== "" && getField(r, citeCol.name).trim() !== "");
    const idByDoi = await resolveOpenAlexIds(
      withDoi.map((r) => getField(r, doiCol.name)),
      (url) => this.lookupFetch(url),
    );
    const map = new Map<string, string>();
    for (const row of withDoi) {
      const id = idByDoi.get(normalizeDoi(getField(row, doiCol.name)).toLowerCase());
      if (id) map.set(id, getField(row, citeCol.name).trim());
    }
    this.citeIndex = { map, at: Date.now() };
    return map;
  }

  private citesCheckedCol(cols: readonly { name: string; type: string }[]): { name: string } | undefined {
    return cols.find((c) => c.type === "date" && /^cites checked$/i.test(c.name));
  }

  async findCitationsFor(doi: string): Promise<Record<string, string> | null> {
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return null;
    }
    const cols = (this.host.renderedProfile() ?? this.host.currentProfile())?.columns ?? [];
    const doiCol = this.fieldCol(cols, "doi");
    const citeCol = this.fieldCol(cols, "citekey");
    const citesCol = this.fieldCol(cols, "cites");
    if (!doiCol || !citeCol || !citesCol) {
      new Notice("This needs DOI, Citation key, and Cites columns.");
      return null;
    }
    const map = await this.citationLibraryMap(doiCol, citeCol, false);
    const refIds = await fetchReferencedIds(doi, (url) => this.lookupFetch(url));
    const cited: string[] = [];
    for (const id of refIds) {
      const key = map.get(id);
      if (key) cited.push(`[[${key}]]`);
    }
    const out: Record<string, string> = { [citesCol.name]: [...new Set(cited)].join(" ") };
    const checked = this.citesCheckedCol(cols);
    if (checked) out[checked.name] = new Date().toISOString().slice(0, 10);
    return out;
  }

  async findCitationLinks(): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile?.academicKit) {
      new Notice("Open an Academic Research kit view to use this.");
      return;
    }
    if (!this.host.deps.store.getSettings().researchLookupEnabled) {
      new Notice("Enable research lookups in Settings → Academic Research kit.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const doiCol = this.fieldCol(cols, "doi");
    const citeCol = this.fieldCol(cols, "citekey");
    const citesCol = this.fieldCol(cols, "cites");
    if (!doiCol || !citeCol || !citesCol) {
      new Notice("This needs a DOI column, a Citation key column, and a Cites column.");
      return;
    }
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, {});
    const withDoi = result.rows.filter((r) => getField(r, doiCol.name).trim() !== "" && getField(r, citeCol.name).trim() !== "");
    if (withDoi.length === 0) {
      new Notice("No rows with both a DOI and a cite key to link.");
      return;
    }
    const checkedCol = this.citesCheckedCol(cols);
    const today = new Date().toISOString().slice(0, 10);
    const delay = this.host.deps.store.getSettings().researchRequestDelayMs;

    const notice = new Notice("Resolving your library on OpenAlex…", 0);
    const keyByOaId = await this.citationLibraryMap(doiCol, citeCol, true);

    const edits: { provenance: Row["provenance"]; column: string; value: string }[] = [];
    let linked = 0;
    for (let i = 0; i < withDoi.length; i++) {
      const target = withDoi[i]!;
      const targetKey = getField(target, citeCol.name).trim();
      const refIds = await fetchReferencedIds(getField(target, doiCol.name), (url) => this.lookupFetch(url));
      const cited: string[] = [];
      for (const id of refIds) {
        const key = keyByOaId.get(id);
        if (key && key !== targetKey) cited.push(`[[${key}]]`);
      }
      const value = [...new Set(cited)].join(" ");
      if (value !== getField(target, citesCol.name).trim()) edits.push({ provenance: target.provenance, column: citesCol.name, value });
      if (value !== "") linked++;
      // Stamp when this paper was checked, so an empty Cites reads as "checked, none found" not "unchecked".
      if (checkedCol) edits.push({ provenance: target.provenance, column: checkedCol.name, value: today });
      notice.setMessage(`Finding citations… ${i + 1}/${withDoi.length}`);
      if (i < withDoi.length - 1) await new Promise((r) => window.setTimeout(r, delay));
    }
    notice.hide();

    if (edits.length > 0) {
      const paths = [...new Set(edits.map((e) => e.provenance.filePath))];
      const snapshot = await this.host.deps.writer.snapshot(paths);
      const res = await this.host.deps.writer.editCells(edits);
      if (res.applied > 0) {
        this.host.deps.undo.push({
          label: "Link citations",
          undo: async () => {
            await this.host.deps.writer.restore(snapshot);
            for (const p of paths) this.host.deps.dataService.invalidate(p);
            void this.host.renderActive();
          },
        });
      }
      for (const p of paths) this.host.deps.dataService.invalidate(p);
      void this.host.renderActive();
    }
    new Notice(`Linked ${linked} paper(s) to references already in your library${checkedCol ? ` (${withDoi.length} checked)` : ""}.`);
  }

  async importReferences(refs: readonly ImportedRef[]): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile) return;
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const reference = this.host.lastRows()[0]?.provenance;
    if (!reference) {
      new Notice("Open a library with at least one row to import into.");
      return;
    }
    const doiCol = this.fieldCol(cols, "doi");
    const keyCol = this.fieldCol(cols, "citekey");
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, {});
    const existing = new Set<string>();
    for (const row of result.rows) {
      if (doiCol) {
        const d = normalizeDoi(getField(row, doiCol.name)).toLowerCase();
        if (d) existing.add(`doi:${d}`);
      }
      if (keyCol) {
        const k = getField(row, keyCol.name).trim().toLowerCase().replace(/^@/, "");
        if (k) existing.add(`key:${k}`);
      }
    }

    const notice = new Notice(`Importing ${refs.length} reference(s)…`, 0);
    let added = 0;
    let skipped = 0;
    for (let i = 0; i < refs.length; i++) {
      const ref = refs[i]!;
      const dkey = ref.doi ? `doi:${normalizeDoi(ref.doi).toLowerCase()}` : "";
      const kkey = ref.citeKey ? `key:${ref.citeKey.toLowerCase().replace(/^@/, "")}` : "";
      if ((dkey && existing.has(dkey)) || (kkey && existing.has(kkey))) {
        skipped++;
        continue;
      }
      try {
        const res = await this.host.deps.writer.appendRow(reference, this.refToRowValues(cols, ref));
        if (res.ok) {
          added++;
          if (dkey) existing.add(dkey);
          if (kkey) existing.add(kkey);
        }
      } catch {
        // skip a row that fails to append
      }
      notice.setMessage(`Importing… ${i + 1}/${refs.length}`);
    }
    notice.hide();
    this.host.deps.dataService.invalidate(reference.filePath);
    void this.host.renderActive();
    new Notice(`Imported ${added} new, skipped ${skipped} already in the library.`);
  }

  private refToRowValues(cols: readonly { name: string; type: string }[], ref: ImportedRef): Record<string, string> {
    const values: Record<string, string> = {};
    const put = (field: AcademicField, value: string): void => {
      if (value.trim() === "") return;
      const col = this.fieldCol(cols, field);
      if (col) values[col.name] = value;
    };
    put("citekey", ref.citeKey);
    put("authors", ref.authors);
    put("year", ref.year);
    put("title", ref.title);
    put("venue", ref.venue);
    put("doi", ref.doi);
    put("tags", ref.tags);
    put("summary", ref.abstract);
    return values;
  }

  private shardName(value: string): string {
    const clean = value.trim().replace(/[\\/:*?"<>|#^[\]]/g, " ").replace(/\s+/g, " ").trim();
    return clean === "" ? "Unfiled" : clean.slice(0, 60);
  }

  async openShardModal(): Promise<void> {
    const profile = this.host.currentProfile();
    if (!profile) {
      new Notice("Open a Knowledge View first.");
      return;
    }
    if (profile.scope.mode !== "folders" || (profile.scope.folders[0] ?? "") === "") {
      new Notice("Sharding needs a view scoped to a folder.");
      return;
    }
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, { search: this.host.search() });
    if (result.rows.length === 0) {
      new Notice("This view has no rows to shard.");
      return;
    }
    const fields: ShardField[] = [];
    for (const col of cols) {
      if (col.type === "markdown" || col.type === "authors") continue; // long/compound fields make poor keys
      const buckets = new Set<string>();
      for (const row of result.rows) buckets.add(this.shardName(getField(row, col.name)));
      if (buckets.size > 1 && buckets.size <= result.rows.length) {
        fields.push({ name: col.name, buckets: buckets.size, sample: [...buckets].sort() });
      }
    }
    if (fields.length === 0) {
      new Notice("No field splits these rows into multiple files.");
      return;
    }
    fields.sort((a, b) => a.buckets - b.buckets);
    new ShardModal(this.host.app, fields, (field) => void this.shardBy(field, profile)).open();
  }

  private async shardBy(field: string, profile: Profile): Promise<void> {
    const cols = (this.host.renderedProfile() ?? profile).columns;
    const folder = profile.scope.folders[0] ?? "";
    const result = await this.host.deps.dataService.query({ ...profile, pageSize: null }, { search: this.host.search() });

    const buckets = new Map<string, Row[]>();
    for (const row of result.rows) {
      const name = this.shardName(getField(row, field));
      const list = buckets.get(name) ?? buckets.set(name, []).get(name)!;
      list.push(row);
    }
    if (buckets.size <= 1) {
      new Notice("Every row shares one value for that field — nothing to split.");
      return;
    }

    const headers = cols.map((c) => c.name);
    const sourcePaths = [...new Set(result.rows.map((r) => r.provenance.filePath))];
    const snapshot = await this.host.deps.writer.snapshot(sourcePaths);
    const notice = new Notice(`Sharding ${result.rows.length} rows into ${buckets.size} files…`, 0);

    const createdShards: string[] = [];
    const removeShards = async (): Promise<void> => {
      for (const shard of createdShards) {
        const f = this.host.app.vault.getAbstractFileByPath(shard);
        if (f) await this.host.app.fileManager.trashFile(f);
      }
    };
    try {
      for (const [bucket, rows] of buckets) {
        let path = `${folder}/${bucket}.md`;
        for (let n = 2; this.host.app.vault.getAbstractFileByPath(path) || sourcePaths.includes(path); n++) {
          path = `${folder}/${bucket} (${n}).md`;
        }
        await this.host.app.vault.create(path, this.shardNote(bucket, field, headers, rows));
        createdShards.push(path);
      }

      const byFile = new Map<string, RowProvenance[]>();
      for (const row of result.rows) {
        const list = byFile.get(row.provenance.filePath) ?? byFile.set(row.provenance.filePath, []).get(row.provenance.filePath)!;
        list.push(row.provenance);
      }
      for (const [, provs] of byFile) await this.host.deps.writer.deleteRows(provs);

      this.host.deps.undo.push({
        label: `Shard by ${field}`,
        undo: async () => {
          await this.host.deps.writer.restore(snapshot);
          await removeShards();
          for (const p of sourcePaths) this.host.deps.dataService.invalidate(p);
          void this.host.renderActive();
        },
      });
      for (const p of [...sourcePaths, ...createdShards]) this.host.deps.dataService.invalidate(p);
      void this.host.renderActive();
      notice.hide();
      new Notice(`Sharded into ${buckets.size} files by ${field}.`);
    } catch (error) {
      notice.hide();
      console.error("[KVS] Sharding failed:", error);
      await this.host.deps.writer.restore(snapshot);
      await removeShards();
      void this.host.renderActive();
      new Notice("Couldn't shard the library (check that the vault is writable).");
    }
  }

  private shardNote(bucket: string, field: string, headers: readonly string[], rows: readonly Row[]): string {
    const lines = rows.map((row) => `| ${headers.map((h) => escapeTableCell(getField(row, h))).join(" | ")} |`);
    return [
      `# ${bucket}`,
      "",
      `${rows.length} paper(s) · ${field} = ${bucket}`,
      "",
      `| ${headers.join(" | ")} |`,
      `| ${headers.map(() => "---").join(" | ")} |`,
      ...lines,
      "",
    ].join("\n");
  }
}
