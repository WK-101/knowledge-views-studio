import { describe, it, expect, beforeEach } from "vitest";
import { selectionStore, scrollStore, forgetViewState, capViewState } from "../src/views/view-state";

describe("view-state pruning and cap", () => {
  beforeEach(() => {
    selectionStore.clear();
    scrollStore.clear();
  });

  it("forgets all state under a view prefix, leaving other views' state", () => {
    selectionStore.set("dashboard:v1:l1", new Set(["a"]));
    scrollStore.set("dashboard:v1:l1", 100);
    scrollStore.set("dashboard:v1:l2", 200);
    scrollStore.set("dashboard:v2:l1", 300);
    forgetViewState("dashboard:v1:"); // remove view v1 (all its layouts)
    expect(scrollStore.has("dashboard:v1:l1")).toBe(false);
    expect(scrollStore.has("dashboard:v1:l2")).toBe(false);
    expect(scrollStore.has("dashboard:v2:l1")).toBe(true);
    expect(selectionStore.has("dashboard:v1:l1")).toBe(false);
  });

  it("forgets an exact layout key without touching sibling layouts", () => {
    scrollStore.set("dashboard:v1:l1", 1);
    scrollStore.set("dashboard:v1:l2", 2);
    forgetViewState("dashboard:v1:l1");
    expect(scrollStore.has("dashboard:v1:l1")).toBe(false);
    expect(scrollStore.has("dashboard:v1:l2")).toBe(true);
  });

  it("caps a store so it can never grow without bound", () => {
    for (let i = 0; i < 600; i++) {
      scrollStore.set(`k${i}`, i);
      capViewState(scrollStore);
    }
    expect(scrollStore.size).toBeLessThanOrEqual(500);
    expect(scrollStore.has("k599")).toBe(true); // newest kept
    expect(scrollStore.has("k0")).toBe(false); // oldest evicted
  });
});
