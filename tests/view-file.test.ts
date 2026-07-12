import { describe, it, expect } from "vitest";
import { serializeViewFile, parseViewFile, KVS_VIEW_EXTENSION } from "../src/services/view-file";
import { createProfile } from "../src/services/profile/profile";

describe("view-file serialization", () => {
  it("round-trips a complete profile", () => {
    const profile = createProfile({
      name: "Roadmap",
      category: "Planning",
      scope: { mode: "folders", folders: ["Projects"], includeSubfolders: true },
      extractors: ["table", "task"],
      columns: [{ name: "Title", type: "text" }, { name: "Due", type: "date" }],
      sort: [{ field: "Due", direction: "asc" }],
      group: { field: "Status" },
      filter: { combinator: "and", conditions: [{ field: "Status", op: "eq", value: "open" }] } as never,
      hiddenColumns: ["Internal"],
      tableWidth: "wide",
      rowHeight: "compact",
      sourceColumn: false,
      view: { type: "kanban", options: { groupBy: "Status" } },
      pageSize: 50,
    });
    const text = serializeViewFile(profile);
    const back = parseViewFile(text);
    expect(back).not.toBeNull();
    expect(back!.name).toBe("Roadmap");
    expect(back!.category).toBe("Planning");
    expect(back!.scope.folders).toEqual(["Projects"]);
    expect(back!.extractors).toEqual(["table", "task"]);
    expect(back!.columns.map((c) => c.name)).toEqual(["Title", "Due"]);
    expect(back!.group?.field).toBe("Status");
    expect(back!.hiddenColumns).toEqual(["Internal"]);
    expect(back!.tableWidth).toBe("wide");
    expect(back!.view.type).toBe("kanban");
    expect(back!.pageSize).toBe(50);
  });

  it("fills defaults for a partial profile", () => {
    const back = parseViewFile(JSON.stringify({ knowledgeView: 1, profile: { name: "Bare" } }));
    expect(back).not.toBeNull();
    expect(back!.name).toBe("Bare");
    expect(back!.tableWidth).toBe("fit"); // default applied
    expect(back!.sourceColumn).toBe(true); // default applied
  });

  it("returns null for invalid content", () => {
    expect(parseViewFile("")).toBeNull();
    expect(parseViewFile("not json")).toBeNull();
    expect(parseViewFile("[1,2,3]")).toBeNull();
    expect(parseViewFile(JSON.stringify({ knowledgeView: 1 }))).toBeNull();
  });

  it("uses the kvsview extension", () => {
    expect(KVS_VIEW_EXTENSION).toBe("kvsview");
  });
});

import { serializeViewDoc, parseViewDoc } from "../src/services/view-file";

describe("view-file multi-view documents", () => {
  it("round-trips several views and the active tab", () => {
    const a = createProfile({ name: "Table", view: { type: "table", options: {} } });
    const b = createProfile({ name: "Board", view: { type: "kanban", options: {} } });
    const c = createProfile({ name: "Calendar", view: { type: "calendar", options: {} } });
    const text = serializeViewDoc({ views: [a, b, c], activeView: b.id });
    const doc = parseViewDoc(text);
    expect(doc).not.toBeNull();
    expect(doc!.views.map((v) => v.name)).toEqual(["Table", "Board", "Calendar"]);
    expect(doc!.activeView).toBe(b.id);
    expect(doc!.views[1]!.view.type).toBe("kanban");
  });

  it("falls back to the first view when activeView is unknown", () => {
    const a = createProfile({ name: "One" });
    const text = serializeViewDoc({ views: [a], activeView: "missing-id" });
    const doc = parseViewDoc(text);
    expect(doc!.activeView).toBe(a.id);
  });

  it("round-trips a multi-view group: one file exports and re-imports its views together", () => {
    const views = [
      createProfile({ name: "Board", category: "Projects" }),
      createProfile({ name: "Calendar", category: "Projects" }),
    ];
    // Export the group as ONE multi-view file (what "Export group as .kvsview" does).
    const text = serializeViewDoc({ views, activeView: views[0]!.id });
    const doc = parseViewDoc(text)!;
    expect(doc.views).toHaveLength(2);
    // Re-import (what the importer does for a multi-view file): fresh ids, grouped under the file name.
    const imported = doc.views.map((v) => createProfile({ ...v, id: undefined, category: "Projects" }));
    expect(imported.map((v) => v.name)).toEqual(["Board", "Calendar"]);
    expect(imported.every((v) => v.category === "Projects")).toBe(true);
    expect(imported[0]!.id).not.toBe(views[0]!.id); // no id collisions on re-import
  });

  it("reads a version-1 single-profile file as a one-view document", () => {
    const legacy = JSON.stringify({ knowledgeView: 1, profile: { name: "Legacy", view: { type: "table", options: {} } } });
    const doc = parseViewDoc(legacy);
    expect(doc).not.toBeNull();
    expect(doc!.views).toHaveLength(1);
    expect(doc!.views[0]!.name).toBe("Legacy");
    expect(doc!.activeView).toBe(doc!.views[0]!.id);
  });

  it("rejects invalid or empty documents", () => {
    expect(parseViewDoc("")).toBeNull();
    expect(parseViewDoc("nope")).toBeNull();
    expect(parseViewDoc(JSON.stringify({ views: [] }))).toBeNull();
  });

  it("assigns a fresh id on re-import so a stored view never collides", () => {
    const original = createProfile({
      name: "My view",
      scope: { mode: "folders", folders: ["A"], includeSubfolders: false },
    });
    const doc = parseViewDoc(serializeViewFile(original));
    // The settings importer strips the id (createProfile with id undefined) for a fresh one.
    const imported = createProfile({ ...doc!.views[0]!, id: undefined });
    expect(imported.id).not.toBe(original.id);
    expect(imported.name).toBe("My view");
    expect(imported.scope.folders).toEqual(["A"]);
  });
});
