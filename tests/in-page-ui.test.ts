import { describe, it, expect } from "vitest";
import { inPageTheme, highlightAlpha } from "../shared/in-page-ui";

describe("in-page UI theme", () => {
  it("gives a consistent set of tokens for light and dark", () => {
    expect(inPageTheme(false).bg).toBe("#ffffff");
    expect(inPageTheme(true).bg).toBe("#232327");
    expect(inPageTheme(false).accent).toBe(inPageTheme(true).accent); // accent is scheme-independent
  });
});

describe("highlightAlpha (transparency)", () => {
  it("increases with intensity", () => {
    expect(highlightAlpha("light", false)).toBeLessThan(highlightAlpha("medium", false));
    expect(highlightAlpha("medium", false)).toBeLessThan(highlightAlpha("strong", false));
  });
  it("treats an unknown intensity as medium", () => {
    expect(highlightAlpha("nonsense", false)).toBe(highlightAlpha("medium", false));
  });
  it("is slightly softer in dark mode", () => {
    expect(highlightAlpha("strong", true)).toBeLessThan(highlightAlpha("strong", false));
  });
});
