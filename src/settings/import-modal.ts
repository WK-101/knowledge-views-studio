import { Modal, Notice, Setting, type App } from "obsidian";
import type { ProfileStore } from "../services/index";
import { validateProfileJson } from "./builders";

/** Paste-JSON importer with live validation. */
export class ImportProfileModal extends Modal {
  private json = "";

  constructor(
    app: App,
    private readonly store: ProfileStore,
    private readonly onDone?: () => void,
  ) {
    super(app);
  }

  override onOpen(): void {
    this.titleEl.setText("Import view from JSON");
    const content = this.contentEl;
    content.empty();

    const textarea = content.createEl("textarea", { cls: "kvs-import-textarea" });
    textarea.rows = 12;
    textarea.placeholder = "Paste a view's JSON here…";

    const status = content.createDiv({ cls: "kvs-import-status" });
    const refresh = (): void => {
      this.json = textarea.value;
      const trimmed = this.json.trim();
      if (trimmed === "") {
        status.setText("");
        status.removeClass("kvs-invalid");
        return;
      }
      const result = validateProfileJson(trimmed);
      status.setText(result.ok ? "Looks valid." : result.error);
      status.toggleClass("kvs-invalid", !result.ok);
    };
    textarea.addEventListener("input", refresh);

    new Setting(content)
      .addButton((button) =>
        button
          .setButtonText("Import")
          .setCta()
          .onClick(() => {
            const result = validateProfileJson(this.json.trim());
            if (!result.ok) {
              new Notice(`Cannot import: ${result.error}`);
              return;
            }
            this.store.addProfile(result.profile);
            new Notice("View imported.");
            this.close();
            this.onDone?.();
          }),
      )
      .addButton((button) => button.setButtonText("Cancel").onClick(() => this.close()));
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
