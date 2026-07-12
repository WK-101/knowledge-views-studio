import { strFromU8, strToU8, unzipSync, zipSync } from "fflate";

/**
 * A format-agnostic view of an OOXML container (`.xlsx`/`.docx`/`.pptx` are all zips of named
 * "parts"). This layer knows nothing about sheets, rows or documents — it just exposes the parts
 * and lets one part be replaced and the whole re-zipped.
 *
 * Fidelity guarantee (precise): unzip→rezip does NOT reproduce the original container byte-for-byte
 * (deflate output differs), so the file is not byte-identical. What IS guaranteed is that every part
 * you don't call `withPart` on keeps its exact *decompressed* content — so formulas, styles, charts,
 * pivot caches, number formats and defined names (all in parts you never touch) survive exactly.
 */
export interface OfficePackage {
  /** Part names in their stored order, e.g. "xl/worksheets/sheet1.xml". */
  parts(): string[];
  has(part: string): boolean;
  readBytes(part: string): Uint8Array | undefined;
  /** UTF-8 decode of an XML/text part. */
  readText(part: string): string | undefined;
  /** Immutable: a NEW package with one part replaced (position preserved; appended if new). */
  withPart(part: string, content: Uint8Array | string): OfficePackage;
  /** Re-zip. Untouched parts keep their exact decompressed bytes and original order. */
  toBytes(): Uint8Array;
}

function makePackage(entries: Map<string, Uint8Array>): OfficePackage {
  return {
    parts: () => [...entries.keys()],
    has: (part) => entries.has(part),
    readBytes: (part) => entries.get(part),
    readText: (part) => {
      const bytes = entries.get(part);
      return bytes ? strFromU8(bytes) : undefined;
    },
    withPart: (part, content) => {
      const next = new Map(entries);
      next.set(part, typeof content === "string" ? strToU8(content) : content);
      return makePackage(next);
    },
    toBytes: () => zipSync(Object.fromEntries(entries), { level: 6 }),
  };
}

export function openOfficePackage(input: ArrayBuffer | Uint8Array): OfficePackage {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  // Fail fast with a readable reason instead of fflate's cryptic "invalid zip data". A real
  // OOXML file is a zip and starts with the "PK" signature; empty files, cloud-sync placeholders,
  // and old binary .xls files (OLE, starts 0xD0 0xCF) renamed to .xlsx all land here.
  if (bytes.byteLength < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
    throw new Error("not a valid .xlsx file (missing zip signature) — it may be empty, not fully synced, or an old .xls saved with an .xlsx name");
  }
  const unzipped = unzipSync(bytes);
  const entries = new Map<string, Uint8Array>();
  for (const [name, data] of Object.entries(unzipped)) entries.set(name, data);
  return makePackage(entries);
}
