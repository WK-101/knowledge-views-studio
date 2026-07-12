import { describe, it, expect } from "vitest";
import {
  isTableSeparator,
  splitTableRow,
  stripInlineMarkdown,
  escapeTableCell,
  extractImageEmbeds,
  decodeCellText,
} from "../src/util/markdown";

describe("isTableSeparator", () => {
  it("accepts pipe-delimited separators with alignment colons", () => {
    expect(isTableSeparator("| --- | :--: | ---: |")).toBe(true);
    expect(isTableSeparator("---|:--|--:")).toBe(true);
  });

  it("rejects prose and non-separator lines", () => {
    expect(isTableSeparator("hello | world")).toBe(false);
    expect(isTableSeparator("| a | b |")).toBe(false);
    expect(isTableSeparator("")).toBe(false);
  });
});

describe("splitTableRow", () => {
  it("does not split on pipes inside code, wikilinks, links, or escapes", () => {
    const cells = splitTableRow("| a | b\\|c | `d|e` | [[x|y]] | [t](http://h?a|b) |");
    // `\|` does not split the row *and* yields a real pipe: the cell's value is what the author meant,
    // not the escape artifact. (It used to come back as "b\\|c", which then leaked the backslash into
    // filters, sorts, search and clipboard copies.) escapeTableCell restores the escape on write.
    expect(cells).toEqual(["a", "b|c", "`d|e`", "[[x|y]]", "[t](http://h?a|b)"]);
  });

  it("trims cells and tolerates missing outer pipes", () => {
    expect(splitTableRow("a | b | c")).toEqual(["a", "b", "c"]);
  });
});

describe("stripInlineMarkdown", () => {
  it("reduces wikilinks, links, images, and emphasis to plain text", () => {
    expect(stripInlineMarkdown("**bold** and [[Note|Alias]]")).toBe("bold and Alias");
    expect(stripInlineMarkdown("see [docs](http://x)")).toBe("see docs");
    expect(stripInlineMarkdown("![[diagram.png]]")).toBe("[image: diagram.png]");
  });
});

describe("escapeTableCell", () => {
  it("escapes pipes and converts newlines to <br>", () => {
    expect(escapeTableCell("a|b\nc")).toBe("a\\|b<br>c");
  });
});

describe("extractImageEmbeds", () => {
  it("finds internal and external image embeds", () => {
    const embeds = extractImageEmbeds("text ![[a.png]] more ![alt](b.jpg)");
    expect(embeds).toEqual(["![[a.png]]", "![](b.jpg)"]);
  });
});

describe("rich cell round-trip (multi-line + images)", () => {
  it("decodes <br> to newlines for editing and re-encodes on write", () => {
    const stored = "Para one.<br><br>Para two.<br>- a bullet";
    const editing = decodeCellText(stored);
    expect(editing).toBe("Para one.\n\nPara two.\n- a bullet");
    expect(escapeTableCell(editing)).toBe(stored);
  });

  it("keeps image embeds and escapes pipes across the round-trip", () => {
    const editing = "See ![[fig1.png]] and the table | with a pipe";
    const stored = escapeTableCell(editing);
    expect(stored).toContain("![[fig1.png]]");
    expect(stored).toContain("\\|");
    expect(decodeCellText(stored)).toContain("![[fig1.png]]");
  });
});
