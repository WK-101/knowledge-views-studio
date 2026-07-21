import { describe, it, expect } from "vitest";
import {
  CUSTOM_ENGINE_PREFIX,
  DEFAULT_SEARCH_TARGETS,
  WEB_ENGINES,
  displayHits,
  isUsableTemplate,
  normalizeSearchTargets,
  resolveEngine,
  searchUrl,
  wireSearchMode,
} from "../extension/src/lib/search-targets";

const ids = (list: readonly { id: string }[]): string[] => list.map((e) => e.id);

describe("search targets · where the toolbar's Search action can send a selection", () => {
  it("defaults to the vault plus the catalogue engines at their default states", () => {
    expect(DEFAULT_SEARCH_TARGETS.vault).toBe(true);
    expect(ids(DEFAULT_SEARCH_TARGETS.engines)).toEqual(ids(WEB_ENGINES));
    for (const engine of WEB_ENGINES) {
      expect(DEFAULT_SEARCH_TARGETS.engines.find((e) => e.id === engine.id)!.enabled).toBe(engine.defaultOn);
    }
  });

  it("gives the full default for nothing, or nonsense, in storage", () => {
    expect(normalizeSearchTargets(undefined)).toEqual(DEFAULT_SEARCH_TARGETS);
    expect(normalizeSearchTargets(null)).toEqual(DEFAULT_SEARCH_TARGETS);
    expect(normalizeSearchTargets("nope")).toEqual(DEFAULT_SEARCH_TARGETS);
    expect(normalizeSearchTargets(42)).toEqual(DEFAULT_SEARCH_TARGETS);
  });

  it("keeps the stored order and on/off, drops duplicates, appends missing built-ins at their defaults", () => {
    const out = normalizeSearchTargets({
      vault: false,
      engines: [
        { id: "wikipedia", enabled: false },
        { id: "google", enabled: true },
        { id: "google", enabled: false }, // duplicate — ignored
      ],
    });
    expect(out.vault).toBe(false);
    expect(ids(out.engines).slice(0, 2)).toEqual(["wikipedia", "google"]);
    expect(out.engines.find((e) => e.id === "wikipedia")!.enabled).toBe(false);
    expect(out.engines.find((e) => e.id === "google")!.enabled).toBe(true);
    // Every built-in present exactly once; the ones never stored arrive at their catalogue defaults.
    expect([...ids(out.engines)].sort()).toEqual([...ids(WEB_ENGINES)].sort());
    expect(out.engines.find((e) => e.id === "bing")!.enabled).toBe(false);
  });

  it("keeps a valid custom engine and drops a broken one", () => {
    const good = { id: `${CUSTOM_ENGINE_PREFIX}abc`, enabled: true, label: "PubMed", template: "https://pubmed.ncbi.nlm.nih.gov/?term=%s" };
    const out = normalizeSearchTargets({
      engines: [
        good,
        { id: `${CUSTOM_ENGINE_PREFIX}bad1`, enabled: true, label: "No slot", template: "https://example.com/" }, // no %s
        { id: `${CUSTOM_ENGINE_PREFIX}bad2`, enabled: true, label: "Not web", template: "ftp://example.com/%s" },
        { id: `${CUSTOM_ENGINE_PREFIX}bad3`, enabled: true, template: "https://example.com/%s" }, // no label
        { id: "made-up", enabled: true }, // unknown, not custom — dropped
      ],
    });
    expect(out.engines.filter((e) => e.id.startsWith(CUSTOM_ENGINE_PREFIX))).toEqual([good]);
    expect(ids(out.engines)).not.toContain("made-up");
  });

  it("resolves built-ins from the catalogue, ignoring any stored label or template", () => {
    const resolved = resolveEngine({ id: "google", enabled: true, label: "Evil", template: "https://evil.example/%s" });
    expect(resolved).toEqual({ id: "google", label: "Google", template: "https://www.google.com/search?q=%s" });
  });

  it("resolves a custom engine from its own label and template, and refuses an unusable one", () => {
    expect(resolveEngine({ id: `${CUSTOM_ENGINE_PREFIX}x`, enabled: true, label: " PubMed ", template: "https://pubmed.ncbi.nlm.nih.gov/?term=%s" }))
      .toEqual({ id: `${CUSTOM_ENGINE_PREFIX}x`, label: "PubMed", template: "https://pubmed.ncbi.nlm.nih.gov/?term=%s" });
    expect(resolveEngine({ id: `${CUSTOM_ENGINE_PREFIX}x`, enabled: true, label: "", template: "https://a.b/%s" })).toBeNull();
    expect(resolveEngine({ id: "unknown", enabled: true })).toBeNull();
  });

  it("judges templates: web URL with a %s slot, or nothing", () => {
    expect(isUsableTemplate("https://example.com/?q=%s")).toBe(true);
    expect(isUsableTemplate("http://example.com/%s")).toBe(true);
    expect(isUsableTemplate("  https://example.com/?q=%s  ")).toBe(true);
    expect(isUsableTemplate("https://example.com/?q=")).toBe(false);
    expect(isUsableTemplate("javascript:alert(1)//%s")).toBe(false);
    expect(isUsableTemplate("example.com/?q=%s")).toBe(false);
  });

  it("builds a search URL: text collapsed, encoded, filling every %s", () => {
    expect(searchUrl("https://e.com/?q=%s", "  spaced   out\nquery ")).toBe("https://e.com/?q=spaced%20out%20query");
    expect(searchUrl("https://e.com/%s/find?q=%s", "a&b")).toBe("https://e.com/a%26b/find?q=a%26b");
    expect(searchUrl("https://e.com/?q=%s", 'he said "no"')).toBe("https://e.com/?q=he%20said%20%22no%22");
  });

  it("maps the stored search-mode preference to the wire mode", () => {
    expect(wireSearchMode("keyword")).toBe("keyword");
    expect(wireSearchMode("meaning")).toBe("semantic");
    expect(wireSearchMode("semantic")).toBe("semantic");
    expect(wireSearchMode("ask")).toBe("ask");
    expect(wireSearchMode("telepathy")).toBe("keyword");
  });

  it("turns hits into display rows: own URL first, then an obsidian:// link, else unlinked", () => {
    const hits = [
      { id: "1", title: "A link", source: "link", url: "https://kept.example/", score: 1 },
      { id: "2", title: "A note", source: "note", path: "Notes/Deep work.md", snippet: "the snippet", score: 1 },
      { id: "3", title: "Nowhere", source: "row", location: "Row 4", score: 1 },
    ];
    const out = displayHits(hits, "My Vault");
    expect(out[0]!.href).toBe("https://kept.example/");
    expect(out[1]!.href).toBe("obsidian://open?vault=My%20Vault&file=Notes%2FDeep%20work.md");
    expect(out[1]!.snippet).toBe("the snippet");
    expect(out[2]!.href).toBe("");
    expect(out[2]!.location).toBe("Row 4");
  });

  it("leaves file hits unlinked when the vault's name is unknown, rather than linking to nowhere", () => {
    const out = displayHits([{ id: "1", title: "A note", source: "note", path: "n.md", score: 1 }], "");
    expect(out[0]!.href).toBe("");
  });
});
