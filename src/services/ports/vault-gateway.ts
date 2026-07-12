/**
 * The port between the application services and the host (Obsidian). Services
 * depend only on this narrow interface, never on `obsidian` directly, which is
 * what lets the whole services layer run under unit tests with a fake gateway.
 * The concrete Obsidian implementation lives in `src/obsidian/`.
 */
export interface VaultFileRef {
  readonly path: string;
  readonly basename: string;
  readonly folder: string;
  readonly createdMs: number;
  readonly modifiedMs: number;
  readonly sizeBytes: number;
}

export type VaultChangeKind = "create" | "modify" | "delete" | "rename";

export interface VaultChangeEvent {
  readonly kind: VaultChangeKind;
  readonly path: string;
  readonly oldPath?: string;
}

export type Unsubscribe = () => void;

export interface VaultGateway {
  /** All Markdown files in the vault, with the metadata extraction needs. */
  listMarkdownFiles(): VaultFileRef[];
  /** Files whose extension is in `exts` (lowercased, no dot), e.g. ["xlsx"]. */
  listFilesByExtension(exts: readonly string[]): VaultFileRef[];
  /** Read a file's current text (empty string if it cannot be read). */
  read(path: string): Promise<string>;
  /** Read a file's raw bytes (empty buffer if it cannot be read). For binary sources. */
  readBinary(path: string): Promise<ArrayBuffer>;
  /** Atomically transform a file's text (read + write under the host's lock). */
  process(path: string, transform: (content: string) => string): Promise<void>;
  /** Transform a file's raw bytes (read + write). For binary sources like `.xlsx`. */
  processBinary(path: string, transform: (bytes: Uint8Array) => Uint8Array): Promise<void>;
  /** Whether a file or folder exists at this path. */
  exists(path: string): Promise<boolean>;
  /** Create a folder (and parents) if it doesn't already exist. */
  ensureFolder(path: string): Promise<void>;
  /** Write bytes to a new or existing file (used for backups). */
  writeBinary(path: string, bytes: Uint8Array): Promise<void>;
  /** Subscribe to watched-file changes; returns an unsubscribe function. */
  onChange(listener: (event: VaultChangeEvent) => void): Unsubscribe;
}
