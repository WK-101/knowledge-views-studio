import { describe, it, expect } from "vitest";
import { asQuote, asBlockquote, asMarkdownLink, formatCopy } from "../extension/src/lib/copy-formats";

describe("copy formats · turning a selection into clipboard text", () => {
  it("quote wraps the text in typographic quotes, collapsed to one line", () => {
    expect(asQuote("attention is all you need")).toBe("\u201cattention is all you need\u201d");
    expect(asQuote("  ragged\n  across   lines  ")).toBe("\u201cragged across lines\u201d");
  });

  it("blockquote prefixes every line, keeping blank lines as a bare >", () => {
    expect(asBlockquote("one line")).toBe("> one line");
    expect(asBlockquote("first\nsecond")).toBe("> first\n> second");
    expect(asBlockquote("a\n\nb")).toBe("> a\n>\n> b");
    expect(asBlockquote("  padded  ")).toBe("> padded");
  });

  it("markdown link labels the page URL with the collapsed text, escaping brackets", () => {
    expect(asMarkdownLink("the title", "https://example.com/x")).toBe("[the title](https://example.com/x)");
    expect(asMarkdownLink("multi\nline", "https://e.com")).toBe("[multi line](https://e.com)");
    // Brackets in the label would otherwise break the link.
    expect(asMarkdownLink("see [1] here", "https://e.com")).toBe("[see \\[1\\] here](https://e.com)");
  });

  it("formatCopy dispatches to the right formatter", () => {
    expect(formatCopy("quote", "x", "https://e.com")).toBe("\u201cx\u201d");
    expect(formatCopy("blockquote", "x", "https://e.com")).toBe("> x");
    expect(formatCopy("markdown-link", "x", "https://e.com")).toBe("[x](https://e.com)");
  });
});
