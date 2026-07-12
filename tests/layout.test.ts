import { describe, it, expect } from "vitest";
import { createProfile, normalizeLayout } from "../src/services/profile/profile";
import { composeLayout, hasMultipleLayouts, layoutFromProfile, profileLayouts, splitViewPatch } from "../src/services/profile/layout";
import { serializeViewFile, parseViewFile } from "../src/services/view-file";

describe("layouts — a view with one shared data source and many presentations", () => {
  it("treats a legacy single-layout view as one default layout named from its type", () => {
    const profile = createProfile({ view: { type: "kanban", options: {} } });
    expect(profile.layouts).toBeUndefined(); // no multi-layout data stored
    const layouts = profileLayouts(profile);
    expect(layouts).toHaveLength(1);
    expect(layouts[0]!.name).toBe("Board");
    expect(layouts[0]!.view.type).toBe("kanban");
    expect(hasMultipleLayouts(profile)).toBe(false);
  });

  it("returns explicit layouts when present, with fresh ids and filled defaults", () => {
    const profile = createProfile({
      layouts: [
        { name: "Grid", view: { type: "table", options: {} } },
        { view: { type: "kanban", options: { groupField: "Status" } } }, // no name ⇒ default "Board"
      ],
    });
    const layouts = profileLayouts(profile);
    expect(layouts.map((l) => l.name)).toEqual(["Grid", "Board"]);
    expect(layouts[0]!.id).not.toBe(layouts[1]!.id);
    expect(layouts[0]!.rowHeight).toBe("normal"); // defaults filled
    expect(hasMultipleLayouts(profile)).toBe(true);
  });

  it("composeLayout keeps the shared data and takes presentation only from the layout", () => {
    const profile = createProfile({
      name: "Data",
      scope: { mode: "folders", folders: ["Research"], includeSubfolders: false },
      filter: { combinator: "and", conditions: [{ field: "Status", operator: "contains", value: "open" }], groups: [] },
      columnMatch: "contains",
      view: { type: "table", options: {} }, // base presentation, should be overridden
    });
    const layout = normalizeLayout({
      name: "Board",
      view: { type: "kanban", options: { groupField: "Status" } },
      sort: [{ field: "Due", direction: "asc" }],
      pageSize: 25,
    });
    const composed = composeLayout(profile, layout);

    // shared data preserved
    expect(composed.scope.folders).toEqual(["Research"]);
    expect(composed.filter).toEqual(profile.filter);
    expect(composed.columnMatch).toBe("contains");
    // presentation from the layout
    expect(composed.view.type).toBe("kanban");
    expect(composed.view.options).toEqual({ groupField: "Status" });
    expect(composed.sort).toEqual([{ field: "Due", direction: "asc" }]);
    // a composed layout is a single rendered view — no nested layouts
    expect(composed.layouts).toBeUndefined();
    // page size is presentation and comes from the layout — this is what pagination and export read
    expect(composed.pageSize).toBe(layout.pageSize);
  });

  it("edits to the shared data flow to every layout (no drift)", () => {
    const profile = createProfile({ scope: { mode: "folders", folders: ["A"], includeSubfolders: false } });
    const layout = layoutFromProfile(profile);
    const moved = { ...profile, scope: { mode: "vault" as const, folders: [], includeSubfolders: true } };
    expect(composeLayout(moved, layout).scope.mode).toBe("vault"); // same layout, new shared scope
  });

  it("round-trips a multi-layout view through the .kvsview format", () => {
    const profile = createProfile({
      name: "Projects",
      scope: { mode: "folders", folders: ["Projects"], includeSubfolders: false },
      layouts: [
        { name: "Table", view: { type: "table", options: {} } },
        { name: "Board", view: { type: "kanban", options: { groupField: "Status" } }, sort: [{ field: "Due", direction: "asc" }] },
      ],
    });
    const back = parseViewFile(serializeViewFile(profile))!;
    expect(back.layouts).toHaveLength(2);
    expect(back.layouts!.map((l) => l.name)).toEqual(["Table", "Board"]);
    expect(back.layouts![1]!.view.options).toEqual({ groupField: "Status" });
    expect(back.scope.folders).toEqual(["Projects"]);
  });

  it("splitViewPatch routes presentation to the layout and data to the view", () => {
    const { data, layout } = splitViewPatch({
      // presentation → layout
      view: { type: "kanban", options: { groupField: "Status" } },
      sort: [{ field: "Due", direction: "asc" }],
      hiddenColumns: ["Notes"],
      // data → view
      filter: { combinator: "and", conditions: [], groups: [] },
      columnMatch: "exact",
      scope: { mode: "vault", folders: [], includeSubfolders: true },
    });
    expect(Object.keys(layout).sort()).toEqual(["hiddenColumns", "sort", "view"]);
    expect(Object.keys(data).sort()).toEqual(["columnMatch", "filter", "scope"]);
    expect(layout.view!.type).toBe("kanban");
    expect(data.columnMatch).toBe("exact");
  });
});
