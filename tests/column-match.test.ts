import { describe, it, expect } from "vitest";
import { rowMatchesColumns } from "../src/domain/transform/column-match";
import { runTransform } from "../src/domain/transform/pipeline";
import { createDefaultColumnTypeRegistry } from "../src/domain/columns/index";
import { makeRow } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();

describe("rowMatchesColumns", () => {
  const taskRow = makeRow({ Task: "A", Status: "Doing" });
  const otherRow = makeRow({ Title: "X", Author: "Y" });

  it("loose matches everything", () => {
    expect(rowMatchesColumns(otherRow, ["Task", "Status"], "loose")).toBe(true);
  });

  it("contains requires every configured (non-virtual) column to be present", () => {
    expect(rowMatchesColumns(taskRow, ["Task", "Status"], "contains")).toBe(true);
    expect(rowMatchesColumns(taskRow, ["Task", "Owner"], "contains")).toBe(false);
    expect(rowMatchesColumns(otherRow, ["Task"], "contains")).toBe(false);
    // virtual fields are ignored when matching
    expect(rowMatchesColumns(taskRow, ["Task", "Status", "note"], "contains")).toBe(true);
  });

  it("exact additionally forbids extra headers", () => {
    expect(rowMatchesColumns(taskRow, ["Task", "Status"], "exact")).toBe(true);
    expect(rowMatchesColumns(taskRow, ["Task"], "exact")).toBe(false); // Status is extra
  });
});

describe("pipeline honours columnMatch", () => {
  const rows = [makeRow({ Task: "A", Status: "Doing" }), makeRow({ Title: "X" })];

  it("loose keeps mismatched rows; contains drops them", () => {
    const columns = [{ name: "Task", type: "text" }, { name: "Status", type: "select" }];
    const loose = runTransform(rows, { columns, columnMatch: "loose" }, { registry });
    expect(loose.total).toBe(2);
    const strict = runTransform(rows, { columns, columnMatch: "contains" }, { registry });
    expect(strict.total).toBe(1);
    expect(strict.rows[0]!.cells.Task).toBe("A");
  });
});
