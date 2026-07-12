import { describe, it, expect } from "vitest";
import { UndoManager } from "../src/services/undo/undo-manager";

describe("UndoManager", () => {
  it("undoes in LIFO order and reports labels", async () => {
    const log: string[] = [];
    const m = new UndoManager();
    m.push({ label: "first", undo: async () => void log.push("undo-first") });
    m.push({ label: "second", undo: async () => void log.push("undo-second") });

    expect(m.canUndo()).toBe(true);
    expect(m.peekLabel()).toBe("second");
    expect(await m.undo()).toBe("second");
    expect(await m.undo()).toBe("first");
    expect(log).toEqual(["undo-second", "undo-first"]);
    expect(m.canUndo()).toBe(false);
    expect(await m.undo()).toBeNull();
  });

  it("enforces the max-size cap by dropping the oldest", async () => {
    const m = new UndoManager(2);
    const ran: string[] = [];
    for (const label of ["a", "b", "c"]) {
      m.push({ label, undo: async () => void ran.push(label) });
    }
    expect(m.size()).toBe(2);
    expect(await m.undo()).toBe("c");
    expect(await m.undo()).toBe("b");
    expect(await m.undo()).toBeNull(); // "a" was dropped
  });

  it("clear empties the stack", () => {
    const m = new UndoManager();
    m.push({ label: "x", undo: async () => {} });
    m.clear();
    expect(m.canUndo()).toBe(false);
  });
});
