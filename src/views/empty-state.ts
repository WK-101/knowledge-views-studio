import type { ViewRenderContext } from "./view";

const EXAMPLE_TABLE = [
  "| Task | Status | Due |",
  "| --- | --- | --- |",
  "| Write intro | Doing | 2025-02-01 |",
  "| Ship v1 | Todo | 2025-02-14 |",
].join("\n");

/**
 * A self-diagnosing empty state. Instead of a dead-end "nothing here", it uses the gathered-row
 * count and whether a filter is active to explain *why* the view is empty and offer a one-tap fix:
 *  • rows exist but are filtered/excluded  → Clear filters / Edit view
 *  • nothing found in scope                → show a copyable example table + Change folder
 * Remedy buttons appear only when the host provides `ctx.emptyState` (the dashboard); read-only
 * hosts still get the explanatory text.
 */
export function renderEmptyState(root: HTMLElement, ctx: ViewRenderContext, noun = "rows"): void {
  const box = root.createDiv({ cls: "kvs-empty kvs-empty-card" });
  const es = ctx.emptyState;
  const gathered = ctx.result.gathered;

  if (gathered > 0) {
    const one = gathered === 1;
    box.createDiv({
      cls: "kvs-empty-title",
      text: `${gathered} ${one ? "row was" : "rows were"} found, but ${one ? "it doesn’t" : "none"} match this view.`,
    });
    box.createDiv({
      cls: "kvs-empty-desc",
      text: es?.hasFilter
        ? "A filter or advanced query is hiding them."
        : "The view’s column matching is excluding them — try loosening it in the view settings.",
    });
    if (es) {
      const actions = box.createDiv({ cls: "kvs-empty-actions" });
      if (es.hasFilter) {
        const clear = actions.createEl("button", { cls: "mod-cta", text: "Clear filters" });
        clear.addEventListener("click", () => es.onClearFilters());
      }
      const edit = actions.createEl("button", { cls: "kvs-tb-btn", text: "Edit view" });
      edit.addEventListener("click", () => es.onOpenSettings());
    }
    return;
  }

  // Nothing gathered from the source — teach how to create some.
  box.createDiv({ cls: "kvs-empty-title", text: `No ${noun} yet` });
  box.createDiv({
    cls: "kvs-empty-desc",
    text: `This view collects table rows from notes in ${es?.scopeLabel ?? "your notes"}. Add a Markdown table like this to one of them:`,
  });
  box.createEl("pre", { cls: "kvs-empty-example" }).setText(EXAMPLE_TABLE);

  const actions = box.createDiv({ cls: "kvs-empty-actions" });
  const copy = actions.createEl("button", { cls: "mod-cta", text: "Copy example table" });
  copy.addEventListener("click", () => {
    void navigator.clipboard?.writeText(EXAMPLE_TABLE);
    copy.setText("Copied");
    window.setTimeout(() => copy.setText("Copy example table"), 1500);
  });
  if (es) {
    const edit = actions.createEl("button", { cls: "kvs-tb-btn", text: "Change folder" });
    edit.addEventListener("click", () => es.onOpenSettings());
  }
}
