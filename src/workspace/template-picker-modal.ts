import { App, Modal, setIcon } from "obsidian";
import { STARTER_TEMPLATES, type StarterTemplate } from "./templates";

/** Lists the starter templates and reports the chosen one. */
export class TemplatePickerModal extends Modal {
  constructor(
    app: App,
    private readonly onPick: (template: StarterTemplate) => void,
    private readonly templates: readonly StarterTemplate[] = STARTER_TEMPLATES,
  ) {
    super(app);
  }

  override onOpen(): void {
    const { contentEl, titleEl } = this;
    titleEl.setText("Start from a template");
    contentEl.addClass("kvs-welcome");
    contentEl.createEl("p", {
      cls: "kvs-welcome-sub",
      text: "Each option creates a note with a filled example table and a matching view you can edit or delete.",
    });

    for (const template of this.templates) {
      const card = contentEl.createDiv({ cls: "kvs-welcome-card" });
      const ic = card.createSpan({ cls: "kvs-welcome-card-icon" });
      setIcon(ic, template.icon);
      const text = card.createDiv({ cls: "kvs-welcome-card-text" });
      text.createDiv({ cls: "kvs-welcome-card-title", text: template.label });
      text.createDiv({ cls: "kvs-welcome-card-desc", text: template.description });
      const button = card.createEl("button", { cls: "mod-cta", text: "Create" });
      button.addEventListener("click", () => {
        this.close();
        this.onPick(template);
      });
    }
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
