import type { Row, SourceFileMeta } from "../../domain/index";
import { fnv1a } from "../../util/hash";

/** Marks a row as sourced from Obsidian Bases (frontmatter/file data), not an in-body table. */
export const BASES_EXTRACTOR_ID = "bases";

/**
 * A Bases entry already flattened to plain strings by the Obsidian-facing adapter.
 * Keeping this pure (no `obsidian` import) lets the row mapping be unit-tested.
 */
export interface ExtractedBasesEntry {
  readonly filePath: string;
  readonly fileName: string;
  readonly folderPath: string;
  readonly createdMs: number;
  readonly modifiedMs: number;
  readonly sizeBytes?: number;
  /** Position in the Bases result set, used as a stable locator. */
  readonly index: number;
  /** Property id -> stringified value. */
  readonly cells: Readonly<Record<string, string>>;
}

/**
 * Map Bases entries onto the single KVS `Row` model so the existing view cores can
 * render them. Each entry becomes one row (Bases is file-centric: one entry = one
 * file). Rows carry `extractor: "bases"`, which has no write-back target — Bases
 * data is rendered read-only, leaving in-body-table write-back to the table path.
 */
export function buildRowsFromBasesData(
  entries: readonly ExtractedBasesEntry[],
  propertyKeys: readonly string[],
): Row[] {
  return entries.map((entry) => {
    const file: SourceFileMeta = {
      filePath: entry.filePath,
      fileName: entry.fileName,
      folderPath: entry.folderPath,
      createdMs: entry.createdMs,
      modifiedMs: entry.modifiedMs,
      sizeBytes: entry.sizeBytes ?? 0,
    };
    const signature = propertyKeys.map((key) => entry.cells[key] ?? "").join("\u0001");
    return {
      cells: entry.cells,
      file,
      provenance: {
        filePath: entry.filePath,
        extractor: BASES_EXTRACTOR_ID,
        locator: { entryIndex: entry.index },
        fingerprint: fnv1a(signature),
      },
    } satisfies Row;
  });
}
