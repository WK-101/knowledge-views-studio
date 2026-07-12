import { describe, it, expect } from "vitest";
import { unzipSync, strFromU8 } from "fflate";
import { buildDocx } from "../src/services/export/docx-format";
import type { ExportTable, PdfOptions } from "../src/services/export/export-format";

const opts: PdfOptions = {
  orientation: "portrait",
  pageSize: "A4",
  margin: "normal",
  fontSizePt: 10,
  fontFamily: "Georgia, serif",
  title: "Field report",
  subtitle: "Q3",
  accent: "#4c6ef5",
  zebra: true,
  includeDate: false,
  pageNumbers: true,
  repeatHeader: true,
  fitToWidth: true,
  rowNumbers: true,
};

const table: ExportTable = {
  headers: ["Task", "Owner"],
  rows: [
    ["Design", "Mara"],
    ["Build", "WES"],
  ],
  widths: [220, 120],
};

// A 1x1 transparent PNG.
const PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

describe("buildDocx", () => {
  it("produces a valid OOXML package with the table and header", () => {
    const bytes = buildDocx(table, opts);
    expect(bytes[0]).toBe(0x50); // 'P'
    expect(bytes[1]).toBe(0x4b); // 'K'
    const files = unzipSync(bytes);
    expect(Object.keys(files)).toContain("[Content_Types].xml");
    expect(Object.keys(files)).toContain("word/document.xml");
    expect(Object.keys(files)).toContain("word/_rels/document.xml.rels");
    const doc = strFromU8(files["word/document.xml"]!);
    expect(doc).toContain("<w:tbl>");
    expect(doc).toContain("Field report");
    expect(doc).toContain("Task");
    expect(doc).toContain("Mara");
    expect(doc).toContain("Georgia"); // font name derived from the CSS stack
    expect(doc).toContain("<w:tblHeader/>"); // header repeats
    expect(doc).not.toContain('w:orient="landscape"');
  });

  it("adds a page-number footer when enabled and drops it when not", () => {
    const withNums = unzipSync(buildDocx(table, opts));
    expect(Object.keys(withNums)).toContain("word/footer1.xml");
    expect(strFromU8(withNums["word/footer1.xml"]!)).toContain("PAGE");

    const noNums = unzipSync(buildDocx(table, { ...opts, pageNumbers: false }));
    expect(Object.keys(noNums)).not.toContain("word/footer1.xml");
  });

  it("renders markdown tokens as runs, breaks and hyperlinks", () => {
    const rich = {
      headers: ["Notes"],
      rows: [["x"]],
      widths: [200],
      segments: {
        "0:0": [
          {
            type: "p",
            inline: [
              { kind: "text", value: "bold", bold: true },
              { kind: "break" },
              { kind: "link", value: "site", href: "https://a.co" },
            ],
          },
        ],
      },
    } as ExportTable;
    const files = unzipSync(buildDocx(rich, { ...opts, rowNumbers: false }));
    const doc = strFromU8(files["word/document.xml"]!);
    expect(doc).toContain("<w:b/>"); // bold run
    expect(doc).toContain("<w:br/>"); // line break
    expect(doc).toContain("<w:hyperlink"); // link
    const rels = strFromU8(files["word/_rels/document.xml.rels"]!);
    expect(rels).toContain("relationships/hyperlink");
    expect(rels).toContain('Target="https://a.co"');
  });

  it("renders nested lists as native Word numbering", () => {
    const doc = {
      headers: ["Notes"],
      rows: [["x"]],
      widths: [220],
      segments: {
        "0:0": [
          {
            type: "list",
            ordered: false,
            start: 1,
            items: [
              {
                inline: [{ kind: "text", value: "parent" }],
                children: [
                  {
                    type: "list",
                    ordered: true,
                    start: 3,
                    items: [{ inline: [{ kind: "text", value: "child" }], children: [] }],
                  },
                ],
              },
            ],
          },
        ],
      },
    } as ExportTable;
    const files = unzipSync(buildDocx(doc, { ...opts, rowNumbers: false }));
    const xml = strFromU8(files["word/document.xml"]!);
    expect(xml).toContain("parent");
    expect(xml).toContain("child");
    // Native numbering: paragraphs reference numPr with ilvl 0 (parent) and ilvl 1 (nested).
    expect(xml).toContain("<w:numPr>");
    expect(xml).toContain('w:ilvl w:val="0"');
    expect(xml).toContain('w:ilvl w:val="1"');
    // A numbering part exists and defines bullet + decimal formats.
    expect(files["word/numbering.xml"]).toBeDefined();
    const numbering = strFromU8(files["word/numbering.xml"]!);
    expect(numbering).toContain("w:abstractNum");
    expect(numbering).toContain('w:numFmt w:val="bullet"');
    expect(numbering).toContain('w:numFmt w:val="decimal"');
    expect(numbering).toContain("\u2022"); // bullet char • lives in the numbering definition
    expect(numbering).toContain('w:startOverride w:val="3"'); // ordered sublist restarts at 3
    // Content types + relationship register the numbering part.
    expect(strFromU8(files["[Content_Types].xml"]!)).toContain("numbering.xml");
    expect(strFromU8(files["word/_rels/document.xml.rels"]!)).toContain("numbering.xml");
  });

  it("embeds images as media with relationships and a drawing", () => {
    const withImg: ExportTable = {
      headers: ["Name", "Photo"],
      rows: [["Mars", "![[mars.png]]"]],
      widths: [120, 120],
      segments: { "0:1": [{ type: "p", inline: [{ kind: "image", src: PNG }] }] },
    };
    const files = unzipSync(buildDocx(withImg, { ...opts, rowNumbers: false }));
    const mediaKeys = Object.keys(files).filter((k) => k.startsWith("word/media/"));
    expect(mediaKeys.length).toBe(1);
    const doc = strFromU8(files["word/document.xml"]!);
    expect(doc).toContain("<w:drawing>");
    expect(doc).toContain("<pic:pic");
    const rels = strFromU8(files["word/_rels/document.xml.rels"]!);
    expect(rels).toContain("relationships/image");
  });
});
