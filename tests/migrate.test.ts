import { describe, it, expect } from "vitest";
import { migrateData } from "../src/services/profile/migrate";
import { SCHEMA_VERSION } from "../src/services/profile/profile";

const legacy = {
  profiles: [
    {
      id: "p1",
      label: "Papers",
      enabled: true,
      profileKind: "literature",
      source: { mode: "single-folder", singleFolder: "Research", multiFolders: [], includeSubfolders: true },
      match: {
        headers: [
          { id: "h1", name: "Source", role: "note-link" },
          { id: "h2", name: "Year", role: "year" },
          { id: "h3", name: "Status", role: "plain", fieldKind: "status" },
        ],
        headerMatchMode: "loose",
        includeMalformedTables: false,
      },
      transform: {
        filters: [
          { field: "Year", operator: "gte", value: "2020" },
          { field: "Status", operator: "eq", value: "done" },
        ],
        defaultSortField: "Year",
        defaultSortDirection: "desc",
        defaultPageSize: 25,
        queryExpression: "Year >= 2000",
      },
      output: { baseOutputFile: "Papers.md" },
    },
  ],
  activeProfileId: "p1",
  autoRefreshProfileId: "p1",
  interactiveAutoRefresh: true,
  metaProfiles: [{ id: "m1", label: "Dash", enabled: true, profileIds: ["p1"], defaultView: "cards" }],
};

describe("migrateData — legacy PluginSettingsV2", () => {
  const { data, warnings } = migrateData(legacy);

  it("stamps the current schema version and keeps the active profile", () => {
    expect(data.version).toBe(SCHEMA_VERSION);
    expect(data.activeProfileId).toBe("p1");
    expect(data.profiles).toHaveLength(1);
  });

  it("maps scope, columns (role/fieldKind -> type), and operators (eq -> equals)", () => {
    const p = data.profiles[0]!;
    expect(p.name).toBe("Papers");
    expect(p.scope).toEqual({ mode: "folders", folders: ["Research"], includeSubfolders: true });
    expect(p.columns).toEqual([
      { name: "Source", type: "link" },
      { name: "Year", type: "number" },
      { name: "Status", type: "select" },
    ]);
    expect(p.filter?.conditions).toEqual([
      { field: "Year", operator: "gte", value: "2020" },
      { field: "Status", operator: "equals", value: "done" },
    ]);
    expect(p.advancedQuery).toBe("Year >= 2000");
    expect(p.sort).toEqual([{ field: "Year", direction: "desc" }]);
    expect(p.pageSize).toBe(25);
  });

  it("carries the legacy auto-refresh flag and warns about dropped meta-profiles", () => {
    expect(data.settings.autoRefresh).toBe(true);
    expect(warnings.some((w) => /meta-profile/i.test(w))).toBe(true);
  });
});

describe("migrateData — robustness", () => {
  it("returns defaults for junk input without throwing", () => {
    expect(migrateData(null).data.profiles).toHaveLength(0);
    expect(migrateData(42).data.version).toBe(SCHEMA_VERSION);
    expect(migrateData({ nonsense: true }).data.profiles).toHaveLength(0);
  });

  it("is idempotent on already-current data", () => {
    const once = migrateData(legacy).data;
    const twice = migrateData(once).data;
    expect(twice.version).toBe(SCHEMA_VERSION);
    expect(twice.profiles).toHaveLength(1);
    expect(twice.profiles[0]!.name).toBe("Papers");
  });
});
