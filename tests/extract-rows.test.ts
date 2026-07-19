import { describe, it, expect } from "vitest";
import {
  findRowCandidates,
  tableCandidates,
  listCandidates,
  candidateRowToFields,
  normalizeHeaders,
  looksLikeData,
  MAX_CANDIDATE_ROWS,
} from "../shared/extract-rows";
import type { PageSnapshot, RawTable } from "../shared/extract";

const page = (patch: Partial<PageSnapshot> = {}): PageSnapshot => ({
  url: "https://example.com/list",
  ...patch,
});

const table = (patch: Partial<RawTable> = {}): RawTable => ({
  headers: ["Title", "Author"],
  rows: [
    ["First paper", "Ada"],
    ["Second paper", "Grace"],
  ],
  ...patch,
});

describe("rows · normalizeHeaders", () => {
  it("names an unnamed column rather than leaving a blank", () => {
    expect(normalizeHeaders(["Title", "", "Year"])).toEqual(["Title", "Column 2", "Year"]);
  });

  it("makes repeated headers distinct, since a duplicate would overwrite the first", () => {
    expect(normalizeHeaders(["Name", "Name", "Name"])).toEqual(["Name", "Name 2", "Name 3"]);
  });

  it("collapses whitespace in a header", () => {
    expect(normalizeHeaders(["  Published   Date "])).toEqual(["Published Date"]);
  });
});

describe("rows · looksLikeData", () => {
  it("accepts a table with headers and several rows", () => {
    expect(looksLikeData(table())).toBe(true);
  });

  it("rejects a single-column table, which is a list not a table", () => {
    expect(looksLikeData(table({ headers: ["Title"], rows: [["a"], ["b"]] }))).toBe(false);
  });

  it("rejects a one-row table", () => {
    expect(looksLikeData(table({ rows: [["only", "one"]] }))).toBe(false);
  });

  it("rejects a layout table whose headers are mostly empty", () => {
    // Pages still position things with tables; those have the same tags as real ones.
    expect(looksLikeData(table({ headers: ["", "", ""], rows: [["a", "b", "c"], ["d", "e", "f"]] }))).toBe(false);
  });

  it("rejects a table whose rows are empty scaffolding", () => {
    expect(looksLikeData(table({ rows: [["", ""], ["", ""]] }))).toBe(false);
  });
});

describe("rows · from tables", () => {
  it("finds a data table and keeps its rows", () => {
    const found = tableCandidates(page({ tables: [table()] }));
    expect(found).toHaveLength(1);
    expect(found[0]?.rows).toHaveLength(2);
    expect(found[0]?.headers).toEqual(["Title", "Author"]);
  });

  it("uses a caption as the label so two tables can be told apart", () => {
    const found = tableCandidates(page({ tables: [table({ caption: "Volume 12 contents" })] }));
    expect(found[0]?.label).toBe("Volume 12 contents");
  });

  it("falls back to a numbered label when there's no caption", () => {
    expect(tableCandidates(page({ tables: [table()] }))[0]?.label).toBe("Table 1");
  });

  it("pads short rows so every row lines up with the headers", () => {
    const found = tableCandidates(
      page({ tables: [table({ headers: ["A", "B", "C"], rows: [["1", "2"], ["3", "4", "5"]] })] }),
    );
    expect(found[0]?.rows.every((r) => r.length === 3)).toBe(true);
  });

  it("drops rows that are entirely empty", () => {
    const found = tableCandidates(
      page({ tables: [table({ rows: [["a", "b"], ["", ""], ["c", "d"]] })] }),
    );
    expect(found[0]?.rows).toHaveLength(2);
  });

  it("caps how many rows one page can offer", () => {
    const many = Array.from({ length: MAX_CANDIDATE_ROWS + 50 }, (_, i) => [`t${String(i)}`, "x"]);
    const found = tableCandidates(page({ tables: [table({ rows: many })] }));
    expect(found[0]?.rows).toHaveLength(MAX_CANDIDATE_ROWS);
  });

  it("ignores layout tables entirely", () => {
    expect(tableCandidates(page({ tables: [table({ headers: ["", ""], rows: [["a", "b"], ["c", "d"]] })] }))).toHaveLength(0);
  });
});

describe("rows · from JSON-LD lists", () => {
  it("reads an ItemList of entities", () => {
    const found = listCandidates(
      page({
        jsonLd: [
          {
            "@type": "ItemList",
            itemListElement: [
              { name: "One", author: "Ada", url: "https://a" },
              { name: "Two", author: "Grace", url: "https://b" },
            ],
          },
        ],
      }),
    );
    expect(found).toHaveLength(1);
    expect(found[0]?.rows).toHaveLength(2);
    expect(found[0]?.headers).toContain("Title");
  });

  it("unwraps ListItem entries that wrap the thing they describe", () => {
    const found = listCandidates(
      page({
        jsonLd: [
          {
            itemListElement: [
              { "@type": "ListItem", item: { name: "Wrapped one", url: "https://a" } },
              { "@type": "ListItem", item: { name: "Wrapped two", url: "https://b" } },
            ],
          },
        ],
      }),
    );
    expect(found[0]?.rows.map((r) => r[0])).toEqual(["Wrapped one", "Wrapped two"]);
  });

  it("only offers columns something actually fills", () => {
    const found = listCandidates(
      page({ jsonLd: [{ itemListElement: [{ name: "One" }, { name: "Two" }] }] }),
    );
    // Author and Published were absent from every entity, so they aren't offered as empty columns.
    expect(found).toHaveLength(0); // fewer than two filled columns is a list, not a table
  });

  it("flattens an author given as an object", () => {
    const found = listCandidates(
      page({
        jsonLd: [
          {
            itemListElement: [
              { name: "One", author: { name: "Ada" } },
              { name: "Two", author: { name: "Grace" } },
            ],
          },
        ],
      }),
    );
    expect(found[0]?.rows[0]?.[1]).toBe("Ada");
  });

  it("needs at least two entities to be a list at all", () => {
    expect(listCandidates(page({ jsonLd: [{ itemListElement: [{ name: "Only", url: "https://a" }] }] }))).toHaveLength(0);
  });

  it("survives malformed JSON-LD", () => {
    expect(() => listCandidates(page({ jsonLd: [null, 7, "text", []] }))).not.toThrow();
  });
});

describe("rows · findRowCandidates", () => {
  it("prefers a structured list over a table, since the page describes its own entities", () => {
    const found = findRowCandidates(
      page({
        tables: [table()],
        jsonLd: [
          {
            itemListElement: [
              { name: "One", url: "https://a" },
              { name: "Two", url: "https://b" },
            ],
          },
        ],
      }),
    );
    expect(found[0]?.kind).toBe("list");
    expect(found[1]?.kind).toBe("table");
  });

  it("finds nothing on an ordinary article page", () => {
    expect(findRowCandidates(page({ meta: [{ key: "og:title", content: "An article" }] }))).toHaveLength(0);
  });
});

describe("rows · candidateRowToFields", () => {
  it("pairs each header with its cell", () => {
    const candidate = tableCandidates(page({ tables: [table()] }))[0]!;
    expect(candidateRowToFields(candidate, 0)).toEqual([
      { key: "Title", value: "First paper" },
      { key: "Author", value: "Ada" },
    ]);
  });

  it("omits empty cells rather than sending blank fields", () => {
    const candidate = tableCandidates(
      page({ tables: [table({ rows: [["Title only", ""], ["a", "b"]] })] }),
    )[0]!;
    expect(candidateRowToFields(candidate, 0)).toEqual([{ key: "Title", value: "Title only" }]);
  });

  it("returns nothing for a row that isn't there", () => {
    const candidate = tableCandidates(page({ tables: [table()] }))[0]!;
    expect(candidateRowToFields(candidate, 99)).toEqual([]);
  });
});
