#!/usr/bin/env node
/**
 * Build the OCR asset bundle (ocr-assets.zip) that the plugin downloads once and then runs entirely offline.
 *
 * The bundle contains tesseract's worker + WASM core (from the installed tesseract.js package) and the
 * language models you want to ship. Attach the resulting ocr-assets.zip to a GitHub release, then point
 * OCR_ASSETS_URL (src/services/search/ocr/assets.ts) at it and paste the printed SHA-256 into
 * OCR_ASSETS_SHA256 so the download is verified.
 *
 * Usage:
 *   node scripts/build-ocr-assets.mjs            # eng only
 *   node scripts/build-ocr-assets.mjs eng deu    # English + German
 *
 * Language models are fetched from the tessdata_fast repository (fast LSTM models, ~1–2 MB each).
 */
import { createWriteStream } from "node:fs";
import { readFile, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { zipSync } from "fflate";

const langs = process.argv.slice(2).length ? process.argv.slice(2) : ["eng"];
const TESSDATA = (lang) => `https://github.com/tesseract-ocr/tessdata_fast/raw/main/${lang}.traineddata`;

async function get(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Fetch failed (${res.status}): ${url}`);
  return new Uint8Array(await res.arrayBuffer());
}

async function main() {
  const files = {};

  // Tesseract runtime: worker + simd LSTM core, straight from the installed package.
  const pkg = join(process.cwd(), "node_modules", "tesseract.js");
  const core = join(process.cwd(), "node_modules", "tesseract.js-core");
  files["worker.min.js"] = new Uint8Array(await readFile(join(pkg, "dist", "worker.min.js")));
  files["tesseract-core-simd-lstm.wasm.js"] = new Uint8Array(await readFile(join(core, "tesseract-core-simd-lstm.wasm.js")));

  // Language models: gzip them, since tesseract is configured with gzip:true.
  const { gzipSync } = await import("fflate");
  for (const lang of langs) {
    const data = await get(TESSDATA(lang));
    files[`${lang}.traineddata.gz`] = gzipSync(data);
    console.log(`  + ${lang}.traineddata (${(data.length / 1e6).toFixed(1)} MB)`);
  }

  const zip = zipSync(files, { level: 6 });
  const out = join(process.cwd(), "ocr-assets.zip");
  await new Promise((resolve, reject) => {
    const s = createWriteStream(out);
    s.on("finish", resolve);
    s.on("error", reject);
    s.end(Buffer.from(zip));
  });
  const sha = createHash("sha256").update(zip).digest("hex");
  await writeFile(join(process.cwd(), "ocr-assets.sha256.txt"), sha + "\n");
  console.log(`\nWrote ocr-assets.zip (${(zip.length / 1e6).toFixed(1)} MB)`);
  console.log(`SHA-256: ${sha}`);
  console.log("\nNext: attach ocr-assets.zip to a release, then set OCR_ASSETS_URL + OCR_ASSETS_SHA256 in src/services/search/ocr/assets.ts");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
