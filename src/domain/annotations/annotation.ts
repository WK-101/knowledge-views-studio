import { fnv1a } from "../../util/hash";

/** Standard PDF markup + note kinds we recognise (superset covers Zotero/EPUB later). */
export type AnnotationKind = "highlight" | "underline" | "strikeout" | "note" | "square" | "freetext" | "ink" | "image";
export type AnnotationSource = "pdf-embedded" | "zotero" | "zotflow" | "manual" | "docx" | "xlsx" | "pptx";

/** An axis-aligned rectangle in PDF user space (origin bottom-left). */
export interface AnnotationRect {
  readonly x0: number;
  readonly y0: number;
  readonly x1: number;
  readonly y1: number;
}

/**
 * A normalised annotation. Deliberately LOSSLESS: geometry (rects/quadPoints), colour, opacity and
 * identity are kept even though today's renderer only uses text/comment/page/colour — so a future
 * annotator (write path) has everything it needs without a model change.
 */
export interface KvsAnnotation {
  readonly id: string;
  readonly kind: AnnotationKind;
  readonly color?: string; // "#rrggbb"
  readonly opacity?: number;
  readonly text: string; // highlighted text (markup annotations)
  readonly comment: string; // the reader's note
  readonly page: number; // 1-based
  readonly pageLabel?: string;
  readonly rects: readonly AnnotationRect[]; // geometry (lossless)
  readonly quadPoints?: readonly number[]; // raw quads (lossless)
  readonly imagePath?: string; // area/image annotations (future)
  readonly source: AnnotationSource;
  readonly attachment: string; // file path or URL the annotation came from
  readonly author?: string;
  readonly createdAt?: string; // ISO
}

/** Stable id for dedup/merge and idempotent re-sync. Source-independent so the *same* highlight from
 *  two sources (PDF + Zotero) collapses to one; independent of comment edits. */
export function annotationId(a: Pick<KvsAnnotation, "attachment" | "page" | "kind" | "text" | "rects">): string {
  const geo = a.text.trim() !== "" ? a.text.trim() : a.rects.map((r) => `${r.x0.toFixed(1)},${r.y0.toFixed(1)},${r.x1.toFixed(1)},${r.y1.toFixed(1)}`).join(";");
  return fnv1a(`${a.attachment}|${a.page}|${a.kind}|${geo}`);
}

const NAMED: readonly { name: string; rgb: [number, number, number] }[] = [
  { name: "yellow", rgb: [255, 212, 0] },
  { name: "red", rgb: [231, 76, 60] },
  { name: "green", rgb: [58, 166, 117] },
  { name: "blue", rgb: [46, 108, 176] },
  { name: "purple", rgb: [142, 68, 173] },
  { name: "orange", rgb: [230, 126, 34] },
  { name: "gray", rgb: [149, 165, 166] },
];

/** Nearest human colour name for a hex — drives the colour→callout/theme mapping. */
export function colorName(hex?: string): string {
  if (!hex) return "gray";
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return "gray";
  const v = parseInt(m[1]!, 16);
  const rgb: [number, number, number] = [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  let best = NAMED[0]!;
  let bestD = Infinity;
  for (const c of NAMED) {
    const d = (c.rgb[0] - rgb[0]) ** 2 + (c.rgb[1] - rgb[1]) ** 2 + (c.rgb[2] - rgb[2]) ** 2;
    if (d < bestD) {
      bestD = d;
      best = c;
    }
  }
  return best.name;
}

/** Convert a 0..1 (or 0..255) RGB triple to a hex string. */
export function rgbToHex(r: number, g: number, b: number): string {
  const to = (n: number): string => {
    const scaled = n <= 1 ? Math.round(n * 255) : Math.round(n);
    return Math.max(0, Math.min(255, scaled)).toString(16).padStart(2, "0");
  };
  return `#${to(r)}${to(g)}${to(b)}`;
}

/** Hex "#rrggbb" → [r,g,b] on a 0..1 scale (for PDF /C colour arrays). */
export function hexToRgb01(hex?: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex ?? "").trim());
  if (!m) return [1, 0.83, 0]; // default yellow
  const v = parseInt(m[1]!, 16);
  return [((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255];
}

/** Axis-aligned bounding box of rects → [x0,y0,x1,y1] (PDF /Rect). */
export function boundingRect(rects: readonly AnnotationRect[]): [number, number, number, number] {
  if (rects.length === 0) return [0, 0, 0, 0];
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  for (const r of rects) {
    x0 = Math.min(x0, r.x0);
    y0 = Math.min(y0, r.y0);
    x1 = Math.max(x1, r.x1);
    y1 = Math.max(y1, r.y1);
  }
  return [x0, y0, x1, y1];
}

/**
 * Rects (one per highlighted line) → a flat QuadPoints array in PDF order: for each quad,
 * upper-left, upper-right, lower-left, lower-right (x,y pairs), y-up.
 */
export function rectsToQuadPoints(rects: readonly AnnotationRect[]): number[] {
  const out: number[] = [];
  for (const r of rects) {
    out.push(r.x0, r.y1, r.x1, r.y1, r.x0, r.y0, r.x1, r.y0);
  }
  return out;
}

/** A PDF rect → an on-screen overlay box (CSS px within a page), using a viewport point converter. */
export function viewportBox(
  rect: AnnotationRect,
  convertToViewportPoint: (x: number, y: number) => [number, number],
): { left: number; top: number; width: number; height: number } {
  const [ax, ay] = convertToViewportPoint(rect.x0, rect.y1); // PDF top-left (y1 is the top, y-up)
  const [bx, by] = convertToViewportPoint(rect.x1, rect.y0); // PDF bottom-right
  return { left: Math.min(ax, bx), top: Math.min(ay, by), width: Math.abs(bx - ax), height: Math.abs(by - ay) };
}
