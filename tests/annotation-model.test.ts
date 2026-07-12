import { describe, it, expect } from "vitest";
import { annotationId, colorName, rgbToHex, textInRects, type PositionedText, type AnnotationRect } from "../src/domain/annotations/index";

describe("annotation model", () => {
  it("gives a stable id independent of the comment", () => {
    const base = { attachment: "p.pdf", page: 3, kind: "highlight" as const, text: "self-attention", rects: [] };
    const a = annotationId(base);
    const b = annotationId({ ...base }); // same everything
    expect(a).toBe(b);
    // Different page → different id.
    expect(annotationId({ ...base, page: 4 })).not.toBe(a);
  });

  it("falls back to geometry for the id when there's no text (e.g. a note)", () => {
    const rects: AnnotationRect[] = [{ x0: 10, y0: 20, x1: 30, y1: 40 }];
    const id1 = annotationId({ attachment: "p.pdf", page: 1, kind: "note", text: "", rects });
    const id2 = annotationId({ attachment: "p.pdf", page: 1, kind: "note", text: "", rects: [{ x0: 99, y0: 99, x1: 100, y1: 100 }] });
    expect(id1).not.toBe(id2);
  });

  it("names colours by nearest and converts rgb→hex", () => {
    expect(colorName("#ffd400")).toBe("yellow");
    expect(colorName("#e74c3c")).toBe("red");
    expect(colorName("#2e6cb0")).toBe("blue");
    expect(rgbToHex(1, 0, 0)).toBe("#ff0000"); // 0..1 scale
    expect(rgbToHex(255, 212, 0)).toBe("#ffd400"); // 0..255 scale
  });
});

describe("textInRects", () => {
  // Two lines of a highlight. PDF space: higher y = higher on the page.
  const line1: AnnotationRect = { x0: 0, y0: 100, x1: 200, y1: 112 };
  const line2: AnnotationRect = { x0: 0, y0: 84, x1: 120, y1: 96 };
  const runs: PositionedText[] = [
    { str: "The", bbox: { x0: 0, y0: 100, x1: 30, y1: 112 } },
    { str: " transformer", bbox: { x0: 32, y0: 100, x1: 120, y1: 112 } },
    { str: " relies", bbox: { x0: 122, y0: 100, x1: 180, y1: 112 } },
    { str: "on attention", bbox: { x0: 0, y0: 84, x1: 110, y1: 96 } },
    { str: "OUTSIDE", bbox: { x0: 0, y0: 400, x1: 60, y1: 412 } }, // far away → excluded
  ];

  it("reads runs under each line, left-to-right, lines top-to-bottom", () => {
    expect(textInRects([line1, line2], runs)).toBe("The transformer relies on attention");
  });

  it("returns empty when nothing overlaps", () => {
    expect(textInRects([{ x0: 500, y0: 500, x1: 510, y1: 510 }], runs)).toBe("");
  });
});
