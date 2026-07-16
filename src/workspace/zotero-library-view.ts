import { ItemView, Notice, setIcon, setTooltip, type App, type WorkspaceLeaf } from "obsidian";
import type { ZoteroLibraryItem, ZoteroProvider } from "../services/zotero/provider";
import type { ZoteroLibraryCache } from "../services/zotero/zotero-library-cache";
import { ZOTERO_COLUMNS } from "../services/zotero/zotero-rows";

export const ZOTERO_LIBRARY_VIEW_TYPE = "kvs-zotero-library";

/** Open (or focus) the live Zotero library view in a tab. */
export async function openZoteroLibraryView(app: App): Promise<void> {
  let leaf = app.workspace.getLeavesOfType(ZOTERO_LIBRARY_VIEW_TYPE)[0];
  if (!leaf) {
    leaf = app.workspace.getLeaf("tab");
    await leaf.setViewState({ type: ZOTERO_LIBRARY_VIEW_TYPE, active: true });
  }
  await app.workspace.revealLeaf(leaf);
}

/** The columns shown, and how to read each from an item — display order matches ZOTERO_COLUMNS. */
const COLUMN_VALUE: Record<(typeof ZOTERO_COLUMNS)[number], (i: ZoteroLibraryItem) => string> = {
  Title: (i) => i.title,
  Creators: (i) => i.creators,
  Year: (i) => i.year,
  Type: (i) => i.itemType,
  Publication: (i) => i.publication,
  "Cite Key": (i) => i.citeKey,
  DOI: (i) => i.doi,
  Tags: (i) => i.tags.join(", "),
  Collections: (i) => i.collections.join(", "),
  Added: (i) => i.dateAdded.slice(0, 10),
  Modified: (i) => i.dateModified.slice(0, 10),
};

/**
 * A live view of your Zotero library inside Obsidian.
 *
 * This is the piece that answers "make Zotero feel native". Where zotero-lib-view reads a *stale* Better
 * BibTeX JSON export, this reads Zotero's **live local API** — the library is always current, no manual
 * re-export. It is deliberately read-only, because Zotero's local API is read-only today, and it says so
 * plainly rather than pretending. The path to editing is already laid (see the write seam in
 * `services/zotero/provider.ts`); this view will gain an edit affordance the day the provider's write
 * backend reports it can write, without being rebuilt.
 */
export class ZoteroLibraryView extends ItemView {
  private items: ZoteroLibraryItem[] = [];
  private filtered: ZoteroLibraryItem[] = [];
  private query = "";
  private sortBy: (typeof ZOTERO_COLUMNS)[number] = "Added";
  private sortDir: "asc" | "desc" = "desc";
  private tableWrap!: HTMLElement;
  private statusEl!: HTMLElement;
  private actionBar!: HTMLElement;
  /** Keys of items the user has ticked. Actions operate on these, or on the filtered view when none. */
  private readonly selected = new Set<string>();
  /** Zotero keys that already have a literature note in the vault — for the reading-progress indicator. */
  private notedKeys = new Set<string>();

  constructor(
    leaf: WorkspaceLeaf,
    private readonly provider: ZoteroProvider,
    private readonly onOpenItem: (item: ZoteroLibraryItem) => void,
    /** Build a KVS dashboard from these Zotero items (a selection, or the whole filtered view). */
    private readonly onSendToDashboard: (items: ZoteroLibraryItem[]) => void,
    /** Create-or-open a literature note for these items (one, or a selection). Returns after all are done. */
    private readonly onLiteratureNotes: (items: ZoteroLibraryItem[]) => Promise<void>,
    /** Report the set of Zotero keys that currently have a literature note, for the status indicator. */
    private readonly notedKeysProvider: () => Set<string>,
    /** Shared library cache, so opening this view reuses a recent fetch (from fill/promote/search) instead
     *  of re-reading the whole library every time. Absent = always fetch directly. */
    private readonly libraryCache?: ZoteroLibraryCache,
  ) {
    super(leaf);
  }

  override getViewType(): string {
    return ZOTERO_LIBRARY_VIEW_TYPE;
  }
  override getDisplayText(): string {
    return "Zotero library";
  }
  override getIcon(): string {
    return "library";
  }

  override async onOpen(): Promise<void> {
    const root = this.contentEl;
    root.empty();
    root.addClass("kvs-zotero-lib");

    const bar = root.createDiv({ cls: "kvs-zotero-bar" });
    const search = bar.createEl("input", { cls: "kvs-zotero-search", attr: { type: "search", placeholder: "Search your Zotero library…", "aria-label": "Search Zotero library" } });
    search.addEventListener("input", () => {
      this.query = search.value;
      this.applyFilter();
      this.renderTable();
    });
    const refresh = bar.createEl("button", { cls: "kvs-zotero-refresh", attr: { "aria-label": "Refresh from Zotero" } });
    setIcon(refresh, "refresh-cw");
    setTooltip(refresh, "Reload from Zotero");
    refresh.addEventListener("click", () => void this.reload(true));

    // The honest capability line: read-only, and why. Announced to assistive tech as a status.
    const note = root.createDiv({ cls: "kvs-zotero-note", attr: { role: "status" } });
    note.setText(this.provider.writes.capabilityNote());

    // The action bar: send a selection (or the whole filtered view) straight to a dashboard, or copy it.
    // This is the friction-remover — the library and the dashboard stop being two disconnected worlds.
    this.actionBar = root.createDiv({ cls: "kvs-zotero-actions" });
    this.renderActionBar();

    this.statusEl = root.createDiv({ cls: "kvs-zotero-status", attr: { role: "status", "aria-live": "polite" } });
    this.tableWrap = root.createDiv({ cls: "kvs-zotero-table-wrap" });

    await this.reload();
    return undefined;
  }

  private async reload(force = false): Promise<void> {
    this.statusEl.setText("Loading from Zotero…");
    if (force) this.libraryCache?.invalidate();
    this.notedKeys = this.notedKeysProvider();
    // Reuse the shared cache when present, so opening this view right after a fill/promote (or reopening it)
    // is instant instead of re-reading the whole library.
    try {
      this.items = this.libraryCache ? await this.libraryCache.getItems(this.provider) : await this.provider.listItems();
    } catch {
      this.items = [];
    }
    // An empty result is ambiguous — unreachable Zotero, or a genuinely empty library. Only then pay for a
    // reachability probe to show the right message; a non-empty library needs no extra round-trip.
    if (this.items.length === 0 && !(await this.provider.ping())) {
      this.statusEl.setText("");
      this.tableWrap.empty();
      const empty = this.tableWrap.createDiv({ cls: "kvs-zotero-empty" });
      empty.createEl("p", { text: "Can't reach Zotero." });
      empty.createEl("p", { cls: "kvs-zotero-empty-sub", text: "Make sure Zotero is running and its local API is enabled (Zotero → Settings → Advanced → \"Allow other applications on this computer to communicate with Zotero\")." });
      return;
    }
    this.applyFilter();
    this.renderTable();
  }

  private applyFilter(): void {
    const q = this.query.trim().toLowerCase();
    this.filtered = q === ""
      ? [...this.items]
      : this.items.filter((i) => this.searchText(i).includes(q));
    // Sort a copy by the active column/direction.
    const read = COLUMN_VALUE[this.sortBy];
    this.filtered.sort((a, b) => {
      const cmp = read(a).localeCompare(read(b), undefined, { numeric: true });
      return this.sortDir === "asc" ? cmp : -cmp;
    });
  }

  /** Full-record search, like zotero-lib-view: title, creators, year, tags, publication, abstract, DOI. */
  private searchText(i: ZoteroLibraryItem): string {
    return [i.title, i.creators, i.year, i.publication, i.doi, i.citeKey, i.tags.join(" "), i.extra["abstract"] ?? ""].join(" ").toLowerCase();
  }

  private renderTable(): void {
    this.tableWrap.empty();
    this.statusEl.setText(`${this.filtered.length} of ${this.items.length} item${this.items.length === 1 ? "" : "s"}`);

    if (this.items.length === 0) {
      this.tableWrap.createDiv({ cls: "kvs-zotero-empty", text: "Your Zotero library has no top-level items, or none were returned." });
      return;
    }

    const table = this.tableWrap.createEl("table", { cls: "kvs-zotero-table" });
    table.setAttribute("role", "grid");
    table.setAttribute("aria-rowcount", String(this.filtered.length + 1));

    const headRow = table.createEl("thead").createEl("tr");
    headRow.setAttribute("aria-rowindex", "1");
    // Leading select-all checkbox.
    const selAllTh = headRow.createEl("th", { cls: "kvs-zotero-th kvs-zotero-checkcol" });
    selAllTh.setAttribute("scope", "col");
    const selAll = selAllTh.createEl("input", { attr: { type: "checkbox", "aria-label": "Select all shown" } });
    selAll.checked = this.filtered.length > 0 && this.filtered.every((i) => this.selected.has(i.key));
    selAll.addEventListener("change", () => {
      if (selAll.checked) for (const i of this.filtered) this.selected.add(i.key);
      else for (const i of this.filtered) this.selected.delete(i.key);
      this.renderActionBar();
      this.renderTable();
    });
    for (const col of ZOTERO_COLUMNS) {
      const th = headRow.createEl("th", { text: col, cls: "kvs-zotero-th" });
      th.setAttribute("scope", "col");
      th.setAttribute("role", "columnheader");
      th.tabIndex = 0;
      const active = this.sortBy === col;
      th.setAttribute("aria-sort", active ? (this.sortDir === "desc" ? "descending" : "ascending") : "none");
      if (active) th.createSpan({ cls: "kvs-zotero-sort", text: this.sortDir === "desc" ? " ↓" : " ↑" });
      const sort = (): void => {
        if (this.sortBy === col) this.sortDir = this.sortDir === "asc" ? "desc" : "asc";
        else {
          this.sortBy = col;
          this.sortDir = "asc";
        }
        this.applyFilter();
        this.renderTable();
      };
      th.addEventListener("click", sort);
      th.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          sort();
        }
      });
    }

    const tbody = table.createEl("tbody");
    this.filtered.forEach((item, idx) => {
      const tr = tbody.createEl("tr", { cls: "kvs-zotero-row" });
      tr.setAttribute("aria-rowindex", String(idx + 2));
      // Leading selection checkbox.
      const checkTd = tr.createEl("td", { cls: "kvs-zotero-td kvs-zotero-checkcol" });
      const check = checkTd.createEl("input", { attr: { type: "checkbox", "aria-label": `Select ${item.title || item.key}` } });
      check.checked = this.selected.has(item.key);
      if (check.checked) tr.addClass("is-selected");
      check.addEventListener("change", () => {
        if (check.checked) this.selected.add(item.key);
        else this.selected.delete(item.key);
        tr.toggleClass("is-selected", check.checked);
        this.renderActionBar();
      });
      for (const col of ZOTERO_COLUMNS) {
        const td = tr.createEl("td", { cls: "kvs-zotero-td" });
        const value = COLUMN_VALUE[col](item);
        if (col === "Title") {
          // A note dot shows reading progress at a glance: filled when this paper already has a literature
          // note in the vault, hollow when it doesn't.
          const hasNote = this.notedKeys.has(item.key);
          const dot = td.createSpan({ cls: hasNote ? "kvs-zotero-noted is-noted" : "kvs-zotero-noted" });
          setTooltip(dot, hasNote ? "Has a literature note" : "No literature note yet");
          // Clicking the title creates the literature note (or opens it if it exists) — the researcher's
          // actual endpoint: a first-class Obsidian note to think in, not the paper's web page.
          const link = td.createEl("a", { cls: "kvs-zotero-title", text: value || "(untitled)", href: "#" });
          setTooltip(link, hasNote ? "Open literature note" : "Create literature note");
          link.addEventListener("click", (e) => {
            e.preventDefault();
            void this.onLiteratureNotes([item]).then(() => {
              this.notedKeys = this.notedKeysProvider();
              this.renderTable();
            });
          });
          // Secondary: jump to the item in Zotero itself.
          const ext = td.createSpan({ cls: "kvs-zotero-ext" });
          setIcon(ext, "external-link");
          setTooltip(ext, "Open in Zotero");
          ext.addEventListener("click", (e) => {
            e.stopPropagation();
            this.onOpenItem(item);
          });
        } else {
          td.setText(value);
        }
      }
    });
  }

  /** The items an action applies to: the ticked ones, or — when none are ticked — the whole filtered view. */
  private targetItems(): { items: ZoteroLibraryItem[]; usingSelection: boolean } {
    if (this.selected.size > 0) {
      const items = this.items.filter((i) => this.selected.has(i.key));
      return { items, usingSelection: true };
    }
    return { items: [...this.filtered], usingSelection: false };
  }

  /**
   * Render the action bar. It always reflects what an action would act on right now — the selection if
   * there is one, otherwise everything currently shown — so "Send to dashboard" is never ambiguous.
   */
  private renderActionBar(): void {
    if (!this.actionBar) return;
    this.actionBar.empty();
    const { items, usingSelection } = this.targetItems();
    const n = items.length;
    const scopeLabel = usingSelection ? `${n} selected` : `all ${n} shown`;

    const label = this.actionBar.createSpan({ cls: "kvs-zotero-actlabel" });
    label.setText(usingSelection ? `${n} selected` : "No selection");

    const mkBtn = (text: string, icon: string, tip: string, onClick: () => void): void => {
      const b = this.actionBar.createEl("button", { cls: "kvs-zotero-actbtn" });
      setIcon(b.createSpan({ cls: "kvs-zotero-actbtn-ic" }), icon);
      b.createSpan({ text });
      setTooltip(b, tip);
      b.disabled = n === 0;
      b.addEventListener("click", onClick);
    };

    mkBtn("Open as dashboard", "layout-dashboard", `Build a KVS dashboard (all layouts) from ${scopeLabel}`, () => {
      this.onSendToDashboard(this.targetItems().items);
    });
    mkBtn("Create notes", "file-plus", `Create (or open) a literature note for each of ${scopeLabel}`, () => {
      void this.onLiteratureNotes(this.targetItems().items).then(() => {
        this.notedKeys = this.notedKeysProvider();
        this.renderTable();
      });
    });
    mkBtn("Copy as table", "table", `Copy ${scopeLabel} as a Markdown table`, () => void this.copyAsTable());
    mkBtn("Copy citations", "quote", `Copy cite keys for ${scopeLabel}`, () => void this.copyCitations());

    if (usingSelection) {
      const clear = this.actionBar.createEl("button", { cls: "kvs-zotero-actclear", text: "Clear" });
      setTooltip(clear, "Clear selection");
      clear.addEventListener("click", () => {
        this.selected.clear();
        this.renderActionBar();
        this.renderTable();
      });
    }
  }

  /** Copy the target items as a Markdown table — which, pasted into a note, KVS's own table source re-reads. */
  private async copyAsTable(): Promise<void> {
    const { items } = this.targetItems();
    if (items.length === 0) return;
    const cols = ["Title", "Creators", "Year", "Type", "Publication", "Cite Key", "DOI"] as const;
    const esc = (s: string): string => s.replace(/\|/g, "\\|").replace(/\n/g, " ");
    const header = `| ${cols.join(" | ")} |`;
    const rule = `| ${cols.map(() => "---").join(" | ")} |`;
    const body = items
      .map((i) => `| ${[i.title, i.creators, i.year, i.itemType, i.publication, i.citeKey, i.doi].map((v) => esc(v ?? "")).join(" | ")} |`)
      .join("\n");
    await this.copyText(`${header}\n${rule}\n${body}\n`, `Copied ${items.length} item${items.length === 1 ? "" : "s"} as a Markdown table`);
  }

  /** Copy the target items' cite keys (falling back to a compact reference when a key is missing). */
  private async copyCitations(): Promise<void> {
    const { items } = this.targetItems();
    if (items.length === 0) return;
    const lines = items.map((i) => (i.citeKey ? `[@${i.citeKey}]` : `${i.creators}${i.year ? ` (${i.year})` : ""}. ${i.title}.`));
    await this.copyText(lines.join("\n") + "\n", `Copied ${items.length} citation${items.length === 1 ? "" : "s"}`);
  }

  private async copyText(text: string, okMessage: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      new Notice(okMessage);
    } catch {
      new Notice("Couldn't access the clipboard.");
    }
  }
}
