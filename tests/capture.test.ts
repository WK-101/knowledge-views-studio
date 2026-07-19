import { describe, it, expect } from "vitest";
import { normalizeText, normalizeDate, normalizeNumber, normalizeForType } from "../src/services/capture/normalize";
import { mapToColumns, applyDefaults } from "../src/services/capture/map";
import { appendCapturedRow, appendCapturedRows } from "../src/services/capture/capture-table";
import { findDuplicate, buildCapturedNote, safeFileName } from "../src/services/capture/capture-service";
import type { Row } from "../src/domain/index";
import { parseCaptureText, effectiveTarget } from "../src/services/capture/parse";
import type { TargetSource } from "../src/services/capture/parse";
import type { CaptureColumn } from "../src/services/capture/types";
import type { CapturePayload } from "../src/services/capture/types";

const col = (name: string, typeId = "text", extra: Partial<CaptureColumn> = {}): CaptureColumn => ({
  name,
  typeId,
  ...extra,
});

const payload = (fields: Record<string, string>, url?: string): CapturePayload => ({
  fields: Object.entries(fields).map(([key, value]) => ({ key, value })),
  ...(url !== undefined ? { url } : {}),
});

describe("capture · normalizeText", () => {
  it("composes Unicode so the same character from different sources compares equal", () => {
    const decomposed = "Cafe\u0301"; // e + combining acute
    expect(normalizeText(decomposed)).toBe("Café");
    expect(normalizeText(decomposed)).toBe(normalizeText("Café"));
  });

  it("collapses non-breaking and ideographic spaces", () => {
    expect(normalizeText("a\u00A0b\u3000c")).toBe("a b c");
    expect(normalizeText("  spaced   out  ")).toBe("spaced out");
  });

  it("drops zero-width spaces but keeps joiners that carry meaning", () => {
    expect(normalizeText("we\u200Bird")).toBe("weird"); // ZWSP removed
    // ZWJ must survive: stripping it would break emoji sequences and several scripts.
    expect(normalizeText("\u0928\u200D\u092E")).toContain("\u200D");
  });
});

describe("capture · normalizeDate", () => {
  it("accepts ISO, with or without a time part", () => {
    expect(normalizeDate("2026-07-18")).toBe("2026-07-18");
    expect(normalizeDate("2026-07-18T10:23:42Z")).toBe("2026-07-18");
    expect(normalizeDate("2026-7-8")).toBe("2026-07-08");
  });

  it("reads CJK dates", () => {
    expect(normalizeDate("2026年7月18日")).toBe("2026-07-18");
  });

  it("reads English month names in either order", () => {
    expect(normalizeDate("18 July 2026")).toBe("2026-07-18");
    expect(normalizeDate("July 18, 2026")).toBe("2026-07-18");
    expect(normalizeDate("18 Jul 2026")).toBe("2026-07-18");
  });

  it("resolves numeric dates when the day gives the order away", () => {
    expect(normalizeDate("18/07/2026")).toBe("2026-07-18"); // 18 can only be a day
    expect(normalizeDate("07/18/2026")).toBe("2026-07-18"); // 18 can only be a day
    expect(normalizeDate("18.07.2026")).toBe("2026-07-18");
  });

  it("REFUSES to guess a genuinely ambiguous date", () => {
    // 03/07 is 3 July in most of the world and 7 March in the US. Picking one would write a plausible
    // wrong date that nobody would ever notice — so it's left for a person to resolve.
    expect(normalizeDate("03/07/2026")).toBe("03/07/2026");
    expect(normalizeDate("01/02/2026")).toBe("01/02/2026");
  });

  it("leaves unparseable text alone rather than inventing a date", () => {
    expect(normalizeDate("sometime last spring")).toBe("sometime last spring");
    expect(normalizeDate("")).toBe("");
  });
});

describe("capture · normalizeNumber", () => {
  it("uses the last separator as the decimal point, whatever the convention", () => {
    expect(normalizeNumber("1,234.56")).toBe("1234.56"); // Anglo
    expect(normalizeNumber("1.234,56")).toBe("1234.56"); // European
  });

  it("handles space-grouped numbers", () => {
    expect(normalizeNumber("1 234,56")).toBe("1234.56");
    expect(normalizeNumber("1\u00A0234,56")).toBe("1234.56"); // non-breaking space
  });

  it("distinguishes a thousands group from a fraction by digit count", () => {
    expect(normalizeNumber("1,234")).toBe("1234"); // three digits: a group
    expect(normalizeNumber("1,23")).toBe("1.23"); // two digits: a fraction
  });

  it("strips currency and stray symbols", () => {
    expect(normalizeNumber("$1,299.00")).toBe("1299.00");
    expect(normalizeNumber("€ 45,50")).toBe("45.50");
  });

  it("returns the original when the result wouldn't be a number", () => {
    expect(normalizeNumber("not a number")).toBe("not a number");
  });
});

describe("capture · normalizeForType", () => {
  it("reads the many ways a checkbox arrives", () => {
    expect(normalizeForType("Yes", "checkbox")).toBe("true");
    expect(normalizeForType("0", "checkbox")).toBe("false");
  });

  it("splits tags on comma, semicolon, or the CJK enumeration comma", () => {
    expect(normalizeForType("a, b; c、d", "tags")).toBe("a, b, c, d");
    expect(normalizeForType("one,, ,two", "tags")).toBe("one, two");
  });

  it("passes unknown types through as text", () => {
    expect(normalizeForType("  hi  ", "something-else")).toBe("hi");
  });
});

describe("capture · mapToColumns", () => {
  const columns = [
    col("Title", "text", { role: "title" }),
    col("Author", "text"),
    col("Published", "date", { role: "date" }),
    col("Link", "url"),
    col("Tags", "tags", { role: "tags" }),
  ];

  it("matches a column by its exact name first", () => {
    const { values } = mapToColumns(payload({ Title: "Attention Is All You Need" }), columns);
    expect(values["Title"]).toBe("Attention Is All You Need");
  });

  it("matches through aliases the source happens to use", () => {
    const { values } = mapToColumns(
      payload({ "og:title": "A Paper", "schema:author": "Ada Lovelace", "og:article:published_time": "2026-07-18T09:00:00Z" }),
      columns,
    );
    expect(values["Title"]).toBe("A Paper");
    expect(values["Author"]).toBe("Ada Lovelace");
    expect(values["Published"]).toBe("2026-07-18"); // normalized on the way in
  });

  it("puts the source url in a url column when nothing else claimed it", () => {
    const { values } = mapToColumns(payload({ title: "X" }, "https://example.com/a"), columns);
    expect(values["Link"]).toBe("https://example.com/a");
  });

  it("hands back what it couldn't place instead of dropping it", () => {
    const { unmapped } = mapToColumns(payload({ title: "X", "some:oddity": "keep me" }), columns);
    expect(unmapped.map((f) => f.key)).toContain("some:oddity");
  });

  it("never overwrites a column an earlier, stronger match already filled", () => {
    const { values } = mapToColumns(payload({ Title: "Exact wins", "og:title": "Alias loses" }), columns);
    expect(values["Title"]).toBe("Exact wins");
  });

  it("snaps a choice value onto the vocabulary the column already uses", () => {
    const withStatus = [col("Status", "select", { options: [{ value: "In progress" }, { value: "Done" }] })];
    const { values } = mapToColumns(payload({ Status: "in PROGRESS" }), withStatus);
    expect(values["Status"]).toBe("In progress"); // not a second spelling of the same status
  });

  it("ignores empty values so blanks don't crowd out later matches", () => {
    const { values } = mapToColumns(payload({ Title: "   ", "og:title": "Real title" }), columns);
    expect(values["Title"]).toBe("Real title");
  });

  it("fills defaults only where nothing was captured", () => {
    const withDefault = [col("Status", "text", { defaultValue: "Unread" }), col("Title", "text")];
    const filled = applyDefaults({ Title: "X" }, withDefault);
    expect(filled["Status"]).toBe("Unread");
    expect(filled["Title"]).toBe("X");
  });
});

describe("capture · appendCapturedRow", () => {
  const table = ["| Title | Author |", "| --- | --- |", "| First | Ada |"].join("\n");

  it("appends to the note's first table by default", () => {
    const res = appendCapturedRow(table, { Title: "Second", Author: "Grace" });
    expect(res.ok).toBe(true);
    expect(res.createdTable).toBe(false);
    expect(res.content).toContain("| Second | Grace |");
    expect(res.content.indexOf("First")).toBeLessThan(res.content.indexOf("Second"));
  });

  it("fills only the headers the table actually has, leaving the rest blank", () => {
    const res = appendCapturedRow(table, { Title: "T", Author: "A", Unknown: "ignored" });
    expect(res.content).toContain("| T | A |");
    expect(res.content).not.toContain("ignored");
  });

  it("finds the table belonging to a named heading", () => {
    const doc = [
      "## Books",
      "| Title |",
      "| --- |",
      "| Book one |",
      "",
      "## Papers",
      "| Title |",
      "| --- |",
      "| Paper one |",
    ].join("\n");
    const res = appendCapturedRow(doc, { Title: "Paper two" }, { heading: "Papers" });
    expect(res.ok).toBe(true);
    const lines = res.content.split("\n");
    expect(lines.indexOf("| Paper two |")).toBeGreaterThan(lines.indexOf("## Papers"));
  });

  it("does not reach into a later section's table", () => {
    const doc = ["## Empty section", "", "## Later", "| Title |", "| --- |", "| x |"].join("\n");
    const res = appendCapturedRow(doc, { Title: "new" }, { heading: "Empty section" });
    expect(res.ok).toBe(false); // the table below belongs to "Later", not here
  });

  it("creates the table when asked and none exists — the case add-row can't handle today", () => {
    const res = appendCapturedRow("# Notes\n", { Title: "First ever", Author: "Ada" }, {
      createIfMissing: true,
      columns: ["Title", "Author"],
    });
    expect(res.ok).toBe(true);
    expect(res.createdTable).toBe(true);
    expect(res.content).toContain("| Title | Author |");
    expect(res.content).toContain("| First ever | Ada |");
  });

  it("creates the heading too when it isn't there yet", () => {
    const res = appendCapturedRow("# Notes\n", { Title: "x" }, {
      heading: "Captured",
      createIfMissing: true,
      columns: ["Title"],
    });
    expect(res.ok).toBe(true);
    expect(res.content).toContain("## Captured");
    expect(res.content).toContain("| x |");
  });

  it("refuses rather than guessing when there's no table and creating wasn't allowed", () => {
    const res = appendCapturedRow("# Notes\n", { Title: "x" });
    expect(res.ok).toBe(false);
    expect(res.reason).toBeTruthy();
    expect(res.content).toBe("# Notes\n"); // untouched
  });

  it("won't create a table with no columns to name", () => {
    const res = appendCapturedRow("# Notes\n", { Title: "x" }, { createIfMissing: true, columns: [] });
    expect(res.ok).toBe(false);
  });

  it("escapes pipes so a value can't break the table", () => {
    const res = appendCapturedRow(table, { Title: "a | b", Author: "x" });
    expect(res.ok).toBe(true);
    expect(res.content).toContain("a \\| b");
  });

  it("preserves CRLF line endings", () => {
    const crlf = ["| Title |", "| --- |", "| One |"].join("\r\n");
    const res = appendCapturedRow(crlf, { Title: "Two" });
    expect(res.content).toContain("\r\n");
    expect(res.content).not.toMatch(/[^\r]\n/);
  });
});

describe("capture · findDuplicate", () => {
  const columns = [col("Title", "text"), col("URL", "url"), col("DOI", "doi")];
  const row = (cells: Record<string, string>): Row =>
    ({ cells, file: {}, provenance: { filePath: "a.md" } }) as unknown as Row;

  it("matches on an identity field", () => {
    const rows = [row({ Title: "A", URL: "https://example.com/x" })];
    const hit = findDuplicate({ URL: "https://example.com/x" }, columns, rows);
    expect(hit?.on).toBe("URL");
  });

  it("ignores case and surrounding whitespace when comparing", () => {
    const rows = [row({ DOI: "10.1000/ABC" })];
    expect(findDuplicate({ DOI: "  10.1000/abc " }, columns, rows)).not.toBeNull();
  });

  it("does NOT treat a shared title as a duplicate", () => {
    // Two different papers can share a title; refusing a legitimate item is worse than a duplicate.
    const rows = [row({ Title: "Introduction" })];
    expect(findDuplicate({ Title: "Introduction" }, columns, rows)).toBeNull();
  });

  it("ignores empty values on both sides", () => {
    expect(findDuplicate({ URL: "" }, columns, [row({ URL: "" })])).toBeNull();
  });

  it("returns null when there's nothing to compare against", () => {
    expect(findDuplicate({ URL: "https://x" }, columns, [])).toBeNull();
  });
});

describe("capture · buildCapturedNote", () => {
  it("writes captured values as frontmatter a view can read back", () => {
    const note = buildCapturedNote({ Title: "A Paper", Author: "Ada" });
    expect(note.startsWith("---\n")).toBe(true);
    expect(note).toContain("Title: A Paper");
    expect(note).toContain("Author: Ada");
  });

  it("quotes values that would otherwise break YAML", () => {
    const note = buildCapturedNote({ Title: "Rethinking: a study", Note: "- not a list" });
    expect(note).toContain('Title: "Rethinking: a study"');
    expect(note).toContain('Note: "- not a list"');
  });

  it("skips empty values rather than writing blank keys", () => {
    expect(buildCapturedNote({ Title: "X", Empty: "   " })).not.toContain("Empty");
  });

  it("records the source url when no column already captured it", () => {
    expect(buildCapturedNote({ Title: "X" }, { url: "https://example.com" })).toContain("url: https://example.com");
  });

  it("flattens newlines so a multi-line value can't break the block", () => {
    expect(buildCapturedNote({ Note: "one\ntwo" })).toContain("Note: one two");
  });
});

describe("capture · safeFileName", () => {
  it("removes characters a file name can't hold", () => {
    expect(safeFileName('a/b:c*d?"e<f>g|h')).toBe("abcdefgh");
  });

  it("falls back when nothing usable is left", () => {
    expect(safeFileName("///")).toBe("Captured");
    expect(safeFileName("")).toBe("Captured");
  });

  it("keeps non-Latin titles intact", () => {
    expect(safeFileName("日本語のタイトル")).toBe("日本語のタイトル");
  });

  it("caps the length", () => {
    expect(safeFileName("x".repeat(200)).length).toBe(80);
  });
});

describe("capture · parseCaptureText", () => {
  it("treats a bare URL as the source, not a nameless field", () => {
    const p = parseCaptureText("https://example.com/article");
    expect(p.url).toBe("https://example.com/article");
    expect(p.fields).toHaveLength(0);
  });

  it("reads key: value lines", () => {
    const p = parseCaptureText("Title: A Paper\nAuthor: Ada");
    expect(p.fields).toEqual([
      { key: "Title", value: "A Paper" },
      { key: "Author", value: "Ada" },
    ]);
  });

  it("picks up a url given as a labelled line", () => {
    expect(parseCaptureText("Title: X\nURL: https://example.com").url).toBe("https://example.com");
  });

  it("treats loose text as a title and a description", () => {
    const p = parseCaptureText("An interesting headline\nsome supporting detail\nand more");
    expect(p.fields).toContainEqual({ key: "title", value: "An interesting headline" });
    expect(p.fields).toContainEqual({ key: "description", value: "some supporting detail and more" });
  });

  it("returns nothing for empty input", () => {
    expect(parseCaptureText("   ").fields).toHaveLength(0);
    expect(parseCaptureText("").url).toBeUndefined();
  });

  it("doesn't mistake a sentence containing a colon for a field", () => {
    // The key pattern is deliberately narrow; prose shouldn't become a column name.
    const p = parseCaptureText("This is a long sentence that happens to contain: a colon in the middle");
    expect(p.fields[0]?.key).toBe("title");
  });
});

describe("capture · effectiveTarget", () => {
  const base: TargetSource = { newRowFile: "" };

  it("uses the configured capture target when it has a note path", () => {
    const p = { ...base, captureTarget: { shape: "row" as const, notePath: "Inbox.md" } };
    expect(effectiveTarget(p)?.notePath).toBe("Inbox.md");
  });

  it("accepts a note-shaped target without a note path", () => {
    const p = { ...base, captureTarget: { shape: "note" as const, folder: "Inbox" } };
    expect(effectiveTarget(p)?.shape).toBe("note");
  });

  it("falls back to the older write-back setting, allowing table creation", () => {
    const p = { ...base, newRowFile: "Library.md" };
    const t = effectiveTarget(p);
    expect(t?.notePath).toBe("Library.md");
    expect(t?.createIfMissing).toBe(true);
  });

  it("ignores a row target with no note path and reports nothing configured", () => {
    const p = { ...base, captureTarget: { shape: "row" as const, notePath: "  " } };
    expect(effectiveTarget(p)).toBeNull();
    expect(effectiveTarget(base)).toBeNull();
  });
});

describe("capture · appendCapturedRows (many at once)", () => {
  const doc = ["| Title | Author |", "| --- | --- |", "| First | Ada |"].join("\n");

  it("writes several rows in one pass, in order", () => {
    const res = appendCapturedRows(doc, [
      { Title: "Second", Author: "Grace" },
      { Title: "Third", Author: "Katherine" },
    ]);
    expect(res.ok).toBe(true);
    const lines = res.content.split("\n");
    expect(lines.indexOf("| Second | Grace |")).toBeLessThan(lines.indexOf("| Third | Katherine |"));
  });

  it("appends after the existing rows rather than replacing them", () => {
    const res = appendCapturedRows(doc, [{ Title: "Second", Author: "Grace" }]);
    expect(res.content).toContain("| First | Ada |");
    expect(res.content).toContain("| Second | Grace |");
  });

  it("creates the table once and writes every row into it", () => {
    const res = appendCapturedRows(
      "# Notes\n",
      [{ Title: "A" }, { Title: "B" }, { Title: "C" }],
      { createIfMissing: true, columns: ["Title"] },
    );
    expect(res.ok).toBe(true);
    expect(res.createdTable).toBe(true);
    // One header, one separator, three rows — not three tables.
    expect(res.content.split("\n").filter((l) => l.startsWith("| Title |"))).toHaveLength(1);
    for (const value of ["A", "B", "C"]) expect(res.content).toContain(`| ${value} |`);
  });

  it("refuses an empty batch rather than writing nothing quietly", () => {
    const res = appendCapturedRows(doc, []);
    expect(res.ok).toBe(false);
    expect(res.content).toBe(doc);
  });

  it("escapes pipes in every row, not just the first", () => {
    const res = appendCapturedRows(doc, [{ Title: "a | b" }, { Title: "c | d" }]);
    expect(res.content).toContain("a \\| b");
    expect(res.content).toContain("c \\| d");
  });

  it("leaves the file untouched when there's no table and creating wasn't allowed", () => {
    const res = appendCapturedRows("# Notes\n", [{ Title: "x" }]);
    expect(res.ok).toBe(false);
    expect(res.content).toBe("# Notes\n");
  });

  it("fills only the headers the table has, across all rows", () => {
    const res = appendCapturedRows(doc, [
      { Title: "A", Author: "X", Extra: "dropped" },
      { Title: "B", Author: "Y" },
    ]);
    expect(res.content).not.toContain("dropped");
    expect(res.content).toContain("| A | X |");
    expect(res.content).toContain("| B | Y |");
  });
})
