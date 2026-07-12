import { describe, it, expect } from "vitest";
import { resolveBlockProfile } from "../src/codeblock/resolve";
import { parseViewBlock } from "../src/codeblock/config";
import { DEFAULT_SETTINGS, createProfile } from "../src/services/profile/profile";

describe("resolveBlockProfile", () => {
  it("builds an ad-hoc profile from a block when none is referenced", () => {
    const config = parseViewBlock(["folder: Research", "view: cards", "limit: 10", "query: Year >= 2020"].join("\n"));
    const profile = resolveBlockProfile(config, undefined, DEFAULT_SETTINGS);
    expect(profile.scope).toEqual({ mode: "folders", folders: ["Research"], includeSubfolders: true });
    expect(profile.view.type).toBe("cards");
    expect(profile.pageSize).toBe(10);
    expect(profile.advancedQuery).toBe("Year >= 2020");
  });

  it("overrides only the keys present, inheriting the rest from the referenced profile", () => {
    const referenced = createProfile({
      name: "Base",
      scope: { mode: "folders", folders: ["Notes"], includeSubfolders: true },
      view: { type: "table", options: {} },
      advancedQuery: "Status == \"open\"",
      pageSize: 50,
    });
    const config = parseViewBlock(["profile: Base", "view: cards"].join("\n"));
    const profile = resolveBlockProfile(config, referenced, DEFAULT_SETTINGS);
    expect(profile.view.type).toBe("cards"); // overridden
    expect(profile.scope.folders).toEqual(["Notes"]); // inherited
    expect(profile.advancedQuery).toBe("Status == \"open\""); // inherited
    expect(profile.pageSize).toBe(50); // inherited
  });
});

describe("save-view-as-note round-trip", () => {
  it("a `profile: <name>` block re-renders the referenced view faithfully", () => {
    const view = createProfile({
      name: "My tasks",
      scope: { mode: "folders", folders: ["Work"], includeSubfolders: true },
      view: { type: "kanban", options: { groupBy: "Status" } },
      sort: [{ field: "Due", direction: "asc" }],
      rowHeight: "comfortable",
      tableWidth: "wide",
    });
    // This is exactly what "Save view as note" writes (fences stripped by the processor):
    const config = parseViewBlock(`profile: ${view.name}`);
    expect(config.profile).toBe("My tasks");
    const resolved = resolveBlockProfile(config, view, DEFAULT_SETTINGS);
    expect(resolved.scope).toEqual(view.scope);
    expect(resolved.view).toEqual(view.view);
    expect(resolved.sort).toEqual(view.sort);
    expect(resolved.rowHeight).toBe("comfortable");
    expect(resolved.tableWidth).toBe("wide");
  });
});
