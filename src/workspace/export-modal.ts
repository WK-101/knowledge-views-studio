import { Modal, Setting, type App, type DropdownComponent } from "obsidian";
import {
  buildCsv,
  buildMarkdownTable,
  buildPrintHtml,
  DEFAULT_CSV_OPTIONS,
  DEFAULT_EXPORT_FONT,
  DEFAULT_MD_OPTIONS,
  DEFAULT_XLSX_OPTIONS,
  FONT_PRESETS,
  pdfPageDimensions,
  resolvePdfLayout,
  type CsvOptions,
  type ExportTable,
  type MarkdownExportOptions,
  type PdfOptions,
  type XlsxOptions,
  buildBibtex,
  buildBibliography,
  rowToReference,
  type BibliographyStyle,
  type ReferenceColumn,
  type Reference,
} from "../services/index";

export type ExportFormat = "csv" | "markdown" | "xlsx" | "pdf" | "docx" | "bibtex" | "bibliography";

export interface ExportRequest {
  format: ExportFormat;
  columns: "visible" | "all";
  includeMetadata: boolean;
  embedView: boolean;
  rowScope: "all" | "page";
  destination: "download" | "vault";
  fileName: string;
  bibliographyStyle: BibliographyStyle;
  pdf: PdfOptions;
  csv: CsvOptions;
  markdown: MarkdownExportOptions;
  xlsx: XlsxOptions;
}

export interface ExportModalOptions {
  readonly defaultName: string;
  readonly paginated: boolean;
  /** A sample table (headers + a page of rows) used to render the live preview. */
  readonly previewTable?: ExportTable;
  /** Whether the academic kit is on for this view (unlocks BibTeX + bibliography formats). */
  readonly academic?: boolean;
  /** Column name -> type id, used to map rows to references for BibTeX/bibliography. */
  readonly columnTypes?: Readonly<Record<string, string>>;
  /** Total rows that will actually be exported (the preview shows a sample of these). */
  readonly totalRows?: number;
  readonly onSubmit: (request: ExportRequest) => void;
}

const FORMAT_LABELS: Record<ExportFormat, string> = {
  csv: "CSV",
  markdown: "Markdown",
  xlsx: "Excel",
  pdf: "PDF",
  docx: "Word",
  bibtex: "BibTeX",
  bibliography: "Bibliography",
};

/** Collects export settings with a large, format-accurate live preview beside grouped options. */
export class ExportOptionsModal extends Modal {
  private readonly state: ExportRequest;
  private previewHost: HTMLElement | null = null;
  private settingsHost: HTMLElement | null = null;

  constructor(
    app: App,
    private readonly opts: ExportModalOptions,
  ) {
    super(app);
    this.state = {
      format: "pdf",
      columns: "visible",
      includeMetadata: false,
      embedView: true,
      rowScope: "all",
      destination: "download",
      fileName: opts.defaultName || "export",
      bibliographyStyle: "apa",
      pdf: {
        orientation: "auto",
        pageSize: "auto",
        margin: "normal",
        fontSizePt: 10,
        fontFamily: DEFAULT_EXPORT_FONT,
        title: opts.defaultName || "",
        subtitle: "",
        accent: "#4c6ef5",
        zebra: true,
        includeDate: true,
        pageNumbers: true,
        repeatHeader: true,
        fitToWidth: true,
        rowNumbers: false,
      },
      csv: { ...DEFAULT_CSV_OPTIONS },
      markdown: { ...DEFAULT_MD_OPTIONS, title: opts.defaultName || "" },
      xlsx: { ...DEFAULT_XLSX_OPTIONS, sheetName: opts.defaultName || "Export" },
    };
  }

  override onOpen(): void {
    this.setTitle("Export");
    this.modalEl.addClass("kvs-export-modal-el");
    const { contentEl } = this;
    contentEl.empty();
    contentEl.addClass("kvs-export-modal");

    // Format picker — a segmented control across the top.
    const head = contentEl.createDiv({ cls: "kvs-export-head" });
    head.createDiv({ cls: "kvs-export-head-label", text: "Format" });
    const seg = head.createDiv({ cls: "kvs-export-formats" });
    const formats: ExportFormat[] = ["pdf", "docx", "xlsx", "csv", "markdown", ...(this.opts.academic ? (["bibtex", "bibliography"] as ExportFormat[]) : [])];
    formats.forEach((fmt) => {
      const btn = seg.createEl("button", { cls: "kvs-export-fmt", text: FORMAT_LABELS[fmt] });
      if (fmt === this.state.format) btn.addClass("is-active");
      btn.addEventListener("click", () => {
        this.state.format = fmt;
        this.render();
      });
    });

    const body = contentEl.createDiv({ cls: "kvs-export-body" });
    this.previewHost = body.createDiv({ cls: "kvs-export-preview" });
    this.settingsHost = body.createDiv({ cls: "kvs-export-settings" });

    const footer = contentEl.createDiv({ cls: "kvs-export-footer" });
    new Setting(footer)
      .addButton((b) => b.setButtonText("Cancel").onClick(() => this.close()))
      .addButton((b) =>
        b
          .setButtonText("Export")
          .setCta()
          .onClick(() => {
            this.close();
            this.opts.onSubmit(this.state);
          }),
      );

    this.render();
  }

  override onClose(): void {
    this.contentEl.empty();
  }

  private render(): void {
    // Reflect the active format on the segmented control.
    this.contentEl.findAll(".kvs-export-fmt").forEach((el, i) => {
      const fmt = (["pdf", "docx", "xlsx", "csv", "markdown", "bibtex", "bibliography"] as ExportFormat[])[i];
      el.toggleClass("is-active", fmt === this.state.format);
    });
    this.renderSettings();
    this.refreshPreview();
  }

  // ---------- settings ----------
  private section(host: HTMLElement, title: string): HTMLElement {
    const wrap = host.createDiv({ cls: "kvs-export-section" });
    wrap.createDiv({ cls: "kvs-export-section-title", text: title });
    return wrap.createDiv({ cls: "kvs-export-section-body" });
  }

  private renderSettings(): void {
    const host = this.settingsHost;
    if (!host) return;
    host.empty();
    const fmt = this.state.format;

    const content = this.section(host, "Content");
    new Setting(content).setName("Columns").addDropdown((d) => {
      d.addOption("visible", "Visible columns");
      d.addOption("all", "All columns");
      d.setValue(this.state.columns).onChange((v) => (this.state.columns = v as "visible" | "all"));
    });
    new Setting(content)
      .setName("Source metadata")
      .setDesc("Append Source path, Table, and Row columns.")
      .addToggle((t) => t.setValue(this.state.includeMetadata).onChange((v) => (this.state.includeMetadata = v)));
    if (this.opts.paginated) {
      new Setting(content).setName("Rows").addDropdown((d) => {
        d.addOption("all", "All rows");
        d.addOption("page", "Current page only");
        d.setValue(this.state.rowScope).onChange((v) => (this.state.rowScope = v as "all" | "page"));
      });
    }

    if (fmt === "pdf" || fmt === "docx") this.renderPagedSettings(host);
    else if (fmt === "csv") this.renderCsvSettings(host);
    else if (fmt === "markdown") this.renderMarkdownSettings(host);
    else if (fmt === "xlsx") this.renderXlsxSettings(host);
    else if (fmt === "bibliography") this.renderBibliographySettings(host);

    if (fmt !== "pdf") {
      const out = this.section(host, "Output");
      new Setting(out).setName("Destination").addDropdown((d) => {
        d.addOption("download", "Download file");
        d.addOption("vault", "Save to vault");
        d.setValue(this.state.destination).onChange((v) => (this.state.destination = v as "download" | "vault"));
      });
      new Setting(out).setName("File name").addText((t) =>
        t
          .setPlaceholder("export")
          .setValue(this.state.fileName)
          .onChange((v) => (this.state.fileName = v.trim() || "export")),
      );
    }
  }

  private renderCsvSettings(host: HTMLElement): void {
    const set = (patch: Partial<CsvOptions>): void => {
      this.state.csv = { ...this.state.csv, ...patch };
      this.refreshPreview();
    };
    const csv = (): CsvOptions => this.state.csv;
    const body = this.section(host, "CSV options");
    new Setting(body).setName("Delimiter").addDropdown((d) => {
      d.addOption(",", "Comma  ,");
      d.addOption(";", "Semicolon  ;");
      d.addOption("\t", "Tab");
      d.addOption("|", "Pipe  |");
      d.setValue(csv().delimiter).onChange((v) => set({ delimiter: v as CsvOptions["delimiter"] }));
    });
    new Setting(body).setName("Header row").addToggle((t) => t.setValue(csv().includeHeader).onChange((v) => set({ includeHeader: v })));
    new Setting(body)
      .setName("Quote every field")
      .setDesc("Off quotes only fields that need it.")
      .addToggle((t) => t.setValue(csv().quoteAll).onChange((v) => set({ quoteAll: v })));
    new Setting(body).setName("Line endings").addDropdown((d) => {
      d.addOption("crlf", "Windows (CRLF)");
      d.addOption("lf", "Unix (LF)");
      d.setValue(csv().newline).onChange((v) => set({ newline: v as CsvOptions["newline"] }));
    });
    new Setting(body)
      .setName("Byte-order mark (BOM)")
      .setDesc("Helps Excel open UTF-8 files with the right encoding.")
      .addToggle((t) => t.setValue(csv().bom).onChange((v) => set({ bom: v })));
  }

  private renderMarkdownSettings(host: HTMLElement): void {
    const set = (patch: Partial<MarkdownExportOptions>): void => {
      this.state.markdown = { ...this.state.markdown, ...patch };
      this.refreshPreview();
    };
    const md = (): MarkdownExportOptions => this.state.markdown;
    const body = this.section(host, "Markdown options");
    new Setting(body).setName("Column alignment").addDropdown((d) => {
      d.addOption("none", "Default");
      d.addOption("left", "Left");
      d.addOption("center", "Center");
      d.addOption("right", "Right");
      d.setValue(md().align).onChange((v) => set({ align: v as MarkdownExportOptions["align"] }));
    });
    new Setting(body)
      .setName("Add a title heading")
      .addToggle((t) => t.setValue(md().includeTitle).onChange((v) => set({ includeTitle: v })));
    if (md().includeTitle) {
      new Setting(body).setName("Title").addText((t) => t.setValue(md().title).onChange((v) => set({ title: v })));
    }
    new Setting(body)
      .setName("Embed view settings")
      .setDesc("Store this view's columns, types and layout so re-importing the file restores the view.")
      .addToggle((t) => t.setValue(this.state.embedView).onChange((v) => (this.state.embedView = v)));
  }

  private renderXlsxSettings(host: HTMLElement): void {
    const set = (patch: Partial<XlsxOptions>): void => {
      this.state.xlsx = { ...this.state.xlsx, ...patch };
      this.refreshPreview();
    };
    const xl = (): XlsxOptions => this.state.xlsx;
    const body = this.section(host, "Excel options");
    new Setting(body).setName("Sheet name").addText((t) =>
      t
        .setPlaceholder("Export")
        .setValue(xl().sheetName)
        .onChange((v) => set({ sheetName: v })),
    );
    new Setting(body).setName("Bold header row").addToggle((t) => t.setValue(xl().boldHeader).onChange((v) => set({ boldHeader: v })));
    new Setting(body)
      .setName("Freeze header row")
      .setDesc("Keep the header visible while scrolling.")
      .addToggle((t) => t.setValue(xl().freezeHeader).onChange((v) => set({ freezeHeader: v })));
    new Setting(body)
      .setName("Add auto-filter")
      .setDesc("Filter/sort dropdowns on the header row.")
      .addToggle((t) => t.setValue(xl().autoFilter).onChange((v) => set({ autoFilter: v })));
    new Setting(body).setName("Zebra striping").addToggle((t) => t.setValue(xl().zebra).onChange((v) => set({ zebra: v })));
  }

  private renderPagedSettings(host: HTMLElement): void {
    const set = (patch: Partial<PdfOptions>): void => {
      this.state.pdf = { ...this.state.pdf, ...patch };
      this.refreshPreview();
    };
    const pdf = (): PdfOptions => this.state.pdf;
    const isDocx = this.state.format === "docx";

    const page = this.section(host, "Page");
    new Setting(page).setName("Page size").addDropdown((d) => {
      d.addOption("auto", "Auto (fit columns)");
      for (const size of ["A4", "Letter", "Legal", "A3", "Tabloid", "B4", "B5", "A5", "A6", "Executive"]) d.addOption(size, size);
      d.setValue(pdf().pageSize).onChange((v) => set({ pageSize: v as PdfOptions["pageSize"] }));
    });
    new Setting(page).setName("Orientation").addDropdown((d) => {
      d.addOption("auto", "Auto");
      d.addOption("portrait", "Portrait");
      d.addOption("landscape", "Landscape");
      d.setValue(pdf().orientation).onChange((v) => set({ orientation: v as PdfOptions["orientation"] }));
    });
    new Setting(page).setName("Margins").addDropdown((d) => {
      d.addOption("normal", "Normal");
      d.addOption("narrow", "Narrow");
      d.addOption("wide", "Wide");
      d.setValue(pdf().margin).onChange((v) => set({ margin: v as PdfOptions["margin"] }));
    });

    const header = this.section(host, "Header");
    new Setting(header).setName("Title").addText((t) => t.setValue(pdf().title).onChange((v) => set({ title: v })));
    new Setting(header).setName("Subtitle").addText((t) => t.setValue(pdf().subtitle).onChange((v) => set({ subtitle: v })));
    new Setting(header)
      .setName("Accent color")
      .setDesc("Header tint and the rule under the title.")
      .addColorPicker((c) => c.setValue(pdf().accent).onChange((v) => set({ accent: v })));
    new Setting(header).setName("Show date").addToggle((t) => t.setValue(pdf().includeDate).onChange((v) => set({ includeDate: v })));
    if (!isDocx) {
      new Setting(header)
        .setName("Page numbers")
        .setDesc("Number each page in the footer.")
        .addToggle((t) => t.setValue(pdf().pageNumbers).onChange((v) => set({ pageNumbers: v })));
    }

    const style = this.section(host, "Table style");
    new Setting(style)
      .setName("Font")
      .setDesc("Typeface and base size in points.")
      .addDropdown((d) => {
        for (const preset of FONT_PRESETS) d.addOption(preset.value, preset.label);
        const current = pdf().fontFamily;
        if (!FONT_PRESETS.some((p) => p.value === current)) d.addOption(current, current);
        d.setValue(current).onChange((v) => set({ fontFamily: v }));
        void this.loadInstalledFonts(d);
      })
      .addText((t) => {
        t.setPlaceholder("10")
          .setValue(String(pdf().fontSizePt))
          .onChange((v) => {
            const n = Number(v);
            if (Number.isFinite(n) && n >= 5 && n <= 24) set({ fontSizePt: Math.round(n) });
          });
        t.inputEl.type = "number";
        t.inputEl.addClass("kvs-export-num");
      });
    new Setting(style).setName("Zebra striping").addToggle((t) => t.setValue(pdf().zebra).onChange((v) => set({ zebra: v })));
    new Setting(style).setName("Row numbers").addToggle((t) => t.setValue(pdf().rowNumbers).onChange((v) => set({ rowNumbers: v })));
    if (!isDocx) {
      new Setting(style)
        .setName("Repeat header each page")
        .addToggle((t) => t.setValue(pdf().repeatHeader).onChange((v) => set({ repeatHeader: v })));
      new Setting(style)
        .setName("Fit table to page width")
        .setDesc("Off keeps natural column widths for wide tables.")
        .addToggle((t) => t.setValue(pdf().fitToWidth).onChange((v) => set({ fitToWidth: v })));
    }
  }

  // ---------- preview ----------
  private refreshPreview(): void {
    const host = this.previewHost;
    if (!host) return;
    host.empty();
    const table = this.opts.previewTable;
    if (!table || (table.headers.length === 0 && table.rows.length === 0)) {
      host.createDiv({ cls: "kvs-export-preview-empty", text: "Preview appears here once there are rows." });
      return;
    }
    const stage = host.createDiv({ cls: "kvs-export-stage" });
    const fmt = this.state.format;
    let caption = "";
    if (fmt === "pdf" || fmt === "docx") caption = this.renderPagedPreview(stage, table);
    else if (fmt === "csv") caption = this.renderTextPreview(stage, buildCsv(table, this.state.csv));
    else if (fmt === "markdown") caption = this.renderTextPreview(stage, buildMarkdownTable(table, this.state.markdown));
    else if (fmt === "bibtex") caption = this.renderTextPreview(stage, buildBibtex(this.references(table))) && "BibTeX entries";
    else if (fmt === "bibliography") caption = this.renderTextPreview(stage, buildBibliography(this.references(table), this.state.bibliographyStyle)) && `${this.state.bibliographyStyle.toUpperCase()} references`;
    else caption = this.renderGridPreview(stage, table);

    const total = this.opts.totalRows ?? table.rows.length;
    const note = total > table.rows.length ? ` · showing ${table.rows.length} of ${total} rows` : "";
    host.createDiv({ cls: "kvs-export-cap", text: `${caption}${note}` });
  }

  private renderPagedPreview(stage: HTMLElement, table: ExportTable): string {
    const { pageSize, orientation } = resolvePdfLayout(table, this.state.pdf);
    const [w, h] = pdfPageDimensions(pageSize, orientation);
    const frame = stage.createEl("iframe", { cls: "kvs-export-page" });
    frame.style.aspectRatio = `${w} / ${h}`;
    frame.setAttribute("sandbox", "allow-same-origin");
    frame.srcdoc = buildPrintHtml(table, this.state.pdf, "preview");
    return this.state.format === "docx"
      ? `Word document · ${pageSize} · ${orientation} · styling preview`
      : `PDF · ${pageSize} · ${orientation}`;
  }

  private renderTextPreview(stage: HTMLElement, text: string): string {
    const MAX = 40;
    const lines = text.split(/\r?\n/);
    const shown = lines.slice(0, MAX).join("\n") + (lines.length > MAX ? `\n… ${lines.length - MAX} more line(s)` : "");
    stage.createEl("pre", { cls: "kvs-export-text", text: shown });
    return this.state.format === "csv" ? "Delimited text" : "Markdown source";
  }

  private renderGridPreview(stage: HTMLElement, table: ExportTable): string {
    const xl = this.state.xlsx;
    const grid = stage.createEl("table", { cls: "kvs-export-grid" });
    if (xl.freezeHeader) grid.addClass("is-frozen");
    const headRow = grid.createEl("thead").createEl("tr");
    headRow.createEl("th", { cls: "kvs-export-grid-corner" });
    table.headers.forEach((headerText) => {
      const th = headRow.createEl("th");
      if (xl.boldHeader) th.addClass("is-bold");
      th.createSpan({ text: headerText });
      if (xl.autoFilter) th.createSpan({ cls: "kvs-export-grid-filter", text: "▾" });
    });
    const tbody = grid.createEl("tbody");
    table.rows.forEach((row, i) => {
      const tr = tbody.createEl("tr");
      if (xl.zebra && i % 2 === 1) tr.addClass("is-zebra");
      tr.createEl("td", { cls: "kvs-export-grid-num", text: String(i + 1) });
      table.headers.forEach((_h, ci) => tr.createEl("td", { text: row[ci] ?? "" }));
    });
    return `Excel worksheet “${xl.sheetName.trim() || "Export"}”`;
  }

  private references(table: ExportTable): Reference[] {
    const cols: ReferenceColumn[] = table.headers.map((name) => ({ name, typeId: this.opts.columnTypes?.[name] ?? "text" }));
    return table.rows.map((cells) => {
      const record: Record<string, string> = {};
      table.headers.forEach((h, i) => (record[h] = cells[i] ?? ""));
      return rowToReference(cols, record);
    });
  }

  private renderBibliographySettings(host: HTMLElement): void {
    const body = this.section(host, "Bibliography");
    new Setting(body).setName("Style").addDropdown((d) => {
      d.addOption("apa", "APA (7th)");
      d.addOption("mla", "MLA");
      d.setValue(this.state.bibliographyStyle).onChange((v) => {
        this.state.bibliographyStyle = v as BibliographyStyle;
        this.refreshPreview();
      });
    });
  }

  /** Append locally-installed fonts to the font dropdown (Local Font Access API, when available). */
  private async loadInstalledFonts(d: DropdownComponent): Promise<void> {
    const query = (window as unknown as { queryLocalFonts?: () => Promise<Array<{ family: string }>> }).queryLocalFonts;
    if (typeof query !== "function") return;
    try {
      const fonts = await query();
      const families = [...new Set(fonts.map((f) => f.family))].sort((a, b) => a.localeCompare(b));
      if (families.length === 0) return;
      const group = document.createElement("optgroup");
      group.label = "Installed fonts";
      for (const family of families) {
        const option = document.createElement("option");
        option.value = family;
        option.text = family;
        group.appendChild(option);
      }
      d.selectEl.appendChild(group);
    } catch {
      // Local Font Access unavailable or denied — the presets remain usable.
    }
  }
}
