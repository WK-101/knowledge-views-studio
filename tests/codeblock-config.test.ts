import { describe, it, expect } from "vitest";
import { parseViewBlock } from "../src/codeblock/config";

describe("parseViewBlock", () => {
  it("parses keys, lists, sort, columns, and limit", () => {
    const config = parseViewBlock(
      [
        "# a comment",
        "profile: Papers",
        "view: Cards",
        "folders: Research, Archive/Old",
        "query: Year >= 2020",
        "sort: Year desc, Title",
        "columns: Title, Year:number",
        "limit: 25",
        "group-by: Status",
      ].join("\n"),
    );
    expect(config.profile).toBe("Papers");
    expect(config.view).toBe("cards");
    expect(config.folders).toEqual(["Research", "Archive/Old"]);
    expect(config.query).toBe("Year >= 2020");
    expect(config.sort).toEqual([
      { field: "Year", direction: "desc" },
      { field: "Title", direction: "asc" },
    ]);
    expect(config.columns).toEqual([{ name: "Title" }, { name: "Year", type: "number" }]);
    expect(config.limit).toBe(25);
    expect(config.group).toBe("Status");
  });

  it("ignores blanks, comments, and malformed lines", () => {
    const config = parseViewBlock(["", "  ", "not a pair", "view:", "limit: nope"].join("\n"));
    expect(config).toEqual({});
  });
});

describe("view options in blocks", () => {
  it("parses option.<Key> entries with their case preserved", () => {
    const config = parseViewBlock(["view: kanban", "option.groupField: Status", "option.weekStart: mon"].join("\n"));
    expect(config.view).toBe("kanban");
    expect(config.viewOptions).toEqual({ groupField: "Status", weekStart: "mon" });
  });
});
