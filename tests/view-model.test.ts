import { describe, it, expect } from "vitest";
import { resolveColumns } from "../src/views/view-model";
import { createProfile } from "../src/services/profile/profile";
import { makeRow } from "./_helpers";

describe("resolveColumns", () => {
  it("uses configured columns, marking data columns editable and carrying options", () => {
    const profile = createProfile({
      columns: [
        { name: "Title", type: "link", label: "Name" },
        { name: "Status", type: "select", options: [{ value: "Open" }, { value: "Done" }] },
        { name: "created", type: "date" }, // a virtual field -> not editable
        { name: "Secret", type: "text", visible: false },
      ],
    });
    const cols = resolveColumns(profile, []);
    expect(cols).toEqual([
      { name: "Title", label: "Name", typeId: "link", editable: true, role: "title" },
      { name: "Status", label: "Status", typeId: "select", editable: true, role: "status", options: [{ value: "Open" }, { value: "Done" }] },
      { name: "created", label: "created", typeId: "date", editable: false, role: "date" },
    ]);
  });

  it("auto-discovers columns from rows and infers their types (all editable)", () => {
    const rows = [makeRow({ Title: "[[A]]", Year: "2021", Tags: "x, y" }), makeRow({ Title: "[[B]]", Year: "2019" })];
    const cols = resolveColumns(createProfile(), rows);
    expect(cols.map((c) => c.name)).toEqual(["Title", "Year", "Tags"]);
    expect(cols.map((c) => c.typeId)).toEqual(["link", "number", "tags"]);
    expect(cols.every((c) => c.editable)).toBe(true);
  });

  it("shows an added virtual field WITHOUT collapsing a discovery view", () => {
    const rows = [makeRow({ Title: "[[A]]", Year: "2021" }), makeRow({ Title: "[[B]]", Year: "2019" })];
    // Only a virtual column configured — discovery must stay on (all data fields still show).
    const profile = createProfile({ columns: [{ name: "created", type: "date" }] });
    const cols = resolveColumns(profile, rows);
    expect(cols.map((c) => c.name)).toEqual(["Title", "Year", "created"]);
    expect(cols.find((c) => c.name === "created")).toMatchObject({ typeId: "date", editable: false });
  });

  it("hides an added virtual field when it is in hiddenColumns", () => {
    const rows = [makeRow({ Title: "[[A]]" })];
    const profile = createProfile({ columns: [{ name: "modified", type: "date" }], hiddenColumns: ["modified"] });
    const cols = resolveColumns(profile, rows);
    expect(cols.map((c) => c.name)).toEqual(["Title"]); // discovery preserved, virtual hidden
  });

  it("hiding a column keeps its definition (single source of truth is hiddenColumns)", () => {
    const profile = createProfile({
      columns: [
        { name: "A", type: "text" },
        { name: "B", type: "text" },
        { name: "C", type: "text" },
      ],
      hiddenColumns: ["B"],
    });
    // The definition is untouched — B is still a configured column...
    expect(profile.columns.map((c) => c.name)).toEqual(["A", "B", "C"]);
    // ...it is simply not rendered in this view.
    expect(resolveColumns(profile, []).map((c) => c.name)).toEqual(["A", "C"]);
  });

  it("migrates a legacy column.visible:false into hiddenColumns", () => {
    const profile = createProfile({
      columns: [
        { name: "A", type: "text" },
        { name: "Secret", type: "text", visible: false },
      ],
    });
    // visible flag folded into hiddenColumns; the column definition is preserved without the flag.
    expect(profile.hiddenColumns.map((x) => x.toLowerCase())).toContain("secret");
    expect(profile.columns.map((c) => c.name)).toEqual(["A", "Secret"]);
    expect(profile.columns.find((c) => c.name === "Secret")).not.toHaveProperty("visible");
    expect(resolveColumns(profile, []).map((c) => c.name)).toEqual(["A"]);
  });

  it("applies per-view widths from columnWidths without dropping or altering definitions", () => {
    // Reproduces the resize-with-hidden-column bug: resizing must not touch the column list.
    const profile = createProfile({
      columns: [
        { name: "A", type: "text" },
        { name: "B", type: "text" },
      ],
      hiddenColumns: ["B"],
      columnWidths: { a: 240 },
    });
    const cols = resolveColumns(profile, []);
    expect(cols.map((c) => c.name)).toEqual(["A"]); // B still hidden — NOT removed
    expect(cols.find((c) => c.name === "A")?.width).toBe(240);
    expect(profile.columns.map((c) => c.name)).toEqual(["A", "B"]); // definitions intact
  });

  it("applies columnWidths to discovered columns as well", () => {
    const profile = createProfile({ columnWidths: { title: 180 } });
    const cols = resolveColumns(profile, [makeRow({ Title: "x", Year: "2021" })]);
    expect(cols.find((c) => c.name === "Title")?.width).toBe(180);
    expect(cols.find((c) => c.name === "Year")?.width).toBeUndefined();
  });
});

describe("resolveColumns + hiddenColumns", () => {
  it("drops hidden columns in both discovery and configured modes", () => {
    const rows = [makeRow({ Task: "A", Status: "Doing", Owner: "Mara" })];
    // discovery mode
    const discovered = resolveColumns(createProfile({ columns: [], hiddenColumns: ["owner"] }), rows);
    expect(discovered.map((c) => c.name)).toEqual(["Task", "Status"]);
    // configured mode
    const configured = resolveColumns(
      createProfile({
        columns: [
          { name: "Task", type: "text" },
          { name: "Owner", type: "text" },
        ],
        hiddenColumns: ["owner"],
      }),
      rows,
    );
    expect(configured.map((c) => c.name)).toEqual(["Task"]);
  });

  it("carries the column's summary onto the resolved column (footer reads this back)", () => {
    // Regression: resolveColumns used to drop `summary`, so the table footer always saw "none" and picking
    // Sum/Count/etc. never took effect.
    const profile = createProfile({
      columns: [
        { name: "Item", type: "text" },
        { name: "Price", type: "number", summary: "sum" },
        { name: "Qty", type: "number", summary: "avg" },
      ],
    });
    const cols = resolveColumns(profile, []);
    expect(cols.find((c) => c.name === "Price")?.summary).toBe("sum");
    expect(cols.find((c) => c.name === "Qty")?.summary).toBe("avg");
    // A column with no summary configured stays unset.
    expect(cols.find((c) => c.name === "Item")?.summary).toBeUndefined();
  });

  it("carries number display mode (bar/ring) and displayMax through", () => {
    const profile = createProfile({
      columns: [{ name: "Progress", type: "number", display: "bar", displayMax: 100 }],
    });
    const col = resolveColumns(profile, []).find((c) => c.name === "Progress");
    expect(col?.display).toBe("bar");
    expect(col?.displayMax).toBe(100);
  });
});
