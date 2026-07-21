import { describe, it, expect } from "vitest";
import { tableToMarkdown } from "../shared/table-markdown";

describe("tableToMarkdown", () => {
  it("builds a GFM table with a header, divider, and body", () => {
    const md = tableToMarkdown(["Name", "Year"], [["Paper A", "2021"], ["Paper B", "2019"]]);
    expect(md).toBe("| Name | Year |\n| --- | --- |\n| Paper A | 2021 |\n| Paper B | 2019 |");
  });

  it("escapes pipes so a cell can't start a new column", () => {
    const md = tableToMarkdown(["A"], [["x | y"]]);
    expect(md).toContain("x \\| y");
  });

  it("flattens newlines within a cell so a row can't break", () => {
    const md = tableToMarkdown(["A"], [["line one\nline two"]]);
    expect(md).toBe("| A |\n| --- |\n| line one line two |");
  });

  it("pads short rows and trims long ones to the header width", () => {
    const md = tableToMarkdown(["A", "B", "C"], [["only one"], ["a", "b", "c", "extra"]]);
    const lines = md.split("\n");
    expect(lines[2]).toBe("| only one |  |  |");
    expect(lines[3]).toBe("| a | b | c |");
  });

  it("returns empty string for a table with no columns", () => {
    expect(tableToMarkdown([], [["x"]])).toBe("");
  });
});
