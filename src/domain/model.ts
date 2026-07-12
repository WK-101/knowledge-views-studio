/**
 * The single internal data model. The legacy codebase carried two parallel row
 * shapes (CollectedRow + NormalizedRow) which silently diverged and caused the
 * interactive view to crash. Here there is exactly one row type, end to end.
 */

/** File-level metadata captured when a row is extracted. */
export interface SourceFileMeta {
  /** Vault-relative path, e.g. `Research/Papers/Smith 2021.md`. */
  readonly filePath: string;
  /** Base name without extension, e.g. `Smith 2021`. */
  readonly fileName: string;
  /** Parent folder path, e.g. `Research/Papers`. */
  readonly folderPath: string;
  readonly createdMs: number;
  readonly modifiedMs: number;
  readonly sizeBytes: number;
}

/**
 * Everything needed to find a row again in its source file for write-back.
 * `locator` is extractor-specific; for tables it is `{ tableIndex, rowIndex, line }`.
 * `fingerprint` is a content hash used to relocate the row if line numbers shift.
 */
export interface RowProvenance {
  readonly filePath: string;
  readonly extractor: string;
  readonly locator: Readonly<Record<string, number | string>>;
  readonly fingerprint: string;
  /** Field names whose cell can't be edited in place (e.g. Excel formula cells). Editing is blocked
   *  for these so a computed value is never silently overwritten with a literal. */
  readonly readOnlyFields?: readonly string[];
}

/** One extracted record: column name -> raw cell string, plus source metadata. */
export interface Row {
  /** Data columns, keyed by their source header (original casing preserved). */
  readonly cells: Readonly<Record<string, string>>;
  readonly file: SourceFileMeta;
  readonly provenance: RowProvenance;
  /**
   * The cells each source contributed, kept intact — set only when several sources were folded into
   * one row. `cells` is the merged view (the item row wins a clash); this preserves what every source
   * actually said, so a column can be bound to one specific source.
   */
  readonly bySource?: Readonly<Record<string, Readonly<Record<string, string>>>>;
}

export type Dataset = readonly Row[];
