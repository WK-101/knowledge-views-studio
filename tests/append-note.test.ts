import { describe, it, expect } from "vitest";
import { appendToNote, capturedAppendBlock } from "../src/services/capture/append-note";

const note = ["# Daily log", "", "## Captured", "", "- earlier thing", "", "## Tasks", "", "- [ ] a task", ""].join("\n");

describe("append · to the end of a note", () => {
  it("appends after the existing content with one blank line", () => {
    const res = appendToNote("Existing.\n", "New block.");
    expect(res.ok).toBe(true);
    expect(res.content).toBe("Existing.\n\nNew block.\n");
  });

  it("doesn't widen the gap on repeated appends", () => {
    const once = appendToNote("Existing.\n", "One.");
    const twice = appendToNote(once.content, "Two.");
    expect(twice.content).toBe("Existing.\n\nOne.\n\nTwo.\n");
  });

  it("copes with an empty note", () => {
    expect(appendToNote("", "First.").content).toBe("First.\n");
  });

  it("refuses an empty block rather than writing a stray gap", () => {
    const res = appendToNote("Existing.\n", "   ");
    expect(res.ok).toBe(false);
    expect(res.content).toBe("Existing.\n");
  });
});

describe("append · under a heading", () => {
  it("lands at the end of the heading's section, not the end of the note", () => {
    const res = appendToNote(note, "- new thing", { heading: "Captured" });
    const lines = res.content.split("\n");
    expect(lines.indexOf("- new thing")).toBeGreaterThan(lines.indexOf("- earlier thing"));
    expect(lines.indexOf("- new thing")).toBeLessThan(lines.indexOf("## Tasks"));
  });

  it("owns its subsections: appends after a ### that belongs to the ##", () => {
    // Stopping at the first deeper heading would land in the middle of the section.
    const nested = ["## Captured", "", "text", "", "### Detail", "", "more", "", "## Next", ""].join("\n");
    const res = appendToNote(nested, "appended", { heading: "Captured" });
    const lines = res.content.split("\n");
    expect(lines.indexOf("appended")).toBeGreaterThan(lines.indexOf("more"));
    expect(lines.indexOf("appended")).toBeLessThan(lines.indexOf("## Next"));
  });

  it("matches the heading whatever its level and case", () => {
    const res = appendToNote("### captured\n\nx\n", "y", { heading: "Captured" });
    expect(res.ok).toBe(true);
    expect(res.content).toContain("y");
  });

  it("does NOT match a heading that merely starts the same way", () => {
    const res = appendToNote("## Captured elsewhere\n\nx\n", "y", { heading: "Captured" });
    expect(res.ok).toBe(false);
  });

  it("refuses a missing heading unless told to create it", () => {
    const res = appendToNote("# Note\n", "y", { heading: "Captured" });
    expect(res.ok).toBe(false);
    expect(res.reason).toContain("Captured");
  });

  it("creates the heading when asked, once, at the end", () => {
    const first = appendToNote("# Note\n", "one", { heading: "Captured", createHeading: true });
    expect(first.ok).toBe(true);
    expect(first.createdHeading).toBe(true);
    const second = appendToNote(first.content, "two", { heading: "Captured", createHeading: true });
    expect(second.createdHeading).toBeUndefined();
    expect(second.content.match(/## Captured/g)).toHaveLength(1);
    const lines = second.content.split("\n");
    expect(lines.indexOf("two")).toBeGreaterThan(lines.indexOf("one"));
  });

  it("appends into an empty section directly under its heading", () => {
    const res = appendToNote("## Captured\n\n## Next\n", "first", { heading: "Captured" });
    const lines = res.content.split("\n");
    expect(lines.indexOf("first")).toBeGreaterThan(lines.indexOf("## Captured"));
    expect(lines.indexOf("first")).toBeLessThan(lines.indexOf("## Next"));
  });

  it("preserves CRLF endings rather than mixing them", () => {
    const res = appendToNote("A.\r\n", "B.");
    expect(res.content).toBe("A.\r\n\r\nB.\r\n");
  });
});

describe("append · the block a capture becomes", () => {
  it("writes a linked source line, then the body", () => {
    const block = capturedAppendBlock({ Title: "A Read" }, "https://x/a", "The article.");
    expect(block).toBe("**[A Read](https://x/a)**\n\nThe article.");
  });

  it("links the bare url when there's no title", () => {
    expect(capturedAppendBlock({}, "https://x/a", "Body.")).toBe("**[https://x/a](https://x/a)**\n\nBody.");
  });

  it("is just the body when there's neither title nor url", () => {
    expect(capturedAppendBlock({}, "", "Body only.")).toBe("Body only.");
  });

  it("is just the source line when there's no body — properties-only append", () => {
    expect(capturedAppendBlock({ Title: "T" }, "https://x", "")).toBe("**[T](https://x)**");
  });

  it("never writes frontmatter, which would corrupt the middle of a note", () => {
    const block = capturedAppendBlock({ Title: "T", author: "A" }, "https://x", "B");
    expect(block).not.toContain("---");
  });
})
