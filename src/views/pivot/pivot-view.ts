import type { KnowledgeView, ViewRenderContext } from "../view";
import { optString } from "../view-options";
import { buildPivot, type AggregateKind } from "./pivot";

const AGG_LABELS: Record<AggregateKind, string> = {
  count: "Count",
  sum: "Sum",
  avg: "Average",
  min: "Minimum",
  max: "Maximum",
};

function formatNumber(value: number, kind: AggregateKind): string {
  if (kind === "avg") return value.toFixed(2);
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

function renderPivot(ctx: ViewRenderContext): void {
  const { container, profile, result } = ctx;
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-pivot-view" });

  const rowField = optString(profile.view.options, "rowField");
  if (rowField === "") {
    root.createDiv({ cls: "kvs-empty", text: "Choose a “Rows (group by)” field in this view's settings." });
    return;
  }
  const columnFieldRaw = optString(profile.view.options, "columnField");
  const columnField = columnFieldRaw === "" ? null : columnFieldRaw;
  const kind = (optString(profile.view.options, "aggregate", "count") as AggregateKind) || "count";
  const aggField = optString(profile.view.options, "aggregateField");
  const agg = kind === "count" ? { kind } : { kind, field: aggField };

  const pivot = buildPivot(result.rows, rowField, columnField, agg);
  const valueHeader = kind === "count" ? "Count" : `${AGG_LABELS[kind]}${aggField ? ` of ${aggField}` : ""}`;

  const scroll = root.createDiv({ cls: "kvs-table-scroll" });
  const table = scroll.createEl("table", { cls: "kvs-table kvs-pivot-table" });

  const headRow = table.createEl("thead").createEl("tr");
  headRow.createEl("th", { cls: "kvs-th", text: rowField });
  if (columnField) {
    for (const key of pivot.columnKeys) headRow.createEl("th", { cls: "kvs-th kvs-num", text: key || "(none)" });
    headRow.createEl("th", { cls: "kvs-th kvs-num", text: "Total" });
  } else {
    headRow.createEl("th", { cls: "kvs-th kvs-num", text: valueHeader });
  }

  const tbody = table.createEl("tbody");
  pivot.rowKeys.forEach((rowKey, r) => {
    const tr = tbody.createEl("tr", { cls: "kvs-row" });
    tr.createEl("td", { cls: "kvs-td", text: rowKey || "(none)" });
    if (columnField) {
      pivot.columnKeys.forEach((_, c) => {
        tr.createEl("td", { cls: "kvs-td kvs-num", text: formatNumber(pivot.values[r]?.[c] ?? 0, kind) });
      });
      tr.createEl("td", { cls: "kvs-td kvs-num kvs-total", text: formatNumber(pivot.rowTotals[r] ?? 0, kind) });
    } else {
      tr.createEl("td", { cls: "kvs-td kvs-num", text: formatNumber(pivot.rowTotals[r] ?? 0, kind) });
    }
  });

  const foot = table.createEl("tfoot").createEl("tr", { cls: "kvs-row kvs-total-row" });
  foot.createEl("td", { cls: "kvs-td kvs-total", text: "Total" });
  if (columnField) {
    pivot.columnTotals.forEach((value) => foot.createEl("td", { cls: "kvs-td kvs-num kvs-total", text: formatNumber(value, kind) }));
    foot.createEl("td", { cls: "kvs-td kvs-num kvs-total", text: formatNumber(pivot.grandTotal, kind) });
  } else {
    foot.createEl("td", { cls: "kvs-td kvs-num kvs-total", text: formatNumber(pivot.grandTotal, kind) });
  }
}

export const pivotView: KnowledgeView = {
  type: "pivot",
  label: "Summary",
  paginates: false,
  icon: "table-2",
  optionSpecs: [
    { key: "rowField", label: "Rows (group by)", kind: "field" },
    { key: "columnField", label: "Columns (optional)", kind: "field" },
    {
      key: "aggregate",
      label: "Aggregate",
      kind: "select",
      choices: [
        { value: "count", label: "Count" },
        { value: "sum", label: "Sum" },
        { value: "avg", label: "Average" },
        { value: "min", label: "Minimum" },
        { value: "max", label: "Maximum" },
      ],
    },
    { key: "aggregateField", label: "Aggregate field", kind: "field", description: "Used for sum / average / min / max." },
  ],
  render: renderPivot,
};
