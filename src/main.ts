import { Notice, Plugin, TFile, type Editor } from "obsidian";
import { SearchIndexer } from "./workspace/search-indexer";
import { applyDevicePolicy } from "./workspace/search-extract";
import { currentDevice } from "./util/device";
import { SEARCH_VIEW_TYPE, SearchView, openSearchView } from "./workspace/search-view";
import { captureFromClipboard, captureColumnsFor } from "./workspace/capture-command";
import { CaptureService } from "./services/capture/capture-service";
import { BridgeService } from "./services/bridge/bridge-service";
import { runBridgeSearch } from "./services/search/bridge-search";
import { openQuickSearch } from "./workspace/quick-search-modal";
import { OcrPipeline } from "./services/search/ocr/pipeline";
import { RELATED_VIEW_TYPE, RelatedNotesView, openRelatedView } from "./workspace/related-notes-view";
import { ZOTERO_LIBRARY_VIEW_TYPE, ZoteroLibraryView, openZoteroLibraryView } from "./workspace/zotero-library-view";
import { openZoteroCollectionPicker } from "./workspace/zotero-collection-modal";
import { LocalApiZoteroProvider } from "./services/zotero/local-api-provider";
import type { ZoteroLibraryItem } from "./services/zotero/provider";
import { createZoteroFetcher } from "./workspace/zotero-transport";
import { ZOTERO_DOC_PREFIX, zoteroSearchDocs } from "./services/zotero/zotero-search-docs";
import { createOrOpenLiteratureNote, indexLiteratureNotes, literatureNoteKey, refreshLiteratureNoteAnnotations } from "./services/notes/literature-note";
import { ZoteroLibraryCache } from "./services/zotero/zotero-library-cache";
import { updateDedicatedNoteIndex, removeFromDedicatedNoteIndex } from "./services/notes/dedicated-note";
import { fetchZoteroAnnotations } from "./services/annotations/zotero-client";
import type { KvsAnnotation } from "./domain/index";
import { LocalIndexBackend, VaultIndexBackend, type IndexBackend } from "./workspace/index-backend";
import {
  ExtractorRegistry,
  TABLE_EXTRACTOR_ID,
  parseMarkdownTables,
  createDefaultColumnTypeRegistry,
  tableExtractor,
  frontmatterExtractor,
  taskExtractor,
  inlineFieldExtractor,
} from "./domain/index";
import { PromotionService } from "./services/notes/promote-service";
import { WebAnnotationService } from "./services/web-annotations/web-annotation-service";
import type { StoredAnnotation } from "../shared/annotations";
import { findDedicatedNote } from "./services/notes/dedicated-note";
import { referencesToNote, type ImportedRef, DataService, ARCHIVE_EXTENSION, KVS_PACK_EXTENSION, KVS_VIEW_EXTENSION, ProfileStore, UndoManager, WriterService, createProfile, migrateData, xlsxExtractor } from "./services/index";
import { ObsidianVaultGateway } from "./obsidian/index";
import {
  createDefaultCellEditorRegistry,
  createDefaultCellRendererRegistry,
  createDefaultViewRegistry,
  parseKvsMarker,
  rebuildMarkdownTable,
  type RenderProfileDeps,
} from "./views/index";
import { ViewBlockController, type ProcessorDeps } from "./codeblock/processor";
import { registerKvsBasesViews } from "./obsidian/bases/register";
import { DashboardView, DASHBOARD_VIEW_TYPE } from "./workspace/dashboard-view";
import { pendingFocusStore } from "./views/view-state";
import { registerAttachmentPanel } from "./workspace/attachment-panel";
import { registerAnnotationDecorator } from "./workspace/annotation-decorator";
import { syncPaperAnnotations } from "./workspace/annotation-sync";
import { buildHighlightSynthesis } from "./workspace/synthesis";
import { registerPdfAnnotatorToolbar, PdfOverlayManager, HIGHLIGHT_COLORS } from "./workspace/pdf-annotator";
import { WelcomeModal } from "./workspace/welcome-modal";
import { TemplatePickerModal } from "./workspace/template-picker-modal";
import { ViewBrowserModal } from "./workspace/view-browser-modal";
import { ImportReferencesModal } from "./workspace/import-references-modal";
import { AddByDoiModal } from "./workspace/add-by-doi-modal";
import type { StarterTemplate } from "./workspace/templates";
import { availableTemplates } from "./workspace/templates";
import { BackupPackView, BACKUP_VIEW_TYPE } from "./workspace/backup-pack-view";
import { ArchiveView, ARCHIVE_VIEW_TYPE } from "./workspace/archive-view";
import { BackupExportModal, type BackupExportOptions } from "./workspace/backup-export-modal";
import { backupAllViews } from "./workspace/backup-runner";
import { ImportModal } from "./workspace/import-modal";
import { KnowledgeViewsSettingTab } from "./settings/settings-tab";

const INSERT_TEMPLATE = ["```knowledge-view", "view: table", "folder: ", "limit: 25", "```", ""].join("\n");

/**
 * Composition root. Phase 4 adds the visible layer on top of the Phase 3
 * services: a live code-block processor, a workspace pane, a settings tab, a
 * ribbon icon, and commands — all driven by the cached DataService and the
 * pluggable view + cell-renderer registries.
 */
export default class KnowledgeViewsStudioPlugin extends Plugin {
  private pdfOverlayManager?: PdfOverlayManager;
  /** The local browser bridge. Present but inert unless switched on in settings. */
  bridge?: BridgeService;
  private searchIndexer?: SearchIndexer;
  private profileStore?: ProfileStore;
  private zoteroLibraryCache?: ZoteroLibraryCache;
  private ocrPipeline?: OcrPipeline;
  private dataService?: DataService;

  override async onload(): Promise<void> {
    const { data, warnings } = migrateData(await this.loadData());
    if (warnings.length > 0) {
      console.warn(`[Knowledge Views Studio] Migrated saved data with notes:\n- ${warnings.join("\n- ")}`);
    }

    const registry = createDefaultColumnTypeRegistry();
    const extractors = new ExtractorRegistry().register(tableExtractor).register(frontmatterExtractor).register(taskExtractor).register(inlineFieldExtractor).register(xlsxExtractor);
    const store = new ProfileStore({ data, persist: (snapshot) => this.saveData(snapshot) });
    const zoteroLibraryCache = new ZoteroLibraryCache();
    this.zoteroLibraryCache = zoteroLibraryCache;
    const gateway = new ObsidianVaultGateway(this.app, (ref) => this.registerEvent(ref), () =>
      store.getSettings().enableExcelSources ? ["md", "xlsx"] : ["md"],
    );

    const applyImageVars = (): void => {
      const s = store.getSettings();
      document.body.style.setProperty("--kvs-img-max-h", s.imageMaxHeight > 0 ? `${s.imageMaxHeight}px` : "none");
      document.body.style.setProperty("--kvs-img-max-w", s.imageMaxWidth > 0 ? `${s.imageMaxWidth}px` : "100%");
    };
    applyImageVars();
    this.register(store.onChange(applyImageVars));
    const warnedSources = new Set<string>();
    const dataService = new DataService({
      gateway,
      registry,
      extractors,
      getSettings: () => store.getSettings(),
      zoteroProvider: () => new LocalApiZoteroProvider(store.getSettings().zoteroApiBase, createZoteroFetcher()),
      onSourceWarning: (path, error) => {
        console.warn(`[KVS] Skipped unreadable source ${path}:`, error);
        if (warnedSources.has(path)) return;
        warnedSources.add(path);
        const name = path.split("/").pop() ?? path;
        const reason = error instanceof Error ? error.message : "could not be read";
        new Notice(`Knowledge Views: skipped "${name}" — ${reason}.`, 7000);
      },
    });
    this.profileStore = store;
    this.dataService = dataService;

    const undo = new UndoManager();
    const renderDeps: RenderProfileDeps = {
      app: this.app,
      dataService,
      views: createDefaultViewRegistry(),
      cellRenderers: createDefaultCellRendererRegistry(),
      cellEditors: createDefaultCellEditorRegistry(),
      registry,
      writer: new WriterService(gateway, { excelBackup: () => store.getSettings().enableExcelBackup }),
      undo,
    };
    const deps: ProcessorDeps = { ...renderDeps, store, zoteroLibraryCache };

    this.registerMarkdownCodeBlockProcessor("knowledge-view", (source, el, ctx) => {
      ctx.addChild(new ViewBlockController(el, source, ctx.sourcePath, deps));
    });

    this.registerView(DASHBOARD_VIEW_TYPE, (leaf) => new DashboardView(leaf, deps));

    // obsidian://kvs-open?view=<id>&ref=<rowRef> — the browser companion's "open in view, at this row".
    // The focus request is parked in a one-shot store the table consumes on render, because at this moment
    // the view may not even exist yet, let alone have rendered the row.
    this.registerObsidianProtocolHandler("kvs-open", (params) => {
      const viewId = typeof params["view"] === "string" ? params["view"] : "";
      const ref = typeof params["ref"] === "string" ? params["ref"] : "";
      if (viewId === "" && ref === "") return;
      if (viewId !== "" && this.profileStore?.getProfile(viewId)) {
        this.profileStore.setActiveProfile(viewId);
        if (ref !== "") pendingFocusStore.set(viewId, ref);
      }
      void this.activateDashboard();
    });
    // Saved view files (.kvsview) open in this same view, file-backed — KVS's take on .base
    // files: a complete, self-contained dashboard that can be opened in its own pane.
    this.registerExtensions([KVS_VIEW_EXTENSION], DASHBOARD_VIEW_TYPE);

    // Backup packages (.kvspack): a frozen snapshot of a view's settings + all its data, opened
    // in a viewer that can restore them to the vault.
    this.registerView(BACKUP_VIEW_TYPE, (leaf) => new BackupPackView(leaf, deps));
    this.registerExtensions([KVS_PACK_EXTENSION], BACKUP_VIEW_TYPE);

    // Archival packages (.kvsarchive): a ZIP preservation master, opened in a viewer that can
    // verify checksums and restore the data + attachments.
    this.registerView(ARCHIVE_VIEW_TYPE, (leaf) => new ArchiveView(leaf, deps));
    this.registerExtensions([ARCHIVE_EXTENSION], ARCHIVE_VIEW_TYPE);

    // Lend KVS's Board, Calendar, and Summary views to Obsidian Bases when available.
      registerKvsBasesViews(this, { cellRenderers: renderDeps.cellRenderers });

    this.addRibbonIcon("layout-grid", "Open Knowledge Views", () => void this.activateDashboard());

    const annotationSyncOpts = () => ({ zotero: { enabled: store.getSettings().zoteroApiEnabled, base: store.getSettings().zoteroApiBase }, zotflow: { enabled: store.getSettings().zotflowInteropEnabled }, themeSpec: store.getSettings().annotationThemes });
    const overlayManager = new PdfOverlayManager(this.app, annotationSyncOpts);
    this.pdfOverlayManager = overlayManager;
    registerAnnotationDecorator(this, (sourcePath, blockId) => void overlayManager.queueDelete(sourcePath, blockId), (sourcePath, blockId) => void overlayManager.editAnnotation(sourcePath, blockId));
    registerPdfAnnotatorToolbar(this, overlayManager);
    this.registerEvent(this.app.workspace.on("active-leaf-change", () => overlayManager.onLeafChange()));
    this.registerDomEvent(window, "blur", () => void overlayManager.flushAll());

    // Keep the dedicated-note frontmatter index current *incrementally* — apply each file's change to the
    // index instead of rebuilding it, so editing a cell (which changes one file's metadata) doesn't rescan
    // the whole vault on the next render. Searching/sorting/scrolling touch no files and so touch nothing here.
    this.registerEvent(this.app.metadataCache.on("changed", (file) => updateDedicatedNoteIndex(this.app, file)));
    this.registerEvent(this.app.metadataCache.on("deleted", (file) => removeFromDedicatedNoteIndex(file.path)));
    this.registerEvent(
      this.app.vault.on("rename", (file, oldPath) => {
        removeFromDedicatedNoteIndex(oldPath);
        if (file instanceof TFile) updateDedicatedNoteIndex(this.app, file);
      }),
    );

    // ---- full-text search index ----
    const makeBackend = (): IndexBackend => {
      const st = store.getSettings();
      return st.indexLocation === "vault"
        ? new VaultIndexBackend(this.app, st.indexFolder)
        : new LocalIndexBackend(`kvs-search-${this.app.vault.getName()}`);
    };
    // The phone's veto (see applyDevicePolicy): a laptop's "index every PDF" and "use the neural engine"
    // sync to a device that cannot afford either, and it never agreed to them.
    const device = currentDevice();
    const searchIndexer = new SearchIndexer(this.app, () => {
      const st = store.getSettings();
      return applyDevicePolicy(
        {
          attachments: st.indexAttachments,
          attachmentsOnMobile: st.indexAttachmentsOnMobile,
          excel: st.enableExcelSources,
          ocr: st.ocrEnabled,
          semanticEngine: st.semanticEngine,
          relevance: st.relevance,
        },
        device,
      );
    }, makeBackend(), async () => {
      // Zotero search integration: when enabled, feed the live library and its annotations into the same
      // index as vault files. Guarded end to end — off by default, and any failure (Zotero not running,
      // API off) contributes nothing rather than breaking the build.
      if (!store.getSettings().indexZotero) return null;
      const provider = new LocalApiZoteroProvider(store.getSettings().zoteroApiBase, createZoteroFetcher());
      if (!(await provider.ping())) return null;
      // Items come from the shared cache (so a recent fill/promote/library-view fetch is reused); annotations
      // aren't cached, so fetch those directly. Both in parallel.
      const [items, annotations] = await Promise.all([zoteroLibraryCache.getItems(provider), provider.listAllAnnotations()]);
      return { prefix: ZOTERO_DOC_PREFIX, docs: zoteroSearchDocs(items, annotations) };
    });
    this.searchIndexer = searchIndexer;
    searchIndexer.register(this);
    // Offline OCR pipeline: recognizes text in images in idle time and folds it into the search index.
    // Owned here, fed by the indexer's image hook, and desktop-gated internally.
    const ocrDir = this.manifest.dir ?? `${this.app.vault.configDir}/plugins/${this.manifest.id}`;
    const ocrPipeline = new OcrPipeline(
      this.app,
      ocrDir,
      () => [...store.getSettings().ocrLanguages],
      () => store.getSettings().ocrEnabled,
      (file, text) => searchIndexer.indexImageText(file, text),
    );
    this.ocrPipeline = ocrPipeline;
    searchIndexer.setOcr(ocrPipeline);
    void ocrPipeline.init(this);
    searchIndexer.setEnableAttachments(() => store.updateSettings({ indexAttachments: true }));
    this.registerView(SEARCH_VIEW_TYPE, (leaf) => new SearchView(leaf, searchIndexer));
    this.registerView(RELATED_VIEW_TYPE, (leaf) => new RelatedNotesView(leaf, searchIndexer));
    this.addCommand({
      id: "kvs-related-notes",
      name: "Open related notes",
      callback: () => void openRelatedView(this.app),
    });

    this.addCommand({
      id: "kvs-quick-search",
      name: "Quick search (jump to a note)",
      callback: () => openQuickSearch(this.app, searchIndexer),
    });

    this.addCommand({
      id: "kvs-capture-clipboard",
      name: "Capture clipboard into a view",
      callback: () => void captureFromClipboard({ app: this.app, store, dataService }),
    });

    // The local browser bridge. Constructed always, started only if switched on — so nothing about it costs
    // anything until someone asks for it.
    const captureService = new CaptureService(this.app);
    this.bridge = new BridgeService({
      app: this.app,
      pluginVersion: this.manifest.version,
      settings: () => store.getSettings().bridge,
      saveSettings: (patch) => {
        const current = store.getSettings().bridge;
        store.updateSettings({ bridge: { ...current, ...patch } });
      },
      context: () => ({
        listProfiles: () => store.listProfiles(),
        viewData: async (profile) => {
          const result = await dataService.query({ ...profile, pageSize: null }, {});
          return { rows: result.rows, columns: captureColumnsFor(profile, result.rows) };
        },
        capture: captureService,
        onCaptured: (path) => dataService.invalidate(path),
        // Notes and tags write-back destinations, read live so a settings change takes effect at once.
        annotationWriteback: () => store.getSettings().annotationWriteback,
        // Reuses the same writer the app itself edits through, so a change from the browser goes down
        // exactly the path an in-app edit does — including its undo history.
        editCells: async (edits) => {
          await renderDeps.writer.editCells(edits);
        },
        // Annotation storage next to the plugin's own data, so it syncs wherever the vault syncs.
        webAnnotations: (() => {
          const service = new WebAnnotationService({
            app: this.app,
            storePath: `${this.manifest.dir ?? `${this.app.vault.configDir}/plugins/knowledge-views-studio`}/web-annotations.json`,
          });
          return {
            list: (url: string) => service.list(url),
            save: (annotation: StoredAnnotation) => service.save(annotation),
            remove: (url: string, id: string) => service.remove(url, id),
            removeAll: (url: string) => service.removeAll(url),
            removeFromDedicatedNote: (url: string, annotation: StoredAnnotation) =>
              service.removeFromDedicatedNote(url, annotation),
            appendToDedicatedNote: (
              matchKey: string,
              matchValue: string,
              annotation: StoredAnnotation,
              opts?: { note?: boolean; tags?: boolean; tagsToProperty?: boolean },
            ) => service.appendToDedicatedNote(matchKey, matchValue, annotation, opts),
          };
        })(),
        // Deletion through the same writer as every other edit — snapshot first, so it shares undo.
        deleteRows: async (rows) => {
          const paths = [...new Set(rows.map((r) => r.provenance.filePath))];
          const snapshot = await renderDeps.writer.snapshot(paths);
          const result = await renderDeps.writer.deleteRows(rows.map((r) => r.provenance));
          if (!result.ok) return 0;
          renderDeps.undo.push({
            label: "Delete row (from browser)",
            undo: async () => {
              await renderDeps.writer.restore(snapshot);
              for (const path of snapshot.keys()) renderDeps.dataService.invalidate(path);
            },
          });
          return rows.length;
        },
        noteForUrl: (url: string) => findDedicatedNote(this.app, "source", url)?.path ?? null,
        // The page's dedicated note goes to the vault's trash — recoverable, because notes are writing.
        trashNoteForUrl: async (url: string) => {
          const note = findDedicatedNote(this.app, "source", url);
          if (note === null) return null;
          const path = note.path;
          await this.app.fileManager.trashFile(note);
          return path;
        },
        // Promotion through the same writer as every other edit, so the link backfill shares its undo path.
        promote: (profile, row, columns) => {
          const service = new PromotionService({
            app: this.app,
            editCell: async (target, column, value) => {
              await renderDeps.writer.editCells([{ provenance: target.provenance, column, value }]);
            },
          });
          const scopeFolder = profile.scope.mode === "folders" ? (profile.scope.folders[0] ?? "") : "";
          return service.promote(
            {
              academicKit: profile.academicKit,
              ...(profile.dedicatedNoteKey !== undefined ? { dedicatedNoteKey: profile.dedicatedNoteKey } : {}),
              ...(profile.promotedNotesFolder !== undefined
                ? { promotedNotesFolder: profile.promotedNotesFolder }
                : {}),
              ...(profile.promotedNoteTemplate !== undefined
                ? { promotedNoteTemplate: profile.promotedNoteTemplate }
                : {}),
              ...(scopeFolder !== "" ? { scopeFolder } : {}),
            },
            row,
            columns,
            store.getSettings().promotedNoteTemplate,
          );
        },
        // Only offered when search exists at all; the endpoint then says so rather than returning an empty
        // list that would read as "nothing found".
        ...(searchIndexer
          ? { search: (request) => runBridgeSearch(searchIndexer, request) }
          : {}),
      }),
    });
    void this.bridge.sync();
    this.register(() => void this.bridge?.stop());

    // Live Zotero library view — reads Zotero's local API (always current, unlike a static export). The
    // provider is built per-open from settings; it is read-only today (Zotero's local API is), with the
    // write seam already in place for when that changes.
    this.registerView(ZOTERO_LIBRARY_VIEW_TYPE, (leaf) => {
      const provider = new LocalApiZoteroProvider(store.getSettings().zoteroApiBase, createZoteroFetcher());
      return new ZoteroLibraryView(
        leaf,
        provider,
        (item) => void this.openZoteroItem(item),
        (items) => void this.createZoteroDashboard(items),
        (items) => this.createLiteratureNotes(items),
        () => new Set(indexLiteratureNotes(this.app).keys()),
        zoteroLibraryCache,
      );
    });
    this.addCommand({
      id: "kvs-open-zotero-library",
      name: "Open Zotero library",
      callback: () => void openZoteroLibraryView(this.app),
    });
    this.addCommand({
      id: "kvs-create-zotero-dashboard",
      name: "Create Zotero library dashboard (all layouts)",
      callback: () => void this.createZoteroDashboard(),
    });
    this.addCommand({
      id: "kvs-create-zotero-collection-dashboard",
      name: "Create Zotero dashboard from a collection…",
      callback: () => {
        const provider = new LocalApiZoteroProvider(store.getSettings().zoteroApiBase, createZoteroFetcher());
        void openZoteroCollectionPicker(this.app, provider, (key, name) => {
          void this.createZoteroDashboard(undefined, key, name);
        });
      },
    });
    this.addCommand({
      id: "kvs-refresh-literature-note",
      name: "Refresh literature note from Zotero",
      checkCallback: (checking) => {
        const file = this.app.workspace.getActiveFile();
        const key = file ? literatureNoteKey(this.app, file) : "";
        if (key === "" || !file) return false; // only offered on a note that carries a zotero-key
        if (!checking) void this.refreshLiteratureNote(file, key);
        return true;
      },
    });
    this.addRibbonIcon("search", "Search vault (KVS)", () => void openSearchView(this.app));
    this.app.workspace.onLayoutReady(() => {
      // Search is a feature, not a tax: if it's switched off, KVS never reads the vault for it.
      if (!store.getSettings().enableSearch) return;
      void searchIndexer.load().then(() => {
        // Only announce indexing when there is real work — on a warm start almost nothing changes, and
        // a progress notice that flashes on every launch is just noise.
        let notice: Notice | undefined;
        return searchIndexer
          .buildAll((done, total) => {
            if (!notice && total > 25 && done < total) notice = new Notice("KVS: building search index…", 0);
            notice?.setMessage(`KVS: indexing ${done}/${total}…`);
          })
          .then(() => {
            notice?.hide();
            const st = searchIndexer.status();
            if (notice && st.docCount > 0) new Notice(`KVS search index ready: ${st.docCount} items from ${st.fileCount} files.`, 4000);
          });
      });
    });
    this.addCommand({
      id: "kvs-rebuild-search-index",
      name: "Rebuild search index",
      callback: () => {
        const notice = new Notice("KVS: rebuilding search index…", 0);
        void searchIndexer.rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`)).then(() => {
          notice.hide();
          new Notice(`KVS search index rebuilt: ${searchIndexer.status().docCount} items.`, 4000);
        });
      },
    });
    this.addCommand({
      id: "kvs-refresh-zotero-search",
      name: "Refresh Zotero in search index",
      checkCallback: (checking) => {
        if (!store.getSettings().indexZotero) return false; // only when Zotero search is enabled
        if (!checking) {
          const notice = new Notice("KVS: refreshing Zotero in search…", 0);
          this.zoteroLibraryCache?.invalidate();
          void searchIndexer.refreshExternalDocs().then((n) => {
            notice.hide();
            new Notice(`Zotero search refreshed: ${n} item${n === 1 ? "" : "s"} indexed.`, 4000);
          }).catch(() => {
            notice.hide();
            new Notice("Couldn't refresh Zotero in search.");
          });
        }
        return true;
      },
    });
    this.addCommand({
      id: "kvs-search-vault",
      name: "Search vault",
      callback: () => void openSearchView(this.app),
    });
    this.addCommand({
      id: "kvs-build-semantic-index",
      name: "Build semantic search index (offline)",
      callback: () => {
        const notice = new Notice("KVS: building semantic index…", 0);
        void searchIndexer.buildSemantic((done, total) => notice.setMessage(`KVS: semantic ${done}/${total}…`)).then(() => {
          notice.hide();
          new Notice("KVS semantic index ready. Toggle Semantic mode in search.", 5000);
        });
      },
    });
    this.registerDomEvent(window, "scroll", () => overlayManager.captureActiveScroll(), true);
    registerAttachmentPanel(this, annotationSyncOpts);
    this.addCommand({
      id: "open-dashboard",
      name: "Open dashboard",
      callback: () => void this.activateDashboard(),
    });
    this.addCommand({
      id: "insert-view-block",
      name: "Insert Knowledge View block",
      editorCallback: (editor: Editor) => editor.replaceSelection(INSERT_TEMPLATE),
    });
    this.addCommand({
      id: "undo-last-change",
      name: "Undo last change",
      callback: () =>
        void (async () => {
          const label = await undo.undo();
          new Notice(label ? `Undone: ${label}` : "Nothing to undo.");
        })(),
    });
    this.addCommand({
      id: "import-table",
      name: "Import table to a new note (CSV, Markdown, Excel)",
      callback: () => new ImportModal(this.app).open(),
    });
    this.addCommand({
      id: "create-view-from-note",
      name: "Create view from current note's table",
      callback: () => void this.createViewFromActiveNote(),
    });
    this.addCommand({
      id: "getting-started",
      name: "Open getting-started guide",
      callback: () => this.showWelcome(),
    });
    this.addCommand({
      id: "create-from-template",
      name: "Create view from starter template",
      callback: () => new TemplatePickerModal(this.app, (t) => void this.createFromTemplate(t), availableTemplates(store.getSettings().enableAcademicKit)).open(),
    });
    this.addCommand({
      id: "browse-saved-views",
      name: "Browse saved views (.kvsview files)",
      callback: () => new ViewBrowserModal(this.app).open(),
    });
    this.addCommand({
      id: "paste-rows-as-view",
      name: "Create view from pasted rows",
      callback: () => void this.pasteRowsAsView(),
    });
    this.addCommand({
      id: "toggle-focus-mode",
      name: "Toggle focus mode (maximize the view)",
      // checkCallback, not callback: a command that only works with a Knowledge View open should be
      // absent from the palette otherwise, which is Obsidian's convention — not present-but-scolding.
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) view.toggleFocusMode();
        return true;
      },
    });
    this.addCommand({
      id: "import-references",
      name: "Import references (BibTeX, CSV)",
      callback: () => {
        if (!store.getSettings().enableAcademicKit) {
          new Notice("Enable the Academic Research kit in settings to import references.");
          return;
        }
        const active = this.app.workspace.getActiveViewOfType(DashboardView);
        new ImportReferencesModal(this.app, (refs, viewName) => {
          if (active && active.hasImportTarget()) void active.importReferences(refs);
          else void this.createFromReferences(refs, viewName);
        }).open();
      },
    });
    this.addCommand({
      id: "add-papers-by-doi",
      name: "Add papers by DOI (current view)",
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) new AddByDoiModal(this.app, (dois) => void view.captureByDoi(dois)).open();
        return true;
      },
    });
    this.addCommand({
      id: "fill-missing-from-doi",
      name: "Fill missing metadata from DOI (current view)",
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) void view.bulkFillFromDoi();
        return true;
      },
    });
    this.addCommand({
      id: "highlight-pdf-selection",
      name: "Highlight selection in PDF",
      callback: () => void overlayManager.addHighlightFromSelection(HIGHLIGHT_COLORS[0]!.hex),
    });
    this.addCommand({
      id: "build-highlight-synthesis",
      name: "Build highlight synthesis (group highlights by theme)",
      callback: () => void buildHighlightSynthesis(this.app),
    });
    this.addCommand({
      id: "sync-paper-annotations",
      name: "Sync PDF annotations into this note",
      checkCallback: (checking) => {
        const file = this.app.workspace.getActiveFile();
        if (!file || file.extension !== "md") return false;
        if (!checking) void syncPaperAnnotations(this.app, file, annotationSyncOpts());
        return true;
      },
    });
    this.addCommand({
      id: "insert-attachment-panel",
      name: "Insert paper attachment panel",
      editorCallback: (editor) => {
        editor.replaceSelection("\n## Attachments\n\n```kvs-paper\n```\n");
      },
    });
    this.addCommand({
      id: "find-duplicate-dois",
      name: "Find duplicate DOIs across the library",
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) void view.findDuplicateDois();
        return true;
      },
    });
    this.addCommand({
      id: "find-citation-links",
      name: "Find citation links across the library (OpenAlex)",
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) void view.findCitationLinks();
        return true;
      },
    });
    this.addCommand({
      id: "shard-library",
      name: "Split this library across multiple files",
      checkCallback: (checking) => {
        const view = this.app.workspace.getActiveViewOfType(DashboardView);
        if (!view) return false;
        if (!checking) void view.openShardModal();
        return true;
      },
    });

    // First run only: greet the user and offer the two fastest paths to a working view.
    this.app.workspace.onLayoutReady(() => {
      if (!store.getSettings().onboardingSeen) {
        store.updateSettings({ onboardingSeen: true });
        this.showWelcome();
      }
    });

    // Right-click a note → build a view from its table, a low-friction entry point for new users.
    this.registerEvent(
      this.app.workspace.on("file-menu", (menu, file) => {
        if (!(file instanceof TFile) || file.extension !== "md") return;
        menu.addItem((item) =>
          item
            .setTitle("Create Knowledge View from table")
            .setIcon("layout-grid")
            .onClick(() => void this.createViewFromActiveNote(file)),
        );
      }),
    );
    this.addCommand({
      id: "backup-all-views",
      name: "Back up all views",
      callback: () => {
        const profiles = store.listProfiles();
        if (profiles.length === 0) {
          new Notice("There are no saved views to back up.");
          return;
        }
        const defaults: BackupExportOptions = {
          format: "pack",
          scope: "all",
          includeAttachments: true,
          includeExternal: true,
          encrypt: false,
          password: "",
          dateStamp: false,
          folder: "KVS Backups",
          filename: "",
        };
        new BackupExportModal(
          this.app,
          defaults,
          false,
          (options) =>
            void (async () => {
              new Notice(`Backing up ${profiles.length} view(s)…`);
              const report = await backupAllViews(this.app, deps, options, (m) => new Notice(m));
              new Notice(
                report.failed.length === 0
                  ? `Backed up ${report.ok} view(s) to ${report.folder}`
                  : `Backed up ${report.ok}, failed ${report.failed.length}: ${report.failed.join(", ")}`,
              );
            })(),
          true,
        ).open();
      },
    });

    this.addSettingTab(
      new KnowledgeViewsSettingTab(this.app, this, {
        store,
        views: renderDeps.views,
        registry,
        dataService,
        onGettingStarted: () => this.showWelcome(),
        searchIndexer,
        ...(this.bridge ? { bridge: this.bridge } : {}),
      }),
    );
  }

  override onunload(): void {
    void this.pdfOverlayManager?.flushAll();
    void this.searchIndexer?.persist();
    void this.ocrPipeline?.destroy();
    void this.profileStore?.flush();
    this.profileStore?.dispose();
    this.dataService?.dispose();
  }

  private async activateDashboard(): Promise<void> {
    const { workspace } = this.app;
    let leaf = workspace.getLeavesOfType(DASHBOARD_VIEW_TYPE)[0];
    if (!leaf) {
      const created = workspace.getLeaf(true);
      await created.setViewState({ type: DASHBOARD_VIEW_TYPE, active: true });
      leaf = created;
    }
    if (leaf) void workspace.revealLeaf(leaf);
  }

  /**
   * Open a Zotero library item the sensible way, given what we have. A future version can create/open a
   * literature note (as zotero-lib-view does) or hand a PDF attachment to ZotFlow's reader (as our
   * attachment panel already does); for now we open the item's canonical web location — its URL, or its
   * DOI resolver — which always works and never guesses at a note path the user didn't ask for.
   */
  private openZoteroItem(item: ZoteroLibraryItem): void {
    const url = item.url || (item.doi ? `https://doi.org/${item.doi}` : "");
    if (url) {
      window.open(url, "_blank");
    } else {
      new Notice(`No link available for "${item.title || item.key}".`);
    }
  }

  /**
   * Build a ready-to-use view from the table in a note — the fastest way for a new user to see the
   * plugin work on their own data. Discovery mode means no column setup: the table's own headers
   * become the columns. Scopes to the note's folder so the view keeps working as more notes are added.
   */
  private async createViewFromActiveNote(target?: TFile): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    const file = target ?? this.app.workspace.getActiveFile();
    if (!file || file.extension !== "md") {
      new Notice("Open a note that contains a table first.");
      return;
    }
    const content = await this.app.vault.cachedRead(file);
    if (parseMarkdownTables(content).length === 0) {
      new Notice(`No Markdown table found in “${file.basename}”.`);
      return;
    }
    const folder = file.parent && file.parent.path !== "/" ? file.parent.path : "";
    const profile = createProfile({
      name: `${file.basename} table`,
      scope: folder
        ? { mode: "folders", folders: [folder], includeSubfolders: false }
        : { mode: "vault", folders: [], includeSubfolders: true },
      extractors: [TABLE_EXTRACTOR_ID], // discovery mode (no columns) → the table's headers become columns
    });
    store.addProfile(profile);
    store.setActiveProfile(profile.id);
    await this.activateDashboard();
    new Notice(`Created a view from “${file.basename}”.`);
  }

  /**
   * Create a live Zotero library view that renders through the full engine — every layout (table, cards,
   * board, calendar, gallery, chart, pivot), filters, computed columns, and search over Zotero data, not a
   * bespoke one-off table. Columns carry semantic roles so the non-table layouts have sensible defaults
   * (title for cards, date for the calendar, tags for the board).
   */
  /**
   * Create — or open, if they already exist — literature notes for a set of Zotero items. Each note is a
   * first-class Obsidian note (metadata frontmatter, abstract, a durable Zotero link, and a Notes section),
   * with the paper's Zotero annotations pulled into a managed Annotations region. Idempotent by Zotero key,
   * so this never makes duplicates and re-running refreshes annotations in place. Opens the first note.
   */
  /**
   * Re-pull a literature note's annotations from Zotero and rewrite its Annotations region in place. Keeps
   * the note current as you annotate more in Zotero, without touching your own writing. Best-effort:
   * unreachable Zotero or a paper with no annotations just reports that, and never alters your prose.
   */
  private async refreshLiteratureNote(file: TFile, key: string): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    const settings = store.getSettings();
    const fetcher = createZoteroFetcher();
    const notice = new Notice("Refreshing annotations from Zotero…", 0);
    try {
      this.zoteroLibraryCache?.invalidate();
      const provider = new LocalApiZoteroProvider(settings.zoteroApiBase, fetcher);
      if (!(await provider.ping())) {
        notice.hide();
        new Notice("Can't reach Zotero. Make sure it's running with the local API enabled.");
        return;
      }
      const annotations = await fetchZoteroAnnotations(settings.zoteroApiBase, [key], fetcher);
      await refreshLiteratureNoteAnnotations(this.app, file, annotations, settings.annotationThemes);
      notice.hide();
      new Notice(annotations.length > 0 ? `Refreshed ${annotations.length} annotation${annotations.length === 1 ? "" : "s"} from Zotero.` : "No annotations in Zotero for this paper yet.");
    } catch (error) {
      notice.hide();
      new Notice(`Couldn't refresh from Zotero: ${error instanceof Error ? error.message : "unexpected error"}`);
    }
  }

  private async createLiteratureNotes(items: readonly ZoteroLibraryItem[]): Promise<void> {
    const store = this.profileStore;
    if (!store || items.length === 0) return;
    const settings = store.getSettings();
    const folder = settings.literatureNotesFolder || "Literature";
    const fetcher = createZoteroFetcher();
    const notice = new Notice(items.length === 1 ? "Preparing literature note…" : `Preparing ${items.length} literature notes…`, 0);
    let created = 0;
    let opened = 0;
    let first: TFile | null = null;
    try {
      for (const item of items) {
        // Pull this paper's annotations from Zotero so the note is populated on creation. Best-effort:
        // a paper with none, or an unreachable Zotero, simply yields an empty Annotations region.
        let annotations: KvsAnnotation[] = [];
        try {
          annotations = await fetchZoteroAnnotations(settings.zoteroApiBase, [item.key], fetcher);
        } catch {
          annotations = [];
        }
        const result = await createOrOpenLiteratureNote(this.app, item, { folder, template: settings.literatureNoteTemplate, annotations, themeSpec: settings.annotationThemes });
        if (result.created) created++;
        else opened++;
        if (!first) first = result.file;
      }
      notice.hide();
      if (first) await this.app.workspace.getLeaf(true).openFile(first);
      const bits = [created > 0 ? `Created ${created}` : "", opened > 0 ? `opened ${opened}` : ""].filter((s) => s !== "");
      new Notice(`${bits.join(", ")} literature note${created + opened === 1 ? "" : "s"}.`);
    } catch (error) {
      notice.hide();
      new Notice(`Couldn't create literature notes: ${error instanceof Error ? error.message : "unexpected error"}`);
    }
  }

  private async createZoteroDashboard(selection?: readonly ZoteroLibraryItem[], collectionKey?: string | null, collectionName?: string): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    // Scope precedence: an explicit item selection (from the library view) pins those keys; otherwise a
    // collection key scopes to that collection; otherwise the whole library. Each renders through the full
    // engine (all layouts, filters).
    const keys = selection && selection.length > 0 ? selection.map((i) => i.key) : undefined;
    const name = keys
      ? `Zotero selection (${keys.length})`
      : collectionKey
        ? `Zotero — ${collectionName ?? "collection"}`
        : "Zotero library";
    const profile = createProfile({
      name,
      scope: {
        mode: "zotero",
        folders: [],
        includeSubfolders: false,
        ...(keys ? { zoteroItemKeys: keys } : {}),
        ...(collectionKey ? { zoteroCollectionKey: collectionKey } : {}),
      },
      extractors: ["zotero-library"],
      columns: [
        { name: "Title", type: "text", role: "title" },
        { name: "Creators", type: "text" },
        { name: "Year", type: "text", role: "date" },
        { name: "Type", type: "text", role: "status" },
        { name: "Publication", type: "text" },
        { name: "Cite Key", type: "text" },
        { name: "DOI", type: "text" },
        { name: "Tags", type: "tags", role: "tags" },
        { name: "Collections", type: "tags" },
        { name: "Added", type: "date" },
        { name: "Modified", type: "date" },
      ],
    });
    store.addProfile(profile);
    store.setActiveProfile(profile.id);
    await this.activateDashboard();
    new Notice(
      keys
        ? `Created a dashboard from ${keys.length} Zotero item${keys.length === 1 ? "" : "s"}.`
        : collectionKey
          ? `Created a dashboard for the “${collectionName ?? "collection"}” collection.`
          : "Created a live Zotero library view. It reads from Zotero's local API — make sure Zotero is running.",
    );
  }

  /** Open the first-run welcome (also reachable via the "Getting started" command). */
  private showWelcome(): void {
    new WelcomeModal(this.app, {
      onUseNote: () => void this.createViewFromActiveNote(),
      onTemplate: () => new TemplatePickerModal(this.app, (t) => void this.createFromTemplate(t), availableTemplates(this.profileStore?.getSettings().enableAcademicKit ?? false)).open(),
      onBlank: () => void this.createBlankView(),
      onSearch: () => void openSearchView(this.app),
      onQuickSearch: () => {
        if (this.searchIndexer) openQuickSearch(this.app, this.searchIndexer);
      },
      academicKit: this.profileStore?.getSettings().enableAcademicKit ?? false,
    }).open();
  }

  /** Materialise a starter template: a demo note (its own folder, so the view is isolated) + a
   *  matching view, then open it. Each is fully editable and deletable like any other note/view. */
  private async createFromTemplate(template: StarterTemplate): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    const { vault } = this.app;
    try {
      await this.ensureFolder("KVS Examples");

      let folder = `KVS Examples/${template.folderName}`;
      for (let n = 2; vault.getAbstractFileByPath(folder); n++) folder = `KVS Examples/${template.folderName} ${n}`;
      await vault.createFolder(folder);
      const notePath = `${folder}/${template.noteName}.md`;
      await vault.create(notePath, template.content());

      const profile = createProfile({
        name: template.viewName,
        scope: { mode: "folders", folders: [folder], includeSubfolders: false },
        extractors: [TABLE_EXTRACTOR_ID],
        view: { type: template.viewType, options: { ...template.viewOptions } },
        ...(template.columns ? { columns: template.columns.map((c) => ({ name: c.name, type: c.type })) } : {}),
        ...(template.academicKit ? { academicKit: true } : {}),
        ...(template.group ? { group: { field: template.group.field } } : {}),
        ...(template.layouts
          ? { layouts: template.layouts.map((l) => ({ name: l.name, view: { type: l.type, options: { ...(l.options ?? {}) } } })) }
          : {}),
      });
      store.addProfile(profile);
      store.setActiveProfile(profile.id);
      await this.activateDashboard();
      new Notice(`Created the “${template.label}” example.`);
    } catch (error) {
      console.error("[KVS] Could not create template:", error);
      new Notice("Couldn't create the example (check that the vault is writable).");
    }
  }

  private async ensureFolder(path: string): Promise<void> {
    if (!this.app.vault.getAbstractFileByPath(path)) await this.app.vault.createFolder(path);
  }

  /** Create a papers note from imported references + a matching academic view. */
  private async createFromReferences(refs: readonly ImportedRef[], viewName: string): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    const { vault } = this.app;
    try {
      await this.ensureFolder("KVS Examples");
      let folder = `KVS Examples/${viewName}`;
      for (let n = 2; vault.getAbstractFileByPath(folder); n++) folder = `KVS Examples/${viewName} ${n}`;
      await vault.createFolder(folder);
      await vault.create(`${folder}/${viewName}.md`, referencesToNote(refs));

      const profile = createProfile({
        name: viewName,
        scope: { mode: "folders", folders: [folder], includeSubfolders: false },
        extractors: [TABLE_EXTRACTOR_ID],
        view: { type: "table", options: {} },
        academicKit: true,
        columns: [
          { name: "Cite key", type: "citekey" },
          { name: "Authors", type: "authors" },
          { name: "Year", type: "number" },
          { name: "Title", type: "text" },
          { name: "Venue", type: "text" },
          { name: "Tags", type: "tags" },
          { name: "Summary", type: "markdown" },
          { name: "DOI", type: "doi" },
        ],
      });
      store.addProfile(profile);
      store.setActiveProfile(profile.id);
      await this.activateDashboard();
      new Notice(`Imported ${refs.length} reference${refs.length === 1 ? "" : "s"}.`);
    } catch (error) {
      console.error("[KVS] Could not import references:", error);
      new Notice("Couldn't import references (check that the vault is writable).");
    }
  }

  /** Create an empty (discovery-mode) view over the whole vault and open it. */
  private async createBlankView(): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    const profile = createProfile({ name: "New view", extractors: [TABLE_EXTRACTOR_ID] });
    store.addProfile(profile);
    store.setActiveProfile(profile.id);
    await this.activateDashboard();
  }

  /**
   * Reconstruct rows copied from a view into a fresh view. If the clipboard carries a KVS type
   * marker (from "Copy as → KVS rows"), the columns are rebuilt with their original types; otherwise
   * types are inferred from the pasted table. Either way you get a real, typed view — not dead text.
   */
  private async pasteRowsAsView(): Promise<void> {
    const store = this.profileStore;
    if (!store) return;
    let text = "";
    try {
      text = await navigator.clipboard.readText();
    } catch {
      text = "";
    }
    if (!text.trim()) {
      new Notice("The clipboard is empty or unavailable.");
      return;
    }
    const table = parseMarkdownTables(text)[0];
    if (!table) {
      new Notice("No table found on the clipboard to paste.");
      return;
    }
    const meta = parseKvsMarker(text); // original column types, if present
    const markdown = rebuildMarkdownTable(
      table.headers,
      table.rows.map((r) => r.cells),
    );

    try {
      await this.ensureFolder("KVS Examples");
      let folder = "KVS Examples/Pasted rows";
      for (let n = 2; this.app.vault.getAbstractFileByPath(folder); n++) folder = `KVS Examples/Pasted rows ${n}`;
      await this.app.vault.createFolder(folder);
      await this.app.vault.create(`${folder}/Pasted rows.md`, `# Pasted rows\n\n${markdown}\n`);

      const typeByName = new Map((meta ?? []).map((c) => [c.name.toLowerCase(), c.type]));
      const columns = meta ? table.headers.map((h) => ({ name: h, type: typeByName.get(h.toLowerCase()) ?? "text" })) : [];
      const profile = createProfile({
        name: "Pasted rows",
        scope: { mode: "folders", folders: [folder], includeSubfolders: false },
        extractors: [TABLE_EXTRACTOR_ID],
        columns, // empty ⇒ discovery mode (types inferred)
      });
      store.addProfile(profile);
      store.setActiveProfile(profile.id);
      await this.activateDashboard();
      new Notice(meta ? "Pasted rows as a new view (types preserved)." : "Pasted rows as a new view.");
    } catch (error) {
      console.error("[KVS] Paste rows failed:", error);
      new Notice("Couldn't create the view (is the vault writable?).");
    }
  }
}
