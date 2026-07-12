import { describe, it, expect } from "vitest";
import { splitList, LIST } from "../src/domain/columns/types/list";
import { splitTags } from "../src/domain/columns/types/tags";

describe("list column type", () => {
  it("splits on commas/semicolons/newlines but keeps multi-word items whole", () => {
    expect(splitList("New York, Los Angeles; Rome")).toEqual(["New York", "Los Angeles", "Rome"]);
    expect(splitList("flour\neggs\nsugar")).toEqual(["flour", "eggs", "sugar"]);
  });

  it("differs from tags, which also splits on spaces", () => {
    expect(splitList("New York")).toEqual(["New York"]); // one list item
    expect(splitTags("New York")).toEqual(["New", "York"]); // two tags
  });

  it("reports emptiness and plain text correctly", () => {
    expect(LIST.isEmpty("")).toBe(true);
    expect(LIST.isEmpty("a, b")).toBe(false);
    expect(LIST.toPlainText("a,  b ,c")).toBe("a, b, c");
  });
});
