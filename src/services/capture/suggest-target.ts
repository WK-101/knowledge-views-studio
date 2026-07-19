import type { Row } from "../../domain/index";
import type { CaptureTarget } from "./types";

/**
 * Working out where a view's captures should go, when nobody has said.
 *
 * Until now a view without a capture target simply refused, and the companion reported "no view can receive
 * captures" — which reads as a fault rather than as a setting nobody had been asked to fill in. Refusing to
 * guess is the right instinct when a guess could corrupt something, but this isn't that: the target is
 * proposed, shown, and only saved when someone accepts it.
 *
 * The proposal follows where the view's data already lives. A view assembled from `Reading/Books.md` should
 * capture into `Reading/Books.md` — anywhere else would scatter one collection across two files, which is
 * precisely the mess this plugin exists to undo.
 */

/** Strip characters a vault path can't hold. */
function safeSegment(raw: string, fallback: string): string {
  const cleaned = raw
    .replace(/[\\/:*?"<>|#^[\]]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return cleaned === "" ? fallback : cleaned.slice(0, 60);
}

/**
 * The file most of a view's rows come from.
 *
 * Ties go to whichever appeared first, which is the order the sources were discovered — so a view with an
 * even split lands on the one its author listed first rather than on an arbitrary winner.
 */
export function dominantFile(rows: readonly Row[]): string | null {
  const counts = new Map<string, number>();
  for (const row of rows) {
    const path = row.provenance.filePath;
    if (path === undefined || path === "") continue;
    counts.set(path, (counts.get(path) ?? 0) + 1);
  }
  let best: string | null = null;
  let bestCount = 0;
  for (const [path, count] of counts) {
    if (count > bestCount) {
      best = path;
      bestCount = count;
    }
  }
  return best;
}

/**
 * Propose a capture target for a view.
 *
 * Markdown sources only: a view built from spreadsheets shouldn't quietly start appending rows to a
 * worksheet, where the write path has different rules and a mistake is harder to see.
 */
export function suggestCaptureTarget(
  rows: readonly Row[],
  viewName: string,
  folder = "Captured",
): CaptureTarget {
  const dominant = dominantFile(rows);
  if (dominant !== null && dominant.toLowerCase().endsWith(".md")) {
    // Capture into the file the view already reads, so one collection stays in one place.
    return { shape: "row", notePath: dominant, createIfMissing: true };
  }
  // Nothing to follow: a new file named after the view, created on first capture.
  const name = safeSegment(viewName, "Captured");
  const dir = safeSegment(folder, "Captured");
  return { shape: "row", notePath: `${dir}/${name}.md`, createIfMissing: true };
}

/** Whether a view can currently receive a capture, for the setup checklist. */
export function hasUsableTarget(target: CaptureTarget | undefined, newRowFile: string | undefined): boolean {
  if (target !== undefined) {
    if (target.shape === "note") return true;
    if ((target.notePath ?? "").trim() !== "") return true;
  }
  return (newRowFile ?? "").trim() !== "";
}
