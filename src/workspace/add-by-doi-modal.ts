import { Modal, Notice, type App } from "obsidian";

/** Extract DOIs from pasted text (one per line, or embedded in URLs). */
export function extractDois(text: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const m of text.matchAll(/10\.\d{4,9}\/[^\s"'<>]+/g)) {
    const doi = m[0].replace(/[.,;]+$/, "");
    const key = doi.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      out.push(doi);
    }
  }
  return out;
}

/** Paste one or more DOIs; on capture, hands back the parsed DOI list. */
export class AddByDoiModal extends Modal {
  private text = "";

  constructor(
    app: App,
    private readonly onCapture: (dois: readonly string[]) => void,
  ) {
    super(app);
  }

  override onOpen(): void {
    this.setTitle("Add papers by DOI");
    const { contentEl } = this;
    contentEl.addClass("kvs-import-refs");
    contentEl.createEl("p", {
      cls: "kvs-import-sub",
      text: "Paste one or more DOIs (one per line, or as doi.org links). KVS fetches each paper's details and adds a row.",
    });
    const ta = contentEl.createEl("textarea", { cls: "kvs-import-textarea" });
    ta.setAttr("placeholder", "10.5555/3295222\nhttps://doi.org/10.18653/v1/N19-1423");
    const status = contentEl.createDiv({ cls: "kvs-import-status" });
    const refresh = (): void => {
      this.text = ta.value;
      const n = extractDois(this.text).length;
      status.setText(this.text.trim() === "" ? "" : `${n} DOI(s) detected`);
    };
    ta.addEventListener("input", refresh);

    const buttons = contentEl.createDiv({ cls: "kvs-import-actions modal-button-container" });
    const cancel = buttons.createEl("button", { text: "Cancel" });
    cancel.addEventListener("click", () => this.close());
    const go = buttons.createEl("button", { cls: "mod-cta", text: "Fetch & add" });
    go.addEventListener("click", () => {
      const dois = extractDois(this.text);
      if (dois.length === 0) {
        new Notice("No DOIs found in the pasted text.");
        return;
      }
      this.close();
      this.onCapture(dois);
    });
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
