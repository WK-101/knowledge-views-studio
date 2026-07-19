import { describe, it, expect } from "vitest";
import { displayColumns } from "../extension/src/lib/dashboard-panel";
import type { SchemaColumn } from "../shared/protocol";

const col = (name: string, patch: Partial<SchemaColumn> = {}): SchemaColumn => ({
  name,
  typeId: "text",
  ...patch,
});

describe("dashboard · choosing what to show in a narrow panel", () => {
  it("puts the title first, wherever it sits in the view", () => {
    const columns = [col("Notes"), col("Name", { role: "title" }), col("Year")];
    expect(displayColumns(columns)[0]?.name).toBe("Name");
  });

  it("recognises a title by name when no role says so", () => {
    expect(displayColumns([col("Year"), col("Title")])[0]?.name).toBe("Title");
  });

  it("puts the things you act on next", () => {
    const columns = [col("Title", { role: "title" }), col("Year"), col("Status", { role: "status" })];
    expect(displayColumns(columns).map((c) => c.name).slice(0, 2)).toEqual(["Title", "Status"]);
  });

  it("leaves prose out entirely, since a paragraph in a narrow column helps nobody", () => {
    const columns = [col("Title", { role: "title" }), col("Abstract", { typeId: "markdown" })];
    expect(displayColumns(columns).map((c) => c.name)).not.toContain("Abstract");
  });

  it("shows only a few, so a twenty-column view still fits", () => {
    const many = Array.from({ length: 20 }, (_, i) => col(`C${String(i)}`));
    expect(displayColumns(many).length).toBeLessThanOrEqual(4);
  });

  it("prefers a choice column over a plain one, since it can be changed inline", () => {
    const columns = [col("Title", { role: "title" }), col("Misc"), col("Stage", { options: ["A", "B"] })];
    expect(displayColumns(columns, 2).map((c) => c.name)).toEqual(["Title", "Stage"]);
  });

  it("copes with a view that has nothing but prose", () => {
    expect(displayColumns([col("Body", { typeId: "markdown" })])).toEqual([]);
  });
});
