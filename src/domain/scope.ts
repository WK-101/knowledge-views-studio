/**
 * Folder-scope resolution. The legacy plugin had three overlapping modes
 * (full-vault / single-folder / multi-folder); a single folder is just a
 * folder list of length one, so this collapses to two modes.
 */
export type ScopeMode = "vault" | "folders" | "zotero";

export interface ScopeConfig {
  readonly mode: ScopeMode;
  readonly folders: readonly string[];
  readonly includeSubfolders: boolean;
  /** When mode is "zotero": limit to a Zotero collection (folder). Omitted means the whole library. */
  readonly zoteroCollectionKey?: string;
  /**
   * When mode is "zotero": limit to exactly these item keys — used when a dashboard is built from a
   * selection in the library view. Omitted means no key filter (all items, or the whole collection).
   */
  readonly zoteroItemKeys?: readonly string[];
}

export const DEFAULT_SCOPE: ScopeConfig = {
  mode: "vault",
  folders: [],
  includeSubfolders: true,
};

function normalizeFolder(folder: string): string {
  return String(folder ?? "")
    .replace(/^\/+|\/+$/g, "")
    .trim();
}

function parentFolder(filePath: string): string {
  const idx = filePath.lastIndexOf("/");
  return idx >= 0 ? filePath.slice(0, idx) : "";
}

export function fileInScope(filePath: string, scope: ScopeConfig): boolean {
  if (scope.mode === "vault") return true;

  const folders = scope.folders.map(normalizeFolder).filter((f) => f.length > 0);
  if (folders.length === 0) return true;

  const parent = parentFolder(filePath);
  return folders.some((folder) =>
    scope.includeSubfolders
      ? filePath === folder || filePath.startsWith(`${folder}/`)
      : parent === folder,
  );
}

export function filterPathsByScope(paths: readonly string[], scope: ScopeConfig): string[] {
  return paths.filter((p) => fileInScope(p, scope));
}
