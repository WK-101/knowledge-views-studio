import { Modal, Notice, Setting, TFile, normalizePath, type App } from "obsidian";
import {
  buildMarkdownTable,
  detectImportFormat,
  normalizeTable,
  parseCsv,
  parseMarkdownTable,
  parseXlsx,
  readEmbeddedView,
  type EmbeddedView,
  type ExportTable,
  type ImportFormat,
} from "../services/index";

/**
 * Import a CSV, Markdown, or Excel file into a new note containing a Markdown
 * table — which KVS can then aggregate like any other source. Self-contained:
 * parses on selection, previews, and writes the note on confirm.
 */
export class ImportModal extends Modal {
  private file: File | null = null;
  private rawText = "";
  private table: ExportTable | null = null;
  private error = "";
  private format: ImportFormat = "csv";
  private noteName = "Imported table";
  private folder = "";
  private heading = "";
  private readonly fileInput: HTMLInputElement;

  constructor(
    app: App,
    private readonly onImported?: (config: Partial<EmbeddedView>, notePath: string) => void,
  ) {
    super(app);
    this.fileInput = createEl("input", { attr: { type: "file", accept: ".csv,.md,.markdown,.xlsx" } });
    this.fileInput.addEventListener("change", () => {
      const picked = this.fileInput.files?.[0] ?? null;
      if (picked) void this.loadFile(picked);
    });
  }

  override onOpen(): void {
    this.setTitle("Import table to a new note");
    this.render();
  }

  override onClose(): void {
    this.contentEl.empty();
  }

  private async loadFile(file: File): Promise<void> {
    this.file = file;
    this.format = detectImportFormat(file.name);
    this.noteName = file.name.replace(/\.[^.]+$/, "") || "Imported table";
    await this.reparse();
  }

  private async reparse(): Promise<void> {
    this.error = "";
    this.table = null;
    if (!this.file) return;
    try {
      if (this.format === "xlsx") {
        this.table = parseXlsx(new Uint8Array(await this.file.arrayBuffer()));
      } else if (this.format === "markdown") {
        this.rawText = await this.file.text();
        this.table = parseMarkdownTable(this.rawText);
      } else {
        this.table = parseCsv(await this.file.text());
      }
      if (this.table.headers.length === 0 && this.table.rows.length === 0) {
        this.error = "No table data found in this file.";
        this.table = null;
      }
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      this.table = null;
    }
    this.render();
  }

  private render(): void {
    const { contentEl } = this;
    contentEl.empty();
    contentEl.addClass("kvs-import-modal");

    new Setting(contentEl)
      .setName("File")
      .setDesc(this.file ? this.file.name : "Choose a .csv, .md, or .xlsx file.")
      .addButton((b) => b.setButtonText(this.file ? "Change file" : "Choose file").onClick(() => this.fileInput.click()));

    if (this.file) {
      new Setting(contentEl).setName("Format").addDropdown((d) => {
        d.addOption("csv", "CSV");
        d.addOption("markdown", "Markdown table");
        d.addOption("xlsx", "Excel (.xlsx)");
        d.setValue(this.format).onChange((v) => {
          this.format = v as ImportFormat;
          void this.reparse();
        });
      });
    }

    if (this.error) {
      contentEl.createDiv({ cls: "kvs-import-error", text: this.error });
    }

    if (this.table) {
      this.renderPreview(contentEl, normalizeTable(this.table));

      new Setting(contentEl)
        .setName("Note name")
        .addText((t) => t.setValue(this.noteName).onChange((v) => (this.noteName = v)));
      new Setting(contentEl)
        .setName("Folder")
        .setDesc("Optional vault folder; created if missing. Leave blank for the vault root.")
        .addText((t) => t.setPlaceholder("e.g. Imports").setValue(this.folder).onChange((v) => (this.folder = v)));
      new Setting(contentEl)
        .setName("Heading")
        .setDesc("Optional title placed above the table.")
        .addText((t) => t.setValue(this.heading).onChange((v) => (this.heading = v)));
    }

    new Setting(contentEl)
      .addButton((b) =>
        b
          .setButtonText("Import")
          .setCta()
          .setDisabled(!this.table)
          .onClick(() => void this.doImport()),
      )
      .addButton((b) => b.setButtonText("Cancel").onClick(() => this.close()));
  }

  private renderPreview(parent: HTMLElement, table: ExportTable): void {
    const wrap = parent.createDiv({ cls: "kvs-import-preview" });
    wrap.createDiv({
      cls: "kvs-import-preview-caption",
      text: `${table.headers.length} column(s) × ${table.rows.length} row(s)`,
    });
    const scroll = wrap.createDiv({ cls: "kvs-import-preview-scroll" });
    const tableEl = scroll.createEl("table", { cls: "kvs-import-preview-table" });
    const head = tableEl.createEl("thead").createEl("tr");
    for (const h of table.headers) head.createEl("th", { text: h });
    const body = tableEl.createEl("tbody");
    for (const row of table.rows.slice(0, 5)) {
      const tr = body.createEl("tr");
      for (const cell of row) tr.createEl("td", { text: cell });
    }
    if (table.rows.length > 5) {
      wrap.createDiv({ cls: "kvs-import-preview-more", text: `…and ${table.rows.length - 5} more row(s)` });
    }
  }

  private async doImport(): Promise<void> {
    if (!this.table) return;
    const table = normalizeTable(this.table);
    const md = buildMarkdownTable(table);
    const heading = this.heading.trim();
    const config = this.format === "markdown" ? readEmbeddedView(this.rawText) : null;
    const markerLine = config ? (this.rawText.match(/<!--\s*kvs:view\s+[A-Za-z0-9+/=]+\s*-->/)?.[0] ?? "") : "";
    const parts: string[] = [];
    if (markerLine) parts.push(markerLine);
    if (heading) parts.push(`# ${heading}`);
    parts.push(md);
    const body = `${parts.join("\n\n")}\n`;

    const folder = this.folder.trim().replace(/^\/+|\/+$/g, "");
    if (folder && !this.app.vault.getAbstractFileByPath(folder)) {
      try {
        await this.app.vault.createFolder(folder);
      } catch {
        // already exists or invalid; the create below will surface a real error
      }
    }

    const base = this.sanitize(this.noteName.trim() || "Imported table");
    const path = await this.uniquePath(normalizePath(folder ? `${folder}/${base}.md` : `${base}.md`));

    try {
      const file = await this.app.vault.create(path, body);
      new Notice(`Imported ${table.rows.length} row(s) to ${path}`);
      if (config && this.onImported) this.onImported(config, path);
      this.close();
      if (file instanceof TFile) void this.app.workspace.getLeaf(true).openFile(file);
    } catch (e) {
      new Notice(`Import failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  private sanitize(name: string): string {
    return name.replace(/[\\/:*?"<>|]/g, "-").replace(/\s+/g, " ").trim() || "Imported table";
  }

  private async uniquePath(path: string): Promise<string> {
    const dot = path.lastIndexOf(".");
    const base = dot > 0 ? path.slice(0, dot) : path;
    const ext = dot > 0 ? path.slice(dot) : "";
    let candidate = path;
    let i = 1;
    while (this.app.vault.getAbstractFileByPath(candidate)) {
      candidate = `${base} ${i}${ext}`;
      i += 1;
    }
    return candidate;
  }
}
