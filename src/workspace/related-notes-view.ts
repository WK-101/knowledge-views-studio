import { ItemView, TFile, setIcon, setTooltip, type App, type WorkspaceLeaf } from "obsidian";
import type { SearchResult } from "../services/index";
import type { SearchIndexer } from "./search-indexer";

export const RELATED_VIEW_TYPE = "kvs-related-view";

/** Open (or focus) the Related notes panel in the right sidebar. */
export async function openRelatedView(app: App): Promise<void> {
  let leaf = app.workspace.getLeavesOfType(RELATED_VIEW_TYPE)[0];
  if (!leaf) {
    const right = app.workspace.getRightLeaf(false);
    if (!right) return;
    leaf = right;
    await leaf.setViewState({ type: RELATED_VIEW_TYPE, active: true });
  }
  await app.workspace.revealLeaf(leaf);
}

/**
 * Notes related to the one you're reading — by meaning, not by links.
 *
 * Folders and tags make you decide the structure up front. This lets it emerge: it surfaces the notes
 * that are *about* the same things as the one in front of you, including ones you'd forgotten and ones
 * you never thought to link.
 */
export class RelatedNotesView extends ItemView {
  private listEl!: HTMLElement;
  private currentPath: string | null = null;

  constructor(
    leaf: WorkspaceLeaf,
    private readonly indexer: SearchIndexer,
  ) {
    super(leaf);
  }

  override getViewType(): string {
    return RELATED_VIEW_TYPE;
  }
  override getDisplayText(): string {
    return "Related notes";
  }
  override getIcon(): string {
    return "git-compare-arrows";
  }

  override async onOpen(): Promise<void> {
    const root = this.contentEl;
    root.empty();
    root.addClass("kvs-related");
    this.listEl = root.createDiv({ cls: "kvs-related-list" });

    // Follow the active note.
    this.registerEvent(this.app.workspace.on("active-leaf-change", () => this.refresh()));
    this.registerEvent(this.app.workspace.on("file-open", () => this.refresh()));
    this.refresh();
    return Promise.resolve();
  }

  private refresh(): void {
    const file = this.app.workspace.getActiveFile();
    const path = file?.extension === "md" ? file.path : null;
    if (path === this.currentPath) return;
    this.currentPath = path;
    this.render();
  }

  private render(): void {
    const el = this.listEl;
    el.empty();

    if (!this.currentPath) {
      this.empty(el, "file-text", "Open a note", "Related notes will appear here.");
      return;
    }
    if (!this.indexer.hasSemantic) {
      const box = this.empty(el, "sparkles", "Semantic index not built", "Related notes are found by meaning, which needs the semantic index. It builds on your device — nothing is downloaded and nothing leaves your vault.");
      const btn = box.createEl("button", { cls: "mod-cta", text: "Build it now" });
      btn.addEventListener("click", () => {
        btn.disabled = true;
        btn.setText("Building…");
        void this.indexer.buildSemantic().then(() => this.render());
      });
      return;
    }

    const related = this.indexer.relatedTo(this.currentPath, 12);
    if (related.length === 0) {
      this.empty(el, "search-x", "Nothing related found", "This note doesn't yet share much ground with the rest of your vault — or the index is out of date.");
      return;
    }

    const head = el.createDiv({ cls: "kvs-related-head" });
    head.createSpan({ text: `${related.length} related note${related.length === 1 ? "" : "s"}` });
    const rebuild = head.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    setIcon(rebuild, "refresh-cw");
    setTooltip(rebuild, "Rebuild the semantic index");
    rebuild.addEventListener("click", () => {
      void this.indexer.buildSemantic().then(() => this.render());
    });

    for (const hit of related) this.renderHit(el, hit);
  }

  private renderHit(parent: HTMLElement, hit: SearchResult): void {
    const path = String(hit.meta?.["path"] ?? "");
    const row = parent.createDiv({ cls: "kvs-related-item" });

    const title = row.createDiv({ cls: "kvs-related-title" });
    title.setText(String(hit.meta?.["title"] ?? hit.location ?? path));

    // A quiet strength bar, rather than a number nobody can interpret.
    const bar = row.createDiv({ cls: "kvs-related-bar" });
    const fill = bar.createDiv({ cls: "kvs-related-fill" });
    fill.setCssProps({ "--kvs-rel": `${Math.round(Math.min(1, hit.score) * 100)}%` });
    setTooltip(row, `${Math.round(Math.min(1, hit.score) * 100)}% similar · ${path}`);

    row.addEventListener("click", () => {
      void this.app.workspace.openLinkText(path, "", false);
    });

    // Link it into the current note, which is the action people actually want next.
    const link = row.createEl("button", { cls: "clickable-icon kvs-related-link" });
    setIcon(link, "link");
    setTooltip(link, "Insert a link to this note at the cursor");
    link.addEventListener("click", (e) => {
      e.stopPropagation();
      this.insertLink(path);
    });
  }

  private insertLink(path: string): void {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) return;
    const active = this.app.workspace.getActiveFile();
    const editor = this.app.workspace.activeEditor?.editor;
    if (!editor || !active) return;
    const link = this.app.fileManager.generateMarkdownLink(file, active.path);
    editor.replaceSelection(link);
  }

  private empty(parent: HTMLElement, icon: string, title: string, desc: string): HTMLElement {
    const box = parent.createDiv({ cls: "kvs-related-empty" });
    setIcon(box.createDiv({ cls: "kvs-related-empty-ic" }), icon);
    box.createDiv({ cls: "kvs-related-empty-title", text: title });
    box.createDiv({ cls: "kvs-related-empty-desc", text: desc });
    return box;
  }
}
