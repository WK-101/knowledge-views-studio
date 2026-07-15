import { FuzzySuggestModal, ItemView, Menu, Notice, SearchComponent, TFile, TFolder, setIcon, setTooltip, type App, type WorkspaceLeaf } from "obsidian";
import { makeSnippet, noteToDocs, parseQuery, questionTerms, rowsToDocs, scoringTerms, type SearchResult, type Snippet } from "../services/index";
import type { AnswerPassage, SearchIndexer } from "./search-indexer";
import { closePopover, openPopover } from "./popover";

export const SEARCH_VIEW_TYPE = "kvs-search-view";

interface SourceMeta {
  readonly id: string;
  readonly label: string;
  readonly icon: string;
  readonly color: string;
}
const SOURCES: readonly SourceMeta[] = [
  { id: "note", label: "Notes", icon: "file-text", color: "var(--color-blue)" },
  { id: "annotation", label: "Annotations", icon: "highlighter", color: "var(--color-yellow)" },
  { id: "row", label: "Rows", icon: "table", color: "var(--color-green)" },
  { id: "pdf", label: "PDF", icon: "book", color: "var(--color-red)" },
  { id: "docx", label: "Word", icon: "file-text", color: "var(--color-cyan)" },
  { id: "pptx", label: "PowerPoint", icon: "presentation", color: "var(--color-orange)" },
  { id: "xlsx", label: "Excel", icon: "sheet", color: "var(--color-green)" },
  { id: "epub", label: "EPUB", icon: "book-open", color: "var(--color-purple)" },
  { id: "zotero", label: "Zotero", icon: "library", color: "var(--color-pink)" },
  { id: "zotero-annotation", label: "Zotero notes", icon: "highlighter", color: "var(--color-pink)" },
];
const SOURCE = new Map(SOURCES.map((s) => [s.id, s]));
const FACET_LIMIT = 3000;

function highlightTerms(query: string): string[] {
  return scoringTerms(parseQuery(query)).map((k) => (k.includes("\u0000") ? k.slice(k.indexOf("\u0000") + 1) : k));
}

/** Open (or focus) the dedicated search view. Shared by the command, ribbon, and dashboard toolbar. */
export async function openSearchView(app: App): Promise<void> {
  let leaf = app.workspace.getLeavesOfType(SEARCH_VIEW_TYPE)[0];
  if (!leaf) {
    leaf = app.workspace.getLeaf(true);
    await leaf.setViewState({ type: SEARCH_VIEW_TYPE, active: true });
  }
  await app.workspace.revealLeaf(leaf);
  if (leaf.view instanceof SearchView) leaf.view.focusInput();
}

interface SearchState {
  query: string;
  selected: string[];
  matchMode: "all" | "any";
  compact: boolean;
  group: boolean;
  collapsed: string[];
  fuzzy: boolean;
  folders: string[];
  sort: "relevance" | "modified" | "name";
  mode: "keyword" | "semantic" | "hybrid" | "ask";
}

/** Native folder picker for scoping the search. */
class FolderSuggest extends FuzzySuggestModal<TFolder> {
  constructor(
    app: App,
    private readonly onPick: (folder: TFolder) => void,
  ) {
    super(app);
    this.setPlaceholder("Scope search to a folder…");
  }
  getItems(): TFolder[] {
    return this.app.vault.getAllLoadedFiles().filter((f): f is TFolder => f instanceof TFolder && f.path !== "/");
  }
  getItemText(item: TFolder): string {
    return item.path;
  }
  onChooseItem(item: TFolder): void {
    this.onPick(item);
  }
}

export class SearchView extends ItemView {
  private query = "";
  private selected = new Set(SOURCES.map((s) => s.id));
  private matchMode: "all" | "any" = "all";
  private compact = false;
  private group = false;
  private collapsed = new Set<string>();
  private fuzzy = false;
  private folders: string[] = [];
  private sort: "relevance" | "modified" | "name" = "relevance";
  private mode: "keyword" | "semantic" | "hybrid" | "ask" = "keyword";
  private timer: number | undefined;
  private hits: SearchResult[] = [];
  private rowEls: HTMLElement[] = [];
  private activeIndex = -1;

  private inputEl!: HTMLInputElement;
  private search!: SearchComponent;
  private scopeBtn?: HTMLElement;
  private folderBtn?: HTMLElement;
  private lastCounts: Record<string, number> = {};
  private countEl!: HTMLElement;
  private resultsEl!: HTMLElement;

  constructor(
    leaf: WorkspaceLeaf,
    private readonly indexer: SearchIndexer,
  ) {
    super(leaf);
  }

  override getViewType(): string {
    return SEARCH_VIEW_TYPE;
  }
  override getDisplayText(): string {
    return "Search";
  }
  override getIcon(): string {
    return "search";
  }

  override async onOpen(): Promise<void> {
    this.loadState();
    const root = this.contentEl;
    root.empty();
    root.addClass("kvs-searchview");

    // ================= Bar 1: search + display controls =================
    const bar = root.createDiv({ cls: "kvs-toolbar-bar kvs-sv-bar" });

    this.search = new SearchComponent(bar);
    this.search.setValue(this.query);
    this.search.onChange((v) => {
      this.query = v;
      this.schedule();
    });
    this.inputEl = this.search.inputEl;
    this.inputEl.parentElement?.addClass("kvs-sv-search");
    this.search.inputEl.addEventListener("keydown", (e) => this.onKey(e));
    this.updatePlaceholder();

    const tools = bar.createDiv({ cls: "kvs-tb-group kvs-sv-tools" });
    this.mkSeg(
      tools,
      [
        { id: "detailed", label: "Detailed", icon: "align-left" },
        { id: "compact", label: "Compact", icon: "align-justify" },
      ],
      this.compact ? "compact" : "detailed",
      (id) => {
        this.compact = id === "compact";
        this.persist();
        this.render();
      },
    ).addClass("kvs-sv-viewseg");
    const groupBtn = this.iconBtn(tools, "layers", "Group by type", this.group, () => {
      this.group = !this.group;
      groupBtn.toggleClass("kvs-tb-active", this.group);
      this.persist();
      this.render();
    });
    this.iconBtn(tools, "arrow-up-down", "Sort results", false, (e) => this.openSortMenu(e));
    this.iconBtn(tools, "refresh-cw", "Rebuild search index", false, () => this.rebuildIndex());
    this.iconBtn(tools, "circle-help", "Search syntax", false, (e) => this.openHelpMenu(e));
    this.countEl = bar.createDiv({ cls: "kvs-sv-count kvs-results" });
    // The result count is the one place a screen-reader user learns their search returned anything. As a
    // live region, "12 results" / "No matches" / "5 passages" is announced when it changes, without
    // moving focus off the search box they are still typing in.
    this.countEl.setAttribute("role", "status");
    this.countEl.setAttribute("aria-live", "polite");

    // ================= Bar 2: modes + filters (single compact row) =================
    const filter = root.createDiv({ cls: "kvs-toolbar-bar kvs-sv-filterbar" });

    this.mkSeg(
      filter,
      [
        { id: "keyword", label: "Keyword", icon: "search" },
        { id: "semantic", label: "Semantic", icon: "sparkles" },
        { id: "hybrid", label: "Hybrid", icon: "blend" },
        { id: "ask", label: "Ask", icon: "message-square" },
      ],
      this.mode,
      (id) => {
        this.mode = id as "keyword" | "semantic" | "hybrid" | "ask";
        this.persist();
        this.updatePlaceholder();
        this.render();
      },
    );

    filter.createDiv({ cls: "kvs-toolbar-sep" });
    const matchGroup = filter.createDiv({ cls: "kvs-tb-group" });
    this.mkSeg(matchGroup, [{ id: "all", label: "All" }, { id: "any", label: "Any" }], this.matchMode, (id) => {
      this.matchMode = id as "all" | "any";
      this.persist();
      this.render();
    });
    const fuzzyBtn = this.iconBtn(matchGroup, "wand-sparkles", "Fuzzy / partial matching (typing part of a word matches the whole)", this.fuzzy, () => {
      this.fuzzy = !this.fuzzy;
      fuzzyBtn.toggleClass("kvs-tb-active", this.fuzzy);
      this.persist();
      this.render();
    });

    filter.createDiv({ cls: "kvs-toolbar-sep" });
    const filterGroup = filter.createDiv({ cls: "kvs-tb-group" });
    this.scopeBtn = this.iconBtn(filterGroup, "filter", "Filter by type", false, () => this.openScopePopover(this.scopeBtn!));
    this.folderBtn = this.iconBtn(filterGroup, "folder", "Scope to folders", false, () => this.openFolderPopover(this.folderBtn!));

    this.resultsEl = root.createDiv({ cls: "kvs-sv-results" });
    this.syncFilterBtns();

    this.render();
    window.setTimeout(() => this.inputEl.focus(), 0);
    return Promise.resolve();
  }

  private openHelpMenu(e: MouseEvent): void {
    const menu = new Menu();
    const items: [string, string][] = [
      ['"exact phrase"', "Exact phrase"],
      ["-word", "Exclude a word"],
      ["a OR b", "Either term"],
      ["(a OR b) c", "Grouping"],
      ["title:word", "Match in the title"],
      ["tag:name", "Has the tag"],
      ["author:name", "Any frontmatter field"],
      ["/regex/", "Regular expression"],
    ];
    for (const [ex, desc] of items) {
      menu.addItem((it) =>
        it.setTitle(`${desc}  ·  ${ex}`).onClick(() => {
          const next = `${this.inputEl.value} ${ex}`.trim();
          this.search.setValue(next);
          this.query = next;
          this.inputEl.focus();
          this.render();
        }),
      );
    }
    menu.showAtMouseEvent(e);
  }

  private openSortMenu(e: MouseEvent): void {
    const menu = new Menu();
    for (const [id, label] of [["relevance", "Relevance"], ["modified", "Last modified"], ["name", "Name"]] as const) {
      menu.addItem((item) => {
        item
          .setTitle(label)
          .setChecked(this.sort === id)
          .onClick(() => {
            this.sort = id;
            this.persist();
            this.render();
          });
      });
    }
    menu.showAtMouseEvent(e);
  }

  /** Folder-scope chips + an add button. Empty = whole vault. */
  private syncFilterBtns(): void {
    const scopeActive = !SOURCES.every((s) => this.selected.has(s.id));
    this.scopeBtn?.toggleClass("kvs-tb-active", scopeActive);
    this.folderBtn?.toggleClass("kvs-tb-active", this.folders.length > 0);
  }

  private rebuildIndex(): void {
    const notice = new Notice("KVS: rebuilding search index…", 0);
    void this.indexer
      .rebuild((done, total) => notice.setMessage(`KVS: indexing ${done}/${total}…`))
      .then(() => {
        notice.hide();
        new Notice("KVS search index rebuilt.", 3000);
        this.render();
      });
  }

  /** Type filter as a popover (dashboard-style), replacing inline pill chips. */
  private openScopePopover(anchor: HTMLElement): void {
    openPopover(anchor, (content, handle) => {
      content.addClass("kvs-sv-pop");
      const head = content.createDiv({ cls: "kvs-sv-pophead" });
      head.createSpan({ text: "Show types" });
      const allLink = head.createEl("button", { cls: "kvs-sv-poplink", text: "All" });
      allLink.addEventListener("click", () => {
        this.selected = new Set(SOURCES.map((s) => s.id));
        this.persist();
        this.render();
        handle.rerender();
      });
      for (const src of SOURCES) {
        const on = this.selected.has(src.id);
        const n = this.lastCounts[src.id] ?? 0;
        const row = content.createDiv({ cls: on ? "kvs-sv-poprow is-on" : "kvs-sv-poprow" });
        const check = row.createSpan({ cls: "kvs-sv-popcheck" });
        if (on) setIcon(check, "check");
        const dot = row.createSpan({ cls: "kvs-sv-scopedot" });
        dot.style.background = src.color;
        row.createSpan({ cls: "kvs-sv-poplabel", text: src.label });
        row.createSpan({ cls: "kvs-sv-popcount", text: String(n) });
        const only = row.createEl("button", { cls: "kvs-sv-poponly", text: "only" });
        only.addEventListener("click", (e) => {
          e.stopPropagation();
          this.selected = new Set([src.id]);
          this.persist();
          this.render();
          handle.rerender();
        });
        row.addEventListener("click", () => {
          if (this.selected.has(src.id)) this.selected.delete(src.id);
          else this.selected.add(src.id);
          if (this.selected.size === 0) this.selected = new Set(SOURCES.map((s) => s.id));
          this.persist();
          this.render();
          handle.rerender();
        });
      }
    });
  }

  /** Folder scope as a popover. */
  private openFolderPopover(anchor: HTMLElement): void {
    openPopover(anchor, (content, handle) => {
      content.addClass("kvs-sv-pop");
      content.createDiv({ cls: "kvs-sv-pophead", text: "Scope to folders" });
      if (this.folders.length === 0) content.createDiv({ cls: "kvs-sv-popmuted", text: "Searching the whole vault" });
      for (const folder of this.folders) {
        const row = content.createDiv({ cls: "kvs-sv-poprow" });
        setIcon(row.createSpan({ cls: "kvs-sv-popcheck" }), "folder");
        row.createSpan({ cls: "kvs-sv-poplabel", text: folder });
        const rm = row.createEl("button", { cls: "kvs-sv-poponly", text: "remove" });
        rm.addEventListener("click", (e) => {
          e.stopPropagation();
          this.folders = this.folders.filter((f) => f !== folder);
          this.persist();
          this.render();
          handle.rerender();
        });
      }
      const add = content.createEl("button", { cls: "kvs-sv-popadd" });
      setIcon(add.createSpan({ cls: "kvs-sv-popaddic" }), "folder-plus");
      add.createSpan({ text: "Add folder" });
      add.addEventListener("click", () => {
        new FolderSuggest(this.app, (f) => {
          if (!this.folders.includes(f.path)) this.folders.push(f.path);
          this.persist();
          this.render();
          handle.rerender();
        }).open();
      });
    });
  }

  override async onClose(): Promise<void> {
    closePopover();
    if (this.timer) window.clearTimeout(this.timer);
    return Promise.resolve();
  }

  focusInput(): void {
    this.inputEl?.focus();
  }

  private schedule(): void {
    if (this.timer) window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this.render(), 160);
  }

  private iconBtn(parent: HTMLElement, icon: string, tip: string, active: boolean, onClick: (e: MouseEvent) => void): HTMLElement {
    const btn = parent.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    setIcon(btn, icon);
    setTooltip(btn, tip);
    btn.toggleClass("kvs-tb-active", active);
    btn.addEventListener("click", (e) => onClick(e));
    return btn;
  }

  // ---- segmented control (matches the dashboard's kvs-seg) ----
  private mkSeg(parent: HTMLElement, options: readonly { id: string; label: string; icon?: string }[], active: string, onChange: (id: string) => void): HTMLElement {
    const seg = parent.createDiv({ cls: "kvs-seg" });
    for (const opt of options) {
      const btn = seg.createEl("button", { cls: "kvs-seg-btn" });
      if (opt.icon) setIcon(btn.createSpan({ cls: "kvs-seg-ic" }), opt.icon);
      btn.createSpan({ text: opt.label });
      btn.toggleClass("is-on", opt.id === active);
      btn.addEventListener("click", () => {
        seg.querySelectorAll(".kvs-seg-btn").forEach((b) => b.removeClass("is-on"));
        btn.addClass("is-on");
        onChange(opt.id);
      });
    }
    return seg;
  }

  // ---- persisted state ----
  private stateKey(): string {
    return `kvs-search-state:${this.app.vault.getName()}`;
  }
  private loadState(): void {
    try {
      const raw = window.localStorage.getItem(this.stateKey());
      if (!raw) return;
      const s = JSON.parse(raw) as Partial<SearchState>;
      if (typeof s.query === "string") this.query = s.query;
      if (Array.isArray(s.selected) && s.selected.length > 0) this.selected = new Set(s.selected);
      if (s.matchMode === "all" || s.matchMode === "any") this.matchMode = s.matchMode;
      this.compact = Boolean(s.compact);
      this.group = Boolean(s.group);
      if (Array.isArray(s.collapsed)) this.collapsed = new Set(s.collapsed);
      this.fuzzy = Boolean(s.fuzzy);
      if (Array.isArray(s.folders)) this.folders = s.folders.filter((f): f is string => typeof f === "string");
      if (s.sort === "relevance" || s.sort === "modified" || s.sort === "name") this.sort = s.sort;
      if (s.mode === "keyword" || s.mode === "semantic" || s.mode === "hybrid" || s.mode === "ask") this.mode = s.mode;
    } catch {
      /* ignore */
    }
  }
  private persist(): void {
    try {
      const s: SearchState = { query: this.query, selected: [...this.selected], matchMode: this.matchMode, compact: this.compact, group: this.group, collapsed: [...this.collapsed], fuzzy: this.fuzzy, folders: this.folders, sort: this.sort, mode: this.mode };
      window.localStorage.setItem(this.stateKey(), JSON.stringify(s));
    } catch {
      /* ignore */
    }
  }

  private onKey(e: KeyboardEvent): void {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      this.moveActive(1);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      this.moveActive(-1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      const h = this.hits[this.activeIndex] ?? this.hits[0];
      if (h) this.jump(h);
    } else if (e.key === "Escape" && this.inputEl.value !== "") {
      e.preventDefault();
      this.search.setValue("");
      this.query = "";
      this.persist();
      this.render();
    }
  }

  private moveActive(delta: number): void {
    if (this.rowEls.length === 0) return;
    if (this.activeIndex >= 0) this.rowEls[this.activeIndex]?.removeClass("is-active");
    this.activeIndex = Math.max(0, Math.min(this.rowEls.length - 1, this.activeIndex + delta));
    const el = this.rowEls[this.activeIndex];
    el?.addClass("is-active");
    el?.scrollIntoView({ block: "nearest" });
  }

  private updatePlaceholder(): void {
    if (!this.inputEl) return;
    this.inputEl.placeholder = this.mode === "ask" ? "Ask a question — the best passages from your vault are the answer" : 'Search everything — "phrase", -exclude, tag:x, /regex/, title:…';
  }

  private render(): void {
    const q = this.query.trim();
    this.resultsEl.empty();
    this.rowEls = [];
    this.hits = [];
    this.activeIndex = -1;

    if (q === "") {
      this.lastCounts = {};
      this.syncFilterBtns();
      const st = this.indexer.status();
      this.countEl.setText(st.building ? "Indexing…" : `${st.docCount.toLocaleString()} indexed`);
      const box = this.resultsEl.createDiv({ cls: "kvs-sv-empty" });
      box.createDiv({
        text:
          this.mode === "ask"
            ? "Ask a question in natural language — you'll get the most relevant passages from your notes, rows, and attachments, with sources."
            : "Search across your notes, dashboard rows, and annotations.",
      });
      // Attachment indexing is opt-in, so say so here — where someone would actually want it — rather
      // than leaving the capability buried in settings.
      if (!this.indexer.indexesAttachments) {
        const offer = box.createDiv({ cls: "kvs-sv-offer" });
        offer.createDiv({ text: "Attachments aren't indexed yet. Turn it on to search inside the full text of your PDFs, Word, PowerPoint and EPUB files." });
        const btn = offer.createEl("button", { cls: "mod-cta", text: "Index my attachments" });
        btn.addEventListener("click", () => {
          btn.disabled = true;
          btn.setText("Indexing…");
          void this.indexer.enableAttachments().then(() => this.render());
        });
      }
      return;
    }

    if (this.mode === "ask") {
      void this.renderAnswer(q);
      return;
    }

    // Semantic / hybrid need the vector index; offer to build it if missing.
    if ((this.mode === "semantic" || this.mode === "hybrid") && !this.indexer.hasSemantic) {
      this.lastCounts = {};
      this.syncFilterBtns();
      this.countEl.setText(this.indexer.semanticBuilding ? "Building…" : "Not built");
      const box = this.resultsEl.createDiv({ cls: "kvs-sv-empty" });
      if (this.indexer.semanticBuilding) {
        box.setText("Building the semantic index…");
      } else {
        box.createDiv({ text: "Semantic search needs a one-time offline index of your vault." });
        const btn = box.createEl("button", { cls: "mod-cta kvs-sv-buildbtn", text: "Build semantic index" });
        btn.addEventListener("click", () => {
          btn.disabled = true;
          btn.setText("Building…");
          void this.indexer.buildSemantic().then(() => this.render());
        });
      }
      return;
    }

    let all: SearchResult[];
    if (this.mode === "semantic") all = this.applyFolders(this.indexer.semanticSearch(q, FACET_LIMIT));
    else if (this.mode === "hybrid") all = this.applyFolders(this.hybrid(q));
    else all = this.indexer.search(q, { matchMode: this.matchMode, limit: FACET_LIMIT, fuzzy: this.fuzzy, ...(this.folders.length > 0 ? { folders: this.folders } : {}) });
    const counts: Record<string, number> = {};
    for (const r of all) counts[r.source] = (counts[r.source] ?? 0) + 1;
    this.lastCounts = counts;
    this.syncFilterBtns();

    let hits = all.filter((r) => this.selected.has(r.source));
    if (this.sort === "modified") hits = hits.sort((a, b) => (Number(b.meta?.["mtime"]) || 0) - (Number(a.meta?.["mtime"]) || 0));
    else if (this.sort === "name") hits = hits.sort((a, b) => String(a.location ?? a.id).localeCompare(String(b.location ?? b.id)));
    this.hits = hits.slice(0, 300);
    const capped = all.length >= FACET_LIMIT ? "+" : "";
    this.countEl.setText(`${this.hits.length}${capped} result${this.hits.length === 1 ? "" : "s"}`);

    if (this.hits.length === 0) {
      this.resultsEl.createDiv({ cls: "kvs-sv-empty", text: all.length > 0 ? "No results in the selected scopes — widen the scope chips above." : `No matches for “${q}”.` });
      return;
    }
    const terms = highlightTerms(q);
    if (this.group) this.renderGrouped(this.hits, terms);
    else for (const r of this.hits) this.renderHit(this.resultsEl, r, terms);
  }

  private renderGrouped(hits: SearchResult[], terms: string[]): void {
    const bySource = new Map<string, SearchResult[]>();
    for (const r of hits) {
      const arr = bySource.get(r.source) ?? [];
      arr.push(r);
      bySource.set(r.source, arr);
    }
    for (const src of SOURCES) {
      const group = bySource.get(src.id);
      if (!group || group.length === 0) continue;
      const section = this.resultsEl.createDiv({ cls: "kvs-sv-group" });
      const isCollapsed = this.collapsed.has(src.id);
      const head = section.createDiv({ cls: "kvs-sv-grouphead" });
      const chev = head.createSpan({ cls: "kvs-sv-groupchev" });
      setIcon(chev, isCollapsed ? "chevron-right" : "chevron-down");
      const ic = head.createSpan({ cls: "kvs-sv-groupic" });
      setIcon(ic, src.icon);
      ic.style.color = src.color;
      head.createSpan({ cls: "kvs-sv-groupname", text: src.label });
      head.createSpan({ cls: "kvs-sv-groupcount", text: String(group.length) });
      head.addEventListener("click", () => {
        if (this.collapsed.has(src.id)) this.collapsed.delete(src.id);
        else this.collapsed.add(src.id);
        this.persist();
        this.render();
      });
      if (!isCollapsed) {
        const body = section.createDiv({ cls: "kvs-sv-groupbody" });
        for (const r of group) this.renderHit(body, r, terms);
      }
    }
  }

  /** Post-filter results to the selected folders (used for semantic/hybrid, which don't filter in-engine). */
  private applyFolders(results: SearchResult[]): SearchResult[] {
    if (this.folders.length === 0) return results;
    const prefixes = this.folders.map((f) => (f.endsWith("/") ? f : `${f}/`));
    return results.filter((r) => {
      const p = r.meta?.["path"];
      return typeof p === "string" && prefixes.some((pre) => p.startsWith(pre));
    });
  }

  /** Hybrid ranking now lives in the indexer, where the user's relevance weights are. */
  private hybrid(q: string): SearchResult[] {
    return this.indexer.hybridSearch(q, {
      matchMode: this.matchMode,
      limit: FACET_LIMIT,
      fuzzy: this.fuzzy,
    });
  }

  /** Ask mode: retrieve + rank passages for the question and present them as the answer. */
  private async renderAnswer(q: string): Promise<void> {
    this.lastCounts = {};
    this.syncFilterBtns();
    this.countEl.setText("Thinking…");
    this.resultsEl.empty();
    this.resultsEl.createDiv({ cls: "kvs-sv-empty", text: "Finding the most relevant passages…" });
    let passages = await this.indexer.answer(q, 8);
    if (this.query.trim() !== q || this.mode !== "ask") return; // superseded by newer input
    if (this.folders.length > 0) {
      const prefixes = this.folders.map((f) => (f.endsWith("/") ? f : `${f}/`));
      passages = passages.filter((p) => {
        const path = p.meta?.["path"];
        return typeof path === "string" && prefixes.some((pre) => path.startsWith(pre));
      });
    }
    this.resultsEl.empty();
    this.countEl.setText(`${passages.length} passage${passages.length === 1 ? "" : "s"}`);
    if (passages.length === 0) {
      this.resultsEl.createDiv({ cls: "kvs-sv-empty", text: `No relevant passages found for “${q}”. Try rephrasing, or build the semantic index for better recall.` });
      return;
    }
    this.resultsEl.createDiv({ cls: "kvs-sv-answerhint", text: "Passages that best answer your question — click any to open its source." });
    const terms = questionTerms(q);
    for (const p of passages) this.renderPassage(p, terms);
  }

  private renderPassage(p: AnswerPassage, terms: string[]): void {
    const meta = SOURCE.get(p.source);
    const card = this.resultsEl.createDiv({ cls: "kvs-sv-passage" });
    if (meta) card.style.setProperty("--kvs-src-color", meta.color);
    const body = card.createDiv({ cls: "kvs-sv-passagetext" });
    this.highlightInto(body, p.text, terms);
    const src = card.createDiv({ cls: "kvs-sv-passagesrc" });
    if (meta) {
      setIcon(src.createSpan({ cls: "kvs-sv-badgeic" }), meta.icon);
      src.createSpan({ cls: "kvs-sv-passagesrclabel", text: `${meta.label} · ${p.location ?? p.docId}` });
    } else src.createSpan({ text: p.location ?? p.docId });
    card.addEventListener("click", () => this.jump({ id: p.docId, score: p.score, source: p.source, ...(p.location ? { location: p.location } : {}), ...(p.meta ? { meta: p.meta } : {}) }));
  }

  /** Highlight the given terms (case-insensitive, whole/partial) within text. */
  private highlightInto(el: HTMLElement, text: string, terms: string[]): void {
    el.empty();
    const uniq = [...new Set(terms.filter((t) => t !== ""))];
    if (uniq.length === 0) {
      el.setText(text);
      return;
    }
    const re = new RegExp(`(${uniq.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "giu");
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) el.appendText(text.slice(last, m.index));
      el.createEl("mark", { text: m[0] });
      last = m.index + m[0].length;
      if (m.index === re.lastIndex) re.lastIndex++;
    }
    if (last < text.length) el.appendText(text.slice(last));
  }

  private renderHit(parent: HTMLElement, r: SearchResult, terms: string[]): void {
    const meta = SOURCE.get(r.source);
    const row = parent.createDiv({ cls: this.compact ? "kvs-sv-hit is-compact" : "kvs-sv-hit" });
    row.dataset["source"] = r.source;
    if (meta) row.style.setProperty("--kvs-src-color", meta.color);
    this.rowEls.push(row);
    const head = row.createDiv({ cls: "kvs-sv-hithead" });
    const badge = head.createSpan({ cls: "kvs-sv-badge" });
    if (meta) {
      setIcon(badge.createSpan({ cls: "kvs-sv-badgeic" }), meta.icon);
      badge.createSpan({ text: meta.label });
    } else badge.setText(r.source);
    head.createSpan({ cls: "kvs-sv-loc", text: r.location ?? r.id });
    row.addEventListener("click", () => this.jump(r));
    if (!this.compact) {
      const snip = row.createDiv({ cls: "kvs-sv-snippet" });
      void this.snippetFor(r, terms).then((s) => this.paintSnippet(snip, s));
    }
  }

  private paintSnippet(el: HTMLElement, s: Snippet): void {
    el.empty();
    if (s.text === "") {
      el.remove();
      return;
    }
    if (s.prefix) el.appendText("…");
    let last = 0;
    for (const [a, b] of s.ranges) {
      if (a > last) el.appendText(s.text.slice(last, a));
      el.createEl("mark", { text: s.text.slice(a, b) });
      last = b;
    }
    if (last < s.text.length) el.appendText(s.text.slice(last));
    if (s.suffix) el.appendText("…");
  }

  private async snippetFor(r: SearchResult, terms: string[]): Promise<Snippet> {
    return makeSnippet(await this.textFor(r), terms, 280);
  }

  private async textFor(r: SearchResult): Promise<string> {
    const path = r.meta?.["path"];
    if (typeof path !== "string") return "";
    if (r.source === "note" || r.source === "annotation" || r.source === "row") {
      const f = this.app.vault.getAbstractFileByPath(path);
      if (!(f instanceof TFile)) return "";
      const content = await this.app.vault.cachedRead(f);
      if (r.source === "row") {
        const rows = rowsToDocs(path, content);
        return rows.find((d) => d.id === r.id)?.text ?? rows.map((d) => d.text).join("  •  ");
      }
      const docs = noteToDocs(path, content);
      const exact = docs.find((d) => d.id === r.id);
      if (exact) return exact.text;
      // Stale index or changed heading — fall back so notes still show a snippet before a rebuild.
      if (r.source === "annotation") return docs.find((d) => d.source === "annotation")?.text ?? content;
      const noteText = docs.filter((d) => d.source === "note").map((d) => d.text).join("\n");
      return noteText !== "" ? noteText : content;
    }
    return (await this.indexer.getText(r.id)) ?? "";
  }

  private jump(r: SearchResult): void {
    // Zotero hits are not vault files — open them in Zotero itself via its select protocol. The key is the
    // item for a library hit, or the annotation's parent for an annotation hit.
    if (r.source === "zotero" || r.source === "zotero-annotation") {
      const key = r.source === "zotero" ? r.meta?.["zoteroKey"] : r.meta?.["parentKey"];
      if (typeof key === "string" && key !== "") {
        window.open(`zotero://select/library/items/${key}`, "_blank");
      }
      return;
    }
    const path = r.meta?.["path"];
    if (typeof path !== "string") return;
    const section = String(r.meta?.["section"] ?? "");
    const heading = String(r.meta?.["heading"] ?? "");
    if (r.source === "pdf") {
      const page = /p\.(\d+)/.exec(section)?.[1];
      void this.app.workspace.openLinkText(page ? `${path}#page=${page}` : path, "", false);
      return;
    }
    if (r.source === "note" && heading !== "") {
      void this.app.workspace.openLinkText(`${path}#${heading}`, "", false);
      return;
    }
    if (r.source === "row" && typeof r.meta?.["line"] === "number") {
      const f = this.app.vault.getAbstractFileByPath(path);
      if (f instanceof TFile) void this.app.workspace.getLeaf(false).openFile(f, { eState: { line: r.meta["line"] } });
      return;
    }
    void this.app.workspace.openLinkText(path, "", false);
  }
}
