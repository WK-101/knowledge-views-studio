import { Modal, Notice, Setting, TFolder, setIcon, type App } from "obsidian";
import {
  FIELD_ROLES,
  inferFieldRole,
  NO_VALUE_OPERATORS,
  OPERATOR_LABELS,
  validateExpression,
  type FieldRole,
  type ColumnTypeRegistry,
  type FilterCombinator,
  type FilterCondition,
  type FilterGroup,
  tableExtractor,
  frontmatterExtractor,
  taskExtractor,
  inlineFieldExtractor,
  type FilterOperator,
  type RollupColumn,
  type RollupAggregate,
  type RollupMatch,
  canEnrich,
  type RowMerge,
  sourceLabel,
  discoverHeaderSources,
  type ComputedColumn,
  type Row,
} from "../domain/index";
import {
  XLSX_EXTRACTOR_ID,
  xlsxExtractor,
  composeLayout,
  splitViewPatch,
  normalizeLayout,
  DEFAULT_PROMOTED_TEMPLATE,
  type DataService,
  type Layout,
  type Profile,
  type ProfileStore,
} from "../services/index";
import { optBool, optNumber, optString, type ViewOptionSpec, type ViewRegistry } from "../views/index";
import {
  ACADEMIC_COLUMN_TYPES,
  resolveFieldColumn,
  MAPPABLE_FIELDS,
  ACADEMIC_FIELD_LABELS,
} from "../domain/index";
import {
  BUILT_IN_COLUMN_TYPES,
  fieldOptions,
  formatOptions,
  mergeDiscovered,
  moveItem,
  operatorsForType,
  parseOptions,
  suggestColumns,
} from "./builders";
import {
  button,
  chipField,
  emptyState,
  groupHead,
  hint,
  iconButton,
  miniField,
  optionCards,
  panelHead,
  recordCard,
  select as uiSelect,
  textInput,
  toggle as uiToggle,
} from "./editor-ui";
import { FormulaEditorModal, copyFormulaPrompt } from "./formula-editor-modal";

export interface ProfileEditorDeps {
  readonly store: ProfileStore;
  readonly views: ViewRegistry;
  readonly registry: ColumnTypeRegistry;
  readonly dataService: DataService;
}

const EMPTY_GROUP: FilterGroup = { combinator: "and", conditions: [], groups: [] };

/**
 * A full editor for one saved view: General, Columns, Filter, and Sort sections.
 * Edits patch the store live (so an open dashboard updates as you type), and
 * structural changes re-render only their own section.
 */
/** Presentation copy for the row-source cards: a short title plus what it actually does. */
const EXTRACTOR_META: Record<string, { title: string; desc: string; icon: string }> = {
  [tableExtractor.id]: {
    title: "Table rows",
    desc: "Each row of every Markdown table in the note.",
    icon: "table",
  },
  [frontmatterExtractor.id]: {
    title: "Note properties",
    desc: "The note's frontmatter becomes one row per note.",
    icon: "file-text",
  },
  [taskExtractor.id]: {
    title: "Tasks",
    desc: "Every checkbox item becomes a row.",
    icon: "check-square",
  },
  [inlineFieldExtractor.id]: {
    title: "Inline fields",
    desc: "key:: value pairs in the body, one row per note.",
    icon: "text-cursor-input",
  },
  [xlsxExtractor.id]: {
    title: "Excel rows",
    desc: "Each row of a worksheet in an .xlsx workbook.",
    icon: "sheet",
  },
};

export class ProfileEditorModal extends Modal {
  private profile: Profile;
  private generalEl!: HTMLElement;
  private sourcesEl!: HTMLElement;
  private researchEl!: HTMLElement;
  private viewOptionsEl!: HTMLElement;
  private columnsEl!: HTMLElement;
  private rollupsEl!: HTMLElement;
  private formulasEl!: HTMLElement;
  private filterEl!: HTMLElement;
  private sortEl!: HTMLElement;
  private activeIndex = 0;
  /** Which sources supplied each header, learned from the last "Discover from vault" scan. */
  private headerSources = new Map<string, string[]>();
  /** Re-applies the current filter after a panel re-renders (so filtered results stay correct). */
  private refilter: () => void = () => undefined;

  constructor(
    app: App,
    private readonly deps: ProfileEditorDeps,
    profile: Profile,
    private readonly onDone?: () => void,
    /** When set, changes are routed here instead of the store (used by file-backed views). */
    private readonly onPatch?: (patch: Partial<Profile>) => void,
    /** Which layout's presentation to edit (multi-layout views); defaults to the first. */
    initialLayoutId?: string,
  ) {
    super(app);
    this.profile = profile;
    this.editLayoutId = initialLayoutId ?? profile.layouts?.[0]?.id ?? null;
  }

  private editLayoutId: string | null;

  /** The layout currently being edited, or null for a legacy (single-layout) view. */
  private editedLayout(): Layout | null {
    const layouts = this.profile.layouts;
    if (!layouts || layouts.length === 0) return null;
    return layouts.find((l) => l.id === this.editLayoutId) ?? layouts[0]!;
  }

  /** The profile the presentation sections read from: shared data + the layout being edited. */
  private edited(): Profile {
    const layout = this.editedLayout();
    return layout ? composeLayout(this.profile, layout) : this.profile;
  }

  override onOpen(): void {
    this.titleEl.setText("");
    this.modalEl.addClass("kvs-editor-modal");
    const content = this.contentEl;
    content.empty();
    content.addClass("kvs-editor");

    // Header: title + the view being edited, plus a filter that finds any setting across all sections.
    const header = content.createDiv({ cls: "kvs-editor-header" });
    const heading = header.createDiv({ cls: "kvs-editor-heading" });
    heading.createDiv({ cls: "kvs-editor-eyebrow", text: "View settings" });
    heading.createDiv({ cls: "kvs-editor-title", text: this.profile.name || "Untitled view" });
    const search = header.createDiv({ cls: "kvs-editor-search" });
    setIcon(search.createSpan({ cls: "kvs-editor-search-ic" }), "search");
    const searchInput = search.createEl("input", { type: "text" });
    searchInput.placeholder = "Find a setting...";

    // Body: a section sidebar on the left, one panel visible at a time on the right.
    const body = content.createDiv({ cls: "kvs-editor-body" });
    const nav = body.createDiv({ cls: "kvs-editor-nav" });
    const panels = body.createDiv({ cls: "kvs-editor-panels" });

    this.generalEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.sourcesEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.viewOptionsEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.columnsEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.rollupsEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.formulasEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.filterEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.researchEl = panels.createDiv({ cls: "kvs-editor-panel" });
    this.sortEl = panels.createDiv({ cls: "kvs-editor-panel" });

    this.renderGeneral();
    this.renderSources();
    this.renderViewOptions();
    this.renderColumns();
    this.renderRollups();
    this.renderFormulas();
    this.renderFilter();
    this.renderResearch();
    this.renderSort();

    const kitOn = this.deps.store.getSettings().enableAcademicKit;
    const sections: { el: HTMLElement; label: string; icon: string; desc: string; group: string }[] = [
      { el: this.generalEl, label: "General", icon: "settings-2", desc: "Name & category", group: "view" },
      { el: this.sourcesEl, label: "Sources", icon: "folder-tree", desc: "Where rows come from", group: "data" },
      { el: this.columnsEl, label: "Columns", icon: "table-2", desc: "Fields, types & widths", group: "data" },
      { el: this.rollupsEl, label: "Rollups", icon: "sigma", desc: "Aggregate related rows", group: "data" },
      { el: this.formulasEl, label: "Formulas", icon: "function-square", desc: "Columns computed from others", group: "data" },
      { el: this.filterEl, label: "Filter", icon: "filter", desc: "Which rows appear", group: "data" },
      ...(kitOn
        ? [{ el: this.researchEl, label: "Research", icon: "graduation-cap", desc: "Academic kit for this view", group: "data" }]
        : []),
      { el: this.viewOptionsEl, label: "Type & display", icon: "layout-dashboard", desc: "View type & options", group: "layout" },
      { el: this.sortEl, label: "Sort & grouping", icon: "arrow-up-down", desc: "Order & grouping", group: "layout" },
    ];
    const GROUPS: Record<string, { label: string; hint: string }> = {
      view: { label: "View", hint: "this view" },
      data: { label: "Data", hint: "shared by all layouts" },
      layout: { label: "Layout", hint: "this layout only" },
    };

    let lastGroup = "";
    const navItems = sections.map((section) => {
      if (section.group !== lastGroup) {
        lastGroup = section.group;
        const meta = GROUPS[section.group];
        const header = nav.createDiv({ cls: "kvs-nav-group" });
        header.createSpan({ cls: "kvs-nav-group-label", text: meta?.label ?? section.group });
        header.createSpan({ cls: "kvs-nav-group-hint", text: meta?.hint ?? "" });
      }
      const item = nav.createDiv({ cls: "kvs-nav-item" });
      item.setAttribute("tabindex", "0");
      item.setAttribute("role", "tab");
      const icon = item.createSpan({ cls: "kvs-nav-icon" });
      setIcon(icon, section.icon);
      const text = item.createDiv({ cls: "kvs-nav-text" });
      text.createDiv({ cls: "kvs-nav-label", text: section.label });
      text.createDiv({ cls: "kvs-nav-desc", text: section.desc });
      return item;
    });
    const activate = (index: number): void => {
      this.activeIndex = index;
      sections.forEach((section, i) => section.el.toggleClass("is-active", i === index));
      navItems.forEach((item, i) => item.toggleClass("is-active", i === index));
    };
    navItems.forEach((item, index) => {
      item.addEventListener("click", () => {
        searchInput.value = "";
        applyFilter("");
        activate(index);
      });
      item.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          activate(index);
        } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          event.preventDefault();
          const next = (index + (event.key === "ArrowDown" ? 1 : navItems.length - 1)) % navItems.length;
          activate(next);
          navItems[next]?.focus();
        }
      });
    });

    /** Empty query → normal one-panel-at-a-time. Otherwise show every matching setting across sections. */
    const applyFilter = (raw: string): void => {
      const query = raw.trim().toLowerCase();
      panels.toggleClass("is-filtering", query !== "");
      if (query === "") {
        activate(this.activeIndex);
        for (const section of sections) {
          for (const item of Array.from(section.el.querySelectorAll<HTMLElement>(".setting-item"))) item.show();
        }
        return;
      }
      for (const section of sections) {
        const items = Array.from(section.el.querySelectorAll<HTMLElement>(".setting-item"));
        let visible = 0;
        for (const item of items) {
          if (item.hasClass("setting-item-heading")) continue;
          const hit = (item.textContent ?? "").toLowerCase().includes(query);
          item.toggle(hit);
          if (hit) visible++;
        }
        for (const headingEl of items.filter((i) => i.hasClass("setting-item-heading"))) {
          let sib = headingEl.nextElementSibling as HTMLElement | null;
          let any = false;
          while (sib && !sib.hasClass("setting-item-heading")) {
            if (sib.hasClass("setting-item") && sib.isShown()) any = true;
            sib = sib.nextElementSibling as HTMLElement | null;
          }
          headingEl.toggle(any);
        }
        section.el.toggleClass("is-match", visible > 0);
      }
    };
    searchInput.addEventListener("input", () => applyFilter(searchInput.value));
    this.refilter = () => applyFilter(searchInput.value);

    activate(0);

    // Sticky footer with the primary action.
    const footer = content.createDiv({ cls: "kvs-editor-footer" });
    new Setting(footer).addButton((button) => button.setButtonText("Done").setCta().onClick(() => this.close()));
  }

  override onClose(): void {
    this.contentEl.empty();
    this.onDone?.();
  }

  private patch(patch: Partial<Profile>): void {
    let effective: Partial<Profile> = patch;
    const layouts = this.profile.layouts;
    if (layouts && layouts.length > 0) {
      // Presentation edits land on the layout being edited; data edits on the shared view.
      const { data, layout: layoutPatch } = splitViewPatch(patch);
      if (Object.keys(layoutPatch).length > 0) {
        const nextLayouts = layouts.map((l) => (l.id === this.editedLayout()?.id ? normalizeLayout({ ...l, ...layoutPatch }) : l));
        effective = { ...data, layouts: nextLayouts };
      } else {
        effective = data;
      }
    }
    this.profile = { ...this.profile, ...effective };
    if (this.onPatch) this.onPatch(effective);
    else this.deps.store.patchProfile(this.profile.id, effective);
  }

  /** Per-source options for the Excel source. Header row is shown 1-based, stored 0-based. */
  private renderXlsxOptions(el: HTMLElement): void {
    const current = this.profile.sourceOptions?.[XLSX_EXTRACTOR_ID] ?? {};
    const patchXlsx = (key: string, value: string): void => {
      const existing = this.profile.sourceOptions ?? {};
      const forXlsx: Record<string, string> = { ...(existing[XLSX_EXTRACTOR_ID] ?? {}) };
      if (value === "") delete forXlsx[key];
      else forXlsx[key] = value;
      this.patch({ sourceOptions: { ...existing, [xlsxExtractor.id]: forXlsx } });
    };

    new Setting(el)
      .setName("Sheet")
      .setDesc("Sheet name, a number (1 = first sheet), or “all” to combine every sheet (adds a Sheet column). Blank uses the first sheet.")
      .addText((t) =>
        t
          .setPlaceholder("First sheet")
          .setValue(current.sheet ?? "")
          .onChange((v) => patchXlsx("sheet", v.trim())),
      );
    new Setting(el)
      .setName("Header row")
      .setDesc("Which row holds the column names (1 = first row).")
      .addText((t) =>
        t
          .setPlaceholder("1")
          .setValue(current.headerRow !== undefined ? String(Number(current.headerRow) + 1) : "")
          .onChange((v) => {
            const trimmed = v.trim();
            if (trimmed === "") return patchXlsx("headerRow", "");
            const n = Number(trimmed);
            if (Number.isFinite(n) && n >= 1) patchXlsx("headerRow", String(Math.floor(n) - 1));
          }),
      );
  }

  /** A collapsed-by-default disclosure so advanced settings don't overwhelm a panel. */
  private advancedGroup(parent: HTMLElement, label = "Advanced"): HTMLElement {
    const details = parent.createEl("details", { cls: "kvs-advanced-group" });
    details.createEl("summary", { cls: "kvs-advanced-summary", text: label });
    return details.createDiv({ cls: "kvs-advanced-body" });
  }

  private setFolders(folders: readonly string[]): void {
    const clean = [...new Set(folders.map((f) => f.trim()).filter((f) => f !== ""))];
    this.patch({
      scope: {
        mode: clean.length > 0 ? "folders" : "vault",
        folders: clean,
        includeSubfolders: this.profile.scope.includeSubfolders,
      },
    });
  }

  private allFolders(): string[] {
    const out: string[] = [];
    for (const file of this.app.vault.getAllLoadedFiles()) {
      if (file instanceof TFolder && file.path !== "/") out.push(file.path);
    }
    return out.sort((a, b) => a.localeCompare(b));
  }

  /** Frontmatter property names used across the vault, most common first, for the dedicated-note dropdown. */
  private frontmatterKeys(): string[] {
    const counts = new Map<string, number>();
    for (const file of this.app.vault.getMarkdownFiles()) {
      const fm = this.app.metadataCache.getFileCache(file)?.frontmatter;
      if (!fm) continue;
      for (const key of Object.keys(fm)) {
        if (key === "position") continue; // Obsidian's internal frontmatter position marker
        counts.set(key, (counts.get(key) ?? 0) + 1);
      }
    }
    return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([k]) => k);
  }

  private allMarkdownFiles(): string[] {
    return this.app.vault
      .getMarkdownFiles()
      .map((f) => f.path)
      .sort((a, b) => a.localeCompare(b));
  }

  /** Field mapping (kit): pin the generic-typed columns (Title/Year/Venue/Summary) so DOI/OpenAlex
   *  lookups keep working after a column is renamed. */
  private renderFieldMap(el: HTMLElement): void {
    const cols = this.profile.columns;
    groupHead(el, "Field mapping", "so lookups survive renames");
    hint(el, "Which columns hold each field. DOI, citation key, authors, tags and cites are detected from their type.");
    for (const field of MAPPABLE_FIELDS) {
      const auto = resolveFieldColumn(cols, field);
      new Setting(el).setName(ACADEMIC_FIELD_LABELS[field]).addDropdown((d) => {
        d.addOption("", auto ? `Auto (${auto.name})` : "Auto (none found)");
        for (const c of cols) d.addOption(c.name, c.label ?? c.name);
        d.setValue(this.profile.fieldMap?.[field] ?? "");
        d.onChange((v) => {
          const next = { ...(this.profile.fieldMap ?? {}) };
          if (v === "") delete next[field];
          else next[field] = v;
          this.patch({ fieldMap: next });
        });
      });
    }
  }

  /** Where "Promote to dedicated note" saves paper notes, and this view's optional note template (kit). */
  private renderPromotedFolder(el: HTMLElement): void {
    const setting = new Setting(el)
      .setName("Promoted notes folder")
      .setDesc("Where “Promote to dedicated note” saves paper notes. Empty = a “Papers” subfolder of this view's folder.");
    const input = setting.controlEl.createEl("input", { cls: "kvs-folder-input" });
    input.type = "text";
    input.placeholder = "e.g. Research/Papers";
    input.value = this.profile.promotedNotesFolder ?? "";
    const dl = setting.controlEl.createEl("datalist");
    dl.id = `kvs-pf-${Math.random().toString(36).slice(2)}`;
    for (const f of this.allFolders()) dl.createEl("option", { value: f });
    input.setAttr("list", dl.id);
    const commit = (): void => this.patch({ promotedNotesFolder: input.value.trim().replace(/\/+$/, "") });
    input.addEventListener("change", commit);
    input.addEventListener("blur", commit);

    // How a row is linked to its dedicated note. Matching on a frontmatter field (the DOI, for academic
    // views) finds the note wherever it lives and stops "promote" ever making a duplicate. Presented as a
    // dropdown of the frontmatter properties actually used in the vault, so any view — academic or not — can
    // pick the property that identifies its notes.
    const academic = this.profile.academicKit === true;
    const current = this.profile.dedicatedNoteKey ?? "";
    // Curated identifiers first, then whatever the vault actually uses, de-duplicated; keep the current
    // value even if no note carries it yet.
    const curated = ["doi", "isbn", "url", "zotero-key", "uid", "id"];
    const keyOptions = [...new Set([...(current ? [current] : []), ...curated, ...this.frontmatterKeys()])];
    new Setting(el)
      .setName("Match dedicated notes by")
      .setDesc("Frontmatter property that ties a row to its note, so “Promote to note” recognises an existing note anywhere in the vault (not just by folder or filename). Default matches by DOI for academic views.")
      .addDropdown((dd) => {
        dd.addOption("", academic ? "Default (DOI)" : "Default (match by note link only)");
        for (const k of keyOptions) dd.addOption(k, k);
        dd.setValue(current);
        dd.onChange((v) => this.patch({ dedicatedNoteKey: v }));
      });

    const tmpl = new Setting(el)
      .setName("Promoted note template (this view)")
      .setDesc("Overrides the global template for this view. Placeholders: {{title}}, {{authors}}, {{year}}, {{venue}}, {{doi}}, {{citekey}}, {{cite}}, {{tags}}, {{date}}. Empty = use the global template.");
    tmpl.addTextArea((ta) => {
      ta.setPlaceholder("Leave empty to use the global template…");
      ta.setValue(this.profile.promotedNoteTemplate ?? "");
      ta.onChange((value) => this.patch({ promotedNoteTemplate: value }));
      ta.inputEl.rows = 10;
      ta.inputEl.addClass("kvs-template-textarea");
    });
    tmpl.addExtraButton((btn) =>
      btn
        .setIcon("clipboard-copy")
        .setTooltip("Start from the default template")
        .onClick(() => {
          this.patch({ promotedNoteTemplate: DEFAULT_PROMOTED_TEMPLATE });
          this.renderResearch();
          this.refilter();
        }),
    );
  }

  // ---- General: what this view *is* (identity only) ----
  private renderGeneral(): void {
    const el = this.generalEl;
    el.empty();
    panelHead(el, { title: "General", desc: "What this view is called and where it appears." });

    new Setting(el)
      .setName("Name")
      .setDesc("Shown in the view switcher and on the dashboard tab.")
      .addText((text) => text.setValue(this.profile.name).onChange((v) => this.patch({ name: v })));

    new Setting(el)
      .setName("Category")
      .setDesc("Optional label to group this view with others in the switcher.")
      .addText((text) =>
        text
          .setPlaceholder("e.g. Projects")
          .setValue(this.profile.category ?? "")
          .onChange((v) => this.patch({ category: v.trim() || undefined })),
      );
  }

  // ---- Research: the Academic kit for this view (only when the kit is on globally) ----
  private renderResearch(): void {
    const el = this.researchEl;
    el.empty();
    if (!this.deps.store.getSettings().enableAcademicKit) return;

    panelHead(el, {
      title: "Research",
      desc: "The Academic kit for this view: citation-aware columns, field mapping, and promoted notes.",
    });
    new Setting(el)
      .setName("Academic Research kit")
      .setDesc("Enable academic column types (citation key, authors, DOI, arXiv, PubMed) and refined styling for this view.")
      .addToggle((toggle) =>
        toggle.setValue(this.profile.academicKit).onChange((v) => {
          this.patch({ academicKit: v });
          this.renderResearch();
          this.refilter();
        }),
      );

    if (!this.profile.academicKit) {
      hint(el, "Turn this on to map academic fields, choose where promoted notes are saved, and set this view's note template.");
      return;
    }
    this.renderFieldMap(el);
    this.renderPromotedFolder(el);
  }

  // ---- Sources: where this view's rows come from ----
  private renderSources(): void {
    const el = this.sourcesEl;
    el.empty();
    panelHead(el, {
      title: "Sources",
      desc: "Where this view's rows come from, and where new rows are written back to.",
    });

    groupHead(el, "Scope", "which notes are searched");
    chipField(el, {
      chips: this.profile.scope.folders,
      emptyLabel: "Whole vault — every folder",
      placeholder: "Add a folder to narrow the scope...",
      icon: "folder",
      suggest: (q) => {
        const chosen = this.profile.scope.folders.map((f) => f.toLowerCase());
        return this.allFolders().filter((f) => !chosen.includes(f.toLowerCase()) && f.toLowerCase().includes(q));
      },
      onAdd: (folder) => {
        this.setFolders([...this.profile.scope.folders, folder]);
        this.renderSources();
        this.refilter();
      },
      onRemove: (folder) => {
        this.setFolders(this.profile.scope.folders.filter((f) => f !== folder));
        this.renderSources();
        this.refilter();
      },
    });

    new Setting(el)
      .setName("Include subfolders")
      .setDesc("Also gather rows from folders nested inside the ones above.")
      .addToggle((t) =>
        t
          .setValue(this.profile.scope.includeSubfolders)
          .onChange((v) => this.patch({ scope: { ...this.profile.scope, includeSubfolders: v } })),
      );

    // What becomes a row — each choice gets room to say what it does.
    const excelEnabled = this.deps.store.getSettings().enableExcelSources;
    const SOURCES = [tableExtractor, frontmatterExtractor, taskExtractor, inlineFieldExtractor];
    if (excelEnabled) SOURCES.push(xlsxExtractor);
    groupHead(el, "What becomes a row", "combine these to span tables and properties in one view");
    optionCards(
      el,
      SOURCES.map((source) => {
        const meta = EXTRACTOR_META[source.id];
        return {
          id: source.id,
          title: meta?.title ?? source.label,
          desc: meta?.desc ?? "",
          icon: meta?.icon ?? "square",
          on: this.profile.extractors.includes(source.id),
        };
      }),
      (id, on) => {
        const set = new Set(this.profile.extractors);
        if (on) set.add(id);
        else set.delete(id);
        this.patch({ extractors: [...set] });
        this.renderSources(); // reveal/hide the Excel options below
        this.refilter();
      },
    );
    if (this.profile.extractors.length === 0) {
      hint(el, "Pick at least one — otherwise this view has nothing to collect.");
    }

    // How the sources combine. Only meaningful once a note-level source (properties, inline fields) is
    // mixed with an item-level one (table rows, tasks, worksheet rows) — otherwise there is nothing to
    // fold together, so the choice is hidden rather than shown as a no-op.
    if (canEnrich(this.profile.extractors)) {
      const enrich = this.profile.rowMerge === "enrich";
      groupHead(el, "How these sources combine", "note-level values vs item rows");
      optionCards(
        el,
        [
          {
            id: "separate",
            title: "Keep rows separate",
            desc: "Each source contributes its own rows. A note with a 3-row table plus properties gives 4 rows.",
            icon: "rows-3",
            on: !enrich,
          },
          {
            id: "enrich",
            title: "Add note values to each row",
            desc: "The note's properties are folded into every table row from that note, instead of adding a row of their own.",
            icon: "combine",
            on: enrich,
          },
        ],
        (id) => {
          const next: RowMerge = id === "enrich" ? "enrich" : "separate";
          if (next === this.profile.rowMerge) return; // these are exclusive, not toggles
          this.patch({ rowMerge: next });
          this.renderSources();
          this.refilter();
        },
      );
      if (enrich) {
        hint(
          el,
          "Where a field is defined in both — Author in the table and in the note's properties — the table row wins, because it is the more specific statement. A note with no table rows still appears as its own row.",
        );
      } else {
        hint(
          el,
          "Nothing is merged or overwritten: a field like Author simply appears in the same column, on rows from each source — they just sit on different rows.",
        );
        const hasSourceColumn = this.profile.columns.some((c) => c.name.trim().toLowerCase() === "source");
        if (!hasSourceColumn) {
          const bar = el.createDiv({ cls: "kvs-inline-action" });
          button(bar, "Add a “Source” column").addEventListener("click", () => {
            this.patch({ columns: [...this.profile.columns, { name: "Source", type: "text", editable: false }] });
            this.renderColumns();
            this.renderSources();
            this.refilter();
            new Notice("Added a Source column — it shows which source each row came from.");
          });
          hint(bar, "See at a glance which source each row came from. It can also be grouped, filtered, and sorted on.");
        }
      }
    }

    if (excelEnabled && this.profile.extractors.includes(XLSX_EXTRACTOR_ID)) {
      groupHead(el, "Excel options", "for the Excel worksheet source");
      this.renderXlsxOptions(el);
    }

    groupHead(el, "Writing back", "where added rows are saved");
    const nr = new Setting(el)
      .setName("New rows go to")
      .setDesc("The note whose table receives rows added via “Add row” / “Add row below”. Empty = the row's own note (or the first note in the view). The note must already have a compatible table.");
    const nrInput = nr.controlEl.createEl("input", { type: "text" });
    nrInput.placeholder = "e.g. Research/Library.md";
    nrInput.value = this.profile.newRowFile ?? "";
    const nrList = nr.controlEl.createEl("datalist");
    nrList.id = `kvs-nr-${Math.random().toString(36).slice(2)}`;
    for (const f of this.allMarkdownFiles()) nrList.createEl("option", { value: f });
    nrInput.setAttr("list", nrList.id);
    const nrCommit = (): void => this.patch({ newRowFile: nrInput.value.trim() });
    nrInput.addEventListener("change", nrCommit);
    nrInput.addEventListener("blur", nrCommit);
  }

  // ---- View options ----
  private renderViewOptions(): void {
    const el = this.viewOptionsEl;
    el.empty();
    panelHead(el, {
      title: "Type & display",
      desc: "How these rows are presented. Changing the type never changes the data, scope, or filter.",
    });

    // For a multi-layout view, choose which layout these settings edit. Data (scope, filter, columns)
    // is shared and edited in the other tabs regardless of this choice.
    const layouts = this.profile.layouts;
    if (layouts && layouts.length > 0) {
      new Setting(el)
        .setName("Editing layout")
        .setDesc("These layout settings apply to the selected layout only. Add or remove layouts from the view's tab bar.")
        .addDropdown((dropdown) => {
          for (const l of layouts) dropdown.addOption(l.id, l.name);
          dropdown.setValue(this.editedLayout()?.id ?? layouts[0]!.id).onChange((id) => {
            this.editLayoutId = id;
            this.renderViewOptions();
            this.renderColumns();
            this.renderSort();
          });
        });
    }

    // The view type lives here (not buried in General) so "Layout" always has content and actually
    // controls the layout. The same rows can be shown as a table, board, calendar, cards, or summary.
    new Setting(el)
      .setName("View type")
      .setDesc("How these rows are displayed. Switching this changes only the layout — the data, scope, and filter stay the same.")
      .addDropdown((dropdown) => {
        for (const view of this.deps.views.all()) dropdown.addOption(view.type, view.label);
        dropdown.setValue(this.edited().view.type).onChange((v) => {
          this.patch({ view: { type: v, options: this.edited().view.options } });
          this.renderViewOptions();
        });
      });

    new Setting(el)
      .setName("Page size")
      .setDesc("Rows per page in table views. Blank shows all rows.")
      .addText((text) =>
        text
          .setPlaceholder("All")
          .setValue(this.edited().pageSize ? String(this.edited().pageSize) : "")
          .onChange((v) => {
            const n = Number(v);
            this.patch({ pageSize: Number.isFinite(n) && n > 0 ? Math.floor(n) : null });
          }),
      );

    new Setting(el)
      .setName("Hide empty columns")
      .setDesc("Hide columns that are blank for every matching row.")
      .addToggle((toggle) => toggle.setValue(this.edited().hideEmptyColumns).onChange((v) => this.patch({ hideEmptyColumns: v })));

    // View-specific options (board's group field, calendar's date field, pivot's rows/columns, …).
    const view = this.deps.views.get(this.edited().view.type);
    const specs = view?.optionSpecs ?? [];
    if (specs.length > 0) {
      groupHead(el, `${view?.label ?? "View"} options`, "specific to this layout");
      for (const spec of specs) this.renderOptionSpec(el, spec);
    }
  }

  private renderOptionSpec(parent: HTMLElement, spec: ViewOptionSpec): void {
    const setting = new Setting(parent).setName(spec.label);
    if (spec.description) setting.setDesc(spec.description);
    const options = this.edited().view.options;
    const setOption = (value: unknown): void => {
      this.patch({ view: { type: this.edited().view.type, options: { ...this.edited().view.options, [spec.key]: value } } });
    };

    if (spec.kind === "field") {
      const fields = fieldOptions(this.profile.columns).filter((f) => (spec.fieldFilter === "date" ? f.typeId === "date" : true));
      setting.addDropdown((d) => {
        d.addOption("", "—");
        for (const field of fields) d.addOption(field.name, field.name);
        const current = optString(options, spec.key);
        if (current && !fields.some((f) => f.name === current)) d.addOption(current, current);
        d.setValue(current).onChange((v) => setOption(v === "" ? undefined : v));
      });
    } else if (spec.kind === "select") {
      setting.addDropdown((d) => {
        for (const choice of spec.choices ?? []) d.addOption(choice.value, choice.label);
        d.setValue(optString(options, spec.key, spec.choices?.[0]?.value ?? "")).onChange((v) => setOption(v));
      });
    } else if (spec.kind === "toggle") {
      setting.addToggle((t) => t.setValue(optBool(options, spec.key)).onChange((v) => setOption(v)));
    } else if (spec.kind === "number") {
      setting.addText((t) =>
        t.setValue(String(optNumber(options, spec.key, 0))).onChange((v) => {
          const n = Number(v);
          setOption(Number.isFinite(n) ? n : undefined);
        }),
      );
    } else {
      setting.addText((t) => {
        if (spec.placeholder) t.setPlaceholder(spec.placeholder);
        t.setValue(optString(options, spec.key)).onChange((v) => setOption(v === "" ? undefined : v));
      });
    }
  }

  // ---- Columns ----
  // ---- Columns ----
  private renderColumns(): void {
    const el = this.columnsEl;
    el.empty();
    panelHead(el, {
      title: "Columns",
      desc: "The fields this view shows, and how each one behaves.",
      actions: (bar) => {
        button(bar, "Discover from vault").addEventListener("click", () => void this.discoverColumns());
        button(bar, "Add column", true).addEventListener("click", () => {
          this.patch({ columns: [...this.profile.columns, { name: "New column", type: "text" }] });
          this.renderColumns();
          this.refilter();
        });
      },
    });

    groupHead(el, "Table display", "how the table itself is drawn");

    new Setting(el)
      .setName("Row height")
      .setDesc("Density of table rows.")
      .addDropdown((d) => {
        d.addOption("compact", "Compact");
        d.addOption("normal", "Normal");
        d.addOption("comfortable", "Comfortable");
        d.setValue(this.edited().rowHeight).onChange((v) =>
          this.patch({ rowHeight: v === "compact" || v === "comfortable" ? v : "normal" }),
        );
      });

    new Setting(el)
      .setName("Table width")
      .setDesc("Fit columns to the pane, or give them room and scroll horizontally.")
      .addDropdown((d) => {
        d.addOption("fit", "Fit to pane");
        d.addOption("wide", "Wide — scroll horizontally");
        d.setValue(this.edited().tableWidth).onChange((v) => this.patch({ tableWidth: v === "wide" ? "wide" : "fit" }));
      });

    new Setting(el)
      .setName("Freeze first column")
      .setDesc("Keep the first column (and source link) visible when scrolling horizontally.")
      .addToggle((t) => t.setValue(this.edited().frozenFirstColumn).onChange((v) => this.patch({ frozenFirstColumn: v })));

    new Setting(el)
      .setName("Freeze header row")
      .setDesc("Keep the column headers visible while scrolling rows.")
      .addToggle((t) => t.setValue(this.edited().frozenHeader).onChange((v) => this.patch({ frozenHeader: v })));

    new Setting(el)
      .setName("Summary row")
      .setDesc("Show the aggregation footer (Sum, Count, Average…) beneath the table. Turn off to reclaim the space when you don't need it.")
      .addToggle((t) => t.setValue(this.edited().showSummaryRow !== false).onChange((v) => this.patch({ showSummaryRow: v })));

    new Setting(el)
      .setName("Rows per group")
      .setDesc("When grouped, draw at most this many rows per group, with a “Show N more” control. 0 = draw them all. The group's count always reports the true total.")
      .addText((t) =>
        t.setValue(String(this.edited().groupLimit ?? 0)).onChange((v) => {
          const n = Number(v);
          if (Number.isFinite(n) && n >= 0) this.patch({ groupLimit: Math.floor(n) });
        }),
      );

    new Setting(el)
      .setName("Header matching")
      .setDesc("How strictly a table's headers must match these columns before its rows are aggregated.")
      .addDropdown((d) => {
        d.addOption("loose", "Loose — include every table in scope");
        d.addOption("contains", "Contains — table must have all these columns");
        d.addOption("exact", "Exact — table headers must match exactly");
        d.setValue(this.profile.columnMatch).onChange((v) =>
          this.patch({ columnMatch: v === "contains" || v === "exact" ? v : "loose" }),
        );
      });

    groupHead(el, "Fields", `${this.profile.columns.length} column${this.profile.columns.length === 1 ? "" : "s"}`);
    if (this.profile.extractors.length > 1) {
      hint(
        el,
        "With more than one source selected, each column can be bound to a specific source — its header is then matched only within that source. Leave it on “Any source” unless the same header exists in several of them.",
      );
    }

    if (this.profile.columns.length === 0) {
      emptyState(
        el,
        "table-2",
        "No columns defined",
        "Leave it this way to auto-detect columns from your tables, or discover them from the vault to edit each one.",
      );
      return;
    }

    const typeChoices = [
      ...BUILT_IN_COLUMN_TYPES.map((t) => ({ value: t.id, label: t.label })),
      ...(this.profile.academicKit ? ACADEMIC_COLUMN_TYPES.map((t) => ({ value: t.id, label: t.label })) : []),
    ];
    const roleChoices = FIELD_ROLES.map((role) => ({ value: role, label: role === "none" ? "Auto" : role }));
    // Only the sources this view actually collects from — Excel appears here exactly when it is enabled
    // in settings and selected as a source. With one source there is nothing to disambiguate, so the
    // control is omitted entirely (a single-entry dropdown would be noise).
    const sourceChoices = [
      { value: "", label: "Any source" },
      ...this.profile.extractors.map((id) => ({ value: id, label: sourceLabel(id) })),
    ];

    this.profile.columns.forEach((column, index) => {
      const key = column.name.toLowerCase();
      const shown = !this.edited().hiddenColumns.some((x) => x.toLowerCase() === key);

      const card = recordCard(el, {
        badge: String(index + 1),
        actions: (bar) => {
          iconButton(bar, "chevron-up", "Move up", () => this.moveColumn(index, index - 1));
          iconButton(bar, "chevron-down", "Move down", () => this.moveColumn(index, index + 1));
          iconButton(bar, "trash-2", "Remove column", () => {
            this.patch({ columns: this.profile.columns.filter((_, i) => i !== index) });
            this.renderColumns();
            this.refilter();
          });
        },
      });
      card.el.toggleClass("is-hidden-col", !shown);

      // Identity: the field name is the record's title.
      const nameInput = textInput(card.title, column.name, "Field name", (v) => this.patchColumn(index, { name: v }));
      nameInput.addClass("kvs-rec-name");

      const typeCtl = miniField(card.grid, "Type");
      uiSelect(typeCtl, typeChoices, column.type, (v) => {
        this.patchColumn(index, { type: v });
        this.renderColumns();
        this.refilter();
      });

      // With several sources selected, a header can exist in more than one of them. Binding the column
      // to one source says which it means; "Any source" keeps the default behaviour.
      if (sourceChoices.length > 2) {
        const found = this.headerSources.get(column.name.trim().toLowerCase()) ?? [];
        const clash = found.length > 1 && column.source === undefined;
        const srcCtl = miniField(card.grid, clash ? "From source — appears in several" : "From source", {
          hint: clash
            ? `This header was found in: ${found.map(sourceLabel).join(", ")}. Pick one, or leave it on "Any source" to take whichever the row itself carries.`
            : "Match this column's header only within one source. Any source = wherever the row came from.",
        });
        if (clash) srcCtl.addClass("is-warn");
        uiSelect(srcCtl, sourceChoices, column.source ?? "", (v) => {
          this.patchColumn(index, { source: v === "" ? undefined : v });
          this.renderColumns();
          this.refilter();
        });
      }

      const roleCtl = miniField(card.grid, "Role", { hint: "Drives smart defaults (board group-by, calendar date, card title)." });
      const roleSel = uiSelect(roleCtl, roleChoices, column.role ?? "none", (v) =>
        this.patchColumn(index, { role: v === "none" ? undefined : (v as FieldRole) }),
      );
      const inferred = inferFieldRole(column.type, column.name);
      if (inferred !== "none" && (column.role ?? "none") === "none") {
        roleSel.setAttribute("title", `Auto-detected: ${inferred}`);
      }

      const labelCtl = miniField(card.grid, "Header label", { hint: "Shown instead of the field name." });
      textInput(labelCtl, column.label ?? "", column.name, (v) =>
        this.patchColumn(index, { label: v.trim() === "" ? undefined : v }),
      );

      if (column.type === "number") {
        const dispCtl = miniField(card.grid, "Show as", { hint: "Draw the number as a bar or ring instead of plain text." });
        uiSelect(
          dispCtl,
          [
            { value: "plain", label: "Plain number" },
            { value: "bar", label: "Progress bar" },
            { value: "ring", label: "Ring" },
          ],
          column.display ?? "plain",
          (v) => {
            this.patchColumn(index, { display: v === "plain" ? undefined : v });
            this.renderColumns();
            this.refilter();
          },
        );
        if ((column.display ?? "plain") !== "plain") {
          const maxCtl = miniField(card.grid, "Full at", { hint: "The value that counts as 100%." });
          textInput(maxCtl, column.displayMax ? String(column.displayMax) : "", "100", (v) => {
            const n = Number(v);
            this.patchColumn(index, { displayMax: Number.isFinite(n) && n > 0 ? n : undefined });
          });
        }
      }

      if (column.type === "doi" || column.type === "arxiv" || column.type === "pmid") {
        const dispCtl = miniField(card.grid, "Show as", {
          hint: "A DOI is a link, not reading material. Compact shows a small “open” chip; Full shows the whole identifier.",
        });
        uiSelect(
          dispCtl,
          [
            { value: "compact", label: "Compact chip" },
            { value: "full", label: "Full identifier" },
            ...(column.type === "doi" ? [{ value: "publisher", label: "Publisher" }] : []),
          ],
          column.display ?? "compact",
          (v) => {
            this.patchColumn(index, { display: v === "compact" ? undefined : v });
            this.renderColumns();
          },
        );
      }

      const widthCtl = miniField(card.grid, "Width");
      textInput(widthCtl, column.width ? String(column.width) : "", "auto", (v) => {
        const n = Number(v);
        this.patchColumn(index, { width: Number.isFinite(n) && n > 0 ? Math.floor(n) : undefined });
      });

      const defCtl = miniField(card.grid, "Default for new rows", {
        wide: true,
        hint: "Pre-fill this column when a row is added. Use {{today}}, {{now}} or {{time}}.",
      });
      textInput(defCtl, column.defaultValue ?? "", "—", (v) =>
        this.patchColumn(index, { defaultValue: v.trim() === "" ? undefined : v }),
      );

      if (column.type === "select") {
        const optCtl = miniField(card.grid, "Allowed values", { wide: true, hint: "Comma-separated." });
        textInput(optCtl, formatOptions(column.options), "Draft, In review, Done", (v) =>
          this.patchColumn(index, { options: parseOptions(v) }),
        );
      }

      const showCtl = miniField(card.grid, "Show in views", {
        hint: "Hide it in views without removing it from the table.",
      });
      uiToggle(showCtl, shown, (v) => {
        const others = this.edited().hiddenColumns.filter((x) => x.toLowerCase() !== key);
        // Visibility lives only in hiddenColumns; the column definition is never altered here.
        this.patch({ hiddenColumns: v ? others : [...others, column.name] });
        card.el.toggleClass("is-hidden-col", !v);
      });
    });
  }

  private patchColumn(index: number, patch: Partial<Profile["columns"][number]>): void {
    this.patch({ columns: this.profile.columns.map((c, i) => (i === index ? { ...c, ...patch } : c)) });
  }

  private moveColumn(from: number, to: number): void {
    this.patch({ columns: moveItem(this.profile.columns, from, to) });
    this.renderColumns();
  }

  private async discoverColumns(): Promise<void> {
    try {
      const result = await this.deps.dataService.query(this.profile);
      const discovered = suggestColumns(result.rows);
      if (discovered.length === 0) {
        new Notice("No table columns found in this scope.");
        return;
      }

      // Record which source supplied each header, so columns can be bound and clashes reported.
      this.headerSources = discoverHeaderSources(result.rows);
      const multiSource = this.profile.extractors.length > 1;
      const ambiguous: string[] = [];

      const withSources = discovered.map((column) => {
        if (!multiSource) return column;
        const found = this.headerSources.get(column.name.trim().toLowerCase()) ?? [];
        if (found.length === 1) return { ...column, source: found[0]! };
        // Found in several sources: binding to one would silently drop the others' values, and there is
        // no way to know which was meant — leave it on "Any source" and say so.
        if (found.length > 1) ambiguous.push(column.name);
        return column;
      });

      this.patch({ columns: mergeDiscovered(this.profile.columns, withSources) });
      this.renderColumns();
      this.refilter();

      const added = withSources.length;
      if (ambiguous.length > 0) {
        new Notice(
          `Discovered ${added} column${added === 1 ? "" : "s"}. ${ambiguous.length} appear${ambiguous.length === 1 ? "s" : ""} in more than one source (${ambiguous.slice(0, 3).join(", ")}${ambiguous.length > 3 ? "…" : ""}) — set "From source" on those to choose which one you mean.`,
          9000,
        );
      } else if (multiSource) {
        new Notice(`Discovered ${added} column${added === 1 ? "" : "s"}, each bound to the source it came from.`, 5000);
      }
    } catch (error) {
      new Notice(`Could not scan vault: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  // ---- Filter ----

  private renderFormulas(): void {
    const el = this.formulasEl;
    el.empty();
    panelHead(el, {
      title: "Formulas",
      desc: "Columns computed from the others — a duration, a status, a flag. They are derived on the fly, never stored, unless you ask for them to be written back.",
      actions: (bar) => {
        button(bar, "Add formula", true).addEventListener("click", () => {
          const computed: ComputedColumn = { name: "New formula", expression: "" };
          this.patch({ computed: [...this.profile.computed, computed] });
          this.renderFormulas();
          this.refilter();
        });
      },
    });

    if (this.profile.computed.length === 0) {
      emptyState(
        el,
        "function-square",
        "No formulas yet",
        'Add one to derive a column from the others — for example days([Start], [Due]) for a duration, or if([Hours] > 8, "Long", "Short") for a flag.',
      );
      return;
    }

    const typeChoices = [
      { value: "", label: "Auto" },
      ...BUILT_IN_COLUMN_TYPES.map((t) => ({ value: t.id, label: t.label })),
    ];

    this.profile.computed.forEach((computed, index) => {
      const card = recordCard(el, {
        badge: String(index + 1),
        actions: (bar) => {
          iconButton(bar, "sparkles", "Copy context for an assistant", () =>
            copyFormulaPrompt(computed, this.fieldNames()),
          );
          iconButton(bar, "trash-2", "Remove formula", () => {
            this.patch({ computed: this.profile.computed.filter((_, i) => i !== index) });
            this.renderFormulas();
            this.refilter();
          });
        },
      });

      const nameInput = textInput(card.title, computed.name, "Formula name", (v) =>
        this.patchComputed(index, { name: v }),
      );
      nameInput.addClass("kvs-rec-name");

      // The expression is not a text field -- it opens the editor, which can actually explain itself.
      const exprCtl = miniField(card.grid, "Expression", { wide: true });
      const open = exprCtl.createEl("button", { cls: "kvs-fx-open" });
      open.createSpan({ cls: "kvs-fx-open-code", text: computed.expression || "Click to write a formula…" });
      if (!computed.expression) open.addClass("is-empty");
      open.addEventListener("click", () => void this.openFormulaEditor(index, computed));

      const typeCtl = miniField(card.grid, "Result type", { hint: "How the computed value is displayed and sorted." });
      uiSelect(typeCtl, typeChoices, computed.type ?? "", (v) => this.patchComputed(index, { type: v === "" ? undefined : v }));

      const matCtl = miniField(card.grid, "Write to source column", {
        hint: 'An existing source column to materialize this value into, via "Write rollups to source".',
      });
      textInput(matCtl, computed.materializeTo ?? "", "(off)", (v) =>
        this.patchComputed(index, { materializeTo: v.trim() === "" ? undefined : v.trim() }),
      );
    });
  }

  private patchComputed(index: number, patch: Partial<ComputedColumn>): void {
    this.patch({
      computed: this.profile.computed.map((c, i) => (i === index ? { ...c, ...patch } : c)),
    });
  }

  private fieldNames(): string[] {
    return fieldOptions(this.profile.columns).map((f) => f.name);
  }

  /** Open the formula editor against real rows from this view, so the preview means something. */
  private async openFormulaEditor(index: number, computed: ComputedColumn): Promise<void> {
    let sample: Row[] = [];
    try {
      const result = await this.deps.dataService.query(this.profile);
      sample = result.rows.slice(0, 25);
    } catch {
      sample = [];
    }
    new FormulaEditorModal(this.app, computed, this.fieldNames(), sample, (expression) => {
      this.patchComputed(index, { expression });
      this.renderFormulas();
      this.refilter();
    }).open();
  }

  private patchRollup(index: number, patch: Partial<RollupColumn>): void {
    const rollups = this.profile.rollups.map((r, i) => (i === index ? { ...r, ...patch } : r));
    this.patch({ rollups });
  }

  private renderRollups(): void {
    const el = this.rollupsEl;
    el.empty();
    panelHead(el, {
      title: "Relations & rollups",
      desc: "Aggregate a field across the rows of the notes a relation links to.",
      actions: (bar) => {
        button(bar, "Add rollup", true).addEventListener("click", () => {
          const rollup: RollupColumn = { name: "Rollup", relationField: "", targetField: "", aggregate: "count" };
          this.patch({ rollups: [...this.profile.rollups, rollup] });
          this.renderRollups();
    this.renderFormulas();
          this.refilter();
        });
      },
    });

    const AGGS: ReadonlyArray<[RollupAggregate, string]> = [
      ["count", "Count — number of related rows"],
      ["count-unique", "Count unique — distinct target values"],
      ["sum", "Sum"],
      ["avg", "Average"],
      ["min", "Minimum"],
      ["max", "Maximum"],
      ["list", "List — all target values"],
      ["unique", "Unique — distinct target values"],
    ];

    if (this.profile.rollups.length === 0) {
      emptyState(
        el,
        "sigma",
        "No rollups yet",
        'Give a column the "Relation (note links)" type to hold the [[links]], then add a rollup to aggregate over it.',
      );
      return;
    }

    const typeChoices = [
      { value: "", label: "Auto" },
      ...BUILT_IN_COLUMN_TYPES.map((t) => ({ value: t.id, label: t.label })),
      ...(this.profile.academicKit ? ACADEMIC_COLUMN_TYPES.map((t) => ({ value: t.id, label: t.label })) : []),
    ];

    this.profile.rollups.forEach((rollup, index) => {
      const card = recordCard(el, {
        badge: String(index + 1),
        actions: (bar) => {
          iconButton(bar, "columns-3", "Add as a column", () => {
            const type =
              rollup.type ??
              (rollup.aggregate === "sum" || rollup.aggregate === "avg" || rollup.aggregate === "count" || rollup.aggregate === "count-unique"
                ? "number"
                : "text");
            this.patch({ columns: [...this.profile.columns, { name: rollup.name, type, editable: false }] });
            this.renderColumns();
            new Notice(`Added "${rollup.name}" as a column.`);
          });
          iconButton(bar, "trash-2", "Remove rollup", () => {
            this.patch({ rollups: this.profile.rollups.filter((_, i) => i !== index) });
            this.renderRollups();
    this.renderFormulas();
            this.refilter();
          });
        },
      });

      const nameInput = textInput(card.title, rollup.name, "Rollup name", (v) => this.patchRollup(index, { name: v }));
      nameInput.addClass("kvs-rec-name");

      const relCtl = miniField(card.grid, "Relation column", { hint: "The column holding the [[note]] links to follow." });
      textInput(relCtl, rollup.relationField, "e.g. Tasks", (v) => this.patchRollup(index, { relationField: v }));

      const aggCtl = miniField(card.grid, "Aggregate");
      uiSelect(
        aggCtl,
        AGGS.map(([value, label]) => ({ value, label })),
        rollup.aggregate,
        (v) => {
          this.patchRollup(index, { aggregate: v as RollupAggregate });
          this.renderRollups();
    this.renderFormulas();
          this.refilter();
        },
      );

      if (rollup.aggregate !== "count") {
        const tgtCtl = miniField(card.grid, "Target field", { hint: "Field on the related rows to aggregate." });
        textInput(tgtCtl, rollup.targetField, "e.g. Hours", (v) => this.patchRollup(index, { targetField: v }));
      }

      const typeCtl = miniField(card.grid, "Result type", { hint: "How the rolled-up value is displayed and sorted." });
      uiSelect(typeCtl, typeChoices, rollup.type ?? "", (v) => this.patchRollup(index, { type: v === "" ? undefined : v }));

      const matchCtl = miniField(card.grid, "Match links by", { hint: "How [[links]] resolve to source notes." });
      uiSelect(
        matchCtl,
        [
          { value: "either", label: "Note name or path" },
          { value: "name", label: "Note name only" },
          { value: "path", label: "Full path only" },
        ],
        rollup.matchBy ?? "either",
        (v) => this.patchRollup(index, { matchBy: v as RollupMatch }),
      );

      const matCtl = miniField(card.grid, "Write to source column", {
        wide: true,
        hint: 'An existing source column to materialize this value into, via "Write rollups to source".',
      });
      textInput(matCtl, rollup.materializeTo ?? "", "(off)", (v) =>
        this.patchRollup(index, { materializeTo: v.trim() === "" ? undefined : v.trim() }),
      );
    });
  }

  private renderFilter(): void {
    const el = this.filterEl;
    el.empty();
    const group = this.profile.filter ?? EMPTY_GROUP;
    panelHead(el, {
      title: "Filter",
      desc: "Which rows appear in this view. With no conditions, every row matches.",
      actions: (bar) => {
        button(bar, "Add condition", true).addEventListener("click", () => {
          const field = fieldOptions(this.profile.columns)[0]?.name ?? "note";
          this.setFilter({ ...group, conditions: [...group.conditions, { field, operator: "contains", value: "" }] });
          this.renderFilter();
          this.refilter();
        });
      },
    });

    new Setting(el)
      .setName("Match")
      .setDesc("How the conditions below combine.")
      .addDropdown((d) => {
        d.addOption("and", "All conditions");
        d.addOption("or", "Any condition");
        d.addOption("none", "No conditions true");
        d.setValue(group.combinator).onChange((v) =>
          this.setFilter({ ...group, combinator: v as FilterCombinator }),
        );
      });

    if (group.conditions.length === 0) {
      emptyState(el, "filter", "No conditions", "Every row in scope appears in this view. Add a condition to narrow it.");
    } else {
      groupHead(el, "Conditions", `${group.conditions.length} condition${group.conditions.length === 1 ? "" : "s"}`);
      group.conditions.forEach((condition, index) => this.renderCondition(el, group, condition, index));
    }

    if (group.groups.length > 0) {
      el.createDiv({
        cls: "setting-item-description",
        text: `Plus ${group.groups.length} nested group(s), edited from the dashboard Filter menu.`,
      });
    }

    // Advanced expression — ANDed with the conditions above. Collapsed so it doesn't crowd the basics.
    const advBase = 'Optional. ANDed with the conditions above. e.g. Year >= 2020 and Status == "open".';
    const advEl = this.advancedGroup(el, "Advanced filter expression");
    const querySetting = new Setting(advEl).setName("Expression").setDesc(advBase);
    querySetting.addTextArea((text) =>
      text
        .setPlaceholder('Priority == "High" or contains(Tags, "urgent")')
        .setValue(this.profile.advancedQuery ?? "")
        .onChange((v) => {
          const trimmed = v.trim();
          const result = trimmed === "" ? { ok: true as const } : validateExpression(trimmed);
          querySetting.controlEl.toggleClass("kvs-invalid", !result.ok);
          querySetting.setDesc(result.ok ? advBase : `Invalid: ${result.error}`);
          if (result.ok) this.patch({ advancedQuery: trimmed === "" ? null : trimmed });
        }),
    );
  }

  private renderCondition(
    parent: HTMLElement,
    group: FilterGroup,
    condition: FilterCondition,
    index: number,
  ): void {
    const fields = fieldOptions(this.profile.columns);
    const fieldType = fields.find((f) => f.name.toLowerCase() === condition.field.toLowerCase())?.typeId ?? "text";
    const operators = operatorsForType(fieldType, this.deps.registry);
    const setting = new Setting(parent).setClass("kvs-settings-condition");

    setting.addDropdown((d) => {
      for (const field of fields) d.addOption(field.name, field.name);
      if (condition.field && !fields.some((f) => f.name === condition.field)) {
        d.addOption(condition.field, condition.field);
      }
      d.setValue(condition.field || fields[0]?.name || "note").onChange((v) => {
        this.patchCondition(group, index, { field: v });
        this.renderFilter();
      });
    });

    setting.addDropdown((d) => {
      for (const op of operators) d.addOption(op, OPERATOR_LABELS[op]);
      const current = operators.includes(condition.operator) ? condition.operator : operators[0] ?? "contains";
      d.setValue(current).onChange((v) => {
        this.patchCondition(group, index, { operator: v as FilterOperator });
        this.renderFilter();
      });
    });

    if (!NO_VALUE_OPERATORS.has(condition.operator)) {
      const columnConfig = this.profile.columns.find(
        (c) => c.name.toLowerCase() === condition.field.toLowerCase(),
      );
      const options = columnConfig?.type === "select" ? columnConfig.options : undefined;
      if (options && options.length > 0) {
        setting.addDropdown((d) => {
          d.addOption("", "—");
          for (const option of options) d.addOption(option.value, option.label ?? option.value);
          d.setValue(condition.value ?? "").onChange((v) => this.patchCondition(group, index, { value: v }));
        });
      } else {
        setting.addText((t) =>
          t
            .setPlaceholder("value")
            .setValue(condition.value ?? "")
            .onChange((v) => this.patchCondition(group, index, { value: v })),
        );
      }
    }

    setting.addExtraButton((b) =>
      b
        .setIcon("trash")
        .setTooltip("Remove")
        .onClick(() => {
          this.setFilter({ ...group, conditions: group.conditions.filter((_, i) => i !== index) });
          this.renderFilter();
        }),
    );
  }

  private patchCondition(group: FilterGroup, index: number, patch: Partial<FilterCondition>): void {
    this.setFilter({
      ...group,
      conditions: group.conditions.map((c, i) => (i === index ? { ...c, ...patch } : c)),
    });
  }

  private setFilter(group: FilterGroup): void {
    const empty = group.conditions.length === 0 && group.groups.length === 0;
    this.patch({ filter: empty ? null : group });
  }

  // ---- Sort ----
  private renderSort(): void {
    const el = this.sortEl;
    el.empty();
    new Setting(el).setName("Sort").setHeading();

    const fields = fieldOptions(this.profile.columns);

    new Setting(el).addButton((button) =>
      button
        .setButtonText("Add sort")
        .setCta()
        .onClick(() => {
          this.patch({ sort: [...this.edited().sort, { field: fields[0]?.name ?? "note", direction: "asc" }] });
          this.renderSort();
        }),
    );

    if (this.edited().sort.length === 0) {
      el.createDiv({ cls: "kvs-empty", text: "No sort — rows keep their natural order." });
    }

    this.edited().sort.forEach((key, index) => {
      const setting = new Setting(el).setClass("kvs-settings-condition");
      setting.addDropdown((d) => {
        for (const field of fields) d.addOption(field.name, field.name);
        if (key.field && !fields.some((f) => f.name === key.field)) d.addOption(key.field, key.field);
        d.setValue(key.field || fields[0]?.name || "note").onChange((v) => this.patchSort(index, { field: v }));
      });
      setting.addDropdown((d) => {
        d.addOption("asc", "Ascending");
        d.addOption("desc", "Descending");
        d.setValue(key.direction).onChange((v) => this.patchSort(index, { direction: v === "desc" ? "desc" : "asc" }));
      });
      setting.addExtraButton((b) =>
        b.setIcon("chevron-up").setTooltip("Move up").onClick(() => {
          this.patch({ sort: moveItem(this.edited().sort, index, index - 1) });
          this.renderSort();
        }),
      );
      setting.addExtraButton((b) =>
        b.setIcon("chevron-down").setTooltip("Move down").onClick(() => {
          this.patch({ sort: moveItem(this.edited().sort, index, index + 1) });
          this.renderSort();
        }),
      );
      setting.addExtraButton((b) =>
        b.setIcon("trash").setTooltip("Remove").onClick(() => {
          this.patch({ sort: this.edited().sort.filter((_, i) => i !== index) });
          this.renderSort();
        }),
      );
    });
  }

  private patchSort(index: number, patch: Partial<Profile["sort"][number]>): void {
    this.patch({ sort: this.edited().sort.map((k, i) => (i === index ? { ...k, ...patch } : k)) });
  }
}
