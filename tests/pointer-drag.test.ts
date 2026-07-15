import { describe, expect, it } from "vitest";
import {
  DEFAULT_THRESHOLDS,
  pressCancel,
  pressHold,
  pressMove,
  pressStart,
  type DragState,
} from "../src/util/pointer-drag";

const mouse = (x = 0, y = 0): DragState => pressStart(x, y, "mouse");
const finger = (x = 0, y = 0): DragState => pressStart(x, y, "touch");

describe("drag activation", () => {
  it("a press is not yet a drag, on any device", () => {
    expect(mouse().phase).toBe("pending");
    expect(finger().phase).toBe("pending");
  });

  describe("mouse: movement is the intent to drag", () => {
    it("stays a click while inside the slop radius", () => {
      expect(pressMove(mouse(), 3, 0).phase).toBe("pending");
    });

    it("becomes a drag once the pointer clears it", () => {
      expect(pressMove(mouse(), 20, 0).phase).toBe("active");
    });

    it("measures the slop radially, not per axis (a diagonal nudge is still a nudge)", () => {
      // 3,3 is 4.24px away: outside a 4px radius, though neither axis alone exceeds it.
      expect(pressMove(mouse(), 3, 3).phase).toBe("active");
      expect(pressMove(mouse(), 2, 2).phase).toBe("pending"); // 2.83px
    });

    it("never needs a hold — that is a touch idea", () => {
      expect(pressHold(mouse()).phase).toBe("pending");
    });
  });

  describe("touch: movement is the intent to scroll", () => {
    it("abandons the press when the finger travels — the user is scrolling the board", () => {
      expect(pressMove(finger(), 0, 40).phase).toBe("cancelled");
    });

    it("tolerates the wobble of a finger trying to hold still", () => {
      expect(pressMove(finger(), 5, 5).phase).toBe("pending"); // 7.07px, inside the 10px slop
    });

    it("becomes a drag when the press is held instead", () => {
      expect(pressHold(finger()).phase).toBe("active");
    });

    it("a hold that already lost to a scroll cannot resurrect the drag", () => {
      // The timer is not cancellable everywhere; the machine must be the thing that refuses.
      const scrolled = pressMove(finger(), 0, 40);
      expect(pressHold(scrolled).phase).toBe("cancelled");
    });

    it("once dragging, moving is dragging — the scroll rule no longer applies", () => {
      const dragging = pressHold(finger());
      const moved = pressMove(dragging, 0, 400);
      expect(moved.phase).toBe("active");
    });
  });

  it("a cancelled press stays cancelled, whatever happens next", () => {
    const dead = pressCancel(mouse());
    expect(pressMove(dead, 500, 500).phase).toBe("cancelled");
    expect(pressHold(dead).phase).toBe("cancelled");
  });

  it("honours custom thresholds rather than hard-coding its own", () => {
    const strict = { ...DEFAULT_THRESHOLDS, mouseSlop: 100 };
    expect(pressMove(mouse(), 50, 0, strict).phase).toBe("pending");
    expect(pressMove(mouse(), 50, 0).phase).toBe("active");
  });

  it("thresholds are measured from where the press began, not from the origin", () => {
    const away = mouse(500, 500);
    expect(pressMove(away, 501, 500).phase).toBe("pending");
    expect(pressMove(away, 520, 500).phase).toBe("active");
  });
});
