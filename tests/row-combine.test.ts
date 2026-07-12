import { describe, it, expect } from "vitest";
import { combineRows, canEnrich, sourceLabel, isNoteLevel, applySourceBindings, discoverHeaderSources } from "../src/domain/extract/combine";
import { getField } from "../src/domain/fields";
import type { Row } from "../src/domain/model";

const file = {
  fileName: "Paper",
  filePath: "Lib/Paper.md",
  folderPath: "Lib",
  createdMs: 0,
  modifiedMs: 0,
  sizeBytes: 0,
};

function row(extractor: string, cells: Record<string, string>, rowIndex = 0): Row {
  return {
    cells,
    file,
    provenance: { filePath: file.filePath, extractor, locator: { rowIndex }, fingerprint: `${extractor}${rowIndex}` },
  };
}

describe("combineRows — separate (default)", () => {
  it("keeps every source's rows independent; a shared field never conflicts", () => {
    const rows = [
      row("table", { Title: "Chapter 1", Author: "Vaswani" }, 0),
      row("table", { Title: "Chapter 2", Author: "Shazeer" }, 1),
      row("frontmatter", { Author: "Editor" }),
    ];
    const out = combineRows(rows, "separate");
    expect(out).toHaveLength(3); // 2 table rows + 1 note row — nothing merged, nothing lost
    expect(out.map((r) => getField(r, "Author"))).toEqual(["Vaswani", "Shazeer", "Editor"]);
  });
});

describe("combineRows — enrich", () => {
  it("folds note-level values into each item row from the same note", () => {
    const rows = [
      row("table", { Title: "Chapter 1" }, 0),
      row("table", { Title: "Chapter 2" }, 1),
      row("frontmatter", { Author: "Vaswani", Year: "2017" }),
    ];
    const out = combineRows(rows, "enrich");
    expect(out).toHaveLength(2); // the note row is now context, not a row of its own
    for (const r of out) {
      expect(getField(r, "Author")).toBe("Vaswani");
      expect(getField(r, "Year")).toBe("2017");
    }
    expect(out.map((r) => getField(r, "Title"))).toEqual(["Chapter 1", "Chapter 2"]);
  });

  it("the item row wins when both define the same field — the more specific statement", () => {
    const rows = [
      row("table", { Title: "Chapter 1", Author: "Vaswani" }),
      row("frontmatter", { Author: "Editor" }),
    ];
    const out = combineRows(rows, "enrich");
    expect(getField(out[0]!, "Author")).toBe("Vaswani"); // NOT overwritten by the note property
  });

  it("collision detection is case-insensitive (author vs Author)", () => {
    const rows = [row("table", { author: "Vaswani" }), row("frontmatter", { Author: "Editor" })];
    const out = combineRows(rows, "enrich");
    expect(getField(out[0]!, "author")).toBe("Vaswani");
    // and no duplicate key is smuggled in under a different casing
    expect(Object.keys(out[0]!.cells).filter((k) => k.toLowerCase() === "author")).toHaveLength(1);
  });

  it("a note with no item rows still yields its note-level row (enriching nothing loses nothing)", () => {
    const rows = [row("frontmatter", { Author: "Solo" })];
    expect(combineRows(rows, "enrich")).toHaveLength(1);
  });

  it("with no note-level source, item rows pass through untouched", () => {
    const rows = [row("table", { Title: "A" }), row("task", { Title: "B" })];
    expect(combineRows(rows, "enrich")).toHaveLength(2);
  });

  it("several note-level sources merge, the earlier one winning a clash", () => {
    const rows = [
      row("table", { Title: "A" }),
      row("frontmatter", { Author: "FromProps" }),
      row("inline", { Author: "FromInline", Extra: "x" }),
    ];
    const out = combineRows(rows, "enrich");
    expect(getField(out[0]!, "Author")).toBe("FromProps");
    expect(getField(out[0]!, "Extra")).toBe("x");
  });
});

describe("source virtual field", () => {
  it("exposes which source a row came from", () => {
    expect(getField(row("table", {}), "source")).toBe("Table row");
    expect(getField(row("frontmatter", {}), "source")).toBe("Note properties");
    expect(getField(row("task", {}), "source")).toBe("Task");
  });
});

describe("helpers", () => {
  it("canEnrich only when a note-level and an item-level source are mixed", () => {
    expect(canEnrich(["table", "frontmatter"])).toBe(true);
    expect(canEnrich(["table", "task"])).toBe(false);
    expect(canEnrich(["frontmatter", "inline"])).toBe(false);
    expect(canEnrich(["table"])).toBe(false);
  });
  it("classifies note-level sources", () => {
    expect(isNoteLevel("frontmatter")).toBe(true);
    expect(isNoteLevel("inline")).toBe(true);
    expect(isNoteLevel("table")).toBe(false);
    expect(sourceLabel("xlsx")).toBe("Excel row");
  });
});

describe("the `source` virtual field never shadows real data", () => {
  it("a real column named 'source' wins over the virtual value", () => {
    const r = row("table", { Source: "[[Original Paper]]", Title: "A" });
    expect(getField(r, "source")).toBe("[[Original Paper]]");
  });
  it("and falls back to the extractor label only when there is no such column", () => {
    expect(getField(row("table", { Title: "A" }), "source")).toBe("Table row");
  });
});

describe("applySourceBindings — a column bound to one source", () => {
  const cols = (source?: string) => [{ name: "Author", type: "text", ...(source ? { source } : {}) }];

  it("unbound columns are untouched", () => {
    const rows = [row("table", { Author: "Vaswani" }), row("frontmatter", { Author: "Editor" })];
    const out = applySourceBindings(rows, cols());
    expect(out.map((r) => getField(r, "Author"))).toEqual(["Vaswani", "Editor"]);
  });

  it("in separate mode, the column only takes values from the bound source", () => {
    const rows = [row("table", { Author: "Vaswani" }), row("frontmatter", { Author: "Editor" })];
    const out = applySourceBindings(rows, cols("frontmatter"));
    expect(getField(out[0]!, "Author")).toBe(""); // the table row didn't come from properties
    expect(getField(out[1]!, "Author")).toBe("Editor");
  });

  it("in enrich mode, binding picks the source's value even where the item row won the clash", () => {
    const merged = combineRows(
      [row("table", { Title: "Ch 1", Author: "Vaswani" }), row("frontmatter", { Author: "Editor" })],
      "enrich",
    );
    // default (unbound): the item row wins
    expect(getField(merged[0]!, "Author")).toBe("Vaswani");
    // bound to properties: the note's value is recovered, not lost
    const bound = applySourceBindings(merged, cols("frontmatter"));
    expect(getField(bound[0]!, "Author")).toBe("Editor");
    // bound to the table: the table's own value
    const boundTable = applySourceBindings(merged, cols("table"));
    expect(getField(boundTable[0]!, "Author")).toBe("Vaswani");
  });

  it("matching is case-insensitive on the header", () => {
    const rows = [row("frontmatter", { author: "Editor" })];
    expect(getField(applySourceBindings(rows, cols("frontmatter"))[0]!, "Author")).toBe("Editor");
  });

  it("a value taken from another source is marked read-only, so an edit can't write it to the wrong file", () => {
    const merged = combineRows([row("table", { Title: "Ch 1" }), row("frontmatter", { Author: "Editor" })], "enrich");
    const bound = applySourceBindings(merged, cols("frontmatter"));
    expect(bound[0]!.provenance.readOnlyFields).toContain("Author"); // row writes back to the table
    // whereas binding to the row's own source stays editable
    const own = applySourceBindings(merged, cols("table"));
    expect(own[0]!.provenance.readOnlyFields ?? []).not.toContain("Author");
  });
});

describe("discoverHeaderSources", () => {
  it("records which source supplied each header", () => {
    const rows = [
      row("table", { Title: "A", Status: "Done" }),
      row("frontmatter", { Author: "Vaswani", Year: "2017" }),
    ];
    const found = discoverHeaderSources(rows);
    expect(found.get("title")).toEqual(["table"]);
    expect(found.get("author")).toEqual(["frontmatter"]);
  });

  it("flags a header that several sources define", () => {
    const rows = [row("table", { Author: "Vaswani" }), row("frontmatter", { Author: "Editor" })];
    expect(discoverHeaderSources(rows).get("author")?.sort()).toEqual(["frontmatter", "table"]);
  });

  it("is case-insensitive, so Author and author are one field", () => {
    const rows = [row("table", { author: "a" }), row("frontmatter", { Author: "b" })];
    expect(discoverHeaderSources(rows).get("author")).toHaveLength(2);
  });

  it("sees through a folded row to each source's own cells", () => {
    const merged = combineRows(
      [row("table", { Title: "A", Author: "Vaswani" }), row("frontmatter", { Author: "Editor", Year: "2017" })],
      "enrich",
    );
    const found = discoverHeaderSources(merged);
    expect(found.get("author")?.sort()).toEqual(["frontmatter", "table"]); // both, despite the merge
    expect(found.get("year")).toEqual(["frontmatter"]);
    expect(found.get("title")).toEqual(["table"]);
  });
});
