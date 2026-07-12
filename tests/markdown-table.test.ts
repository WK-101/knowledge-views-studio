import { describe, it, expect } from "vitest";
import { parseMarkdownTables } from "../src/domain/extract/markdown-table";

const DOC = [
  "# Notes", // 0
  "", // 1
  "| Source | Year |", // 2
  "| --- | --- |", // 3
  "| [[Paper A]] | 2021 |", // 4
  "| [[Paper B]] | 2019 |", // 5
  "", // 6
  "Some prose", // 7
  "", // 8
  "| Task | Done |", // 9
  "|---|---|", // 10
  "| Write tests | x |", // 11
].join("\n");

describe("parseMarkdownTables", () => {
  it("finds every table with correct headers, rows, and line numbers", () => {
    const tables = parseMarkdownTables(DOC);
    expect(tables).toHaveLength(2);

    const first = tables[0]!;
    expect(first.headers).toEqual(["Source", "Year"]);
    expect(first.headerLine).toBe(2);
    expect(first.rows).toHaveLength(2);
    expect(first.rows[0]!.cells).toEqual(["[[Paper A]]", "2021"]);
    expect(first.rows[0]!.line).toBe(4);
    expect(first.rows[1]!.line).toBe(5);

    const second = tables[1]!;
    expect(second.headers).toEqual(["Task", "Done"]);
    expect(second.rows[0]!.line).toBe(11);
  });

  it("ignores a header with no body rows and handles empty input", () => {
    expect(parseMarkdownTables("| A | B |\n| --- | --- |\n\ntext")).toHaveLength(0);
    expect(parseMarkdownTables("")).toHaveLength(0);
  });
});
