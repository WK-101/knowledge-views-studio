import { gunzipSync, gzipSync } from "fflate";

/**
 * Serialising the search index to a file.
 *
 * IndexedDB stores JavaScript objects directly, so typed arrays survive without anyone thinking about
 * it. A file does not: it holds bytes. And the index is full of typed arrays — every semantic vector is
 * a `Float32Array`, and `JSON.stringify` turns one of those into `{"0":0.1,"1":0.2,...}`, which is both
 * enormous and no longer a `Float32Array` when it comes back.
 *
 * So: a small container. A JSON envelope describing the structure, with each typed array replaced by a
 * reference into a single binary blob appended after it, and the whole thing gzipped. Vectors are dense
 * float data and postings are runs of small integers; both compress well, which matters when the file is
 * going to cross a sync service.
 *
 *   [ "KVSI" ][ u8 version ][ u32 json length ][ json ][ binary blob ]
 *
 * The format is versioned, and a file whose version we do not recognise is refused rather than guessed
 * at — a half-understood index is worse than no index.
 */

const MAGIC = 0x4b565349; // "KVSI"
const VERSION = 1;

type TypedArray = Float32Array | Int32Array | Uint8Array;

interface TypedRef {
  readonly __ta: "f32" | "i32" | "u8";
  readonly off: number;
  readonly len: number;
}

function isTypedRef(v: unknown): v is TypedRef {
  return typeof v === "object" && v !== null && "__ta" in v && "off" in v && "len" in v;
}

function tagOf(v: TypedArray): TypedRef["__ta"] | null {
  if (v instanceof Float32Array) return "f32";
  if (v instanceof Int32Array) return "i32";
  if (v instanceof Uint8Array) return "u8";
  return null;
}

/** Serialise a structure containing typed arrays to gzipped bytes. */
export function encodeIndex(value: unknown): Uint8Array {
  const chunks: Uint8Array[] = [];
  let offset = 0;

  const walk = (node: unknown): unknown => {
    if (node === null || typeof node !== "object") return node;

    const tag = tagOf(node as TypedArray);
    if (tag) {
      const arr = node as TypedArray;
      const bytes = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
      // Copy: the source may be a view into a larger buffer, and we are about to concatenate.
      const copy = new Uint8Array(bytes);
      chunks.push(copy);
      const ref: TypedRef = { __ta: tag, off: offset, len: arr.length };
      offset += copy.byteLength;
      return ref;
    }

    if (Array.isArray(node)) return node.map(walk);

    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node)) out[k] = walk(v);
    return out;
  };

  const envelope = walk(value);
  const json = new TextEncoder().encode(JSON.stringify(envelope));

  const blobLen = offset;
  const total = 4 + 1 + 4 + json.byteLength + blobLen;
  const buf = new Uint8Array(total);
  const view = new DataView(buf.buffer);
  view.setUint32(0, MAGIC);
  view.setUint8(4, VERSION);
  view.setUint32(5, json.byteLength);
  buf.set(json, 9);
  let p = 9 + json.byteLength;
  for (const chunk of chunks) {
    buf.set(chunk, p);
    p += chunk.byteLength;
  }

  return gzipSync(buf);
}

/** Read back what `encodeIndex` wrote. Returns undefined for anything we do not recognise. */
export function decodeIndex(gz: Uint8Array): unknown {
  let buf: Uint8Array;
  try {
    buf = gunzipSync(gz);
  } catch {
    return undefined; // not our file, or truncated by a half-finished sync
  }
  if (buf.byteLength < 9) return undefined;

  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (view.getUint32(0) !== MAGIC) return undefined;
  if (view.getUint8(4) !== VERSION) return undefined; // a version we do not understand is refused, not guessed

  const jsonLen = view.getUint32(5);
  if (9 + jsonLen > buf.byteLength) return undefined;

  let envelope: unknown;
  try {
    envelope = JSON.parse(new TextDecoder().decode(buf.subarray(9, 9 + jsonLen)));
  } catch {
    return undefined;
  }

  const blob = buf.subarray(9 + jsonLen);

  const revive = (node: unknown): unknown => {
    if (node === null || typeof node !== "object") return node;

    if (isTypedRef(node)) {
      const { __ta, off, len } = node;
      const bytesPer = __ta === "u8" ? 1 : 4;
      const end = off + len * bytesPer;
      if (off < 0 || end > blob.byteLength) return undefined; // corrupt reference: refuse, don't guess
      // Copy into a fresh, aligned buffer: `blob` is a view and its offset need not be 4-byte aligned,
      // which a Float32Array view would reject.
      const slice = new Uint8Array(blob.subarray(off, end));
      if (__ta === "f32") return new Float32Array(slice.buffer, 0, len);
      if (__ta === "i32") return new Int32Array(slice.buffer, 0, len);
      return slice;
    }

    if (Array.isArray(node)) return node.map(revive);

    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node)) out[k] = revive(v);
    return out;
  };

  return revive(envelope);
}

/** Human-readable size, for telling the user what they are about to put in their vault. */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
