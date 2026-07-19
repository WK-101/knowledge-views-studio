import { describe, it, expect } from "vitest";
import { matchRule, ruleMatches, hostOf, isUsableRule, mergeTags, type DomainRule } from "../shared/rules";

const rule = (domain: string, viewId = "v", patch: Partial<DomainRule> = {}): DomainRule => ({
  domain,
  viewId,
  ...patch,
});

describe("rules · hostOf", () => {
  it("takes the host from a full url", () => {
    expect(hostOf("https://www.arxiv.org/abs/1234")).toBe("arxiv.org");
  });

  it("accepts a bare host, which is what people type into a rule", () => {
    expect(hostOf("arxiv.org")).toBe("arxiv.org");
    expect(hostOf("www.arxiv.org")).toBe("arxiv.org");
  });

  it("ignores scheme, path and port", () => {
    expect(hostOf("http://example.com:8080/some/path")).toBe("example.com");
  });

  it("returns nothing for nothing", () => {
    expect(hostOf("")).toBe("");
    expect(hostOf("   ")).toBe("");
  });
});

describe("rules · matching a host", () => {
  it("matches the domain itself", () => {
    expect(ruleMatches(rule("arxiv.org"), "arxiv.org")).toBe(true);
  });

  it("matches a subdomain", () => {
    expect(ruleMatches(rule("google.com"), "scholar.google.com")).toBe(true);
  });

  it("doesn't match a domain that merely ends the same way", () => {
    // notarxiv.org must not be caught by a rule for arxiv.org.
    expect(ruleMatches(rule("arxiv.org"), "notarxiv.org")).toBe(false);
  });

  it("doesn't match an unrelated host", () => {
    expect(ruleMatches(rule("arxiv.org"), "example.com")).toBe(false);
  });
});

describe("rules · choosing which applies", () => {
  const rules = [
    rule("google.com", "general"),
    rule("scholar.google.com", "papers"),
    rule("arxiv.org", "papers", { shape: "row" }),
  ];

  it("applies the rule for the site", () => {
    expect(matchRule(rules, "https://arxiv.org/abs/2401.1")?.viewId).toBe("papers");
  });

  it("prefers the more specific rule over the domain it sits under", () => {
    expect(matchRule(rules, "https://scholar.google.com/x")?.viewId).toBe("papers");
    expect(matchRule(rules, "https://google.com/search")?.viewId).toBe("general");
  });

  it("doesn't depend on the order rules happen to be listed in", () => {
    const reversed = [...rules].reverse();
    expect(matchRule(reversed, "https://scholar.google.com/x")?.viewId).toBe("papers");
  });

  it("returns nothing when no rule covers the page", () => {
    expect(matchRule(rules, "https://example.com/x")).toBeNull();
  });

  it("returns nothing for an unusable url", () => {
    expect(matchRule(rules, "")).toBeNull();
  });

  it("carries the shape the rule asked for", () => {
    expect(matchRule(rules, "https://arxiv.org/abs/1")?.shape).toBe("row");
  });
});

describe("rules · usability", () => {
  it("accepts a rule that names both a site and a view", () => {
    expect(isUsableRule({ domain: "arxiv.org", viewId: "v" })).toBe(true);
  });

  it("rejects one that would never fire", () => {
    // Otherwise the list fills up with entries that quietly do nothing.
    expect(isUsableRule({ domain: "", viewId: "v" })).toBe(false);
    expect(isUsableRule({ domain: "arxiv.org", viewId: "  " })).toBe(false);
  });
});

describe("rules · merging tags", () => {
  it("adds the rule's tags to whatever the page supplied", () => {
    expect(mergeTags("ai", "papers, unread")).toBe("ai, papers, unread");
  });

  it("doesn't repeat a tag that's already there", () => {
    expect(mergeTags("papers", "papers, unread")).toBe("papers, unread");
  });

  it("ignores case when deciding what's a repeat", () => {
    expect(mergeTags("Papers", "papers")).toBe("Papers");
  });

  it("copes when either side is empty", () => {
    expect(mergeTags("", "unread")).toBe("unread");
    expect(mergeTags("ai", undefined)).toBe("ai");
    expect(mergeTags("", undefined)).toBe("");
  });
});
