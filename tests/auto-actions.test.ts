import { describe, it, expect } from "vitest";
import {
  coerceSiteAutoAction,
  matchAutoAction,
  normalizeSiteAutoActions,
  ruleHasEffect,
  summarizeAutoAction,
  type SiteAutoAction,
} from "../extension/src/lib/auto-actions";

describe("per-site auto-actions", () => {
  it("keeps a highlight rule and carries only its parameters", () => {
    const rule = coerceSiteAutoAction({
      domain: "example.com",
      onSelect: "highlight",
      color: "green",
      style: "underline",
      intensity: "strong",
      copyFormat: "blockquote", // irrelevant to highlight — dropped
    });
    expect(rule).not.toBeNull();
    expect(rule!.onSelect).toBe("highlight");
    expect(rule!.color).toBe("green");
    expect(rule!.style).toBe("underline");
    expect(rule!.intensity).toBe("strong");
    expect(rule!.copyFormat).toBeUndefined();
  });

  it("keeps a copy rule's format and nothing highlight-only", () => {
    const rule = coerceSiteAutoAction({ domain: "example.com", onSelect: "copy", copyFormat: "markdown-link", color: "red" });
    expect(rule!.copyFormat).toBe("markdown-link");
    expect(rule!.color).toBeUndefined();
    expect(rule!.style).toBeUndefined();
  });

  it("keeps a sticky rule's colour but not style/intensity", () => {
    const rule = coerceSiteAutoAction({ domain: "example.com", onSelect: "sticky", color: "blue" });
    expect(rule!.color).toBe("blue");
    expect(rule!.style).toBeUndefined();
    expect(rule!.intensity).toBeUndefined();
  });

  it("defaults an unknown colour to yellow when the kind needs one", () => {
    expect(coerceSiteAutoAction({ domain: "e.com", onSelect: "highlight", color: "chartreuse" })!.color).toBe("yellow");
  });

  it("only carries alsoShowToolbar when there is an on-selection action", () => {
    expect(coerceSiteAutoAction({ domain: "e.com", onSelect: "highlight", alsoShowToolbar: true })!.alsoShowToolbar).toBe(true);
    // With onSelect "none" the flag is meaningless and dropped (the rule survives on its page-load effect).
    const pageOnly = coerceSiteAutoAction({ domain: "e.com", onSelect: "none", alsoShowToolbar: true, openSidebar: true });
    expect(pageOnly!.alsoShowToolbar).toBeUndefined();
    expect(pageOnly!.openSidebar).toBe(true);
  });

  it("keeps a page-load-only rule (sidebar and/or launcher) even with onSelect none", () => {
    const rule = coerceSiteAutoAction({ domain: "e.com", onSelect: "none", showStickyLauncher: true });
    expect(rule).not.toBeNull();
    expect(rule!.onSelect).toBe("none");
    expect(rule!.showStickyLauncher).toBe(true);
  });

  it("drops a rule with no host, or with no effect at all", () => {
    expect(coerceSiteAutoAction({ domain: "", onSelect: "highlight" })).toBeNull();
    expect(coerceSiteAutoAction({ domain: "example.com", onSelect: "none" })).toBeNull();
    expect(coerceSiteAutoAction(null)).toBeNull();
    expect(coerceSiteAutoAction(42)).toBeNull();
  });

  it("ruleHasEffect is true for an on-select action or a page-load toggle, false otherwise", () => {
    expect(ruleHasEffect({ domain: "e.com", onSelect: "copy" })).toBe(true);
    expect(ruleHasEffect({ domain: "e.com", onSelect: "none", openSidebar: true })).toBe(true);
    expect(ruleHasEffect({ domain: "e.com", onSelect: "none" })).toBe(false);
  });

  it("normalizes a list, dropping junk and de-duplicating by host (first wins)", () => {
    const out = normalizeSiteAutoActions([
      { domain: "example.com", onSelect: "highlight", color: "green" },
      { domain: "www.example.com", onSelect: "copy" }, // same host after hostOf — dropped
      { domain: "", onSelect: "highlight" }, // no host — dropped
      "garbage",
      { domain: "other.com", onSelect: "none", openSidebar: true },
    ]);
    expect(out.map((r) => r.domain)).toEqual(["example.com", "other.com"]);
    expect(out[0]!.onSelect).toBe("highlight");
  });

  it("matches most-specific-wins, subdomains included", () => {
    const rules: SiteAutoAction[] = [
      { domain: "example.com", onSelect: "highlight", color: "yellow" },
      { domain: "docs.example.com", onSelect: "copy", copyFormat: "quote" },
    ];
    expect(matchAutoAction(rules, "https://docs.example.com/page")!.onSelect).toBe("copy");
    expect(matchAutoAction(rules, "https://blog.example.com/x")!.onSelect).toBe("highlight");
    expect(matchAutoAction(rules, "https://elsewhere.org/")).toBeNull();
  });

  it("summarizes a rule readably", () => {
    expect(
      summarizeAutoAction({ domain: "e.com", onSelect: "highlight", color: "green", style: "highlight", intensity: "medium", alsoShowToolbar: true }),
    ).toBe("highlight in green · then show the toolbar");
    expect(summarizeAutoAction({ domain: "e.com", onSelect: "copy", copyFormat: "markdown-link" })).toBe("copy as markdown link");
    expect(summarizeAutoAction({ domain: "e.com", onSelect: "none", openSidebar: true, showStickyLauncher: true })).toBe(
      "open the sidebar · show the sticky launcher",
    );
  });
});
