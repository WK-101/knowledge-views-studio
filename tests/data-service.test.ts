import { describe, it, expect, vi } from "vitest";
import { DataService } from "../src/services/data-service";
import { createProfile, DEFAULT_SETTINGS, type GlobalSettings } from "../src/services/profile/profile";
import {
  ExtractorRegistry,
  createDefaultColumnTypeRegistry,
  tableExtractor,
} from "../src/domain/index";
import { FakeVaultGateway } from "./_helpers";

const TABLE = ["| Title | Year |", "| --- | --- |", "| Row | 2021 |"].join("\n");

function setup(settings: Partial<GlobalSettings> = {}) {
  const gateway = new FakeVaultGateway();
  const registry = createDefaultColumnTypeRegistry();
  const extractors = new ExtractorRegistry().register(tableExtractor);
  const service = new DataService({
    gateway,
    registry,
    extractors,
    getSettings: () => ({ ...DEFAULT_SETTINGS, ...settings }),
  });
  return { gateway, service };
}

describe("DataService — scope and extraction", () => {
  it("gathers rows only from in-scope files", async () => {
    const { gateway, service } = setup();
    gateway.setFile("Research/A.md", TABLE);
    gateway.setFile("Other/B.md", TABLE);
    const profile = createProfile({ scope: { mode: "folders", folders: ["Research"], includeSubfolders: true } });
    const result = await service.query(profile);
    expect(result.rows).toHaveLength(1);
    expect(result.rows[0]!.file.folderPath).toBe("Research");
    service.dispose();
  });
});

describe("DataService — caching", () => {
  it("re-reads only files that changed", async () => {
    const { gateway, service } = setup({ autoRefresh: false });
    gateway.setFile("N/A.md", TABLE, 1);
    gateway.setFile("N/B.md", TABLE, 1);
    const profile = createProfile();

    await service.query(profile);
    const afterFirst = gateway.reads;
    expect(afterFirst).toBe(2);

    await service.query(profile);
    expect(gateway.reads).toBe(afterFirst); // fully cached, no new reads

    gateway.setFile("N/A.md", TABLE.replace("Row", "Row2"), 2);
    gateway.emit({ kind: "modify", path: "N/A.md" });

    await service.query(profile);
    expect(gateway.reads).toBe(afterFirst + 1); // only A re-read
    service.dispose();
  });
});

describe("DataService — real auto-refresh", () => {
  it("emits a debounced, path-tagged change when auto-refresh is on", () => {
    vi.useFakeTimers();
    const { gateway, service } = setup({ autoRefresh: true, refreshDebounceMs: 100 });
    const seen: string[][] = [];
    service.onChange((change) => seen.push(change.paths));

    gateway.emit({ kind: "modify", path: "N/A.md" });
    gateway.emit({ kind: "modify", path: "N/B.md" });
    expect(seen).toHaveLength(0);

    vi.advanceTimersByTime(150);
    expect(seen).toEqual([["N/A.md", "N/B.md"]]);

    service.dispose();
    vi.useRealTimers();
  });

  it("does not emit when auto-refresh is off, but still invalidates", () => {
    vi.useFakeTimers();
    const { gateway, service } = setup({ autoRefresh: false, refreshDebounceMs: 100 });
    const seen: string[][] = [];
    service.onChange((change) => seen.push(change.paths));
    gateway.emit({ kind: "modify", path: "N/A.md" });
    vi.advanceTimersByTime(150);
    expect(seen).toHaveLength(0);
    service.dispose();
    vi.useRealTimers();
  });
});


describe("DataService — query memoization", () => {
  const MULTI = [
    "| Title | N |",
    "| --- | --- |",
    "| Alpha | 1 |",
    "| Bravo | 2 |",
    "| Cain | 3 |",
    "| Delta | 4 |",
    "| Echo | 5 |",
  ].join("\n");

  it("serves different pages from cache without re-reading files", async () => {
    const { gateway, service } = setup({ autoRefresh: false });
    gateway.setFile("N/A.md", MULTI, 1);
    const profile = createProfile({ pageSize: 2 });

    const p0 = await service.query(profile, { page: 0 });
    const readsAfter = gateway.reads;
    const p1 = await service.query(profile, { page: 1 });

    expect(gateway.reads).toBe(readsAfter); // page change reused the dataset + prepared result
    expect(p0.total).toBe(5);
    expect(p0.rows.map((r) => r.cells.Title)).toEqual(["Alpha", "Bravo"]);
    expect(p1.rows.map((r) => r.cells.Title)).toEqual(["Cain", "Delta"]);
    service.dispose();
  });

  it("reflects a file change on the next query (memo invalidated)", async () => {
    const { gateway, service } = setup({ autoRefresh: false });
    gateway.setFile("N/A.md", ["| Title |", "| --- |", "| One |"].join("\n"), 1);
    const profile = createProfile();
    expect((await service.query(profile)).total).toBe(1);

    gateway.setFile("N/A.md", ["| Title |", "| --- |", "| One |", "| Two |"].join("\n"), 2);
    gateway.emit({ kind: "modify", path: "N/A.md" });
    expect((await service.query(profile)).total).toBe(2);
    service.dispose();
  });
});
