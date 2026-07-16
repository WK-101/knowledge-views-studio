import { normalizePath, type App } from "obsidian";

/**
 * The OCR result cache — a plain JSON file kept in the plugin's own folder so it *syncs* between devices
 * (unlike the search index, which is machine-local). The point: OCR is expensive, so recognizing an image
 * once on the desktop means the phone never has to. Entries are keyed by content signature (mtime + size +
 * languages), so editing an image re-runs it but merely syncing it does not.
 */
export interface OcrCacheEntry {
  mtime: number;
  size: number;
  langs: string;
  text: string;
}

export class OcrCache {
  private entries = new Map<string, OcrCacheEntry>();
  private dirty = false;
  private readonly path: string;

  constructor(
    private readonly app: App,
    manifestDir: string,
  ) {
    this.path = normalizePath(`${manifestDir}/ocr-cache.json`);
  }

  async load(): Promise<void> {
    try {
      if (!(await this.app.vault.adapter.exists(this.path))) return;
      const raw = await this.app.vault.adapter.read(this.path);
      const obj = JSON.parse(raw) as Record<string, OcrCacheEntry>;
      this.entries = new Map(Object.entries(obj));
    } catch {
      this.entries = new Map();
    }
  }

  async save(): Promise<void> {
    if (!this.dirty) return;
    try {
      const obj: Record<string, OcrCacheEntry> = {};
      for (const [k, v] of this.entries) obj[k] = v;
      await this.app.vault.adapter.write(this.path, JSON.stringify(obj));
      this.dirty = false;
    } catch {
      // A failed cache write is non-fatal — OCR just re-runs next session.
    }
  }

  /** Cached text if the signature still matches, else null. */
  get(path: string, mtime: number, size: number, langs: string): string | null {
    const e = this.entries.get(path);
    if (e && e.mtime === mtime && e.size === size && e.langs === langs) return e.text;
    return null;
  }

  set(path: string, entry: OcrCacheEntry): void {
    this.entries.set(path, entry);
    this.dirty = true;
  }

  remove(path: string): void {
    if (this.entries.delete(path)) this.dirty = true;
  }

  rename(oldPath: string, newPath: string): void {
    const e = this.entries.get(oldPath);
    if (e) {
      this.entries.delete(oldPath);
      this.entries.set(newPath, e);
      this.dirty = true;
    }
  }
}
