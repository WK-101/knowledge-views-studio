import { describe, it, expect } from "vitest";
import { ViewRegistry } from "../src/views/registry";
import { CellRendererRegistry } from "../src/views/cells/cell-renderer";
import type { KnowledgeView } from "../src/views/view";
import type { CellRenderer } from "../src/views/cells/cell-renderer";

const fakeView = (type: string): KnowledgeView => ({ type, label: type, render: () => {} });
const fakeRenderer = (typeId: string): CellRenderer => ({ typeId, render: () => {} });

describe("ViewRegistry", () => {
  it("registers, looks up, and falls back to the first view", () => {
    const registry = new ViewRegistry();
    registry.register(fakeView("table"), true);
    registry.register(fakeView("cards"));
    expect(registry.get("cards")?.type).toBe("cards");
    expect(registry.get("nonexistent")?.type).toBe("table"); // fallback
    expect(registry.has("cards")).toBe(true);
    expect(registry.all()).toHaveLength(2);
  });
});

describe("CellRendererRegistry", () => {
  it("looks up by type id with a registered fallback", () => {
    const registry = new CellRendererRegistry();
    registry.register(fakeRenderer("text"), true);
    registry.register(fakeRenderer("number"));
    expect(registry.get("number")?.typeId).toBe("number");
    expect(registry.get("unknown")?.typeId).toBe("text"); // fallback
    expect(registry.has("number")).toBe(true);
  });
});
