import { describe, it, expect } from "vitest";
import {
  exportCsv,
  exportHtml,
  exportJson,
  exportMarkdown,
  slugify,
  type PageExport,
} from "../extension/src/lib/export-highlights";

const sample: PageExport = {
  meta: { title: "Deep Work", url: "https://example.com/deep-work", exportedAt: "2026-07-21T10:00:00.000Z" },
  highlights: [
    { id: "abcdef1234", color: "green", style: "highlight", intensity: "medium", text: "focus is a skill", note: "key point", tags: ["focus", "habits"], createdAt: "2026-07-20T09:00:00.000Z" },
    { id: "zzzz999999", color: "yellow", style: "underline", intensity: "strong", text: "shallow work\nspans lines", createdAt: "2026-07-20T10:00:00.000Z" },
  ],
  notes: [{ id: "note000001", color: "blue", body: "A **thought** to revisit", createdAt: "2026-07-20T11:00:00.000Z" }],
};

const hex = (name: string): string => (name === "green" ? "#5fb236" : name === "blue" ? "#2ea8e5" : "#ffd400");

describe("export-highlights · rich, KVS-style exports of a page's annotations", () => {
  it("markdown uses KVS coloured callouts with block ids, note, and tags", () => {
    const md = exportMarkdown(sample);
    expect(md).toContain("# Deep Work");
    expect(md).toContain("[Source](https://example.com/deep-work)");
    expect(md).toContain("## Highlights (2)");
    expect(md).toContain("> [!kvs-mark-green] green · 2026-07-20 ^anno-abcdef12");
    expect(md).toContain("> focus is a skill");
    expect(md).toContain("> **Note:** key point");
    expect(md).toContain("> #focus #habits");
    // Underline + strong surface in the meta line; multi-line quote is prefixed per line.
    expect(md).toContain("> [!kvs-mark-yellow] yellow · underline · strong · 2026-07-20");
    expect(md).toContain("> shallow work\n> spans lines");
    expect(md).toContain("## Sticky notes (1)");
    expect(md).toContain("A **thought** to revisit");
  });

  it("markdown omits empty sections", () => {
    const md = exportMarkdown({ meta: sample.meta, highlights: [], notes: [] });
    expect(md).not.toContain("## Highlights");
    expect(md).not.toContain("## Sticky notes");
    expect(md).toContain("# Deep Work");
  });

  it("html is a standalone doc, escapes text, tints marks by palette, renders note markdown", () => {
    const html = exportHtml(sample, hex);
    expect(html.startsWith("<!doctype html>")).toBe(true);
    expect(html).toContain("<title>Deep Work</title>");
    expect(html).toContain("border-left-color:#5fb236");
    expect(html).toContain("background:#5fb23633"); // colour + alpha suffix
    expect(html).toContain("#focus");
    // Sticky note body is rendered markdown, not raw.
    expect(html).toContain("<strong>thought</strong>");
  });

  it("html escapes hostile text rather than injecting it", () => {
    const html = exportHtml(
      { meta: { title: "<x>", url: "", exportedAt: "" }, highlights: [{ id: "i", color: "yellow", text: "<script>alert(1)</script>", createdAt: "" }], notes: [] },
      hex,
    );
    expect(html).not.toContain("<script>alert(1)</script>");
    expect(html).toContain("&lt;script&gt;");
  });

  it("csv has a header, one row per annotation, and quotes fields with commas/quotes/newlines", () => {
    const csv = exportCsv(sample);
    const lines = csv.split("\r\n");
    expect(lines[0]).toBe("Kind,Color,Style,Intensity,Text,Note,Tags,Created,URL");
    expect(lines[1]).toContain("highlight,green,highlight,medium,focus is a skill,key point,focus habits");
    // The multi-line quote is wrapped in quotes.
    expect(csv).toContain('"shallow work\nspans lines"');
    // The sticky note is a row too, told apart by Kind.
    expect(csv).toContain("note,blue,,,A **thought** to revisit,");
  });

  it("csv doubles embedded quotes", () => {
    const csv = exportCsv({ meta: sample.meta, highlights: [{ id: "i", color: "yellow", text: 'he said "hi"', createdAt: "" }], notes: [] });
    expect(csv).toContain('"he said ""hi"""');
  });

  it("json carries source, timestamp, and every annotation field", () => {
    const parsed = JSON.parse(exportJson(sample)) as {
      source: { title: string; url: string };
      exportedAt: string;
      highlights: { tags?: string[] }[];
      notes: { body: string }[];
    };
    expect(parsed.source.url).toBe("https://example.com/deep-work");
    expect(parsed.exportedAt).toBe("2026-07-21T10:00:00.000Z");
    expect(parsed.highlights[0]!.tags).toEqual(["focus", "habits"]);
    expect(parsed.notes[0]!.body).toBe("A **thought** to revisit");
  });

  it("slugify makes a filesystem-safe stem and falls back to 'page'", () => {
    expect(slugify("Deep Work: A Guide!")).toBe("deep-work-a-guide");
    expect(slugify("   ")).toBe("page");
    expect(slugify("")).toBe("page");
    expect(slugify("a".repeat(80)).length).toBeLessThanOrEqual(60);
  });
});
