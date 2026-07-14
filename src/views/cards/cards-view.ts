import { getField, type Row,
  limitRows,
  moreLabel,
} from "../../domain/index";
import { renderEmptyState } from "../empty-state";
import { findColumnByRole, type ResolvedColumn } from "../view-model";
import { openRowDetail } from "../row-detail-modal";
import type { KnowledgeView, ViewRenderContext } from "../view";

function pickTitleColumn(columns: readonly ResolvedColumn[]): ResolvedColumn | undefined {
  return findColumnByRole(columns, "title") ?? columns.find((c) => c.typeId === "link") ?? columns[0];
}

function renderCard(grid: HTMLElement, row: Row, ctx: ViewRenderContext): void {
  const card = grid.createDiv({ cls: "kvs-card kvs-card-clickable" });
  card.addEventListener("click", (event) => {
    if ((event.target as HTMLElement).closest("a")) return;
    openRowDetail(ctx, row);
  });
  const title = pickTitleColumn(ctx.columns);
  const bodyColumns = ctx.columns.filter((c) => c !== title);

  if (title) {
    const header = card.createDiv({ cls: "kvs-card-title" });
    const renderer = ctx.cellRenderers.get(title.typeId);
    const value = getField(row, title.name);
    if (renderer) {
      renderer.render({ el: header, value, column: title, app: ctx.app, sourcePath: ctx.sourcePath, component: ctx.component });
    } else {
      header.setText(value);
    }
  }

  for (const column of bodyColumns) {
    const value = getField(row, column.name);
    if (value.trim() === "") continue;
    const field = card.createDiv({ cls: "kvs-card-field" });
    field.createSpan({ cls: "kvs-card-label", text: column.label });
    const valueEl = field.createSpan({ cls: "kvs-card-value" });
    const renderer = ctx.cellRenderers.get(column.typeId);
    if (renderer) {
      renderer.render({ el: valueEl, value, column, app: ctx.app, sourcePath: ctx.sourcePath, component: ctx.component });
    } else {
      valueEl.setText(value);
    }
  }
}

function renderCards(ctx: ViewRenderContext): void {
  const { container, result } = ctx;
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-cards-view" });

  const toolbar = root.createDiv({ cls: "kvs-toolbar" });
  toolbar.createSpan({
    cls: "kvs-count",
    text: `${result.total} ${result.total === 1 ? "card" : "cards"}`,
  });

  if (result.total === 0) {
    renderEmptyState(root, ctx, "cards");
    return;
  }

  if (result.groups) {
    for (const group of result.groups) {
      const section = root.createDiv({ cls: "kvs-cards-group" });
      const heading = section.createDiv({ cls: "kvs-cards-group-header" });
      heading.createSpan({ cls: "kvs-group-key", text: group.key });
      // The count is always the true one, even when only a few cards are drawn.
      heading.createSpan({ cls: "kvs-group-count", text: ` · ${group.rows.length}` });
      const grid = section.createDiv({ cls: "kvs-cards-grid" });
      const drawGroup = (expanded: boolean): void => {
        grid.empty();
        const { rows, hidden } = limitRows(group.rows, ctx.profile.groupLimit, expanded);
        for (const row of rows) renderCard(grid, row, ctx);
        if (hidden > 0) {
          const more = section.createEl("button", { cls: "kvs-show-more", text: moreLabel(hidden) });
          more.addEventListener("click", () => {
            more.remove();
            drawGroup(true);
          });
        }
      };
      drawGroup(false);
    }
  } else {
    const grid = root.createDiv({ cls: "kvs-cards-grid" });
    for (const row of result.rows) renderCard(grid, row, ctx);
  }
}

export const cardsView: KnowledgeView = {
  type: "cards",
  label: "Cards",
  icon: "layout-grid",
  render: renderCards,
};
