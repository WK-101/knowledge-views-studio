import { describe, it, expect } from "vitest";
import { colorForKey, dotTop, keyForColor } from "../extension/src/lib/minimap";
import { HIGHLIGHT_COLORS } from "../shared/annotations";

describe("minimap · dot positioning and colour-key mapping", () => {
  it("places a dot at its fraction of the rail", () => {
    expect(dotTop(0, 1000, 600)).toBe(0);
    expect(dotTop(500, 1000, 600)).toBe(300);
    expect(dotTop(1000, 1000, 600)).toBe(600);
    expect(dotTop(250, 1000, 800)).toBe(200);
  });

  it("clamps out-of-range positions rather than overflowing the rail", () => {
    expect(dotTop(-50, 1000, 600)).toBe(0);
    expect(dotTop(5000, 1000, 600)).toBe(600);
  });

  it("puts everything at the top when there's nothing to scroll", () => {
    expect(dotTop(0, 0, 600)).toBe(0);
    expect(dotTop(100, -1, 600)).toBe(0);
  });

  it("maps number keys 1-8 to the palette in order, and nothing else", () => {
    expect(colorForKey("1")).toBe(HIGHLIGHT_COLORS[0]);
    expect(colorForKey("3")).toBe("green");
    expect(colorForKey("8")).toBe(HIGHLIGHT_COLORS[7]);
    expect(colorForKey("0")).toBeNull();
    expect(colorForKey("9")).toBeNull();
    expect(colorForKey("")).toBeNull();
    expect(colorForKey("a")).toBeNull();
    expect(colorForKey("2.5")).toBeNull();
  });

  it("gives the number-key label for a colour, round-tripping colorForKey", () => {
    expect(keyForColor("yellow")).toBe("1");
    expect(keyForColor("green")).toBe("3");
    for (const color of HIGHLIGHT_COLORS) {
      expect(colorForKey(keyForColor(color))).toBe(color);
    }
  });
});
