import { describe, it, expect } from "vitest";
import { CellEditorRegistry, type CellEditor } from "../src/views/cells/cell-editor";

const fake = (typeId: string): CellEditor => ({ typeId, edit: () => {} });

describe("CellEditorRegistry", () => {
  it("looks up by type id and falls back when registered", () => {
    const registry = new CellEditorRegistry();
    registry.register(fake("text"), true);
    registry.register(fake("select"));
    expect(registry.get("select")?.typeId).toBe("select");
    expect(registry.get("unknown")?.typeId).toBe("text"); // fallback
  });

  it("returns null when there is no match and no fallback", () => {
    const registry = new CellEditorRegistry();
    expect(registry.get("anything")).toBeNull();
  });
});
