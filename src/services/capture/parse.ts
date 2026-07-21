import type { CaptureField, CapturePayload, CaptureTarget } from "./types";

/**
 * Reading a capture out of loose input, and working out where it should go.
 *
 * Kept free of any Obsidian import so it can be tested directly — and so the browser companion can reuse the
 * same parsing later without dragging the plugin's UI along with it.
 */

/** A capture target, with the shape of the thing it targets. */
export interface TargetSource {
  readonly captureTarget?: CaptureTarget;
  readonly newRowFile?: string;
}

/**
 * Read a payload out of arbitrary pasted text.
 *
 * Three shapes are recognised, in order of confidence: a bare URL, `Key: value` lines, and everything else.
 * The last is treated as a title and a description, because that's what a copied snippet nearly always is —
 * a headline followed by supporting text.
 */
export function parseCaptureText(text: string): CapturePayload {
  const trimmed = text.trim();
  if (trimmed === "") return { fields: [] };

  // A bare URL is the commonest thing on a clipboard, so treat it as the source rather than as a nameless
  // field — that way it can fill a url column and be used to spot something already captured.
  if (/^https?:\/\/\S+$/i.test(trimmed)) return { fields: [], url: trimmed };

  const lines = trimmed.split(/\r?\n/).filter((l) => l.trim() !== "");
  const fields: CaptureField[] = [];
  const unlabelled: string[] = [];
  let url: string | undefined;

  for (const line of lines) {
    // Deliberately narrow: a short, word-like key followed by a separator. Prose that happens to contain a
    // colon shouldn't be mistaken for a field name.
    const m = /^\s*([A-Za-z][\w .-]{0,30}?)\s*[:=]\s*(\S.*)$/.exec(line);
    if (m) {
      const key = m[1]!.trim();
      const value = m[2]!.trim();
      if (key.toLowerCase() === "url") url = value;
      fields.push({ key, value });
    } else {
      unlabelled.push(line.trim());
    }
  }

  if (unlabelled.length > 0) {
    fields.push({ key: "title", value: unlabelled[0]! });
    if (unlabelled.length > 1) fields.push({ key: "description", value: unlabelled.slice(1).join(" ") });
  }

  return { fields, ...(url !== undefined ? { url } : {}) };
}

/**
 * Where a view's captures should land.
 *
 * Falls back to the older `newRowFile` setting so a view configured before capture existed still works —
 * and with table creation allowed, since that setting's own limitation (needing an existing row to anchor
 * against) is exactly what capture is meant to get past.
 */
export function effectiveTarget(source: TargetSource): CaptureTarget | null {
  const target = source.captureTarget;
  if (target) {
    if (target.shape === "note") return target;
    // A periodic destination resolves its own path each time, so it's valid without a static notePath.
    if (target.destination === "periodic") return target;
    if ((target.notePath ?? "").trim() !== "") return target;
  }
  const legacy = (source.newRowFile ?? "").trim();
  if (legacy !== "") return { shape: "row", notePath: legacy, createIfMissing: true };
  return null;
}
