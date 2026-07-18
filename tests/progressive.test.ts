import { describe, it, expect } from "vitest";
import { shouldChunk, nextChunkEnd, DEFAULT_CHUNK } from "../src/views/progressive";

describe("progressive rendering — chunk scheduling", () => {
  it("only chunks when the list is longer than one chunk", () => {
    expect(shouldChunk(10, 48)).toBe(false); // short list: draw it all, no sentinel
    expect(shouldChunk(48, 48)).toBe(false); // exactly one chunk is still one paint
    expect(shouldChunk(49, 48)).toBe(true);
  });

  it("treats a non-positive chunk size as 'no chunking'", () => {
    expect(shouldChunk(1000, 0)).toBe(false);
    expect(nextChunkEnd(0, 1000, 0)).toBe(1000);
  });

  it("advances by one chunk at a time and never overshoots the total", () => {
    expect(nextChunkEnd(0, 200, 48)).toBe(48);
    expect(nextChunkEnd(48, 200, 48)).toBe(96);
    expect(nextChunkEnd(192, 200, 48)).toBe(200); // clamped to the end
    expect(nextChunkEnd(200, 200, 48)).toBe(200); // already complete
  });

  it("walks a large list to completion in whole chunks", () => {
    const total = 1000;
    let rendered = 0;
    let rounds = 0;
    while (rendered < total) {
      const next = nextChunkEnd(rendered, total, DEFAULT_CHUNK);
      expect(next).toBeGreaterThan(rendered); // always progresses — no infinite loop
      rendered = next;
      rounds++;
      if (rounds > 100) break; // guard: would indicate a stall
    }
    expect(rendered).toBe(total);
    expect(rounds).toBe(Math.ceil(total / DEFAULT_CHUNK));
  });

  it("is safe against a negative rendered count", () => {
    expect(nextChunkEnd(-5, 100, 10)).toBe(10);
  });

  it("uses a first chunk large enough to fill a tall pane", () => {
    // A regression guard on the constant: too small and the first paint looks empty on a big screen.
    expect(DEFAULT_CHUNK).toBeGreaterThanOrEqual(24);
  });
});
