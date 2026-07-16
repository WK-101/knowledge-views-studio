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

  it("fills abstract, annotations, and zotero-key when the paper is in Zotero", () => {
    const out = renderPromotedNote("A: {{abstract}} | N: {{annotations}} | K: {{zoteroKey}}", {
      ...fields,
      abstract: "We propose X.",
      annotations: "- highlight one",
      zoteroKey: "ZKEY1",
    });
    expect(out).toBe("A: We propose X. | N: - highlight one | K: ZKEY1");
  });

  it("leaves the same placeholders empty (not undefined) when the paper is not in Zotero — identical structure", () => {
    // The whole point of unifying: a non-Zotero promotion uses the same template; the Zotero-only fields
    // are simply empty, so both notes have the same sections.
    const out = renderPromotedNote('abstract: {{abstract}}\nkey: "{{zoteroKey}}"\nnotes: {{annotations}}', fields);
    expect(out).toBe('abstract: \nkey: ""\nnotes: ');
  });

  it("the default template carries the unified sections (Abstract, Annotations, zotero-key)", () => {
    const out = renderPromotedNote(DEFAULT_PROMOTED_TEMPLATE, { ...fields, abstract: "Abs.", annotations: "- a", zoteroKey: "ZK" });
    expect(out).toContain("## Abstract");
    expect(out).toContain("Abs.");
    expect(out).toContain("## Annotations");
    expect(out).toContain("- a");
    expect(out).toContain('zotero-key: "ZK"');
  });
});
