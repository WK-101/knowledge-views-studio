import { describe, it, expect, vi } from "vitest";
import { bbtEndpointFromApiBase, fetchBbtCiteKey, fetchBbtCiteKeys, type JsonPoster } from "../src/services/zotero/bbt-citekey";

describe("bbtEndpointFromApiBase — derives BBT's JSON-RPC URL from the Zotero API base", () => {
  it("keeps the same origin and swaps the path", () => {
    expect(bbtEndpointFromApiBase("http://127.0.0.1:23119/api/users/0")).toBe("http://127.0.0.1:23119/better-bibtex/json-rpc");
  });
  it("handles a trailing slash and a non-default host", () => {
    expect(bbtEndpointFromApiBase("http://localhost:23119/api/users/0/")).toBe("http://localhost:23119/better-bibtex/json-rpc");
  });
  it("falls back gracefully for a non-URL base", () => {
    expect(bbtEndpointFromApiBase("127.0.0.1:23119/api/users/0")).toContain("/better-bibtex/json-rpc");
  });
});

describe("fetchBbtCiteKeys — reads exact keys from BBT's item.citationkey", () => {
  it("posts a JSON-RPC request and maps itemKey → citationKey", async () => {
    const poster: JsonPoster = vi.fn(async (_url, body) => {
      // Verify we send the documented shape: method item.citationkey, params [[keys]].
      expect(body).toMatchObject({ jsonrpc: "2.0", method: "item.citationkey", params: [["AAA", "BBB"]] });
      return { status: 200, json: { jsonrpc: "2.0", result: { AAA: "vaswani2017", BBB: "bengio2019" }, id: 1 } };
    });
    const map = await fetchBbtCiteKeys("http://x/better-bibtex/json-rpc", ["AAA", "BBB"], poster);
    expect(map.get("AAA")).toBe("vaswani2017");
    expect(map.get("BBB")).toBe("bengio2019");
  });

  it("omits items BBT has no key for (null in the result)", async () => {
    const poster: JsonPoster = vi.fn(async () => ({ status: 200, json: { result: { AAA: "smith2020", BBB: null } } }));
    const map = await fetchBbtCiteKeys("http://x", ["AAA", "BBB"], poster);
    expect(map.get("AAA")).toBe("smith2020");
    expect(map.has("BBB")).toBe(false);
  });

  it("returns an empty map when BBT is unreachable (status 0) — never throws", async () => {
    const poster: JsonPoster = vi.fn(async () => ({ status: 0, reason: "error: ECONNREFUSED" }));
    const map = await fetchBbtCiteKeys("http://x", ["AAA"], poster);
    expect(map.size).toBe(0);
  });

  it("returns an empty map for a non-200 response", async () => {
    const poster: JsonPoster = vi.fn(async () => ({ status: 500, json: {} }));
    expect((await fetchBbtCiteKeys("http://x", ["AAA"], poster)).size).toBe(0);
  });

  it("doesn't call BBT at all for an empty key list", async () => {
    const poster: JsonPoster = vi.fn(async () => ({ status: 200, json: { result: {} } }));
    await fetchBbtCiteKeys("http://x", [], poster);
    expect(poster).not.toHaveBeenCalled();
  });

  it("single-key convenience returns '' when absent", async () => {
    const poster: JsonPoster = vi.fn(async () => ({ status: 200, json: { result: {} } }));
    expect(await fetchBbtCiteKey("http://x", "AAA", poster)).toBe("");
  });
});
