import { getField } from "../../domain/index";
import type { ResolvedColumn } from "../view-model";
import type { KnowledgeView, ViewRenderContext } from "../view";
import { optString } from "../view-options";
import { findColumnByRole } from "../view-model";
import { buildCalendarMonth, type CalendarDay } from "./calendar";

import { monthState, capViewState, type MonthState } from "../view-state";
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const WEEKDAYS_SUN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function resolveDateField(ctx: ViewRenderContext): string {
  const configured = optString(ctx.profile.view.options, "dateField");
  if (configured !== "") return configured;
  const dateColumn = findColumnByRole(ctx.columns, "date") ?? ctx.columns.find((c) => c.typeId === "date");
  return dateColumn ? dateColumn.name : "modified";
}

function titleColumnFor(ctx: ViewRenderContext, dateField: string): ResolvedColumn | undefined {
  const byRole = findColumnByRole(ctx.columns, "title");
  if (byRole && byRole.name.toLowerCase() !== dateField.toLowerCase()) return byRole;
  return ctx.columns.find((c) => c.typeId !== "date" && c.name.toLowerCase() !== dateField.toLowerCase());
}

function renderDay(
  el: HTMLElement,
  day: CalendarDay,
  titleColumn: ResolvedColumn | undefined,
  ctx: ViewRenderContext,
): void {
  const cell = el.createDiv({ cls: day.inMonth ? "kvs-cal-day" : "kvs-cal-day kvs-cal-out" });
  cell.createDiv({ cls: "kvs-cal-daynum", text: String(day.day) });
  const limit = 3;
  for (const row of day.rows.slice(0, limit)) {
    const chip = cell.createDiv({ cls: "kvs-cal-chip" });
    chip.setText(titleColumn ? getField(row, titleColumn.name) || row.file.fileName : row.file.fileName);
    ctx.component.registerDomEvent(chip, "click", () => {
      void ctx.app.workspace.openLinkText(row.file.filePath, ctx.sourcePath);
    });
  }
  if (day.rows.length > limit) {
    cell.createDiv({ cls: "kvs-cal-more", text: `+${day.rows.length - limit} more` });
  }
}

function paint(ctx: ViewRenderContext, root: HTMLElement, dateField: string, weekStartsOn: 0 | 1): void {
  root.empty();
  const state = monthState.get(ctx.viewKey) ?? defaultState();
  monthState.set(ctx.viewKey, state);
  capViewState(monthState);

  const header = root.createDiv({ cls: "kvs-cal-header" });
  const prev = header.createEl("button", { cls: "kvs-cal-nav", text: "‹" });
  header.createSpan({ cls: "kvs-cal-month", text: `${MONTHS[state.month]} ${state.year}` });
  const next = header.createEl("button", { cls: "kvs-cal-nav", text: "›" });
  const today = header.createEl("button", { cls: "kvs-cal-today", text: "Today" });

  const repaint = (): void => paint(ctx, root, dateField, weekStartsOn);
  ctx.component.registerDomEvent(prev, "click", () => {
    state.month -= 1;
    if (state.month < 0) {
      state.month = 11;
      state.year -= 1;
    }
    repaint();
  });
  ctx.component.registerDomEvent(next, "click", () => {
    state.month += 1;
    if (state.month > 11) {
      state.month = 0;
      state.year += 1;
    }
    repaint();
  });
  ctx.component.registerDomEvent(today, "click", () => {
    const now = new Date();
    state.year = now.getFullYear();
    state.month = now.getMonth();
    repaint();
  });

  const grid = root.createDiv({ cls: "kvs-cal-grid" });
  const labels = weekStartsOn === 1 ? [...WEEKDAYS_SUN.slice(1), WEEKDAYS_SUN[0]] : WEEKDAYS_SUN;
  for (const label of labels) grid.createDiv({ cls: "kvs-cal-weekday", text: label ?? "" });

  const month = buildCalendarMonth(ctx.result.rows, dateField, state.year, state.month, { weekStartsOn });
  const titleColumn = titleColumnFor(ctx, dateField);
  for (const week of month.weeks) {
    for (const day of week) renderDay(grid, day, titleColumn, ctx);
  }
}

function defaultState(): MonthState {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() };
}

function renderCalendar(ctx: ViewRenderContext): void {
  ctx.container.empty();
  const root = ctx.container.createDiv({ cls: "kvs-view kvs-calendar-view" });
  const dateField = resolveDateField(ctx);
  const weekStartsOn: 0 | 1 = optString(ctx.profile.view.options, "weekStart") === "mon" ? 1 : 0;
  paint(ctx, root, dateField, weekStartsOn);
}

export const calendarView: KnowledgeView = {
  type: "calendar",
  label: "Calendar",
  paginates: false,
  icon: "calendar",
  optionSpecs: [
    { key: "dateField", label: "Date field", kind: "field", fieldFilter: "date", description: "Which date positions each note." },
    {
      key: "weekStart",
      label: "Week starts on",
      kind: "select",
      choices: [
        { value: "sun", label: "Sunday" },
        { value: "mon", label: "Monday" },
      ],
    },
  ],
  render: renderCalendar,
};
