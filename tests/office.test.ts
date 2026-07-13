import { describe, it, expect } from "vitest";
import { zipSync, strToU8 } from "fflate";
import { parseDocxComments, parseXlsxComments, parseXlsxThreadedComments, parsePptxComments, parsePptxAuthors, readOfficeAnnotations, officeCommentToAnnotation } from "../src/services/annotations/office";

describe("Office comment parsers", () => {
  it("parses Word comments with author + entity decoding", () => {
    const xml = `<w:comments><w:comment w:id="1" w:author="Alice" w:date="2024-01-02T00:00:00Z"><w:p><w:r><w:t>Needs a citation &amp; a figure</w:t></w:r></w:p></w:comment></w:comments>`;
    const c = parseDocxComments(xml);
    expect(c).toHaveLength(1);
    expect(c[0]).toMatchObject({ text: "Needs a citation & a figure", author: "Alice" });
  });

  it("parses Excel legacy comments with cell ref + author lookup", () => {
    const xml = `<comments><authors><author>Bob</author></authors><commentList><comment ref="B4" authorId="0"><text><r><t>Check this total</t></r></text></comment></commentList></comments>`;
    const c = parseXlsxComments(xml);
    expect(c[0]).toMatchObject({ text: "Check this total", ref: "B4", author: "Bob" });
  });

  it("parses Excel threaded comments", () => {
    const xml = `<ThreadedComments><threadedComment ref="C7" id="{1}"><text>Threaded note</text></threadedComment></ThreadedComments>`;
    expect(parseXlsxThreadedComments(xml)[0]).toMatchObject({ text: "Threaded note", ref: "C7" });
  });

  it("parses PowerPoint comments with author lookup", () => {
    const authors = parsePptxAuthors(`<p:cmAuthorLst><p:cmAuthor id="0" name="Carol"/></p:cmAuthorLst>`);
    const c = parsePptxComments(`<p:cmLst><p:cm authorId="0"><p:text>Slide note</p:text></p:cm></p:cmLst>`, authors);
    expect(c[0]).toMatchObject({ text: "Slide note", author: "Carol" });
  });
});

describe("readOfficeAnnotations (real zip)", () => {
  it("unzips a .docx and yields note annotations with distinct ids", () => {
    const zip = zipSync({
      "word/comments.xml": strToU8(`<w:comments><w:comment w:id="1" w:author="A"><w:p><w:r><w:t>first</w:t></w:r></w:p></w:comment><w:comment w:id="2"><w:p><w:r><w:t>second</w:t></w:r></w:p></w:comment></w:comments>`),
    });
    const anns = readOfficeAnnotations(zip.buffer, "notes.docx", "word");
    expect(anns).toHaveLength(2);
    expect(anns[0]!.source).toBe("docx");
    expect(anns[0]!.comment).toBe("first");
    expect(anns[0]!.id).not.toBe(anns[1]!.id); // distinct ids per comment
  });

  it("returns [] for non-zip bytes without throwing", () => {
    expect(readOfficeAnnotations(strToU8("not a zip").buffer, "x.docx", "word")).toEqual([]);
  });
});

describe("officeCommentToAnnotation", () => {
  it("maps to a note with a location label from the ref", () => {
    const a = officeCommentToAnnotation({ text: "hi", ref: "A1" }, "book.xlsx", "excel");
    expect(a).toMatchObject({ kind: "note", comment: "hi", pageLabel: "A1", source: "xlsx" });
  });
});

import { parseDocxHighlights } from "../src/services/annotations/office";

describe("Word highlighted-text extraction", () => {
  it("extracts highlighted runs, merging consecutive same-colour runs", () => {
    const xml =
      `<w:p>` +
      `<w:r><w:rPr><w:highlight w:val="yellow"/></w:rPr><w:t>self-</w:t></w:r>` +
      `<w:r><w:rPr><w:highlight w:val="yellow"/></w:rPr><w:t>attention</w:t></w:r>` +
      `<w:r><w:t> is </w:t></w:r>` +
      `<w:r><w:rPr><w:highlight w:val="green"/></w:rPr><w:t>a method</w:t></w:r>` +
      `</w:p>`;
    const h = parseDocxHighlights(xml);
    expect(h).toHaveLength(2);
    expect(h[0]).toMatchObject({ text: "self-attention", color: "#ffff00" }); // merged
    expect(h[1]).toMatchObject({ text: "a method", color: "#00ff00" });
  });

  it("reads highlights from a real .docx as highlight annotations", () => {
    const doc = `<w:document><w:body><w:p><w:r><w:rPr><w:highlight w:val="red"/></w:rPr><w:t>important claim</w:t></w:r></w:p></w:body></w:document>`;
    const zip = zipSync({ "word/document.xml": strToU8(doc) });
    const anns = readOfficeAnnotations(zip.buffer, "notes.docx", "word");
    expect(anns).toHaveLength(1);
    expect(anns[0]).toMatchObject({ kind: "highlight", text: "important claim", color: "#ff0000", source: "docx" });
  });
});

import { parsePptxHighlights, parseXlsxFills, parseXlsxCellFillIds, parseSharedStrings, parseXlsxHighlightCells } from "../src/services/annotations/office";

describe("repeated highlights get distinct ids (dedup fix)", () => {
  it("keeps every identical-text highlight as a separate annotation", () => {
    const runs = ["yellow", "green", "yellow"].map((c) => `<w:r><w:rPr><w:highlight w:val="${c}"/></w:rPr><w:t>TEST</w:t></w:r><w:r><w:t> x </w:t></w:r>`).join("");
    const zip = zipSync({ "word/document.xml": strToU8(`<w:document><w:body><w:p>${runs}</w:p></w:body></w:document>`) });
    const anns = readOfficeAnnotations(zip.buffer, "d.docx", "word");
    expect(anns).toHaveLength(3);
    expect(new Set(anns.map((a) => a.id)).size).toBe(3); // all distinct despite identical text
    expect(anns.map((a) => a.color)).toEqual(["#ffff00", "#00ff00", "#ffff00"]);
  });
});

describe("PowerPoint highlighted text", () => {
  it("extracts a:highlight runs with their colour", () => {
    const slide = `<p:sld><a:p><a:r><a:rPr><a:highlight><a:srgbClr val="00B0F0"/></a:highlight></a:rPr><a:t>key point</a:t></a:r></a:p></p:sld>`;
    const zip = zipSync({ "ppt/slides/slide1.xml": strToU8(slide) });
    const anns = readOfficeAnnotations(zip.buffer, "deck.pptx", "powerpoint");
    expect(anns).toHaveLength(1);
    expect(anns[0]).toMatchObject({ kind: "highlight", text: "key point", color: "#00b0f0", source: "pptx", pageLabel: "Slide 1" });
  });
});

describe("Excel highlighted cells", () => {
  it("reads cells with a user fill + value, by cell reference", () => {
    const styles = `<styleSheet><fills><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill></fills><cellXfs><xf fillId="0"/><xf fillId="2"/></cellXfs></styleSheet>`;
    const shared = `<sst><si><t>flagged value</t></si></sst>`;
    const sheet = `<worksheet><sheetData><row><c r="A1" s="0"><v>1</v></c><c r="B2" s="1" t="s"><v>0</v></c></row></sheetData></worksheet>`;
    const zip = zipSync({ "xl/styles.xml": strToU8(styles), "xl/sharedStrings.xml": strToU8(shared), "xl/worksheets/sheet1.xml": strToU8(sheet) });
    const anns = readOfficeAnnotations(zip.buffer, "book.xlsx", "excel");
    expect(anns).toHaveLength(1); // only B2 (fillId 2, has value); A1 has no fill
    expect(anns[0]).toMatchObject({ kind: "highlight", text: "flagged value", color: "#ffff00", pageLabel: "B2", source: "xlsx" });
  });

  it("parses fills, cell formats and shared strings", () => {
    expect(parseXlsxFills(`<fills><fill><patternFill patternType="solid"><fgColor rgb="FF00FF00"/></patternFill></fill></fills>`)).toEqual(["#00ff00"]);
    expect(parseXlsxCellFillIds(`<cellXfs><xf fillId="0"/><xf fillId="3"><alignment/></xf></cellXfs>`)).toEqual([0, 3]);
    expect(parseSharedStrings(`<sst><si><t>a</t></si><si><t>b</t></si></sst>`)).toEqual(["a", "b"]);
    expect(parsePptxHighlights(`<a:r><a:rPr><a:highlight><a:srgbClr val="FF0000"/></a:highlight></a:rPr><a:t>x</a:t></a:r>`)[0]!.color).toBe("#ff0000");
    void parseXlsxHighlightCells;
  });
});
