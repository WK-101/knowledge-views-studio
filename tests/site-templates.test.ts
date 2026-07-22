import { describe, it, expect } from "vitest";
import {
  coerceTemplateRule,
  normalizeTemplateRules,
  matchTemplateRule,
  type TemplateRule,
} from "../shared/site-templates";

describe("coerceTemplateRule", () => {
  it("accepts a usable rule and trims the host", () => {
    expect(coerceTemplateRule({ host: " arxiv.org ", templateId: "starter-academic" })).toEqual({
      host: "arxiv.org",
      templateId: "starter-academic",
    });
  });

  it("rejects an empty host, an empty template, or a non-object", () => {
    expect(coerceTemplateRule({ host: "", templateId: "x" })).toBeNull();
    expect(coerceTemplateRule({ host: "arxiv.org", templateId: "" })).toBeNull();
    expect(coerceTemplateRule({ host: "   ", templateId: "x" })).toBeNull();
    expect(coerceTemplateRule(null)).toBeNull();
  });
});

describe("normalizeTemplateRules", () => {
  it("drops junk and de-duplicates by host (first writer wins)", () => {
    const out = normalizeTemplateRules([
      { host: "arxiv.org", templateId: "a" },
      { host: "www.arxiv.org", templateId: "b" }, // same host after normalisation
      { host: "", templateId: "c" },
      { host: "youtube.com", templateId: "d" },
    ]);
    expect(out.map((r) => [r.host, r.templateId])).toEqual([
      ["arxiv.org", "a"],
      ["youtube.com", "d"],
    ]);
  });

  it("returns [] for non-array input", () => {
    expect(normalizeTemplateRules(undefined)).toEqual([]);
  });
});

describe("matchTemplateRule", () => {
  const rules: TemplateRule[] = [
    { host: "google.com", templateId: "general" },
    { host: "scholar.google.com", templateId: "academic" },
    { host: "youtube.com", templateId: "video" },
  ];

  it("matches a host and its subdomains", () => {
    expect(matchTemplateRule(rules, "https://youtube.com/watch?v=1")?.templateId).toBe("video");
    expect(matchTemplateRule(rules, "https://m.youtube.com/watch")?.templateId).toBe("video");
  });

  it("the most specific host wins", () => {
    expect(matchTemplateRule(rules, "https://scholar.google.com/x")?.templateId).toBe("academic");
    expect(matchTemplateRule(rules, "https://www.google.com/search")?.templateId).toBe("general");
  });

  it("returns null when nothing matches or the url is unusable", () => {
    expect(matchTemplateRule(rules, "https://example.com")).toBeNull();
    expect(matchTemplateRule(rules, "")).toBeNull();
    expect(matchTemplateRule([], "https://youtube.com")).toBeNull();
  });
});
