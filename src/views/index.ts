import { ViewRegistry } from "./registry";
import { tableView } from "./table/table-view";
import { cardsView } from "./cards/cards-view";
import { kanbanView } from "./kanban/kanban-view";
import { calendarView } from "./calendar/calendar-view";
import { pivotView } from "./pivot/pivot-view";
import { galleryView } from "./gallery/gallery-view";
import { chartView } from "./chart/chart-view";

export * from "./view";
export * from "./view-model";
export * from "./empty-state";
export * from "./copy/row-copy";
export * from "./view-options";
export * from "./registry";
export * from "./cells/cell-renderer";
export * from "./cells/default-renderers";
export * from "./cells/cell-editor";
export * from "./cells/default-editors";
export * from "./editing";
export * from "./render-profile";
export { buildKanbanBoard } from "./kanban/board";
export { buildPivot, aggregate } from "./pivot/pivot";
export { buildCalendarMonth } from "./calendar/calendar";

/** A registry preloaded with the built-in views (table is the fallback). */
export function createDefaultViewRegistry(): ViewRegistry {
  const registry = new ViewRegistry();
  registry.register(tableView, true);
  registry.register(cardsView);
  registry.register(kanbanView);
  registry.register(calendarView);
  registry.register(pivotView);
  registry.register(galleryView);
  registry.register(chartView);
  return registry;
}
export * from "./view-state";
export { openRowDetail } from "./row-detail-modal";
export { noteLinkColumnName, wikilinkTarget } from "./promoted-detect";
