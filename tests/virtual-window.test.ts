import { describe, it, expect } from "vitest";
import {
  buildPrefix,
  totalHeight,
  findRowAt,
  computeWindow,
  anchorShift,
} from "../src/views/table/virtual-window";

describe("buildPrefix", () => {
  it("accumulates offsets and total", () => {
    const p = buildPrefix([10, 20, 30]);
    expect(p).toEqual([0, 10, 30, 60]);
    expect(totalHeight(p)).toBe(60);
  });
  it("treats missing/negative heights as zero", () => {
    expect(buildPrefix([10, -5, 5])).toEqual([0, 10, 10, 15]);
    expect(buildPrefix([])).toEqual([0]);
    expect(totalHeight([0])).toBe(0);
  });
});

describe("findRowAt", () => {
  const p = buildPrefix([10, 20, 30]); // [0,10,30,60]
  it("maps an offset to the row that contains it", () => {
    expect(findRowAt(p, 0)).toBe(0);
    expect(findRowAt(p, 9)).toBe(0);
    expect(findRowAt(p, 10)).toBe(1);
    expect(findRowAt(p, 29)).toBe(1);
    expect(findRowAt(p, 30)).toBe(2);
  });
  it("clamps below zero and beyond the end", () => {
    expect(findRowAt(p, -100)).toBe(0);
    expect(findRowAt(p, 10_000)).toBe(2); // last row, never the total sentinel
  });
});

describe("computeWindow", () => {
  const p = buildPrefix([10, 10, 10, 10, 10]); // [0,10,20,30,40,50]
  it("covers the viewport with no overscan", () => {
    expect(computeWindow(p, 20, 20, 0)).toEqual({ start: 2, end: 4 });
  });
  it("pads by overscan on both sides and clamps to bounds", () => {
    expect(computeWindow(p, 20, 20, 1)).toEqual({ start: 1, end: 5 });
    expect(computeWindow(p, 0, 20, 2)).toEqual({ start: 0, end: 4 });
  });
  it("returns an empty window with no rows", () => {
    expect(computeWindow([0], 0, 100, 4)).toEqual({ start: 0, end: 0 });
  });
});

describe("anchorShift", () => {
  it("reports the offset delta of the anchor row", () => {
    const before = buildPrefix([40, 40, 40, 40]); // [0,40,80,120,160]
    const after = buildPrefix([80, 90, 40, 40]); // row 0,1 grew
    // anchor row 2 moved from offset 80 to 170 -> shift 90
    expect(anchorShift(before, after, 2)).toBe(90);
    // anchor row 0 never shifts
    expect(anchorShift(before, after, 0)).toBe(0);
  });
});
