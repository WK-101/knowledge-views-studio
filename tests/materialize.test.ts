import { describe, it, expect } from "vitest";
import { tableExtractor } from "../src/domain/index";
import { applyRollups, type RollupColumn } from "../src/domain/transform/rollup";
import { applyCellEdits } from "../src/services/write/source-writer";
import { FieldTypeResolver } from "../src/domain/transform/field-type";
import { createDefaultColumnTypeRegistry, getField, type Row } from "../src/domain/index";

const registry = createDefaultColumnTypeRegistry();

function extract(path: string, content: string): Row[] {
  return tableExtractor.extract({
    file: {
      filePath: path,
      fileName: path.replace(/^.*\//, "").replace(/\.md$/, ""),
      folderPath: path.includes("/") ? path.replace(/\/[^/]*$/, "") : "",
      createdMs: 0,
      modifiedMs: 1,
      sizeBytes: content.length,
    },
    content,
  });
}

describe("materialize (derived value -> source cell)", () => {
  const projectContent = ["| Project | Tasks | Count |", "| --- | --- | --- |", "| P | [[TasksA]] |  |"].join("\n");
  const tasksContent = ["| Title | Hours |", "| --- | --- |", "| a | 2 |", "| b | 3 |"].join("\n");

  const rollup: RollupColumn = { name: "Count", relationField: "Tasks", targetField: "", aggregate: "count", materializeTo: "Count" };
  const resolver = new FieldTypeResolver(registry, []);

  it("writes the rolled-up value into the existing target column", () => {
    const rows = [...extract("Project.md", projectContent), ...extract("TasksA.md", tasksContent)];
    const rolled = applyRollups(rows, [rollup], resolver);
    const projectRow = rolled.find((r) => r.file.fileName === "Project")!;
    expect(getField(projectRow, "Count")).toBe("2");

    const result = applyCellEdits(projectContent, [
      { provenance: projectRow.provenance, column: "Count", value: getField(projectRow, "Count") },
    ]);
    expect(result.applied).toBe(1);
    expect(result.failures).toHaveLength(0);
    expect(result.content).toContain("| P | [[TasksA]] | 2 |");
  });

  it("fails cleanly (no corruption) when the target column is missing", () => {
    const rows = [...extract("Project.md", projectContent), ...extract("TasksA.md", tasksContent)];
    const rolled = applyRollups(rows, [{ ...rollup, materializeTo: "Missing" }], resolver);
    const projectRow = rolled.find((r) => r.file.fileName === "Project")!;
    const result = applyCellEdits(projectContent, [
      { provenance: projectRow.provenance, column: "Missing", value: "2" },
    ]);
    expect(result.applied).toBe(0);
    expect(result.failures).toHaveLength(1);
    expect(result.content).toBe(projectContent); // unchanged
  });
});
