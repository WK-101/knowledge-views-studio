import { App, FuzzySuggestModal, Modal, Notice, TFile, setIcon } from "obsidian";
import { KVS_VIEW_EXTENSION, createProfile, parseViewDoc, type Profile, type ProfileStore } from "../services/index";

/**
 * Search-and-select importer for `.kvsview` files already in the vault. A single-view file imports
 * straight into the stored views. A *multi-view* file (like tabs) is ambiguous — the flat store can
 * hold it as a named group, or it can be opened directly with its tabs intact — so we ask which the
 * user wants rather than guessing.
 */
export class KvsViewFileImportModal extends FuzzySuggestModal<TFile> {
  constructor(
    app: App,
    private readonly store: ProfileStore,
    private readonly onDone?: () => void,
  ) {
    super(app);
    this.setPlaceholder("Search .kvsview files to import…");
  }

  getItems(): TFile[] {
    return this.app.vault
      .getFiles()
      .filter((f) => f.extension === KVS_VIEW_EXTENSION)
      .sort((a, b) => a.basename.localeCompare(b.basename));
  }

  getItemText(file: TFile): string {
    return file.parent && file.parent.path !== "/" ? `${file.basename} ${file.parent.path}` : file.basename;
  }

  override renderSuggestion(item: { item: TFile }, el: HTMLElement): void {
    el.addClass("kvs-view-suggestion");
    el.createDiv({ cls: "kvs-view-suggestion-name", text: item.item.basename });
    const parent = item.item.parent?.path;
    if (parent && parent !== "/") el.createDiv({ cls: "kvs-view-suggestion-path", text: parent });
  }

  onChooseItem(file: TFile): void {
    void (async () => {
      const doc = parseViewDoc(await this.app.vault.read(file));
      if (!doc || doc.views.length === 0) {
        new Notice("That file isn't a valid .kvsview view.");
        return;
      }
      if (doc.views.length === 1) {
        this.importViews(doc.views, undefined);
        return;
      }
      new ImportChoiceModal(this.app, file.basename, doc.views.length, {
        onGroup: () => this.importViews(doc.views, file.basename),
        onOpen: () => void this.app.workspace.getLeaf(false).openFile(file),
      }).open();
    })();
  }

  /** Add the views to the store — grouped under `group` when given — each with a fresh id. */
  private importViews(views: readonly Profile[], group: string | undefined): void {
    for (const view of views) {
      const partial: Partial<Profile> = group ? { ...view, id: undefined, category: group } : { ...view, id: undefined };
      this.store.addProfile(createProfile(partial));
    }
    new Notice(
      views.length === 1
        ? `Imported “${views[0]!.name}” into your views.`
        : `Imported ${views.length} views (grouped as “${group}”).`,
    );
    this.onDone?.();
  }
}

/** Asks how to handle a multi-view file: add it as a group, or open it with its tabs. */
class ImportChoiceModal extends Modal {
  constructor(
    app: App,
    private readonly name: string,
    private readonly count: number,
    private readonly actions: { readonly onGroup: () => void; readonly onOpen: () => void },
  ) {
    super(app);
  }

  override onOpen(): void {
    const { contentEl, titleEl } = this;
    titleEl.setText("Import multi-view file");
    contentEl.addClass("kvs-welcome");
    contentEl.createEl("p", {
      cls: "kvs-welcome-lead",
      text: `“${this.name}” contains ${this.count} views. How would you like to use it?`,
    });

    const card = (icon: string, title: string, desc: string, cta: string, primary: boolean, run: () => void): void => {
      const el = contentEl.createDiv({ cls: "kvs-welcome-card" });
      setIcon(el.createSpan({ cls: "kvs-welcome-card-icon" }), icon);
      const text = el.createDiv({ cls: "kvs-welcome-card-text" });
      text.createDiv({ cls: "kvs-welcome-card-title", text: title });
      text.createDiv({ cls: "kvs-welcome-card-desc", text: desc });
      const button = el.createEl("button", { cls: primary ? "mod-cta" : "", text: cta });
      button.addEventListener("click", () => {
        this.close();
        run();
      });
    };

    card(
      "layers",
      "Import as a group",
      `Add all ${this.count} views to your stored views, grouped under “${this.name}”. Shows as a named set in settings.`,
      "Import as a group",
      true,
      this.actions.onGroup,
    );
    card(
      "layout-dashboard",
      "Open as a multi-tab file",
      "Open the file now with its views as tabs (like a Base). Nothing is added to your stored views.",
      "Open as tabs",
      false,
      this.actions.onOpen,
    );
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
