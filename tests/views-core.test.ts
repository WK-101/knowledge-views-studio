import { describe, it, expect } from "vitest";
import { buildKanbanBoard } from "../src/views/kanban/board";
import { buildPivot } from "../src/views/pivot/pivot";
import { buildCalendarMonth } from "../src/views/calendar/calendar";
import { makeRow } from "./_helpers";

describe("buildKanbanBoard", () => {
  it("orders columns by the provided order, shows empty columns, and trails a (none)", () => {
    const rows = [
      makeRow({ Title: "A", Status: "Doing" }),
      makeRow({ Title: "B", Status: "Todo" }),
      makeRow({ Title: "C", Status: "" }),
      makeRow({ Title: "D", Status: "Doing" }),
    ];
    const board = buildKanbanBoard(rows, "Status", { order: ["Todo", "Doing", "Done"] });
    expect(board.columns.map((c) => c.label)).toEqual(["Todo", "Doing", "Done", "(none)"]);
    expect(board.columns.find((c) => c.key === "Doing")?.rows).toHaveLength(2);
    expect(board.columns.find((c) => c.key === "Done")?.rows).toHaveLength(0); // empty but present
    expect(board.columns.at(-1)?.rows).toHaveLength(1); // the blank one
  });
});

describe("buildPivot", () => {
  it("counts rows grouped by one field", () => {
    const rows = [
      makeRow({ Team: "Eng", Points: "3" }),
      makeRow({ Team: "Eng", Points: "5" }),
      makeRow({ Team: "Design", Points: "2" }),
    ];
    const pivot = buildPivot(rows, "Team", null, { kind: "count" });
    expect(pivot.rowKeys).toEqual(["Eng", "Design"]);
    expect(pivot.rowTotals).toEqual([2, 1]);
    expect(pivot.grandTotal).toBe(3);
  });

  it("sums a numeric field across a two-dimensional pivot with correct totals", () => {
    const rows = [
      makeRow({ Team: "Eng", Q: "Q1", Points: "3" }),
      makeRow({ Team: "Eng", Q: "Q2", Points: "5" }),
      makeRow({ Team: "Design", Q: "Q1", Points: "2" }),
    ];
    const pivot = buildPivot(rows, "Team", "Q", { kind: "sum", field: "Points" });
    expect(pivot.rowKeys).toEqual(["Eng", "Design"]);
    expect(pivot.columnKeys).toEqual(["Q1", "Q2"]);
    // Eng: Q1=3, Q2=5 ; Design: Q1=2, Q2=0
    expect(pivot.values).toEqual([
      [3, 5],
      [2, 0],
    ]);
    expect(pivot.rowTotals).toEqual([8, 2]);
    expect(pivot.columnTotals).toEqual([5, 5]);
    expect(pivot.grandTotal).toBe(10);
  });
});

describe("buildCalendarMonth", () => {
  it("places a dated row on the correct day with a 6x7 grid", () => {
    const rows = [makeRow({ Title: "Launch", Due: "2021-03-15" })];
    const month = buildCalendarMonth(rows, "Due", 2021, 2 /* March */, { weekStartsOn: 0 });
    expect(month.weeks).toHaveLength(6);
    expect(month.weeks.every((w) => w.length === 7)).toBe(true);
    const dayWithRow = month.weeks.flat().find((d) => d.rows.length > 0);
    expect(dayWithRow?.iso).toBe("2021-03-15");
    expect(dayWithRow?.inMonth).toBe(true);
  });
});
