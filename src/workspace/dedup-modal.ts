import { Modal, Setting, type App } from "obsidian";
import type { Row } from "../domain/index";
import { getField } from "../domain/index";
import type { DuplicateGroup } from "../services/index";

export interface DedupEdit {
  readonly provenance: Row["provenance"];
  readonly column: string;
  readonly value: string;
}
export interface DedupResolution {
  /** Rows to delete (the non-kept copies). */
  readonly remove: readonly Row[];
  /** Edits that fill each keeper's empty cells from its duplicates (empty if merge was off). */
  readonly mergeEdits: readonly DedupEdit[];
}

/** Review duplicate-DOI groups and choose, per group, which copy to keep. */
export class DedupModal extends Modal {
  private readonly keepIndex = new Map<number, number>(); // group idx -> kept row idx (default 0 = richest)
  private merge = true;

  constructor(
    app: App,
    private readonly groups: readonly DuplicateGroup[],
    private readonly titleColumn: string | null,
    private readonly keyColumn: string | null,
    private readonly onResolve: (res: DedupResolution) => void,
  ) {
    super(app);
    this.groups.forEach((_g, i) => this.keepIndex.set(i, 0));
  }

  private label(row: Row): string {
    const title = this.titleColumn ? getField(row, this.titleColumn).trim() : "";
    const key = this.keyColumn ? getField(row, this.keyColumn).trim() : "";
    const where = row.file.fileName || row.provenance.filePath;
    const head = title !== "" ? title : key !== "" ? key : "(untitled row)";
    return key !== "" && title !== "" ? `${head} · ${key} · ${where}` : `${head} · ${where}`;
  }

  override onOpen(): void {
    const total = this.groups.reduce((n, g) => n + g.rows.length - 1, 0);
    this.setTitle(`Duplicate DOIs — ${this.groups.length} group${this.groups.length === 1 ? "" : "s"}, ${total} extra cop${total === 1 ? "y" : "ies"}`);
    const { contentEl } = this;
    contentEl.addClass("kvs-dedup");
    contentEl.createEl("p", { cls: "kvs-import-sub", text: "For each DOI, pick the copy to keep. The others will be deleted. The fullest copy is preselected." });

    for (const [gi, group] of this.groups.entries()) {
      const box = contentEl.createDiv({ cls: "kvs-dedup-group" });
      box.createDiv({ cls: "kvs-dedup-doi", text: group.doi });
      group.rows.forEach((row, ri) => {
        const line = box.createEl("label", { cls: "kvs-dedup-row" });
        const radio = line.createEl("input", { cls: "kvs-dedup-radio" });
        radio.type = "radio";
        radio.name = `kvs-dedup-${gi}`;
        radio.checked = ri === 0;
        radio.addEventListener("change", () => {
          if (radio.checked) this.keepIndex.set(gi, ri);
        });
        line.createSpan({ cls: "kvs-dedup-label", text: this.label(row) });
        if (ri === 0) line.createSpan({ cls: "kvs-dedup-suggested", text: "fullest" });
      });
    }

    new Setting(contentEl)
      .setName("Merge before deleting")
      .setDesc("Fill any empty fields in the kept copy from the copies being deleted, so no data is lost.")
      .addToggle((t) => t.setValue(this.merge).onChange((v) => (this.merge = v)));

    new Setting(contentEl)
      .addButton((b) => b.setButtonText("Cancel").onClick(() => this.close()))
      .addButton((b) =>
        b
          .setButtonText(`Delete ${total} duplicate${total === 1 ? "" : "s"}`)
          // setDestructive() needs Obsidian 1.13; we support 1.10, and Obsidian's report lists this
            // only as a recommendation. Revert to setDestructive when the minimum version rises.
            .setWarning()
          .onClick(() => {
            const remove: Row[] = [];
            const mergeEdits: DedupEdit[] = [];
            this.groups.forEach((group, gi) => {
              const keep = this.keepIndex.get(gi) ?? 0;
              const keeper = group.rows[keep]!;
              group.rows.forEach((row, ri) => {
                if (ri !== keep) remove.push(row);
              });
              if (this.merge) {
                // Fill the keeper's empty cells from the fullest duplicate that has a value.
                for (const [column, value] of Object.entries(keeper.cells)) {
                  if (value.trim() !== "") continue;
                  for (let ri = 0; ri < group.rows.length; ri++) {
                    if (ri === keep) continue;
                    const other = (group.rows[ri]!.cells[column] ?? "").trim();
                    if (other !== "") {
                      mergeEdits.push({ provenance: keeper.provenance, column, value: other });
                      break;
                    }
                  }
                }
              }
            });
            this.close();
            this.onResolve({ remove, mergeEdits });
          }),
      );
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
