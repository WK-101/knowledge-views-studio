import { describe, it, expect } from "vitest";
import { PDFDocument, PDFName, PDFArray, PDFDict, PDFNumber } from "pdf-lib";
import { pdfAnnotationStore } from "../src/services/annotations/pdf-annotation-store";
import { rectsToQuadPoints, hexToRgb01, boundingRect } from "../src/domain/annotations/annotation";
import type { KvsAnnotation } from "../src/domain/index";

describe("PDF annotation geometry", () => {
  it("builds QuadPoints in PDF order (UL,UR,LL,LR) and a bounding rect", () => {
    const q = rectsToQuadPoints([{ x0: 10, y0: 20, x1: 110, y1: 32 }]);
    expect(q).toEqual([10, 32, 110, 32, 10, 20, 110, 20]);
    expect(boundingRect([{ x0: 10, y0: 20, x1: 110, y1: 32 }, { x0: 5, y0: 8, x1: 60, y1: 18 }])).toEqual([5, 8, 110, 32]);
    expect(hexToRgb01("#ff0000")).toEqual([1, 0, 0]);
  });
});

describe("pdfAnnotationStore.write (round-trip via pdf-lib)", () => {
  it("injects a standard Highlight annotation into the page", async () => {
    const base = await PDFDocument.create();
    base.addPage([600, 800]);
    const bytes = (await base.save()).buffer as ArrayBuffer;

    const ann: KvsAnnotation = {
      id: "x", kind: "highlight", text: "hello", comment: "my note", page: 1,
      rects: [{ x0: 50, y0: 700, x1: 250, y1: 712 }], source: "manual", attachment: "p.pdf", color: "#ffd400",
    };
    const out = await pdfAnnotationStore.write(bytes, [ann]);

    const re = await PDFDocument.load(out);
    const page = re.getPages()[0]!;
    const annots = page.node.get(PDFName.of("Annots")) as PDFArray;
    expect(annots).toBeTruthy();
    const dict = annots.lookup(0, PDFDict);
    expect((dict.get(PDFName.of("Subtype")) as { encodedName?: string }).toString()).toContain("Highlight");
    const quad = dict.get(PDFName.of("QuadPoints")) as PDFArray;
    expect(quad.size()).toBe(8);
    expect((quad.lookup(0, PDFNumber)).asNumber()).toBe(50); // x0 of the quad
    const color = dict.get(PDFName.of("C")) as PDFArray;
    expect((color.lookup(0, PDFNumber)).asNumber()).toBeCloseTo(1, 2); // yellow R≈1
  });
});

import { PDFString } from "pdf-lib";

async function makePdf(pages = 2): Promise<ArrayBuffer> {
  const d = await PDFDocument.create();
  for (let i = 0; i < pages; i++) d.addPage([600, 800]);
  return (await d.save()).buffer as ArrayBuffer;
}

describe("incremental add/remove (append-only, validated)", () => {
  it("appends a highlight incrementally, preserving original bytes + page count", async () => {
    const base = await makePdf(3);
    const ann: KvsAnnotation = { id: "abc12345", kind: "highlight", text: "t", comment: "c", page: 2, rects: [{ x0: 40, y0: 600, x1: 240, y1: 612 }], source: "manual", attachment: "p.pdf", color: "#5fb236" };
    const out = await pdfAnnotationStore.addAnnotations(base, [ann]);
    // Original bytes are a prefix (incremental keeps them verbatim).
    expect(new Uint8Array(out).slice(0, base.byteLength)).toEqual(new Uint8Array(base));
    // Re-parses, same page count, annotation present.
    const re = await PDFDocument.load(out);
    expect(re.getPageCount()).toBe(3);
    const annots = re.getPages()[1]!.node.get(PDFName.of("Annots")) as PDFArray;
    expect(annots.size()).toBe(1);
  });

  it("removes an annotation matching a page+rect target", async () => {
    const base = await makePdf(1);
    const ann: KvsAnnotation = { id: "d", kind: "highlight", text: "t", comment: "", page: 1, rects: [{ x0: 50, y0: 700, x1: 250, y1: 712 }], source: "manual", attachment: "p.pdf", color: "#ffd400" };
    const withAnn = await pdfAnnotationStore.addAnnotations(base, [ann]);
    const removed = await pdfAnnotationStore.removeAnnotations(withAnn, [{ page: 1, rect: [50, 700, 250, 712] }]);
    const re = await PDFDocument.load(removed);
    const annots = re.getPages()[0]!.node.get(PDFName.of("Annots")) as PDFArray;
    expect(annots.size()).toBe(0);
  });
});
void PDFString;

import { viewportBox } from "../src/domain/annotations/annotation";

describe("viewportBox (overlay geometry)", () => {
  it("maps a PDF rect to a screen box via a viewport converter (y-flip)", () => {
    // page 600x800, scale 1, no rotation: PDF (x,y up) -> viewport (x, 800-y down)
    const convert = (x: number, y: number): [number, number] => [x, 800 - y];
    const box = viewportBox({ x0: 50, y0: 700, x1: 250, y1: 712 }, convert);
    expect(box).toEqual({ left: 50, top: 88, width: 200, height: 12 });
  });
});
