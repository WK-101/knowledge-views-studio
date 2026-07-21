import { describe, it, expect } from "vitest";
import {
  DEFAULT_ISLAND_SETTINGS,
  ISLAND_SIZE_SCALE,
  normalizeIslandSettings,
} from "../extension/src/lib/island-settings";

describe("island settings · the toolbar's appearance and behaviour", () => {
  it("defaults reproduce today's behaviour", () => {
    expect(DEFAULT_ISLAND_SETTINGS).toEqual({
      size: "medium",
      theme: "auto",
      trigger: "auto",
      minChars: 0,
      hideOnScroll: false,
      inEditable: true,
    });
  });

  it("gives the full default for nothing, or nonsense", () => {
    expect(normalizeIslandSettings(undefined)).toEqual(DEFAULT_ISLAND_SETTINGS);
    expect(normalizeIslandSettings(null)).toEqual(DEFAULT_ISLAND_SETTINGS);
    expect(normalizeIslandSettings("nope")).toEqual(DEFAULT_ISLAND_SETTINGS);
  });

  it("keeps recognised enum values and rejects unknown ones back to the default", () => {
    expect(normalizeIslandSettings({ size: "large", theme: "dark", trigger: "hold-alt" })).toMatchObject({
      size: "large",
      theme: "dark",
      trigger: "hold-alt",
    });
    expect(normalizeIslandSettings({ size: "huge", theme: "sepia", trigger: "shout" })).toMatchObject({
      size: "medium",
      theme: "auto",
      trigger: "auto",
    });
  });

  it("clamps, floors, and guards minChars", () => {
    expect(normalizeIslandSettings({ minChars: 5 }).minChars).toBe(5);
    expect(normalizeIslandSettings({ minChars: -3 }).minChars).toBe(0);
    expect(normalizeIslandSettings({ minChars: 9999 }).minChars).toBe(100);
    expect(normalizeIslandSettings({ minChars: 4.9 }).minChars).toBe(4);
    expect(normalizeIslandSettings({ minChars: "12" }).minChars).toBe(0); // non-number → default
    expect(normalizeIslandSettings({ minChars: NaN }).minChars).toBe(0);
  });

  it("treats the booleans strictly: hideOnScroll needs true, inEditable only false turns it off", () => {
    expect(normalizeIslandSettings({ hideOnScroll: true }).hideOnScroll).toBe(true);
    expect(normalizeIslandSettings({ hideOnScroll: "yes" }).hideOnScroll).toBe(false);
    expect(normalizeIslandSettings({}).hideOnScroll).toBe(false);
    expect(normalizeIslandSettings({ inEditable: false }).inEditable).toBe(false);
    expect(normalizeIslandSettings({ inEditable: 0 }).inEditable).toBe(true); // only an explicit false is off
    expect(normalizeIslandSettings({}).inEditable).toBe(true);
  });

  it("maps each size to a scale, medium being 1", () => {
    expect(ISLAND_SIZE_SCALE.medium).toBe(1);
    expect(ISLAND_SIZE_SCALE.small).toBeLessThan(1);
    expect(ISLAND_SIZE_SCALE.large).toBeGreaterThan(1);
  });
});
