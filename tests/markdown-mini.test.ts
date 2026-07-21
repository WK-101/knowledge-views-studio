import { describe, it, expect } from "vitest";
import { renderMarkdown } from "../extension/src/lib/markdown-mini";

describe("markdown-mini · a small, safe renderer for sticky notes", () => {
  it("escapes HTML in the source so nothing injects", () => {
    const out = renderMarkdown('<script>alert(1)</script> & "quotes"');
    expect(out).not.toContain("<script>");
    expect(out).toContain("&lt;script&gt;");
    expect(out).toContain("&amp;");
  });

  it("renders bold, italic, strikethrough, and inline code", () => {
    expect(renderMarkdown("**bold**")).toContain("<strong>bold</strong>");
    expect(renderMarkdown("_italic_")).toContain("<em>italic</em>");
    expect(renderMarkdown("~~gone~~")).toContain("<del>gone</del>");
    expect(renderMarkdown("`code`")).toContain("<code>code</code>");
  });

  it("does not treat markup inside inline code as emphasis", () => {
    const out = renderMarkdown("`a **b** c`");
    expect(out).toContain("<code>a **b** c</code>");
    expect(out).not.toContain("<strong>");
  });

  it("renders headings by level", () => {
    expect(renderMarkdown("# Title")).toContain("<h1>Title</h1>");
    expect(renderMarkdown("### Small")).toContain("<h3>Small</h3>");
  });

  it("renders unordered and ordered lists", () => {
    expect(renderMarkdown("- a\n- b")).toBe("<ul><li>a</li><li>b</li></ul>");
    expect(renderMarkdown("1. a\n2. b")).toBe("<ol><li>a</li><li>b</li></ol>");
  });

  it("renders blockquotes and horizontal rules", () => {
    expect(renderMarkdown("> quoted")).toBe("<blockquote>quoted</blockquote>");
    expect(renderMarkdown("---")).toBe("<hr>");
  });

  it("renders a fenced code block with its contents escaped and untouched", () => {
    const out = renderMarkdown("```\nconst x = 1 < 2;\n```");
    expect(out).toContain("<pre><code>const x = 1 &lt; 2;</code></pre>");
  });

  it("links only safe schemes, and renders a hostile scheme as plain text", () => {
    expect(renderMarkdown("[go](https://example.com)")).toContain(
      '<a href="https://example.com" target="_blank" rel="noreferrer noopener">go</a>',
    );
    const bad = renderMarkdown("[x](javascript:alert(1))");
    expect(bad).not.toContain("href");
    expect(bad).toContain("x");
    // A bare domain is promoted to https rather than left dead.
    expect(renderMarkdown("[site](example.com)")).toContain('href="https://example.com"');
  });

  it("wraps loose lines in paragraphs and keeps hard breaks", () => {
    expect(renderMarkdown("hello")).toBe("<p>hello</p>");
    expect(renderMarkdown("one\ntwo")).toBe("<p>one<br>two</p>");
    expect(renderMarkdown("one\n\ntwo")).toBe("<p>one</p>\n<p>two</p>");
  });

  it("returns empty for empty input", () => {
    expect(renderMarkdown("")).toBe("");
    expect(renderMarkdown("   \n  ")).toBe("");
  });
});
