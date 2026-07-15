import { describe, expect, it } from "vitest";
import { enableHandleDrag } from "../src/util/pointer-drag";

/**
 * The pure state machine is tested next door. This proves the *wiring*: that `enableHandleDrag` attaches
 * pointer listeners and drives its callbacks from them — including for `pointerType: "touch"`, which is
 * the precise thing the old HTML5 `draggable`/`dragstart` code could never do. A regression here is the
 * whole bug coming back, so it is worth a test that does not trust the browser to be present.
 *
 * A minimal EventTarget-ish double stands in for an Obsidian HTMLElement: just the surface this function
 * touches — add/removeEventListener, the Obsidian `addClass`/`removeClass` sugar, and pointer capture.
 * No jsdom, so the Node-only suite stays Node-only.
 */
class FakeEl {
  private readonly listeners = new Map<string, Set<(e: unknown) => void>>();
  readonly classes = new Set<string>();
  captured: number | null = null;

  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners.get(type) ?? this.listeners.set(type, new Set()).get(type)!).add(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners.get(type)?.delete(fn);
  }
  addClass(c: string): void {
    this.classes.add(c);
  }
  removeClass(c: string): void {
    this.classes.delete(c);
  }
  setPointerCapture(id: number): void {
    this.captured = id;
  }
  /** Deliver an event to whatever is currently listening — the test's stand-in for the browser. */
  fire(type: string, event: Record<string, unknown>): void {
    for (const fn of this.listeners.get(type) ?? []) fn(event);
  }
  listenerCount(type: string): number {
    return this.listeners.get(type)?.size ?? 0;
  }
}

const evt = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  pointerId: 1,
  pointerType: "touch",
  button: 0,
  clientX: 0,
  clientY: 0,
  preventDefault() {},
  stopPropagation() {},
  ...over,
});

const bind = (): { el: FakeEl; moves: number[]; ended: boolean; started: boolean } => {
  const el = new FakeEl();
  const moves: number[] = [];
  const record = { started: false, ended: false };
  enableHandleDrag(el as unknown as HTMLElement, {
    onStart: () => (record.started = true),
    onMove: (e) => moves.push((e as unknown as { clientX: number }).clientX),
    onEnd: () => (record.ended = true),
  });
  return { el, moves, get started() { return record.started; }, get ended() { return record.ended; } };
};

describe("handle drag wiring (the thing HTML5 DnD could not do on touch)", () => {
  it("runs the full drag from a touch pointer — down, move, up", () => {
    const h = bind();
    h.el.fire("pointerdown", evt());
    expect(h.started).toBe(true);
    expect(h.el.captured).toBe(1); // a 1px divider must keep tracking outside its own bounds

    h.el.fire("pointermove", evt({ clientX: 30 }));
    h.el.fire("pointermove", evt({ clientX: 60 }));
    expect(h.moves).toEqual([30, 60]);

    h.el.fire("pointerup", evt({ clientX: 60 }));
    expect(h.ended).toBe(true);
  });

  it("marks the handle live during the drag and clears it after", () => {
    const h = bind();
    h.el.fire("pointerdown", evt());
    expect(h.el.classes.has("kvs-drag-live")).toBe(true);
    h.el.fire("pointerup", evt());
    expect(h.el.classes.has("kvs-drag-live")).toBe(false);
  });

  it("detaches its move/up listeners on release — no leak across drags", () => {
    const h = bind();
    h.el.fire("pointerdown", evt());
    expect(h.el.listenerCount("pointermove")).toBe(1);
    h.el.fire("pointerup", evt());
    expect(h.el.listenerCount("pointermove")).toBe(0);
    expect(h.el.listenerCount("pointerup")).toBe(0);
  });

  it("ignores a second pointer mid-drag (a second finger must not hijack the gesture)", () => {
    const h = bind();
    h.el.fire("pointerdown", evt({ pointerId: 1 }));
    h.el.fire("pointermove", evt({ pointerId: 2, clientX: 999 }));
    expect(h.moves).toEqual([]); // the intruder's coordinate was not recorded
    h.el.fire("pointermove", evt({ pointerId: 1, clientX: 15 }));
    expect(h.moves).toEqual([15]);
  });

  it("treats pointercancel as an end, not a hang", () => {
    const h = bind();
    h.el.fire("pointerdown", evt());
    h.el.fire("pointercancel", evt());
    expect(h.ended).toBe(true);
    expect(h.el.classes.has("kvs-drag-live")).toBe(false);
  });

  it("ignores a right-click (mouse, non-zero button) — that is for the context menu", () => {
    const h = bind();
    h.el.fire("pointerdown", evt({ pointerType: "mouse", button: 2 }));
    expect(h.started).toBe(false);
    expect(h.el.listenerCount("pointermove")).toBe(0);
  });
});
