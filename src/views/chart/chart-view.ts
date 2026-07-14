import { Chart, registerables } from "chart.js";
import { setIcon, setTooltip } from "obsidian";
import { renderEmptyState } from "../empty-state";
import { optString } from "../view-options";
import { openRowDetail } from "../row-detail-modal";
import { buildChartSeries, describeSeries, CHART_AGGREGATES } from "./chart-data";
import type { KnowledgeView, ViewRenderContext } from "../view";
import type { SummaryFn } from "../../domain/index";

Chart.register(...registerables);

type ChartKind = "bar" | "hbar" | "line" | "area" | "donut" | "number";

const KINDS: readonly { id: ChartKind; label: string; icon: string }[] = [
  { id: "bar", label: "Bar", icon: "bar-chart-3" },
  { id: "hbar", label: "Horizontal", icon: "bar-chart-horizontal" },
  { id: "line", label: "Line", icon: "line-chart" },
  { id: "area", label: "Area", icon: "area-chart" },
  { id: "donut", label: "Donut", icon: "pie-chart" },
  { id: "number", label: "Number", icon: "hash" },
];

/** Read a CSS variable from the live theme, so charts follow the user's colours instead of fighting them. */
function themeVar(el: HTMLElement, name: string, fallback: string): string {
  const v = getComputedStyle(el).getPropertyValue(name).trim();
  return v === "" ? fallback : v;
}

function palette(el: HTMLElement): string[] {
  const names = ["--color-blue", "--color-purple", "--color-green", "--color-orange", "--color-red", "--color-cyan", "--color-yellow", "--color-pink"];
  return names.map((n, i) => themeVar(el, n, ["#4c8dff", "#a882ff", "#4caf82", "#ff9f4c", "#ff5c5c", "#4cc9f0", "#ffd166", "#ff7ab6"][i]!));
}

function renderChart(ctx: ViewRenderContext): void {
  const { container, result, profile } = ctx;
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-chart-view" });

  const kind = (optString(profile.view.options, "chartType", "bar") || "bar") as ChartKind;
  const groupBy = optString(profile.view.options, "groupBy");
  const aggregate = (optString(profile.view.options, "aggregate", "count") || "count") as SummaryFn;
  const valueField = optString(profile.view.options, "valueField");

  const rows = result.rows;

  // Toolbar: change the chart without leaving the view. Persisted like the gallery's controls.
  const bar = root.createDiv({ cls: "kvs-chart-toolbar" });
  bar.createSpan({ cls: "kvs-chart-caption", text: describeSeries(aggregate, valueField, groupBy) });
  bar.createDiv({ cls: "kvs-tb-spacer" });
  const seg = bar.createDiv({ cls: "kvs-seg kvs-chart-kinds" });
  for (const k of KINDS) {
    const b = seg.createEl("button", { cls: "kvs-seg-btn" });
    setIcon(b.createSpan({ cls: "kvs-seg-ic" }), k.icon);
    b.toggleClass("is-on", k.id === kind);
    setTooltip(b, k.label);
    b.addEventListener("click", () => ctx.onSetViewOption?.("chartType", k.id));
  }

  if (groupBy === "") {
    const box = root.createDiv({ cls: "kvs-chart-empty" });
    box.createDiv({ cls: "kvs-chart-empty-title", text: "Choose what to group by" });
    box.createDiv({
      cls: "kvs-chart-empty-desc",
      text: "A chart needs two answers: what to group by (the axis), and what to measure (the value). Set them in this view's settings — Type & display.",
    });
    return;
  }
  if (rows.length === 0) {
    renderEmptyState(root, ctx, "chart");
    return;
  }

  const series = buildChartSeries(rows, groupBy, aggregate, valueField);
  if (series.labels.length === 0) {
    root.createDiv({ cls: "kvs-chart-empty", text: `No values to chart for “${groupBy}”.` });
    return;
  }

  // The "number" chart is not a chart at all -- it's the single figure people usually actually want.
  if (kind === "number") {
    const total = series.values.reduce((a, b) => a + b, 0);
    const box = root.createDiv({ cls: "kvs-chart-number" });
    box.createDiv({ cls: "kvs-chart-number-value", text: String(Math.round(total * 100) / 100) });
    box.createDiv({ cls: "kvs-chart-number-label", text: describeSeries(aggregate, valueField, groupBy) });
    const grid = root.createDiv({ cls: "kvs-chart-breakdown" });
    series.labels.forEach((label, i) => {
      const line = grid.createDiv({ cls: "kvs-chart-breakdown-row" });
      line.createSpan({ cls: "kvs-chart-breakdown-key", text: label });
      line.createSpan({ cls: "kvs-chart-breakdown-val", text: String(series.values[i]) });
    });
    return;
  }

  const wrap = root.createDiv({ cls: "kvs-chart-canvas" });
  const canvas = wrap.createEl("canvas");
  const colors = palette(root);
  const text = themeVar(root, "--text-muted", "#888");
  const grid = themeVar(root, "--background-modifier-border", "#ddd");
  const single = colors[0]!;

  const chart = new Chart(canvas, {
    type: kind === "donut" ? "doughnut" : kind === "line" || kind === "area" ? "line" : "bar",
    data: {
      labels: series.labels,
      datasets: [
        {
          label: describeSeries(aggregate, valueField, groupBy),
          data: series.values,
          backgroundColor: kind === "donut" ? series.labels.map((_, i) => colors[i % colors.length]!) : kind === "area" ? `${single}33` : single,
          borderColor: single,
          borderWidth: kind === "line" || kind === "area" ? 2 : 0,
          fill: kind === "area",
          tension: 0.25,
          borderRadius: kind === "bar" || kind === "hbar" ? 4 : 0,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: kind === "hbar" ? "y" : "x",
      plugins: {
        legend: { display: kind === "donut", labels: { color: text } },
        tooltip: { callbacks: { footer: (items) => `${series.buckets[items[0]?.dataIndex ?? 0]?.length ?? 0} rows — click to open` } },
      },
      scales:
        kind === "donut"
          ? {}
          : {
              x: { ticks: { color: text }, grid: { color: grid } },
              y: { ticks: { color: text }, grid: { color: grid }, beginAtZero: true },
            },
      // Click a bar to see the rows behind it. A chart you cannot interrogate is a picture, not a tool.
      onClick: (_event, elements) => {
        const i = elements[0]?.index;
        if (i === undefined) return;
        const bucket = series.buckets[i];
        const first = bucket?.[0];
        if (first) openRowDetail(ctx, first);
      },
    },
  });
  ctx.component.register(() => chart.destroy());
}

export const chartView: KnowledgeView = {
  type: "chart",
  label: "Chart",
  icon: "bar-chart-3",
  paginates: false, // a chart summarises everything that passed the filter, not one page of it
  optionSpecs: [
    {
      key: "groupBy",
      label: "Group by",
      kind: "field",
      fieldFilter: "any",
      description: "The axis: rows are bucketed by this column's value.",
    },
    {
      key: "aggregate",
      label: "Measure",
      kind: "select",
      choices: CHART_AGGREGATES.map((a) => ({ value: a.id, label: a.label })),
      description: "What each bar or slice represents.",
    },
    {
      key: "valueField",
      label: "Value column",
      kind: "field",
      fieldFilter: "any",
      description: "The column to measure. Ignored when measuring a count of rows.",
    },
    {
      key: "chartType",
      label: "Chart type",
      kind: "select",
      choices: KINDS.map((k) => ({ value: k.id, label: k.label })),
    },
  ],
  render: renderChart,
};
