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

describe("buildPivot — single-pass bucketing keeps the old semantics", () => {
  it("returns an empty shape for no rows", () => {
    const pivot = buildPivot([], "Team", null, { kind: "count" });
    expect(pivot.rowKeys).toEqual([]);
    expect(pivot.values).toEqual([]);
    expect(pivot.rowTotals).toEqual([]);
    expect(pivot.grandTotal).toBe(0);
  });

  it("keeps first-encounter key order, not sorted order", () => {
    const rows = [makeRow({ Team: "Zeta" }), makeRow({ Team: "Alpha" }), makeRow({ Team: "Zeta" })];
    expect(buildPivot(rows, "Team", null, { kind: "count" }).rowKeys).toEqual(["Zeta", "Alpha"]);
  });

  it("leaves sparse cells at the aggregate's empty value", () => {
    const rows = [
      makeRow({ Team: "Eng", Q: "Q1", Points: "3" }),
      makeRow({ Team: "Design", Q: "Q2", Points: "7" }),
    ];
    const pivot = buildPivot(rows, "Team", "Q", { kind: "sum", field: "Points" });
    // Eng has nothing in Q2, Design nothing in Q1 — those cells stay 0, not undefined.
    expect(pivot.values).toEqual([
      [3, 0],
      [0, 7],
    ]);
  });

  it("computes averages over the whole group rather than averaging the cells", () => {
    // Eng: 2 and 4 in Q1, 12 in Q2. Averaging cells would give (3 + 12)/2 = 7.5; the true group avg is 6.
    const rows = [
      makeRow({ Team: "Eng", Q: "Q1", Points: "2" }),
      makeRow({ Team: "Eng", Q: "Q1", Points: "4" }),
      makeRow({ Team: "Eng", Q: "Q2", Points: "12" }),
    ];
    const pivot = buildPivot(rows, "Team", "Q", { kind: "avg", field: "Points" });
    expect(pivot.rowTotals[0]).toBe(6);
  });

  it("groups blank values together under one empty key", () => {
    const rows = [makeRow({ Team: "" }), makeRow({ Team: "  " }), makeRow({ Team: "Eng" })];
    const pivot = buildPivot(rows, "Team", null, { kind: "count" });
    expect(pivot.rowKeys).toEqual(["", "Eng"]);
    expect(pivot.rowTotals).toEqual([2, 1]);
  });

  it("handles a high-cardinality pivot without quadratic blow-up", () => {
    // Every row its own key — the shape that made the old re-filtering approach O(keys x rows).
    const rows = Array.from({ length: 2000 }, (_, i) => makeRow({ Id: `k${i}`, N: "1" }));
    const started = Date.now();
    const pivot = buildPivot(rows, "Id", null, { kind: "sum", field: "N" });
    expect(pivot.rowKeys).toHaveLength(2000);
    expect(pivot.grandTotal).toBe(2000);
    expect(Date.now() - started).toBeLessThan(2000); // generous, but quadratic would blow past it
  });
});
