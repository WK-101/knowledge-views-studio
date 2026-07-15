import { describe, expect, it } from "vitest";
import {
  parseZotFlowSidecar,
  zotflowSidecarPath,
  zotflowToKvsAnnotation,
} from "../src/services/annotations/zotflow-interop";

/**
 * The interop with ZotFlow reads another program's file format off disk. That is exactly where robustness
 * matters most: a corrupt, half-written, or version-changed `.zf.json` must degrade to "no annotations",
 * never crash the sync. These tests pin the defensive parsing and the mapping into our annotation model.
 * (The detection and file-opening paths need a live Obsidian app and ZotFlow install, so they are not
 * unit-testable here — they are guarded at their call sites to fall back to our own reader.)
 */

describe("zotflowSidecarPath", () => {
  it("swaps the extension for .zf.json", () => {
    expect(zotflowSidecarPath("Papers/myPaper.pdf")).toBe("Papers/myPaper.zf.json");
    expect(zotflowSidecarPath("Books/intro.epub")).toBe("Books/intro.zf.json");
  });

  it("handles paths with dots in the folder name", () => {
    expect(zotflowSidecarPath("My.Papers/a.b.pdf")).toBe("My.Papers/a.b.zf.json");
  });

  it("handles a path with no extension by appending", () => {
    expect(zotflowSidecarPath("noext")).toBe("noext.zf.json");
  });
});

describe("parseZotFlowSidecar — defensive parsing of a foreign format", () => {
  it("parses a well-formed sidecar", () => {
    const raw = JSON.stringify({
      version: 1,
      annotations: [
        { id: "a1", type: "highlight", text: "the passage", comment: "my note", color: "#ffd400", pageLabel: "12", dateModified: "2026-01-01T00:00:00Z" },
        { id: "a2", type: "underline", text: "underlined bit" },
      ],
    });
    const out = parseZotFlowSidecar(raw);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ id: "a1", type: "highlight", text: "the passage", comment: "my note", color: "#ffd400", pageLabel: "12" });
    expect(out[1]).toMatchObject({ id: "a2", type: "underline", text: "underlined bit" });
  });

  it("returns [] for invalid JSON rather than throwing", () => {
    expect(parseZotFlowSidecar("{ not json")).toEqual([]);
    expect(parseZotFlowSidecar("")).toEqual([]);
  });

  it("returns [] when the annotations field is missing or not an array", () => {
    expect(parseZotFlowSidecar(JSON.stringify({ version: 1 }))).toEqual([]);
    expect(parseZotFlowSidecar(JSON.stringify({ annotations: "nope" }))).toEqual([]);
    expect(parseZotFlowSidecar(JSON.stringify({ annotations: {} }))).toEqual([]);
  });

  it("skips individual annotations that lack an id or type, keeping the good ones", () => {
    const raw = JSON.stringify({
      annotations: [
        { type: "highlight", text: "no id" }, // dropped
        { id: "ok", type: "highlight", text: "kept" }, // kept
        { id: "no-type" }, // dropped
        null, // dropped
        "string", // dropped
      ],
    });
    const out = parseZotFlowSidecar(raw);
    expect(out).toHaveLength(1);
    expect(out[0]!.id).toBe("ok");
  });

  it("omits optional fields that are the wrong type instead of coercing them", () => {
    const raw = JSON.stringify({
      annotations: [{ id: "a", type: "highlight", text: 123, comment: null, color: 42 }],
    });
    const out = parseZotFlowSidecar(raw);
    expect(out).toHaveLength(1);
    expect(out[0]).toEqual({ id: "a", type: "highlight" }); // no text/comment/color leaked
  });

  it("tolerates a top-level array that isn't an object", () => {
    expect(parseZotFlowSidecar(JSON.stringify([1, 2, 3]))).toEqual([]);
    expect(parseZotFlowSidecar(JSON.stringify(null))).toEqual([]);
  });
});

describe("zotflowToKvsAnnotation — mapping into our model", () => {
  it("maps a highlight with text and comment", () => {
    const k = zotflowToKvsAnnotation(
      { id: "a1", type: "highlight", text: "quoted", comment: "note", color: "#ffd400", pageLabel: "7" },
      "Papers/x.pdf",
    );
    expect(k).toMatchObject({
      kind: "highlight",
      text: "quoted",
      comment: "note",
      color: "#ffd400",
      pageLabel: "7",
      source: "zotflow",
      attachment: "Papers/x.pdf",
    });
    expect(k.id).toBeTruthy(); // content id assigned
  });

  it("maps ZotFlow/Zotero annotation types to our kinds", () => {
    const kindOf = (t: string): string => zotflowToKvsAnnotation({ id: "x", type: t }, "f.pdf").kind;
    expect(kindOf("highlight")).toBe("highlight");
    expect(kindOf("underline")).toBe("underline");
    expect(kindOf("note")).toBe("note");
    expect(kindOf("ink")).toBe("ink");
    expect(kindOf("image")).toBe("image");
    expect(kindOf("text")).toBe("note"); // Zotero's "text" annotation is a standalone note
  });

  it("falls back to highlight for an unknown type", () => {
    expect(zotflowToKvsAnnotation({ id: "x", type: "future-type" }, "f.pdf").kind).toBe("highlight");
  });

  it("gives two annotations on the same file distinct ids by content", () => {
    const a = zotflowToKvsAnnotation({ id: "1", type: "highlight", text: "alpha" }, "f.pdf");
    const b = zotflowToKvsAnnotation({ id: "2", type: "highlight", text: "beta" }, "f.pdf");
    expect(a.id).not.toBe(b.id);
  });
});
