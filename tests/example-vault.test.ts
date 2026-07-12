import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { DataService } from "../src/services/data-service";
import { createProfile, DEFAULT_SETTINGS } from "../src/services/profile/profile";
import { ExtractorRegistry, createDefaultColumnTypeRegistry, tableExtractor, getField } from "../src/domain/index";
import { buildKanbanBoard } from "../src/views/kanban/board";
import { buildPivot } from "../src/views/pivot/pivot";
import { FakeVaultGateway } from "./_helpers";

const PROJECT_NOTES = ["Website Redesign.md", "Mobile App.md", "Marketing Launch.md"];

function loadExampleVault(): DataService {
  const gateway = new FakeVaultGateway();
  for (const name of PROJECT_NOTES) {
    const content = readFileSync(join(__dirname, "..", "examples", "Projects", name), "utf8");
    gateway.setFile(`Projects/${name}`, content);
  }
  // a note outside the scope, to prove folder scoping works
  gateway.setFile("Inbox/Scratch.md", "| Task | Status |\n| --- | --- |\n| Ignore me | Todo |");
  return new DataService({
    gateway,
    registry: createDefaultColumnTypeRegistry(),
    extractors: new ExtractorRegistry().register(tableExtractor),
    getSettings: () => DEFAULT_SETTINGS,
  });
}

describe("example vault — end-to-end pipeline", () => {
  it("aggregates in-body task rows from the Projects folder only", async () => {
    const service = loadExampleVault();
    const profile = createProfile({
      scope: { mode: "folders", folders: ["Projects"], includeSubfolders: true },
      pageSize: null,
    });
    const result = await service.query(profile);
    expect(result.total).toBe(13); // 5 + 4 + 4, the Inbox note excluded
    expect(result.rows.every((r) => getField(r, "Task") !== "")).toBe(true);
    service.dispose();
  });

  it("builds the status board the Task Board note renders", async () => {
    const service = loadExampleVault();
    const profile = createProfile({
      scope: { mode: "folders", folders: ["Projects"], includeSubfolders: true },
      pageSize: null,
    });
    const { rows } = await service.query(profile);
    const board = buildKanbanBoard(rows, "Status");
    const count = (key: string): number => board.columns.find((c) => c.key === key)?.rows.length ?? 0;
    expect(count("Done")).toBe(3);
    expect(count("Doing")).toBe(4);
    expect(count("Todo")).toBe(6);
    service.dispose();
  });

  it("sums story points by owner × status for the summary block", async () => {
    const service = loadExampleVault();
    const profile = createProfile({
      scope: { mode: "folders", folders: ["Projects"], includeSubfolders: true },
      pageSize: null,
    });
    const { rows } = await service.query(profile);
    const pivot = buildPivot(rows, "Owner", "Status", { kind: "sum", field: "Points" });
    expect(pivot.grandTotal).toBe(71);
    const owners = new Set(pivot.rowKeys);
    expect(owners).toEqual(new Set(["Mara", "Devin", "Priya"]));
    const maraTotal = pivot.rowTotals[pivot.rowKeys.indexOf("Mara")];
    expect(maraTotal).toBe(19);
    service.dispose();
  });
});
