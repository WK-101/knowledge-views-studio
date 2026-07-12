import { describe, it, expect } from "vitest";
import { extractDois } from "../src/workspace/add-by-doi-modal";

describe("DOI extraction", () => {
  it("pulls DOIs from lines and doi.org links, de-duplicated", () => {
    const text = `10.5555/3295222
https://doi.org/10.18653/v1/N19-1423
doi:10.5555/3295222
random text 10.1000/xyz123.`;
    expect(extractDois(text)).toEqual(["10.5555/3295222", "10.18653/v1/N19-1423", "10.1000/xyz123"]);
  });

  it("returns empty for text without DOIs", () => {
    expect(extractDois("no dois here")).toEqual([]);
  });
});
