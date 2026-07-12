import { Modal, Setting, type App } from "obsidian";

export interface ShardField {
  readonly name: string;
  readonly buckets: number;
  readonly sample: readonly string[];
}

/** Pick a field to shard a library by; shows how many files it would produce. */
export class ShardModal extends Modal {
  private field: string;

  constructor(
    app: App,
    private readonly fields: readonly ShardField[],
    private readonly onShard: (field: string) => void,
  ) {
    super(app);
    this.field = fields[0]?.name ?? "";
  }

  override onOpen(): void {
    this.setTitle("Shard this library");
    const { contentEl } = this;
    contentEl.addClass("kvs-import-refs");
    contentEl.createEl("p", {
      cls: "kvs-import-sub",
      text: "Split the library into several notes by a field (e.g. Year or Status). KVS still shows them as one library — this just keeps each file small and fast at scale.",
    });

    const info = contentEl.createDiv({ cls: "kvs-import-status" });
    const describe = (): void => {
      const f = this.fields.find((x) => x.name === this.field);
      info.setText(f ? `${f.buckets} file(s): ${f.sample.slice(0, 6).join(", ")}${f.sample.length > 6 ? "…" : ""}` : "");
    };

    new Setting(contentEl).setName("Shard by").addDropdown((d) => {
      for (const f of this.fields) d.addOption(f.name, `${f.name} (${f.buckets} files)`);
      d.setValue(this.field).onChange((v) => {
        this.field = v;
        describe();
      });
    });
    describe();

    const buttons = contentEl.createDiv({ cls: "modal-button-container" });
    buttons.createEl("button", { text: "Cancel" }).addEventListener("click", () => this.close());
    const go = buttons.createEl("button", { cls: "mod-cta", text: "Shard" });
    go.addEventListener("click", () => {
      this.close();
      this.onShard(this.field);
    });
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
