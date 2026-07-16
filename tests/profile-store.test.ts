import { describe, it, expect, vi } from "vitest";
import { ProfileStore } from "../src/services/profile/profile-store";
import { DEFAULT_DATA, createProfile, type PluginData } from "../src/services/profile/profile";

const freshData = (): PluginData => structuredClone(DEFAULT_DATA);

describe("ProfileStore — mutations and persistence", () => {
  it("coalesces rapid mutations into one debounced persist", () => {
    vi.useFakeTimers();
    const persisted: PluginData[] = [];
    const store = new ProfileStore({
      data: freshData(),
      persist: async (d) => void persisted.push(d),
      debounceMs: 100,
    });
    store.addProfile(createProfile({ name: "A" }));
    store.addProfile(createProfile({ name: "B" }));
    expect(persisted).toHaveLength(0);
    vi.advanceTimersByTime(150);
    expect(persisted).toHaveLength(1);
    expect(persisted[0]!.profiles).toHaveLength(2);
    vi.useRealTimers();
  });

  it("flush persists immediately and reflects setting changes", async () => {
    const persisted: PluginData[] = [];
    const store = new ProfileStore({ data: freshData(), persist: async (d) => void persisted.push(d) });
    store.updateSettings({ autoRefresh: false });
    await store.flush();
    expect(persisted).toHaveLength(1);
    expect(persisted[0]!.settings.autoRefresh).toBe(false);
  });

  it("supports patch, remove, reorder, and active selection", () => {
    const store = new ProfileStore({ data: freshData(), persist: async () => {} });
    const a = store.addProfile(createProfile({ name: "A" }));
    const b = store.addProfile(createProfile({ name: "B" }));
    store.patchProfile(a.id, { name: "A2" });
    expect(store.getProfile(a.id)?.name).toBe("A2");
    store.setActiveProfile(b.id);
    expect(store.getActiveProfile()?.id).toBe(b.id);
    store.reorderProfiles([b.id, a.id]);
    expect(store.listProfiles().map((p) => p.id)).toEqual([b.id, a.id]);
    store.removeProfile(b.id);
    expect(store.listProfiles()).toHaveLength(1);
    expect(store.getActiveProfileId()).toBeNull();
  });
});

describe("ProfileStore — import/export", () => {
  it("round-trips a profile with a fresh id", () => {
    const store = new ProfileStore({ data: freshData(), persist: async () => {} });
    const original = store.addProfile(createProfile({ name: "Exported", advancedQuery: "Year >= 2020" }));
    const json = store.exportProfile(original.id);
    const imported = store.importProfile(json);
    expect(imported.id).not.toBe(original.id);
    expect(imported.name).toBe("Exported");
    expect(imported.advancedQuery).toBe("Year >= 2020");
  });
});

describe("profile category (view organisation)", () => {
  it("carries a category when provided and omits it when blank", () => {
    expect(createProfile({ category: "Projects" }).category).toBe("Projects");
    expect("category" in createProfile({})).toBe(false);
    expect("category" in createProfile({ category: "" })).toBe(false);
  });
});

describe("leading meta-column flags", () => {
  it("defaults source-note and row-selection columns on, and can turn them off", () => {
    expect(createProfile({}).sourceColumn).toBe(true);
    expect(createProfile({}).rowSelection).toBe(true);
    expect(createProfile({ sourceColumn: false, rowSelection: false }).sourceColumn).toBe(false);
    expect(createProfile({ sourceColumn: false, rowSelection: false }).rowSelection).toBe(false);
  });
});

describe("createProfile — persists per-view display/matching fields (regression)", () => {
  it("round-trips dedicatedNoteKey and showSummaryRow through createProfile", () => {
    // These optional fields were being dropped by createProfile, so the per-view note-match dropdown and the
    // summary-row toggle silently reset on reload. They must survive a create/deserialize round-trip.
    const p = createProfile({ dedicatedNoteKey: "isbn", showSummaryRow: false });
    expect(p.dedicatedNoteKey).toBe("isbn");
    expect(p.showSummaryRow).toBe(false);
  });

  it("keeps showSummaryRow: false rather than folding it to the shown default", () => {
    // The whole point of the toggle is `false`; truthiness-based carrying would drop it. Must be preserved.
    expect(createProfile({ showSummaryRow: false }).showSummaryRow).toBe(false);
    expect(createProfile({ showSummaryRow: true }).showSummaryRow).toBe(true);
    // Unset stays unset (treated as shown by the renderer).
    expect(createProfile({}).showSummaryRow).toBeUndefined();
  });
});
