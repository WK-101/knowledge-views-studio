import { Modal, Notice, Setting, MarkdownRenderer, setIcon, TFile, type App, type Component } from "obsidian";
import { getField, type Row } from "../domain/index";
import { splitList } from "../domain/columns/types/list";
import { toBoolean } from "../domain/columns/types/checkbox";
import { RATING_MAX } from "../domain/columns/types/rating";
import { decodeCellText } from "../util/markdown";
import { findColumnByRole, type ResolvedColumn } from "./view-model";
import type { CellRendererRegistry } from "./cells/cell-renderer";
import { openSourceNote } from "./open-source";
import { wireImageZoom } from "./image-zoom";

export interface RowDetailProps {
  readonly app: App;
  readonly row: Row;
  readonly columns: readonly ResolvedColumn[];
  readonly cellRenderers: CellRendererRegistry;
  readonly sourcePath: string;
  readonly component: Component;
  /** When provided, fields are editable and edits write back through this. */
  readonly onEditCell?: (row: Row, column: string, value: string) => void;
  /** Distinct existing values for a column (drives select/theme autocomplete). */
  readonly columnValues?: (columnName: string) => readonly string[];
  /** Look up metadata for a DOI; returns column→value to fill (empty cells only), or null. */
  readonly onFetchDoiValues?: (doi: string) => Promise<Record<string, string> | null>;
  /** Find which library papers this DOI cites; returns column→value (Cites + optional checked), or null. */
  readonly onFindCitations?: (doi: string) => Promise<Record<string, string> | null>;
}

const LONG_TEXT_TYPES = new Set(["markdown", "text", "authors"]);

/** An editable "paper card": every field of a row as a proper form control, so long-form fields
 *  (summary, critique, notes) get real text areas instead of a cramped table cell, and themes get a
 *  chips + autocomplete editor to keep the vocabulary consistent. Read-only when no writer is given. */
export class RowDetailModal extends Modal {
  private readonly values = new Map<string, string>();
  private fieldsEl: HTMLElement | null = null;

  constructor(private readonly props: RowDetailProps) {
    super(props.app);
  }

  override onOpen(): void {
    const { contentEl } = this;
    contentEl.empty();
    contentEl.addClass("kvs-row-detail");
    wireImageZoom(contentEl);
    this.setTitle(this.titleText());
    for (const column of this.props.columns) this.values.set(column.name, getField(this.props.row, column.name));

    if (this.props.onFetchDoiValues && this.doiColumn()) {
      const bar = contentEl.createDiv({ cls: "kvs-card-actions" });
      const fetchBtn = bar.createEl("button", { cls: "kvs-card-fetch" });
      setIcon(fetchBtn.createSpan({ cls: "kvs-card-fetch-ic" }), "download-cloud");
      fetchBtn.appendText("Fetch from DOI");
      fetchBtn.addEventListener("click", () => void this.fetchFromDoi(fetchBtn));
      if (this.props.onFindCitations && this.props.columns.some((c) => c.typeId === "relation")) {
        const citeBtn = bar.createEl("button", { cls: "kvs-card-fetch" });
        setIcon(citeBtn.createSpan({ cls: "kvs-card-fetch-ic" }), "git-fork");
        citeBtn.appendText("Find citations");
        citeBtn.addEventListener("click", () => void this.findCitations(citeBtn));
      }
    }

    this.fieldsEl = contentEl.createDiv({ cls: "kvs-row-detail-fields" });
    this.renderFields();

    const provenance = this.props.row.provenance;
    const meta = contentEl.createDiv({ cls: "kvs-row-detail-meta" });
    meta.createDiv({ cls: "kvs-row-detail-path", text: provenance.filePath });

    new Setting(contentEl).addButton((button) =>
      button
        .setButtonText("Open note")
        .setCta()
        .onClick(() => {
          this.close();
          openSourceNote(this.props.app, provenance.filePath, this.props.sourcePath);
        }),
    );
  }

  private renderFields(): void {
    const fields = this.fieldsEl;
    if (!fields) return;
    fields.empty();
    for (const column of this.props.columns) {
      const field = fields.createDiv({ cls: "kvs-row-detail-field" });
      field.createDiv({ cls: "kvs-row-detail-label", text: column.label });
      const valueEl = field.createDiv({ cls: "kvs-row-detail-value" });
      const editable = Boolean(this.props.onEditCell) && column.editable && !this.props.row.provenance.readOnlyFields?.includes(column.name);
      if (editable) this.renderEditor(valueEl, column);
      else this.renderReadOnly(valueEl, column);
    }
  }

  private doiColumn(): ResolvedColumn | undefined {
    return this.props.columns.find((c) => c.typeId === "doi");
  }

  /** Find library papers this DOI cites, and write them into the Cites column (overwrite = refresh). */
  private async findCitations(button: HTMLButtonElement): Promise<void> {
    const doiCol = this.doiColumn();
    const doi = doiCol ? (this.values.get(doiCol.name) ?? "").trim() : "";
    if (doi === "") {
      new Notice("Enter a DOI in this card first.");
      return;
    }
    button.disabled = true;
    button.setText("Finding…");
    try {
      const result = await this.props.onFindCitations?.(doi);
      if (!result) {
        new Notice("Couldn't look up citations.");
        return;
      }
      for (const [column, value] of Object.entries(result)) {
        this.values.set(column, value); // overwrite: citations reflect the latest lookup
        this.props.onEditCell?.(this.props.row, column, value);
      }
      this.renderFields();
      const citesCol = this.props.columns.find((c) => c.typeId === "relation");
      const n = citesCol ? ((result[citesCol.name] ?? "").match(/\[\[/g)?.length ?? 0) : 0;
      new Notice(n > 0 ? `Found ${n} citation${n === 1 ? "" : "s"} in your library.` : "No papers from your library are cited by this one.");
    } finally {
      button.disabled = false;
      button.empty();
      setIcon(button.createSpan({ cls: "kvs-card-fetch-ic" }), "git-fork");
      button.appendText("Find citations");
    }
  }

  /** Fill empty fields from the DOI in this card, using the host's lookup. */
  private async fetchFromDoi(button: HTMLButtonElement): Promise<void> {
    const doiCol = this.doiColumn();
    const doi = doiCol ? (this.values.get(doiCol.name) ?? "").trim() : "";
    if (doi === "") {
      new Notice("Enter a DOI in this card first.");
      return;
    }
    button.disabled = true;
    button.setText("Fetching…");
    try {
      const filled = await this.props.onFetchDoiValues?.(doi);
      if (!filled) return; // the provider shows a specific reason (network / not found / rate-limited)
      let n = 0;
      for (const [column, value] of Object.entries(filled)) {
        if (value.trim() === "") continue;
        if ((this.values.get(column) ?? "").trim() !== "") continue; // never overwrite
        this.values.set(column, value);
        this.props.onEditCell?.(this.props.row, column, value);
        n++;
      }
      this.renderFields();
      new Notice(n > 0 ? `Filled ${n} field(s) from DOI.` : "Those fields are already filled.");
    } finally {
      button.disabled = false;
      button.empty();
      setIcon(button.createSpan({ cls: "kvs-card-fetch-ic" }), "download-cloud");
      button.appendText("Fetch from DOI");
    }
  }

  /** Rich text field: a growing editor with a live rendered preview and paste-to-attach images —
   *  so a row can hold a few hundred words plus figures from the paper, edited comfortably. */
  private renderRichText(host: HTMLElement, column: ResolvedColumn): void {
    const wrap = host.createDiv({ cls: "kvs-rich-field" });
    const bar = wrap.createDiv({ cls: "kvs-rich-bar" });
    const ta = wrap.createEl("textarea", { cls: "kvs-card-textarea kvs-rich-textarea" });
    ta.value = decodeCellText(this.values.get(column.name) ?? "");
    const preview = wrap.createDiv({ cls: "kvs-rich-preview" });

    const grow = (): void => {
      ta.setCssStyles({ height: "auto" });
      ta.setCssStyles({ height: `${Math.max(48, ta.scrollHeight)}px` });
    };
    const renderPreview = (): void => {
      preview.empty();
      const text = ta.value.trim();
      if (text === "") {
        preview.createSpan({ cls: "kvs-rich-empty", text: "Preview" });
        return;
      }
      void MarkdownRenderer.render(this.props.app, text, preview, this.props.sourcePath, this.props.component);
    };
    grow();
    renderPreview();

    let timer: number | null = null;
    ta.addEventListener("input", () => {
      grow();
      if (timer !== null) window.clearTimeout(timer);
      timer = window.setTimeout(renderPreview, 250);
    });
    ta.addEventListener("blur", () => {
      this.commit(column, ta.value);
      renderPreview();
    });
    ta.addEventListener("paste", (event) => {
      const items = event.clipboardData?.items;
      let file: File | null = null;
      for (let i = 0; items && i < items.length; i++) {
        const it = items[i]!;
        if (it.type.startsWith("image/")) {
          file = it.getAsFile();
          break;
        }
      }
      if (!file) return;
      event.preventDefault();
      const f = file;
      void this.attachImage(f, ta, () => {
        this.commit(column, ta.value);
        renderPreview();
      });
    });

    const addBtn = bar.createEl("a", { cls: "kvs-rich-btn", text: "Add image" });
    setIcon(addBtn.createSpan({ cls: "kvs-rich-ic" }), "image-plus");
    addBtn.addEventListener("click", () => this.pickImage(ta, () => {
      this.commit(column, ta.value);
      renderPreview();
    }));
  }

  /** Save an image file into the vault and insert its embed at the cursor. */
  private async attachImage(file: File, ta: HTMLTextAreaElement, after: () => void): Promise<void> {
    try {
      const ext = (file.name.split(".").pop() || file.type.split("/")[1] || "png").toLowerCase();
      const name = file.name && file.name !== "image.png" ? file.name : `pasted-${Date.now()}.${ext}`;
      const path = await this.props.app.fileManager.getAvailablePathForAttachment(name, this.props.sourcePath);
      await this.props.app.vault.createBinary(path, await file.arrayBuffer());
      const target = this.props.app.vault.getAbstractFileByPath(path);
      const link = target instanceof TFile ? this.props.app.fileManager.generateMarkdownLink(target, this.props.sourcePath) : `![[${path.split("/").pop()}]]`;
      const embed = link.startsWith("!") ? link : `!${link}`;
      const pos = ta.selectionStart ?? ta.value.length;
      ta.value = `${ta.value.slice(0, pos)}${embed}${ta.value.slice(ta.selectionEnd ?? pos)}`;
      after();
    } catch {
      // Attaching failed (read-only vault?) — leave the text untouched.
    }
  }

  private pickImage(ta: HTMLTextAreaElement, after: () => void): void {
    const input = createEl("input", { attr: { type: "file", accept: "image/*" } });
    input.addEventListener("change", () => {
      const file = input.files?.[0];
      if (file) void this.attachImage(file, ta, after);
    });
    input.click();
  }

  private commit(column: ResolvedColumn, value: string): void {
    if (value === this.values.get(column.name)) return;
    this.values.set(column.name, value);
    this.props.onEditCell?.(this.props.row, column.name, value);
  }

  private renderReadOnly(host: HTMLElement, column: ResolvedColumn): void {
    const value = this.values.get(column.name) ?? "";
    const renderer = this.props.cellRenderers.get(column.typeId);
    if (renderer && value.trim() !== "") {
      renderer.render({ el: host, value, column, app: this.props.app, sourcePath: this.props.sourcePath, component: this.props.component });
    } else {
      host.setText(value.trim() === "" ? "—" : value);
    }
  }

  private renderEditor(host: HTMLElement, column: ResolvedColumn): void {
    const value = this.values.get(column.name) ?? "";
    if (column.typeId === "list") {
      this.renderThemesEditor(host, column);
    } else if (column.typeId === "checkbox") {
      const input = host.createEl("input", { cls: "kvs-card-check" });
      input.type = "checkbox";
      input.checked = toBoolean(value);
      input.addEventListener("change", () => this.commit(column, input.checked ? "x" : ""));
    } else if (column.typeId === "rating") {
      this.renderRating(host, column);
    } else if (column.typeId === "markdown") {
      this.renderRichText(host, column);
    } else if (LONG_TEXT_TYPES.has(column.typeId)) {
      const ta = host.createEl("textarea", { cls: "kvs-card-textarea" });
      ta.value = decodeCellText(value);
      ta.rows = Math.min(10, Math.max(2, ta.value.split("\n").length + 1));
      ta.addEventListener("blur", () => this.commit(column, ta.value));
    } else {
      const input = host.createEl("input", { cls: "kvs-card-input" });
      input.type = column.typeId === "number" ? "number" : "text";
      input.value = value;
      if (column.typeId === "select") this.attachDatalist(input, this.suggestions(column));
      input.addEventListener("blur", () => this.commit(column, input.value));
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") input.blur();
      });
    }
  }

  private renderRating(host: HTMLElement, column: ResolvedColumn): void {
    const wrap = host.createDiv({ cls: "kvs-card-rating" });
    const draw = (): void => {
      wrap.empty();
      const current = Number(this.values.get(column.name) ?? "0") || 0;
      for (let i = 1; i <= RATING_MAX; i++) {
        const star = wrap.createSpan({ cls: `kvs-card-star${i <= current ? " is-on" : ""}` });
        setIcon(star, "star");
        star.addEventListener("click", () => {
          this.commit(column, i === current ? "" : String(i));
          draw();
        });
      }
    };
    draw();
  }

  /** Themes editor: removable chips for current values + an input with autocomplete to add more. */
  private renderThemesEditor(host: HTMLElement, column: ResolvedColumn): void {
    const wrap = host.createDiv({ cls: "kvs-themes-editor" });
    const chips = wrap.createDiv({ cls: "kvs-themes-chips" });
    const items = (): string[] => splitList(this.values.get(column.name) ?? "");
    const write = (list: string[]): void => this.commit(column, list.join(", "));
    const draw = (): void => {
      chips.empty();
      for (const item of items()) {
        const chip = chips.createSpan({ cls: "kvs-pill kvs-theme-chip" });
        chip.createSpan({ text: item });
        const x = chip.createSpan({ cls: "kvs-theme-x" });
        setIcon(x, "x");
        x.addEventListener("click", () => {
          write(items().filter((t) => t.toLowerCase() !== item.toLowerCase()));
          draw();
        });
      }
    };
    draw();
    const input = wrap.createEl("input", { cls: "kvs-card-input kvs-theme-input" });
    input.setAttr("placeholder", "Add theme…");
    this.attachDatalist(input, this.suggestions(column));
    const add = (): void => {
      const v = input.value.trim();
      if (v === "") return;
      const list = items();
      if (!list.some((t) => t.toLowerCase() === v.toLowerCase())) {
        list.push(v);
        write(list);
        draw();
      }
      input.value = "";
    };
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === ",") {
        e.preventDefault();
        add();
      }
    });
    input.addEventListener("blur", add);
  }

  private suggestions(column: ResolvedColumn): readonly string[] {
    const fromData = this.props.columnValues?.(column.name) ?? [];
    const fromOptions = (column.options ?? []).map((o) => o.value);
    return [...new Set([...fromOptions, ...fromData])].filter((s) => s.trim() !== "").sort();
  }

  private attachDatalist(input: HTMLInputElement, values: readonly string[]): void {
    if (values.length === 0) return;
    const id = `kvs-dl-${Math.random().toString(36).slice(2)}`;
    const dl = input.parentElement!.createEl("datalist");
    dl.id = id;
    for (const v of values) dl.createEl("option", { value: v });
    input.setAttr("list", id);
  }

  override onClose(): void {
    this.contentEl.empty();
  }

  private titleText(): string {
    const titleColumn = findColumnByRole(this.props.columns, "title") ?? this.props.columns[0];
    const title = titleColumn ? getField(this.props.row, titleColumn.name).trim() : "";
    return title || this.props.row.file.fileName || "Row details";
  }
}

/** Open the row-detail modal from a view render context. */
export function openRowDetail(
  ctx: {
    app: App;
    cellRenderers: CellRendererRegistry;
    sourcePath: string;
    component: Component;
    columns: readonly ResolvedColumn[];
    onEditCell?: (row: Row, column: string, value: string) => void;
    columnValues?: (columnName: string) => readonly string[];
    onFetchDoiValues?: (doi: string) => Promise<Record<string, string> | null>;
    onFindCitations?: (doi: string) => Promise<Record<string, string> | null>;
  },
  row: Row,
): void {
  new RowDetailModal({
    app: ctx.app,
    row,
    columns: ctx.columns,
    cellRenderers: ctx.cellRenderers,
    sourcePath: ctx.sourcePath,
    component: ctx.component,
    ...(ctx.onEditCell ? { onEditCell: ctx.onEditCell } : {}),
    ...(ctx.columnValues ? { columnValues: ctx.columnValues } : {}),
    ...(ctx.onFetchDoiValues ? { onFetchDoiValues: ctx.onFetchDoiValues } : {}),
    ...(ctx.onFindCitations ? { onFindCitations: ctx.onFindCitations } : {}),
  }).open();
}
