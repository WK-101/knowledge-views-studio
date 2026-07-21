import { defaultWideWidth } from "../views/view-model";
import { Menu, Notice, requestUrl, setIcon, setTooltip, TextFileView, type WorkspaceLeaf, TFile, MarkdownView } from "obsidian";
import { extractImageEmbeds } from "../util/markdown";
import { openSearchView } from "./search-view";
import {

  NO_VALUE_OPERATORS,
  OPERATOR_LABELS,
  type ColumnConfig,
  type FilterCombinator,
  type FilterCondition,
  type FilterGroup,
  type FilterOperator,
  type PageInfo,
  type Row,
  type SortKey,
  getField,
  isVirtualField,
  validateExpression,
  DEFAULT_SCOPE,
  TABLE_EXTRACTOR_ID,
  resolveRowDefaults,
  type AcademicField,
} from "../domain/index";
import { splitList } from "../domain/columns/types/list";
import { splitTags } from "../domain/columns/types/tags";
import {
  buildCsv,
  buildDocx,
  buildExportTable,
  buildBibtex,
  buildBibliography,
  rowToReference,
  renderPromotedNote,
  DEFAULT_PROMOTED_TEMPLATE,
  hasRenderableMarkdown,
  parseCellBlocks,
  type Block,
  type ExportColumn,
  type ExportTable,
  buildMarkdownTable,
  buildPrintHtml,
  embedViewComment,
  type EmbeddedView,
  buildXlsx,
  createProfile,
  composeLayout,
  splitViewPatch,
  layoutFromProfile,
  layoutTypeLabel,
  normalizeLayout,
  serializeViewFile,
  serializeViewDoc,
  parseViewDoc,
  KVS_VIEW_EXTENSION,
  type Layout,
  type Profile,
  WriteScheduler,
  type SaveStatus,
} from "../services/index";
import { dedicatedNoteKeyFor, findDedicatedNote } from "../services/notes/dedicated-note";
import { PromotionService } from "../services/notes/promote-service";
import { noteBasename, withSourceBacklink } from "../services/notes/promotion-plan";
import { promotedNotesEnabled, resolveNoteLinkColumn } from "../views/promoted-detect";
import { exportViewBackup } from "./backup-runner";
import {
  buildClipboardFor,
  buildViewBlock,
  computeColumnChoices,
  suggestedColumns,
  resolveColumns,
  openRowDetail,
  writeClipboard,
  type CopyFormat,
  createEditingHandlers,
  optBool,
  optString,
  renderProfile,
  type ColumnChoice,
  type EditingHandlers,
  forgetViewState,
} from "../views/index";
import { moveItem, operatorsForType } from "../settings/builders";
import { ProfileEditorModal } from "../settings/profile-editor-modal";
import { closePopover, enableRowDrag, openPopover } from "./popover";
import { ExportOptionsModal, type ExportRequest } from "./export-modal";
import { ViewSwitcherModal } from "./view-switcher-modal";
import { ImportModal } from "./import-modal";
import type { ImportedRef } from "../services/index";
import { AcademicController } from "./academic-controller";
import { ViewBrowserModal } from "./view-browser-modal";
import { BackupExportModal, type BackupExportOptions } from "./backup-export-modal";
import type { ProcessorDeps } from "../codeblock/processor";
import { wireImageZoom } from "../views/image-zoom";
import { CaptureService } from "../services/capture/capture-service";
import { captureColumnsFor } from "./capture-command";
import { effectiveTarget } from "../services/capture/parse";
import type { CaptureTarget } from "../services/capture/types";

export const DASHBOARD_VIEW_TYPE = "kvs-dashboard";

/**
 * A dedicated pane that renders a saved view with an in-pane toolbar modelled on
 * the Obsidian Bases toolbar: a view-switcher menu (with layouts), and Filter,
 * Sort, Properties, results, and quick Search controls as native icon buttons.
 * Every change but Search is persisted to the saved view.
 */
const IMAGE_MIME: Record<string, string> = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
  svg: "image/svg+xml",
  bmp: "image/bmp",
  avif: "image/avif",
};

function extFromPath(path: string): string {
  const clean = path.split(/[?#]/)[0] ?? path;
  const dot = clean.lastIndexOf(".");
  return dot >= 0 ? clean.slice(dot + 1).toLowerCase() : "";
}

function bytesToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function dataUrlFromBytes(buffer: ArrayBuffer, ext: string): string | null {
  const mime = IMAGE_MIME[ext.toLowerCase()];
  if (!mime) return null;
  return `data:${mime};base64,${bytesToBase64(buffer)}`;
}



export class DashboardView extends TextFileView {
  private profileId: string | null;
  private readonly academic: AcademicController;
  private toolbarEl: HTMLElement | null = null;
  private hintEl: HTMLElement | null = null;
  private focusExitEl: HTMLElement | null = null;
  private focusMode = false;
  private bodyEl: HTMLElement | null = null;
  private pagerEl: HTMLElement | null = null;
  private editing?: EditingHandlers;

  // File mode: when this view is backed by a saved `.kvsview` file it holds that file's views
  // (tabs) and the active one, all independent of the plugin's stored views and persisted back
  // to the file. A file may contain several views, like an Obsidian Base.
  private fileMode = false;
  private fileViews: Profile[] = [];
  private activeViewId: string | null = null;
  private activeLayoutId: string | null = null;
  private renamingLayoutId: string | null = null;
  private renamingId: string | null = null;
  private fileData = "";

  private search = "";
  private page = 0;
  private renderSeq = 0;
  private searchTimer: number | null = null;
  private lastRows: Row[] = [];
  private lastSig = "";
  private lastResultSig = "";

  private resultsEl: HTMLElement | null = null;
  private filterBtn: HTMLElement | null = null;
  private sortBtn: HTMLElement | null = null;
  private propsBtn: HTMLElement | null = null;
  private viewMenuDisplayOpen = false;
  private writeScheduler: WriteScheduler | null = null;
  private saveStatusEl: HTMLElement | null = null;
  private saveStatusTimer: number | null = null;
  private saveState: SaveStatus = "idle";
  private lastMarkdownView: MarkdownView | null = null;

  /** Distinct existing values for a column across loaded rows (splitting list columns) — for
   *  autocomplete. Complete for un-paginated views; a page-sized sample for paginated ones. */
  private columnValues(name: string): readonly string[] {
    const col = (this.renderedProfile() ?? this.currentProfile())?.columns.find((c) => c.name.toLowerCase() === name.toLowerCase());
    const isList = col?.type === "list";
    const seen = new Set<string>();
    for (const row of this.lastRows) {
      const raw = getField(row, name).trim();
      if (raw === "") continue;
      if (isList) for (const item of splitList(raw)) seen.add(item);
      else seen.add(raw);
    }
    return [...seen].sort((a, b) => a.localeCompare(b));
  }

  /** Insert a Pandoc citation into the last-edited note, or copy it if no note is open. */
  private insertCitation(citeKey: string): void {
    const cite = `[@${citeKey.replace(/^@/, "")}]`;
    const md = this.lastMarkdownView;
    const stillOpen = md !== null && this.app.workspace.getLeavesOfType("markdown").some((l) => l.view === md);
    if (stillOpen && md.editor) {
      md.editor.replaceSelection(cite);
      void this.app.workspace.revealLeaf(md.leaf);
      new Notice(`Inserted ${cite} into “${md.file?.basename ?? "note"}”`);
    } else {
      void navigator.clipboard.writeText(cite);
      new Notice(`${cite} copied — open a note to insert it directly.`);
    }
  }

  /** Force a full reload: drop the data cache (re-read notes from disk) + the citation index. */
  private forceRefresh(): void {
    this.deps.dataService.clearCache();
    this.academic.resetCiteIndex();
    void this.renderActive();
    new Notice("Refreshed from disk.");
  }

  /** Whether references can be imported into the active view (a kit view with existing rows). */
  hasImportTarget(): boolean {
    return this.lastRows.length > 0 && (this.currentProfile()?.academicKit ?? false);
  }

  // The academic-research commands are wired from main.ts against the view; the view forwards each to the
  // controller that now owns the logic. Thin by design — the view stays the stable command surface, and
  // the kit stays encapsulated behind `this.academic`.
  bulkFillFromDoi(): Promise<void> {
    return this.academic.bulkFillFromDoi();
  }
  captureByDoi(dois: readonly string[]): Promise<void> {
    return this.academic.captureByDoi(dois);
  }
  findDuplicateDois(): Promise<void> {
    return this.academic.findDuplicateDois();
  }
  findCitationLinks(): Promise<void> {
    return this.academic.findCitationLinks();
  }
  importReferences(refs: readonly ImportedRef[]): Promise<void> {
    return this.academic.importReferences(refs);
  }
  openShardModal(): Promise<void> {
    return this.academic.openShardModal();
  }

  /** Append a blank row and immediately open its card, so adding a paper never means scrolling to the
   *  bottom to find the new row. */
  /** Provenance of the table a new row should go into: the per-view target file's first row if that
   *  file has rows here, else the supplied fallback (the clicked row, or the first row). */
  private appendTargetFor(fallback: Row["provenance"]): Row["provenance"] {
    const target = (this.currentProfile()?.newRowFile ?? "").trim();
    if (target === "") return fallback;
    const inTarget = this.lastRows.find((r) => r.provenance.filePath === target);
    return inTarget ? inTarget.provenance : fallback;
  }

  private async addRowAndEdit(): Promise<void> {
    const base = this.renderedProfile();
    if (!base) return;

    // If the view has a capture destination configured — a fixed note, or a daily/weekly/monthly periodic
    // note — add the row through the capture pipeline. That's what lets "Add row" create the note and the
    // table when they don't exist yet (today's daily note, say), rather than needing an existing row to
    // anchor against. This is the destination the user chose in the view's Capture settings.
    const target = effectiveTarget(base);
    if (
      target !== null &&
      target.shape === "row" &&
      (target.destination === "periodic" || (target.notePath ?? "").trim() !== "")
    ) {
      await this.addRowViaCapture(base, target);
      return;
    }

    // Fallback for a view with no capture destination: anchor a blank row against an existing row's table.
    const fallback = this.lastRows[0]?.provenance;
    if (!fallback) {
      new Notice("This view has no rows yet — add one from a template, or capture papers by DOI.");
      return;
    }
    const reference = this.appendTargetFor(fallback);
    const snapshot = await this.deps.writer.snapshot([reference.filePath]);
    const res = await this.deps.writer.appendRow(reference, {});
    if (!res.ok) {
      new Notice(`Couldn't add a row: ${res.reason ?? "unknown error"}`);
      return;
    }
    this.deps.undo.push({
      label: "Add row",
      undo: async () => {
        await this.deps.writer.restore(snapshot);
        this.deps.dataService.invalidate(reference.filePath);
        void this.renderActive();
      },
    });
    this.deps.dataService.invalidate(reference.filePath);
    await this.revealNewRowIn(base, reference.filePath);
  }

  /**
   * Add a blank row through the capture pipeline, then open it.
   *
   * Unlike the anchored path this can address a table that's empty or not yet written — and, for a periodic
   * destination, create today's (or this week's / month's) note first — so the first item can arrive before
   * any table has been typed by hand. Undo restores the target file's prior contents (an empty note if we
   * had to create it).
   */
  private async addRowViaCapture(base: Profile, target: CaptureTarget): Promise<void> {
    const service = new CaptureService(this.app);
    const path = service.targetPath(target);
    if (path === "" || path === ".") {
      new Notice("This view's capture destination isn't fully set — check the view's Capture settings.");
      return;
    }

    const before = await this.deps.dataService.query({ ...base, pageSize: null }, {});
    const columns = captureColumnsFor(base, before.rows);
    const snapshot = await this.deps.writer.snapshot([path]);

    const res = await service.commit(target, {}, columns, { fields: [] });
    if (!res.ok) {
      new Notice(`Couldn't add a row: ${res.reason ?? "unknown error"}`);
      return;
    }
    const writtenPath = res.path ?? path;
    this.deps.undo.push({
      label: "Add row",
      undo: async () => {
        await this.deps.writer.restore(snapshot);
        this.deps.dataService.invalidate(writtenPath);
        void this.renderActive();
      },
    });
    this.deps.dataService.invalidate(writtenPath);
    await this.revealNewRowIn(base, writtenPath);
  }

  /** Re-query, find the newest row in a file, re-render, and open its detail card (the shared tail of both
   *  add-row paths). */
  private async revealNewRowIn(base: Profile, filePath: string): Promise<void> {
    const result = await this.deps.dataService.query({ ...base, pageSize: null }, {});
    const inFile = result.rows.filter((r) => r.provenance.filePath === filePath);
    const line = (r: Row): number => Number(r.provenance.locator.line ?? 0);
    const newRow = inFile.length > 0 ? inFile.reduce((a, b) => (line(b) > line(a) ? b : a)) : undefined;
    await this.renderActive();
    if (!newRow) return;
    openRowDetail(
      {
        app: this.app,
        cellRenderers: this.deps.cellRenderers,
        sourcePath: this.file?.path ?? "",
        component: this,
        columns: resolveColumns(base, result.rows),
        onEditCell: (row, column, value) => this.editing?.onEditCell(row, column, value),
        columnValues: (name) => this.columnValues(name),
        ...(base.academicKit ? { onFetchDoiValues: (doi: string) => this.academic.fetchDoiValues(doi) } : {}),
        ...(base.academicKit ? { onFindCitations: (doi: string) => this.academic.findCitationsFor(doi) } : {}),
      },
      newRow,
    );
  }

  /** For each library paper with a DOI, find which other library papers it cites (via OpenAlex) and
   *  record them as [[cite-key]] links in a "Cites" column — real, traversable graph edges. */
  /** Cached library resolution: OpenAlex id → cite key for the papers in this view. Rebuilt on force
   *  or after 5 minutes, so per-paper "Find citations" doesn't re-resolve the whole library each time. */

  /** Promote a library row to a dedicated note (pre-seeded with metadata + a Findings table). */
  private async promoteToNote(row: Row, profile: Profile): Promise<void> {
    // Non-academic views promote through the general service — a cleaner note template, the configurable
    // link column, and the two-way source backlink — rather than the paper-shaped academic flow below.
    if (!profile.academicKit) {
      await this.promoteGeneral(row, profile);
      return;
    }
    const cols = (this.renderedProfile() ?? profile).columns;
    const val = (field: AcademicField): string => {
      const col = this.academic.fieldCol(cols, field);
      return col ? getField(row, col.name).trim() : "";
    };
    const key = val("citekey");
    const title = val("title");
    const authors = val("authors");
    const year = val("year");
    const venue = val("venue");
    const doi = val("doi");
    const tagCol = this.academic.fieldCol(cols, "tags");
    const tags = tagCol ? splitTags(getField(row, tagCol.name)) : [];

    const base = (key || title || "Paper").replace(/[\\/:*?"<>|#^[\]]/g, "").trim() || "Paper";
    const configured = (profile.promotedNotesFolder ?? "").trim().replace(/\/+$/, "");
    const scopeFolder = profile.scope.mode === "folders" ? profile.scope.folders[0] ?? "" : "";
    const dir = configured !== "" ? configured : scopeFolder ? `${scopeFolder}/Papers` : "Papers";
    // The link column: the view's explicit choice, else the auto-detected "Note"/link column.
    const noteColName = resolveNoteLinkColumn(profile.noteLinkColumn, cols.map((c) => ({ name: c.name, type: c.type })));
    const noteCol = noteColName !== null ? cols.find((c) => c.name === noteColName) ?? null : null;

    // Already promoted? Look for the dedicated note two ways, most robust first:
    //  1. By a stable identifier in the note's frontmatter (the DOI for academic views) — finds the note
    //     wherever it lives and whatever it's called, and stops "promote" making duplicates.
    //  2. By a [[wikilink]] recorded in the row's Note column (the older behaviour, kept as a fallback).
    const matchKey = dedicatedNoteKeyFor(profile);
    const matchValue =
      matchKey === "doi"
        ? doi
        : matchKey !== ""
          ? (() => {
              const c = cols.find((col) => col.name.toLowerCase() === matchKey.toLowerCase());
              return c ? getField(row, c.name).trim() : "";
            })()
          : "";
    if (matchKey !== "" && matchValue !== "") {
      const existing = findDedicatedNote(this.app, matchKey, matchValue);
      if (existing) {
        await this.app.workspace.getLeaf(true).openFile(existing);
        // Backfill the row's link column if it's empty, so the ↗ indicator and future opens are instant.
        if (noteCol && getField(row, noteCol.name).trim() === "") {
          const nm = existing.path.replace(/\.md$/, "").split("/").pop() ?? existing.basename;
          await this.applyRowEdits(row.provenance.filePath, [{ provenance: row.provenance, column: noteCol.name, value: `[[${nm}]]` }], "Link paper note");
        }
        return;
      }
    }

    const existingLink = noteCol ? getField(row, noteCol.name).trim() : "";
    const linkTarget = /\[\[([^\]|]+)/.exec(existingLink)?.[1]?.trim();
    if (linkTarget) {
      const found = this.app.metadataCache.getFirstLinkpathDest(linkTarget, row.provenance.filePath);
      if (found) {
        await this.app.workspace.getLeaf(true).openFile(found);
        return;
      }
    }

    try {
      // Create the folder (and any missing parents) so a deep path like "A/B/Papers" works.
      const parts = dir.split("/").filter((p) => p !== "");
      let acc = "";
      for (const part of parts) {
        acc = acc === "" ? part : `${acc}/${part}`;
        if (!this.app.vault.getAbstractFileByPath(acc)) await this.app.vault.createFolder(acc);
      }
      let notePath = `${dir}/${base}.md`;
      for (let n = 2; this.app.vault.getAbstractFileByPath(notePath); n++) notePath = `${dir}/${base} ${n}.md`;
      const noteName = notePath.slice(dir.length + 1, -3);

      // One template for every promoted note — whether or not the paper is in Zotero — so they look the
      // same. When the DOI is in Zotero, we enrich: prefer its richer metadata and fill the Abstract,
      // Annotations, and zotero-key. Otherwise those fields are empty and the structure is identical.
      const enrich = doi !== "" ? await this.academic.zoteroPromoteEnrichment(doi) : null;
      const template = (profile.promotedNoteTemplate ?? "").trim() || this.deps.store.getSettings().promotedNoteTemplate.trim() || DEFAULT_PROMOTED_TEMPLATE;
      const fields = {
        title: enrich?.item.title || title,
        authors: enrich?.item.creators || authors,
        year: enrich?.item.year || year,
        venue: enrich?.item.publication || venue,
        doi: enrich?.item.doi || doi,
        citekey: enrich?.citeKey || key,
        tags: enrich && enrich.item.tags.length > 0 ? enrich.item.tags : tags,
        abstract: enrich?.abstract ?? "",
        annotations: enrich?.annotations ?? "",
        zoteroKey: enrich?.zoteroKey ?? "",
      };
      let noteContent = renderPromotedNote(template, fields);
      // Two-way graph link: a `[[source]]` backlink inside the note when the source is a markdown note.
      if (profile.backlinkToSource !== false && /\.md$/i.test(row.provenance.filePath)) {
        noteContent = withSourceBacklink(noteContent, noteBasename(row.provenance.filePath));
      }
      await this.app.vault.create(notePath, noteContent);

      // Link the row back to its new note (fill the Note column if present).
      if (noteCol && getField(row, noteCol.name).trim() === "") {
        const applied = await this.applyRowEdits(row.provenance.filePath, [{ provenance: row.provenance, column: noteCol.name, value: `[[${noteName}]]` }], "Link paper note");
        if (applied === 0) {
          new Notice(`Note created, but couldn't write the link into the “${noteCol.name}” column — check that column exists in the table.`);
        }
      } else if (!noteCol) {
        new Notice("Note created, but this view has no link column to record it (add a “Note” link column to see the ↗ indicator).");
      }
      const file = this.app.vault.getAbstractFileByPath(notePath);
      if (file instanceof TFile) await this.app.workspace.getLeaf(true).openFile(file);
      new Notice(`Created “${noteName}”.`);
    } catch (error) {
      console.error("[KVS] Promote to note failed:", error);
      new Notice("Couldn't create the note (check that the vault is writable).");
    }
  }

  /**
   * Promote a row in a non-academic view through the general service: a web-shaped note template, the
   * configurable dedicated-note key and wikilink column, and the two-way source backlink. Idempotent — a
   * second promote opens the note the first one made.
   */
  private async promoteGeneral(row: Row, profile: Profile): Promise<void> {
    const cols = (this.renderedProfile() ?? profile).columns;
    const scopeFolder = profile.scope.mode === "folders" ? profile.scope.folders[0] ?? "" : "";
    const service = new PromotionService({
      app: this.app,
      editCell: async (r, column, value) => {
        await this.applyRowEdits(
          r.provenance.filePath,
          [{ provenance: r.provenance, column, value }],
          "Link note",
        );
      },
    });
    try {
      const result = await service.promote(
        {
          academicKit: false,
          ...(profile.dedicatedNoteKey !== undefined ? { dedicatedNoteKey: profile.dedicatedNoteKey } : {}),
          ...(profile.promotedNotesFolder !== undefined ? { promotedNotesFolder: profile.promotedNotesFolder } : {}),
          ...(profile.promotedNoteTemplate !== undefined ? { promotedNoteTemplate: profile.promotedNoteTemplate } : {}),
          ...(profile.noteLinkColumn !== undefined ? { noteLinkColumn: profile.noteLinkColumn } : {}),
          ...(profile.backlinkToSource !== undefined ? { backlinkToSource: profile.backlinkToSource } : {}),
          ...(scopeFolder !== "" ? { scopeFolder } : {}),
        },
        row,
        cols.map((c) => ({ name: c.name, type: c.type })),
      );
      if (!result.ok) {
        new Notice(result.reason ?? "Couldn't create the note.");
        return;
      }
      if (result.path !== undefined) {
        const file = this.app.vault.getAbstractFileByPath(result.path);
        if (file instanceof TFile) await this.app.workspace.getLeaf(true).openFile(file);
      }
      new Notice(result.created === false ? "Opened the existing note." : "Created the dedicated note.");
    } catch (error) {
      console.error("[KVS] Promote to note failed:", error);
      new Notice("Couldn't create the note (check that the vault is writable).");
    }
  }

  /** Write a batch of cell edits to one file, with undo + refresh (used by DOI fill / promote). */
  private async applyRowEdits(
    path: string,
    edits: readonly { provenance: Row["provenance"]; column: string; value: string }[],
    label: string,
  ): Promise<number> {
    const snapshot = await this.deps.writer.snapshot([path]);
    const result = await this.deps.writer.editCells(edits);
    if (result.applied > 0) {
      this.deps.undo.push({
        label,
        undo: async () => {
          await this.deps.writer.restore(snapshot);
          this.deps.dataService.invalidate(path);
          void this.renderActive();
        },
      });
    }
    this.deps.dataService.invalidate(path);
    void this.renderActive();
    return result.applied;
  }

  constructor(
    leaf: WorkspaceLeaf,
    private readonly deps: ProcessorDeps,
  ) {
    super(leaf);
    this.allowNoFile = true; // store-backed dashboard opens with no file
    this.profileId = deps.store.getActiveProfileId();
    // The academic-research kit lives in its own class now (see AcademicController). The view hands it a
    // narrow window onto the live state it needs — profiles, rows, search, and the shared write path —
    // and delegates the kit's commands to it. Everything academic that used to sprawl through this file
    // is reached through `this.academic`.
    this.academic = new AcademicController({
      app: this.app,
      deps: this.deps,
      renderedProfile: () => this.renderedProfile(),
      currentProfile: () => this.currentProfile(),
      lastRows: () => this.lastRows,
      search: () => this.search,
      renderActive: () => void this.renderActive(),
      applyRowEdits: (path, edits, label) => this.applyRowEdits(path, edits, label),
      appendTargetFor: (fallback) => this.appendTargetFor(fallback),
    });
  }

  override getViewType(): string {
    return DASHBOARD_VIEW_TYPE;
  }
  override getDisplayText(): string {
    if (this.fileMode) return this.file?.basename ?? this.currentProfile()?.name ?? "Knowledge View";
    return "Knowledge Views";
  }
  override getIcon(): string {
    return "layout-grid";
  }

  override canAcceptExtension(extension: string): boolean {
    return extension === KVS_VIEW_EXTENSION;
  }

  /** TextFileView: serialize all views (tabs) so edits persist to disk. */
  override getViewData(): string {
    if (this.fileViews.length === 0) return this.fileData;
    return serializeViewDoc({ views: this.fileViews, activeView: this.activeViewId ?? this.fileViews[0]!.id });
  }

  /** TextFileView: a `.kvsview` file was loaded — parse its views and render the dashboard. */
  override setViewData(data: string, _clear: boolean): void {
    this.fileMode = true;
    this.fileData = data;
    const doc = parseViewDoc(data);
    this.fileViews = doc ? [...doc.views] : [];
    this.activeViewId = doc?.activeView ?? this.fileViews[0]?.id ?? null;
    this.renamingId = null;
    this.page = 0;
    this.search = "";
    if (this.toolbarEl) {
      this.buildToolbar();
      void this.renderActive();
    }
  }

  /** TextFileView: clear before a different file loads. */
  override clear(): void {
    this.fileViews = [];
    this.activeViewId = null;
    this.renamingId = null;
    this.fileData = "";
    this.bodyEl?.empty();
  }

  override async onOpen(): Promise<void> {
    const root = this.contentEl;
    // The dashboard is a *pane*, and a pane is not the window: a 4K monitor can hold this view in a 380px
    // sidebar split, and a phone can hold it full-bleed. Window media queries answer the wrong question.
    // Naming the root a container query context lets the toolbar and layouts respond to the width they
    // actually have — which fixes splits and pop-outs on desktop, not only phones. Fully supported since
    // the plugin's floor (Obsidian 1.10 / its Chromium) ships container queries.
    root.addClass("kvs-cq-root");
    root.empty();
    root.addClass("kvs-dashboard");

    this.toolbarEl = root.createDiv({ cls: "kvs-toolbar-bar" });
    this.hintEl = root.createDiv({ cls: "kvs-hint-host" });
    this.bodyEl = root.createDiv({ cls: "kvs-dashboard-body" });

    // Floating "exit focus" control, shown only in focus mode (chrome hidden, view maximised).
    this.focusExitEl = root.createDiv({ cls: "kvs-focus-exit" });
    this.focusExitEl.hide();
    const exitBtn = this.focusExitEl.createEl("button", { cls: "kvs-focus-exit-btn" });
    setIcon(exitBtn.createSpan({ cls: "kvs-focus-exit-ic" }), "minimize-2");
    exitBtn.createSpan({ text: "Exit focus" });
    setTooltip(exitBtn, "Exit focus mode");
    exitBtn.addEventListener("click", () => this.toggleFocusMode());
    wireImageZoom(root);

    // Remember the last note the user was editing, so citation actions can insert into it.
    this.registerEvent(
      this.app.workspace.on("active-leaf-change", (leaf) => {
        if (leaf?.view instanceof MarkdownView) this.lastMarkdownView = leaf.view;
      }),
    );

    if (this.deps.store.getSettings().inlineEditing) {
      this.writeScheduler = new WriteScheduler({
        writer: this.deps.writer,
        invalidate: (path) => this.deps.dataService.invalidate(path),
        undo: this.deps.undo,
        rerender: () => void this.renderActive(),
        onStatus: (status) => this.showSaveStatus(status),
        notify: (message) => new Notice(message),
      });
      this.editing = createEditingHandlers(
        this.deps,
        () => void this.renderActive(),
        () => resolveRowDefaults(this.renderedProfile()?.columns ?? []),
        (row, column, value) => this.writeScheduler?.queue(row.provenance, column, value),
        (clicked) => this.appendTargetFor(clicked.provenance),
      );
    }

    this.register(
      this.deps.dataService.onChange((change) => {
        const profile = this.currentProfile();
        if (profile && change.paths.some((p) => this.deps.dataService.affectsScope(p, profile.scope))) {
          void this.renderActive();
        }
      }),
    );
    this.register(this.deps.store.onChange(() => this.onStoreChange()));

    if (this.fileMode) {
      // A `.kvsview` file's views already arrived via setViewData (before onOpen) — the DOM
      // exists now, so paint it.
      this.buildToolbar();
      await this.renderActive();
      return;
    }
    // If a file is loading, its profile arrives via setViewData (after onOpen) which paints —
    // skip the store-backed initial paint so we don't briefly flash the wrong view.
    const state = this.leaf.getViewState().state as { file?: unknown } | undefined;
    if (typeof state?.file !== "string") {
      const initial = this.currentProfile();
      this.lastResultSig = initial ? this.resultSig(initial) : "";
      this.buildToolbar();
      await this.renderActive();
    }
  }

  override async onClose(): Promise<void> {
    closePopover();
    if (this.saveStatusTimer !== null) window.clearTimeout(this.saveStatusTimer);
    await this.writeScheduler?.flushNow(); // don't lose edits that haven't been written yet
  }

  // ---- profile helpers ----
  /** Whether any derived column is configured to write itself back to the source. */
  private hasMaterializeTargets(): boolean {
    const profile = this.currentProfile();
    if (!profile) return false;
    return profile.rollups.some((r) => r.materializeTo) || profile.computed.some((c) => c.materializeTo);
  }

  /**
   * Write every derived column that has a "write to source" target into its target
   * source-table column, across all matching rows. Snapshots first for undo, edits
   * only existing columns (a missing target is reported, never invented), and never
   * runs automatically — the user triggers it, so there is no write storm.
   */
  private materializeDerived(): void {
    void (async () => {
      const profile = this.currentProfile();
      if (!profile) return;
      const targets = [
        ...profile.rollups.filter((r) => r.materializeTo).map((r) => ({ field: r.name, to: r.materializeTo as string })),
        ...profile.computed.filter((c) => c.materializeTo).map((c) => ({ field: c.name, to: c.materializeTo as string })),
      ];
      if (targets.length === 0) {
        new Notice('No derived columns are set to write to source. Set "Write to source column" on a rollup first.');
        return;
      }

      const result = await this.deps.dataService.query({ ...profile, pageSize: null }, { search: this.search });
      const rows = result.rows;
      const edits = rows.flatMap((row) =>
        targets.map((t) => ({ provenance: row.provenance, column: t.to, value: getField(row, t.field) })),
      );
      if (edits.length === 0) {
        new Notice("No rows to write.");
        return;
      }

      const paths = [...new Set(rows.map((row) => row.provenance.filePath))];
      const snapshot = await this.deps.writer.snapshot(paths);
      const res = await this.deps.writer.editCells(edits);
      if (res.applied > 0) {
        this.deps.undo.push({
          label: `Write rollups to source (${res.applied})`,
          undo: async () => {
            await this.deps.writer.restore(snapshot);
            for (const path of paths) this.deps.dataService.invalidate(path);
            void this.renderActive();
          },
        });
      }
      for (const path of paths) this.deps.dataService.invalidate(path);
      new Notice(
        res.failures.length > 0
          ? `Wrote ${res.applied} cell(s); ${res.failures.length} skipped (target column missing in those tables).`
          : `Wrote ${res.applied} cell(s) to source.`,
      );
      void this.renderActive();
    })();
  }

  private currentProfile(): Profile | undefined {
    if (this.fileMode) return this.fileViews.find((v) => v.id === this.activeViewId) ?? this.fileViews[0];
    if (this.profileId) return this.deps.store.getProfile(this.profileId);
    return this.deps.store.listProfiles()[0];
  }

  /** A quiet "Saving…/Saved" indicator so background writes feel tangible. */
  private showSaveStatus(status: SaveStatus): void {
    this.saveState = status;
    this.renderSaveStatus();
    if (this.saveStatusTimer !== null) window.clearTimeout(this.saveStatusTimer);
    if (status === "saved" || status === "error") {
      this.saveStatusTimer = window.setTimeout(() => {
        this.saveState = "idle";
        this.renderSaveStatus();
      }, 1800);
    }
  }

  private renderSaveStatus(): void {
    const el = this.saveStatusEl;
    if (!el) return;
    el.empty();
    el.toggleClass("is-visible", this.saveState !== "idle");
    el.toggleClass("is-error", this.saveState === "error");
    if (this.saveState === "idle") return;
    const icon = this.saveState === "saving" ? "loader" : this.saveState === "saved" ? "check" : "alert-triangle";
    setIcon(el.createSpan({ cls: "kvs-save-ic" }), icon);
    el.createSpan({ text: this.saveState === "saving" ? "Saving…" : this.saveState === "saved" ? "Saved" : "Not saved" });
  }

  /** The per-view state key for a view+layout — unique per view (id) and layout, in both modes. */
  private viewKeyFor(viewId: string, layoutId: string): string {
    return this.fileMode && this.file
      ? `file:${this.file.path}:${viewId}:${layoutId}`
      : `dashboard:${viewId}:${layoutId}`;
  }

  /** The key prefix covering every layout of a view — used to forget its state on deletion. */
  private viewKeyPrefixFor(viewId: string): string {
    return this.fileMode && this.file ? `file:${this.file.path}:${viewId}:` : `dashboard:${viewId}:`;
  }

  /** The active layout of the current view, or null when the view has no explicit layouts (legacy). */
  private currentLayout(): Layout | null {
    const layouts = this.currentProfile()?.layouts;
    if (!layouts || layouts.length === 0) return null;
    return layouts.find((l) => l.id === this.activeLayoutId) ?? layouts[0]!;
  }

  /** The profile actually rendered: shared data + the active layout's presentation (or the view
   *  itself when it has no explicit layouts). This is what the toolbar/body operate on visually. */
  private renderedProfile(): Profile | undefined {
    const profile = this.currentProfile();
    if (!profile) return undefined;
    const layout = this.currentLayout();
    return layout ? composeLayout(profile, layout) : profile;
  }

  private patchActive(patch: Partial<Profile>): void {
    const profile = this.currentProfile();
    if (!profile) return;

    // In a multi-layout view, presentation edits land on the active layout; data edits (filter,
    // scope, columns, …) land on the shared view so every layout sees them.
    let effective: Partial<Profile> = patch;
    const layouts = profile.layouts;
    if (layouts && layouts.length > 0) {
      const { data, layout: layoutPatch } = splitViewPatch(patch);
      const activeId = this.currentLayout()?.id;
      const nextLayouts =
        Object.keys(layoutPatch).length > 0
          ? layouts.map((l) => (l.id === activeId ? normalizeLayout({ ...l, ...layoutPatch }) : l))
          : layouts;
      effective = { ...data, layouts: nextLayouts };
    }

    if (this.fileMode) {
      const active = this.currentProfile();
      if (!active) return;
      this.fileViews = this.fileViews.map((v) => (v.id === active.id ? { ...v, ...effective } : v));
      this.requestSave(); // persist the change back to the .kvsview file
      this.buildToolbar();
      void this.renderActive();
      return;
    }
    this.deps.store.patchProfile(profile.id, effective);
  }

  private editorDeps(): {
    store: ProcessorDeps["store"];
    views: ProcessorDeps["views"];
    registry: ProcessorDeps["registry"];
    dataService: ProcessorDeps["dataService"];
  } {
    return {
      store: this.deps.store,
      views: this.deps.views,
      registry: this.deps.registry,
      dataService: this.deps.dataService,
    };
  }

  private choices(): ColumnChoice[] {
    const profile = this.renderedProfile();
    return profile ? computeColumnChoices(profile, this.lastRows) : [];
  }

  private iconForView(type: string): string {
    return this.deps.views.get(type)?.icon ?? "table";
  }

  private toolbarSig(): string {
    const profiles = this.deps.store.listProfiles();
    const active = this.currentProfile();
    const rendered = this.renderedProfile();
    const layoutSig = (active?.layouts ?? []).map((l) => `${l.id}:${l.name}:${l.view.type}`).join(",");
    return `${profiles.map((p) => `${p.id}:${p.name}`).join("|")}#${active?.id ?? ""}#${rendered?.view.type ?? ""}#${this.activeLayoutId ?? ""}#${layoutSig}`;
  }

  private resultSig(p: Profile): string {
    return JSON.stringify({
      scope: p.scope,
      filter: p.filter,
      advancedQuery: p.advancedQuery,
      sort: p.sort,
      group: p.group,
      pageSize: p.pageSize,
      columnMatch: p.columnMatch,
    });
  }

  private onStoreChange(): void {
    if (this.fileMode) return; // a file-backed view is independent of the stored views
    // Adopt an active view chosen elsewhere (e.g. "Create view from note"). The in-app view switcher
    // sets this.profileId and the store's active id together, so normally this is a no-op.
    const activeId = this.deps.store.getActiveProfileId();
    if (activeId && activeId !== this.profileId && this.deps.store.getProfile(activeId)) {
      this.profileId = activeId;
      this.buildToolbar();
    }
    const rendered = this.renderedProfile();
    const sig = rendered ? this.resultSig(rendered) : "";
    if (sig !== this.lastResultSig) {
      this.page = 0; // the row set changed — go back to the first page
      this.lastResultSig = sig;
    }
    if (this.toolbarSig() !== this.lastSig) this.buildToolbar();
    void this.renderActive();
  }

  // ---- toolbar ----
  private buildToolbar(): void {
    const bar = this.toolbarEl;
    if (!bar) return;
    closePopover();
    bar.empty();
    this.lastSig = this.toolbarSig();
    const active = this.currentProfile();
    const rendered = this.renderedProfile();

    // Left: in a view file, a Bases-style tab strip (one tab per view) lives here on the same
    // row as the controls. In the store-backed dashboard it's the view switcher instead.
    if (this.fileMode) {
      const tabs = bar.createDiv({ cls: "kvs-view-tabs" });
      this.buildTabs(tabs);
    } else {
      const switcher = bar.createDiv({ cls: "kvs-view-switcher" });
      switcher.setAttribute("tabindex", "0");
      switcher.setAttribute("role", "button");
      switcher.setAttribute("aria-haspopup", "menu");
      setIcon(switcher.createSpan({ cls: "kvs-view-icon" }), rendered ? this.iconForView(rendered.view.type) : "table");
      switcher.createSpan({ cls: "kvs-view-name", text: active?.name ?? "No views yet" });
      setIcon(switcher.createSpan({ cls: "kvs-view-chevron" }), "chevron-down");
      switcher.addEventListener("click", () => this.openViewMenu(switcher));
      switcher.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          this.openViewMenu(switcher);
        }
      });
    }
    this.renderLayoutTabs(bar); // inline layout tabs (no extra row)

    bar.createDiv({ cls: "kvs-tb-spacer" });

    const right = bar.createDiv({ cls: "kvs-tb-group" });

    const searchWrap = right.createDiv({ cls: "search-input-container kvs-search-wrap" });
    const searchInput = searchWrap.createEl("input", { cls: "kvs-search" });
    searchInput.type = "search";
    searchInput.placeholder = "Search…";
    searchInput.value = this.search;
    searchInput.disabled = !active;
    searchInput.addEventListener("input", () => {
      this.search = searchInput.value;
      this.page = 0;
      if (this.searchTimer !== null) window.clearTimeout(this.searchTimer);
      this.searchTimer = window.setTimeout(() => void this.renderActive(), 200);
    });

    const results = right.createEl("button", { cls: "kvs-results" });
    results.type = "button";
    results.disabled = !active;
    results.addEventListener("click", (event) => this.openResultsMenu(event));
    this.resultsEl = results;

    this.pagerEl = right.createSpan({ cls: "kvs-pager" });

    this.iconBtn(right, "text-search", "Search everything — notes, annotations & attachment full text (KVS)", () => void openSearchView(this.app), false);
    this.iconBtn(right, "undo-2", "Undo last change", () => void this.undoLast(), false);
    this.iconBtn(right, "refresh-cw", "Refresh (re-read notes from disk)", () => this.forceRefresh(), !active);
    this.filterBtn = this.iconBtn(right, "filter", "Filter", (a) => this.openFilterPopover(a), !active);
    this.sortBtn = this.iconBtn(right, "arrow-up-down", "Sort", (a) => this.openSortPopover(a), !active);
    this.propsBtn = this.iconBtn(right, "table-2", "Properties", (a) => this.openPropertiesPopover(a), !active);
    this.iconBtn(right, "maximize-2", "Focus mode (maximize the view)", () => this.toggleFocusMode(), !active);
    this.saveStatusEl = right.createDiv({ cls: "kvs-save-status" });
    // A silent status pill is invisible to a screen reader — the user edits a cell and gets no signal it
    // saved, or worse, no signal it failed. aria-live="polite" announces each transition without stealing
    // focus; role="status" is its matching landmark. "Not saved" is the one that must never pass silently.
    this.saveStatusEl.setAttribute("role", "status");
    this.saveStatusEl.setAttribute("aria-live", "polite");
    this.renderSaveStatus();
    const view = rendered ? this.deps.views.get(rendered.view.type) : undefined;
    if (view?.optionSpecs && view.optionSpecs.length > 0) {
      this.iconBtn(right, "settings-2", "View options", (a) => this.openViewOptionsPopover(a), false);
    }
    if (this.editing && active) {
      const addBtn = right.createEl("button", { cls: "clickable-icon kvs-tb-icon kvs-add-row-btn" });
      addBtn.type = "button";
      setIcon(addBtn, "list-plus");
      setTooltip(addBtn, "Add a row (opens its card)");
      addBtn.addEventListener("click", () => void this.addRowAndEdit());
    }
    if (!this.fileMode) {
      const newBtn = this.iconBtn(right, "plus", "New view", () => this.newView(), false);
      newBtn.addClass("kvs-new-cta");
    }

    this.refreshBadges();
  }

  private iconBtn(
    parent: HTMLElement,
    icon: string,
    tip: string,
    onClick: (anchor: HTMLElement) => void,
    disabled: boolean,
  ): HTMLElement {
    const button = parent.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    button.type = "button";
    button.disabled = disabled;
    setIcon(button, icon);
    setTooltip(button, tip);
    button.addEventListener("click", () => onClick(button));
    return button;
  }

  private refreshBadges(): void {
    const view = this.currentProfile();
    const rendered = this.renderedProfile();
    if (this.filterBtn) this.filterBtn.toggleClass("kvs-tb-active", (view?.filter?.conditions.length ?? 0) > 0);
    if (this.sortBtn) {
      this.sortBtn.toggleClass("kvs-tb-active", (rendered?.sort.length ?? 0) > 0 || rendered?.group != null);
    }
    if (this.propsBtn) {
      const n = rendered ? suggestedColumns(rendered, this.lastRows).length : 0;
      this.propsBtn.toggleClass("kvs-tb-dot", n > 0);
      setTooltip(this.propsBtn, n > 0 ? `Properties — ${n} field${n === 1 ? "" : "s"} in your data not shown` : "Properties");
    }
  }

  /** Append fields as configured columns (data — shared by all layouts). */
  private addColumns(fields: readonly { name: string; type: string }[]): void {
    const p = this.renderedProfile();
    if (!p || fields.length === 0) return;
    const existing = new Set(p.columns.map((c) => c.name.toLowerCase()));
    const additions = fields
      .filter((f) => !existing.has(f.name.toLowerCase()))
      .map((f) => ({ name: f.name, type: f.type }));
    if (additions.length === 0) return;
    this.patchActive({ columns: [...p.columns, ...additions] });
  }

  // ---- view management menu ----
  private openViewMenu(anchor: HTMLElement): void {
    openPopover(anchor, (content, handle) => {
      const active = this.currentProfile();
      const shown = this.renderedProfile(); // presentation of the active layout (or the view itself)
      content.addClass("kvs-viewmenu");

      // Header: title + quick actions.
      const head = content.createDiv({ cls: "kvs-viewmenu-head" });
      head.createSpan({ cls: "kvs-viewmenu-title", text: this.fileMode ? "View file" : "Views" });
      if (!this.fileMode) {
        const profiles = this.deps.store.listProfiles();
        const headActions = head.createDiv({ cls: "kvs-viewmenu-head-actions" });
        if (profiles.length > 8) {
          const search = headActions.createEl("button", { cls: "clickable-icon" });
          setIcon(search, "search");
          setTooltip(search, "Search views");
          search.addEventListener("click", () => {
            handle.close();
            this.openViewSwitcher();
          });
        }
        const addBtn = headActions.createEl("button", { cls: "clickable-icon" });
        setIcon(addBtn, "plus");
        setTooltip(addBtn, "New view");
        addBtn.addEventListener("click", () => {
          handle.close();
          this.newView();
        });
      }

      // Everything below the header scrolls, so long view lists + settings never clip.
      const scroll = content.createDiv({ cls: "kvs-viewmenu-scroll" });

      // View list. In file mode the tab strip handles switching between views, so the menu is
      // scoped to the active view's layout and settings; otherwise it lists the stored views.
      if (!this.fileMode) {
        const list = scroll.createDiv({ cls: "kvs-viewmenu-list" });
        const profiles = this.deps.store.listProfiles();
        if (profiles.length === 0) list.createDiv({ cls: "kvs-viewmenu-empty", text: "No views yet" });
        const byCategory = new Map<string, Profile[]>();
        for (const profile of profiles) {
          const key = profile.category ?? "";
          const arr = byCategory.get(key);
          if (arr) arr.push(profile);
          else byCategory.set(key, [profile]);
        }
        const categories = [...byCategory.keys()].sort((a, b) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b)));
        const multi = categories.length > 1;
        for (const category of categories) {
          if (category) list.createDiv({ cls: "kvs-viewmenu-cat", text: category });
          else if (multi) list.createDiv({ cls: "kvs-viewmenu-cat", text: "Ungrouped" });
          for (const profile of byCategory.get(category) ?? []) {
            const item = list.createDiv({ cls: "kvs-viewmenu-item" });
            if (profile.id === active?.id) item.addClass("is-active");
            setIcon(item.createSpan({ cls: "kvs-viewmenu-item-icon" }), this.iconForView(profile.view.type));
            item.createSpan({ cls: "kvs-viewmenu-item-name", text: profile.name });
            if (profile.id === active?.id) setIcon(item.createSpan({ cls: "kvs-viewmenu-item-check" }), "check");
            item.addEventListener("click", () => {
              this.profileId = profile.id;
              this.activeLayoutId = null; // start on the new view's first layout
              this.page = 0;
              this.deps.store.setActiveProfile(profile.id);
              handle.close();
            });
          }
        }
      }

      if (active) {
        // Display settings collapse behind a disclosure so the menu stays compact by default.
        const disc = scroll.createDiv({ cls: "kvs-viewmenu-disc" });
        const discHead = disc.createDiv({ cls: "kvs-viewmenu-disc-head" });
        setIcon(
          discHead.createSpan({ cls: "kvs-viewmenu-disc-chev" }),
          this.viewMenuDisplayOpen ? "chevron-down" : "chevron-right",
        );
        discHead.createSpan({ cls: "kvs-viewmenu-disc-label", text: "Display" });
        const summary =
          layoutTypeLabel(shown?.view.type ?? "table") +
          (shown?.view.type === "table" ? ` · ${shown?.tableWidth ?? "fit"} · ${shown?.rowHeight ?? "normal"}` : "");
        discHead.createSpan({ cls: "kvs-viewmenu-disc-sum", text: summary });
        discHead.addEventListener("click", () => {
          this.viewMenuDisplayOpen = !this.viewMenuDisplayOpen;
          handle.rerender();
        });

        if (this.viewMenuDisplayOpen) {
          const body = disc.createDiv({ cls: "kvs-viewmenu-disc-body" });
          // Layout options as a wrapping grid so every view type stays visible.
          const layout = body.createDiv({ cls: "kvs-viewmenu-sec" });
          layout.createDiv({ cls: "kvs-viewmenu-sec-label", text: "Layout" });
          const grid = layout.createDiv({ cls: "kvs-layout-grid" });
          for (const candidate of this.deps.views.all()) {
            const btn = grid.createEl("button", { cls: "kvs-layout-btn" });
            if (candidate.type === shown?.view.type) btn.addClass("is-on");
            setIcon(btn.createSpan({ cls: "kvs-layout-ic" }), candidate.icon ?? "table");
            btn.createSpan({ cls: "kvs-layout-name", text: candidate.label });
            btn.addEventListener("click", () => {
              this.patchActive({ view: { type: candidate.type, options: shown?.view.options ?? {} } });
              handle.rerender();
            });
          }

          // Hide empty columns — applies to any view that shows fields.
          const emptySec = body.createDiv({ cls: "kvs-viewmenu-sec" });
          const emptyRow = emptySec.createDiv({ cls: "kvs-viewmenu-check" });
          const emptyCb = emptyRow.createEl("input");
          emptyCb.type = "checkbox";
          emptyCb.checked = Boolean(shown?.hideEmptyColumns);
          emptyCb.id = "kvs-hide-empty";
          const emptyLabel = emptyRow.createEl("label", { text: "Hide empty columns" });
          emptyLabel.setAttribute("for", "kvs-hide-empty");
          emptyCb.addEventListener("change", () => {
            this.patchActive({ hideEmptyColumns: emptyCb.checked });
            handle.rerender();
          });

          // Table width + row height (table layout only).
          if (shown?.view.type === "table") {
            const widthSec = body.createDiv({ cls: "kvs-viewmenu-sec" });
            widthSec.createDiv({ cls: "kvs-viewmenu-sec-label", text: "Table width" });
            const wseg = widthSec.createDiv({ cls: "kvs-seg" });
            const widthBtn = (label: string, value: "fit" | "wide"): void => {
              const b = wseg.createEl("button", { cls: "kvs-seg-btn", text: label });
              if (shown?.tableWidth === value) b.addClass("is-on");
              b.addEventListener("click", () => {
                this.patchActive({ tableWidth: value });
                handle.rerender();
              });
            };
            widthBtn("Fit", "fit");
            widthBtn("Wide", "wide");

            const heightSec = body.createDiv({ cls: "kvs-viewmenu-sec" });
            heightSec.createDiv({ cls: "kvs-viewmenu-sec-label", text: "Row height" });
            const hseg = heightSec.createDiv({ cls: "kvs-seg" });
            const heightBtn = (label: string, value: "compact" | "normal" | "comfortable"): void => {
              const b = hseg.createEl("button", { cls: "kvs-seg-btn", text: label });
              if (shown?.rowHeight === value) b.addClass("is-on");
              b.addEventListener("click", () => {
                this.patchActive({ rowHeight: value });
                handle.rerender();
              });
            };
            heightBtn("Compact", "compact");
            heightBtn("Normal", "normal");
            heightBtn("Comfortable", "comfortable");
          }
        }
      }

      // Current-view / global actions.
      const actions = scroll.createDiv({ cls: "kvs-viewmenu-actions" });
      const action = (icon: string, label: string, fn: () => void, danger = false): void => {
        const a = actions.createDiv({ cls: danger ? "kvs-viewmenu-action kvs-viewmenu-danger" : "kvs-viewmenu-action" });
        setIcon(a.createSpan({ cls: "kvs-viewmenu-action-ic" }), icon);
        a.createSpan({ text: label });
        a.addEventListener("click", () => {
          handle.close();
          fn();
        });
      };
      if (this.fileMode) {
        if (active) {
          action("pencil", "Rename", () => {
            this.renamingId = active.id;
            this.buildToolbar();
          });
          action("copy", "Duplicate view", () => this.duplicateFileView(active.id));
          action("settings", "Edit settings", () => this.editView());
          if (this.fileViews.length > 1) action("trash", "Delete view", () => this.deleteFileView(active.id), true);
          action("file-plus", "Save a copy…", () => void this.saveViewAsNote());
          action("package", "Export / backup…", () => this.openBackupExport("pack"));
          if (this.deps.store.getSettings().enableRowCopy) {
            action("code", "Copy as live view (embed)", () => this.copyViewBlock(active));
          }
          action("layout-grid", "Add layout…", () => this.addLayoutFromMenu());
        }
      } else {
        if (active) {
          action("pencil", "Edit settings", () => this.editView());
          action("copy", "Duplicate", () => this.duplicateView());
          action("file-plus", "Save as dashboard file…", () => void this.saveViewAsNote());
          action("package", "Export / backup…", () => this.openBackupExport("pack"));
          action("layout-grid", "Add layout…", () => this.addLayoutFromMenu());
        }
        action("import", "Import table…", () =>
          new ImportModal(this.app, (config, notePath) => this.createImportedView(config, notePath)).open(),
        );
        action("folder-search", "Browse saved views…", () => new ViewBrowserModal(this.app).open());
        if (active && this.deps.store.getSettings().enableRowCopy) {
          action("code", "Copy as live view (embed)", () => this.copyViewBlock(active));
        }
        if (active) action("trash", "Delete view", () => this.deleteView(), true);
      }
    });
  }

  // ---- file-mode view tabs (multiple views per .kvsview file, like a Base) ----
  /** Populate the toolbar's left area with one tab per view (file mode only). */
  private buildTabs(el: HTMLElement): void {
    const active = this.currentProfile();
    for (const view of this.fileViews) {
      const tab = el.createDiv({ cls: "kvs-view-tab" });
      if (view.id === active?.id) tab.addClass("is-active");
      setIcon(tab.createSpan({ cls: "kvs-view-tab-ic" }), this.iconForView(view.view.type));

      if (this.renamingId === view.id) {
        const input = tab.createEl("input", { cls: "kvs-view-tab-input" });
        input.value = view.name;
        window.setTimeout(() => {
          input.focus();
          input.select();
        }, 0);
        const commit = (): void => {
          const name = input.value.trim() || view.name;
          this.renamingId = null;
          this.applyViewName(view.id, name);
        };
        input.addEventListener("keydown", (event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          } else if (event.key === "Escape") {
            event.preventDefault();
            this.renamingId = null;
            this.buildToolbar();
          }
        });
        input.addEventListener("blur", commit);
      } else {
        tab.setAttribute("tabindex", "0");
        tab.setAttribute("role", "tab");
        tab.setAttribute("aria-selected", view.id === active?.id ? "true" : "false");
        tab.createSpan({ cls: "kvs-view-tab-name", text: view.name });
        tab.addEventListener("click", () => this.switchFileView(view.id));
        tab.addEventListener("keydown", (event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            this.switchFileView(view.id);
          } else if (event.key === "F2") {
            event.preventDefault();
            this.renamingId = view.id;
            this.buildToolbar();
          }
        });
        tab.addEventListener("dblclick", () => {
          this.renamingId = view.id;
          this.buildToolbar();
        });
        tab.addEventListener("contextmenu", (event) => {
          event.preventDefault();
          this.openTabMenu(view, tab);
        });
        if (view.id === active?.id) {
          // The active tab's caret opens the full designed view menu (layout, width, actions).
          const caret = tab.createSpan({ cls: "kvs-view-tab-caret" });
          setIcon(caret, "chevron-down");
          caret.addEventListener("click", (event) => {
            event.stopPropagation();
            this.openViewMenu(caret);
          });
        }
      }
    }

    const add = el.createDiv({ cls: "kvs-view-tab-add" });
    setIcon(add, "plus");
    setTooltip(add, "Add view");
    add.addEventListener("click", () => this.addFileView());
  }

  private openTabMenu(view: Profile, anchorEl: HTMLElement): void {
    const menu = new Menu();
    menu.addItem((item) =>
      item
        .setIcon("pencil")
        .setTitle("Rename")
        .onClick(() => {
          this.renamingId = view.id;
          this.buildToolbar();
        }),
    );
    menu.addItem((item) => item.setIcon("copy").setTitle("Duplicate view").onClick(() => this.duplicateFileView(view.id)));
    menu.addItem((item) =>
      item.setIcon("settings").setTitle("Edit settings").onClick(() => {
        this.switchFileView(view.id);
        this.editView();
      }),
    );
    if (this.fileViews.length > 1) {
      menu.addItem((item) => item.setIcon("trash").setTitle("Delete view").onClick(() => this.deleteFileView(view.id)));
    }
    const rect = anchorEl.getBoundingClientRect();
    menu.showAtPosition({ x: rect.left, y: rect.bottom + 2 });
  }

  private switchFileView(id: string): void {
    if (id === this.activeViewId) return;
    this.activeViewId = id;
    this.activeLayoutId = null; // start the new view on its own first layout
    this.page = 0;
    this.search = "";
    this.renamingId = null;
    this.requestSave(); // remember the active tab
    this.buildToolbar();
    void this.renderActive();
  }

  // ---- layout tabs (a view's shared data shown several ways) ----
  /** Render the layout tabs inline in the toolbar when the current view has explicit layouts. */
  private renderLayoutTabs(parent: HTMLElement): void {
    const profile = this.currentProfile();
    const layouts = profile?.layouts;
    if (!profile || !layouts || layouts.length === 0) return;

    parent.createDiv({ cls: "kvs-toolbar-sep" });
    const bar = parent.createDiv({ cls: "kvs-layout-tabs-inline" });
    const activeId = this.currentLayout()?.id;
    for (const layout of layouts) {
      const tab = bar.createDiv({ cls: "kvs-layout-tab" });
      if (layout.id === activeId) tab.addClass("is-active");
      setIcon(tab.createSpan({ cls: "kvs-layout-tab-ic" }), this.iconForView(layout.view.type));

      if (this.renamingLayoutId === layout.id) {
        const input = tab.createEl("input", { cls: "kvs-layout-tab-input" });
        input.value = layout.name;
        window.setTimeout(() => {
          input.focus();
          input.select();
        }, 0);
        const commit = (): void => {
          const name = input.value.trim() || layout.name;
          this.renamingLayoutId = null;
          this.applyLayouts(
            profile,
            layouts.map((l) => (l.id === layout.id ? normalizeLayout({ ...l, name }) : l)),
          );
        };
        input.addEventListener("keydown", (event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          } else if (event.key === "Escape") {
            event.preventDefault();
            this.renamingLayoutId = null;
            this.buildToolbar();
          }
        });
        input.addEventListener("blur", commit);
      } else {
        tab.setAttribute("tabindex", "0");
        tab.setAttribute("role", "tab");
        tab.setAttribute("aria-selected", layout.id === activeId ? "true" : "false");
        tab.createSpan({ cls: "kvs-layout-tab-name", text: layout.name });
        tab.addEventListener("click", () => this.switchLayout(layout.id));
        tab.addEventListener("keydown", (event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            this.switchLayout(layout.id);
          } else if (event.key === "F2") {
            event.preventDefault();
            this.renamingLayoutId = layout.id;
            this.buildToolbar();
          }
        });
        tab.addEventListener("dblclick", () => {
          this.renamingLayoutId = layout.id;
          this.buildToolbar();
        });
        tab.addEventListener("contextmenu", (event) => {
          event.preventDefault();
          this.openLayoutMenu(profile, layout, tab);
        });
      }
    }
    const add = bar.createDiv({ cls: "kvs-layout-add" });
    add.setAttribute("tabindex", "0");
    add.setAttribute("role", "button");
    setIcon(add, "plus");
    setTooltip(add, "Add a layout of the same data");
    add.addEventListener("click", (event) => this.openAddLayoutMenu(event));
    add.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        const rect = add.getBoundingClientRect();
        this.openAddLayoutMenuAt(rect.left, rect.bottom);
      }
    });
  }

  private switchLayout(id: string): void {
    if (id === this.currentLayout()?.id) return;
    this.activeLayoutId = id;
    this.page = 0;
    this.buildToolbar();
    void this.renderActive();
  }

  private openAddLayoutMenu(event: MouseEvent): void {
    this.buildAddLayoutMenu().showAtMouseEvent(event);
  }

  private openAddLayoutMenuAt(x: number, y: number): void {
    this.buildAddLayoutMenu().showAtPosition({ x, y });
  }

  private buildAddLayoutMenu(): Menu {
    const menu = new Menu();
    for (const view of this.deps.views.all()) {
      menu.addItem((item) =>
        item
          .setTitle(view.label)
          .setIcon(this.iconForView(view.type))
          .onClick(() => this.addLayout(view.type)),
      );
    }
    return menu;
  }

  /** Add a layout of `type`. Converts a legacy (no-layouts) view by capturing its current
   *  presentation as the first layout, so nothing is lost. */
  private addLayout(type: string): void {
    const profile = this.currentProfile();
    if (!profile) return;
    const existing = profile.layouts && profile.layouts.length > 0 ? [...profile.layouts] : [layoutFromProfile(profile)];
    const layout = normalizeLayout({
      view: { type, options: {} },
      name: this.uniqueLayoutName(existing, layoutTypeLabel(type)),
    });
    this.activeLayoutId = layout.id;
    this.page = 0;
    this.applyLayouts(profile, [...existing, layout]);
  }

  private duplicateLayout(profile: Profile, layout: Layout): void {
    const layouts = profile.layouts ?? [];
    const copy = normalizeLayout({ ...layout, id: undefined, name: this.uniqueLayoutName(layouts, `${layout.name} copy`) });
    const idx = layouts.findIndex((l) => l.id === layout.id);
    this.activeLayoutId = copy.id;
    this.applyLayouts(profile, [...layouts.slice(0, idx + 1), copy, ...layouts.slice(idx + 1)]);
  }

  private deleteLayout(profile: Profile, layout: Layout): void {
    const layouts = (profile.layouts ?? []).filter((l) => l.id !== layout.id);
    if (layouts.length === 0) return; // never delete the last layout
    forgetViewState(this.viewKeyFor(profile.id, layout.id)); // free this layout's selection/scroll state
    if (this.currentLayout()?.id === layout.id) this.activeLayoutId = layouts[0]!.id;
    this.page = 0;
    this.applyLayouts(profile, layouts);
  }

  private openLayoutMenu(profile: Profile, layout: Layout, anchor: HTMLElement): void {
    const menu = new Menu();
    menu.addItem((i) =>
      i.setTitle("Rename").setIcon("pencil").onClick(() => {
        this.renamingLayoutId = layout.id;
        this.buildToolbar();
      }),
    );
    menu.addItem((i) => i.setTitle("Duplicate").setIcon("copy").onClick(() => this.duplicateLayout(profile, layout)));
    if ((profile.layouts?.length ?? 0) > 1) {
      menu.addItem((i) => i.setTitle("Delete layout").setIcon("trash").onClick(() => this.deleteLayout(profile, layout)));
    }
    const rect = anchor.getBoundingClientRect();
    menu.showAtPosition({ x: rect.left, y: rect.bottom });
  }

  /** Persist a view's layouts (to the store or the .kvsview file) and re-render. */
  private applyLayouts(profile: Profile, layouts: readonly Layout[]): void {
    if (this.fileMode) {
      this.fileViews = this.fileViews.map((v) => (v.id === profile.id ? { ...v, layouts: [...layouts] } : v));
      this.requestSave();
    } else {
      this.deps.store.patchProfile(profile.id, { layouts: [...layouts] });
    }
    this.buildToolbar();
    void this.renderActive();
  }

  private uniqueLayoutName(existing: readonly Layout[], base: string): string {
    const names = new Set(existing.map((l) => l.name.toLowerCase()));
    if (!names.has(base.toLowerCase())) return base;
    for (let n = 2; ; n++) {
      const candidate = `${base} ${n}`;
      if (!names.has(candidate.toLowerCase())) return candidate;
    }
  }

  /** Entry point from the view menu: converts a legacy view to layouts and adds a second one of a
   *  visibly different type. Further layouts (any type) are added from the "+" on the layout bar. */
  private addLayoutFromMenu(): void {
    const profile = this.currentProfile();
    if (!profile) return;
    const current = this.currentLayout()?.view.type ?? profile.view.type;
    this.addLayout(current === "table" ? "kanban" : "table");
  }

  private uniqueViewName(base: string): string {
    const names = new Set(this.fileViews.map((v) => v.name));
    if (!names.has(base)) return base;
    for (let i = 2; i < 999; i++) if (!names.has(`${base} ${i}`)) return `${base} ${i}`;
    return base;
  }

  private addFileView(): void {
    const active = this.currentProfile();
    // Start a new tab from the current view's data source so it shows the same rows in a
    // fresh table layout — the user can then re-shape it.
    const source: Partial<Profile> = active
      ? {
          scope: active.scope,
          extractors: active.extractors,
          filter: active.filter,
          advancedQuery: active.advancedQuery,
          columns: active.columns,
          columnMatch: active.columnMatch,
        }
      : {};
    const view = createProfile({ ...source, name: this.uniqueViewName("New view"), view: { type: "table", options: {} } });
    this.fileViews = [...this.fileViews, view];
    this.activeViewId = view.id;
    this.page = 0;
    this.requestSave();
    this.buildToolbar();
    void this.renderActive();
  }

  private duplicateFileView(id: string): void {
    const source = this.fileViews.find((v) => v.id === id);
    if (!source) return;
    const copy = createProfile({ ...source, id: undefined, name: this.uniqueViewName(`${source.name} copy`) });
    const idx = this.fileViews.findIndex((v) => v.id === id);
    const next = [...this.fileViews];
    next.splice(idx + 1, 0, copy);
    this.fileViews = next;
    this.activeViewId = copy.id;
    this.page = 0;
    this.requestSave();
    this.buildToolbar();
    void this.renderActive();
  }

  private deleteFileView(id: string): void {
    if (this.fileViews.length <= 1) {
      new Notice("A view file must contain at least one view.");
      return;
    }
    const idx = this.fileViews.findIndex((v) => v.id === id);
    forgetViewState(this.viewKeyPrefixFor(id)); // free every layout's state for the removed view
    this.fileViews = this.fileViews.filter((v) => v.id !== id);
    if (this.activeViewId === id) {
      const fallback = this.fileViews[Math.max(0, idx - 1)] ?? this.fileViews[0];
      this.activeViewId = fallback?.id ?? null;
    }
    this.page = 0;
    this.requestSave();
    this.buildToolbar();
    void this.renderActive();
  }

  private applyViewName(id: string, name: string): void {
    this.fileViews = this.fileViews.map((v) => (v.id === id ? { ...v, name } : v));
    this.requestSave();
    this.buildToolbar();
  }

  /** Recreate a view from settings embedded in an imported file, scoped to the new note. */
  private createImportedView(config: Partial<EmbeddedView>, notePath: string): void {
    const cut = notePath.lastIndexOf("/");
    const folder = cut >= 0 ? notePath.slice(0, cut) : "";
    const scope = folder
      ? { mode: "folders" as const, folders: [folder], includeSubfolders: false }
      : { ...DEFAULT_SCOPE };
    const profile = createProfile({ ...config, scope, extractors: [TABLE_EXTRACTOR_ID], name: "Imported view" });
    this.deps.store.addProfile(profile);
    this.profileId = profile.id;
    this.page = 0;
    this.deps.store.setActiveProfile(profile.id);
    new Notice("Restored the view settings saved in the imported file.");
  }

  /** Fuzzy quick-switcher over all views — scales past a flat menu. */
  private openViewSwitcher(): void {
    new ViewSwitcherModal(
      this.app,
      this.deps.store.listProfiles(),
      this.currentProfile()?.id ?? null,
      (type) => this.iconForView(type),
      (profile) => {
        this.profileId = profile.id;
        this.page = 0;
        this.deps.store.setActiveProfile(profile.id);
      },
    ).open();
  }

  /**
   * Save the current view(s) as a self-contained `.kvsview` file — KVS's equivalent of an
   * Obsidian `.base`. In file mode this copies every tab; otherwise it writes the single active
   * view. Either way the file opens as a full, editable dashboard in its own pane, and several
   * can be open in different panes at once.
   */
  private async saveViewAsNote(): Promise<void> {
    let content: string;
    let base: string;
    if (this.fileMode && this.fileViews.length > 0) {
      const activeIdx = Math.max(0, this.fileViews.findIndex((v) => v.id === this.activeViewId));
      const views = this.fileViews.map((v) => createProfile({ ...v, id: undefined }));
      content = serializeViewDoc({ views, activeView: views[activeIdx]!.id });
      base = this.file?.basename ? `${this.file.basename} copy` : (views[0]?.name ?? "View");
    } else {
      const profile = this.currentProfile();
      if (!profile) return;
      content = serializeViewFile({ ...profile, id: createProfile().id });
      base = profile.name.replace(/[\\/:*?"<>|]/g, "-").trim() || "View";
    }
    base = base.replace(/[\\/:*?"<>|]/g, "-").trim() || "View";
    const path = await this.uniqueVaultPath(`${base}.${KVS_VIEW_EXTENSION}`);
    try {
      const file = await this.app.vault.create(path, content);
      new Notice(`Saved dashboard to ${path}`);
      if (file instanceof TFile) void this.app.workspace.getLeaf(true).openFile(file);
    } catch (error) {
      new Notice(`Couldn't save view: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  /** Open the export options dialog (format, row scope, attachments, encryption, destination). */
  private openBackupExport(format: "pack" | "archive"): void {
    const profile = this.renderedProfile();
    if (!profile) return;
    const base = profile.name.replace(/[\\/:*?"<>|]/g, "-").trim() || "View";
    const defaults: BackupExportOptions = {
      format,
      scope: "all",
      includeAttachments: true,
      includeExternal: true,
      encrypt: false,
      password: "",
      dateStamp: format === "archive",
      folder: "",
      filename: base,
    };
    new BackupExportModal(this.app, defaults, Boolean(profile.pageSize), (options) => void this.runBackupExport(profile, options)).open();
  }

  /** Run a configured export by delegating to the shared backup runner. */
  private async runBackupExport(profile: Profile, o: BackupExportOptions): Promise<void> {
    new Notice("Preparing export…");
    try {
      const file = await exportViewBackup(this.app, this.deps, profile, o, this.search, this.page, true, (m) => new Notice(m));
      if (!file) new Notice("Export produced no file.");
      else {
        const bits = [`${o.format === "pack" ? "backup" : "archive"} saved`];
        if (o.encrypt) bits.push("encrypted");
        new Notice(`${bits.join(", ")}: ${file.path}`);
      }
    } catch (error) {
      new Notice(`Couldn't export: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private newView(): void {
    const profile = createProfile({
      name: "New view",
      view: { type: this.deps.store.getSettings().defaultView, options: {} },
    });
    this.deps.store.addProfile(profile);
    this.profileId = profile.id;
    this.deps.store.setActiveProfile(profile.id);
    new ProfileEditorModal(this.app, this.editorDeps(), profile).open();
  }

  private editView(): void {
    const profile = this.currentProfile();
    if (!profile) return;
    const layoutId = this.currentLayout()?.id;
    if (this.fileMode) {
      // The editor routes presentation→layout itself, so persist its (already-routed) patch verbatim.
      new ProfileEditorModal(this.app, this.editorDeps(), profile, undefined, (patch) => this.applyEditorPatch(profile.id, patch), layoutId).open();
    } else {
      new ProfileEditorModal(this.app, this.editorDeps(), profile, undefined, undefined, layoutId).open();
    }
  }

  /** Apply an editor patch to a file-backed view without re-routing (the editor already routed it). */
  private applyEditorPatch(id: string, patch: Partial<Profile>): void {
    this.fileViews = this.fileViews.map((v) => (v.id === id ? { ...v, ...patch } : v));
    this.requestSave();
    this.buildToolbar();
    void this.renderActive();
  }

  private duplicateView(): void {
    const profile = this.currentProfile();
    if (!profile) return;
    const copy = createProfile({ ...profile, id: undefined, name: `${profile.name} copy` });
    this.deps.store.addProfile(copy);
    this.profileId = copy.id;
    this.deps.store.setActiveProfile(copy.id);
  }

  private deleteView(): void {
    const profile = this.currentProfile();
    if (!profile) return;
    forgetViewState(this.viewKeyPrefixFor(profile.id)); // free every layout's state for the removed view
    this.deps.store.removeProfile(profile.id);
    const next = this.deps.store.listProfiles()[0];
    this.profileId = next?.id ?? null;
    this.deps.store.setActiveProfile(next?.id ?? null);
  }

  // ---- results menu (count / copy / export / page size) ----
  private openResultsMenu(event: MouseEvent): void {
    const menu = new Menu();
    menu.addItem((item) => item.setIcon("copy").setTitle("Copy as Markdown table").onClick(() => this.copyMarkdown()));
    menu.addItem((item) => item.setIcon("download").setTitle("Export…").onClick(() => void this.openExportModal()));
    if (this.hasMaterializeTargets()) {
      menu.addItem((item) =>
        item.setIcon("save").setTitle("Write rollups to source").onClick(() => this.materializeDerived()),
      );
    }
    const profile = this.renderedProfile();
    if (profile) {
      menu.addSeparator();
      menu.addItem((item) => item.setIsLabel(true).setTitle("Page size"));
      for (const size of [10, 20, 30, 50, 70, 100, 150, 200]) {
        menu.addItem((item) =>
          item
            .setTitle(String(size))
            .setChecked(profile.pageSize === size)
            .onClick(() => this.patchActive({ pageSize: size })),
        );
      }
      menu.addItem((item) =>
        item.setTitle("All").setChecked(profile.pageSize === null).onClick(() => this.patchActive({ pageSize: null })),
      );
    }
    menu.showAtMouseEvent(event);
  }

  private visibleColumns(): { name: string; label: string }[] {
    return this.choices()
      .filter((c) => c.visible)
      .map((c) => ({ name: c.name, label: c.label }));
  }

  private copyMarkdown(): void {
    void (async () => {
      const profile = this.renderedProfile();
      if (!profile) return;
      const cols = this.visibleColumns().map((c) => ({ name: c.name, label: c.label }));
      // Copy exactly what the current page shows: query the filtered/sorted set,
      // then slice to the active page when a page size is set.
      const result = await this.deps.dataService.query({ ...profile, pageSize: null }, { search: this.search });
      let rows = result.rows;
      if (profile.pageSize && profile.pageSize > 0) {
        const start = this.page * profile.pageSize;
        rows = rows.slice(start, start + profile.pageSize);
      }
      const table = buildExportTable(rows, cols, false);
      await navigator.clipboard.writeText(buildMarkdownTable(table));
      new Notice(`Copied ${rows.length} ${rows.length === 1 ? "row" : "rows"} as Markdown`);
    })();
  }

  private async openExportModal(): Promise<void> {
    const profile = this.renderedProfile();
    if (!profile) return;
    const paginated = profile.pageSize != null && profile.pageSize > 0;

    // A sample of the current view drives the live preview; report the true total too.
    let previewTable: ExportTable | undefined;
    let totalRows = 0;
    try {
      const sample = await this.deps.dataService.query({ ...profile, pageSize: 24 }, { search: this.search });
      totalRows = sample.total;
      const cols = this.exportColumns(profile, computeColumnChoices(profile, sample.rows), "visible");
      const segments = await this.resolveExportCells(sample.rows, cols);
      previewTable = { ...buildExportTable(sample.rows, cols, false), segments };
    } catch {
      previewTable = undefined;
    }

    new ExportOptionsModal(this.app, {
      defaultName: profile.name || "view",
      paginated,
      previewTable,
      totalRows,
      academic: profile.academicKit,
      columnTypes: previewTable ? this.columnTypesFor(profile, previewTable.headers) : {},
      onSubmit: (request) => void this.runExport(profile, request),
    }).open();
  }

  /** Map each column name to its resolved type id (for reference-aware exports). */
  private columnTypesFor(profile: Profile, names: readonly string[]): Record<string, string> {
    const rendered = this.renderedProfile() ?? profile;
    const configured = new Map(rendered.columns.map((c) => [c.name.toLowerCase(), c.type] as const));
    const out: Record<string, string> = {};
    for (const name of names) out[name] = configured.get(name.toLowerCase()) ?? "text";
    return out;
  }

  private columnTypeOf(profile: Profile, _rows: readonly Row[], name: string): string {
    const rendered = this.renderedProfile() ?? profile;
    return rendered.columns.find((c) => c.name.toLowerCase() === name.toLowerCase())?.type ?? "text";
  }

  /** Export columns carrying the view's configured (or wide-mode) widths, for PDF sizing. */
  private exportColumns(profile: Profile, choices: ColumnChoice[], mode: "all" | "visible"): ExportColumn[] {
    const picked = mode === "all" ? choices : choices.filter((c) => c.visible);
    return picked.map((c) => {
      const cfg = profile.columns.find((col) => col.name.toLowerCase() === c.name.toLowerCase());
      const width = cfg?.width ?? (profile.tableWidth === "wide" ? defaultWideWidth(c.typeId, "none") : undefined);
      return { name: c.name, label: c.label, width, typeId: c.typeId };
    });
  }

  /** Resolve image + rich-text cells into render tokens (keyed "row:col") for exports. */
  private async resolveExportCells(
    rows: readonly Row[],
    columns: ExportColumn[],
  ): Promise<Record<string, readonly Block[]>> {
    const targets = columns
      .map((c, i) => ({ c, i }))
      .filter((x) => x.c.typeId === "image" || x.c.typeId === "markdown" || x.c.typeId === "text");
    if (targets.length === 0) return {};
    const out: Record<string, readonly Block[]> = {};
    for (let r = 0; r < rows.length; r++) {
      const row = rows[r];
      if (!row) continue;
      for (const { c, i } of targets) {
        let raw = getField(row, c.name);
        // An image column may hold a bare filename rather than an embed — treat it as one.
        if (c.typeId === "image" && extractImageEmbeds(raw).length === 0 && raw.trim() !== "") {
          raw = `![[${raw.trim()}]]`;
        }
        const embeds = extractImageEmbeds(raw).slice(0, 8);
        const images = new Map<string, string>();
        for (const embed of embeds) {
          const url = await this.resolveEmbedToDataUrl(embed);
          if (url) images.set(embed, url);
        }
        // Plain-text cells (no images, no Markdown) render fine without tokens.
        if (c.typeId !== "image" && images.size === 0 && !hasRenderableMarkdown(raw)) continue;
        const blocks = parseCellBlocks(raw, images);
        if (blocks.length > 0) out[`${r}:${i}`] = blocks;
      }
    }
    return out;
  }

  /** Turn a single `![[file]]` / `![](url)` embed into a base64 data URL, or null. */
  private async resolveEmbedToDataUrl(embed: string): Promise<string | null> {
    try {
      const internal = /^!\[\[(.+?)\]\]$/.exec(embed);
      if (internal) {
        const link = (internal[1] ?? "").split("|")[0]!.split("#")[0]!.trim();
        const file = this.app.metadataCache.getFirstLinkpathDest(link, "");
        if (!(file instanceof TFile)) return null;
        return dataUrlFromBytes(await this.app.vault.readBinary(file), file.extension);
      }
      const external = /^!\[[^\]]*\]\((.+?)\)$/.exec(embed);
      if (external) {
        const target = (external[1] ?? "").trim();
        if (/^https?:\/\//i.test(target)) {
          const res = await requestUrl({ url: target });
          return dataUrlFromBytes(res.arrayBuffer, extFromPath(target));
        }
        const file =
          this.app.metadataCache.getFirstLinkpathDest(target, "") ?? this.app.vault.getAbstractFileByPath(target);
        if (file instanceof TFile) return dataUrlFromBytes(await this.app.vault.readBinary(file), file.extension);
      }
    } catch {
      // Unreadable/missing image — fall back to the text form.
    }
    return null;
  }

  private async runExport(profile: Profile, request: ExportRequest): Promise<void> {
    try {
      // Filtered + sorted, but un-paginated, so column discovery and row scope are stable.
      const result = await this.deps.dataService.query({ ...profile, pageSize: null }, { search: this.search });
      const allRows = result.rows;
      const choices = computeColumnChoices(profile, allRows);
      const cols = this.exportColumns(profile, choices, request.columns);

      let rows = allRows;
      if (request.rowScope === "page" && profile.pageSize) {
        const start = this.page * profile.pageSize;
        rows = allRows.slice(start, start + profile.pageSize);
      }

      const table = buildExportTable(rows, cols, request.includeMetadata);
      if (request.format === "bibtex" || request.format === "bibliography") {
        const refCols = cols.map((c) => ({ name: c.label ?? c.name, typeId: this.columnTypeOf(profile, allRows, c.name) }));
        const refs = table.rows.map((cells) => {
          const record: Record<string, string> = {};
          table.headers.forEach((h, i) => (record[h] = cells[i] ?? ""));
          return rowToReference(refCols, record);
        });
        if (request.format === "bibtex") {
          await this.deliver(request, `${request.fileName}.bib`, buildBibtex(refs), "application/x-bibtex");
        } else {
          const text = buildBibliography(refs, request.bibliographyStyle);
          await this.deliver(request, `${request.fileName}.md`, text, "text/markdown");
        }
        return;
      }
      if (request.format === "pdf") {
        const segments = await this.resolveExportCells(rows, cols);
        this.printTable(buildPrintHtml({ ...table, segments }, request.pdf));
        return;
      }
      if (request.format === "docx") {
        const segments = await this.resolveExportCells(rows, cols);
        await this.deliver(
          request,
          `${request.fileName}.docx`,
          buildDocx({ ...table, segments }, request.pdf),
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        );
        return;
      }
      if (request.format === "csv") {
        await this.deliver(request, `${request.fileName}.csv`, buildCsv(table, request.csv), "text/csv");
      } else if (request.format === "markdown") {
        const md = buildMarkdownTable(table, request.markdown);
        const body = request.embedView ? `${embedViewComment(profile)}\n${md}` : md;
        await this.deliver(request, `${request.fileName}.md`, body, "text/markdown");
      } else {
        await this.deliver(
          request,
          `${request.fileName}.xlsx`,
          buildXlsx(table, request.xlsx),
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        );
      }
    } catch (error) {
      new Notice(`Export failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async deliver(
    request: ExportRequest,
    filename: string,
    data: string | Uint8Array,
    mime: string,
  ): Promise<void> {
    if (request.destination === "vault") {
      const path = await this.uniqueVaultPath(filename);
      if (typeof data === "string") await this.app.vault.create(path, data);
      else await this.app.vault.createBinary(path, data.buffer as ArrayBuffer);
      new Notice(`Exported to ${path}`);
    } else {
      const blob =
        typeof data === "string"
          ? new Blob([data], { type: mime })
          : new Blob([new Uint8Array(data)], { type: mime });
      const url = URL.createObjectURL(blob);
      const anchor = document.body.createEl("a", { attr: { href: url, download: filename } });
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 2000);
      new Notice(`Exported ${filename}`);
    }
  }

  private async uniqueVaultPath(filename: string): Promise<string> {
    const dot = filename.lastIndexOf(".");
    const base = dot > 0 ? filename.slice(0, dot) : filename;
    const ext = dot > 0 ? filename.slice(dot) : "";
    let path = filename;
    let i = 1;
    while (this.app.vault.getAbstractFileByPath(path)) {
      path = `${base} ${i}${ext}`;
      i += 1;
    }
    return path;
  }

  private printTable(html: string): void {
    const frame = document.body.createEl("iframe", { cls: "kvs-print-frame" });

    // `srcdoc` rather than `document.write()`, which is deprecated and flagged by Obsidian's linter.
    // It loads asynchronously, so everything that touches the frame's document has to hang off `load`.
    frame.addEventListener("load", () => {
      const win = frame.contentWindow;
      const doc = win?.document;
      if (!win || !doc) {
        frame.remove();
        new Notice("Couldn't open the print view.");
        return;
      }
      win.addEventListener("afterprint", () => window.setTimeout(() => frame.remove(), 1000));

      // The print document paginates itself (for page numbers); wait until it signals
      // ready — or a safety timeout — before invoking the print dialog.
      let printed = false;
      const fire = (): void => {
        if (printed) return;
        printed = true;
        win.focus();
        win.print();
      };
      const started = Date.now();
      const poll = (): void => {
        if (printed) return;
        const ready = doc.body?.getAttribute("data-ready") === "1";
        if (ready || Date.now() - started > 1500) fire();
        else window.setTimeout(poll, 50);
      };
      window.setTimeout(poll, 150);
    });

    frame.setAttr("srcdoc", html);
  }

  // ---- Properties popover ----
  private openPropertiesPopover(anchor: HTMLElement): void {
    if (!this.currentProfile()) return;

    // Show/hide a column. Hiding is lightweight (hiddenColumns membership). Showing must make the
    // field actually render: a discovered data field just needs unhiding, but a *virtual* field
    // (note/path/folder/created/modified) or a data field in a curated view isn't in the render set
    // until it's added as a column. Adding a virtual field keeps discovery intact (see resolveColumns).
    const setVisible = (choice: ColumnChoice, makeVisible: boolean): void => {
      const p = this.renderedProfile();
      if (!p) return;
      const key = choice.name.toLowerCase();
      const hiddenLower = p.hiddenColumns.map((x) => x.toLowerCase());

      if (!makeVisible) {
        if (!hiddenLower.includes(key)) this.patchActive({ hiddenColumns: [...p.hiddenColumns, choice.name] });
        return;
      }

      const hiddenColumns = p.hiddenColumns.filter((x) => x.toLowerCase() !== key);
      const inColumns = p.columns.some((c) => c.name.toLowerCase() === key);
      if (inColumns) {
        // A defined column: showing it is purely un-hiding — the column definition is untouched.
        this.patchActive({ hiddenColumns });
        return;
      }

      // Not configured: it renders on its own only as a discovered data field in a discovery view.
      const hasRealColumns = p.columns.some((c) => !isVirtualField(c.name));
      const isDataField = this.lastRows.some((r) => Object.prototype.hasOwnProperty.call(r.cells, choice.name));
      if (!hasRealColumns && isDataField) {
        this.patchActive({ hiddenColumns });
        return;
      }

      const newColumn: ColumnConfig =
        choice.label && choice.label !== choice.name
          ? { name: choice.name, type: choice.typeId, label: choice.label }
          : { name: choice.name, type: choice.typeId };
      this.patchActive({ hiddenColumns, columns: [...p.columns, newColumn] });
    };

    // Reorder / rename persist the column list (as before) but keep every real column —
    // shown, hidden, or already configured — so a hidden column is never dropped.
    const persistStructure = (ordered: ColumnChoice[]): void => {
      const p = this.renderedProfile();
      if (!p) return;
      const prior = new Map(p.columns.map((c) => [c.name.toLowerCase(), c]));
      const hidden = new Set(p.hiddenColumns.map((x) => x.toLowerCase()));
      const configuredNames = new Set(p.columns.map((c) => c.name.toLowerCase()));
      const columns: ColumnConfig[] = ordered
        .filter((c) => c.visible || hidden.has(c.name.toLowerCase()) || configuredNames.has(c.name.toLowerCase()))
        .map((c) => {
          const base = prior.get(c.name.toLowerCase());
          const label = c.label && c.label !== c.name ? c.label : undefined;
          return base ? { ...base, name: c.name, type: c.typeId, label } : { name: c.name, type: c.typeId, label };
        });
      this.patchActive({ columns });
    };

    openPopover(anchor, (content, handle) => {
      const p = this.renderedProfile();
      let choices = this.choices(); // fresh each render, so it reflects the latest hidden state
      content.createDiv({ cls: "kvs-popover-title", text: "Properties" });
      content.createDiv({
        cls: "kvs-popover-hint",
        text: "Show or hide columns in this view. Hidden columns stay part of the table — reopen this list to bring them back.",
      });

      // Proactive suggestion: fields in the underlying data that this curated view doesn't show yet.
      const suggestions = p ? suggestedColumns(p, this.lastRows) : [];
      const suggestNames = new Set(suggestions.map((s) => s.name.toLowerCase()));
      if (suggestions.length > 0) {
        const banner = content.createDiv({ cls: "kvs-prop-suggest" });
        const preview = suggestions.slice(0, 4).map((s) => s.name).join(", ");
        banner.createDiv({
          cls: "kvs-prop-suggest-text",
          text: `${suggestions.length} field${suggestions.length === 1 ? "" : "s"} in your data not shown: ${preview}${suggestions.length > 4 ? "…" : ""}`,
        });
        const add = banner.createEl("button", { cls: "mod-cta kvs-prop-suggest-add", text: "Add all" });
        add.addEventListener("click", () => {
          this.addColumns(suggestions);
          handle.rerender();
        });
      }

      // Leading meta-columns (table only): the source-note link and the row-selection checkbox.
      if (p && p.view.type === "table") {
        const meta = content.createDiv({ cls: "kvs-prop-list kvs-prop-meta" });
        const metaRow = (label: string, on: boolean, toggle: () => void): void => {
          const row = meta.createDiv({ cls: "kvs-prop-row" });
          row.createSpan({ cls: "kvs-grip kvs-grip-fixed" }); // alignment spacer, not draggable
          const eye = row.createEl("button", { cls: "clickable-icon kvs-prop-eye" });
          eye.type = "button";
          setIcon(eye, on ? "eye" : "eye-off");
          eye.toggleClass("kvs-col-hidden", !on);
          setTooltip(eye, on ? "Hide from this view" : "Show in this view");
          eye.setAttribute("aria-label", `${on ? "Hide" : "Show"} ${label}`);
          eye.addEventListener("click", () => {
            toggle();
            handle.rerender();
          });
          row.createDiv({ cls: "kvs-prop-name kvs-prop-meta-label", text: label });
        };
        metaRow("Source note", p.sourceColumn, () => this.patchActive({ sourceColumn: !p.sourceColumn }));
        metaRow("Row selection", p.rowSelection, () => this.patchActive({ rowSelection: !p.rowSelection }));
        content.createDiv({ cls: "kvs-prop-divider" });
      }

      const list = content.createDiv({ cls: "kvs-prop-list" });
      choices.forEach((choice, index) => {
        const row = list.createDiv({ cls: "kvs-prop-row" });
        const grip = row.createSpan({ cls: "kvs-grip" });
        setIcon(grip, "grip-vertical");
        enableRowDrag(grip, row, index, (from, to) => {
          choices = moveItem(choices, from, to);
          persistStructure(choices);
          handle.rerender();
        });
        const eye = row.createEl("button", { cls: "clickable-icon kvs-prop-eye" });
        eye.type = "button";
        setIcon(eye, choice.visible ? "eye" : "eye-off");
        eye.toggleClass("kvs-col-hidden", !choice.visible);
        setTooltip(eye, choice.visible ? "Hide from this view" : "Show in this view");
        eye.setAttribute("aria-label", `${choice.visible ? "Hide" : "Show"} ${choice.name}`);
        eye.addEventListener("click", () => {
          setVisible(choice, !choice.visible);
          handle.rerender();
        });
        const name = row.createEl("input", { cls: "kvs-prop-name" });
        name.type = "text";
        name.value = choice.label;
        name.setAttribute("aria-label", `Rename ${choice.name}`);
        name.addEventListener("change", () => {
          const renamed = choices.map((c) => (c.name === choice.name ? { ...c, label: name.value.trim() || c.name } : c));
          persistStructure(renamed);
        });
        if (suggestNames.has(choice.name.toLowerCase())) {
          row.createSpan({ cls: "kvs-prop-badge", text: "in data" });
        }
      });
    });
  }

  // ---- Filter popover (recursive, nested groups + All/Any/None) ----
  private openFilterPopover(anchor: HTMLElement): void {
    const profile = this.currentProfile();
    if (!profile) return;
    const fields = this.choices();
    const typeOf = (field: string): string =>
      fields.find((c) => c.name.toLowerCase() === field.toLowerCase())?.typeId ?? "text";

    type MGroup = { combinator: FilterCombinator; conditions: FilterCondition[]; groups: MGroup[] };
    const clone = (group: FilterGroup | null): MGroup =>
      group
        ? { combinator: group.combinator, conditions: group.conditions.map((c) => ({ ...c })), groups: group.groups.map(clone) }
        : { combinator: "and", conditions: [], groups: [] };
    const root = clone(profile.filter);

    const persist = (): void => {
      const empty = root.conditions.length === 0 && root.groups.length === 0;
      this.patchActive({ filter: empty ? null : (root) });
    };

    openPopover(anchor, (content, handle) => {
      content.createDiv({ cls: "kvs-popover-title", text: "Filter" });

      // Advanced expression — the same power available in settings, surfaced here. ANDed with the
      // conditions below. Live-validated; only a valid expression is saved.
      const adv = content.createDiv({ cls: "kvs-filter-advanced" });
      adv.createDiv({ cls: "kvs-popover-hint", text: "Advanced expression (optional) — e.g. Year >= 2020 and Status == \"open\"" });
      const advInput = adv.createEl("textarea", { cls: "kvs-adv-query" });
      advInput.rows = 2;
      advInput.value = profile.advancedQuery ?? "";
      advInput.placeholder = 'Priority == "High" or contains(Tags, "urgent")';
      const advError = adv.createDiv({ cls: "kvs-adv-error" });
      let advTimer: number | undefined;
      advInput.addEventListener("input", () => {
        const trimmed = advInput.value.trim();
        const result = trimmed === "" ? { ok: true as const } : validateExpression(trimmed);
        advInput.toggleClass("kvs-invalid", !result.ok);
        advError.setText(result.ok ? "" : `Invalid: ${result.error}`);
        if (!result.ok) return;
        window.clearTimeout(advTimer);
        advTimer = window.setTimeout(() => this.patchActive({ advancedQuery: trimmed === "" ? null : trimmed }), 300);
      });
      content.createDiv({ cls: "kvs-prop-divider" });

      const renderCondition = (listEl: HTMLElement, group: MGroup, index: number): void => {
        const condition = group.conditions[index];
        if (!condition) return;
        const row = listEl.createDiv({ cls: "kvs-filter-row" });

        const fieldSel = row.createEl("select", { cls: "dropdown" });
        for (const field of fields) fieldSel.createEl("option", { text: field.label, value: field.name });
        fieldSel.value = condition.field;
        fieldSel.addEventListener("change", () => {
          const ops = operatorsForType(typeOf(fieldSel.value), this.deps.registry);
          const operator = ops.includes(condition.operator) ? condition.operator : ops[0] ?? "contains";
          group.conditions[index] = { ...condition, field: fieldSel.value, operator };
          persist();
          handle.rerender();
        });

        const opSel = row.createEl("select", { cls: "dropdown" });
        for (const op of operatorsForType(typeOf(condition.field), this.deps.registry)) {
          opSel.createEl("option", { text: OPERATOR_LABELS[op], value: op });
        }
        opSel.value = condition.operator;
        opSel.addEventListener("change", () => {
          group.conditions[index] = { ...condition, operator: opSel.value as FilterOperator };
          persist();
          handle.rerender();
        });

        if (!NO_VALUE_OPERATORS.has(condition.operator)) {
          const value = row.createEl("input", { cls: "kvs-cell-input" });
          value.type = "text";
          value.value = condition.value ?? "";
          value.placeholder = "value";
          value.addEventListener("change", () => {
            group.conditions[index] = { ...condition, value: value.value };
            persist();
          });
        }

        const remove = row.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
        remove.type = "button";
        setIcon(remove, "x");
        setTooltip(remove, "Remove condition");
        remove.addEventListener("click", () => {
          group.conditions.splice(index, 1);
          persist();
          handle.rerender();
        });
      };

      const renderGroup = (host: HTMLElement, group: MGroup, depth: number, onRemove: (() => void) | null): void => {
        const box = host.createDiv({ cls: depth > 0 ? "kvs-filter-group kvs-filter-group-nested" : "kvs-filter-group" });

        const head = box.createDiv({ cls: "kvs-filter-group-head" });
        const combo = head.createEl("select", { cls: "dropdown" });
        combo.createEl("option", { text: "All of the following", value: "and" });
        combo.createEl("option", { text: "Any of the following", value: "or" });
        combo.createEl("option", { text: "None of the following", value: "none" });
        combo.value = group.combinator;
        combo.addEventListener("change", () => {
          group.combinator = combo.value as FilterCombinator;
          persist();
        });
        if (onRemove) {
          const rm = head.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
          rm.type = "button";
          setIcon(rm, "x");
          setTooltip(rm, "Remove group");
          rm.addEventListener("click", () => {
            onRemove();
            persist();
            handle.rerender();
          });
        }

        const list = box.createDiv({ cls: "kvs-filter-list" });
        group.conditions.forEach((_, index) => renderCondition(list, group, index));
        group.groups.forEach((sub, i) => renderGroup(box, sub, depth + 1, () => group.groups.splice(i, 1)));

        const actions = box.createDiv({ cls: "kvs-filter-actions" });
        const addCond = actions.createEl("button", { cls: "kvs-tb-btn", text: "＋ Add condition" });
        addCond.addEventListener("click", () => {
          group.conditions.push({ field: fields[0]?.name ?? "note", operator: "contains", value: "" });
          persist();
          handle.rerender();
        });
        const addGroup = actions.createEl("button", { cls: "kvs-tb-btn", text: "＋ Add group" });
        addGroup.addEventListener("click", () => {
          group.groups.push({ combinator: "and", conditions: [], groups: [] });
          persist();
          handle.rerender();
        });
      };

      renderGroup(content.createDiv({ cls: "kvs-filter-root" }), root, 0, null);
    });
  }

  // ---- Sort & group popover (multi-key, drag-reorderable) ----
  private openSortPopover(anchor: HTMLElement): void {
    const profile = this.renderedProfile();
    if (!profile) return;
    const fields = this.choices();
    let keys: SortKey[] = [...profile.sort];
    const persistSort = (): void => this.patchActive({ sort: keys });

    openPopover(anchor, (content, handle) => {
      const fresh = this.currentProfile();
      if (!fresh) return;
      content.createDiv({ cls: "kvs-popover-title", text: "Sort" });

      const list = content.createDiv({ cls: "kvs-sort-list" });
      keys.forEach((key, index) => {
        const row = list.createDiv({ cls: "kvs-sort-key-row" });
        const grip = row.createSpan({ cls: "kvs-grip" });
        setIcon(grip, "grip-vertical");
        enableRowDrag(grip, row, index, (from, to) => {
          keys = moveItem(keys, from, to);
          persistSort();
          handle.rerender();
        });

        const fieldSel = row.createEl("select", { cls: "dropdown" });
        for (const field of fields) fieldSel.createEl("option", { text: field.label, value: field.name });
        fieldSel.value = key.field;
        fieldSel.addEventListener("change", () => {
          keys = keys.map((k, i) => (i === index ? { ...k, field: fieldSel.value } : k));
          persistSort();
        });

        const dirSel = row.createEl("select", { cls: "dropdown" });
        dirSel.createEl("option", { text: "Ascending", value: "asc" });
        dirSel.createEl("option", { text: "Descending", value: "desc" });
        dirSel.value = key.direction;
        dirSel.addEventListener("change", () => {
          keys = keys.map((k, i) => (i === index ? { ...k, direction: dirSel.value as "asc" | "desc" } : k));
          persistSort();
        });

        const remove = row.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
        remove.type = "button";
        setIcon(remove, "x");
        setTooltip(remove, "Remove");
        remove.addEventListener("click", () => {
          keys = keys.filter((_, i) => i !== index);
          persistSort();
          handle.rerender();
        });
      });

      const add = content.createEl("button", { cls: "kvs-tb-btn", text: "＋ Add sort" });
      add.addEventListener("click", () => {
        keys = [...keys, { field: fields[0]?.name ?? "note", direction: "asc" }];
        persistSort();
        handle.rerender();
      });

      content.createEl("hr", { cls: "kvs-popover-rule" });
      const groupRow = content.createDiv({ cls: "kvs-sort-row" });
      groupRow.createSpan({ cls: "kvs-sort-label", text: "Group by" });
      const groupSel = groupRow.createEl("select", { cls: "dropdown" });
      groupSel.createEl("option", { text: "None", value: "" });
      for (const field of fields) groupSel.createEl("option", { text: field.label, value: field.name });
      groupSel.value = fresh.group?.field ?? "";
      groupSel.addEventListener("change", () => {
        this.patchActive({ group: groupSel.value ? { field: groupSel.value } : null });
      });
    });
  }

  // ---- View-specific options popover ----
  private openViewOptionsPopover(anchor: HTMLElement): void {
    const fields = this.choices();
    const setOption = (key: string, value: unknown): void => {
      const profile = this.renderedProfile();
      if (profile) this.patchActive({ view: { type: profile.view.type, options: { ...profile.view.options, [key]: value } } });
    };

    openPopover(anchor, (content, handle) => {
      const profile = this.renderedProfile();
      const view = profile ? this.deps.views.get(profile.view.type) : undefined;
      if (!profile || !view) return;
      content.createDiv({ cls: "kvs-popover-title", text: `${view.label} options` });
      const options = profile.view.options;

      for (const spec of view.optionSpecs ?? []) {
        const row = content.createDiv({ cls: "kvs-sort-row" });
        row.createSpan({ cls: "kvs-sort-label", text: spec.label });

        if (spec.kind === "field") {
          const select = row.createEl("select", { cls: "dropdown" });
          select.createEl("option", { text: "—", value: "" });
          const eligible = spec.fieldFilter === "date" ? fields.filter((f) => f.typeId === "date") : fields;
          for (const field of eligible) select.createEl("option", { text: field.label, value: field.name });
          select.value = optString(options, spec.key);
          select.addEventListener("change", () => {
            setOption(spec.key, select.value || undefined);
            handle.rerender();
          });
        } else if (spec.kind === "select") {
          const select = row.createEl("select", { cls: "dropdown" });
          for (const choice of spec.choices ?? []) select.createEl("option", { text: choice.label, value: choice.value });
          select.value = optString(options, spec.key, spec.choices?.[0]?.value ?? "");
          select.addEventListener("change", () => {
            setOption(spec.key, select.value);
            handle.rerender();
          });
        } else if (spec.kind === "toggle") {
          const checkbox = row.createEl("input", { cls: "kvs-checkbox" });
          checkbox.type = "checkbox";
          checkbox.checked = optBool(options, spec.key);
          checkbox.addEventListener("change", () => setOption(spec.key, checkbox.checked));
        } else {
          const input = row.createEl("input", { cls: "kvs-cell-input" });
          input.type = "text";
          input.value = optString(options, spec.key);
          if (spec.placeholder) input.placeholder = spec.placeholder;
          input.addEventListener("change", () => setOption(spec.key, input.value || undefined));
        }
      }
    });
  }

  private async undoLast(): Promise<void> {
    const label = await this.deps.undo.undo();
    new Notice(label ? `Undone: ${label}` : "Nothing to undo.");
  }

  private resizeColumn(name: string, width: number): void {
    const rendered = this.renderedProfile();
    if (!rendered) return;
    // Width is a per-layout presentation detail; patchActive routes columnWidths to the active layout.
    const columnWidths = { ...(rendered.columnWidths ?? {}), [name.toLowerCase()]: width };
    this.patchActive({ columnWidths });
  }

  /** Remove one column's stored width so it returns to its default size. */
  private resetColumnWidth(name: string): void {
    const rendered = this.renderedProfile();
    const widths = rendered?.columnWidths;
    const key = name.toLowerCase();
    if (!widths || !(key in widths)) return;
    const next = { ...widths };
    delete next[key];
    this.patchActive({ columnWidths: next });
  }

  /** Hide a data column (add it to the layout's hidden set). */
  private hideColumn(name: string): void {
    const rendered = this.renderedProfile();
    if (!rendered) return;
    if (rendered.hiddenColumns.some((c) => c.toLowerCase() === name.toLowerCase())) return;
    this.patchActive({ hiddenColumns: [...rendered.hiddenColumns, name] });
  }

  private renderPager(info: PageInfo | null): void {
    const pager = this.pagerEl;
    if (!pager) return;
    pager.empty();
    if (!info || info.count <= 1) return;

    const prev = pager.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    prev.type = "button";
    setIcon(prev, "chevron-left");
    setTooltip(prev, "Previous page");
    prev.disabled = info.index <= 0;
    prev.addEventListener("click", () => {
      this.page = Math.max(0, info.index - 1);
      void this.renderActive();
    });

    pager.createSpan({ cls: "kvs-pager-label", text: `${info.index + 1} / ${info.count}` });

    const next = pager.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    next.type = "button";
    setIcon(next, "chevron-right");
    setTooltip(next, "Next page");
    next.disabled = info.index >= info.count - 1;
    next.addEventListener("click", () => {
      this.page = Math.min(info.count - 1, info.index + 1);
      void this.renderActive();
    });
  }

  // ---- body ----
  /** Toggle focus mode: hide the toolbar/hint chrome and the leading gutter, maximising the data. */
  toggleFocusMode(): void {
    this.focusMode = !this.focusMode;
    this.applyFocusChrome();
    void this.renderActive();
  }

  private applyFocusChrome(): void {
    const on = this.focusMode;
    this.toolbarEl?.toggle(!on);
    this.focusExitEl?.toggle(on);
    this.contentEl.toggleClass("kvs-focus", on);
    if (on) this.hintEl?.hide();
  }

  private async renderActive(): Promise<void> {
    const body = this.bodyEl;
    if (!body) return;
    const view = this.currentProfile();
    const base = this.renderedProfile();
    // In focus mode, drop the leading gutter (selection + source) for a clean, maximal reading view.
    const profile = this.focusMode && base ? { ...base, sourceColumn: false, rowSelection: false } : base;
    if (!view || !profile) {
      body.empty();
      body.createDiv({
        cls: "kvs-empty",
        text: this.fileMode ? "Couldn't read this view file." : "No views yet. Use ＋ New to create one.",
      });
      this.renderPager(null);
      this.refreshBadges();
      return;
    }
    const layoutId = this.currentLayout()?.id ?? "default";
    const seq = ++this.renderSeq; // supersede any in-flight render so its DOM writes are dropped

    await renderProfile({
      container: body,
      profile,
      deps: this.deps,
      component: this,
      sourcePath: this.file?.path ?? "",
      viewKey: this.viewKeyFor(view.id, layoutId),
      shouldAbort: () => seq !== this.renderSeq,
      ...(this.writeScheduler ? { overlayRow: this.writeScheduler.overlay } : {}),
      maxRows: this.deps.store.getSettings().maxRows,
      search: this.search,
      page: this.page,
      onResult: ({ rows, total, page }) => {
        this.lastRows = rows;
        this.resultsEl?.setText(`${total} ${total === 1 ? "result" : "results"}`);
        this.renderPager(page);
      },
      onSortChange: (keys) => this.patchActive({ sort: keys }),
      onSetColumnSummary: (column, fn) => {
        const cols = this.currentProfile()?.columns ?? [];
        this.patchActive({
          columns: cols.map((c) =>
            c.name === column ? { ...c, ...(fn === "none" ? { summary: undefined } : { summary: fn }) } : c,
          ),
        });
      },
      onSetViewOption: (key, value) => {
        const p = this.renderedProfile();
        if (p) this.patchActive({ view: { type: p.view.type, options: { ...p.view.options, [key]: value } } });
      },
      onResizeColumn: (name, width) => this.resizeColumn(name, width),
      onResetColumnWidth: (name) => this.resetColumnWidth(name),
      onResetAllColumnWidths: () => this.patchActive({ columnWidths: {} }),
      onHideColumn: (name) => this.hideColumn(name),
      emptyState: {
        scopeLabel: this.scopeLabel(profile),
        hasFilter: profile.filter !== null || (profile.advancedQuery ?? "").trim() !== "",
        onClearFilters: () => this.patchActive({ filter: null, advancedQuery: null }),
        onOpenSettings: () => this.editView(),
      },
      ...(this.editing ? { editing: this.editing } : {}),
      columnValues: (name: string) => this.columnValues(name),
      ...(this.editing ? { onAddRowTop: () => void this.addRowAndEdit() } : {}),
      ...(this.deps.store.getSettings().shortenNestedTags ? { shortenTags: true } : {}),
      ...(profile.academicKit ? { onFetchDoiValues: (doi: string) => this.academic.fetchDoiValues(doi) } : {}),
      ...(profile.academicKit ? { onFindCitations: (doi: string) => this.academic.findCitationsFor(doi) } : {}),
      ...(profile.academicKit ? { onCite: (citeKey: string) => this.insertCitation(citeKey) } : {}),
      ...(profile.academicKit
        ? {
            onFetchDoi: (row: Row) => void this.academic.fillFromDoi(row, profile),
            onFetchZotero: (row: Row) => void this.academic.fillFromZotero(row, profile),
          }
        : {}),
      // Promotion is available on every view (not only academic ones) unless this view turned it off.
      ...(promotedNotesEnabled(profile) ? { onPromote: (row: Row) => void this.promoteToNote(row, profile) } : {}),
      ...(this.deps.store.getSettings().enableRowCopy
        ? {
            onCopyRows: (rows: readonly Row[], format?: CopyFormat) => this.copyRows(rows, profile, format),
            copyOnShortcut: this.deps.store.getSettings().copyUseShortcut,
            copyOptions: {
              includeHeader: this.deps.store.getSettings().copyIncludeHeader,
              stripLinks: this.deps.store.getSettings().copyLinkHandling === "text",
              onToggleHeader: () =>
                this.deps.store.updateSettings({ copyIncludeHeader: !this.deps.store.getSettings().copyIncludeHeader }),
              onToggleStripLinks: () =>
                this.deps.store.updateSettings({
                  copyLinkHandling: this.deps.store.getSettings().copyLinkHandling === "text" ? "keep" : "text",
                }),
            },
          }
        : {}),
    });
    if (!this.focusMode) this.renderHint(profile);
    this.refreshBadges();
  }

  /** Copy the given rows to the clipboard in the chosen format (default = smart multi-format). */
  private copyRows(rows: readonly Row[], profile: Profile, format: CopyFormat = "markdown"): void {
    if (rows.length === 0) return;
    const settings = this.deps.store.getSettings();
    const columns = resolveColumns(profile, this.lastRows);
    const payload = buildClipboardFor(format, rows, columns, {
      linkHandling: settings.copyLinkHandling,
      includeHeader: settings.copyIncludeHeader,
      includeHtml: settings.copyIncludeHtml,
    });
    void writeClipboard(payload).then((ok) =>
      new Notice(ok ? `Copied ${rows.length} row${rows.length === 1 ? "" : "s"} to the clipboard.` : "Couldn't access the clipboard."),
    );
  }

  /** Slice 3: copy the current view as a live `knowledge-view` embed block. */
  private copyViewBlock(profile: Profile): void {
    void writeClipboard({ plain: buildViewBlock(profile) }).then((ok) =>
      new Notice(ok ? "Copied a live-view block — paste it into any note to embed this view." : "Couldn't access the clipboard."),
    );
  }

  // ---- one-time contextual hints ----
  /** The first still-relevant, not-yet-dismissed hint for this view, or null. */
  private activeHint(profile: Profile): { id: string; text: string; action?: { label: string; run: () => void } } | null {
    const seen = new Set(this.deps.store.getSettings().seenHints ?? []);
    const cols = resolveColumns(profile, this.lastRows);

    if (!seen.has("try-calendar") && profile.view.type !== "calendar") {
      const dateCol = cols.find((c) => c.typeId === "date");
      if (dateCol) {
        return {
          id: "try-calendar",
          text: "This view has a date column — you can see these rows on a calendar.",
          action: {
            label: "Switch to Calendar",
            run: () =>
              this.patchActive({ view: { type: "calendar", options: { ...profile.view.options, dateField: dateCol.name } } }),
          },
        };
      }
    }
    if (!seen.has("board-drag") && profile.view.type === "kanban") {
      return { id: "board-drag", text: "Tip: drag cards between columns to move them between groups — the change saves to your note." };
    }
    if (!seen.has("edit-writeback") && this.editing && cols.some((c) => c.editable)) {
      return {
        id: "edit-writeback",
        text: "Tip: double-click any cell to edit it. Changes write back to the source note — undo them with the “Undo last change” command.",
      };
    }
    if (!seen.has("try-layouts") && (profile.layouts?.length ?? 0) <= 1 && this.lastRows.length > 0) {
      return {
        id: "try-layouts",
        text: "One view can show the same data several ways. Add a board or calendar layout alongside this one — they share the same rows and filter.",
        action: { label: "Add a layout", run: () => this.addLayoutFromMenu() },
      };
    }
    return null;
  }

  private renderHint(profile: Profile): void {
    const host = this.hintEl;
    if (!host) return;
    host.empty();
    const hint = this.activeHint(profile);
    if (!hint) {
      host.hide();
      return;
    }
    host.show();
    const bar = host.createDiv({ cls: "kvs-hint" });
    setIcon(bar.createSpan({ cls: "kvs-hint-icon" }), "lightbulb");
    bar.createSpan({ cls: "kvs-hint-text", text: hint.text });
    if (hint.action) {
      const act = bar.createEl("button", { cls: "kvs-hint-action", text: hint.action.label });
      act.addEventListener("click", () => {
        this.dismissHint(hint.id); // store change re-renders
        hint.action?.run();
      });
    }
    const dismiss = bar.createEl("button", { cls: "clickable-icon kvs-hint-dismiss" });
    setIcon(dismiss, "x");
    setTooltip(dismiss, "Dismiss this tip");
    dismiss.addEventListener("click", () => this.dismissHint(hint.id));
  }

  private dismissHint(id: string): void {
    const seen = this.deps.store.getSettings().seenHints ?? [];
    if (!seen.includes(id)) this.deps.store.updateSettings({ seenHints: [...seen, id] }); // re-renders via onStoreChange
  }

  /** A human-readable description of a profile's scope, for empty-state guidance. */
  private scopeLabel(profile: Profile): string {
    const folders = profile.scope.folders.filter((f) => f.trim() !== "");
    if (profile.scope.mode !== "folders" || folders.length === 0) return "your whole vault";
    if (folders.length === 1) return `the “${folders[0]}” folder`;
    return `${folders.length} folders`;
  }
}
