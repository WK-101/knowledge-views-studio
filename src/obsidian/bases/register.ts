import type { Plugin } from "obsidian";
import { kanbanView } from "../../views/kanban/kanban-view";
import { calendarView } from "../../views/calendar/calendar-view";
import { pivotView } from "../../views/pivot/pivot-view";
import type { KnowledgeView } from "../../views/index";
import { KvsBasesView, basesOptionsFor, basesViewId, type BasesViewDeps } from "./kvs-bases-view";

/** The KVS views worth lending to Bases — the ones its built-ins under-serve. */
const EXPOSED_VIEWS: readonly KnowledgeView[] = [kanbanView, calendarView, pivotView];

/**
 * Register KVS's Board, Calendar, and Summary views as custom Bases view types.
 * Returns the number registered. Degrades gracefully to 0 on older Obsidian
 * without the Bases API, or when Bases is disabled in the vault (the API returns
 * false per view), so this is safe to call unconditionally on load.
 */
export function registerKvsBasesViews(plugin: Plugin, deps: BasesViewDeps): number {
  if (typeof plugin.registerBasesView !== "function") return 0;
  let registered = 0;
  for (const view of EXPOSED_VIEWS) {
    const ok = plugin.registerBasesView(basesViewId(view), {
      name: `KVS ${view.label}`,
      icon: view.icon ?? "layout-grid",
      factory: (controller, containerEl) => new KvsBasesView(controller, containerEl, view, deps),
      options: () => basesOptionsFor(view),
    });
    if (ok) registered += 1;
  }
  return registered;
}
