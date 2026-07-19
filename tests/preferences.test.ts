import { describe, it, expect } from "vitest";
import { normalizePreferences, DEFAULT_PREFERENCES } from "../extension/src/lib/preferences";

describe("preferences · reading whatever is in storage", () => {
  it("returns the defaults for nothing at all", () => {
    expect(normalizePreferences(undefined)).toEqual(DEFAULT_PREFERENCES);
    expect(normalizePreferences(null)).toEqual(DEFAULT_PREFERENCES);
    expect(normalizePreferences("nonsense")).toEqual(DEFAULT_PREFERENCES);
  });

  it("keeps values it recognises", () => {
    const prefs = normalizePreferences({ popupSize: "large", alwaysTags: "web" });
    expect(prefs.popupSize).toBe("large");
    expect(prefs.alwaysTags).toBe("web");
  });

  it("falls back for a value outside what's allowed", () => {
    // A stale or hand-edited setting shouldn't put the interface into a state it has no styles for.
    expect(normalizePreferences({ popupSize: "enormous" }).popupSize).toBe("medium");
    expect(normalizePreferences({ searchMode: "telepathy" }).searchMode).toBe("keyword");
    expect(normalizePreferences({ selectionStyle: 7 }).selectionStyle).toBe("quote");
  });

  it("treats a missing boolean as its default rather than as false", () => {
    // includeContent defaults on; absence must not silently turn it off.
    expect(normalizePreferences({}).includeContent).toBe(true);
    expect(normalizePreferences({ includeContent: false }).includeContent).toBe(false);
    expect(normalizePreferences({}).recallBadge).toBe(false);
  });

  it("keeps well-formed rules and discards malformed ones", () => {
    const prefs = normalizePreferences({
      rules: [
        { domain: "arxiv.org", viewId: "papers" },
        { domain: 42, viewId: "x" },
        null,
        "nope",
        { viewId: "no-domain" },
      ],
    });
    expect(prefs.rules).toHaveLength(1);
    expect(prefs.rules[0]?.domain).toBe("arxiv.org");
  });

  it("copes with rules being something other than a list", () => {
    expect(normalizePreferences({ rules: "not a list" }).rules).toEqual([]);
  });

  it("produces a complete set whatever it was given", () => {
    const prefs = normalizePreferences({ popupSize: "small" });
    for (const key of Object.keys(DEFAULT_PREFERENCES)) {
      expect(prefs).toHaveProperty(key);
    }
  });
});
