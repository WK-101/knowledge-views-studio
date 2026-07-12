import { describe, it, expect } from "vitest";
import { applyRollups, type RollupColumn } from "../src/domain/transform/rollup";
import { relationTargets } from "../src/domain/columns/types/relation";
import { parseWikiLinks } from "../src/domain/columns/types/link";
import { FieldTypeResolver } from "../src/domain/transform/field-type";
import { createDefaultColumnTypeRegistry, type ColumnConfig } from "../src/domain/index";
import { makeRow } from "./_helpers";

const registry = createDefaultColumnTypeRegistry();
const resolver = (cols: ColumnConfig[]): FieldTypeResolver => new FieldTypeResolver(registry, cols);
const hoursResolver = resolver([{ name: "hours", type: "number" }, { name: "due", type: "date" }]);

const task = (note: string, hours: string, extra: Record<string, string> = {}): ReturnType<typeof makeRow> =>
  makeRow({ hours, ...extra }, { fileName: note });
const project = (rel: string): ReturnType<typeof makeRow> => makeRow({ tasks: rel, name: "P" }, { fileName: "Project" });

describe("parseWikiLinks / relationTargets", () => {
  it("extracts multiple links, ignoring aliases and #/^ anchors", () => {
    expect(relationTargets("[[A]], [[b/C|Cee]] and [[D#head]]")).toEqual(["A", "b/C", "D"]);
    expect(parseWikiLinks("[[A|Alias]]")[0]).toEqual({ target: "A", alias: "Alias" });
    expect(relationTargets("no links here")).toEqual([]);
  });
});

describe("applyRollups", () => {
  it("counts the rows of linked notes", () => {
    const rows = [project("[[TasksA]]"), task("TasksA", "2"), task("TasksA", "3")];
    const rollups: RollupColumn[] = [{ name: "n", relationField: "tasks", targetField: "", aggregate: "count" }];
    const out = applyRollups(rows, rollups, hoursResolver);
    expect(out[0]!.cells.n).toBe("2"); // project row sees 2 tasks
    expect(out[1]!.cells.n).toBe("0"); // task rows have no relation
  });

  it("sums, averages, mins and maxes a numeric target", () => {
    const rows = [project("[[TasksA]]"), task("TasksA", "2"), task("TasksA", "3"), task("TasksA", "10")];
    const roll = (aggregate: RollupColumn["aggregate"]): RollupColumn => ({
      name: "r", relationField: "tasks", targetField: "hours", aggregate,
    });
    expect(applyRollups(rows, [roll("sum")], hoursResolver)[0]!.cells.r).toBe("15");
    expect(applyRollups(rows, [roll("avg")], hoursResolver)[0]!.cells.r).toBe("5");
    expect(applyRollups(rows, [roll("min")], hoursResolver)[0]!.cells.r).toBe("2");
    expect(applyRollups(rows, [roll("max")], hoursResolver)[0]!.cells.r).toBe("10");
  });

  it("lists and de-duplicates target values", () => {
    const rows = [project("[[TasksA]]"), task("TasksA", "2", { tag: "x" }), task("TasksA", "3", { tag: "x" })];
    const r = resolver([{ name: "tag", type: "text" }]);
    expect(applyRollups(rows, [{ name: "r", relationField: "tasks", targetField: "tag", aggregate: "list" }], r)[0]!.cells.r).toBe("x, x");
    expect(applyRollups(rows, [{ name: "r", relationField: "tasks", targetField: "tag", aggregate: "unique" }], r)[0]!.cells.r).toBe("x");
    expect(applyRollups(rows, [{ name: "r", relationField: "tasks", targetField: "tag", aggregate: "count-unique" }], r)[0]!.cells.r).toBe("1");
  });

  it("follows multiple links and de-duplicates related rows", () => {
    const rows = [project("[[TasksA]], [[TasksB]]"), task("TasksA", "2"), task("TasksB", "5")];
    const out = applyRollups(rows, [{ name: "s", relationField: "tasks", targetField: "hours", aggregate: "sum" }], hoursResolver);
    expect(out[0]!.cells.s).toBe("7");
  });

  it("matches by path when asked", () => {
    const rows = [
      makeRow({ tasks: "[[Notes/TasksA]]", name: "P" }, { fileName: "Project" }),
      task("TasksA", "4"), // path Notes/TasksA.md
    ];
    const byPath = applyRollups(rows, [{ name: "s", relationField: "tasks", targetField: "hours", aggregate: "sum", matchBy: "path" }], hoursResolver);
    expect(byPath[0]!.cells.s).toBe("4");
  });

  it("returns empty results for missing relations and is a zero-copy no-op when unused", () => {
    const rows = [project("[[Nope]]"), task("TasksA", "2")];
    expect(applyRollups(rows, [{ name: "n", relationField: "tasks", targetField: "", aggregate: "count" }], hoursResolver)[0]!.cells.n).toBe("0");
    expect(applyRollups(rows, [], hoursResolver)).toBe(rows);
  });
});
