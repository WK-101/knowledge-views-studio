import { Menu, Notice, Platform, PluginSettingTab, Setting, setIcon, type App, type Plugin } from "obsidian";
import type { ColumnTypeRegistry } from "../domain/index";
import { createZoteroFetcher } from "../workspace/zotero-transport";
import {
  createProfile,
  serializeViewDoc,
  serializeViewFile,
  DEFAULT_PROMOTED_TEMPLATE,
  PROMOTED_PLACEHOLDERS,
  DEFAULT_THEME_SPEC,
  testZoteroConnection,
  type DataService,
  type Profile,
  type ProfileStore,
  DEFAULT_RELEVANCE,
  formatBytes,
} from "../services/index";
import type { SearchIndexer } from "../workspace/search-indexer";
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
    plugin: Plugin,
    private readonly deps: SettingsDeps,
  ) {
    super(app, plugin);
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
      id: "advanced",
      label: "Advanced",
      icon: "sliders-horizontal",
      intro: "Performance and display limits. The defaults suit most vaults — change these only if you have a reason to.",
      render: (el) => this.renderAdvanced(el),
    });
    return list;
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

    if (Platform.isMobile) {
      el.createDiv({
        cls: "kvs-settings-hint",
        text:
          "On mobile, reading attachments is slower and uses battery — index them on a desktop and the result syncs with your vault, " +
          "or leave this off. PDF annotation is designed for desktop and may be limited here.",
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
        settings.semanticEngine === "neural"
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

    new Setting(el)
      .setName("Highlight colour themes")
      .setDesc(
        'Map highlight colours to research themes (used as the callout label and to group "Build highlight synthesis"). Format: "color=Theme; color=Theme". Colours: yellow, green, blue, red, purple, orange, gray.',
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
      profile.scope.mode === "vault" ? "Whole vault" : profile.scope.folders.join(", ") || "Whole vault";
    const viewLabel = this.deps.views.get(profile.view.type)?.label ?? profile.view.type;
    const bits = [scope, viewLabel];
    const conditions = profile.filter?.conditions.length ?? 0;
    if (conditions > 0) bits.push(`${conditions} condition${conditions === 1 ? "" : "s"}`);
    if (profile.advancedQuery) bits.push("expression");
    return bits.join(" · ");
  }
}
