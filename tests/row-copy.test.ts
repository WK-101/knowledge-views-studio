import { describe, it, expect } from "vitest";
import { buildRowClipboard, buildClipboardFor, buildViewBlock, parseKvsMarker, rebuildMarkdownTable } from "../src/views/copy/row-copy";
import { createProfile } from "../src/services/index";
import { makeRow } from "./_helpers";
import type { ResolvedColumn } from "../src/views/view-model";

const col = (name: string): ResolvedColumn => ({ name, label: name, typeId: "text", editable: true, role: "none" });

describe("buildRowClipboard", () => {
  const rows = [
    makeRow({ Title: "Alpha | beta", Ref: "[[Note A|The A]]" }),
    makeRow({ Title: "Gamma", Ref: "[[Folder/Note B]]" }),
  ];
  const columns = [col("Title"), col("Ref")];

  it("builds a markdown table with header, escaping pipes and keeping wikilinks", () => {
    const { plain } = buildRowClipboard(rows, columns, { linkHandling: "keep", includeHeader: true, includeHtml: false });
    const lines = plain.split("\n");
    expect(lines[0]).toBe("| Title | Ref |");
    expect(lines[1]).toBe("| --- | --- |");
    expect(lines[2]).toBe("| Alpha \\| beta | [[Note A\\|The A]] |");
    expect(lines[3]).toContain("[[Folder/Note B]]");
  });

  it("reduces wikilinks to display text when linkHandling = text", () => {
    const { plain } = buildRowClipboard(rows, columns, { linkHandling: "text", includeHeader: false, includeHtml: false });
    expect(plain).toContain("The A"); // alias wins
    expect(plain).toContain("Note B"); // basename of Folder/Note B
    expect(plain).not.toContain("[[");
  });

  it("uses the link target path when linkHandling = path", () => {
    const { plain } = buildRowClipboard([rows[1]!], columns, { linkHandling: "path", includeHeader: false, includeHtml: false });
    expect(plain).toContain("Folder/Note B");
  });

  it("emits an escaped HTML table when includeHtml is on", () => {
    const { html } = buildRowClipboard(rows, columns, { linkHandling: "text", includeHeader: true, includeHtml: true });
    expect(html).toBeDefined();
    expect(html!).toContain("<table>");
    expect(html!).toContain("<th>Title</th>");
    expect(html!).toContain("<td>The A</td>");
    expect(html!).not.toContain("[[");
  });

  it("omits html when includeHtml is off", () => {
    const payload = buildRowClipboard(rows, columns, { linkHandling: "keep", includeHeader: true, includeHtml: false });
    expect(payload.html).toBeUndefined();
  });
});


describe("row copy — <br> handling and formats", () => {
  const rows = [
    makeRow({ Task: "Line one<br>Line two", Owner: "[[People/Ann|Ann]]" }),
    makeRow({ Task: "Simple, value", Owner: "[[Bob]]" }),
  ];
  const columns = [col("Task"), col("Owner")];
  const opts = { linkHandling: "keep" as const, includeHeader: true, includeHtml: true };

  it("renders <br> as a real HTML break (not literal text) for Word/Docs", () => {
    const { html } = buildRowClipboard(rows, columns, opts);
    expect(html!).toContain("Line one<br>Line two"); // a real break element
    expect(html!).not.toContain("&lt;br&gt;"); // never the escaped literal
  });

  it("TSV is tab-separated with links reduced to text", () => {
    const tsv = buildClipboardFor("tsv", rows, columns, opts).plain;
    const lines = tsv.split("\n");
    expect(lines[0]).toBe("Task\tOwner");
    expect(lines[1]).toBe("Line one Line two\tAnn"); // <br> collapsed, alias used
    expect(tsv).not.toContain("[[");
  });

  it("CSV quotes fields containing commas", () => {
    const csv = buildClipboardFor("csv", rows, columns, opts).plain;
    expect(csv).toContain('"Simple, value"');
  });

  it("JSON is an array of objects keyed by column label", () => {
    const json = buildClipboardFor("json", rows, columns, opts).plain;
    const parsed = JSON.parse(json) as Array<Record<string, string>>;
    expect(parsed).toHaveLength(2);
    expect(parsed[1]!.Owner).toBe("Bob");
  });

  it("bullet list makes one Field: value block per row (plain + html)", () => {
    const payload = buildClipboardFor("bullets", rows, columns, opts);
    expect(payload.plain).toContain("- **Task**: Line one Line two");
    expect(payload.html!).toContain("<li><strong>Owner:</strong>");
  });
});

describe("buildViewBlock (copy as live view)", () => {
  it("serialises the view's query into a knowledge-view block", () => {
    const profile = createProfile({
      view: { type: "calendar", options: { dateField: "Due" } },
      scope: { mode: "folders", folders: ["Research"], includeSubfolders: false },
      advancedQuery: 'Status == "open"',
      sort: [{ field: "Due", direction: "asc" }],
    });
    const block = buildViewBlock(profile);
    expect(block.startsWith("```knowledge-view")).toBe(true);
    expect(block).toContain("view: calendar");
    expect(block).toContain("folders: Research");
    expect(block).toContain('query: Status == "open"');
    expect(block).toContain("sort: Due asc");
    expect(block).toContain("option.dateField: Due");
    expect(block.trimEnd().endsWith("```")).toBe(true);
  });
});


describe("round-trippable KVS rows", () => {
  const rows = [makeRow({ Score: "42", Due: "2025-02-01" })];
  const columns = [
    { name: "Score", label: "Score", typeId: "number", editable: true, role: "none" } as ResolvedColumn,
    { name: "Due", label: "Due", typeId: "date", editable: true, role: "none" } as ResolvedColumn,
  ];
  const opts = { linkHandling: "keep" as const, includeHeader: true, includeHtml: false };

  it("embeds a hidden type marker alongside the table, and round-trips the types", () => {
    const { plain } = buildClipboardFor("kvs", rows, columns, opts);
    expect(plain).toContain("<!-- kvs-view:1 "); // hidden marker present
    expect(plain).toContain("| Score | Due |"); // real table too
    const meta = parseKvsMarker(plain);
    expect(meta).not.toBeNull();
    expect(meta!.map((c) => [c.name, c.type])).toEqual([
      ["Score", "number"],
      ["Due", "date"],
    ]);
  });

  it("returns null for text without a marker", () => {
    expect(parseKvsMarker("| a | b |\n| --- | --- |\n| 1 | 2 |")).toBeNull();
  });

  it("rebuilds a clean markdown table, escaping pipes", () => {
    const md = rebuildMarkdownTable(["A", "B"], [["x | y", "z"], ["p", "q"]]);
    expect(md.split("\n")).toEqual(["| A | B |", "| --- | --- |", "| x \\| y | z |", "| p | q |"]);
  });

  it("survives unicode in column names through the marker", () => {
    const cols = [{ name: "Ürün", label: "Ürün", typeId: "text", editable: true, role: "none" } as ResolvedColumn];
    const { plain } = buildClipboardFor("kvs", [makeRow({ "Ürün": "x" })], cols, opts);
    expect(parseKvsMarker(plain)!.map((c) => c.name)).toEqual(["Ürün"]);
  });
});
