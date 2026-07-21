import { describe, it, expect } from "vitest";
import {
  annotationId,
  annotationCellText,
  annotationNoteBlock,
  coerceAnnotation,
  withAnnotation,
  withoutAnnotation,
  HIGHLIGHT_COLORS,
  ZOTERO_PALETTE,
  paletteHex,
  effectivePalette,
  hexToRgb255,
  type StoredAnnotation,
  type PageAnnotations,
} from "../shared/annotations";
import { locateAnchor } from "../shared/anchor-locate";

const ann = (patch: Partial<StoredAnnotation> = {}): StoredAnnotation => ({
  id: "abc123defg",
  url: "https://x/a",
  anchor: { exact: "The important claim.", prefix: "before it. ", suffix: " After it" },
  color: "yellow",
  style: "highlight",
  createdAt: "2026-07-20T00:00:00.000Z",
  ...patch,
});

describe("annotations · model", () => {
  it("makes distinct ids", () => {
    expect(annotationId()).not.toBe(annotationId());
    expect(annotationId()).toMatch(/^[a-z0-9]{10}$/);
  });

  it("replaces an annotation with the same id rather than duplicating it", () => {
    const page: PageAnnotations = { url: "https://x/a", annotations: [ann()] };
    const next = withAnnotation(page, ann({ color: "green" }));
    expect(next.annotations).toHaveLength(1);
    expect(next.annotations[0]?.color).toBe("green");
  });

  it("removes by id, and removing something already gone is not an error", () => {
    const page: PageAnnotations = { url: "https://x/a", annotations: [ann()] };
    expect(withoutAnnotation(page, "abc123defg").annotations).toHaveLength(0);
    expect(withoutAnnotation(page, "never-there").annotations).toHaveLength(1);
  });
});

describe("annotations · reading whatever the sidecar file holds", () => {
  it("reads a well-formed entry", () => {
    // coerceAnnotation now always resolves a transparency, defaulting to medium.
    expect(coerceAnnotation(ann())).toEqual(ann({ intensity: "medium" }));
  });

  it("drops entries that can't paint, rather than breaking every other highlight", () => {
    // The sidecar lives in someone's vault: hand-edits, sync conflicts, future versions.
    for (const bad of [null, 7, "x", {}, { id: "a" }, { id: "a", url: "u", anchor: {} }, { id: "a", url: "u", anchor: { exact: "" } }]) {
      expect(coerceAnnotation(bad)).toBeNull();
    }
  });

  it("falls back to yellow for a colour it doesn't know", () => {
    expect(coerceAnnotation({ ...ann(), color: "chartreuse" })?.color).toBe("yellow");
  });

  it("keeps an underline style and reads anything else as highlight", () => {
    expect(coerceAnnotation({ ...ann(), style: "underline" })?.style).toBe("underline");
    expect(coerceAnnotation({ ...ann(), style: "wavy" })?.style).toBe("highlight");
    expect(coerceAnnotation({ ...ann(), style: undefined })?.style).toBe("highlight");
  });

  it("accepts all six colours and still falls back for junk", () => {
    for (const color of ["yellow", "green", "blue", "red", "purple", "orange"]) {
      expect(coerceAnnotation({ ...ann(), color })?.color).toBe(color);
    }
    expect(coerceAnnotation({ ...ann(), color: "chartreuse" })?.color).toBe("yellow");
  });

  it("drops an empty note rather than keeping a blank field", () => {
    expect(coerceAnnotation({ ...ann(), note: "  " })?.note).toBeUndefined();
    expect(coerceAnnotation({ ...ann(), note: "kept" })?.note).toBe("kept");
  });
});

describe("annotations · the row cell copy", () => {
  it("wraps the quote in a colour mark and appends the note after a dash", () => {
    expect(annotationCellText(ann({ note: "my thought" }))).toBe(
      '<mark class="kvs-mark-yellow">The important claim.</mark> — my thought',
    );
    expect(annotationCellText(ann())).toBe('<mark class="kvs-mark-yellow">The important claim.</mark>');
    // The colour name rides in the class, so the cell shows the highlight's real colour.
    expect(annotationCellText(ann({ color: "green" }))).toContain('class="kvs-mark-green"');
  });

  it("stays on one line whatever the quote contained — a cell must", () => {
    const multi = ann({ anchor: { exact: "line one\nline two" } });
    expect(annotationCellText(multi)).toBe('<mark class="kvs-mark-yellow">line one line two</mark>');
    expect(annotationCellText(multi)).not.toContain("\n");
  });

  it("escapes HTML in the quote so the mark can't be broken, and keeps the note outside the mark", () => {
    const text = annotationCellText(ann({ anchor: { exact: "a < b & c > d" }, note: "outside" }));
    expect(text).toBe('<mark class="kvs-mark-yellow">a &lt; b &amp; c &gt; d</mark> — outside');
  });

  it("never leaks the id or anchor context into the cell", () => {
    const text = annotationCellText(ann({ note: "n" }));
    expect(text).not.toContain("abc123defg");
    expect(text).not.toContain("before it");
  });
});

describe("annotations · the note copy", () => {
  it("writes a blockquote with the note nested beneath", () => {
    expect(annotationNoteBlock(ann({ note: "my thought" }))).toBe(
      "> The important claim.\n>\n> — my thought",
    );
  });

  it("quotes every line of a multi-line highlight", () => {
    const block = annotationNoteBlock(ann({ anchor: { exact: "one\ntwo" } }));
    expect(block).toBe("> one\n> two");
  });
});

describe("annotations · locating for paint", () => {
  const raw = "Intro text here.  The   important\n  claim. And a follow-up sentence.";

  it("finds the quote across re-rendered whitespace, at raw offsets", () => {
    const at = locateAnchor(raw, { exact: "The important claim." });
    expect(at).not.toBeNull();
    expect(raw.slice(at!.start, at!.end).replace(/\s+/g, " ")).toBe("The important claim.");
  });

  it("matches exactly aside from whitespace — no fuzzy painting of the wrong words", () => {
    expect(locateAnchor(raw, { exact: "The unimportant claim." })).toBeNull();
  });

  it("takes a lone occurrence even when the remembered context has changed", () => {
    const at = locateAnchor(raw, { exact: "The important claim.", prefix: "entirely rewritten", suffix: "gone too" });
    expect(at).not.toBeNull();
  });

  it("uses context to choose between duplicates", () => {
    const doubled = "Alpha. Same words here. Beta. Same words here. Gamma.";
    const at = locateAnchor(doubled, { exact: "Same words here.", prefix: "Beta. ", suffix: " Gamma" });
    expect(at).not.toBeNull();
    expect(at!.start).toBeGreaterThan(doubled.indexOf("Beta"));
  });

  it("refuses duplicates it can't tell apart — wrong paint is worse than none", () => {
    const doubled = "Same words here. Same words here.";
    expect(locateAnchor(doubled, { exact: "Same words here." })).toBeNull();
  });

  it("survives regex-special characters in the quote", () => {
    const text = "Cost is $5.00 (roughly [a lot]) today.";
    const at = locateAnchor(text, { exact: "$5.00 (roughly [a lot])" });
    expect(at).not.toBeNull();
    expect(text.slice(at!.start, at!.end)).toBe("$5.00 (roughly [a lot])");
  });

  it("matches across a non-breaking space, which re-renders love to introduce", () => {
    const at = locateAnchor("The\u00a0important claim.", { exact: "The important claim." });
    expect(at).not.toBeNull();
  });

  it("returns null for an empty anchor", () => {
    expect(locateAnchor(raw, { exact: "   " })).toBeNull();
  });
});

describe("Zotero palette (the canonical highlight colours)", () => {
  it("is Zotero's eight colours, in Zotero's order, by exact hex", () => {
    expect(ZOTERO_PALETTE.map((c) => c.name)).toEqual([
      "yellow",
      "red",
      "green",
      "blue",
      "purple",
      "magenta",
      "orange",
      "gray",
    ]);
    expect(ZOTERO_PALETTE.map((c) => c.hex)).toEqual([
      "#ffd400",
      "#ff6666",
      "#5fb236",
      "#2ea8e5",
      "#a28ae5",
      "#e56eee",
      "#f19837",
      "#aaaaaa",
    ]);
  });

  it("exposes all eight names as HIGHLIGHT_COLORS and resolves a colour to its hex", () => {
    expect(HIGHLIGHT_COLORS).toHaveLength(8);
    expect(paletteHex("green")).toBe("#5fb236");
    expect(paletteHex("magenta")).toBe("#e56eee");
    // A name outside the palette can't occur through the type, but the fallback is Zotero yellow.
    expect(paletteHex("yellow")).toBe("#ffd400");
  });
});

describe("effectivePalette (the per-vault override)", () => {
  it("returns Zotero's palette untouched when the override is off or absent", () => {
    expect(effectivePalette(undefined)).toBe(ZOTERO_PALETTE);
    expect(effectivePalette({ enabled: false, colors: { green: "#008080" } })).toBe(ZOTERO_PALETTE);
  });

  it("replaces only the overridden slots when enabled, and derives their rgb from the hex", () => {
    const p = effectivePalette({ enabled: true, colors: { green: "#008080" } });
    const green = p.find((c) => c.name === "green")!;
    expect(green.hex).toBe("#008080");
    expect(green.rgb).toEqual([0, 128, 128]);
    // Every other colour keeps its Zotero value.
    expect(p.find((c) => c.name === "red")!.hex).toBe("#ff6666");
    expect(p.find((c) => c.name === "yellow")!.hex).toBe("#ffd400");
    // Order and length are preserved.
    expect(p.map((c) => c.name)).toEqual(ZOTERO_PALETTE.map((c) => c.name));
  });

  it("falls back to Zotero per-slot for an invalid or missing entry — never a broken swatch", () => {
    const p = effectivePalette({ enabled: true, colors: { green: "not-a-hex", blue: "#123456" } });
    // Invalid green → Zotero green; missing red → Zotero red; valid blue → the override.
    expect(p.find((c) => c.name === "green")!.hex).toBe("#5fb236");
    expect(p.find((c) => c.name === "red")!.hex).toBe("#ff6666");
    expect(p.find((c) => c.name === "blue")!.hex).toBe("#123456");
  });

  it("parses hex to rgb, tolerating a missing # and rejecting nonsense", () => {
    expect(hexToRgb255("#5fb236")).toEqual([95, 178, 54]);
    expect(hexToRgb255("5fb236")).toEqual([95, 178, 54]);
    expect(hexToRgb255("#fff")).toBeNull();
    expect(hexToRgb255("teal")).toBeNull();
  });
});
