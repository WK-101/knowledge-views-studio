import { describe, it, expect } from "vitest";
import { frontmatterExtractor, parseFrontmatter } from "../src/domain/extract/frontmatter-extractor";
import { applyFrontmatterEdits } from "../src/services/write/frontmatter-writer";
import type { Row } from "../src/domain/index";

const file = { filePath: "Book.md", fileName: "Book", folderPath: "", createdMs: 0, modifiedMs: 1, sizeBytes: 0 };
const extract = (content: string): Row[] => frontmatterExtractor.extract({ file, content });

const doc = ['---', 'title: Dune', 'status: reading', 'rating: 5', 'tags: [sci-fi, classic]', 'author:', '  - "[[Frank Herbert]]"', '---', '', 'Body text.'].join("\n");

describe("frontmatter extractor", () => {
  it("reads scalars, inline lists, block lists and links into one row", () => {
    const rows = extract(doc);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.cells).toMatchObject({
      title: "Dune",
      status: "reading",
      rating: "5",
      tags: "sci-fi, classic",
      author: "[[Frank Herbert]]",
    });
  });
  it("yields no row when there is no frontmatter", () => {
    expect(extract("# Just a heading\n")).toHaveLength(0);
    expect(parseFrontmatter([])).toEqual({});
  });
});

describe("frontmatter write-back", () => {
  const prov = () => extract(doc)[0]!.provenance;

  it("replaces a scalar value in place", () => {
    const r = applyFrontmatterEdits(doc, [{ provenance: prov(), column: "status", value: "finished" }]);
    expect(r.applied).toBe(1);
    expect(r.content).toContain("status: finished");
    expect(r.content).toContain("title: Dune");
  });

  it("inserts a missing key before the closing fence", () => {
    const r = applyFrontmatterEdits(doc, [{ provenance: prov(), column: "shelf", value: "favorites" }]);
    expect(r.content).toContain("shelf: favorites");
    expect(extract(r.content)[0]!.cells.shelf).toBe("favorites");
  });

  it("quotes values that YAML would misread", () => {
    const r = applyFrontmatterEdits(doc, [{ provenance: prov(), column: "note", value: "[[Other]]" }]);
    expect(r.content).toContain('note: "[[Other]]"');
    expect(extract(r.content)[0]!.cells.note).toBe("[[Other]]");
  });

  it("converts a list-valued key without orphaning its items", () => {
    const r = applyFrontmatterEdits(doc, [{ provenance: prov(), column: "author", value: "Herbert" }]);
    expect(r.content).toContain("author: Herbert");
    expect(r.content).not.toContain("- \"[[Frank Herbert]]\"");
  });

  it("creates a frontmatter block when the note has none", () => {
    const r = applyFrontmatterEdits("Just a body.\n", [
      { provenance: { filePath: "x.md", extractor: "frontmatter", locator: {}, fingerprint: "" }, column: "status", value: "new" },
    ]);
    expect(r.content.startsWith("---\nstatus: new\n---")).toBe(true);
    expect(r.content).toContain("Just a body.");
  });
});
