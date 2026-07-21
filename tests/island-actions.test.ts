import { describe, it, expect } from "vitest";
import {
  ISLAND_ACTIONS,
  DEFAULT_ISLAND_ACTIONS,
  normalizeIslandActions,
} from "../extension/src/lib/island-actions";

const ids = (list: readonly { id: string }[]): string[] => list.map((a) => a.id);

describe("island actions · the configurable selection toolbar", () => {
  it("defaults to every action, on, in catalogue order", () => {
    expect(ids(DEFAULT_ISLAND_ACTIONS)).toEqual(ids(ISLAND_ACTIONS));
    expect(DEFAULT_ISLAND_ACTIONS.every((a) => a.enabled)).toBe(true);
  });

  it("gives the full default for nothing, or nonsense, in storage", () => {
    expect(normalizeIslandActions(undefined)).toEqual([...DEFAULT_ISLAND_ACTIONS]);
    expect(normalizeIslandActions(null)).toEqual([...DEFAULT_ISLAND_ACTIONS]);
    expect(normalizeIslandActions("nope")).toEqual([...DEFAULT_ISLAND_ACTIONS]);
    expect(normalizeIslandActions(42)).toEqual([...DEFAULT_ISLAND_ACTIONS]);
  });

  it("keeps the stored order and on/off for known actions", () => {
    const stored = [
      { id: "note", enabled: true },
      { id: "colors", enabled: false },
      { id: "style", enabled: true },
      { id: "intensity", enabled: false },
    ];
    const out = normalizeIslandActions(stored);
    expect(ids(out)).toEqual(["note", "colors", "style", "intensity"]);
    expect(out.find((a) => a.id === "colors")!.enabled).toBe(false);
    expect(out.find((a) => a.id === "intensity")!.enabled).toBe(false);
    expect(out.find((a) => a.id === "note")!.enabled).toBe(true);
  });

  it("drops unknown ids and duplicates", () => {
    const out = normalizeIslandActions([
      { id: "note", enabled: true },
      { id: "note", enabled: false }, // duplicate — ignored
      { id: "made-up", enabled: true }, // unknown — dropped
      { id: 7, enabled: true }, // non-string id — dropped
      "garbage", // non-object — dropped
    ]);
    // note (from its first, kept) then the remaining known actions appended in catalogue order.
    expect(ids(out)).toEqual(["note", "colors", "style", "intensity"]);
    expect(out.find((a) => a.id === "note")!.enabled).toBe(true);
    // Exactly the known actions, once each — no unknowns survive.
    expect(out).toHaveLength(ISLAND_ACTIONS.length);
  });

  it("appends actions the stored list never had (a newer version's), on, at the end", () => {
    const out = normalizeIslandActions([{ id: "note", enabled: false }]);
    expect(ids(out)).toEqual(["note", "colors", "style", "intensity"]);
    // The appended ones default to on, so a new action shows up rather than hiding.
    expect(out.find((a) => a.id === "colors")!.enabled).toBe(true);
    expect(out.find((a) => a.id === "style")!.enabled).toBe(true);
    // The one the person had chosen keeps its off state.
    expect(out.find((a) => a.id === "note")!.enabled).toBe(false);
  });

  it("treats a missing enabled flag as on, and only an explicit false as off", () => {
    const out = normalizeIslandActions([
      { id: "colors" }, // no flag → on
      { id: "style", enabled: false }, // explicit off
      { id: "note", enabled: "yes" }, // non-false truthy-ish → on
    ]);
    expect(out.find((a) => a.id === "colors")!.enabled).toBe(true);
    expect(out.find((a) => a.id === "style")!.enabled).toBe(false);
    expect(out.find((a) => a.id === "note")!.enabled).toBe(true);
  });
});
