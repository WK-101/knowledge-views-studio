import { ItemView, setIcon, setTooltip, type App, type WorkspaceLeaf } from "obsidian";
import type { ZoteroLibraryItem, ZoteroProvider } from "../services/zotero/provider";
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

  constructor(
    leaf: WorkspaceLeaf,
    private readonly provider: ZoteroProvider,
    private readonly onOpenItem: (item: ZoteroLibraryItem) => void,
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
    refresh.addEventListener("click", () => void this.reload());

    // The honest capability line: read-only, and why. Announced to assistive tech as a status.
    const note = root.createDiv({ cls: "kvs-zotero-note", attr: { role: "status" } });
    note.setText(this.provider.writes.capabilityNote());

    this.statusEl = root.createDiv({ cls: "kvs-zotero-status", attr: { role: "status", "aria-live": "polite" } });
    this.tableWrap = root.createDiv({ cls: "kvs-zotero-table-wrap" });

    await this.reload();
    return undefined;
  }

  private async reload(): Promise<void> {
    this.statusEl.setText("Loading from Zotero…");
    const reachable = await this.provider.ping();
    if (!reachable) {
      this.statusEl.setText("");
      this.tableWrap.empty();
      const empty = this.tableWrap.createDiv({ cls: "kvs-zotero-empty" });
      empty.createEl("p", { text: "Can't reach Zotero." });
      empty.createEl("p", { cls: "kvs-zotero-empty-sub", text: "Make sure Zotero is running and its local API is enabled (Zotero → Settings → Advanced → \"Allow other applications on this computer to communicate with Zotero\")." });
      return;
    }
    try {
      this.items = await this.provider.listItems({ limit: 1000 });
      this.applyFilter();
      this.renderTable();
    } catch {
      this.statusEl.setText("Couldn't read the library from Zotero.");
    }
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
      for (const col of ZOTERO_COLUMNS) {
        const td = tr.createEl("td", { cls: "kvs-zotero-td" });
        const value = COLUMN_VALUE[col](item);
        if (col === "Title") {
          // The title is the click target — opens the item (its note, or its attachment via the caller).
          const link = td.createEl("a", { cls: "kvs-zotero-title", text: value || "(untitled)", href: "#" });
          link.addEventListener("click", (e) => {
            e.preventDefault();
            this.onOpenItem(item);
          });
        } else {
          td.setText(value);
        }
      }
    });
  }
}
