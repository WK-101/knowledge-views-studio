import { describe, it, expect } from "vitest";
import { renderAnnotationsMarkdown, upsertAnnotationsRegion, ANNOTATIONS_START, ANNOTATIONS_END } from "../src/services/annotations/render";
import { mergeAnnotations } from "../src/services/annotations/merge";
import type { KvsAnnotation } from "../src/domain/index";

const hi = (over: Partial<KvsAnnotation>): KvsAnnotation => ({
  id: "id1", kind: "highlight", text: "self-attention is all you need", comment: "", page: 3,
  rects: [{ x0: 0, y0: 100, x1: 10, y1: 110 }], source: "pdf-embedded", attachment: "p.pdf", color: "#ffd400", ...over,
});

describe("annotation rendering", () => {
  it("renders a colour-mapped callout with a block id and deep link", () => {
    const md = renderAnnotationsMarkdown([hi({ comment: "key claim" })], { linkFor: () => "p.pdf#page=3" });
    expect(md).toContain("## Annotations");
    expect(md).toContain("> [!kvs-mark-yellow] p.3 · yellow · PDF ^anno-");
    expect(md).toContain("> self-attention is all you need");
    expect(md).toContain("> **Note:** key claim");
    expect(md).toContain("> [Open ▸](p.pdf#page=3)");
  });

  it("encodes the highlight colour in the callout type, for every palette colour", () => {
    // The type is what the stylesheet recolours to the exact palette; each colour must map to its own type.
    const cases: [string, string][] = [
      ["#ffd400", "kvs-mark-yellow"],
      ["#ff6666", "kvs-mark-red"],
      ["#5fb236", "kvs-mark-green"],
      ["#2ea8e5", "kvs-mark-blue"],
      ["#a28ae5", "kvs-mark-purple"],
      ["#e56eee", "kvs-mark-magenta"],
      ["#f19837", "kvs-mark-orange"],
      ["#aaaaaa", "kvs-mark-gray"],
    ];
    for (const [hex, type] of cases) {
      expect(renderAnnotationsMarkdown([hi({ color: hex })])).toContain(`[!${type}]`);
    }
  });

  it("still maps colour onto a semantic Obsidian callout type when a caller opts in via colorToCallout", () => {
    const md = renderAnnotationsMarkdown([hi({ color: "#2ea8e5" })], { colorToCallout: { blue: "info" } });
    expect(md).toContain("[!info]");
    expect(md).not.toContain("[!kvs-mark-blue]");
  });

  it("shows a placeholder when there are no annotations", () => {
    expect(renderAnnotationsMarkdown([])).toContain("No annotations found");
  });
});

describe("managed region upsert", () => {
  it("appends when absent and replaces in place when present, preserving surrounding text", () => {
    const note = "# Paper\n\nMy own notes stay.\n";
    const once = upsertAnnotationsRegion(note, "## Annotations\n\nA");
    expect(once).toContain("My own notes stay.");
    expect(once).toContain(ANNOTATIONS_START);
    expect(once).toContain(ANNOTATIONS_END);

    const twice = upsertAnnotationsRegion(once, "## Annotations\n\nB");
    expect(twice).toContain("B");
    expect(twice).not.toContain(">A"); // old body gone
    expect((twice.match(new RegExp(ANNOTATIONS_START, "g")) ?? []).length).toBe(1); // exactly one region
    expect(twice).toContain("My own notes stay.");
  });
});

describe("merge", () => {
  it("dedupes by id and combines distinct comments", () => {
    const a = hi({ id: "x", comment: "from PDF" });
    const b = hi({ id: "x", comment: "from Zotero", source: "zotero" });
    const merged = mergeAnnotations([a], [b]);
    expect(merged).toHaveLength(1);
    expect(merged[0]!.comment).toContain("from PDF");
    expect(merged[0]!.comment).toContain("from Zotero");
  });
});
