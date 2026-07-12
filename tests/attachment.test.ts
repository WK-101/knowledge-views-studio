import { describe, it, expect } from "vitest";
import { parseAttachments, serializeAttachments, attachmentKind, attachmentName } from "../src/services/attachments/attachment";

describe("attachment model", () => {
  it("classifies by extension, URLs, and Zotero links", () => {
    expect(attachmentKind("papers/x.pdf", false)).toBe("pdf");
    expect(attachmentKind("book.EPUB", false)).toBe("epub");
    expect(attachmentKind("fig.png", false)).toBe("image");
    expect(attachmentKind("notes.docx", false)).toBe("word");
    expect(attachmentKind("data.xlsx", false)).toBe("excel");
    expect(attachmentKind("deck.pptx", false)).toBe("powerpoint");
    expect(attachmentKind("https://example.com/page", true)).toBe("web");
    expect(attachmentKind("https://example.com/paper.pdf?x=1", true)).toBe("pdf"); // extension wins
    expect(attachmentKind("something", false)).toBe("file");
  });

  it("parses vault links and URLs with optional labels", () => {
    const src = [
      "[[papers/vaswani2017.pdf]]",
      "[[figures/arch.png]] | Model architecture",
      "https://arxiv.org/abs/1706.03762 | arXiv",
      "zotero://open-pdf/library/items/ABCD",
      "",
      "  some stray note line  ",
    ].join("\n");
    const atts = parseAttachments(src);
    expect(atts).toHaveLength(4);
    expect(atts[0]).toMatchObject({ target: "papers/vaswani2017.pdf", isLink: true, kind: "pdf" });
    expect(atts[1]).toMatchObject({ target: "figures/arch.png", isLink: true, kind: "image", label: "Model architecture" });
    expect(atts[2]).toMatchObject({ target: "https://arxiv.org/abs/1706.03762", isLink: false, kind: "web", label: "arXiv" });
    expect(atts[3]!.isLink).toBe(false); // zotero:// link
  });

  it("round-trips through serialise", () => {
    const src = "[[a/b.pdf]] | Paper\nhttps://x.com | Site";
    expect(serializeAttachments(parseAttachments(src))).toBe(src);
  });

  it("takes the path from a [[path|alias]] link", () => {
    expect(parseAttachments("[[papers/x.pdf|Nice name]]")[0]!.target).toBe("papers/x.pdf");
  });

  it("derives a readable name", () => {
    expect(attachmentName({ target: "papers/vaswani2017.pdf", isLink: true, kind: "pdf" })).toBe("vaswani2017");
    expect(attachmentName({ target: "https://www.nature.com/articles/x", isLink: false, kind: "web" })).toBe("nature.com");
  });
});

import { extractKvsPaperBlocks, allPaperAttachments } from "../src/services/attachments/attachment";

describe("kvs-paper block extraction", () => {
  const note = [
    "# Paper",
    "",
    "## Attachments",
    "",
    "```kvs-paper",
    "[[papers/x.pdf]]",
    "https://arxiv.org/abs/1 | arXiv",
    "```",
    "",
    "## Notes",
    "text with ``` stray fence mention",
  ].join("\n");

  it("extracts the block content and its attachments", () => {
    const blocks = extractKvsPaperBlocks(note);
    expect(blocks).toHaveLength(1);
    const atts = allPaperAttachments(note);
    expect(atts).toHaveLength(2);
    expect(atts[0]).toMatchObject({ target: "papers/x.pdf", kind: "pdf", isLink: true });
    expect(atts.filter((a) => a.kind === "pdf")).toHaveLength(1);
  });
});
