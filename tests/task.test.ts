import { describe, it, expect } from "vitest";
import { taskExtractor } from "../src/domain/extract/task-extractor";
import { applyTaskEdits } from "../src/services/write/task-writer";
import type { Row } from "../src/domain/index";

const file = { filePath: "T.md", fileName: "T", folderPath: "", createdMs: 0, modifiedMs: 1, sizeBytes: 0 };
const extract = (content: string): Row[] => taskExtractor.extract({ file, content });
const doc = [
  "# Tasks",
  "- [ ] Write report 📅 2026-02-01 #work ⏫",
  "- [x] Buy milk",
  "    - [ ] Nested due:2026-03-15",
  "not a task",
].join("\n");

describe("task extractor", () => {
  it("parses status, due, tags and priority; ignores non-tasks", () => {
    const rows = extract(doc);
    expect(rows).toHaveLength(3);
    expect(rows[0]!.cells).toMatchObject({ task: "Write report 📅 2026-02-01 #work ⏫", done: "false", due: "2026-02-01", tags: "#work", priority: "high" });
    expect(rows[1]!.cells).toMatchObject({ task: "Buy milk", done: "true" });
    expect(rows[2]!.cells.due).toBe("2026-03-15");
  });
});

describe("task write-back", () => {
  const rowFor = (needle: string): Row => extract(doc).find((r) => r.cells.task!.includes(needle))!;

  it("toggles the checkbox", () => {
    const r = applyTaskEdits(doc, [{ provenance: rowFor("Write report").provenance, column: "done", value: "true" }]);
    expect(r.applied).toBe(1);
    expect(r.content).toContain("- [x] Write report");
  });

  it("edits the task text", () => {
    const r = applyTaskEdits(doc, [{ provenance: rowFor("Buy milk").provenance, column: "task", value: "Buy oat milk" }]);
    expect(r.content).toContain("- [x] Buy oat milk");
  });

  it("replaces an existing due date and appends a missing one", () => {
    const replaced = applyTaskEdits(doc, [{ provenance: rowFor("Write report").provenance, column: "due", value: "2026-12-31" }]);
    expect(replaced.content).toContain("📅 2026-12-31");
    const appended = applyTaskEdits(doc, [{ provenance: rowFor("Buy milk").provenance, column: "due", value: "2026-06-01" }]);
    expect(appended.content).toContain("Buy milk 📅 2026-06-01");
  });

  it("clears a due date and refuses unsupported columns without mangling", () => {
    const cleared = applyTaskEdits(doc, [{ provenance: rowFor("Write report").provenance, column: "due", value: "" }]);
    expect(cleared.content).not.toContain("2026-02-01");
    const refused = applyTaskEdits(doc, [{ provenance: rowFor("Buy milk").provenance, column: "priority", value: "high" }]);
    expect(refused.applied).toBe(0);
    expect(refused.failures).toHaveLength(1);
    expect(refused.content).toBe(doc);
  });
});
