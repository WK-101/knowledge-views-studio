import { describe, it, expect } from "vitest";
import { parseCellBlocks, hasRenderableMarkdown, type Block } from "../src/services/export/cell-markdown";

const noImages = new Map<string, string>();
const asList = (b: Block | undefined) => (b && b.type === "list" ? b : null);

describe("parseCellBlocks", () => {
  it("parses a paragraph with inline marks", () => {
    const blocks = parseCellBlocks("**b** *i* `c` ~~s~~", noImages);
    expect(blocks).toHaveLength(1);
    const p = blocks[0]!;
    expect(p.type).toBe("p");
    if (p.type === "p") {
      expect(p.inline.find((t) => t.value === "b")?.bold).toBe(true);
      expect(p.inline.find((t) => t.value === "i")?.italic).toBe(true);
      expect(p.inline.find((t) => t.value === "c")?.code).toBe(true);
      expect(p.inline.find((t) => t.value === "s")?.strike).toBe(true);
    }
  });

  it("turns <br> into soft breaks inside a paragraph", () => {
    const blocks = parseCellBlocks("a<br>b", noImages);
    const p = blocks[0]!;
    expect(p.type).toBe("p");
    if (p.type === "p") expect(p.inline.some((t) => t.kind === "break")).toBe(true);
  });

  it("builds nested bullet lists by indentation", () => {
    const md = "- top\n  - sub a\n  - sub b\n- top2";
    const list = asList(parseCellBlocks(md, noImages)[0]);
    expect(list).not.toBeNull();
    expect(list!.ordered).toBe(false);
    expect(list!.items).toHaveLength(2);
    const firstChildren = list!.items[0]!.children;
    const nested = asList(firstChildren[0]);
    expect(nested).not.toBeNull();
    expect(nested!.items).toHaveLength(2);
    expect(nested!.items[0]!.inline[0]?.value).toBe("sub a");
  });

  it("parses ordered lists with a start number", () => {
    const list = asList(parseCellBlocks("3. third\n4. fourth", noImages)[0]);
    expect(list!.ordered).toBe(true);
    expect(list!.start).toBe(3);
  });

  it("splits into separate lists when the marker type changes", () => {
    const blocks = parseCellBlocks("- one\n- two\n1. first\n2. second", noImages);
    const lists = blocks.filter((b) => b.type === "list");
    expect(lists).toHaveLength(2);
    expect(asList(lists[0])!.ordered).toBe(false);
    expect(asList(lists[1])!.ordered).toBe(true);
    expect(asList(lists[1])!.items).toHaveLength(2);
  });

  it("parses task list checkboxes", () => {
    const list = asList(parseCellBlocks("- [ ] todo\n- [x] done", noImages)[0]);
    expect(list!.items[0]!.task).toBe(false);
    expect(list!.items[1]!.task).toBe(true);
  });

  it("parses headings, blockquotes, code fences and rules", () => {
    const blocks = parseCellBlocks("# Title\n\n> quoted\n\n```\ncode line\n```\n\n---", noImages);
    const kinds = blocks.map((b) => b.type);
    expect(kinds).toContain("heading");
    expect(kinds).toContain("quote");
    expect(kinds).toContain("code");
    expect(kinds).toContain("hr");
    const heading = blocks.find((b) => b.type === "heading");
    if (heading && heading.type === "heading") expect(heading.level).toBe(1);
    const code = blocks.find((b) => b.type === "code");
    if (code && code.type === "code") expect(code.text).toContain("code line");
  });

  it("resolves image embeds inside list items", () => {
    const images = new Map([["![[p.png]]", "data:image/png;base64,ZZ"]]);
    const list = asList(parseCellBlocks("- see ![[p.png]]", images)[0]);
    expect(list!.items[0]!.inline.some((t) => t.kind === "image" && t.src === "data:image/png;base64,ZZ")).toBe(true);
  });

  it("detects renderable markdown vs plain text", () => {
    expect(hasRenderableMarkdown("just plain text")).toBe(false);
    expect(hasRenderableMarkdown("has **bold**")).toBe(true);
    expect(hasRenderableMarkdown("- a bullet")).toBe(true);
    expect(hasRenderableMarkdown("line<br>break")).toBe(true);
  });
});
