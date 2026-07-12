import { describe, it, expect } from "vitest";
import { inferFieldRole } from "../src/domain/columns/field-role";
import { findColumnByRole, resolveColumns } from "../src/views/view-model";
import { createProfile } from "../src/services/index";
import { makeRow } from "./_helpers";

describe("inferFieldRole", () => {
  it("infers from type id", () => {
    expect(inferFieldRole("date", "Whatever")).toBe("date");
    expect(inferFieldRole("tags", "Whatever")).toBe("tags");
  });
  it("infers from header name", () => {
    expect(inferFieldRole("text", "Status")).toBe("status");
    expect(inferFieldRole("text", "Due")).toBe("date");
    expect(inferFieldRole("select", "Priority")).toBe("priority");
    expect(inferFieldRole("text", "Title")).toBe("title");
    expect(inferFieldRole("text", "Owner")).toBe("none");
  });
});

describe("findColumnByRole via resolveColumns", () => {
  const rows = [makeRow({ Task: "A", Status: "Doing", Due: "2026-01-01" })];

  it("resolves and finds roles on discovered columns", () => {
    const columns = resolveColumns(createProfile({ columns: [] }), rows);
    expect(findColumnByRole(columns, "status")?.name).toBe("Status");
    expect(findColumnByRole(columns, "date")?.name).toBe("Due");
    expect(findColumnByRole(columns, "priority")).toBeUndefined();
  });

  it("honours an explicit role override on a configured column", () => {
    const profile = createProfile({
      columns: [
        { name: "Task", type: "text", role: "title" },
        { name: "Phase", type: "select" }, // matches status name heuristic
      ],
    });
    const columns = resolveColumns(profile, rows);
    expect(findColumnByRole(columns, "title")?.name).toBe("Task");
    expect(findColumnByRole(columns, "status")?.name).toBe("Phase");
  });
});
