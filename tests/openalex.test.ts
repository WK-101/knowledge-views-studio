import { describe, it, expect, vi } from "vitest";
import { shortOpenAlexId, parseReferencedIds, parseWorksList, fetchReferencedIds, resolveOpenAlexIds } from "../src/services/import/openalex";

describe("OpenAlex", () => {
  it("extracts short ids from urls", () => {
    expect(shortOpenAlexId("https://openalex.org/W2741809807")).toBe("W2741809807");
    expect(shortOpenAlexId("W123")).toBe("W123");
    expect(shortOpenAlexId("nope")).toBe("");
  });

  it("parses referenced_works into a set of ids", () => {
    const ids = parseReferencedIds({ referenced_works: ["https://openalex.org/W1", "https://openalex.org/W2"] });
    expect([...ids].sort()).toEqual(["W1", "W2"]);
  });

  it("parses a works list into id/doi pairs (normalising DOIs)", () => {
    const pairs = parseWorksList({ results: [{ id: "https://openalex.org/W9", doi: "https://doi.org/10.1/X" }, { id: "https://openalex.org/W8", doi: null }] });
    expect(pairs).toEqual([{ id: "W9", doi: "10.1/x" }]);
  });

  it("fetchReferencedIds returns the referenced ids via the fetcher", async () => {
    const fetcher = vi.fn(async () => ({ status: 200, json: { referenced_works: ["https://openalex.org/W5"] } }));
    const ids = await fetchReferencedIds("10.1/a", fetcher);
    expect([...ids]).toEqual(["W5"]);
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("works/doi:10.1%2Fa"));
  });

  it("resolveOpenAlexIds maps DOIs to ids", async () => {
    const fetcher = vi.fn(async () => ({ status: 200, json: { results: [{ id: "https://openalex.org/W1", doi: "https://doi.org/10.1/a" }] } }));
    const map = await resolveOpenAlexIds(["10.1/A"], fetcher);
    expect(map.get("10.1/a")).toBe("W1");
  });
});
