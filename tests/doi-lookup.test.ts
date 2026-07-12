import { describe, it, expect, vi } from "vitest";
import { parseCrossref, normalizeDoi, fetchDoiMetadata, fetchDoiMetadataResult } from "../src/services/import/doi-lookup";

const crossref = {
  message: {
    DOI: "10.5555/3295222",
    author: [
      { given: "Ashish", family: "Vaswani" },
      { given: "Noam", family: "Shazeer" },
    ],
    title: ["Attention Is All You Need"],
    "container-title": ["Advances in Neural Information Processing Systems"],
    issued: { "date-parts": [[2017, 12, 4]] },
  },
};

describe("DOI lookup", () => {
  it("normalises resolver prefixes to a bare DOI", () => {
    expect(normalizeDoi("https://doi.org/10.1/x")).toBe("10.1/x");
    expect(normalizeDoi("doi: 10.1/x")).toBe("10.1/x");
  });

  it("parses Crossref metadata (authors, title, venue, year)", () => {
    const meta = parseCrossref(crossref);
    expect(meta).not.toBeNull();
    expect(meta!.authors).toBe("Vaswani, Ashish; Shazeer, Noam");
    expect(meta!.title).toBe("Attention Is All You Need");
    expect(meta!.venue).toBe("Advances in Neural Information Processing Systems");
    expect(meta!.year).toBe("2017");
  });

  it("returns null for an empty or unusable payload", () => {
    expect(parseCrossref({})).toBeNull();
    expect(parseCrossref({ message: {} })).toBeNull();
  });

  it("fetches via the injected fetcher and parses the result", async () => {
    const fetcher = vi.fn(async () => ({ status: 200, json: crossref }));
    const meta = await fetchDoiMetadata("10.5555/3295222", fetcher);
    expect(fetcher).toHaveBeenCalledWith("https://api.crossref.org/works/10.5555%2F3295222");
    expect(meta!.title).toBe("Attention Is All You Need");
  });

  it("returns null on a non-200 response", async () => {
    const meta = await fetchDoiMetadata("10.1/x", async () => ({ status: 404 }));
    expect(meta).toBeNull();
  });
});

describe("fetchDoiMetadataResult diagnostics", () => {
  it("rejects an obviously invalid DOI without a network call", async () => {
    const fetcher = vi.fn(async () => ({ status: 200 }));
    const res = await fetchDoiMetadataResult("not-a-doi", fetcher);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toMatch(/DOI/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("explains 404, 429, and network errors distinctly", async () => {
    const notFound = await fetchDoiMetadataResult("10.1/x", async () => ({ status: 404 }));
    expect(notFound.ok).toBe(false);
    if (!notFound.ok) expect(notFound.reason).toMatch(/no record/i);

    const limited = await fetchDoiMetadataResult("10.1/x", async () => ({ status: 429 }));
    if (!limited.ok) expect(limited.reason).toMatch(/rate-limit/i);

    const netErr = await fetchDoiMetadataResult("10.1/x", async () => {
      throw new Error("offline");
    });
    if (!netErr.ok) expect(netErr.reason).toMatch(/reach Crossref/i);
  });

  it("returns metadata on success", async () => {
    const res = await fetchDoiMetadataResult("10.5555/3295222", async () => ({ status: 200, json: crossref }));
    expect(res.ok).toBe(true);
    if (res.ok) expect(res.meta.title.length).toBeGreaterThan(0);
  });
});
