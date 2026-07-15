import { Menu, setIcon, setTooltip } from "obsidian";
import {
  dropTargetAt,
  enableLongPressDrag,
  hideDragGhost,
  moveDragGhost,
  showDragGhost,
} from "../../util/pointer-drag";
import { findColumnByRole } from "../view-model";
import { getField, isVirtualField, type Row,
  limitRows,
  moreLabel,
} from "../../domain/index";
import type { ResolvedColumn } from "../view-model";
import type { KnowledgeView, ViewRenderContext } from "../view";
import { optString } from "../view-options";
import { openSourceNote } from "../open-source";
import { buildKanbanBoard, type KanbanColumn } from "./board";

const KANBAN_VIRTUAL_THRESHOLD = 50;
const KANBAN_CARD_H = 120;
const KANBAN_OVERSCAN = 5;

/**
 * Moving a card *is* writing a value: dropping it on the "Done" column sets that row's status field to
 * "Done" and writes it back to the note. There are now three ways to say that — drag it, right-click it,
 * tap its menu — so the controller owns the meaning once, and the gestures merely call it.
 */
class KanbanDrag {
  private row: Row | null = null;
  private hovered: HTMLElement | null = null;

  constructor(
    private readonly field: string,
    private readonly ctx: ViewRenderContext,
    private readonly columns: readonly KanbanColumn[],
  ) {}

  begin(row: Row): void {
    this.row = row;
  }

  hover(list: HTMLElement | null): void {
    if (list === this.hovered) return;
    this.hovered?.removeClass("kvs-drop-target");
    this.hovered = list;
    list?.addClass("kvs-drop-target");
  }

  drop(list: HTMLElement | null): void {
    const row = this.row;
    const key = list?.dataset.kvsColumn;
    this.cancel();
    if (!row || key === undefined) return;
    this.move(row, key);
  }

  cancel(): void {
    this.hovered?.removeClass("kvs-drop-target");
    this.hovered = null;
    this.row = null;
  }

  openMoveMenu(row: Row, x: number, y: number): void {
    const current = getField(row, this.field).trim();
    const menu = new Menu();
    for (const column of this.columns) {
      menu.addItem((item) =>
        item
          .setTitle(column.label)
          .setChecked(column.key === current)
          .onClick(() => this.move(row, column.key)),
      );
    }
    menu.showAtPosition({ x, y });
  }

  private move(row: Row, key: string): void {
    if (getField(row, this.field).trim() === key) return; // no-op move: don't dirty the note for nothing
    this.ctx.onEditCell?.(row, this.field, key);
  }
}

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
  draggable: boolean,
  ctx: ViewRenderContext,
  drag: KanbanDrag,
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

  if (!draggable || row.provenance.extractor !== "table") return;
  card.addClass("kvs-draggable");

  // Dragging is the gesture people reach for, so it stays the headline — but it is also the gesture that
  // a keyboard cannot perform and a screen reader cannot see. The menu is the same move, spelled out:
  // it works with a right-click, a tap, or the keyboard, and it is the reason the board is not
  // drag-or-nothing.
  const menuBtn = card.createEl("button", { cls: "kvs-kanban-card-menu", attr: { "aria-label": "Move this card" } });
  setIcon(menuBtn, "ellipsis-vertical");
  setTooltip(menuBtn, "Move to…");
  const openMenu = (x: number, y: number): void => drag.openMoveMenu(row, x, y);
  menuBtn.addEventListener("click", (event) => {
    event.stopPropagation();
    const box = menuBtn.getBoundingClientRect();
    openMenu(box.left, box.bottom);
  });
  card.addEventListener("contextmenu", (event) => {
    event.preventDefault();
    openMenu(event.clientX, event.clientY);
  });

  enableLongPressDrag(card, {
    onStart: (x, y) => {
      drag.begin(row);
      showDragGhost(card, x, y);
    },
    onMove: (x, y) => {
      moveDragGhost(x, y);
      drag.hover(dropTargetAt(x, y, ".kvs-kanban-list"));
    },
    onDrop: (x, y) => {
      hideDragGhost();
      drag.drop(dropTargetAt(x, y, ".kvs-kanban-list"));
    },
    onCancel: () => {
      hideDragGhost();
      drag.cancel();
    },
  });
}

function renderColumn(
  board: HTMLElement,
  column: KanbanColumn,
  titleColumn: ResolvedColumn | undefined,
  secondary: readonly ResolvedColumn[],
  draggable: boolean,
  ctx: ViewRenderContext,
  drag: KanbanDrag,
): void {
  const col = board.createDiv({ cls: "kvs-kanban-column" });
  const header = col.createDiv({ cls: "kvs-kanban-column-header" });
  header.createSpan({ cls: "kvs-kanban-column-title", text: column.label });
  header.createSpan({ cls: "kvs-kanban-column-count", text: String(column.rows.length) });

  const list = col.createDiv({ cls: "kvs-kanban-list" });
  // The drop target is found by hit-testing the pointer, not by a listener per list, so the list has to
  // be able to say which column it *is*.
  list.dataset.kvsColumn = column.key;
  // Draw only the first N cards of a column, with an honest "Show N more". The header count below
  // still reports the true total -- a column that says 12 when it holds 500 would be lying.
  const limited = limitRows(column.rows, ctx.profile.groupLimit);
  const rows = limited.rows;
  const renderOne = (row: Row): void => renderCard(list, row, titleColumn, secondary, draggable, ctx, drag);

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
      window.requestAnimationFrame(() => {
        scheduled = false;
        renderWindow();
      });
    });
    renderWindow();
    window.requestAnimationFrame(renderWindow);
  } else {
    for (const row of rows) renderOne(row);
  }

  if (limited.hidden > 0) {
    const more = col.createEl("button", { cls: "kvs-show-more", text: moreLabel(limited.hidden) });
    more.addEventListener("click", () => {
      more.remove();
      for (const row of column.rows.slice(rows.length)) renderOne(row);
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
  const drag = new KanbanDrag(field, ctx, board.columns);
  for (const column of board.columns) {
    renderColumn(lane, column, titleColumn, secondary, draggable, ctx, drag);
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
