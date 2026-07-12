import { describe, it, expect } from "vitest";
import { renderPromotedNote, DEFAULT_PROMOTED_TEMPLATE } from "../src/services/export/promoted-note";

const fields = {
  title: "Attention Is All You Need",
  authors: "Vaswani, Ashish; Shazeer, Noam",
  year: "2017",
  venue: "NeurIPS",
  doi: "10.5555/3295222",
  citekey: "vaswani2017",
  tags: ["attention", "architecture"],
};

describe("promoted note template", () => {
  it("substitutes placeholders and formats authors + tags + cite", () => {
    const out = renderPromotedNote(DEFAULT_PROMOTED_TEMPLATE, fields);
    expect(out).toContain("# Attention Is All You Need");
    expect(out).toContain("**Authors:** Vaswani, Ashish, Shazeer, Noam");
    // Authors render as a YAML list so Obsidian shows separate values.
    expect(out).toContain('authors:\n  - "Vaswani, Ashish"\n  - "Shazeer, Noam"');
    expect(out).toContain("tags: [paper, attention, architecture]");
    expect(out).toContain("**Cite:** [@vaswani2017]");
    expect(out).toContain("| vaswani2017 |  |  |  |");
  });

  it("supports a fully custom template", () => {
    expect(renderPromotedNote("{{citekey}} ({{year}})", fields)).toBe("vaswani2017 (2017)");
  });

  it("sanitises quotes so quoted YAML stays valid and leaves unknown placeholders empty", () => {
    const out = renderPromotedNote('title: "{{title}}" {{unknown}}', { ...fields, title: 'A "quoted" title' });
    expect(out).toBe("title: \"A 'quoted' title\" ");
  });

  it("renders authors as a YAML list block via authorsList", () => {
    expect(renderPromotedNote('a:{{authorsList}}', fields)).toBe('a:\n  - "Vaswani, Ashish"\n  - "Shazeer, Noam"');
    expect(renderPromotedNote('a:{{authorsList}}', { ...fields, authors: '' })).toBe('a:');
  });
});
