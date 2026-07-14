import { App, Modal, Notice, setIcon, setTooltip } from "obsidian";
import {
  compileExpression,
  parseExpression,
  traceExpression,
  FUNCTION_DOCS,
  type ComputedColumn,
  type FunctionDoc,
  type Row,
  type TraceStep,
} from "../domain/index";

/**
 * The formula editor.
 *
 * A formula box that only tells you the answer is close to useless: when the answer is blank — which is
 * most of the time while you are writing one — it tells you nothing about *why*. This gives three things
 * a bare textarea cannot:
 *
 *   - the answer, live, against a real row from the user's own data (not a made-up example);
 *   - a reference of every function, with an example you can click straight into the formula;
 *   - and the working: each sub-expression and what it evaluated to, so a blank result points at the
 *     empty field that caused it instead of leaving you to guess.
 */
export class FormulaEditorModal extends Modal {
  private expression: string;
  private rowIndex = 0;

  private inputEl!: HTMLTextAreaElement;
  private resultEl!: HTMLElement;
  private traceEl!: HTMLElement;
  private refEl!: HTMLElement;
  private rowLabelEl!: HTMLElement;

  constructor(
    app: App,
    private readonly column: ComputedColumn,
    private readonly fields: readonly string[],
    private readonly sampleRows: readonly Row[],
    private readonly onSave: (expression: string) => void,
  ) {
    super(app);
    this.expression = column.expression;
  }

  override onOpen(): void {
    const { contentEl, titleEl, modalEl } = this;
    modalEl.addClass("kvs-formula-modal");
    titleEl.setText(`Formula — ${this.column.name}`);

    const body = contentEl.createDiv({ cls: "kvs-fx" });

    // ---- left: write it, see it ----
    const left = body.createDiv({ cls: "kvs-fx-main" });

    this.inputEl = left.createEl("textarea", { cls: "kvs-fx-input" });
    this.inputEl.value = this.expression;
    this.inputEl.rows = 4;
    this.inputEl.placeholder = 'e.g.  if([Hours] > 8, "Long", "Short")';
    this.inputEl.addEventListener("input", () => {
      this.expression = this.inputEl.value;
      this.refresh();
    });

    const resultBox = left.createDiv({ cls: "kvs-fx-result" });
    const head = resultBox.createDiv({ cls: "kvs-fx-result-head" });
    head.createSpan({ cls: "kvs-fx-result-label", text: "Result" });
    this.rowLabelEl = head.createSpan({ cls: "kvs-fx-rowlabel" });
    const nav = head.createDiv({ cls: "kvs-fx-rownav" });
    this.navBtn(nav, "chevron-left", "Previous row", () => this.step(-1));
    this.navBtn(nav, "chevron-right", "Next row", () => this.step(1));
    this.resultEl = resultBox.createDiv({ cls: "kvs-fx-result-value" });

    left.createDiv({ cls: "kvs-fx-section-label", text: "How this was worked out" });
    this.traceEl = left.createDiv({ cls: "kvs-fx-trace" });

    // ---- right: what you can use ----
    const right = body.createDiv({ cls: "kvs-fx-side" });
    this.renderFields(right);
    this.refEl = right.createDiv({ cls: "kvs-fx-ref" });
    this.renderReference();

    // ---- footer ----
    const foot = contentEl.createDiv({ cls: "kvs-fx-foot" });
    const cancel = foot.createEl("button", { text: "Cancel" });
    cancel.addEventListener("click", () => this.close());
    const save = foot.createEl("button", { cls: "mod-cta", text: "Save formula" });
    save.addEventListener("click", () => {
      this.onSave(this.expression);
      this.close();
    });

    this.refresh();
    window.setTimeout(() => this.inputEl.focus(), 0);
  }

  override onClose(): void {
    this.contentEl.empty();
  }

  private navBtn(parent: HTMLElement, icon: string, tip: string, onClick: () => void): void {
    const b = parent.createEl("button", { cls: "clickable-icon kvs-tb-icon" });
    setIcon(b, icon);
    setTooltip(b, tip);
    b.addEventListener("click", onClick);
  }

  private step(delta: number): void {
    if (this.sampleRows.length === 0) return;
    this.rowIndex = (this.rowIndex + delta + this.sampleRows.length) % this.sampleRows.length;
    this.refresh();
  }

  /** Insert text at the cursor, so clicking a field or an example lands where you were typing. */
  private insert(text: string): void {
    const el = this.inputEl;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    el.value = el.value.slice(0, start) + text + el.value.slice(end);
    el.selectionStart = el.selectionEnd = start + text.length;
    this.expression = el.value;
    el.focus();
    this.refresh();
  }

  private renderFields(parent: HTMLElement): void {
    parent.createDiv({ cls: "kvs-fx-section-label", text: "Fields" });
    const box = parent.createDiv({ cls: "kvs-fx-fields" });
    if (this.fields.length === 0) {
      box.createDiv({ cls: "kvs-fx-muted", text: "No columns yet." });
      return;
    }
    for (const field of this.fields) {
      const chip = box.createEl("button", { cls: "kvs-fx-chip", text: field });
      setTooltip(chip, `Insert [${field}]`);
      chip.addEventListener("click", () => this.insert(`[${field}]`));
    }
  }

  private renderReference(): void {
    const el = this.refEl;
    el.empty();
    el.createDiv({ cls: "kvs-fx-section-label", text: "Functions" });

    const search = el.createEl("input", { cls: "kvs-fx-search", type: "text" });
    search.placeholder = "Search functions…";
    const list = el.createDiv({ cls: "kvs-fx-fnlist" });

    const draw = (query: string): void => {
      list.empty();
      const q = query.trim().toLowerCase();
      const matches = FUNCTION_DOCS.filter(
        (f) => q === "" || f.name.includes(q) || f.description.toLowerCase().includes(q) || f.category.toLowerCase().includes(q),
      );
      if (matches.length === 0) {
        list.createDiv({ cls: "kvs-fx-muted", text: `Nothing matches “${query}”.` });
        return;
      }
      let category = "";
      for (const fn of matches) {
        if (fn.category !== category) {
          category = fn.category;
          list.createDiv({ cls: "kvs-fx-fncat", text: category });
        }
        this.renderFn(list, fn);
      }
    };
    search.addEventListener("input", () => draw(search.value));
    draw("");
  }

  private renderFn(parent: HTMLElement, fn: FunctionDoc): void {
    const item = parent.createDiv({ cls: "kvs-fx-fn" });
    const top = item.createDiv({ cls: "kvs-fx-fn-top" });
    top.createSpan({ cls: "kvs-fx-fn-sig", text: fn.signature });
    const use = top.createEl("button", { cls: "kvs-fx-fn-use", text: "Use" });
    setTooltip(use, `Insert: ${fn.example}`);
    use.addEventListener("click", () => this.insert(fn.example));
    item.createDiv({ cls: "kvs-fx-fn-desc", text: fn.description });
    const ex = item.createDiv({ cls: "kvs-fx-fn-ex", text: fn.example });
    ex.addEventListener("click", () => this.insert(fn.example));
  }

  /** Re-evaluate against the current sample row and redraw the answer + the working. */
  private refresh(): void {
    const row = this.sampleRows[this.rowIndex];
    this.rowLabelEl.setText(
      this.sampleRows.length === 0
        ? "no rows to preview against"
        : `row ${this.rowIndex + 1} of ${this.sampleRows.length} · ${row?.file.fileName ?? ""}`,
    );

    this.resultEl.empty();
    this.traceEl.empty();

    const source = this.expression.trim();
    if (source === "") {
      this.resultEl.addClass("is-muted");
      this.resultEl.setText("Write a formula to see its result here.");
      return;
    }

    // A formula that will not parse is the most common state while typing one. Say what is wrong,
    // plainly, and do not pretend to have a result.
    let compiled;
    try {
      compiled = compileExpression(source);
    } catch (error) {
      this.resultEl.removeClass("is-muted");
      this.resultEl.addClass("is-error");
      this.resultEl.setText(error instanceof Error ? error.message : String(error));
      return;
    }
    this.resultEl.removeClass("is-error");

    if (!row) {
      this.resultEl.addClass("is-muted");
      this.resultEl.setText("Valid formula. Add some rows to preview it against.");
      return;
    }

    const value = compiled.evaluate(row);
    const text = value === null || value === "" ? "" : String(value);
    this.resultEl.removeClass("is-muted");
    if (text === "") {
      this.resultEl.addClass("is-muted");
      this.resultEl.setText("(empty)");
    } else {
      this.resultEl.setText(text);
    }

    this.renderTrace(traceExpression(parseExpression(source), row));
  }

  private renderTrace(steps: readonly TraceStep[]): void {
    if (steps.length === 0) {
      this.traceEl.createDiv({ cls: "kvs-fx-muted", text: "Nothing to work out — the formula is a constant." });
      return;
    }
    for (const step of steps) {
      const line = this.traceEl.createDiv({ cls: "kvs-fx-step" });
      line.setCssProps({ "--kvs-depth": String(step.depth) });
      line.createSpan({ cls: "kvs-fx-step-expr", text: step.expr });
      line.createSpan({ cls: "kvs-fx-step-arrow", text: "→" });
      line.createSpan({ cls: "kvs-fx-step-val", text: step.value });
      if (step.note) {
        line.addClass("has-note");
        line.createSpan({ cls: "kvs-fx-step-note", text: step.note });
      }
    }
  }
}

/** Copy a prompt describing this formula's context, for pasting into an assistant. */
export function copyFormulaPrompt(column: ComputedColumn, fields: readonly string[]): void {
  const text = [
    `I am writing a formula for a column called "${column.name}" in an Obsidian table.`,
    "",
    `Available fields: ${fields.map((f) => `[${f}]`).join(", ")}`,
    "",
    "Available functions:",
    ...FUNCTION_DOCS.map((f) => `  ${f.signature} — ${f.description}`),
    "",
    `Current formula: ${column.expression || "(none yet)"}`,
    "",
    "What I want it to do: ",
  ].join("\n");
  void navigator.clipboard?.writeText(text);
  new Notice("Formula context copied — paste it into an assistant.");
}
