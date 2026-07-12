import { describe, it, expect } from "vitest";
import { zipSync, strToU8 } from "fflate";
import { noteToDocs, rowsToDocs, extractOfficeText, extractEpubText, sectionsToDocs } from "../src/services/search/extract";

describe("note extraction (per-heading + annotations)", () => {
  it("splits into heading sections and a separate annotations doc; frontmatter → fields", () => {
    const content = [
      "---",
      "title: Attention Is All You Need",
      "authors:",
      "  - Vaswani",
      "tags: transformers",
      "---",
      "Intro line about the model.",
      "## Method",
      "The model uses self-attention over tokens.",
      "## Results",
      "State of the art on translation.",
      "%% kvs:annotations:start %%",
      "> [!quote] p.3 · Key finding · PDF ^anno-1",
      "> attention is powerful and general",
      "%% kvs:annotations:end %%",
    ].join("\n");
    const docs = noteToDocs("papers/attention.md", content);
    const notes = docs.filter((d) => d.source === "note");
    const anno = docs.find((d) => d.source === "annotation");

    // intro + 2 heading sections
    expect(notes.map((d) => d.location)).toEqual([
      "Attention Is All You Need",
      "Attention Is All You Need › Method",
      "Attention Is All You Need › Results",
    ]);
    // section text stays within its section
    const method = notes.find((d) => d.meta?.["heading"] === "Method")!;
    expect(method.text).toContain("self-attention");
    expect(method.text).not.toContain("State of the art");
    // frontmatter fields ride on every section (for field/tag search + heading boosting), and each
    // section also exposes its heading as a field
    expect(notes[0]!.fields).toMatchObject({ title: "Attention Is All You Need", authors: "Vaswani" });
    expect(notes[1]!.fields).toMatchObject({ authors: "Vaswani", heading: "Method" });
    expect(notes[2]!.fields).toMatchObject({ heading: "Results" });
    // annotations are their own doc, not in the body sections
    expect(anno).toBeTruthy();
    expect(anno!.text).toContain("attention is powerful");
    expect(notes.some((d) => d.text.includes("attention is powerful"))).toBe(false); // no double-indexing
  });

  it("a note with no headings yields a single intro section", () => {
    const docs = noteToDocs("n.md", "just some body text here");
    expect(docs.filter((d) => d.source === "note")).toHaveLength(1);
    expect(docs[0]!.meta?.["heading"]).toBeUndefined();
  });
});

describe("row extraction", () => {
  it("makes one doc per table row with columns as fields", () => {
    const content = ["| Title | Status | Year |", "| --- | --- | --- |", "| BERT | done | 2018 |", "| GPT | reading | 2020 |"].join("\n");
    const docs = rowsToDocs("dash.md", content);
    expect(docs).toHaveLength(2);
    expect(docs[0]!.source).toBe("row");
    expect(docs[0]!.text).toBe("BERT done 2018");
    expect(docs[0]!.fields).toMatchObject({ Title: "BERT", Status: "done", Year: "2018" });
    expect(docs[0]!.id).toContain("row:dash.md#");
  });
  it("skips empty rows", () => {
    expect(rowsToDocs("x.md", "| A | B |\n| --- | --- |\n|  |  |")).toEqual([]);
  });
});

describe("Office full-text extraction", () => {
  it("Word: pulls all body text", () => {
    const doc = `<w:document><w:body><w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world of search</w:t></w:r></w:p></w:body></w:document>`;
    const zip = zipSync({ "word/document.xml": strToU8(doc) });
    expect(extractOfficeText(zip.buffer as ArrayBuffer, "word")[0]!.text).toBe("Hello world of search");
  });
  it("Excel: pulls shared-string text", () => {
    const sst = `<sst><si><t>Revenue</t></si><si><t>Q4 target exceeded</t></si></sst>`;
    const zip = zipSync({ "xl/sharedStrings.xml": strToU8(sst) });
    expect(extractOfficeText(zip.buffer as ArrayBuffer, "excel")[0]!.text).toBe("Revenue Q4 target exceeded");
  });
  it("PowerPoint: one section per slide", () => {
    const s1 = `<p:sld><a:p><a:r><a:t>Slide one title</a:t></a:r></a:p></p:sld>`;
    const s2 = `<p:sld><a:p><a:r><a:t>Slide two content</a:t></a:r></a:p></p:sld>`;
    const zip = zipSync({ "ppt/slides/slide1.xml": strToU8(s1), "ppt/slides/slide2.xml": strToU8(s2) });
    const secs = extractOfficeText(zip.buffer as ArrayBuffer, "powerpoint");
    expect(secs).toHaveLength(2);
    expect(secs[0]).toMatchObject({ location: "Slide 1", text: "Slide one title" });
    expect(secs[1]).toMatchObject({ location: "Slide 2", text: "Slide two content" });
  });
});

describe("EPUB full-text extraction", () => {
  it("extracts text per chapter, stripping tags/scripts", () => {
    const ch1 = `<html><head><style>.x{}</style></head><body><h1>Chapter 1</h1><p>It was a dark night.</p><script>bad()</script></body></html>`;
    const zip = zipSync({ "OEBPS/chapter1.xhtml": strToU8(ch1), "META-INF/container.xml": strToU8("<x/>") });
    const secs = extractEpubText(zip.buffer as ArrayBuffer);
    expect(secs).toHaveLength(1);
    expect(secs[0]!.text).toContain("Chapter 1");
    expect(secs[0]!.text).toContain("dark night");
    expect(secs[0]!.text).not.toContain("bad()");
  });
});

describe("sectionsToDocs", () => {
  it("wraps sections into docs with jump metadata", () => {
    const docs = sectionsToDocs("pdf", "book.pdf", "pdf", "pdf", [{ location: "p.5", text: "some page text" }]);
    expect(docs[0]).toMatchObject({ id: "pdf:book.pdf#p.5", source: "pdf", format: "pdf" });
    expect(docs[0]!.location).toContain("p.5");
    expect(docs[0]!.meta).toMatchObject({ path: "book.pdf", section: "p.5" });
  });
});
