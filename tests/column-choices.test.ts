import { describe, it, expect } from "vitest";
import { computeColumnChoices, suggestedColumns } from "../src/views/view-model";
import { createProfile } from "../src/services/index";
import { makeRow } from "./_helpers";

const rows = [makeRow({ Task: "A", Status: "Doing", Owner: "Mara" })];

describe("computeColumnChoices", () => {
  it("treats discovered fields as visible when no columns are configured", () => {
    const choices = computeColumnChoices(createProfile({ columns: [] }), rows);
    const visible = choices.filter((c) => c.visible).map((c) => c.name);
    expect(visible).toEqual(["Task", "Status", "Owner"]);
    // virtual fields are offered but hidden by default
    expect(choices.find((c) => c.name === "folder")?.visible).toBe(false);
  });

  it("honours configured visibility/order and lists the rest as hidden toggles", () => {
    const profile = createProfile({
      columns: [
        { name: "Status", type: "select" },
        { name: "Task", type: "text", visible: false },
      ],
    });
    const choices = computeColumnChoices(profile, rows);
    expect(choices.slice(0, 2).map((c) => c.name)).toEqual(["Status", "Task"]); // configured order
    expect(choices.find((c) => c.name === "Status")?.visible).toBe(true);
    expect(choices.find((c) => c.name === "Task")?.visible).toBe(false);
    // discovered-but-unconfigured field appears, hidden
    expect(choices.find((c) => c.name === "Owner")?.visible).toBe(false);
  });
});

describe("hiddenColumns (lightweight show/hide)", () => {
  it("hides a discovered column without freezing discovery mode", () => {
    const p = createProfile({ columns: [], hiddenColumns: ["status"] });
    const choices = computeColumnChoices(p, rows);
    // Status is offered but marked hidden; the others stay visible; columns stays empty (still discovery).
    expect(choices.find((c) => c.name === "Status")?.visible).toBe(false);
    expect(choices.filter((c) => c.visible).map((c) => c.name)).toEqual(["Task", "Owner"]);
    expect(p.columns).toEqual([]);
  });

  it("also hides a configured column via hiddenColumns", () => {
    const p = createProfile({
      columns: [
        { name: "Task", type: "text" },
        { name: "Status", type: "select" },
      ],
      hiddenColumns: ["status"],
    });
    const choices = computeColumnChoices(p, rows);
    expect(choices.find((c) => c.name === "Status")?.visible).toBe(false);
    expect(choices.find((c) => c.name === "Task")?.visible).toBe(true);
  });
});

describe("suggestedColumns", () => {
  it("returns nothing for a discovery view (everything already shows)", () => {
    expect(suggestedColumns(createProfile({ columns: [] }), rows)).toEqual([]);
  });

  it("suggests data fields a curated view doesn't show, with inferred types", () => {
    const profile = createProfile({ columns: [{ name: "Task", type: "text" }] });
    const suggestions = suggestedColumns(profile, rows);
    expect(suggestions.map((s) => s.name).sort()).toEqual(["Owner", "Status"]);
  });

  it("excludes fields the user explicitly hid, and virtual fields", () => {
    const profile = createProfile({ columns: [{ name: "Task", type: "text" }], hiddenColumns: ["Owner"] });
    const suggestions = suggestedColumns(profile, rows);
    expect(suggestions.map((s) => s.name)).toEqual(["Status"]); // Owner hidden, virtuals never suggested
  });

  it("suggests nothing once every field is configured", () => {
    const profile = createProfile({
      columns: [
        { name: "Task", type: "text" },
        { name: "Status", type: "text" },
        { name: "Owner", type: "text" },
      ],
    });
    expect(suggestedColumns(profile, rows)).toEqual([]);
  });
});
