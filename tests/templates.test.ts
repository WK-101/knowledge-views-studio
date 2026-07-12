import { describe, it, expect } from "vitest";
import { STARTER_TEMPLATES } from "../src/workspace/templates";
import { parseMarkdownTables } from "../src/domain/index";

describe("starter templates", () => {
  it("each renders a note with exactly one parseable, non-empty table", () => {
    for (const t of STARTER_TEMPLATES) {
      const tables = parseMarkdownTables(t.content());
      expect(tables.length, t.id).toBe(1);
      expect(tables[0]!.headers.length, t.id).toBeGreaterThan(1);
      expect(tables[0]!.rows.length, t.id).toBeGreaterThan(0);
    }
  });

  it("view options (groupField/dateField) reference a real column in the table", () => {
    for (const t of STARTER_TEMPLATES) {
      const headers = parseMarkdownTables(t.content())[0]!.headers.map((h) => h.trim().toLowerCase());
      for (const key of ["groupField", "dateField"] as const) {
        const field = t.viewOptions[key];
        if (field) expect(headers, `${t.id}.${key}`).toContain(field.toLowerCase());
      }
    }
  });

  it("gives each template a unique id and a view type", () => {
    const ids = STARTER_TEMPLATES.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const t of STARTER_TEMPLATES) expect(t.viewType, t.id).toBeTruthy();
  });
});
