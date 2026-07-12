import { Modal, Notice, Setting, type App } from "obsidian";
import { parseReferences, type ImportedRef } from "../services/index";

/** Paste a BibTeX or CSV reference export; on import, hands back the parsed references. */
export class ImportReferencesModal extends Modal {
  private text = "";
  private viewName = "Imported references";

  constructor(
    app: App,
    private readonly onImport: (refs: readonly ImportedRef[], viewName: string) => void,
  ) {
    super(app);
  }

  override onOpen(): void {
    this.setTitle("Import references");
    const { contentEl } = this;
    contentEl.addClass("kvs-import-refs");
    contentEl.createEl("p", {
      cls: "kvs-import-sub",
      text: "Paste a BibTeX (.bib) or CSV export — e.g. from Zotero (File → Export). KVS creates a papers note and a Literature view from it.",
    });

    new Setting(contentEl)
      .setName("View name")
      .addText((t) => t.setValue(this.viewName).onChange((v) => (this.viewName = v.trim() || "Imported references")));

    const ta = contentEl.createEl("textarea", { cls: "kvs-import-textarea" });
    ta.setAttr("placeholder", "@article{smith2020, author = {Smith, J.}, title = {…}, year = {2020} }\n\n— or —\n\nKey,Author,Title,Publication Year,DOI");
    const status = contentEl.createDiv({ cls: "kvs-import-status" });
    const refresh = (): void => {
      this.text = ta.value;
      const n = parseReferences(this.text).length;
      status.setText(this.text.trim() === "" ? "" : n > 0 ? `${n} reference(s) detected` : "No references detected — check the format");
    };
    ta.addEventListener("input", refresh);

    new Setting(contentEl)
      .addButton((b) => b.setButtonText("Cancel").onClick(() => this.close()))
      .addButton((b) =>
        b
          .setButtonText("Import")
          .setCta()
          .onClick(() => {
            const refs = parseReferences(this.text);
            if (refs.length === 0) {
              new Notice("No references found in the pasted text.");
              return;
            }
            this.close();
            this.onImport(refs, this.viewName);
          }),
      );
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
