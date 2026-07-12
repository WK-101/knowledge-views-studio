import { describe, it, expect } from "vitest";
import { resolveDefaultTokens, resolveRowDefaults } from "../src/domain/columns/defaults";
import type { ColumnConfig } from "../src/domain/columns/column-type";

const FIXED = new Date(2025, 0, 6, 9, 5); // 2025-01-06 09:05 (local)

describe("row default values", () => {
  it("resolves date/time tokens against the given time", () => {
    expect(resolveDefaultTokens("{{today}}", FIXED)).toBe("2025-01-06");
    expect(resolveDefaultTokens("{{now}}", FIXED)).toBe("2025-01-06 09:05");
    expect(resolveDefaultTokens("{{time}}", FIXED)).toBe("09:05");
    expect(resolveDefaultTokens("Due: {{today}}", FIXED)).toBe("Due: 2025-01-06");
  });

  it("is case- and space-insensitive on tokens, and leaves literals untouched", () => {
    expect(resolveDefaultTokens("{{ TODAY }}", FIXED)).toBe("2025-01-06");
    expect(resolveDefaultTokens("Todo", FIXED)).toBe("Todo");
  });

  it("builds new-row values only for columns that have a default", () => {
    const columns: ColumnConfig[] = [
      { name: "Task", type: "text" },
      { name: "Status", type: "select", defaultValue: "Todo" },
      { name: "Created", type: "date", defaultValue: "{{today}}" },
      { name: "Notes", type: "text", defaultValue: "   " }, // blank ⇒ skipped
    ];
    const values = resolveRowDefaults(columns, FIXED);
    expect(values).toEqual({ Status: "Todo", Created: "2025-01-06" });
    expect("Task" in values).toBe(false);
    expect("Notes" in values).toBe(false);
  });
});
