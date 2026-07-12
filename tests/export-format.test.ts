import { describe, it, expect } from "vitest";
import { unzipSync, strFromU8 } from "fflate";
import {
  buildExportTable,
  buildCsv,
  buildMarkdownTable,
  buildXlsx,
  buildPrintHtml,
  DEFAULT_CSV_OPTIONS,
  DEFAULT_MD_OPTIONS,
  DEFAULT_XLSX_OPTIONS,
  resolvePdfLayout,
  type ExportTable,
} from "../src/services/export/export-format";
import { makeRow } from "./_helpers";

const table: ExportTable = {
  headers: ["Task", "Notes"],
  rows: [
    ["Write, test", 'He said "hi"'],
    ["Ship", "line1\nline2"],
  ],
};

describe("buildExportTable", () => {
  it("selects columns and appends metadata", () => {
    const rows = [makeRow({ Task: "A", Status: "Doing" })];
    const t = buildExportTable(rows, [{ name: "Task", label: "Task" }], true);
    expect(t.headers).toEqual(["Task", "Source", "Table", "Row"]);
    expect(t.rows[0]?.[0]).toBe("A");
    // provenance metadata columns are present (values depend on the test helper)
    expect(t.rows[0]?.length).toBe(4);
  });
  it("omits metadata when not requested", () => {
    const rows = [makeRow({ Task: "A" })];
    const t = buildExportTable(rows, [{ name: "Task", label: "Task" }], false);
    expect(t.headers).toEqual(["Task"]);
  });
});

describe("buildCsv", () => {
  it("quotes fields with commas, quotes, and newlines", () => {
    const csv = buildCsv(table);
    const lines = csv.split("\r\n");
    expect(lines[0]).toBe("Task,Notes");
    expect(lines[1]).toBe('"Write, test","He said ""hi"""');
    expect(lines[2]).toBe('Ship,"line1\nline2"');
  });
});

describe("buildMarkdownTable", () => {
  it("escapes pipes and flattens newlines", () => {
    const md = buildMarkdownTable({ headers: ["A"], rows: [["x|y"], ["a\nb"]] });
    const lines = md.split("\n");
    expect(lines[0]).toBe("| A |");
    expect(lines[1]).toBe("| --- |");
    expect(lines[2]).toBe("| x\\|y |");
    expect(lines[3]).toBe("| a b |");
  });
});

describe("buildXlsx", () => {
  it("produces a valid zip whose sheet holds the values", () => {
    const bytes = buildXlsx(table);
    expect(bytes[0]).toBe(0x50); // 'P'
    expect(bytes[1]).toBe(0x4b); // 'K'
    const files = unzipSync(bytes);
    expect(Object.keys(files)).toContain("xl/worksheets/sheet1.xml");
    const sheet = strFromU8(files["xl/worksheets/sheet1.xml"]!);
    expect(sheet).toContain("Task");
    expect(sheet).toContain("He said &quot;hi&quot;");
  });
});

describe("buildPrintHtml", () => {
  const opts = {
    orientation: "landscape" as const,
    pageSize: "A4" as const,
    margin: "narrow" as const,
    fontSizePt: 10,
    fontFamily: 'Georgia, "Times New Roman", serif',
    title: "My Report",
    subtitle: "Q3 summary",
    accent: "#ff0000",
    zebra: true,
    includeDate: false,
    pageNumbers: true,
    repeatHeader: true,
    fitToWidth: true,
    rowNumbers: true,
  };

  it("bakes page settings, styling and paginates for print", () => {
    const html = buildPrintHtml(table, opts, "print");
    expect(html).toContain("size: A4 landscape");
    expect(html).toContain("margin: 8mm");
    expect(html).toContain("<h1>My Report</h1>");
    expect(html).toContain("Q3 summary");
    expect(html).toContain("<th>Task</th>");
    expect(html).toContain("#ff0000"); // accent applied
    expect(html).toContain("nth-child(even)"); // zebra on
    expect(html).toContain("<th>#</th>"); // row-number column
    expect(html).toContain("<td>1</td>"); // first row numbered
    expect(html).toContain("Georgia"); // serif stack
    expect(html).toContain('id="kvs-doc"'); // pagination wraps the doc
    expect(html).toContain("data-ready"); // self-signals when paginated
    expect(html).toContain("Page "); // numbered footer built by the script
  });

  it("renders a continuous, script-free preview with a repeating header", () => {
    const html = buildPrintHtml(table, { ...opts, pageNumbers: false }, "preview");
    expect(html).toContain("table-header-group"); // native header repeat
    expect(html).not.toContain("data-ready"); // no pagination script in preview
    expect(html).not.toContain('id="kvs-doc"');
  });

  it("sizes columns from the view's widths via a proportional colgroup", () => {
    const withWidths = {
      headers: ["A", "B"],
      rows: [["1", "2"]],
      widths: [300, 100] as (number | undefined)[],
    };
    const html = buildPrintHtml(withWidths, opts, "preview");
    expect(html).toContain("<colgroup>");
    expect(html).toContain('<col style="width:');
    // 300 vs 100 (+30 for the row-number col) — first data column should be the widest.
    const cols = [...html.matchAll(/<col style="width:([\d.]+)%">/g)].map((m) => Number(m[1]));
    expect(cols.length).toBeGreaterThanOrEqual(2);
    expect(Math.max(...cols)).toBeGreaterThan(cols[0]!); // the 300px column dominates
  });

  it("embeds resolved images as <img> and leaves other cells as text", () => {
    const withImg: ExportTable = {
      headers: ["Name", "Photo"],
      rows: [["Mars", "![[mars.png]]"]],
      segments: { "0:1": [{ type: "p", inline: [{ kind: "image", src: "data:image/png;base64,AAAA" }] }] },
    };
    const html = buildPrintHtml(withImg, { ...opts, rowNumbers: false }, "preview");
    expect(html).toContain('<img src="data:image/png;base64,AAAA"');
    expect(html).toContain("kvs-img-cell");
    expect(html).toContain("<td>Mars</td>"); // non-image cell stays text
    expect(html).not.toContain("![[mars.png]]"); // raw embed text not shown
  });

  it("renders nested lists, task checkboxes and blockquotes as HTML", () => {
    const doc: ExportTable = {
      headers: ["Notes"],
      rows: [["x"]],
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
                    ordered: false,
                    start: 1,
                    items: [{ inline: [{ kind: "text", value: "child" }], children: [] }],
                  },
                ],
              },
              { inline: [{ kind: "text", value: "done" }], task: true, children: [] },
            ],
          },
          { type: "quote", blocks: [{ type: "p", inline: [{ kind: "text", value: "q" }] }] },
        ],
      },
    };
    const html = buildPrintHtml(doc, { ...opts, rowNumbers: false }, "preview");
    expect(html).toContain("<ul");
    expect(html).toContain("parent");
    expect(html).toContain("child");
    expect(html).toContain("<ul class=\"kvs-md-list\"><li>child"); // nested list inside the item
    expect(html).toContain("\u2611"); // checked task box ☑
    expect(html).toContain("<blockquote");
  });

  it("renders markdown marks and turns breaks into <br>", () => {
    const rich: ExportTable = {
      headers: ["Notes"],
      rows: [["x"]],
      segments: {
        "0:0": [
          {
            type: "p",
            inline: [
              { kind: "text", value: "hi ", bold: true },
              { kind: "text", value: "code", code: true },
              { kind: "break" },
              { kind: "link", value: "site", href: "https://a.co" },
            ],
          },
        ],
      },
    };
    const html = buildPrintHtml(rich, { ...opts, rowNumbers: false }, "preview");
    expect(html).toContain("<strong>hi </strong>");
    expect(html).toContain("<code>code</code>");
    expect(html).toContain("<br />");
    expect(html).toContain('<a href="https://a.co">site</a>');
  });

  it("interleaves text and images for rich-text cells", () => {
    const rich: ExportTable = {
      headers: ["Notes"],
      rows: [["see ![[c.png]] here"]],
      segments: { "0:0": [{ type: "p", inline: [{ kind: "text", value: "see " }, { kind: "image", src: "data:image/png;base64,BBBB" }, { kind: "text", value: " here" }] }] },
    };
    const html = buildPrintHtml(rich, { ...opts, rowNumbers: false }, "preview");
    expect(html).toContain("see ");
    expect(html).toContain('<img src="data:image/png;base64,BBBB"');
    expect(html).toContain(" here");
    expect(html).toContain("kvs-img-cell"); // has an image, so image styling applies
  });

  it("omits optional chrome when disabled and guards a bad accent", () => {
    const html = buildPrintHtml(table, {
      ...opts,
      zebra: false,
      rowNumbers: false,
      pageNumbers: false,
      repeatHeader: false,
      subtitle: "",
      accent: "not-a-color",
    });
    expect(html).not.toContain("nth-child(even)");
    expect(html).not.toContain("<th>#</th>");
    expect(html).toContain("#4c6ef5"); // fell back to the default accent
  });
});

describe("resolvePdfLayout (auto page + orientation)", () => {
  const wide = {
    headers: [
      "Project name",
      "Primary owner",
      "Current status",
      "Priority level",
      "Target due date",
      "Effort estimate",
      "Related tags",
      "Latest notes",
      "Stakeholder",
      "Department",
      "Milestone",
      "Last updated",
    ],
    rows: [] as string[][],
  };
  const narrow = { headers: ["a", "b"], rows: [["1", "2"]] };
  const base = {
    orientation: "auto" as const,
    pageSize: "auto" as const,
    margin: "normal" as const,
    fontSizePt: 10,
    fontFamily: "Arial, sans-serif",
    title: "",
    subtitle: "",
    accent: "#4c6ef5",
    zebra: false,
    includeDate: false,
    pageNumbers: false,
    repeatHeader: true,
    fitToWidth: true,
    rowNumbers: false,
  };

  it("goes landscape for many columns and stays portrait for few", () => {
    expect(resolvePdfLayout(wide, base).orientation).toBe("landscape");
    expect(resolvePdfLayout(narrow, base).orientation).toBe("portrait");
  });

  it("escalates when the view's own column widths are large", () => {
    const wideByWidth = {
      headers: ["a", "b", "c"],
      rows: [],
      widths: [700, 700, 700] as (number | undefined)[], // ~555mm of columns
    };
    const layout = resolvePdfLayout(wideByWidth, base);
    // Too wide for portrait A4 (180mm) — must land on a larger/landscape page.
    expect(layout.orientation === "landscape" || layout.pageSize !== "A4").toBe(true);
  });

  it("honours explicit size and orientation over auto", () => {
    const forced = resolvePdfLayout(wide, { ...base, orientation: "portrait", pageSize: "Executive" });
    expect(forced.orientation).toBe("portrait");
    expect(forced.pageSize).toBe("Executive");
  });
});

describe("buildExportTable widths", () => {
  it("carries per-column widths (undefined for metadata columns)", () => {
    const rows = [makeRow({ Task: "x" })];
    const t = buildExportTable(rows, [{ name: "Task", label: "Task", width: 220 }], true);
    expect(t.widths?.[0]).toBe(220);
    // three appended metadata columns have no width
    expect(t.widths?.slice(1)).toEqual([undefined, undefined, undefined]);
  });
});

describe("advanced export options", () => {
  it("CSV honours delimiter, quoting, newline and BOM", () => {
    const semi = buildCsv(table, { ...DEFAULT_CSV_OPTIONS, delimiter: ";" });
    expect(semi.split("\r\n")[0]).toBe("Task;Notes");
    const tsv = buildCsv(table, { ...DEFAULT_CSV_OPTIONS, delimiter: "\t" });
    expect(tsv.split("\r\n")[0]).toBe("Task\tNotes");
    const lf = buildCsv(table, { ...DEFAULT_CSV_OPTIONS, newline: "lf" });
    expect(lf.includes("\r\n")).toBe(false);
    expect(lf.includes("\n")).toBe(true);
    expect(buildCsv(table, { ...DEFAULT_CSV_OPTIONS, bom: true }).charCodeAt(0)).toBe(0xfeff);
    const quoted = buildCsv(table, { ...DEFAULT_CSV_OPTIONS, quoteAll: true });
    expect(quoted.split("\r\n")[0]).toBe('"Task","Notes"');
  });

  it("CSV can omit the header row", () => {
    const noHeader = buildCsv(table, { ...DEFAULT_CSV_OPTIONS, includeHeader: false });
    expect(noHeader.startsWith("Task")).toBe(false);
    expect(noHeader.split("\r\n")).toHaveLength(2);
  });

  it("Markdown honours alignment and an optional title", () => {
    const centered = buildMarkdownTable(table, { ...DEFAULT_MD_OPTIONS, align: "center" });
    expect(centered.split("\n")[1]).toBe("| :---: | :---: |");
    const titled = buildMarkdownTable(table, { ...DEFAULT_MD_OPTIONS, includeTitle: true, title: "My export" });
    expect(titled.split("\n")[0]).toBe("# My export");
  });

  it("XLSX includes a styles part, a custom sheet name, freeze pane and auto-filter", () => {
    const bytes = buildXlsx(table, { ...DEFAULT_XLSX_OPTIONS, sheetName: "Tasks", freezeHeader: true, autoFilter: true });
    const files = unzipSync(bytes);
    expect(Object.keys(files)).toContain("xl/styles.xml");
    expect(strFromU8(files["xl/workbook.xml"]!)).toContain('name="Tasks"');
    const sheet = strFromU8(files["xl/worksheets/sheet1.xml"]!);
    expect(sheet).toContain('state="frozen"');
    expect(sheet).toContain("<autoFilter");
  });

  it("XLSX sheet name is sanitised and length-capped", () => {
    const bytes = buildXlsx(table, { ...DEFAULT_XLSX_OPTIONS, sheetName: "a/b:c*".padEnd(40, "x") });
    const wb = strFromU8(unzipSync(bytes)["xl/workbook.xml"]!);
    const name = /name="([^"]*)"/.exec(wb)?.[1] ?? "";
    expect(name.length).toBeLessThanOrEqual(31);
    expect(/[\\/?*[\]:]/.test(name)).toBe(false);
  });
});
