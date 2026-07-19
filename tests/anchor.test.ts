import { describe, it, expect } from "vitest";
import { buildAnchor, findAnchor, anchorResolves, anchorSummary } from "../shared/anchor";

const text =
  "The first paragraph sets the scene. The important claim appears here. A closing thought follows after.";

describe("anchor · building", () => {
  it("keeps the quote and what surrounds it", () => {
    const anchor = buildAnchor(text, "The important claim appears here.");
    expect(anchor.exact).toBe("The important claim appears here.");
    expect(anchor.prefix).toContain("sets the scene");
    expect(anchor.suffix).toContain("A closing thought");
  });

  it("collapses whitespace, since a selection spanning elements arrives ragged", () => {
    expect(buildAnchor("a  b\n\nc", "a  b").exact).toBe("a b");
  });

  it("copes at the very start and end, where there's context on one side only", () => {
    expect(buildAnchor(text, "The first paragraph").prefix).toBeUndefined();
    expect(buildAnchor(text, "follows after.").suffix).toBeUndefined();
  });

  it("still produces an anchor when the quote isn't found in the text given", () => {
    expect(buildAnchor(text, "not present").exact).toBe("not present");
  });

  it("returns an empty anchor for an empty selection rather than inventing one", () => {
    expect(buildAnchor(text, "   ").exact).toBe("");
  });
});

describe("anchor · finding again", () => {
  it("finds the passage in unchanged text", () => {
    const anchor = buildAnchor(text, "The important claim appears here.");
    expect(findAnchor(text, anchor)).toBeGreaterThanOrEqual(0);
  });

  it("survives the page being re-rendered with different spacing", () => {
    const anchor = buildAnchor(text, "The important claim appears here.");
    const reflowed = text.replace(/ /g, "\n  ");
    expect(anchorResolves(reflowed, anchor)).toBe(true);
  });

  it("survives an edit on one side of the highlight", () => {
    const anchor = buildAnchor(text, "The important claim appears here.");
    const edited = text.replace("The first paragraph sets the scene.", "An entirely new opening line.");
    expect(anchorResolves(edited, anchor)).toBe(true);
  });

  it("reports MISSING when the passage is genuinely gone", () => {
    // Rather than attaching the note to whatever now sits nearby.
    const anchor = buildAnchor(text, "The important claim appears here.");
    expect(anchorResolves("Completely different content entirely.", anchor)).toBe(false);
  });

  it("uses context to pick the right one of two identical sentences", () => {
    const doubled = "Alpha. Repeated sentence. Beta. Repeated sentence. Gamma.";
    const anchor = buildAnchor(doubled, "Repeated sentence.", doubled.indexOf("Repeated sentence.", 20));
    const found = findAnchor(doubled, anchor);
    expect(found).toBeGreaterThan(doubled.indexOf("Beta") - 5);
  });

  it("refuses to guess between duplicates when there's no context at all", () => {
    const doubled = "Repeated. Repeated.";
    expect(findAnchor(doubled, { exact: "Repeated." })).toBe(-1);
  });

  it("finds a unique quote with no context", () => {
    expect(findAnchor(text, { exact: "A closing thought" })).toBeGreaterThanOrEqual(0);
  });

  it("returns -1 for an empty anchor", () => {
    expect(findAnchor(text, { exact: "" })).toBe(-1);
  });
});

describe("anchor · summary", () => {
  it("shows a short highlight in full", () => {
    expect(anchorSummary({ exact: "Short one." })).toBe("Short one.");
  });

  it("trims a long one rather than filling a panel", () => {
    const summary = anchorSummary({ exact: "x".repeat(200) }, 50);
    expect(summary.length).toBeLessThanOrEqual(51);
    expect(summary.endsWith("…")).toBe(true);
  });
});
