import { describe, it, expect } from "vitest";
import { looksLikeResult, candidateUrls, isSearchHost, SEARCH_HOSTS } from "../shared/serp";

describe("serp · which pages we mark on", () => {
  it("recognises the search engines it covers", () => {
    for (const host of ["google.com", "www.google.com", "duckduckgo.com", "arxiv.org"]) {
      expect(isSearchHost(host)).toBe(true);
    }
  });

  it("ignores everywhere else", () => {
    for (const host of ["example.com", "news.ycombinator.com", "reddit.com"]) {
      expect(isSearchHost(host)).toBe(false);
    }
  });

  it("covers both general and academic search", () => {
    expect(SEARCH_HOSTS).toContain("scholar.google.com");
    expect(SEARCH_HOSTS).toContain("pubmed.ncbi.nlm.nih.gov");
  });
});

describe("serp · telling results from furniture", () => {
  it("accepts an ordinary outbound result", () => {
    expect(looksLikeResult("https://example.com/an-article", "google.com")).toBe(true);
  });

  it("rejects links back into the search engine, which are navigation", () => {
    // A "you've read this" mark on a footer link would teach someone to distrust every mark on the page.
    expect(looksLikeResult("https://google.com/preferences", "google.com")).toBe(false);
    expect(looksLikeResult("https://www.youtube.com/", "google.com")).toBe(false);
  });

  it("rejects a bare domain, which is rarely the thing someone saved", () => {
    expect(looksLikeResult("https://example.com", "google.com")).toBe(false);
    expect(looksLikeResult("https://example.com/", "google.com")).toBe(false);
  });

  it("rejects anything that isn't a web link", () => {
    for (const href of ["mailto:a@b.com", "javascript:void(0)", "#section", "/relative", ""]) {
      expect(looksLikeResult(href, "google.com")).toBe(false);
    }
  });

  it("on academic sites, keeps that site's own pages — they ARE the results", () => {
    expect(looksLikeResult("https://arxiv.org/abs/2401.00001", "arxiv.org")).toBe(true);
    expect(looksLikeResult("https://pubmed.ncbi.nlm.nih.gov/12345678/", "pubmed.ncbi.nlm.nih.gov")).toBe(true);
  });

  it("on academic sites, ignores links away to somewhere else", () => {
    expect(looksLikeResult("https://example.com/x", "arxiv.org")).toBe(false);
  });
});

describe("serp · candidate collection", () => {
  const hrefs = [
    "https://example.com/one",
    "https://example.com/two",
    "https://google.com/settings",
    "mailto:x@y.com",
  ];

  it("keeps the results and drops the rest", () => {
    expect(candidateUrls(hrefs, "google.com")).toEqual(["https://example.com/one", "https://example.com/two"]);
  });

  it("asks about a page once, however many times it's linked", () => {
    const repeated = ["https://example.com/a", "https://www.example.com/a/", "https://example.com/a?utm_source=x"];
    expect(candidateUrls(repeated, "google.com")).toHaveLength(1);
  });

  it("caps how many it asks about, so a dense page can't flood the vault", () => {
    const many = Array.from({ length: 200 }, (_, i) => `https://example.com/p${String(i)}`);
    expect(candidateUrls(many, "google.com", 60)).toHaveLength(60);
  });

  it("returns nothing when a page has no results at all", () => {
    expect(candidateUrls(["https://google.com/x", "#a"], "google.com")).toEqual([]);
  });

  it("hands back the original href, since that's what has to be matched in the page", () => {
    const out = candidateUrls(["https://www.example.com/a/?utm_source=x"], "google.com");
    expect(out[0]).toBe("https://www.example.com/a/?utm_source=x");
  });
});
