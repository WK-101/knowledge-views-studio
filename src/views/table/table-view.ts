import { Menu, setIcon, setTooltip } from "obsidian";
import { getField, type Row,
  summarizeColumn,
  SUMMARY_FUNCTIONS,
  type SummaryFn,
} from "../../domain/index";
import { toBoolean } from "../../domain/columns/types/checkbox";
import { defaultWideWidth, type ResolvedColumn } from "../view-model";
import { renderEmptyState } from "../empty-state";
import { COPY_FORMATS } from "../copy/row-copy";
import { openSourceNote } from "../open-source";
import { openRowDetail } from "../row-detail-modal";
import type { KnowledgeView, ViewRenderContext } from "../view";
import { buildPrefix, totalHeight, findRowAt, computeWindow, anchorShift } from "./virtual-window";
import { enableHandleDrag } from "../../util/pointer-drag";

import { selectionStore, bulkDraftStore, scrollStore, capViewState } from "../view-state";
import { noteLinkColumnName, wikilinkTarget, citeKeyColumnName } from "../promoted-detect";
import { dedicatedNoteKeyFor, getDedicatedNoteIndex, normalizeIdentifier } from "../../services/notes/dedicated-note";
import { resolveFieldColumn } from "../../domain/columns/academic-fields";

const VIRTUAL_THRESHOLD = 100;
const OVERSCAN = 8;

const tokenOf = (row: Row): string => `${row.provenance.filePath}::${row.provenance.fingerprint}`;

function flatten(ctx: ViewRenderContext): Row[] {
  return ctx.result.groups ? ctx.result.groups.flatMap((g) => g.rows) : [...ctx.result.rows];
}

/** Sources whose rows KVS can edit, add, duplicate and delete inline (write-back is implemented):
 *  Markdown tables and Excel workbooks. Other extractors stay read-only in the grid. */
const WRITABLE_EXTRACTORS = new Set(["table", "xlsx"]);
const isWritableRow = (row: Row): boolean => WRITABLE_EXTRACTORS.has(row.provenance.extractor);
/** A displayed value that reads as a number, currency amount or percentage (for right-alignment). */
const NUMERIC_DISPLAY = /^\s*[-+]?[$€£¥₹₩]?\s?\d[\d,]*(\.\d+)?\s?%?\s*$/;

function isCellEditable(ctx: ViewRenderContext, column: ResolvedColumn, row: Row): boolean {
  if (row.provenance.readOnlyFields?.includes(column.name)) return false; // e.g. an Excel formula cell
  return Boolean(ctx.onEditCell) && column.editable && isWritableRow(row);
}

function renderCellValue(td: HTMLElement, value: string, column: ResolvedColumn, ctx: ViewRenderContext): void {
  td.empty();
  const renderer = ctx.cellRenderers.get(column.typeId);
  if (renderer) {
    renderer.render({ el: td, value, column, app: ctx.app, sourcePath: ctx.sourcePath, component: ctx.component, shortenTags: ctx.shortenTags });
  } else {
    td.setText(value);
  }
}

// The currently-open in-place cell edit, if any. It is committed before the virtualizer
// rebuilds rows (on scroll) or the table fully repaints, so an edit is never silently lost.
let activeCellEdit: (() => void) | null = null;

/**
 * Edit a cell in place. The editor is rendered directly into the cell, so it scrolls with
 * the table and aligns natively — there is no floating overlay, which means a row taller
 * than the viewport simply edits as a tall cell instead of turning into a popover. The
 * cell's own inset focus ring signals the active edit. Scrolling (or opening another cell)
 * commits the current value.
 */
function enterEditMode(td: HTMLElement, row: Row, column: ResolvedColumn, ctx: ViewRenderContext): void {
  const editor = ctx.cellEditors?.get(column.typeId);
  if (!editor) return;

  activeCellEdit?.(); // commit any other open editor first

  const value = getField(row, column.name);
  const rowHeight = (td.closest("tr") ?? td).getBoundingClientRect().height;

  td.addClass("kvs-editing");
  td.empty();
  td.style.minHeight = `${rowHeight}px`; // hold the row height steady while editing

  let closed = false;
  const teardown = (): void => {
    if (closed) return;
    closed = true;
    if (activeCellEdit === forceCommit) activeCellEdit = null;
    document.removeEventListener("keydown", onKey, true);
    td.removeClass("kvs-editing");
    td.setCssStyles({ minHeight: "" });
  };
  const commit = (next: string): void => {
    teardown();
    if (next !== value) ctx.onEditCell?.(row, column.name, next);
    else renderCellValue(td, value, column, ctx);
  };
  const cancel = (): void => {
    teardown();
    renderCellValue(td, value, column, ctx);
  };
  // Blur the focused control so the editor commits (used on scroll / opening another cell).
  const forceCommit = (): void => {
    const control = td.querySelector("input, textarea, select");
    if (control instanceof HTMLElement) control.blur();
    else if (!closed) cancel();
  };
  activeCellEdit = forceCommit;

  const onKey = (event: KeyboardEvent): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    }
  };
  document.addEventListener("keydown", onKey, true);

  editor.edit({ el: td, value, column, app: ctx.app, component: ctx.component, sourcePath: ctx.sourcePath, commit, cancel, suggestions: ctx.columnValues?.(column.name) ?? [] });
}

function renderCell(tr: HTMLElement, row: Row, column: ResolvedColumn, ctx: ViewRenderContext, markFrozen: boolean): void {
  const td = tr.createEl("td", { cls: "kvs-td" });
  if (markFrozen) td.addClass("kvs-frozen-col0");
  const value = getField(row, column.name);
  const editable = isCellEditable(ctx, column, row);
  // Right-align numbers, currency and percentages — the spreadsheet convention that makes tabular
  // numeric data scan naturally. Driven by the value so Excel currency/percent cells align too.
  if (column.typeId === "number" || column.typeId === "rating" || NUMERIC_DISPLAY.test(value)) {
    td.addClass("kvs-num-cell");
  }
  if (column.typeId === "markdown") td.addClass("kvs-md-cell");

  if (editable && column.typeId === "checkbox") {
    const input = td.createEl("input", { cls: "kvs-checkbox" });
    input.type = "checkbox";
    input.checked = toBoolean(value);
    input.addEventListener("change", () => ctx.onEditCell?.(row, column.name, input.checked ? "x" : ""));
    return;
  }

  renderCellValue(td, value, column, ctx);
  const isFormula = row.provenance.readOnlyFields?.includes(column.name) ?? false;
  if (isFormula) {
    td.addClass("kvs-formula-cell");
    setTooltip(td, "Calculated in Excel (formula) — read-only here");
  } else if (editable && ctx.cellEditors) {
    td.addClass("kvs-editable");
    td.addEventListener("dblclick", () => enterEditMode(td, row, column, ctx));
  }
}

/** Reserved columnWidths key for the combined leading "gutter" (selection + source). A NUL char keeps
 *  it from ever colliding with a real column name (which come from table headers). */
const GUTTER_KEY = "\u0000gutter";

/** The gutter's width in px: an explicit (dragged) width, or a compact default sized to its controls. */
function effectiveGutterWidth(ctx: ViewRenderContext, hasSelect: boolean): number {
  const explicit = ctx.profile.columnWidths?.[GUTTER_KEY];
  if (typeof explicit === "number" && explicit > 0) return explicit;
  // Controls stack vertically (checkbox over the actions "⋯"), so the column only needs to be wide enough for
  // one small control plus a little breathing room — a single narrow lane. The promoted flag sits in the
  // corner and costs no width.
  const hasAnything = hasSelect || ctx.profile.sourceColumn || hasPromoteIndicator(ctx) || ctx.onDeleteRow !== undefined || ctx.onDuplicateRow !== undefined;
  return hasAnything ? 26 : 20;
}

/** A draggable width handle on a header cell. Shared by data columns and the gutter (different mins).
 *  Double-clicking the handle resets the column to its default width. */
function attachResizeHandle(
  th: HTMLElement,
  table: HTMLElement,
  min: number,
  commit: (width: number) => void,
  reset?: () => void,
): void {
  const handle = th.createDiv({ cls: "kvs-col-resize" });
  setTooltip(handle, "Drag to resize · double-click to reset");
  handle.addEventListener("click", (event) => event.stopPropagation());
  if (reset) {
    handle.addEventListener("dblclick", (event) => {
      event.preventDefault();
      event.stopPropagation();
      reset();
    });
  }
  // A pointer drag, not a mouse drag: a 4px-wide divider is exactly the kind of thing a finger could
  // never grab before, because `mousemove` is not delivered while a touch is in progress. The pointer is
  // captured, so the drag survives the cursor leaving those few pixels — which it does immediately.
  let startX = 0;
  let startWidth = 0;
  const widthAt = (clientX: number): number => Math.max(min, Math.round(startWidth + clientX - startX));
  enableHandleDrag(handle, {
    onStart: (event) => {
      startX = event.clientX;
      startWidth = th.getBoundingClientRect().width;
      table.addClass("kvs-fixed");
    },
    onMove: (event) => {
      th.style.width = `${widthAt(event.clientX)}px`;
    },
    onEnd: (event) => commit(widthAt(event.clientX)),
  });
}

/** Right-click menu on a header cell: reset this width, reset all, and (data columns) hide the column. */
function attachHeaderMenu(th: HTMLElement, ctx: ViewRenderContext, name: string, isGutter: boolean): void {
  if (!ctx.onResetColumnWidth && !ctx.onResetAllColumnWidths && !(ctx.onHideColumn && !isGutter)) return;
  th.addEventListener("contextmenu", (event) => {
    event.preventDefault();
    event.stopPropagation();
    const menu = new Menu();
    if (ctx.onResetColumnWidth) {
      menu.addItem((i) => i.setTitle("Reset width").setIcon("move-horizontal").onClick(() => ctx.onResetColumnWidth?.(name)));
    }
    if (ctx.onResetAllColumnWidths) {
      menu.addItem((i) => i.setTitle("Reset all column widths").setIcon("table").onClick(() => ctx.onResetAllColumnWidths?.()));
    }
    if (!isGutter && ctx.onHideColumn) {
      menu.addItem((i) => i.setTitle("Hide column").setIcon("eye-off").onClick(() => ctx.onHideColumn?.(name)));
    }
    menu.showAtMouseEvent(event);
  });
}

/**
 * The combined leading "gutter": the row-selection checkbox and/or a link to the source note in ONE
 * column, instead of two. Row details stay available from the right-click menu. The column is
 * width-adjustable (drag its header edge) via a reserved columnWidths key.
 */
/** Columns pool for this view: configured first (works when hidden), else resolved. */
function columnPool(ctx: ViewRenderContext): { name: string; type: string }[] {
  return ctx.profile.columns.length > 0 ? ctx.profile.columns.map((c) => ({ name: c.name, type: c.type })) : ctx.columns.map((c) => ({ name: c.name, type: c.typeId }));
}

function noteLinkColumn(ctx: ViewRenderContext): string | null {
  return noteLinkColumnName(columnPool(ctx));
}

/** This row's value for the dedicated-note match key (its DOI for academic views), or "". */
function matchValueFor(row: Row, ctx: ViewRenderContext, key: string): string {
  const col = key === "doi" ? resolveFieldColumn(columnPool(ctx), "doi", ctx.profile.fieldMap) : columnPool(ctx).find((c) => c.name.toLowerCase() === key.toLowerCase());
  return col ? getField(row, col.name).trim() : "";
}

/** The dedicated-note target for a row: a frontmatter-identifier match (most robust — survives rename/move),
 *  else the Note column's `[[link]]`, else the paper's cite key if a note by that name exists in the vault.
 *  The frontmatter index is process-cached, so this is an O(1) map lookup per row, not a vault scan. */
function promotedNoteFor(row: Row, ctx: ViewRenderContext): string | null {
  const matchKey = dedicatedNoteKeyFor(ctx.profile);
  if (matchKey !== "") {
    const value = matchValueFor(row, ctx, matchKey);
    if (value !== "") {
      const file = getDedicatedNoteIndex(ctx.app, matchKey).get(normalizeIdentifier(matchKey, value));
      if (file) return file.basename;
    }
  }
  const noteCol = noteLinkColumn(ctx);
  const linked = noteCol ? wikilinkTarget(getField(row, noteCol)) : null;
  if (linked) return linked;
  const keyCol = citeKeyColumnName(columnPool(ctx));
  const key = keyCol ? getField(row, keyCol).trim().replace(/^@/, "") : "";
  if (key !== "" && ctx.app.metadataCache.getFirstLinkpathDest(key, ctx.sourcePath) !== null) return key;
  return null;
}

/** Whether this view can carry a promoted-note indicator (a note-link/cite-key column, or a frontmatter
 *  identifier match such as DOI for academic views). */
function hasPromoteIndicator(ctx: ViewRenderContext): boolean {
  const pool = columnPool(ctx);
  return noteLinkColumnName(pool) !== null || citeKeyColumnName(pool) !== null || dedicatedNoteKeyFor(ctx.profile) !== "";
}

/** Whether the table has a single combined leading gutter column (selection / source / promote indicator).
 *  The header, every body row, and the summary footer MUST agree on this or their columns misalign — hence
 *  one shared predicate rather than three copies of the condition. */
function hasGutterColumn(ctx: ViewRenderContext, hasSelection: boolean): boolean {
  return hasSelection || ctx.profile.sourceColumn === true || hasPromoteIndicator(ctx);
}

function renderGutterCell(
  tr: HTMLElement,
  row: Row,
  ctx: ViewRenderContext,
  selection: Set<string> | null,
  repaint: () => void,
): void {
  const showSource = ctx.profile.sourceColumn;
  const promoted = promotedNoteFor(row, ctx);
  if (!hasGutterColumn(ctx, Boolean(selection))) return;
  const td = tr.createEl("td", { cls: "kvs-td kvs-gutter-cell" });
  td.style.width = `${effectiveGutterWidth(ctx, Boolean(selection))}px`;
  const inner = td.createDiv({ cls: "kvs-gutter" });

  if (selection) {
    const checkbox = inner.createEl("input", { cls: "kvs-checkbox" });
    checkbox.type = "checkbox";
    checkbox.checked = selection.has(tokenOf(row));
    checkbox.setAttribute("aria-label", "Select row");
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) selection.add(tokenOf(row));
      else selection.delete(tokenOf(row));
      tr.toggleClass("is-selected", checkbox.checked);
      repaint(); // lightweight: renderTable passes refreshSelectionUI here, so the grid isn't rebuilt
    });
  }

  // A single, quiet "row actions" button opens the same menu as right-click — the one place row commands
  // live, so the column stays compact and new commands never need new icons. It's revealed on row hover
  // (kept in the layout so nothing shifts), and stays visible for a promoted row so its ◆ flag reads as a
  // handle you can act on.
  if (showSource || promoted || ctx.onDeleteRow || ctx.onDuplicateRow) {
    const actions = inner.createEl("button", { cls: "kvs-row-actions clickable-icon" });
    setIcon(actions, "more-horizontal");
    actions.setAttribute("aria-label", "Row actions");
    setTooltip(actions, "Row actions");
    actions.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      buildRowMenu(ctx, row, promoted).showAtMouseEvent(event);
    });
  }

  // Promoted status: a small accent flag in the corner, always visible, costing no width. Pure state — the
  // "open dedicated note" action lives in the row menu — so the column reads at a glance without clutter.
  if (promoted) {
    td.addClass("is-promoted");
    const flag = inner.createSpan({ cls: "kvs-row-flag" });
    flag.setAttribute("aria-hidden", "true");
    setTooltip(flag, `Has a dedicated note: ${promoted}`);
  }
}

/**
 * Build the row's actions menu — the single source of truth for what you can do to a row, shared by the
 * right-click context menu and the row-tools “⋯” button so the two never diverge. New row commands go here
 * and appear in both places at once. `promoted` is the dedicated note this row links to, if any.
 */
function buildRowMenu(ctx: ViewRenderContext, row: Row, promoted: string | null): Menu {
  const menu = new Menu();
  if (ctx.profile.sourceColumn) {
    menu.addItem((i) =>
      i.setTitle("Open source note").setIcon("file").onClick(() => openSourceNote(ctx.app, row.provenance.filePath, ctx.sourcePath)),
    );
  }
  if (promoted) {
    menu.addItem((i) =>
      i.setTitle("Open dedicated note").setIcon("file-text").onClick(() => void ctx.app.workspace.openLinkText(promoted, ctx.sourcePath, false)),
    );
  }
  menu.addItem((i) => i.setTitle("View details").setIcon("maximize-2").onClick(() => openRowDetail(ctx, row)));
  if (ctx.onCite) {
    const keyCol = ctx.columns.find((c) => c.typeId === "citekey");
    const key = keyCol ? getField(row, keyCol.name).trim() : "";
    if (key !== "") {
      menu.addItem((i) => i.setTitle("Insert citation into note").setIcon("quote").onClick(() => ctx.onCite?.(key)));
    }
  }
  if (ctx.onFetchDoi) {
    const doiCol = ctx.columns.find((c) => c.typeId === "doi");
    if (doiCol && getField(row, doiCol.name).trim() !== "") {
      menu.addItem((i) => i.setTitle("Fill details from DOI").setIcon("download-cloud").onClick(() => ctx.onFetchDoi?.(row)));
      if (ctx.onFetchZotero) {
        menu.addItem((i) => i.setTitle("Fill details from Zotero").setIcon("library").onClick(() => ctx.onFetchZotero?.(row)));
      }
    }
  }
  if (ctx.onPromote && !promoted) {
    menu.addItem((i) => i.setTitle("Promote to dedicated note").setIcon("file-plus").onClick(() => ctx.onPromote?.(row)));
  }
  if (isWritableRow(row) && (ctx.onAddRow || ctx.onDuplicateRow || ctx.onDeleteRow)) {
    menu.addSeparator();
    if (ctx.onAddRow) menu.addItem((i) => i.setTitle("Add row below").setIcon("plus").onClick(() => ctx.onAddRow?.(row)));
    if (ctx.onDuplicateRow) menu.addItem((i) => i.setTitle("Duplicate row").setIcon("copy").onClick(() => ctx.onDuplicateRow?.(row)));
    if (ctx.onDeleteRow) menu.addItem((i) => i.setTitle("Delete row").setIcon("trash").onClick(() => ctx.onDeleteRow?.(row)));
  }
  return menu;
}

function attachRowMenu(tr: HTMLElement, row: Row, ctx: ViewRenderContext): void {
  tr.addEventListener("contextmenu", (event) => {
    event.preventDefault();
    buildRowMenu(ctx, row, promotedNoteFor(row, ctx)).showAtMouseEvent(event);
  });
}

function renderBodyRow(
  tbody: HTMLElement,
  row: Row,
  ctx: ViewRenderContext,
  selection: Set<string> | null,
  repaint: () => void,
  frozen: boolean,
  ariaRowIndex?: number,
): void {
  const tr = tbody.createEl("tr", { cls: "kvs-row" });
  // The row's true position in the full dataset (not its position in the rendered window), so a
  // screen reader reads "row 240 of 500" correctly even though only a dozen rows are in the DOM.
  if (ariaRowIndex !== undefined) tr.setAttribute("aria-rowindex", String(ariaRowIndex));
  renderGutterCell(tr, row, ctx, selection, repaint);
  ctx.columns.forEach((column, index) => renderCell(tr, row, column, ctx, frozen && index === 0));
  attachRowMenu(tr, row, ctx);
}

function editableColumns(ctx: ViewRenderContext): ResolvedColumn[] {
  return ctx.columns.filter((c) => c.editable);
}

function renderBulkBar(
  parent: HTMLElement,
  ctx: ViewRenderContext,
  selectedRows: readonly Row[],
  selection: Set<string>,
  repaint: () => void,
): void {
  const columns = editableColumns(ctx);
  if (columns.length === 0) return;
  const draft = bulkDraftStore.get(ctx.viewKey) ?? { column: columns[0]?.name ?? "", value: "" };
  bulkDraftStore.set(ctx.viewKey, draft);
  capViewState(bulkDraftStore);
  if (!columns.some((c) => c.name === draft.column)) draft.column = columns[0]?.name ?? "";

  const bar = parent.createDiv({ cls: "kvs-bulk-bar" });

  const count = bar.createSpan({ cls: "kvs-bulk-count" });
  count.createSpan({ cls: "kvs-bulk-count-num", text: String(selectedRows.length) });
  count.appendText(selectedRows.length === 1 ? " selected" : " selected");

  const setGroup = bar.createDiv({ cls: "kvs-bulk-set" });
  setGroup.createSpan({ cls: "kvs-bulk-label", text: "Set" });

  const columnSelect = setGroup.createEl("select", { cls: "dropdown kvs-bulk-field" });
  for (const column of columns) {
    const option = columnSelect.createEl("option", { text: column.label, value: column.name });
    if (column.name === draft.column) option.selected = true;
  }

  const valueHost = setGroup.createSpan({ cls: "kvs-bulk-value" });
  const buildValueInput = (): void => {
    valueHost.empty();
    const column = columns.find((c) => c.name === draft.column);
    const options = column?.typeId === "select" ? column.options : undefined;
    if (options && options.length > 0) {
      const select = valueHost.createEl("select", { cls: "dropdown" });
      select.createEl("option", { text: "—", value: "" });
      for (const option of options) {
        const el = select.createEl("option", { text: option.label ?? option.value, value: option.value });
        if (option.value === draft.value) el.selected = true;
      }
      select.addEventListener("change", () => (draft.value = select.value));
    } else {
      const input = valueHost.createEl("input", { cls: "kvs-cell-input" });
      input.type = "text";
      input.value = draft.value;
      input.placeholder = "value";
      input.addEventListener("input", () => (draft.value = input.value));
    }
  };
  columnSelect.addEventListener("change", () => {
    draft.column = columnSelect.value;
    buildValueInput();
  });
  buildValueInput();

  const apply = setGroup.createEl("button", { cls: "mod-cta kvs-bulk-apply", text: "Apply" });
  apply.addEventListener("click", () => {
    ctx.onBulkEdit?.(selectedRows, draft.column, draft.value);
    selection.clear();
    draft.value = "";
  });
  const actions = bar.createDiv({ cls: "kvs-bulk-actions" });
  if (ctx.onCopyRows) {
    const copy = actions.createEl("button", { cls: "kvs-bulk-copy", text: "Copy" });
    setTooltip(copy, "Copy the selected rows (paste as a table in Obsidian, Word, or a spreadsheet)");
    copy.addEventListener("click", () => ctx.onCopyRows?.([...selectedRows]));

    const copyAs = actions.createEl("button", { cls: "kvs-bulk-copyas", text: "Copy as ▾" });
    setTooltip(copyAs, "Copy in a specific format");
    copyAs.addEventListener("click", (event) => {
      const menu = new Menu();
      for (const format of COPY_FORMATS) {
        menu.addItem((item) => item.setTitle(format.label).onClick(() => ctx.onCopyRows?.([...selectedRows], format.id)));
      }
      const opts = ctx.copyOptions;
      if (opts) {
        menu.addSeparator();
        menu.addItem((item) =>
          item.setTitle("Include header row").setChecked(opts.includeHeader).onClick(() => opts.onToggleHeader()),
        );
        menu.addItem((item) =>
          item.setTitle("Strip wikilinks").setChecked(opts.stripLinks).onClick(() => opts.onToggleStripLinks()),
        );
      }
      menu.showAtMouseEvent(event);
    });
  }
  if (ctx.onBulkDelete) {
    const del = actions.createEl("button", { cls: "kvs-bulk-delete mod-warning", text: "Delete" });
    setTooltip(del, "Delete the selected rows from their source notes (can be undone)");
    del.addEventListener("click", () => {
      const rows = [...selectedRows];
      selection.clear();
      ctx.onBulkDelete?.(rows);
    });
  }
  const clear = actions.createEl("button", { cls: "clickable-icon kvs-bulk-clear" });
  setIcon(clear, "x");
  setTooltip(clear, "Clear selection");
  clear.addEventListener("click", () => {
    selection.clear();
    repaint();
  });
}

function renderHeader(
  table: HTMLElement,
  ctx: ViewRenderContext,
  selection: Set<string> | null,
  visibleRows: readonly Row[],
  repaint: () => void,
  frozen: boolean,
): void {
  const headRow = table.createEl("thead").createEl("tr");
  headRow.setAttribute("aria-rowindex", "1"); // the header is ARIA row 1; data rows follow at 2+
  const activeSort = ctx.currentSort[0];

  // One combined leading column for selection + source. Its width drives the frozen-column offset.
  if (hasGutterColumn(ctx, Boolean(selection))) {
    const gutterW = effectiveGutterWidth(ctx, Boolean(selection));
    table.style.setProperty("--kvs-gutter-w", `${gutterW}px`);
    const th = headRow.createEl("th", { cls: "kvs-th kvs-gutter-cell" });
    th.style.width = `${gutterW}px`;
    th.setAttribute("aria-label", "Row tools");
    const inner = th.createDiv({ cls: "kvs-gutter" });
    if (selection) {
      const all = inner.createEl("input", { cls: "kvs-checkbox" });
      all.type = "checkbox";
      all.setAttribute("aria-label", "Select all rows");
      const allSelected = visibleRows.length > 0 && visibleRows.every((r) => selection.has(tokenOf(r)));
      all.checked = allSelected;
      all.addEventListener("change", () => {
        if (all.checked) for (const r of visibleRows) selection.add(tokenOf(r));
        else for (const r of visibleRows) selection.delete(tokenOf(r));
        repaint();
      });
    }
    if (ctx.onResizeColumn) {
      attachResizeHandle(th, table, 28, (w) => ctx.onResizeColumn?.(GUTTER_KEY, w), () => ctx.onResetColumnWidth?.(GUTTER_KEY));
    }
    attachHeaderMenu(th, ctx, GUTTER_KEY, true);
  }

  ctx.columns.forEach((column, columnIndex) => {
    const th = headRow.createEl("th", { cls: "kvs-th" });
    if (frozen && columnIndex === 0) th.addClass("kvs-frozen-col0");
    th.setAttribute("scope", "col");
    th.setAttribute("role", "columnheader");
    th.tabIndex = 0;
    const wide = ctx.profile.tableWidth === "wide";
    const effectiveWidth = column.width ?? (wide ? defaultWideWidth(column.typeId, column.role) : undefined);
    if (effectiveWidth !== undefined) th.style.width = `${effectiveWidth}px`;

    const isActive = activeSort?.field.toLowerCase() === column.name.toLowerCase();
    th.setAttribute("aria-sort", isActive ? (activeSort?.direction === "desc" ? "descending" : "ascending") : "none");
    th.setAttribute("aria-label", `${column.label}, sort`);

    const label = th.createDiv({ cls: "kvs-th-label" });
    label.createSpan({ text: column.label });
    if (isActive) label.createSpan({ cls: "kvs-sort-indicator", text: activeSort?.direction === "desc" ? " ↓" : " ↑" });

    const sort = (): void => {
      const nextDirection: "asc" | "desc" = isActive && activeSort?.direction === "asc" ? "desc" : "asc";
      ctx.onSortChange([{ field: column.name, direction: nextDirection }]);
    };
    ctx.component.registerDomEvent(th, "click", sort);
    ctx.component.registerDomEvent(th, "keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        sort();
      }
    });

    if (ctx.onResizeColumn) {
      attachResizeHandle(th, table, 60, (w) => ctx.onResizeColumn?.(column.name, w), () => ctx.onResetColumnWidth?.(column.name));
    }
    attachHeaderMenu(th, ctx, column.name, false);
  });
}

function spacerRow(tbody: HTMLElement, height: number, span: number): HTMLElement {
  const tr = tbody.createEl("tr", { cls: "kvs-vspacer" });
  tr.style.height = `${height}px`;
  tr.createEl("td", { attr: { colspan: String(span) } });
  return tr;
}

/** Initial per-row height guess before a row has been measured. */

function estimateRowHeight(rowHeight: string): number {
  if (rowHeight === "compact") return 34;
  if (rowHeight === "comfortable") return 60;
  return 44;
}

/**
 * Freeze the current (content-fitted) column widths and switch to fixed layout,
 * so which rows are on screen no longer affects column widths — a prerequisite
 * for wrapping heights to stay stable while virtualizing. Returns false (locking
 * nothing) if the table has not been laid out yet, so the caller can retry.
 */
function lockColumnWidths(table: HTMLElement): boolean {
  const ths = Array.from(table.querySelectorAll("thead th"));
  const widths = ths.map((th) => (th as HTMLElement).getBoundingClientRect().width);
  if (widths.every((w) => !w || w <= 0)) return false;
  ths.forEach((th, i) => {
    const w = widths[i];
    if (w && w > 0) (th as HTMLElement).style.width = `${Math.round(w)}px`;
  });
  table.addClass("kvs-fixed");
  return true;
}

type TableItem = { kind: "header"; key: string; count: number } | { kind: "row"; row: Row };

/** Flatten the result into a single ordered item list (group headers + rows). */
function buildTableItems(ctx: ViewRenderContext): TableItem[] {
  const groups = ctx.result.groups;
  if (groups) {
    const items: TableItem[] = [];
    for (const group of groups) {
      items.push({ kind: "header", key: group.key, count: group.rows.length });
      for (const row of group.rows) items.push({ kind: "row", row });
    }
    return items;
  }
  return ctx.result.rows.map((row) => ({ kind: "row", row }));
}

function renderItem(
  tbody: HTMLElement,
  item: TableItem,
  ctx: ViewRenderContext,
  selection: Set<string> | null,
  repaint: () => void,
  span: number,
  frozen: boolean,
  ariaRowIndex?: number,
): void {
  if (item.kind === "header") {
    const tr = tbody.createEl("tr", { cls: "kvs-group-row" });
    if (ariaRowIndex !== undefined) tr.setAttribute("aria-rowindex", String(ariaRowIndex));
    const cell = tr.createEl("td", { cls: "kvs-group-cell", attr: { colspan: String(span) } });
    cell.createSpan({ cls: "kvs-group-key", text: item.key });
    cell.createSpan({ cls: "kvs-group-count", text: ` · ${item.count}` });
  } else {
    renderBodyRow(tbody, item.row, ctx, selection, repaint, frozen, ariaRowIndex);
  }
}

function renderTable(ctx: ViewRenderContext): void {
  const { container, result } = ctx;
  activeCellEdit?.(); // never let a repaint silently drop an open edit
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-table-view" });
  const repaint = (): void => renderTable(ctx);

  const selectionEnabled = Boolean(ctx.onBulkEdit) && ctx.profile.rowSelection;
  let selection: Set<string> | null = null;
  if (selectionEnabled) {
    selection = selectionStore.get(ctx.viewKey) ?? new Set<string>();
    selectionStore.set(ctx.viewKey, selection);
    capViewState(selectionStore);
  }

  const allRows = flatten(ctx);

  // Cmd/Ctrl+C copies the selected rows — but only when the feature is on, the table (not a text
  // field) is focused, and there's no active text selection, so ordinary copy is never hijacked.
  // Bound on the freshly-created root, which is discarded on the next render, so it never leaks.
  if (ctx.onCopyRows && ctx.copyOnShortcut && selection) {
    root.addEventListener("keydown", (event) => {
      if (!(event.metaKey || event.ctrlKey) || event.shiftKey || event.altKey) return;
      if (event.key.toLowerCase() !== "c") return;
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, [contenteditable='true']")) return;
      const textSel = window.getSelection();
      if (textSel && !textSel.isCollapsed && textSel.toString().trim() !== "") return;
      const rows = allRows.filter((r) => selection.has(tokenOf(r)));
      if (rows.length === 0) return;
      event.preventDefault();
      ctx.onCopyRows?.(rows);
    });
  }

  const toolbar = root.createDiv({ cls: "kvs-toolbar" });
  toolbar.createSpan({ cls: "kvs-count", text: `${result.total} ${result.total === 1 ? "row" : "rows"}` });
  if (ctx.onAddRowTop) {
    const add = toolbar.createEl("button", { cls: "kvs-toolbar-add" });
    setIcon(add.createSpan({ cls: "kvs-toolbar-add-ic" }), "plus");
    add.appendText("Add row");
    add.addEventListener("click", () => ctx.onAddRowTop?.());
  }

  if (result.total === 0) {
    renderEmptyState(root, ctx);
    return;
  }

  const bulkBarHost = root.createDiv({ cls: "kvs-bulk-host" });
  const refreshSelectionUI = (): void => {
    if (!selection) return;
    const sel = allRows.filter((r) => selection.has(tokenOf(r)));
    bulkBarHost.empty();
    if (sel.length > 0) renderBulkBar(bulkBarHost, ctx, sel, selection, repaint);
  };
  refreshSelectionUI();

  const items = buildTableItems(ctx);
  // Rows above the threshold are windowed. Heights are measured per row, so
  // wrapping (normal/comfortable) virtualizes just as well as single-line compact.
  const virtualize = items.length > VIRTUAL_THRESHOLD;
  const hasWidth = ctx.profile.tableWidth === "wide" || ctx.columns.some((c) => c.width !== undefined);
  const frozen = ctx.profile.frozenFirstColumn;
  const frozenHeader = ctx.profile.frozenHeader;

  const scrollCls = virtualize
    ? "kvs-table-scroll kvs-virtual-scroll"
    : frozenHeader
      ? "kvs-table-scroll kvs-frozen-header-scroll"
      : "kvs-table-scroll";
  const scroll = root.createDiv({ cls: scrollCls });
  const tableCls = ["kvs-table", `kvs-rows-${ctx.profile.rowHeight}`, hasWidth ? "kvs-fixed" : "", virtualize ? "kvs-virtual" : "", frozen ? "kvs-frozen-cols" : "", selection ? "kvs-has-select" : "", ctx.profile.tableWidth === "wide" ? "kvs-wide" : ""]
    .filter(Boolean)
    .join(" ");
  const table = scroll.createEl("table", { cls: tableCls });
  // With virtualization the DOM holds only a slice of rows, so a screen reader counting <tr>s would
  // report the wrong total ("row 5 of 8" when there are 500). aria-rowcount states the real total, and
  // each row carries its true aria-rowindex below — so assistive tech navigates the whole grid, not the
  // window. +1 for the header row, which counts in the ARIA row numbering.
  // Every item becomes a row with an aria-rowindex (data rows and group-header rows alike), so the count
  // must include both — plus 1 for the column header. Counting only data rows here would let an index
  // exceed the stated total whenever groups are present, which is an ARIA contradiction.
  table.setAttribute("role", "grid");
  table.setAttribute("aria-rowcount", String(items.length + 1));
  renderHeader(table, ctx, selection, allRows, repaint, frozen);

  const tbody = table.createEl("tbody");
  const span = ctx.columns.length + (selection ? 1 : 0) + 1;
  renderSummaryFooter(table, ctx, allRows, selection !== null);

  if (!virtualize) {
    items.forEach((item, i) => renderItem(tbody, item, ctx, selection, refreshSelectionUI, span, frozen, i + 2));
    scroll.addEventListener("scroll", () => {
      scrollStore.set(ctx.viewKey, scroll.scrollTop);
      capViewState(scrollStore);
    });
    window.requestAnimationFrame(() => {
      const saved = scrollStore.get(ctx.viewKey) ?? 0;
      if (saved > 0) scroll.scrollTop = saved;
    });
    return;
  }

  // ---- Variable-height windowed virtualization (rows + group headers) ----
  const estimate = estimateRowHeight(ctx.profile.rowHeight);
  const heights = new Array<number>(items.length).fill(estimate);
  let prefix = buildPrefix(heights);
  let widthsLocked = hasWidth; // configured widths are already stable
  let lastStart = -1;
  let lastEnd = -1;
  let suppressScroll = false;

  // Rendered rows are kept between frames and recycled: on scroll, rows that stay in view are
  // reused as-is (no re-render, no Markdown re-parse, no re-measure) and only the handful of
  // rows entering the viewport are built. Row order is enforced by a single replaceChildren.
  const rendered = new Map<number, HTMLElement>();
  let topSpacer: HTMLElement | null = null;
  let botSpacer: HTMLElement | null = null;

  const renderWindow = (force: boolean): void => {
    activeCellEdit?.(); // commit an open edit before this window is touched
    const scrollTop = scroll.scrollTop;
    const viewport = scroll.clientHeight || 600;
    const anchorIndex = findRowAt(prefix, scrollTop);
    const { start, end } = computeWindow(prefix, scrollTop, viewport, OVERSCAN);
    if (!force && start === lastStart && end === lastEnd) return;
    lastStart = start;
    lastEnd = end;

    if (force) {
      rendered.clear();
      tbody.empty();
      topSpacer = null;
      botSpacer = null;
    }

    // Drop rows that scrolled out of the window.
    for (const [idx, el] of [...rendered]) {
      if (idx < start || idx >= end) {
        el.remove();
        rendered.delete(idx);
      }
    }
    if (!topSpacer) topSpacer = spacerRow(tbody, 0, span);
    if (!botSpacer) botSpacer = spacerRow(tbody, 0, span);

    // Build only rows that are newly visible (appended here; ordered by replaceChildren below).
    const newRows: { index: number; el: HTMLElement }[] = [];
    for (let i = start; i < end; i++) {
      if (rendered.has(i)) continue;
      const item = items[i];
      if (!item) continue;
      renderItem(tbody, item, ctx, selection, refreshSelectionUI, span, frozen, i + 2);
      const el = tbody.lastElementChild;
      if (el instanceof HTMLElement) {
        rendered.set(i, el);
        newRows.push({ index: i, el });
      }
    }

    // Put the tbody in order: [top spacer, rows start..end-1, bottom spacer]. Existing nodes are
    // moved (not re-created), so reused rows keep their DOM, listeners and rendered content.
    const total = totalHeight(prefix);
    const topH = prefix[start] ?? 0;
    const botH = total - (prefix[end] ?? total);
    topSpacer.style.height = `${topH}px`;
    botSpacer.style.height = `${botH}px`;
    const ordered: HTMLElement[] = [];
    if (topH > 0) ordered.push(topSpacer);
    for (let i = start; i < end; i++) {
      const el = rendered.get(i);
      if (el) ordered.push(el);
    }
    if (botH > 0) ordered.push(botSpacer);
    tbody.replaceChildren(...ordered);

    // Lock widths from the first real content render, then measure under fixed layout.
    if (!widthsLocked && rendered.size > 0) {
      widthsLocked = lockColumnWidths(table);
    }

    // Measure only the rows we just built; reused rows already have known heights.
    const oldPrefix = prefix;
    let changed = false;
    for (const { index, el } of newRows) {
      const h = el.getBoundingClientRect().height;
      if (h > 0 && Math.abs((heights[index] ?? estimate) - h) > 0.5) {
        heights[index] = h;
        changed = true;
      }
    }
    if (changed) {
      prefix = buildPrefix(heights);
      const newTotal = totalHeight(prefix);
      topSpacer.style.height = `${prefix[start] ?? 0}px`;
      botSpacer.style.height = `${newTotal - (prefix[end] ?? newTotal)}px`;
      const shift = anchorShift(oldPrefix, prefix, anchorIndex);
      if (Math.abs(shift) > 0.5) {
        suppressScroll = true;
        scroll.scrollTop = scrollTop + shift;
      }
    }
  };

  let scheduled = false;
  scroll.addEventListener("scroll", () => {
    scrollStore.set(ctx.viewKey, scroll.scrollTop);
    capViewState(scrollStore);
    if (suppressScroll) {
      suppressScroll = false;
      return;
    }
    if (scheduled) return;
    scheduled = true;
    window.requestAnimationFrame(() => {
      scheduled = false;
      renderWindow(false);
    });
  });

  renderWindow(true);
  window.requestAnimationFrame(() => {
    // Correct the window once layout is known (real viewport height) and restore scroll. This
    // recycles rather than force-rebuilding, so it's a no-op when the window hasn't changed.
    const saved = scrollStore.get(ctx.viewKey) ?? 0;
    if (saved > 0) scroll.scrollTop = saved;
    renderWindow(false);
  });
}

export const tableView: KnowledgeView = {
  type: "table",
  label: "Table",
  icon: "table",
  render: renderTable,
};

/**
 * The summary footer: one aggregation per column, over the rows currently shown.
 *
 * It deliberately summarises the *filtered* rows rather than the whole vault, because the question
 * anyone actually has is "what does what I am looking at add up to?" Every column offers the choice;
 * the footer only appears once at least one column has made one, so a plain table stays plain.
 */
function renderSummaryFooter(
  table: HTMLElement,
  ctx: ViewRenderContext,
  rows: readonly Row[],
  selection: boolean,
): void {
  const editable = ctx.onSetColumnSummary !== undefined;
  const anyChosen = ctx.columns.some((c) => c.summary && c.summary !== "none");
  if (ctx.profile.showSummaryRow === false) return; // turned off for this view
  if (!anyChosen && !editable) return;

  const tr = table.createEl("tfoot", { cls: "kvs-tfoot" }).createEl("tr", { cls: "kvs-summary-row" });
  // Exactly one combined leading gutter cell when the header/body have one — same condition as renderHeader
  // and renderGutterCell — so every summary lines up under its own column. (Previously this emitted one or
  // two cells keyed only on selection, which shifted every value one column over and dropped the last
  // column's summary — the "summary row shows nothing / can't click" bug.)
  if (hasGutterColumn(ctx, selection)) {
    tr.createEl("td", { cls: "kvs-summary-cell kvs-gutter" });
  }

  for (const column of ctx.columns) {
    const td = tr.createEl("td", { cls: "kvs-summary-cell" });
    const fn = (column.summary ?? "none") as SummaryFn;
    const value = summarizeColumn(rows, column, fn);
    const label = SUMMARY_FUNCTIONS.find((f) => f.id === fn)?.label ?? "";

    const btn = td.createDiv({ cls: "kvs-summary-btn" });
    if (fn === "none" || value === "") {
      btn.addClass("is-empty");
      btn.setText(editable ? "\u2014" : "");
      if (editable) setTooltip(btn, `Summarise ${column.label}`);
    } else {
      btn.createSpan({ cls: "kvs-summary-label", text: label });
      btn.createSpan({ cls: "kvs-summary-value", text: value });
      setTooltip(btn, `${label} of ${column.label}, over the ${rows.length} row${rows.length === 1 ? "" : "s"} shown`);
    }
    if (!editable) continue;

    btn.addEventListener("click", (event) => {
      const menu = new Menu();
      for (const f of SUMMARY_FUNCTIONS) {
        menu.addItem((item) =>
          item
            .setTitle(f.label)
            .setChecked(f.id === fn)
            .onClick(() => ctx.onSetColumnSummary?.(column.name, f.id)),
        );
      }
      menu.showAtMouseEvent(event);
    });
  }
}
