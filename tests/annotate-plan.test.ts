import { describe, it, expect } from "vitest";
import { annotationCellText, annotationNoteBlock, coerceAnnotation } from "../shared/annotations";
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

describe("annotate · bullet cell text", () => {
  it("prefixes a bullet only when asked", () => {
    const a = ann({ note: "" });
    expect(annotationCellText(a)).toBe("==quoted words==");
    expect(annotationCellText(a, true)).toBe("- ==quoted words==");
  });

  it("keeps the note and the bullet together", () => {
    const a = ann({ note: "my thought" });
    expect(annotationCellText(a, true)).toBe("- ==quoted words== — my thought");
  });

  it("removal finds the line whether it was written plain or bulleted", () => {
    const a = ann({ note: "" });
    expect(cellWithoutAnnotation("==quoted words==", a)).toBe("");
    expect(cellWithoutAnnotation("- ==quoted words==", a)).toBe("");
    expect(cellWithoutAnnotation("keep me<br>- ==quoted words==", a)).toBe("keep me");
  });
});

describe("annotation model · transparency (intensity)", () => {
  it("defaults absent or bad intensity to medium, keeps light/strong", () => {
    expect(coerceAnnotation({ id: "a", url: "u", anchor: { exact: "x" } })?.intensity).toBe("medium");
    expect(coerceAnnotation({ id: "a", url: "u", anchor: { exact: "x" }, intensity: "nope" })?.intensity).toBe("medium");
    expect(coerceAnnotation({ id: "a", url: "u", anchor: { exact: "x" }, intensity: "light" })?.intensity).toBe("light");
    expect(coerceAnnotation({ id: "a", url: "u", anchor: { exact: "x" }, intensity: "strong" })?.intensity).toBe("strong");
  });
});

describe("annotation model · tags", () => {
  it("cleans tags: trims, drops blanks, strips a leading #, de-duplicates case-insensitively", () => {
    const a = coerceAnnotation({
      id: "a", url: "u", anchor: { exact: "x" },
      tags: ["  research ", "#idea", "", "Research", 42, "idea"],
    });
    expect(a?.tags).toEqual(["research", "idea"]);
  });

  it("omits tags entirely when there are none valid", () => {
    const a = coerceAnnotation({ id: "a", url: "u", anchor: { exact: "x" }, tags: ["", 1, "  "] });
    expect(a?.tags).toBeUndefined();
  });

  it("writes tags as hashtags into the dedicated note block", () => {
    const block = annotationNoteBlock({
      id: "a", url: "u", anchor: { exact: "quote" }, color: "yellow", style: "highlight",
      createdAt: "2026-01-01T00:00:00Z", tags: ["deep work", "focus"],
    });
    expect(block).toContain("#deep-work #focus");
  });
});

describe("write-back · configurable note and tag destinations", () => {
  const withTags: StoredAnnotation = {
    id: "t1", url: "u", anchor: { exact: "quoted words" }, color: "yellow", style: "highlight",
    createdAt: "2026-01-01T00:00:00Z", note: "my thought", tags: ["deep work", "focus"],
  };

  it("cell: note on, tags on — hashtags follow the note, spaces hyphenated", () => {
    expect(annotationCellText(withTags, { note: true, tags: true })).toBe(
      "==quoted words== — my thought #deep-work #focus",
    );
  });

  it("cell: note off, tags on — just quote and hashtags", () => {
    expect(annotationCellText(withTags, { note: false, tags: true })).toBe("==quoted words== #deep-work #focus");
  });

  it("cell: tags off (default) matches the old plain form", () => {
    expect(annotationCellText(withTags)).toBe("==quoted words== — my thought");
    expect(annotationCellText(withTags, true)).toBe("- ==quoted words== — my thought"); // boolean = bullet, still works
  });

  it("note block: tags can be omitted while the note stays", () => {
    const block = annotationNoteBlock(withTags, { tags: false });
    expect(block).toContain("my thought");
    expect(block).not.toContain("#deep-work");
  });

  it("removal finds the cell line whichever destinations were on when it was written", () => {
    // Written with tags in the cell; settings later changed. Removal must still strip it.
    const line = annotationCellText(withTags, { note: true, tags: true });
    const cell = `first row<br>${line}<br>third row`;
    expect(cellWithoutAnnotation(cell, withTags)).toBe("first row<br>third row");
  });

  it("removal strips a with-tags note block without carving its no-tags prefix out of the middle", () => {
    const block = annotationNoteBlock(withTags, { note: true, tags: true });
    const content = `## Annotations\n\n${block}\n\n> a note someone kept\n`;
    const cleaned = noteWithoutAnnotation(content, withTags);
    expect(cleaned).not.toBeNull();
    expect(cleaned).not.toContain("#deep-work");
    expect(cleaned).toContain("a note someone kept");
  });
});
