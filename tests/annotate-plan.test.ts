import { describe, it, expect } from "vitest";
import {
  noteWithoutAnnotation,
  annotationColumn,
  rowForUrl,
  readWireAnnotation,
  cellWithoutAnnotation,
} from "../src/services/bridge/annotate-plan";
import type { StoredAnnotation } from "../shared/annotations";
import type { Row } from "../src/domain/index";

const row = (cells: Record<string, string>): Row =>
  ({ cells, provenance: { filePath: "L.md" } }) as unknown as Row;

const ann = (patch: Partial<StoredAnnotation> = {}): StoredAnnotation => ({
  id: "abcdefghij",
  url: "https://x/a",
  anchor: { exact: "quoted words" },
  color: "yellow",
  style: "highlight",
  createdAt: "2026-07-20T00:00:00.000Z",
  ...patch,
});

describe("annotate · which column takes the text", () => {
  it("prefers the most specific name the view offers", () => {
    expect(annotationColumn([{ name: "Notes" }, { name: "Annotations" }])).toBe("Annotations");
    expect(annotationColumn([{ name: "Highlights" }])).toBe("Highlights");
  });

  it("returns null when the view has nowhere for it, rather than guessing a column", () => {
    expect(annotationColumn([{ name: "Title" }, { name: "URL" }])).toBeNull();
  });
});

describe("annotate · which row is the page's", () => {
  const columns = [{ name: "Title", typeId: "text" }, { name: "URL", typeId: "url" }];

  it("finds the row whose URL cell names the page", () => {
    const rows = [row({ Title: "A", URL: "https://x/a" }), row({ Title: "B", URL: "https://x/b" })];
    expect(rowForUrl(rows, columns, "https://x/b")?.cells["Title"]).toBe("B");
  });

  it("matches loosely, the way the rest of the bridge does", () => {
    const rows = [row({ URL: "https://www.x/a/?utm_source=q" })];
    expect(rowForUrl(rows, columns, "https://x/a")).not.toBeNull();
  });

  it("accepts a Source column by name even untyped", () => {
    const rows = [row({ Source: "https://x/a" })];
    expect(rowForUrl(rows, [{ name: "Source" }], "https://x/a")).not.toBeNull();
  });

  it("returns null for a page no row names", () => {
    expect(rowForUrl([row({ URL: "https://x/a" })], columns, "https://elsewhere/p")).toBeNull();
  });
});

describe("annotate · reading the wire", () => {
  it("accepts a well-formed wire annotation and stamps the url", () => {
    const read = readWireAnnotation(
      { id: "abcdefghij", anchor: { exact: "q" }, color: "green", createdAt: "2026-01-01T00:00:00Z" },
      "https://x/a",
    );
    expect(read?.url).toBe("https://x/a");
    expect(read?.color).toBe("green");
  });

  it("refuses what couldn't paint", () => {
    expect(readWireAnnotation({ id: "x", anchor: { exact: "" } }, "https://x/a")).toBeNull();
    expect(readWireAnnotation(null, "https://x/a")).toBeNull();
  });
});

describe("annotate · removing a line from a cell", () => {
  it("strips exactly the annotation's line and keeps the rest", () => {
    const cell = "==first== — a note<br>==quoted words==<br>==third==";
    expect(cellWithoutAnnotation(cell, ann())).toBe("==first== — a note<br>==third==");
  });

  it("matches the line whole, so a similar line a person wrote survives", () => {
    const cell = "==quoted words== but reworded by hand";
    expect(cellWithoutAnnotation(cell, ann())).toBeNull();
  });

  it("returns null when the line isn't there, so the cell isn't rewritten for nothing", () => {
    expect(cellWithoutAnnotation("==something else==", ann())).toBeNull();
  });

  it("leaves an empty cell when the only line is removed", () => {
    expect(cellWithoutAnnotation("==quoted words==", ann())).toBe("");
  });

  it("removes the note-carrying form too", () => {
    const withNote = ann({ note: "my thought" });
    expect(cellWithoutAnnotation("==quoted words== — my thought<br>==keep==", withNote)).toBe("==keep==");
  });
});

describe("annotate · removing a blockquote from the note", () => {
  const withNote = ann({ note: "my thought" });

  it("removes exactly the block it wrote and closes the gap", () => {
    const content = "# T\n\n## Annotations\n\n> quoted words\n>\n> — my thought\n\n> another one\n";
    const cleaned = noteWithoutAnnotation(content, withNote);
    expect(cleaned).toBe("# T\n\n## Annotations\n\n> another one\n");
  });

  it("leaves an edited blockquote alone — their writing outranks our bookkeeping", () => {
    const content = "## Annotations\n\n> quoted words, but rephrased by hand\n";
    expect(noteWithoutAnnotation(content, ann())).toBeNull();
  });

  it("returns null when the block isn't there, so the note isn't rewritten for nothing", () => {
    expect(noteWithoutAnnotation("## Annotations\n\n> something else\n", ann())).toBeNull();
  });
})

describe("annotate · declared columns override the guess", () => {
  it("uses the named URL column even when it isn't url/link/source", async () => {
    const columns = [{ name: "Title", typeId: "text" }, { name: "Web Address", typeId: "text" }];
    const rows = [row({ Title: "T", "Web Address": "https://x/a" })];
    expect(rowForUrl(rows, columns, "https://x/a", "Web Address")?.cells["Title"]).toBe("T");
  });

  it("falls back to the guess when the declared URL column no longer exists", () => {
    const columns = [{ name: "Title", typeId: "text" }, { name: "URL", typeId: "url" }];
    const rows = [row({ Title: "T", URL: "https://x/a" })];
    // "Renamed Column" is gone; the heuristic still finds the url-typed one rather than matching nothing.
    expect(rowForUrl(rows, columns, "https://x/a", "Renamed Column")).not.toBeNull();
  });

  it("uses the named annotations column even when it isn't in the default vocabulary", () => {
    expect(annotationColumn([{ name: "My Marks" }], "My Marks")).toBe("My Marks");
  });

  it("falls back to the vocabulary when the declared annotations column is missing", () => {
    expect(annotationColumn([{ name: "Notes" }], "Gone")).toBe("Notes");
  });
})
