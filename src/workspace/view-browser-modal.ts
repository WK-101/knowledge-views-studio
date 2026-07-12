import { App, FuzzySuggestModal, TFile } from "obsidian";
import { KVS_VIEW_EXTENSION } from "../services/index";

/**
 * Browse every saved view file (`.kvsview`) in the vault — a searchable list to keep track of them
 * and jump straight to any one. Opening a result opens the view in the dashboard.
 */
export class ViewBrowserModal extends FuzzySuggestModal<TFile> {
  constructor(app: App) {
    super(app);
    this.setPlaceholder("Search saved views…");
    this.setInstructions([
      { command: "↵", purpose: "open view" },
      { command: "esc", purpose: "dismiss" },
    ]);
  }

  getItems(): TFile[] {
    return this.app.vault
      .getFiles()
      .filter((f) => f.extension === KVS_VIEW_EXTENSION)
      .sort((a, b) => a.basename.localeCompare(b.basename));
  }

  getItemText(file: TFile): string {
    // Include the folder so search matches by location too.
    return file.parent && file.parent.path !== "/" ? `${file.basename} ${file.parent.path}` : file.basename;
  }

  override renderSuggestion(item: { item: TFile }, el: HTMLElement): void {
    el.addClass("kvs-view-suggestion");
    el.createDiv({ cls: "kvs-view-suggestion-name", text: item.item.basename });
    const parent = item.item.parent?.path;
    if (parent && parent !== "/") el.createDiv({ cls: "kvs-view-suggestion-path", text: parent });
  }

  onChooseItem(file: TFile): void {
    void this.app.workspace.getLeaf(false).openFile(file);
  }
}
