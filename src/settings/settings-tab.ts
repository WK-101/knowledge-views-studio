import { Menu, Notice, Platform, PluginSettingTab, Setting, setIcon, type App, type Plugin } from "obsidian";
import { downloadAssets } from "../services/search/ocr/assets";
import type { ColumnTypeRegistry } from "../domain/index";
import { createZoteroFetcher } from "../workspace/zotero-transport";
import { isZotFlowAvailable } from "../services/annotations/zotflow-interop";
import { LITERATURE_PLACEHOLDERS } from "../services/notes/literature-note";
import {
  createProfile,
  serializeViewDoc,
  serializeViewFile,
  DEFAULT_PROMOTED_TEMPLATE,
  PROMOTED_PLACEHOLDERS,
  DEFAULT_THEME_SPEC,
  DEFAULT_PALETTE_OVERRIDE,
  testZoteroConnection,
  type DataService,
  type Profile,
  type ProfileStore,
  DEFAULT_RELEVANCE,
  formatBytes,
} from "../services/index";
import type { SearchIndexer } from "../workspace/search-indexer";
import type { GlobalSettings } from "../services/profile/profile";
import type { BridgeService } from "../services/bridge/bridge-service";
import { hasUsableTarget, suggestCaptureTarget } from "../services/capture/suggest-target";
import { buildConnectionLink } from "../../shared/protocol";
import { ZOTERO_PALETTE } from "../../shared/annotations";
import { STARTER_TEMPLATES, type NoteTemplate } from "../../shared/note-templates";
import { LocalIndexBackend, VaultIndexBackend } from "../workspace/index-backend";
import type { ViewRegistry } from "../views/index";
import { ProfileEditorModal } from "./profile-editor-modal";
import { ImportProfileModal } from "./import-modal";
import { KvsViewFileImportModal } from "./import-kvsview-modal";

export interface SettingsDeps {
  readonly store: ProfileStore;
  readonly views: ViewRegistry;
  readonly registry: ColumnTypeRegistry;
  readonly dataService: DataService;
  /** Open the getting-started / welcome surface (in-app guidance). */
  readonly onGettingStarted?: () => void;
  /** Search index, for the Search section's status + maintenance actions. */
  readonly searchIndexer?: SearchIndexer;
  /** The local browser bridge, for its status, pairing and activity record. */
  readonly bridge?: BridgeService;
}

interface Section {
  readonly id: string;
  readonly label: string;
  readonly icon: string;
  readonly intro: string;
  readonly render: (el: HTMLElement) => void;
}

/**
 * Settings organised as sections rather than one long scroll: the everyday essentials first, everything
 * specialised behind its own section, and each optional feature revealing its detail only once it is
 * switched on. Nothing is removed — the same settings are grouped by what a person is trying to do, and
 * a filter box finds any of them across every section.
 */
export class KnowledgeViewsSettingTab extends PluginSettingTab {
  private activeSection = "general";
  private filter = "";
  private bodyEl?: HTMLElement;

  constructor(
    app: App,
    private readonly ownPlugin: Plugin,
    private readonly deps: SettingsDeps,
  ) {
    super(app, ownPlugin);
  }

  override display(): void {
    const { containerEl } = this;
    containerEl.empty();
    containerEl.addClass("kvs-settings");

    // Filter — finds a setting by name across every section, for people who know what they want.
    const head = containerEl.createDiv({ cls: "kvs-settings-head" });
    const search = head.createDiv({ cls: "kvs-settings-search" });
    setIcon(search.createSpan({ cls: "kvs-settings-search-ic" }), "search");
    const input = search.createEl("input", { type: "text" });
    input.placeholder = "Search settings...";
    input.value = this.filter;
    input.addEventListener("input", () => {
      this.filter = input.value;
      this.renderBody();
    });

    const sections = this.sections();
    if (!sections.some((s) => s.id === this.activeSection)) this.activeSection = sections[0]!.id;

    const nav = containerEl.createDiv({ cls: "kvs-settings-nav" });
    for (const section of sections) {
      const btn = nav.createEl("button", { cls: "kvs-settings-tab" });
      setIcon(btn.createSpan({ cls: "kvs-settings-tab-ic" }), section.icon);
      btn.createSpan({ text: section.label });
      btn.toggleClass("is-on", section.id === this.activeSection);
      btn.addEventListener("click", () => {
        this.activeSection = section.id;
        this.filter = "";
        this.display();
      });
    }

    this.bodyEl = containerEl.createDiv({ cls: "kvs-settings-body" });
    this.renderBody();
  }

  /** Render just the body — so typing in the filter doesn't rebuild (and unfocus) the search box. */
  private renderBody(): void {
    const body = this.bodyEl;
    if (!body) return;
    body.empty();
    const query = this.filter.trim().toLowerCase();
    const sections = this.sections();

    if (query === "") {
      const section = sections.find((s) => s.id === this.activeSection) ?? sections[0]!;
      body.createDiv({ cls: "kvs-settings-intro", text: section.intro });
      section.render(body);
      return;
    }

    // Filtering: render every section, then hide the settings that don't match.
    for (const section of sections) {
      const wrap = body.createDiv({ cls: "kvs-settings-section" });
      new Setting(wrap).setName(section.label).setHeading();
      section.render(wrap);
    }
    if (this.applyFilter(body, query) === 0) {
      body.createDiv({ cls: "kvs-settings-intro", text: `No settings match "${this.filter}".` });
    }
  }

  /** Hide non-matching settings (and any heading left with nothing under it). Returns the match count. */
  private applyFilter(body: HTMLElement, query: string): number {
    let matched = 0;
    for (const wrap of Array.from(body.children) as HTMLElement[]) {
      const items = Array.from(wrap.querySelectorAll<HTMLElement>(".setting-item"));
      let visible = 0;
      for (const item of items) {
        if (item.hasClass("setting-item-heading")) continue;
        const hit = (item.textContent ?? "").toLowerCase().includes(query);
        item.toggle(hit);
        if (hit) visible++;
      }
      // Drop sub-headings that no longer have anything beneath them.
      for (const heading of items.filter((i) => i.hasClass("setting-item-heading"))) {
        let sib = heading.nextElementSibling as HTMLElement | null;
        let any = false;
        while (sib && !sib.hasClass("setting-item-heading")) {
          if (sib.hasClass("setting-item") && sib.isShown()) any = true;
          sib = sib.nextElementSibling as HTMLElement | null;
        }
        heading.toggle(any);
      }
      wrap.toggle(visible > 0);
      matched += visible;
    }
    return matched;
  }

  // ---------------------------------------------------------------- sections

  private sections(): Section[] {
    const list: Section[] = [
      {
        id: "general",
        label: "General",
        icon: "settings",
        intro: "The everyday essentials. Everything else lives in its own section — nothing is hidden, just tidied away until you need it.",
        render: (el) => this.renderGeneral(el),
      },
      {
        id: "views",
        label: "Views",
        icon: "layout-dashboard",
        intro: "Your saved views. Create one, import one, or edit an existing view's sources, columns, filters, and layout.",
        render: (el) => this.renderViews(el),
      },
      {
        id: "copy",
        label: "Copying",
        icon: "clipboard-copy",
        intro: "Copy selected rows out of a view and paste them into Obsidian, Word, Google Docs, or a spreadsheet.",
        render: (el) => this.renderCopy(el),
      },
      {
        id: "research",
        label: "Research",
        icon: "graduation-cap",
        intro: "The Academic Research kit: citation-aware columns, metadata lookups, Zotero annotations, and literature-review tooling. Off unless you turn it on.",
        render: (el) => this.renderResearch(el),
      },
      {
        id: "data",
        label: "Data sources",
        icon: "database",
        intro: "Where views read rows from, beyond the Markdown tables in your notes.",
        render: (el) => this.renderData(el),
      },
      {
        id: "templates",
        label: "Note templates",
        icon: "file-text",
        intro: "Reusable templates for the notes a capture creates. A view's Capture settings point at one of these by name — edit it here and every view using it follows.",
        render: (el) => this.renderNoteTemplates(el),
      },
    ];
    if (this.deps.searchIndexer) {
      list.push({
        id: "search",
        label: "Search",
        icon: "text-search",
        intro: "Full-text search across notes, rows, annotations, and attachments. The index builds in the background and keeps itself up to date.",
        render: (el) => this.renderSearch(el),
      });
    }
    list.push({
      id: "bridge",
      label: "Browser bridge",
      icon: "plug",
      intro:
        "Lets a paired browser extension read this vault's views and capture into them. It's off until you turn it on, listens only on this computer, and reading and writing are separate permissions.",
      render: (el) => this.renderBridge(el),
    });
    list.push({
      id: "advanced",
      label: "Advanced",
      icon: "sliders-horizontal",
      intro: "Performance and display limits. The defaults suit most vaults — change these only if you have a reason to.",
      render: (el) => this.renderAdvanced(el),
    });
    return list;
  }

  /** The reusable note-template library: list, edit-in-place, delete, add blank, and restore starters. */
  private renderNoteTemplates(el: HTMLElement): void {
    const { store } = this.deps;
    const templates = store.getSettings().noteTemplates;
    const save = (next: readonly NoteTemplate[]): void => store.updateSettings({ noteTemplates: next });
    const replace = (id: string, patch: Partial<NoteTemplate>): void =>
      save(templates.map((t) => (t.id === id ? { ...t, ...patch } : t)));

    const bar = new Setting(el)
      .setName("Your templates")
      .setDesc("Used by captures that save a note. Each is written in Obsidian Web Clipper syntax.");
    bar.addButton((b) =>
      b
        .setButtonText("New template")
        .setCta()
        .onClick(() => {
          const id = `tpl-${Math.random().toString(36).slice(2, 9)}`;
          save([...templates, { id, name: "Untitled template", body: "" }]);
          this.renderBody();
        }),
    );
    // Restore any starter the user has deleted (matched by id), so the gallery is never lost for good.
    const missing = STARTER_TEMPLATES.filter((s) => !templates.some((t) => t.id === s.id));
    if (missing.length > 0) {
      bar.addButton((b) =>
        b.setButtonText("Add a starter…").onClick((evt) => {
          const menu = new Menu();
          for (const s of missing) {
            menu.addItem((item) =>
              item.setTitle(s.name).onClick(() => {
                save([...templates, s]);
                this.renderBody();
              }),
            );
          }
          menu.showAtMouseEvent(evt);
        }),
      );
    }

    if (templates.length === 0) {
      el.createDiv({
        cls: "kvs-settings-intro",
        text: "No templates yet. Add a blank one, or restore a starter from the gallery.",
      });
      return;
    }

    for (const t of templates) {
      const card = el.createDiv({ cls: "kvs-template-card" });

      const nameRow = new Setting(card).setName("Name");
      const nameInput = nameRow.controlEl.createEl("input", { type: "text" });
      nameInput.value = t.name;
      nameInput.addEventListener("change", () => replace(t.id, { name: nameInput.value.trim() || "Untitled template" }));
      nameRow.addExtraButton((b) =>
        b
          .setIcon("trash-2")
          .setTooltip("Delete this template")
          .onClick(() => {
            save(templates.filter((x) => x.id !== t.id));
            this.renderBody();
          }),
      );

      const descRow = new Setting(card).setName("Description").setDesc("One line shown in the picker.");
      const descInput = descRow.controlEl.createEl("input", { type: "text" });
      descInput.value = t.description ?? "";
      descInput.addEventListener("change", () => replace(t.id, { description: descInput.value.trim() }));

      const fnRow = new Setting(card).setName("File name").setDesc("Empty = the page title.");
      const fnInput = fnRow.controlEl.createEl("input", { type: "text" });
      fnInput.placeholder = "{{title|safe_name|truncate:80}}";
      fnInput.value = t.filename ?? "";
      fnInput.addEventListener("change", () => replace(t.id, { filename: fnInput.value.trim() }));

      const bodyRow = new Setting(card).setName("Body").setClass("kvs-template-body");
      const bodyInput = bodyRow.controlEl.createEl("textarea");
      bodyInput.rows = 8;
      bodyInput.placeholder = "---\ntitle: {{title|yaml}}\nsource: {{url}}\n---\n\n{{content}}";
      bodyInput.value = t.body;
      bodyInput.addEventListener("change", () => replace(t.id, { body: bodyInput.value }));

      card.createDiv({
        cls: "kvs-setting-note",
        text: "Available: {{title}} {{url}} {{domain}} {{author}} {{published}} {{description}} {{content}} {{selection}} {{date}} {{image}} {{tags}} · filters: |upper |lower |truncate:N |date:\"YYYY-MM-DD\" |safe_name |list |tags |yaml |wikilink |blockquote |plain |slug",
      });
    }
  }

  private renderGeneral(el: HTMLElement): void {
    const { store, views } = this.deps;
    const settings = store.getSettings();

    if (this.deps.onGettingStarted) {
      new Setting(el)
        .setName("New to Knowledge Views?")
        .setDesc("A quick walkthrough with the fastest ways to create your first view — from a note, a template, or scratch.")
        .addButton((b) =>
          b
            .setButtonText("Getting started")
            .setCta()
            .onClick(() => this.deps.onGettingStarted?.()),
        );
    }

    new Setting(el)
      .setName("Default layout for new views")
      .setDesc("Which view type a newly-created view starts in.")
      .addDropdown((dropdown) => {
        for (const view of views.all()) dropdown.addOption(view.type, view.label);
        dropdown.setValue(settings.defaultView).onChange((value) => store.updateSettings({ defaultView: value }));
      });

    new Setting(el)
      .setName("Auto-refresh views")
      .setDesc("Re-render open views when their source notes change.")
      .addToggle((toggle) =>
        toggle.setValue(settings.autoRefresh).onChange((value) => store.updateSettings({ autoRefresh: value })),
      );

    new Setting(el)
      .setName("Inline editing (write-back)")
      .setDesc("Edit cells directly in views by double-clicking; changes write back to the source note or Excel workbook.")
      .addToggle((toggle) =>
        toggle.setValue(settings.inlineEditing).onChange((value) => store.updateSettings({ inlineEditing: value })),
      );

    new Setting(el)
      .setName("Shorten nested tags")
      .setDesc("Show hierarchical tags (#area/topic/detail) by their last segment only in tables. The full tag is kept for search, graph, and hover.")
      .addToggle((toggle) =>
        toggle.setValue(settings.shortenNestedTags).onChange((value) => store.updateSettings({ shortenNestedTags: value })),
      );
  }

  private renderCopy(el: HTMLElement): void {
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el)
      .setName("Enable row copying")
      .setDesc(
        "Adds a Copy action to the selection bar that copies selected rows as a table — paste it into " +
          "Obsidian (Markdown, live links kept), or into Word, Google Docs, or a spreadsheet (a formatted table). Off by default.",
      )
      .addToggle((toggle) =>
        toggle.setValue(settings.enableRowCopy).onChange((value) => {
          store.updateSettings({ enableRowCopy: value });
          this.display();
        }),
      );

    if (!settings.enableRowCopy) {
      el.createDiv({ cls: "kvs-settings-hint", text: "Turn this on to choose how links, headers, and formatting are copied." });
      return;
    }

    new Setting(el).setName("How rows are copied").setHeading();

    new Setting(el)
      .setName("Wikilinks in copied text")
      .setDesc('How [[links]] are written. "Keep" preserves live links for pasting back into Obsidian.')
      .addDropdown((dropdown) => {
        dropdown.addOption("keep", "Keep [[wikilinks]]");
        dropdown.addOption("text", "Plain text only");
        dropdown.addOption("path", "Use the note path");
        dropdown
          .setValue(settings.copyLinkHandling)
          .onChange((value) =>
            store.updateSettings({ copyLinkHandling: value === "text" || value === "path" ? value : "keep" }),
          );
      });

    new Setting(el)
      .setName("Include a header row")
      .setDesc("Put the column names in the first row of the copied table.")
      .addToggle((toggle) =>
        toggle.setValue(settings.copyIncludeHeader).onChange((value) => store.updateSettings({ copyIncludeHeader: value })),
      );

    new Setting(el)
      .setName("Include an HTML table")
      .setDesc("Also place a formatted HTML table on the clipboard, so Word, Google Docs, and Excel receive real table cells.")
      .addToggle((toggle) =>
        toggle.setValue(settings.copyIncludeHtml).onChange((value) => store.updateSettings({ copyIncludeHtml: value })),
      );

    new Setting(el)
      .setName("Copy with Cmd/Ctrl+C")
      .setDesc("When the table is focused and rows are selected, the shortcut copies them. The Copy button always works regardless.")
      .addToggle((toggle) =>
        toggle.setValue(settings.copyUseShortcut).onChange((value) => store.updateSettings({ copyUseShortcut: value })),
      );
  }

  private renderData(el: HTMLElement): void {
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el)
      .setName("Excel data sources")
      .setDesc(
        "Opt-in: let .xlsx workbooks be used as data sources. Each sheet row becomes a row, and you " +
          "can edit cells and add or delete rows — changes write back to the workbook. When off, Excel files are ignored entirely.",
      )
      .addToggle((toggle) =>
        toggle.setValue(settings.enableExcelSources).onChange((value) => {
          store.updateSettings({ enableExcelSources: value });
          this.display();
        }),
      );

    if (!settings.enableExcelSources) {
      el.createDiv({
        cls: "kvs-settings-hint",
        text: "Markdown tables in your notes are always available as sources — this only adds Excel workbooks.",
      });
      return;
    }

    new Setting(el)
      .setName("Back up Excel files before editing")
      .setDesc("Before the day's first change to a workbook, copy it verbatim into a _kvs-backups folder — a safe restore point (one per file per day).")
      .addToggle((toggle) =>
        toggle.setValue(settings.enableExcelBackup).onChange((value) => store.updateSettings({ enableExcelBackup: value })),
      );
  }

  private renderSearch(el: HTMLElement): void {
    const indexer = this.deps.searchIndexer;
    if (!indexer) return;
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el)
      .setName("Enable search")
      .setDesc("Build a search index of your vault. When off, KVS never reads your files for search and keeps no index.")
      .addToggle((t) =>
        t.setValue(settings.enableSearch).onChange((v) => {
          store.updateSettings({ enableSearch: v });
          this.display();
        }),
      );

    if (!settings.enableSearch) {
      el.createDiv({
        cls: "kvs-settings-hint",
        text: "Search is off. Turn it on to search across your notes, table rows and annotations — and, if you choose, the full text of your attachments.",
      });
      return;
    }

    new Setting(el)
      .setName("Also read attachments")
      .setDesc(
        "Index the text inside PDFs, Word, PowerPoint and EPUB files" +
          (settings.enableExcelSources
            ? " — and Excel workbooks, since Excel sources are on."
            : ". Excel is skipped while Excel data sources are off.") +
          " Off by default: reading every PDF in a large vault costs real time and battery, so it is asked for, not assumed.",
      )
      .addToggle((t) =>
        t.setValue(settings.indexAttachments).onChange((v) => {
          store.updateSettings({ indexAttachments: v });
          const notice = new Notice(v ? "KVS: indexing attachments…" : "KVS: dropping attachments from the index…", 0);
          void indexer
            .rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`))
            .then(() => {
              notice.hide();
              new Notice("KVS search index updated.", 3000);
              this.display();
            });
        }),
      );

    // This used to be a hint: it *told* you attachment indexing would be slow on a phone, and then let
    // the phone do it anyway, because settings sync and the phone inherits whatever the laptop chose.
    // Advice a program declines to act on is not a safeguard. It is now a real answer, asked separately.
    if (settings.indexAttachments) {
      new Setting(el)
        .setName("…on phones and tablets too")
        .setDesc(
          "Off by default, and asked separately on purpose: your settings sync, so switching attachment indexing on at your desk " +
            "would otherwise hand a phone a job it never agreed to — pdf.js across every book in the vault, on a battery. " +
            "Leave this off and index on a desktop instead: with the index stored in your vault, the phone gets the finished result " +
            "for free. Notes are always indexed on mobile; they cost almost nothing.",
        )
        .addToggle((t) =>
          t.setValue(settings.indexAttachmentsOnMobile).onChange((v) => {
            store.updateSettings({ indexAttachmentsOnMobile: v });
            if (Platform.isMobile) {
              const notice = new Notice(v ? "KVS: indexing attachments…" : "KVS: dropping attachments from the index…", 0);
              void indexer
                .rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`))
                .then(() => {
                  notice.hide();
                  new Notice("KVS search index updated.", 3000);
                });
            }
          }),
        );
    }

    // Offline OCR — text inside images (screenshots, photos) becomes searchable. Desktop only; the
    // recognition assets are downloaded once, on request, and everything runs locally thereafter.
    new Setting(el)
      .setName("Search text inside images (OCR)")
      .setDesc(
        Platform.isDesktop
          ? "Recognise text in your images with offline OCR, so screenshots and photos become findable. Runs in the background in idle time; results are cached and sync to your other devices. Requires a one-time asset download below."
          : "OCR runs on desktop only. Recognise images on a desktop and the results (cached in your vault) are available here for free.",
      )
      .addToggle((t) =>
        t
          .setValue(settings.ocrEnabled)
          .setDisabled(!Platform.isDesktop)
          .onChange((v) => {
            store.updateSettings({ ocrEnabled: v });
            const notice = new Notice(v ? "KVS: enabling image OCR…" : "KVS: removing images from the index…", 0);
            void indexer
              .rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`))
              .then(() => {
                notice.hide();
                new Notice("KVS search index updated.", 3000);
              });
          }),
      );

    if (settings.ocrEnabled && Platform.isDesktop) {
      new Setting(el)
        .setName("OCR languages")
        .setDesc("Tesseract language codes, space-separated (e.g. “eng deu”). Each language needs its model in the downloaded assets.")
        .addText((t) =>
          t.setValue(settings.ocrLanguages.join(" ")).onChange((v) => {
            const langs = v.split(/\s+/).map((s) => s.trim().toLowerCase()).filter((s) => s.length > 0);
            store.updateSettings({ ocrLanguages: langs.length > 0 ? langs : ["eng"] });
          }),
        );

      new Setting(el)
        .setName("OCR assets")
        .setDesc("The recognition models and engine (downloaded once, verified, then fully offline). If OCR isn't finding text, (re)download them here.")
        .addButton((b) =>
          b.setButtonText("Download OCR assets").onClick(async () => {
            b.setDisabled(true);
            const notice = new Notice("KVS: downloading OCR assets…", 0);
            try {
              const dir = this.ownPlugin.manifest.dir ?? `${this.app.vault.configDir}/plugins/${this.ownPlugin.manifest.id}`;
              await downloadAssets(this.app, dir);
              notice.hide();
              new Notice("KVS: OCR assets installed. Images will be recognised in the background.", 4000);
            } catch (err) {
              notice.hide();
              new Notice(`KVS: OCR asset download failed — ${err instanceof Error ? err.message : "unknown error"}`, 8000);
            } finally {
              b.setDisabled(false);
            }
          }),
        );
    }

    if (Platform.isMobile) {
      el.createDiv({
        cls: "kvs-settings-hint",
        text:
          "On this device: the neural engine is never used — it would mean downloading and running a sentence-transformer on a phone — " +
          "so semantic search falls back to the built-in engine, which downloads nothing. Everything else works.",
      });
    }

    new Setting(el).setName("Index").setHeading();

    new Setting(el)
      .setName("Where the index lives")
      .setDesc(
        settings.indexLocation === "vault"
          ? "In your vault — so whatever already syncs your notes syncs the index too, and search works on your phone without re-indexing there."
          : "On this device only (IndexedDB). Fast and invisible — but index on your laptop and your phone starts from nothing.",
      )
      .addDropdown((d) => {
        d.addOption("local", "This device only (default)");
        d.addOption("vault", "In my vault — syncs across devices");
        d.setValue(settings.indexLocation).onChange((v) => {
          const next = v === "vault" ? "vault" : "local";
          store.updateSettings({ indexLocation: next });
          const notice = new Notice("KVS: moving the search index…", 0);
          void indexer
            .relocate(
              next === "vault"
                ? new VaultIndexBackend(this.app, store.getSettings().indexFolder)
                : new LocalIndexBackend(`kvs-search-${this.app.vault.getName()}`),
            )
            .then(() => {
              notice.hide();
              new Notice(next === "vault" ? "Search index moved into your vault." : "Search index moved back to this device.", 4000);
              this.display();
            });
        });
      });

    if (settings.indexLocation === "vault") {
      new Setting(el)
        .setName("Index folder")
        .setDesc("Where in your vault the index file is written. It is one file — a partial sync can never leave you with half an index.")
        .addText((t) =>
          t.setValue(settings.indexFolder).onChange((v) => {
            const folder = v.trim() || "KVS Index";
            store.updateSettings({ indexFolder: folder });
          }),
        );

      const box = el.createDiv({ cls: "kvs-settings-disclosure" });
      box.createDiv({ cls: "kvs-settings-disclosure-title", text: "What this costs you" });
      const ul = box.createEl("ul");
      ul.createEl("li", { text: "The index becomes a real file in your vault, so your sync service will carry it. On a large vault with attachments indexed, that can be tens of megabytes — it is compressed, but it is not free." });
      ul.createEl("li", { text: "If you index on two devices at once, your sync service may create a conflict file. Harmless: the index self-corrects on load by re-checking every file against what it recorded. Nothing is lost, but you may see a duplicate file." });
      ul.createEl("li", { text: "A stale index is not a broken one. Whatever changed since it was written is re-indexed on load, so an index synced from another device saves most of the work even when it is out of date." });
      ul.createEl("li", { text: "If you do not sync your vault, this setting buys you nothing. Leave it on “this device only”." });

      void indexer.size().then((bytes) => {
        if (bytes === undefined) return;
        box.createDiv({
          cls: "kvs-settings-hint",
          text: `Current index file: ${formatBytes(bytes)}.`,
        });
      });
    }

    const status = indexer.status();
    new Setting(el)
      .setName("Keyword index")
      .setDesc(
        status.building
          ? `Building… ${status.docCount.toLocaleString()} items so far.`
          : `${status.docCount.toLocaleString()} items indexed across ${status.fileCount.toLocaleString()} files. Rebuild if results look stale.`,
      )
      .addButton((b) =>
        b.setButtonText("Rebuild").onClick(() => {
          const notice = new Notice("KVS: rebuilding search index…", 0);
          void indexer
            .rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`))
            .then(() => {
              notice.hide();
              new Notice("KVS search index rebuilt.", 3000);
              this.display();
            });
        }),
      );

    new Setting(el).setName("Relevance").setHeading();
    el.createDiv({
      cls: "kvs-settings-hint",
      text:
        "What counts, and how much. These were constants buried in the code until now — reasonable guesses, but guesses. " +
        "They are here so you can disagree with them. If you make a mess, Reset puts them back.",
    });

    const rel = settings.relevance;
    const pct = (v: number): string => `${Math.round(v * 100)}%`;

    new Setting(el)
      .setName("Semantic weight (Hybrid mode)")
      .setDesc(
        `${pct(rel.semanticWeight)} meaning, ${pct(1 - rel.semanticWeight)} exact words. ` +
          "Turn it down when you know roughly what you wrote; turn it up when you only remember the idea. " +
          "Only affects Hybrid — Keyword and Semantic modes are unaffected.",
      )
      .addSlider((sl) =>
        sl
          .setLimits(0, 100, 5)
          .setValue(Math.round(rel.semanticWeight * 100))
          .setDynamicTooltip()
          .onChange((v) => {
            store.updateSettings({ relevance: { ...rel, semanticWeight: v / 100 } });
            this.display();
          }),
      );

    new Setting(el)
      .setName("Recency bonus")
      .setDesc(
        rel.recencyWeight === 0
          ? "Off. Recently-edited notes get no advantage."
          : `A note edited today ranks up to ${pct(rel.recencyWeight)} higher than an identical old one. It breaks ties — it will not drag a weak match above a strong one.`,
      )
      .addSlider((sl) =>
        sl
          .setLimits(0, 50, 5)
          .setValue(Math.round(rel.recencyWeight * 100))
          .setDynamicTooltip()
          .onChange((v) => {
            store.updateSettings({ relevance: { ...rel, recencyWeight: v / 100 } });
            this.display();
          }),
      );

    if (rel.recencyWeight > 0) {
      new Setting(el)
        .setName("Recency half-life")
        .setDesc(
          `A note's freshness bonus halves every ${rel.recencyHalfLifeDays} days, then halves again — it decays, ` +
            "it does not expire. (180 days is the figure Obsidian Seek settled on after measuring relevance across a large query set; " +
            "it is a better starting point than one I would have invented.)",
        )
        .addText((t) =>
          t.setValue(String(rel.recencyHalfLifeDays)).onChange((v) => {
            const n = Number(v);
            if (Number.isFinite(n) && n >= 1) store.updateSettings({ relevance: { ...rel, recencyHalfLifeDays: Math.floor(n) } });
          }),
        );
    }

    new Setting(el)
      .setName("Title match bonus")
      .setDesc(`A match in a note's title counts ${rel.titleBoost}× a match in its body.`)
      .addSlider((sl) =>
        sl
          .setLimits(1, 10, 0.5)
          .setValue(rel.titleBoost)
          .setDynamicTooltip()
          .onChange((v) => {
            store.updateSettings({ relevance: { ...rel, titleBoost: v } });
            this.display();
          }),
      );

    new Setting(el)
      .setName("Heading match bonus")
      .setDesc(`A match in a heading counts ${rel.headingBoost}× a match in the body beneath it.`)
      .addSlider((sl) =>
        sl
          .setLimits(1, 10, 0.5)
          .setValue(rel.headingBoost)
          .setDynamicTooltip()
          .onChange((v) => {
            store.updateSettings({ relevance: { ...rel, headingBoost: v } });
            this.display();
          }),
      );

    new Setting(el)
      .setName("Reset relevance to defaults")
      .setDesc("Put every weight above back where it started.")
      .addButton((b) =>
        b.setButtonText("Reset").onClick(() => {
          store.updateSettings({ relevance: DEFAULT_RELEVANCE });
          new Notice("Relevance weights reset.", 3000);
          this.display();
        }),
      );

    new Setting(el).setName("Semantic search").setHeading();

    new Setting(el)
      .setName("Semantic engine")
      .setDesc(
        settings.semanticEngine === "neural" && Platform.isMobile
          ? "Neural model — but not on this device: running a sentence-transformer on a phone is not a reasonable thing to ask of it, " +
              "so the built-in engine is used here instead. Your desktop still uses the neural one."
          : settings.semanticEngine === "neural"
            ? "Neural model — much better at meaning. Downloads a ~25 MB model once (see below)."
            : "Built-in — learns from your own vault. Downloads nothing, ever. Weaker at words your notes have never used together (it cannot know that “car” and “automobile” mean the same thing unless you taught it).",
      )
      .addDropdown((d) => {
        d.addOption("builtin", "Built-in (no download, fully offline)");
        d.addOption("neural", "Neural model (better, downloads once)");
        d.setValue(settings.semanticEngine).onChange((v) => {
          store.updateSettings({ semanticEngine: v === "neural" ? "neural" : "builtin" });
          this.display();
        });
      });

    if (settings.semanticEngine === "neural") {
      const box = el.createDiv({ cls: "kvs-settings-disclosure" });
      box.createDiv({ cls: "kvs-settings-disclosure-title", text: "What this downloads, and what it does not send" });
      const ul = box.createEl("ul");
      ul.createEl("li", { text: "The first time you build the index, it fetches the sentence-transformer model (all-MiniLM-L6-v2, ~25 MB) from Hugging Face, and its runtime from jsDelivr. Both are cached afterwards." });
      ul.createEl("li", { text: "Nothing else is fetched, and no network is needed once it is cached." });
      ul.createEl("li", { text: "Your notes are never sent anywhere. The model runs on your machine, inside a sandbox that can do nothing but turn text into numbers." });
      ul.createEl("li", { text: "Indexing is much slower than the built-in engine — it is running a real model over every document." });
      ul.createEl("li", { text: "If you would rather download nothing at all, use the built-in engine. It is the default for exactly that reason." });
    }

    new Setting(el)
      .setName("Semantic index")
      .setDesc(
        indexer.hasSemantic
          ? "Built. Semantic and Hybrid search work, Ask has better recall, and the Related notes panel is live. Rebuild after adding a lot of new material — or after changing the engine above."
          : "Not built. Build it to enable Semantic and Hybrid search, Ask, and the Related notes panel.",
      )
      .addButton((b) =>
        b
          .setButtonText(indexer.hasSemantic ? "Rebuild" : "Build")
          .setCta()
          .setDisabled(indexer.semanticBuilding)
          .onClick(() => {
            const notice = new Notice("KVS: building semantic index…", 0);
            void indexer
              .buildSemantic((done, total) => notice.setMessage(`KVS: semantic ${done}/${total}…`))
              .then(() => {
                notice.hide();
                new Notice("KVS semantic index ready.", 4000);
                this.display();
              })
              .catch((error: unknown) => {
                notice.hide();
                new Notice(`KVS: ${error instanceof Error ? error.message : String(error)}`, 8000);
              });
          }),
      );
  }

  /**
   * The browser bridge.
   *
   * Every part of it is adjustable rather than fixed, because the right answer genuinely differs: a shared
   * machine may want reading only, a locked-down one may want a single view exposed, and a busy port may
   * need a different number. The one thing that isn't negotiable is the default — off, and staying off until
   * someone deliberately turns it on.
   */
  /**
   * What still needs doing before the companion works.
   *
   * Setup used to be a scavenger hunt: the bridge in one place, pairing in another, and — the one that
   * actually caught people — a per-view capture target with no prompt anywhere, so the extension reported
   * "no view can receive captures" and looked broken. Every prerequisite is stated here instead, with the
   * fix next to it.
   */
  private renderBridgeChecklist(el: HTMLElement, settings: GlobalSettings["bridge"]): void {
    const { store, bridge, dataService } = this.deps;
    const profiles = store.listProfiles();
    const withTarget = profiles.filter((p) => hasUsableTarget(p.captureTarget, p.newRowFile));

    const list = el.createDiv({ cls: "kvs-bridge-checklist" });
    const line = (done: boolean, text: string): HTMLElement => {
      const row = list.createDiv({ cls: `kvs-check ${done ? "is-done" : "is-todo"}` });
      row.createSpan({ cls: "kvs-check-mark", text: done ? "\u2713" : "\u2022" });
      row.createSpan({ text });
      return row;
    };

    line(bridge?.isRunning() === true, bridge?.isRunning() === true ? "The bridge is running" : "The bridge isn't running yet");
    line(settings.token !== null, settings.token !== null ? "An extension is paired" : "Nothing is paired yet");

    const targetLine = line(
      withTarget.length > 0,
      profiles.length === 0
        ? "No views yet — create one first"
        : `${String(withTarget.length)} of ${String(profiles.length)} view(s) can receive captures`,
    );

    // The fix for the case that actually strands people.
    if (withTarget.length < profiles.length && profiles.length > 0) {
      const fix = targetLine.createEl("button", { cls: "kvs-check-fix", text: "Set them up" });
      fix.addEventListener("click", () => {
        void (async () => {
          let changed = 0;
          for (const profile of profiles) {
            if (hasUsableTarget(profile.captureTarget, profile.newRowFile)) continue;
            // Follow where the view's rows already live, so one collection stays in one file.
            const result = await dataService.query({ ...profile, pageSize: null }, {});
            const target = suggestCaptureTarget(result.rows, profile.name);
            store.patchProfile(profile.id, { captureTarget: target });
            changed++;
          }
          new Notice(
            changed === 0
              ? "Every view already had somewhere to capture to."
              : `Set up capture for ${String(changed)} view(s). Check the target in each view's settings.`,
          );
          this.display();
        })();
      });
    }

    line(settings.allowSearch, settings.allowSearch ? "Searching is allowed" : "Searching is off (optional)");
  }

  private renderBridge(el: HTMLElement): void {
    const { store, bridge } = this.deps;
    const settings = store.getSettings().bridge;
    const patch = (p: Partial<typeof settings>): void => {
      store.updateSettings({ bridge: { ...store.getSettings().bridge, ...p } });
      void bridge?.sync();
    };

    new Setting(el)
      .setName("Enable the browser bridge")
      .setDesc(
        "Opens a small server on this computer only (127.0.0.1) so a paired extension can reach this vault. Desktop only. Nothing is exposed to your network.",
      )
      .addToggle((t) =>
        t.setValue(settings.enabled).onChange((value) => {
          patch({ enabled: value });
          window.setTimeout(() => this.display(), 150);
        }),
      );

    if (!settings.enabled) return;

    this.renderBridgeChecklist(el, settings);

    const status = el.createDiv({ cls: "kvs-bridge-status" });
    const error = bridge?.error() ?? null;
    if (error !== null) {
      status.createEl("strong", { text: "Not running: " });
      status.createSpan({ text: error });
    } else if (bridge?.isRunning() === true) {
      status.createSpan({ text: `Running on http://127.0.0.1:${String(settings.port)}` });
    } else {
      status.createSpan({ text: "Starting…" });
    }

    new Setting(el).setName("Pairing").setHeading();

    const paired = settings.token !== null;
    const pairing = new Setting(el)
      .setName(paired ? "Paired with an extension" : "Not paired yet")
      .setDesc(
        paired
          ? "An extension holds a token for this vault. Revoke it to require pairing again."
          : "Generate a code, then type it into the extension. The code lasts five minutes and works once.",
      );

    if (paired) {
      pairing.addButton((b) =>
        b.setButtonText("Revoke").setWarning().onClick(() => {
          void bridge?.revoke().then(() => this.display());
        }),
      );
    } else {
      pairing.addButton((b) =>
        b.setButtonText("Generate a pairing code").setCta().onClick(() => {
          const code = bridge?.startPairing();
          if (code === undefined) return;
          shownCode = code;
          codeEl.setText(code);
          codeEl.removeClass("kvs-hidden");
          copyRow.removeClass("kvs-hidden");
        }),
      );
    }
    const codeEl = el.createDiv({ cls: "kvs-bridge-code kvs-hidden" });

    // Copying beats retyping. The link carries the port as well, which is the other thing people get
    // wrong — so one paste on the far side replaces two fields filled in by hand.
    let shownCode = "";
    const copyRow = el.createDiv({ cls: "kvs-bridge-copy kvs-hidden" });
    const copyCode = copyRow.createEl("button", { text: "Copy code" });
    copyCode.addEventListener("click", () => {
      void navigator.clipboard.writeText(shownCode).then(
        () => new Notice("Pairing code copied."),
        () => new Notice("Couldn't copy — the code is shown above."),
      );
    });
    const copyLink = copyRow.createEl("button", { text: "Copy connection link" });
    copyLink.addEventListener("click", () => {
      const link = buildConnectionLink(settings.port, shownCode);
      void navigator.clipboard.writeText(link).then(
        () => new Notice("Connection link copied — paste it into the extension."),
        () => new Notice("Couldn't copy to the clipboard."),
      );
    });

    new Setting(el).setName("Permissions").setHeading();

    new Setting(el)
      .setName("Allow reading")
      .setDesc("Let a paired extension see this vault's views and check whether something is already saved.")
      .addToggle((t) => t.setValue(settings.allowRead).onChange((v) => patch({ allowRead: v })));

    new Setting(el)
      .setName("Allow writing")
      .setDesc("Let a paired extension capture into your views. Separate from reading, so you can grant one without the other.")
      .addToggle((t) => t.setValue(settings.allowWrite).onChange((v) => patch({ allowWrite: v })));

    new Setting(el)
      .setName("Allow searching")
      .setDesc(
        "Let a paired extension search this vault — notes, rows, annotations, attachments and Zotero — from the browser. This is a bigger grant than the other two: it can return the text inside your notes, not just the shape of your views.",
      )
      .addToggle((t) => t.setValue(settings.allowSearch).onChange((v) => patch({ allowSearch: v })));

    const views = store.listProfiles();
    new Setting(el)
      .setName("Views the bridge can see")
      .setDesc("Empty = every view. Otherwise list view names, one per line, to expose only those.")
      .addTextArea((t) => {
        const byId = new Map(views.map((v) => [v.id, v.name]));
        const current = settings.exposedViewIds;
        t.setValue(current === null ? "" : current.map((id) => byId.get(id) ?? id).join("\n"));
        t.setPlaceholder("All views");
        t.inputEl.rows = 3;
        t.inputEl.addEventListener("blur", () => {
          const names = t.getValue().split("\n").map((n) => n.trim()).filter((n) => n !== "");
          if (names.length === 0) {
            patch({ exposedViewIds: null });
            return;
          }
          const ids = names
            .map((n) => views.find((v) => v.name.toLowerCase() === n.toLowerCase())?.id)
            .filter((id): id is string => id !== undefined);
          patch({ exposedViewIds: ids });
        });
      });

    new Setting(el).setName("Connection").setHeading();

    new Setting(el)
      .setName("Port")
      .setDesc("Change this if something else on your computer already uses it.")
      .addText((t) =>
        t.setValue(String(settings.port)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 1024 && n <= 65535) patch({ port: Math.floor(n) });
        }),
      );

    new Setting(el)
      .setName("Allowed extension IDs")
      .setDesc("Empty = any extension that has paired. Add IDs (one per line) to restrict it further.")
      .addTextArea((t) => {
        t.setValue(settings.allowedOrigins.join("\n"));
        t.setPlaceholder("chrome-extension://…");
        t.inputEl.rows = 2;
        t.inputEl.addEventListener("blur", () => {
          const origins = t.getValue().split("\n").map((o) => o.trim()).filter((o) => o !== "");
          patch({ allowedOrigins: origins });
        });
      });

    new Setting(el)
      .setName("Largest request")
      .setDesc("Reject anything bigger, in kilobytes. Raise it if you capture very long pages.")
      .addText((t) =>
        t.setValue(String(Math.round(settings.maxBodyBytes / 1000))).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 10) patch({ maxBodyBytes: Math.floor(n) * 1000 });
        }),
      );

    new Setting(el).setName("Activity").setHeading();

    new Setting(el)
      .setName("Record requests")
      .setDesc("Keep a list of what the bridge was asked to do, so you can see it for yourself.")
      .addToggle((t) => t.setValue(settings.logRequests).onChange((v) => patch({ logRequests: v })));

    const entries = bridge?.activity() ?? [];
    if (entries.length > 0) {
      const list = el.createDiv({ cls: "kvs-bridge-log" });
      for (const entry of entries.slice(0, 15)) {
        const line = list.createDiv({ cls: "kvs-bridge-log-row" });
        line.createSpan({ text: new Date(entry.at).toLocaleTimeString() });
        line.createSpan({ text: `${entry.method} ${entry.path}` });
        line.createSpan({ text: String(entry.status) });
      }
      new Setting(el).addButton((b) =>
        b.setButtonText("Clear").onClick(() => {
          bridge?.clearActivity();
          this.display();
        }),
      );
    } else {
      el.createDiv({ cls: "kvs-bridge-log", text: "Nothing yet." });
    }

    // Where a highlight's note and tags land in the vault — notes and tags chosen separately, defaults
    // pre-selected. Top-level settings (not bridge-scoped), so the patch differs from the bridge patch above.
    new Setting(el).setName("Highlight write-back").setHeading();
    el.createDiv({
      cls: "kvs-setting-note",
      text: "When you highlight on the web, its note and tags are written into the vault. Choose where each goes — the quote itself is always written. Tags are saved as Obsidian #hashtags (or the frontmatter property), so Obsidian treats them as tags.",
    });
    const wb = (): GlobalSettings["annotationWriteback"] => store.getSettings().annotationWriteback;
    const patchWb = (p: Partial<GlobalSettings["annotationWriteback"]>): void => {
      store.updateSettings({ annotationWriteback: { ...store.getSettings().annotationWriteback, ...p } });
    };

    new Setting(el).setName("Notes").setDesc("Where the note you type on a highlight is written.").setHeading();
    new Setting(el)
      .setName("Note in the row")
      .setDesc("Append the note after the quote in the view's row cell.")
      .addToggle((t) => t.setValue(wb().noteToCell).onChange((v) => patchWb({ noteToCell: v })));
    new Setting(el)
      .setName("Note in the page's note")
      .setDesc("Write the note under the quote in the page's dedicated note, when it has one.")
      .addToggle((t) => t.setValue(wb().noteToNote).onChange((v) => patchWb({ noteToNote: v })));

    new Setting(el).setName("Tags").setDesc("Where the tags you add to a highlight are written.").setHeading();
    new Setting(el)
      .setName("Tags in the row")
      .setDesc("Append the tags as #hashtags in the view's row cell. Off by default — keeps tables lean.")
      .addToggle((t) => t.setValue(wb().tagsToCell).onChange((v) => patchWb({ tagsToCell: v })));
    new Setting(el)
      .setName("Tags in the page's note")
      .setDesc("Write the tags as an inline #hashtag line under the quote in the dedicated note.")
      .addToggle((t) => t.setValue(wb().tagsToNoteInline).onChange((v) => patchWb({ tagsToNoteInline: v })));
    new Setting(el)
      .setName("Tags as a note property")
      .setDesc(
        "Also fold the tags into the dedicated note's frontmatter tags property, tagging the whole note. Additive: removing a highlight later won't strip a tag already added here.",
      )
      .addToggle((t) => t.setValue(wb().tagsToNoteProperty).onChange((v) => patchWb({ tagsToNoteProperty: v })));

    const endpoints = bridge?.endpoints() ?? [];
    if (endpoints.length > 0) {
      const listed = endpoints.map((e) => `${e.method} ${e.path} (${e.permission})`).join(" · ");
      el.createDiv({ cls: "kvs-setting-note", text: `Endpoints: ${listed}` });
    }
  }

  private renderAdvanced(el: HTMLElement): void {
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el).setName("Performance").setHeading();

    new Setting(el)
      .setName("Refresh delay")
      .setDesc("How long to wait after edits before refreshing (milliseconds).")
      .addText((text) =>
        text.setValue(String(settings.refreshDebounceMs)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 0) store.updateSettings({ refreshDebounceMs: Math.floor(n) });
        }),
      );

    new Setting(el)
      .setName("Default page size")
      .setDesc("Rows per page in paginated table views.")
      .addText((text) =>
        text.setValue(String(settings.defaultPageSize)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n > 0) store.updateSettings({ defaultPageSize: Math.floor(n) });
        }),
      );

    new Setting(el)
      .setName("Maximum rows per view")
      .setDesc("Safety cap for aggregate views (board, calendar, summary) on large vaults. 0 means unlimited.")
      .addText((text) =>
        text.setValue(String(settings.maxRows)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 0) store.updateSettings({ maxRows: Math.floor(n) });
        }),
      );

    new Setting(el).setName("Images").setHeading();

    new Setting(el)
      .setName("Maximum image height")
      .setDesc("Cap for images shown in views and row details, in pixels. 0 means no cap.")
      .addText((text) =>
        text.setValue(String(settings.imageMaxHeight)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 0) store.updateSettings({ imageMaxHeight: Math.floor(n) });
        }),
      );

    new Setting(el)
      .setName("Maximum image width")
      .setDesc("Cap for image width, in pixels. 0 fits the image to its container.")
      .addText((text) =>
        text.setValue(String(settings.imageMaxWidth)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 0) store.updateSettings({ imageMaxWidth: Math.floor(n) });
        }),
      );
  }

  private renderResearch(el: HTMLElement): void {
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el)
      .setName("Academic Research kit")
      .setDesc(
        "Opt-in bundle for literature reviews: academic column types (citation key, authors, DOI, arXiv, PubMed) with " +
          "one-click links and citation copying, refined styling, and a Literature Review starter — applied only to views that turn it on.",
      )
      .addToggle((toggle) =>
        toggle.setValue(settings.enableAcademicKit).onChange((value) => {
          store.updateSettings({ enableAcademicKit: value });
          this.display();
        }),
      );

    if (!settings.enableAcademicKit) {
      el.createDiv({
        cls: "kvs-settings-hint",
        text: "Turn the kit on to configure metadata lookups, Zotero, note templates, and highlight themes.",
      });
      return;
    }

    new Setting(el).setName("Metadata lookups").setHeading();

    new Setting(el)
      .setName("Research lookups (DOI / OpenAlex)")
      .setDesc("Allow network requests to fill metadata from a DOI, capture papers, and find citation links. Off = no lookups.")
      .addToggle((toggle) =>
        toggle.setValue(settings.researchLookupEnabled).onChange((value) => store.updateSettings({ researchLookupEnabled: value })),
      );

    new Setting(el)
      .setName("Contact email (polite pool)")
      .setDesc("Optional. Sent to Crossref/OpenAlex to get better, more reliable rate limits. Recommended if you do bulk lookups.")
      .addText((text) =>
        text
          .setPlaceholder("you@example.com")
          .setValue(settings.researchEmail)
          .onChange((value) => store.updateSettings({ researchEmail: value.trim() })),
      );

    new Setting(el)
      .setName("Delay between lookups (ms)")
      .setDesc("Pause between requests during bulk fill / capture / citation-linking. Higher is gentler on the API.")
      .addText((text) =>
        text.setValue(String(settings.researchRequestDelayMs)).onChange((value) => {
          const n = Number(value);
          if (Number.isFinite(n) && n >= 0) store.updateSettings({ researchRequestDelayMs: Math.round(n) });
        }),
      );

    new Setting(el).setName("Zotero").setHeading();

    new Setting(el)
      .setName("Read annotations from Zotero")
      .setDesc("When syncing a paper note, also pull annotations from Zotero's local API for any zotero:// attachments. Requires Zotero 7 running with its local API enabled.")
      .addToggle((t) => t.setValue(settings.zoteroApiEnabled).onChange((v) => store.updateSettings({ zoteroApiEnabled: v })));

    new Setting(el)
      .setName("Zotero local API base URL")
      .setDesc("Change only if your Zotero API differs from the default.")
      .addText((t) =>
        t
          .setPlaceholder("http://127.0.0.1:23119/api/users/0")
          .setValue(settings.zoteroApiBase)
          .onChange((v) => store.updateSettings({ zoteroApiBase: v.trim() || "http://127.0.0.1:23119/api/users/0" })),
      )
      .addButton((b) =>
        b.setButtonText("Test").onClick(async () => {
          b.setButtonText("Testing...").setDisabled(true);
          new Notice(await testZoteroConnection(store.getSettings().zoteroApiBase, createZoteroFetcher()), 10000);
          b.setButtonText("Test").setDisabled(false);
        }),
      );

    new Setting(el)
      .setName("Live Zotero library view")
      .setDesc(
        "Browse your Zotero library live inside Obsidian (command: \"Open Zotero library\"), read straight from Zotero's local API so it's always current — no manual export. It's read-only for now, because Zotero's local API is; editing back into Zotero will light up automatically if Zotero adds local write support. Uses the same local API base URL as the annotation setting above.",
      );

    new Setting(el)
      .setName("Include Zotero in search")
      .setDesc(
        "Also index your Zotero library and its annotations, so one search finds a paper or a highlight from Zotero alongside your notes. Reads Zotero's local API on each rebuild; results open the item in Zotero. Rebuild the search index after turning this on.",
      )
      .addToggle((t) => t.setValue(settings.indexZotero).onChange((v) => store.updateSettings({ indexZotero: v })));

    new Setting(el)
      .setName("Literature notes folder")
      .setDesc(
        "Where a new literature note is created when you click a paper in the Zotero library (or use \"Create notes\"). Each note carries the paper's metadata, abstract, annotations, and a link back to Zotero, and becomes a normal Obsidian note you can link and tag. Notes are matched by their Zotero key, so you never get duplicates.",
      )
      .addText((t) => t.setPlaceholder("Literature").setValue(settings.literatureNotesFolder).onChange((v) => store.updateSettings({ literatureNotesFolder: v.trim() || "Literature" })));

    const litTmpl = new Setting(el)
      .setName("Literature note template")
      .setDesc(
        `Template for a new literature note. Placeholders: ${LITERATURE_PLACEHOLDERS.map((p) => `{{${p}}}`).join(", ")}. Leave empty for the built-in default. Keep an "## Annotations" heading — collected annotations are inserted there. The note's zotero-key frontmatter is added automatically if your template omits it, so duplicate-matching keeps working.`,
      );
    litTmpl.addTextArea((t) => {
      t.setPlaceholder("(built-in default)").setValue(settings.literatureNoteTemplate).onChange((v) => store.updateSettings({ literatureNoteTemplate: v }));
      t.inputEl.rows = 10;
      t.inputEl.addClass("kvs-lit-template");
    });

    new Setting(el)
      .setName("Work with ZotFlow, if installed")
      .setDesc(
        isZotFlowAvailable(this.app)
          ? "ZotFlow is installed. Right-click a PDF or EPUB attachment to open it in ZotFlow's reader, and this note-sync will also collect any annotations you made there (from its .zf.json sidecar). Your own KVS reader stays the default."
          : "When the ZotFlow plugin is installed, KVS can open PDFs and EPUBs in its richer reader and collect the annotations you make there. ZotFlow isn't detected right now, so this does nothing until it's installed and enabled.",
      )
      .addToggle((t) => t.setValue(settings.zotflowInteropEnabled).onChange((v) => store.updateSettings({ zotflowInteropEnabled: v })));

    new Setting(el).setName("Notes and highlights").setHeading();

    const tmplSetting = new Setting(el)
      .setName("Promoted note template")
      .setDesc(
        `Template for "Promote to dedicated note". Placeholders: ${PROMOTED_PLACEHOLDERS.map((p) => `{{${p}}}`).join(", ")}. Leave empty for the built-in default.`,
      );
    tmplSetting.addTextArea((ta) => {
      ta.setPlaceholder(DEFAULT_PROMOTED_TEMPLATE);
      ta.setValue(settings.promotedNoteTemplate);
      ta.onChange((value) => store.updateSettings({ promotedNoteTemplate: value }));
      ta.inputEl.rows = 12;
      ta.inputEl.addClass("kvs-template-textarea");
    });
    tmplSetting.addExtraButton((btn) =>
      btn
        .setIcon("rotate-ccw")
        .setTooltip("Reset to the built-in default")
        .onClick(() => {
          store.updateSettings({ promotedNoteTemplate: DEFAULT_PROMOTED_TEMPLATE });
          this.display();
        }),
    );

    const palette = settings.paletteOverride;
    new Setting(el).setName("Highlight palette").setHeading();
    new Setting(el)
      .setName("Use a custom palette")
      .setDesc(
        "Off by default, highlights use Zotero's eight colours — so a colour looks the same in Zotero, in a PDF, " +
          "and on a web page, and can carry one meaning across the vault. Turn this on to set your own eight; the " +
          "PDF swatches and the web annotator follow. (Naming of imported Zotero/PDF highlights stays on the " +
          "standard values, so imports are still recognised.)",
      )
      .addToggle((t) =>
        t.setValue(palette.enabled).onChange((v) => {
          store.updateSettings({ paletteOverride: { ...palette, enabled: v } });
          this.display();
        }),
      );
    if (palette.enabled) {
      for (const c of ZOTERO_PALETTE) {
        new Setting(el)
          .setName(c.name.charAt(0).toUpperCase() + c.name.slice(1))
          .setDesc(`Zotero default ${c.hex}`)
          .addColorPicker((picker) =>
            picker.setValue(palette.colors[c.name] ?? c.hex).onChange((hex) => {
              store.updateSettings({
                paletteOverride: { ...palette, colors: { ...palette.colors, [c.name]: hex } },
              });
            }),
          );
      }
      new Setting(el).addButton((b) =>
        b.setButtonText("Reset to Zotero's colours").onClick(() => {
          store.updateSettings({ paletteOverride: { ...palette, colors: DEFAULT_PALETTE_OVERRIDE.colors } });
          this.display();
        }),
      );
    }

    new Setting(el)
      .setName("Highlight colour themes")
      .setDesc(
        'Map highlight colours to research themes (used as the callout label and to group "Build highlight synthesis"). Format: "color=Theme; color=Theme". Colours: yellow, red, green, blue, purple, magenta, orange, gray.',
      )
      .addTextArea((ta) => {
        ta.setPlaceholder(DEFAULT_THEME_SPEC).setValue(settings.annotationThemes).onChange((v) => store.updateSettings({ annotationThemes: v }));
        ta.inputEl.rows = 3;
        ta.inputEl.addClass("kvs-template-textarea");
      });
  }

  private renderViews(el: HTMLElement): void {
    const { store } = this.deps;
    const settings = store.getSettings();

    new Setting(el)
      .setName("Saved views")
      .setHeading()
      .addButton((button) =>
        button.setButtonText("Import").onClick((event) => {
          const menu = new Menu();
          menu.addItem((item) =>
            item
              .setTitle("From JSON...")
              .setIcon("code")
              .onClick(() => new ImportProfileModal(this.app, store, () => this.display()).open()),
          );
          menu.addItem((item) =>
            item
              .setTitle("From a .kvsview file...")
              .setIcon("file-input")
              .onClick(() => new KvsViewFileImportModal(this.app, store, () => this.display()).open()),
          );
          menu.showAtMouseEvent(event);
        }),
      )
      .addButton((button) =>
        button
          .setButtonText("New view")
          .setCta()
          .onClick(() => {
            const profile = store.addProfile(
              createProfile({ name: "New view", view: { type: settings.defaultView, options: {} } }),
            );
            this.openEditor(profile);
          }),
      );

    const profiles = store.listProfiles();
    if (profiles.length === 0) {
      el.createDiv({ cls: "kvs-empty", text: "No views yet. Create one to get started." });
      return;
    }

    // Group by category so an imported multi-view file shows as one named set, not scattered rows.
    const byCategory = new Map<string, Profile[]>();
    for (const profile of profiles) {
      const key = profile.category ?? "";
      const list = byCategory.get(key);
      if (list) list.push(profile);
      else byCategory.set(key, [profile]);
    }

    for (const profile of byCategory.get("") ?? []) this.renderProfileRow(el, profile);
    for (const [category, groupProfiles] of byCategory) {
      if (category === "") continue;
      new Setting(el)
        .setName(category)
        .setDesc(`${groupProfiles.length} view${groupProfiles.length === 1 ? "" : "s"}`)
        .setHeading()
        .addExtraButton((b) =>
          b
            .setIcon("file-output")
            .setTooltip("Export this group as one .kvsview file")
            .onClick(() => void this.exportGroupToFile(category, groupProfiles)),
        );
      for (const profile of groupProfiles) this.renderProfileRow(el, profile);
    }
  }

  /** One saved-view row: Edit up front, the rest behind a menu so the row stays readable. */
  private renderProfileRow(containerEl: HTMLElement, profile: Profile): void {
    const store = this.deps.store;
    new Setting(containerEl)
      .setName(profile.name)
      .setDesc(this.summarize(profile))
      .addExtraButton((b) => b.setIcon("pencil").setTooltip("Edit").onClick(() => this.openEditor(profile)))
      .addExtraButton((b) => {
        b.setIcon("more-horizontal").setTooltip("More actions");
        b.extraSettingsEl.addEventListener("click", (event) => {
          const menu = new Menu();
          menu.addItem((item) =>
            item
              .setTitle("Duplicate")
              .setIcon("copy")
              .onClick(() => {
                store.addProfile(createProfile({ ...profile, id: undefined, name: `${profile.name} copy` }));
                this.display();
              }),
          );
          menu.addItem((item) =>
            item
              .setTitle("Copy as JSON")
              .setIcon("clipboard-copy")
              .onClick(() => void navigator.clipboard?.writeText(store.exportProfile(profile.id))),
          );
          menu.addItem((item) =>
            item
              .setTitle("Export as .kvsview file")
              .setIcon("file-output")
              .onClick(() => void this.exportProfileToFile(profile)),
          );
          menu.addSeparator();
          menu.addItem((item) =>
            item
              .setTitle("Delete")
              .setIcon("trash")
              .onClick(() => {
                store.removeProfile(profile.id);
                this.display();
              }),
          );
          menu.showAtMouseEvent(event);
        });
      });
  }

  /** Write a stored view out to a `.kvsview` file in the vault (a portable, openable copy). */
  private async exportProfileToFile(profile: Profile): Promise<void> {
    const safe = profile.name.replace(/[\\/:*?"<>|#^[\]]/g, "-").trim() || "view";
    let path = `${safe}.kvsview`;
    for (let n = 2; this.app.vault.getAbstractFileByPath(path); n++) path = `${safe} ${n}.kvsview`;
    try {
      await this.app.vault.create(path, serializeViewFile(profile));
      new Notice(`Exported to ${path}`);
    } catch (error) {
      console.error("[KVS] Export .kvsview failed:", error);
      new Notice("Couldn't write the .kvsview file.");
    }
  }

  /** Write a whole category of views out to a single multi-view `.kvsview` file — the seamless
   *  counterpart to importing a multi-view file (which groups its views under this category). */
  private async exportGroupToFile(category: string, profiles: readonly Profile[]): Promise<void> {
    if (profiles.length === 0) return;
    const safe = category.replace(/[\\/:*?"<>|#^[\]]/g, "-").trim() || "views";
    let path = `${safe}.kvsview`;
    for (let n = 2; this.app.vault.getAbstractFileByPath(path); n++) path = `${safe} ${n}.kvsview`;
    try {
      const content = serializeViewDoc({ views: [...profiles], activeView: profiles[0]!.id });
      await this.app.vault.create(path, content);
      new Notice(`Exported ${profiles.length} views to ${path}`);
    } catch (error) {
      console.error("[KVS] Export group .kvsview failed:", error);
      new Notice("Couldn't write the .kvsview file.");
    }
  }

  private openEditor(profile: Profile): void {
    new ProfileEditorModal(this.app, this.deps, profile, () => this.display()).open();
  }

  private summarize(profile: Profile): string {
    const scope =
      profile.scope.mode === "zotero"
        ? "Zotero library"
        : profile.scope.mode === "vault"
          ? "Whole vault"
          : profile.scope.folders.join(", ") || "Whole vault";
    const viewLabel = this.deps.views.get(profile.view.type)?.label ?? profile.view.type;
    const bits = [scope, viewLabel];
    const conditions = profile.filter?.conditions.length ?? 0;
    if (conditions > 0) bits.push(`${conditions} condition${conditions === 1 ? "" : "s"}`);
    if (profile.advancedQuery) bits.push("expression");
    return bits.join(" · ");
  }
}
