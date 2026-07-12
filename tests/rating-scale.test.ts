import { describe, it, expect } from "vitest";
import { RATING_MAX, toRating } from "../src/domain/columns/types/rating";

describe("rating scale", () => {
  it("is out of 10", () => {
    expect(RATING_MAX).toBe(10);
  });
  it("parses numeric and star ratings up to the scale", () => {
    expect(toRating("8")).toBe(8);
    expect(toRating("★★★★★★★")).toBe(7);
    expect(toRating("")).toBe(0);
  });
});
