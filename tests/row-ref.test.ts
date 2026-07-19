import { describe, it, expect } from "vitest";
import { rowRefOf, findRowByRef, editableChanges } from "../src/services/bridge/row-ref";
import { normalizeUrl } from "../shared/protocol";
import type { Row, RowProvenance } from "../src/domain/index";

const prov = (patch: Partial<RowProvenance> = {}): RowProvenance => ({
  filePath: "Library.md",
  extractor: "table",
  locator: { table: 0, row: 3 },
  fingerprint: "abc",
  ...patch,
});

const row = (patch: Partial<RowProvenance> = {}, cells: Record<string, string> = {}): Row =>
  ({ cells, provenance: prov(patch) }) as unknown as Row;

describe("bridge · row references", () => {
  it("gives the same row the same handle every time", () => {
    expect(rowRefOf(prov())).toBe(rowRefOf(prov()));
  });

  it("doesn't depend on the order keys happen to be in", () => {
    const a = rowRefOf(prov({ locator: { table: 0, row: 3 } }));
    const b = rowRefOf(prov({ locator: { row: 3, table: 0 } }));
    expect(a).toBe(b);
  });

  it("distinguishes rows in different files, positions, and extractors", () => {
    const base = rowRefOf(prov());
    expect(rowRefOf(prov({ filePath: "Other.md" }))).not.toBe(base);
    expect(rowRefOf(prov({ locator: { table: 0, row: 4 } }))).not.toBe(base);
    expect(rowRefOf(prov({ extractor: "tasks" }))).not.toBe(base);
  });

  it("changes when the row's content changes", () => {
    // So an edit made against a stale view of the data is refused rather than applied to whatever is
    // there now.
    expect(rowRefOf(prov({ fingerprint: "different" }))).not.toBe(rowRefOf(prov()));
  });

  it("reveals no path, so a handle can't be read as a location", () => {
    expect(rowRefOf(prov())).not.toContain("Library");
    expect(rowRefOf(prov())).not.toContain("/");
  });

  it("finds the row a handle names", () => {
    const rows = [row({ fingerprint: "one" }), row({ fingerprint: "two" })];
    const found = findRowByRef(rows, rowRefOf(prov({ fingerprint: "two" })));
    expect(found?.provenance.fingerprint).toBe("two");
  });

  it("matches nothing for a forged or stale handle", () => {
    // The reference is matched, never dereferenced — so a made-up one can't become a write.
    expect(findRowByRef([row()], "not-a-real-ref")).toBeNull();
    expect(findRowByRef([row()], "")).toBeNull();
    expect(findRowByRef([], rowRefOf(prov()))).toBeNull();
  });
});

describe("bridge · editableChanges", () => {
  const columns = [{ name: "Title" }, { name: "Status" }, { name: "Total" }];

  it("allows a change to an ordinary column", () => {
    const { allowed, skipped } = editableChanges(row(), [{ key: "Status", value: "Read" }], columns);
    expect(allowed).toEqual([{ column: "Status", value: "Read" }]);
    expect(skipped).toEqual([]);
  });

  it("matches a column name regardless of case", () => {
    const { allowed } = editableChanges(row(), [{ key: "status", value: "Read" }], columns);
    expect(allowed[0]?.column).toBe("Status");
  });

  it("REFUSES a read-only field, because nothing below this would stop it", () => {
    // In the app these are blocked by the editing surface; a bridge calling the writer directly would sail
    // straight past that and overwrite a computed value with a literal.
    const target = row({ readOnlyFields: ["Total"] });
    const { allowed, skipped } = editableChanges(target, [{ key: "Total", value: "999" }], columns);
    expect(allowed).toEqual([]);
    expect(skipped[0]?.column).toBe("Total");
    expect(skipped[0]?.reason).toMatch(/computed|owned/i);
  });

  it("matches read-only fields regardless of case too", () => {
    const target = row({ readOnlyFields: ["total"] });
    expect(editableChanges(target, [{ key: "Total", value: "1" }], columns).allowed).toEqual([]);
  });

  it("refuses a column the view doesn't have", () => {
    const { allowed, skipped } = editableChanges(row(), [{ key: "Nonsense", value: "x" }], columns);
    expect(allowed).toEqual([]);
    expect(skipped[0]?.reason).toMatch(/no such column/i);
  });

  it("applies what it can and reports what it couldn't, rather than failing wholesale", () => {
    const target = row({ readOnlyFields: ["Total"] });
    const { allowed, skipped } = editableChanges(
      target,
      [{ key: "Status", value: "Read" }, { key: "Total", value: "999" }],
      columns,
    );
    expect(allowed.map((a) => a.column)).toEqual(["Status"]);
    expect(skipped.map((s) => s.column)).toEqual(["Total"]);
  });
});

describe("bridge · normalizeUrl", () => {
  it("recognises the same page written differently", () => {
    const canonical = normalizeUrl("https://example.com/article");
    for (const variant of [
      "https://www.example.com/article",
      "http://example.com/article",
      "https://example.com/article/",
      "https://example.com/article#section",
      "https://EXAMPLE.com/article",
    ]) {
      expect(normalizeUrl(variant)).toBe(canonical);
    }
  });

  it("drops campaign parameters that don't identify a page", () => {
    expect(normalizeUrl("https://example.com/a?utm_source=twitter&utm_medium=social")).toBe(
      normalizeUrl("https://example.com/a"),
    );
    expect(normalizeUrl("https://example.com/a?fbclid=xyz")).toBe(normalizeUrl("https://example.com/a"));
  });

  it("KEEPS parameters that do identify a page", () => {
    // Dropping these would merge two genuinely different pages into one.
    expect(normalizeUrl("https://example.com/watch?v=abc")).not.toBe(normalizeUrl("https://example.com/watch"));
    expect(normalizeUrl("https://example.com/p?id=1")).not.toBe(normalizeUrl("https://example.com/p?id=2"));
  });

  it("orders parameters so the same query in either order matches", () => {
    expect(normalizeUrl("https://example.com/a?b=2&a=1")).toBe(normalizeUrl("https://example.com/a?a=1&b=2"));
  });

  it("leaves something that isn't a url alone, lowercased", () => {
    expect(normalizeUrl("not a url")).toBe("not a url");
    expect(normalizeUrl("")).toBe("");
  });

  it("doesn't rewrite non-web schemes", () => {
    expect(normalizeUrl("mailto:someone@example.com")).toBe("mailto:someone@example.com");
  });
});

describe("bridge · appending into a cell", () => {
  const columns = [{ name: "Annotations" }, { name: "Status" }, { name: "Total" }];

  it("joins the addition to what's there with <br>, keeping the cell's history", () => {
    const target = row({}, { Annotations: "First highlight" });
    const { allowed } = editableChanges(
      target,
      [{ key: "Annotations", value: "Second highlight", mode: "append" }],
      columns,
    );
    expect(allowed[0]?.value).toBe("First highlight<br>Second highlight");
  });

  it("starts cleanly when the cell was empty — no leading separator", () => {
    const target = row({}, { Annotations: "" });
    const { allowed } = editableChanges(target, [{ key: "Annotations", value: "First", mode: "append" }], columns);
    expect(allowed[0]?.value).toBe("First");
  });

  it("composes the final value against the vault's own row, not the caller's copy", () => {
    // The caller sends only the addition; whatever the cell holds NOW is what it lands after. Appending
    // can't replay a stale snapshot of the cell over a newer one.
    const target = row({}, { Annotations: "Newer content the caller never saw" });
    const { allowed } = editableChanges(target, [{ key: "Annotations", value: "Add", mode: "append" }], columns);
    expect(allowed[0]?.value).toContain("Newer content the caller never saw");
  });

  it("refuses to append nothing", () => {
    const target = row({}, { Annotations: "x" });
    const { allowed, skipped } = editableChanges(
      target,
      [{ key: "Annotations", value: "   ", mode: "append" }],
      columns,
    );
    expect(allowed).toEqual([]);
    expect(skipped[0]?.reason).toMatch(/nothing/i);
  });

  it("still refuses read-only cells in append mode", () => {
    const target = row({ readOnlyFields: ["Total"] }, { Total: "5" });
    const { allowed, skipped } = editableChanges(target, [{ key: "Total", value: "1", mode: "append" }], columns);
    expect(allowed).toEqual([]);
    expect(skipped[0]?.column).toBe("Total");
  });

  it("set mode still replaces outright", () => {
    const target = row({}, { Status: "Old" });
    const { allowed } = editableChanges(target, [{ key: "Status", value: "New" }], columns);
    expect(allowed[0]?.value).toBe("New");
  });
})

describe("bridge · annotation text through the cell path (adversarial)", () => {
  const columns = [{ name: "Annotations" }];

  it("carries pipes, quotes and brackets through append composition unmangled", () => {
    // The writer escapes pipes when it writes the table line; the composed value must arrive intact for
    // that to work. Composition mangling them here would corrupt silently.
    const nasty = 'He said "a | b" and [x](y) — plus a <br> literal';
    const target = row({}, { Annotations: "prior" });
    const { allowed } = editableChanges(target, [{ key: "Annotations", value: nasty, mode: "append" }], columns);
    expect(allowed[0]?.value).toBe(`prior<br>${nasty}`);
  });

  it("keeps newline-free composition: multi-line additions arrive as one cell line", () => {
    // A raw newline in a table cell breaks the row; callers must fold them before sending, and the
    // composition must not reintroduce any.
    const target = row({}, { Annotations: "a" });
    const { allowed } = editableChanges(target, [{ key: "Annotations", value: "one two", mode: "append" }], columns);
    expect(allowed[0]?.value).not.toContain("\n");
  });
})
