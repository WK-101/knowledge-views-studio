import { describe, it, expect } from "vitest";
import { makeSnippet } from "../src/services/search/snippet";

describe("makeSnippet", () => {
  it("centres on the densest cluster of matches and marks ranges", () => {
    const text = "Intro paragraph. " + "filler ".repeat(60) + "the transformer uses attention and attention again" + " more".repeat(60);
    const s = makeSnippet(text, ["attention", "transformer"], 120);
    // snippet should contain the cluster, not the intro
    expect(s.text).toContain("attention");
    expect(s.text).toContain("transformer");
    expect(s.ranges.length).toBeGreaterThanOrEqual(2);
    // each range should point at a match
    for (const [a, b] of s.ranges) expect(/attention|transformer/i.test(s.text.slice(a, b))).toBe(true);
    expect(s.prefix).toBe(true);
  });
  it("highlights case-insensitively", () => {
    const s = makeSnippet("The Neural Network learns", ["neural"]);
    expect(s.text.slice(s.ranges[0]![0], s.ranges[0]![1])).toBe("Neural");
  });
  it("falls back to a head slice with no terms", () => {
    const s = makeSnippet("some long text ".repeat(30), []);
    expect(s.ranges).toEqual([]);
    expect(s.suffix).toBe(true);
  });
  it("no ellipsis when the whole text fits", () => {
    const s = makeSnippet("short and sweet", ["sweet"]);
    expect(s.prefix).toBe(false);
    expect(s.suffix).toBe(false);
  });
});
