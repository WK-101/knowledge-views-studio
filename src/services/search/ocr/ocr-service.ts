import { createWorker, OEM, type Worker } from "tesseract.js";
import type { App } from "obsidian";
import { assetUrl } from "./assets";

/**
 * One persistent tesseract.js worker, created lazily on the first job and re-created when the language set
 * changes. Every runtime file — worker, WASM core, language models — loads from the plugin's local assets
 * folder via `app://` URLs, so there is no network access at recognition time. This module is imported
 * dynamically (never at plugin load) because tesseract is desktop-only and heavy; keeping it out of the load
 * path was a deliberate mobile-safety choice.
 */
export class OcrService {
  private worker: Worker | null = null;
  private workerLangs = "";

  constructor(
    private readonly app: App,
    private readonly manifestDir: string,
  ) {}

  async recognize(imageUrl: string, langs: string): Promise<string> {
    const worker = await this.getWorker(langs);
    const result = await worker.recognize(imageUrl);
    return result.data.text.trim();
  }

  async terminate(): Promise<void> {
    const worker = this.worker;
    this.worker = null;
    this.workerLangs = "";
    if (worker) await worker.terminate().catch(() => undefined);
  }

  private async getWorker(langs: string): Promise<Worker> {
    if (this.worker && this.workerLangs === langs) return this.worker;
    await this.terminate();
    this.worker = await createWorker(langs.split("+"), OEM.LSTM_ONLY, {
      workerPath: assetUrl(this.app, this.manifestDir, "worker.min.js"),
      corePath: assetUrl(this.app, this.manifestDir, "tesseract-core-simd-lstm.wasm.js"),
      // tesseract appends `/${lang}.traineddata.gz` to langPath itself.
      langPath: assetUrl(this.app, this.manifestDir, "").replace(/\/$/, ""),
      gzip: true,
      cacheMethod: "none", // we run our own synced cache; keep tesseract's IndexedDB out of it
    });
    this.workerLangs = langs;
    return this.worker;
  }
}
