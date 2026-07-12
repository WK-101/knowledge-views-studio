import type { AnnotationRect } from "./annotation";

/** A text run with its bounding box in PDF user space (origin bottom-left). */
export interface PositionedText {
  readonly str: string;
  readonly bbox: AnnotationRect;
}

/** Do two rects overlap, allowing a small tolerance? */
function overlaps(a: AnnotationRect, b: AnnotationRect, pad: number): boolean {
  return a.x0 - pad <= b.x1 && a.x1 + pad >= b.x0 && a.y0 - pad <= b.y1 && a.y1 + pad >= b.y0;
}

/** Vertical midpoint of a run's box. */
function midY(r: AnnotationRect): number {
  return (r.y0 + r.y1) / 2;
}

/**
 * Extract the text a highlight covers: for each highlighted rect (typically one per visual line),
 * collect text runs that overlap it, order them left-to-right, then order the lines top-to-bottom.
 * Pure and synthetic-testable — the PDF layer only supplies rects + positioned runs.
 */
export function textInRects(rects: readonly AnnotationRect[], runs: readonly PositionedText[], tolerance = 1.5): string {
  const lines: { y: number; text: string }[] = [];
  for (const rect of rects) {
    const hits = runs.filter((run) => overlaps(rect, run.bbox, tolerance));
    if (hits.length === 0) continue;
    hits.sort((a, b) => a.bbox.x0 - b.bbox.x0);
    const text = hits
      .map((h) => h.str)
      .join("")
      .replace(/\s+/g, " ")
      .trim();
    if (text !== "") lines.push({ y: midY(rect), text });
  }
  // Top-to-bottom = descending y in PDF space.
  lines.sort((a, b) => b.y - a.y);
  return lines
    .map((l) => l.text)
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}
