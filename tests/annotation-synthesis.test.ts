import { describe, it, expect } from "vitest";
import { parseThemeMap, themeForColor, DEFAULT_THEME_SPEC } from "../src/services/annotations/themes";
import { parseAnnotationRegion, buildThemeSynthesis } from "../src/services/annotations/collect";
import { renderAnnotationsMarkdown, upsertAnnotationsRegion } from "../src/services/annotations/render";
import type { KvsAnnotation } from "../src/domain/index";

describe("theme map", () => {
  it("parses a color=Theme spec and looks up by colour name", () => {
    const map = parseThemeMap(DEFAULT_THEME_SPEC);
    expect(themeForColor("yellow", map)).toBe("Key finding");
    expect(themeForColor("BLUE", map)).toBe("Method");
    expect(themeForColor("teal", map)).toBeNull();
  });
});

describe("round-trip: render → parse", () => {
  const anns: KvsAnnotation[] = [
    { id: "aaaa1111", kind: "highlight", text: "self-attention is all you need", comment: "central claim", page: 3, rects: [{ x0: 0, y0: 100, x1: 10, y1: 110 }], source: "pdf-embedded", attachment: "p.pdf", color: "#ffd400" },
    { id: "bbbb2222", kind: "highlight", text: "we train on eight GPUs", comment: "", page: 7, rects: [{ x0: 0, y0: 90, x1: 10, y1: 100 }], source: "pdf-embedded", attachment: "p.pdf", color: "#2e6cb0" },
  ];
  const themeMap = parseThemeMap(DEFAULT_THEME_SPEC);

  it("parses back the highlights the renderer produced (with theme labels + block ids)", () => {
    const md = renderAnnotationsMarkdown(anns, { themeMap });
    const parsed = parseAnnotationRegion(upsertAnnotationsRegion("# Paper\n", md));
    expect(parsed).toHaveLength(2);
    expect(parsed[0]).toMatchObject({ page: 3, label: "Key finding", text: "self-attention is all you need", comment: "central claim" });
    expect(parsed[0]!.blockId).toMatch(/^anno-/);
    expect(parsed[1]).toMatchObject({ page: 7, label: "Method", text: "we train on eight GPUs" });
  });
});

describe("buildThemeSynthesis", () => {
  it("groups by theme and embeds each highlight via its block id", () => {
    const doc = buildThemeSynthesis([
      { note: "Vaswani 2017", highlights: [{ blockId: "anno-1", page: 3, label: "Key finding", text: "x", comment: "" }] },
      { note: "Devlin 2019", highlights: [{ blockId: "anno-2", page: 1, label: "Key finding", text: "y", comment: "" }, { blockId: "anno-3", page: 2, label: "Method", text: "z", comment: "" }] },
    ]);
    expect(doc).toContain("## Key finding");
    expect(doc).toContain("![[Vaswani 2017#^anno-1]]");
    expect(doc).toContain("![[Devlin 2019#^anno-2]]");
    expect(doc).toContain("## Method");
    expect(doc).toContain("3 highlights across 2 notes");
  });

  it("can render links instead of embeds", () => {
    const doc = buildThemeSynthesis([{ note: "N", highlights: [{ blockId: "anno-1", page: 5, label: "Evidence", text: "finding text", comment: "" }] }], { embed: false });
    expect(doc).toContain("- finding text — [[N#^anno-1|p.5]]");
  });
});

import { removeAnnotationCallout, calloutSourceLabel } from "../src/services/annotations/collect";

describe("source-aware delete helpers", () => {
  it("removes only the targeted callout (+ its trailing blank), leaving others", () => {
    const region = [
      "# Paper", "",
      "%% kvs:annotations:start %%",
      "## Annotations", "",
      "> [!quote] p.1 · Key finding · PDF ^anno-aaaa1111",
      "> first highlight",
      "",
      "> [!info] p.2 · Method · PDF ^anno-bbbb2222",
      "> second highlight",
      "",
      "%% kvs:annotations:end %%",
    ].join("\n");
    const out = removeAnnotationCallout(region, "anno-aaaa1111");
    expect(out).not.toContain("first highlight");
    expect(out).toContain("second highlight");
    expect(out).toContain("^anno-bbbb2222");
  });

  it("reads the source label from a callout title", () => {
    expect(calloutSourceLabel("p.3 · Key finding · PDF")).toBe("PDF");
    expect(calloutSourceLabel("p.1 · Method · Zotero")).toBe("Zotero");
  });
});

import { replaceAnnotationCallout } from "../src/services/annotations/collect";

describe("replaceAnnotationCallout", () => {
  it("swaps a callout in place, keeping others and the block id", () => {
    const region = [
      "%% kvs:annotations:start %%",
      "> [!quote] p.1 · Key finding · PDF ^anno-aaaa1111",
      "> old text",
      "",
      "> [!info] p.2 · Method · PDF ^anno-bbbb2222",
      "> keep me",
      "",
      "%% kvs:annotations:end %%",
    ].join("\n");
    const newCallout = "> [!warning] p.1 · Limitation · PDF ^anno-aaaa1111\n> old text\n>\n> **Note:** updated";
    const out = replaceAnnotationCallout(region, "anno-aaaa1111", newCallout);
    expect(out).toContain("[!warning] p.1 · Limitation");
    expect(out).toContain("**Note:** updated");
    expect(out).not.toContain("[!quote] p.1");
    expect(out).toContain("keep me"); // other callout intact
  });
});
