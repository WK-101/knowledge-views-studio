import { normalizePath, requestUrl, type App } from "obsidian";
import { unzipSync } from "fflate";

/**
 * OCR runtime assets — tesseract's worker, its WASM core, and the language models — are far too large to
 * bundle (they'd bloat every install and every sync). Instead they're downloaded once, on the user's
 * request, from a release asset, verified against a pinned SHA-256, and unzipped into the plugin's folder;
 * from then on everything loads locally over `app://` URLs, with no further network use ever.
 *
 * Until the assets are present, OCR stays inert (with a clear notice). Point `OCR_ASSETS_URL` at wherever the
 * `ocr-assets.zip` for this build is published.
 */

/** Where the asset bundle is published. Replace the tag with the release you attach ocr-assets.zip to. */
export const OCR_ASSETS_URL = "https://github.com/WK-101/knowledge-views-studio/releases/download/ocr-assets-v1/ocr-assets.zip";

/** SHA-256 of ocr-assets.zip, verified before unzipping. Empty = skip the check (dev only). */
export const OCR_ASSETS_SHA256: string = "";

/** Files the bundle must contain for tesseract to run. */
const REQUIRED_ASSETS = ["worker.min.js", "tesseract-core-simd-lstm.wasm.js", "eng.traineddata.gz"] as const;

const assetsDir = (manifestDir: string): string => normalizePath(`${manifestDir}/ocr-assets`);

/** The `app://` URL Obsidian serves a plugin file from — how tesseract loads its worker/core/models. */
export function assetUrl(app: App, manifestDir: string, name: string): string {
  return app.vault.adapter.getResourcePath(normalizePath(`${assetsDir(manifestDir)}/${name}`));
}

/** Are the core assets already on disk? */
export async function assetsPresent(app: App, manifestDir: string): Promise<boolean> {
  const dir = assetsDir(manifestDir);
  for (const name of REQUIRED_ASSETS) {
    if (!(await app.vault.adapter.exists(normalizePath(`${dir}/${name}`)))) return false;
  }
  return true;
}

async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Download and install the OCR assets. Verifies the checksum (when pinned), unzips into the plugin folder,
 * and returns when the required files are present. Throws with a clear message on any failure so the settings
 * UI can surface it.
 */
export async function downloadAssets(app: App, manifestDir: string, url = OCR_ASSETS_URL): Promise<void> {
  const res = await requestUrl({ url });
  if (res.status < 200 || res.status >= 300) throw new Error(`Download failed (HTTP ${res.status}).`);
  const buf = res.arrayBuffer;

  if (OCR_ASSETS_SHA256 !== "") {
    const actual = await sha256Hex(buf);
    if (actual !== OCR_ASSETS_SHA256.toLowerCase()) {
      throw new Error("Checksum mismatch — the downloaded OCR assets don't match the expected version; not installing.");
    }
  }

  const files = unzipSync(new Uint8Array(buf));
  const dir = assetsDir(manifestDir);
  if (!(await app.vault.adapter.exists(dir))) await app.vault.adapter.mkdir(dir);
  for (const [name, data] of Object.entries(files)) {
    if (name.endsWith("/") || data.length === 0) continue;
    const flat = name.split("/").pop() ?? name; // flatten any nested zip dirs
    await app.vault.adapter.writeBinary(normalizePath(`${dir}/${flat}`), toArrayBuffer(data));
  }
  if (!(await assetsPresent(app, manifestDir))) {
    throw new Error("The OCR asset bundle is missing required files (worker / core / language model).");
  }
}

function toArrayBuffer(u8: Uint8Array): ArrayBuffer {
  return u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) as ArrayBuffer;
}
