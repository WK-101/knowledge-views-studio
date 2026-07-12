import { type App, type EventRef, TAbstractFile, TFile } from "obsidian";
import type {
  Unsubscribe,
  VaultChangeEvent,
  VaultChangeKind,
  VaultFileRef,
  VaultGateway,
} from "../services/ports/vault-gateway";

function toRef(file: TFile): VaultFileRef {
  const parentPath = file.parent ? file.parent.path : "";
  return {
    path: file.path,
    basename: file.basename,
    folder: parentPath === "/" ? "" : parentPath,
    createdMs: file.stat.ctime,
    modifiedMs: file.stat.mtime,
    sizeBytes: file.stat.size,
  };
}

/**
 * The concrete VaultGateway backed by Obsidian. Vault events are registered once
 * (via the plugin's `registerEvent`, so they are cleaned up on unload) and then
 * fanned out to subscribers. This is the only adapter the services need; the
 * rest of the codebase never imports `obsidian`.
 */
export class ObsidianVaultGateway implements VaultGateway {
  private readonly listeners = new Set<(event: VaultChangeEvent) => void>();

  constructor(
    private readonly app: App,
    register: (ref: EventRef) => void,
    /** Which extensions to emit change events for; read dynamically so a settings toggle is live. */
    private readonly watchedExtensions: () => readonly string[] = () => ["md"],
  ) {
    const { vault } = app;
    register(vault.on("create", (file) => this.dispatch("create", file)));
    register(vault.on("modify", (file) => this.dispatch("modify", file)));
    register(vault.on("delete", (file) => this.dispatch("delete", file)));
    register(vault.on("rename", (file, oldPath) => this.dispatch("rename", file, oldPath)));
  }

  listMarkdownFiles(): VaultFileRef[] {
    return this.app.vault.getMarkdownFiles().map(toRef);
  }

  listFilesByExtension(exts: readonly string[]): VaultFileRef[] {
    const want = new Set(exts.map((e) => e.toLowerCase()));
    return this.app.vault
      .getFiles()
      .filter((f) => want.has(f.extension.toLowerCase()))
      .map(toRef);
  }

  async read(path: string): Promise<string> {
    const file = this.app.vault.getAbstractFileByPath(path);
    return file instanceof TFile ? this.app.vault.cachedRead(file) : "";
  }

  async readBinary(path: string): Promise<ArrayBuffer> {
    const file = this.app.vault.getAbstractFileByPath(path);
    return file instanceof TFile ? this.app.vault.readBinary(file) : new ArrayBuffer(0);
  }

  async process(path: string, transform: (content: string) => string): Promise<void> {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (file instanceof TFile) await this.app.vault.process(file, transform);
  }

  async processBinary(path: string, transform: (bytes: Uint8Array) => Uint8Array): Promise<void> {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) return;
    const current = new Uint8Array(await this.app.vault.readBinary(file));
    const next = transform(current);
    await this.app.vault.modifyBinary(file, next.buffer.slice(next.byteOffset, next.byteOffset + next.byteLength) as ArrayBuffer);
  }

  async exists(path: string): Promise<boolean> {
    return this.app.vault.getAbstractFileByPath(path) != null;
  }

  async ensureFolder(path: string): Promise<void> {
    if (this.app.vault.getAbstractFileByPath(path) == null) {
      try {
        await this.app.vault.createFolder(path);
      } catch {
        // A concurrent create or an existing folder — safe to ignore.
      }
    }
  }

  async writeBinary(path: string, bytes: Uint8Array): Promise<void> {
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const existing = this.app.vault.getAbstractFileByPath(path);
    if (existing instanceof TFile) await this.app.vault.modifyBinary(existing, buffer);
    else await this.app.vault.createBinary(path, buffer);
  }

  onChange(listener: (event: VaultChangeEvent) => void): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private dispatch(kind: VaultChangeKind, file: TAbstractFile, oldPath?: string): void {
    if (!(file instanceof TFile)) return;
    const watched = this.watchedExtensions();
    if (!watched.includes(file.extension.toLowerCase())) return;
    const event: VaultChangeEvent =
      oldPath !== undefined ? { kind, path: file.path, oldPath } : { kind, path: file.path };
    for (const listener of [...this.listeners]) listener(event);
  }
}
