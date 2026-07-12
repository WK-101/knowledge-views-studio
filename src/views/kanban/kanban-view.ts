import { setTooltip } from "obsidian";
import { findColumnByRole } from "../view-model";
import { getField, isVirtualField, type Row } from "../../domain/index";
import type { ResolvedColumn } from "../view-model";
import type { KnowledgeView, ViewRenderContext } from "../view";
import { optString } from "../view-options";
import { openSourceNote } from "../open-source";
import { buildKanbanBoard, type KanbanColumn } from "./board";

const KANBAN_VIRTUAL_THRESHOLD = 50;
const KANBAN_CARD_H = 120;
const KANBAN_OVERSCAN = 5;

function paintValue(el: HTMLElement, value: string, column: ResolvedColumn, ctx: ViewRenderContext): void {
  const renderer = ctx.cellRenderers.get(column.typeId);
  if (renderer) {
    renderer.render({ el, value, column, app: ctx.app, sourcePath: ctx.sourcePath, component: ctx.component });
  } else {
    el.setText(value);
  }
}

function renderCard(
  list: HTMLElement,
  row: Row,
  titleColumn: ResolvedColumn | undefined,
  secondary: readonly ResolvedColumn[],
  field: string,
  draggable: boolean,
  ctx: ViewRenderContext,
  onDragStart: (row: Row) => void,
): void {
  const card = list.createDiv({ cls: "kvs-kanban-card" });

  const title = card.createDiv({ cls: "kvs-kanban-card-title kvs-kanban-card-open" });
  if (titleColumn) paintValue(title, getField(row, titleColumn.name), titleColumn, ctx);
  else title.setText(row.file.fileName);
  setTooltip(title, `Open ${row.file.fileName}`);
  title.addEventListener("click", () => openSourceNote(ctx.app, row.provenance.filePath, ctx.sourcePath));

  for (const column of secondary) {
    const value = getField(row, column.name);
    if (value.trim() === "") continue;
    const line = card.createDiv({ cls: "kvs-kanban-card-field" });
    line.createSpan({ cls: "kvs-kanban-card-label", text: `${column.label}: ` });
    paintValue(line.createSpan({ cls: "kvs-kanban-card-value" }), value, column, ctx);
  }

  if (draggable && row.provenance.extractor === "table") {
    card.draggable = true;
    card.addClass("kvs-draggable");
    card.addEventListener("dragstart", (event) => {
      onDragStart(row);
      event.dataTransfer?.setData("text/plain", getField(row, field));
      if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
    });
  }
}

function renderColumn(
  board: HTMLElement,
  column: KanbanColumn,
  field: string,
  titleColumn: ResolvedColumn | undefined,
  secondary: readonly ResolvedColumn[],
  draggable: boolean,
  ctx: ViewRenderContext,
  getDragged: () => Row | null,
  onDragStart: (row: Row) => void,
  clearDragged: () => void,
): void {
  const col = board.createDiv({ cls: "kvs-kanban-column" });
  const header = col.createDiv({ cls: "kvs-kanban-column-header" });
  header.createSpan({ cls: "kvs-kanban-column-title", text: column.label });
  header.createSpan({ cls: "kvs-kanban-column-count", text: String(column.rows.length) });

  const list = col.createDiv({ cls: "kvs-kanban-list" });
  const rows = column.rows;
  const renderOne = (row: Row): void =>
    renderCard(list, row, titleColumn, secondary, field, draggable, ctx, onDragStart);

  if (rows.length > KANBAN_VIRTUAL_THRESHOLD) {
    // Windowed virtualization for very tall columns (fixed card height while virtual).
    list.addClass("kvs-kanban-list-virtual");
    const spacer = (height: number): void => {
      list.createDiv({ cls: "kvs-kanban-vspacer" }).style.height = `${height}px`;
    };
    const renderWindow = (): void => {
      const viewport = list.clientHeight || 480;
      const total = rows.length;
      const visible = Math.ceil(viewport / KANBAN_CARD_H) + KANBAN_OVERSCAN * 2;
      const maxStart = Math.max(0, total - visible);
      const start = Math.min(maxStart, Math.max(0, Math.floor(list.scrollTop / KANBAN_CARD_H) - KANBAN_OVERSCAN));
      const end = Math.min(total, start + visible);
      list.empty();
      if (start > 0) spacer(start * KANBAN_CARD_H);
      for (let i = start; i < end; i++) {
        const row = rows[i];
        if (row) renderOne(row);
      }
      if (end < total) spacer((total - end) * KANBAN_CARD_H);
    };
    let scheduled = false;
    list.addEventListener("scroll", () => {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        renderWindow();
      });
    });
    renderWindow();
    requestAnimationFrame(renderWindow);
  } else {
    for (const row of rows) renderOne(row);
  }

  if (draggable) {
    list.addEventListener("dragover", (event) => {
      event.preventDefault();
      list.addClass("kvs-drop-target");
    });
    list.addEventListener("dragleave", () => list.removeClass("kvs-drop-target"));
    list.addEventListener("drop", (event) => {
      event.preventDefault();
      list.removeClass("kvs-drop-target");
      const row = getDragged();
      clearDragged();
      if (!row) return;
      if (getField(row, field).trim() === column.key) return; // no-op move
      ctx.onEditCell?.(row, field, column.key);
    });
  }
}

function renderKanban(ctx: ViewRenderContext): void {
  const { container, profile, result, columns } = ctx;
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-kanban-view" });

  let field = optString(profile.view.options, "groupField");
  if (field === "") {
    field = findColumnByRole(columns, "status")?.name ?? findColumnByRole(columns, "priority")?.name ?? "";
  }
  if (field === "") {
    root.createDiv({ cls: "kvs-empty", text: "Choose a “Group by” field in this view's settings to build a board." });
    return;
  }

  // Column order from a matching select column's options, if configured.
  const groupColumn = profile.columns.find((c) => c.name.toLowerCase() === field.toLowerCase());
  const order = groupColumn?.type === "select" ? groupColumn.options?.map((o) => o.value) : undefined;
  const board = buildKanbanBoard(result.rows, field, order ? { order } : {});

  const visibleColumns = columns.filter((c) => c.name.toLowerCase() !== field.toLowerCase());
  const titleColumn = findColumnByRole(visibleColumns, "title") ?? visibleColumns[0];
  const secondary = visibleColumns.slice(1, 4);
  const draggable = Boolean(ctx.onEditCell) && !isVirtualField(field);

  const lane = root.createDiv({ cls: "kvs-kanban-board" });
  let dragged: Row | null = null;
  for (const column of board.columns) {
    renderColumn(
      lane,
      column,
      field,
      titleColumn,
      secondary,
      draggable,
      ctx,
      () => dragged,
      (row) => (dragged = row),
      () => (dragged = null),
    );
  }
}

export const kanbanView: KnowledgeView = {
  type: "kanban",
  label: "Board",
  paginates: false,
  icon: "layout-grid",
  optionSpecs: [
    {
      key: "groupField",
      label: "Group by",
      kind: "field",
      description: "The field whose values become board columns. Drag cards to write the new value back.",
    },
  ],
  render: renderKanban,
};
