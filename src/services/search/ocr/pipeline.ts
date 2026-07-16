import { Notice, Platform, type App, type Plugin, type TFile } from "obsidian";
import { OcrQueue, idleScheduler } from "./ocr-queue";
import { OcrCache } from "./ocr-cache";
import { assetsPresent } from "./assets";
// Type-only: tesseract must not be evaluated at plugin load (desktop-only, heavy). Loaded lazily below.
import type { OcrService } from "./ocr-service";

/** Image extensions we attempt OCR on. */
export const OCR_IMAGE_EXTS = new Set(["png", "jpg", "jpeg", "webp", "bmp", "gif", "tiff", "tif"]);

/** Hard cap on extracted text per image — it's a low-weight helper field, not the note's body. */
const MAX_OCR_CHARS = 20_000;

/**
 * Background OCR for images: recognizes text in screenshots and photos in the browser's idle time, one file
 * at a time, and folds the result into the search index. Every result is written to a synced cache, so no
 * image is ever recognized twice — on any device. Entirely offline once the assets are installed; a no-op on
 * mobile and whenever OCR is switched off.
 */
export class OcrPipeline {
  private readonly queue = new OcrQueue(idleScheduler(window));
  private readonly cache: OcrCache;
  private service: OcrService | null = null;
  private warnedMissing = false;

  constructor(
    private readonly app: App,
    private readonly manifestDir: string,
    private readonly getLanguages: () => string[],
    private readonly isEnabled: () => boolean,
    /** Called with recognized text so the indexer can (re)index the image doc. */
    private readonly onText: (file: TFile, text: string) => void,
  ) {
    this.cache = new OcrCache(app, manifestDir);
  }

  async init(plugin: Plugin): Promise<void> {
    await this.cache.load();
    plugin.registerEvent(
      this.app.vault.on("delete", (file) => {
        this.cache.remove(file.path);
        this.queue.drop(file.path);
      }),
    );
    plugin.registerEvent(
      this.app.vault.on("rename", (file, oldPath) => {
        this.cache.rename(oldPath, file.path);
        this.queue.drop(oldPath);
      }),
    );
  }

  async destroy(): Promise<void> {
    this.queue.clear();
    await this.service?.terminate();
    this.service = null;
    await this.cache.save();
  }

  /** Is this a file OCR applies to right now (enabled, desktop, image extension)? */
  handles(file: TFile): boolean {
    return this.isEnabled() && Platform.isDesktop && OCR_IMAGE_EXTS.has(file.extension.toLowerCase());
  }

  /** Enqueue every eligible image (used when OCR is switched on). */
  scanVault(files: readonly TFile[], priority: "high" | "low" = "low"): void {
    for (const file of files) if (this.handles(file)) this.consider(file, priority);
  }

  /** Cache hit → index immediately; miss → queue an idle recognition job. */
  consider(file: TFile, priority: "high" | "low" = "low"): void {
    if (!this.handles(file)) return;
    const langs = this.getLanguages().join("+") || "eng";
    const cached = this.cache.get(file.path, file.stat.mtime, file.stat.size, langs);
    if (cached !== null) {
      if (cached.trim() !== "") this.onText(file, cached.slice(0, MAX_OCR_CHARS));
      return;
    }
    this.queue.push(
      file.path,
      async () => {
        if (!(await this.assetsReady())) return;
        const url = this.app.vault.getResourcePath(file);
        const service = await this.getService();
        const text = (await service.recognize(url, langs)).slice(0, MAX_OCR_CHARS);
        this.cache.set(file.path, { mtime: file.stat.mtime, size: file.stat.size, langs, text });
        if (text.trim() !== "") this.onText(file, text);
        await this.cache.save();
      },
      priority,
    );
  }

  private async getService(): Promise<OcrService> {
    if (!this.service) {
      const { OcrService: Service } = await import("./ocr-service");
      this.service = new Service(this.app, this.manifestDir);
    }
    return this.service;
  }

  private async assetsReady(): Promise<boolean> {
    const present = await assetsPresent(this.app, this.manifestDir);
    if (!present && !this.warnedMissing) {
      this.warnedMissing = true;
      new Notice("KVS: OCR is on but its assets aren't installed — download them from the KVS settings tab.", 8000);
    }
    return present;
  }
}
