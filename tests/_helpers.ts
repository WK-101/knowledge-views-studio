import type { Row, SourceFileMeta } from "../src/domain/model";

export function makeRow(
  cells: Record<string, string>,
  opts: { fileName?: string; folder?: string; created?: string; modified?: string } = {},
): Row {
  const fileName = opts.fileName ?? "Note";
  const folder = opts.folder ?? "Notes";
  const file: SourceFileMeta = {
    filePath: `${folder}/${fileName}.md`,
    fileName,
    folderPath: folder,
    createdMs: Date.parse(opts.created ?? "2020-01-01"),
    modifiedMs: Date.parse(opts.modified ?? "2020-06-01"),
    sizeBytes: 0,
  };
  return {
    cells,
    file,
    provenance: { filePath: file.filePath, extractor: "test", locator: {}, fingerprint: "x" },
  };
}

/** Fixed clock so `daysSince(...)` is deterministic in tests. */
export const NOW = Date.parse("2021-01-11T00:00:00Z");

import type {
  Unsubscribe,
  VaultChangeEvent,
  VaultFileRef,
  VaultGateway,
} from "../src/services/ports/vault-gateway";

/** In-memory VaultGateway for testing the services without Obsidian. */
export class FakeVaultGateway implements VaultGateway {
  private readonly files = new Map<string, { content: string; modifiedMs: number; createdMs: number }>();
  private readonly binaries = new Map<string, { bytes: Uint8Array; modifiedMs: number; createdMs: number }>();
  private readonly folders = new Set<string>();
  private readonly listeners = new Set<(event: VaultChangeEvent) => void>();
  reads = 0;

  setFile(path: string, content: string, modifiedMs = 1): void {
    const existing = this.files.get(path);
    this.files.set(path, { content, modifiedMs, createdMs: existing?.createdMs ?? 0 });
  }

  setBinary(path: string, bytes: Uint8Array, modifiedMs = 1): void {
    const existing = this.binaries.get(path);
    this.binaries.set(path, { bytes, modifiedMs, createdMs: existing?.createdMs ?? 0 });
  }

  deleteFile(path: string): void {
    this.files.delete(path);
    this.binaries.delete(path);
  }

  listMarkdownFiles(): VaultFileRef[] {
    return [...this.files.entries()].map(([path, f]) => ({
      path,
      basename: path.replace(/^.*\//, "").replace(/\.md$/, ""),
      folder: path.includes("/") ? path.replace(/\/[^/]*$/, "") : "",
      createdMs: f.createdMs,
      modifiedMs: f.modifiedMs,
      sizeBytes: f.content.length,
    }));
  }

  listFilesByExtension(exts: readonly string[]): VaultFileRef[] {
    const want = new Set(exts.map((e) => e.toLowerCase()));
    return [...this.binaries.entries()]
      .filter(([path]) => want.has((path.split(".").pop() ?? "").toLowerCase()))
      .map(([path, f]) => ({
        path,
        basename: path.replace(/^.*\//, "").replace(/\.[^.]*$/, ""),
        folder: path.includes("/") ? path.replace(/\/[^/]*$/, "") : "",
        createdMs: f.createdMs,
        modifiedMs: f.modifiedMs,
        sizeBytes: f.bytes.byteLength,
      }));
  }

  async read(path: string): Promise<string> {
    this.reads++;
    return this.files.get(path)?.content ?? "";
  }

  async readBinary(path: string): Promise<ArrayBuffer> {
    this.reads++;
    const bytes = this.binaries.get(path)?.bytes;
    if (!bytes) return new ArrayBuffer(0);
    const buf = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(buf).set(bytes);
    return buf;
  }

  async process(path: string, transform: (content: string) => string): Promise<void> {
    const existing = this.files.get(path);
    if (!existing) return;
    const next = transform(existing.content);
    this.files.set(path, { ...existing, content: next, modifiedMs: existing.modifiedMs + 1 });
  }

  async processBinary(path: string, transform: (bytes: Uint8Array) => Uint8Array): Promise<void> {
    const existing = this.binaries.get(path);
    if (!existing) return;
    const next = transform(existing.bytes);
    this.binaries.set(path, { ...existing, bytes: next, modifiedMs: existing.modifiedMs + 1 });
  }

  async exists(path: string): Promise<boolean> {
    return this.files.has(path) || this.binaries.has(path) || this.folders.has(path);
  }

  async ensureFolder(path: string): Promise<void> {
    this.folders.add(path);
  }

  async writeBinary(path: string, bytes: Uint8Array): Promise<void> {
    this.binaries.set(path, { bytes, modifiedMs: 1, createdMs: 0 });
  }

  onChange(listener: (event: VaultChangeEvent) => void): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit(event: VaultChangeEvent): void {
    for (const listener of [...this.listeners]) listener(event);
  }
}
