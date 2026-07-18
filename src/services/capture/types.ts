/**
 * Capture: turning something from outside the vault into a row or a note.
 *
 * The shape here is deliberately transport-agnostic. A payload is just named fields plus a source, so the
 * same pipeline serves an in-app command today and the browser companion later without either knowing about
 * the other. Everything that decides *meaning* — which field belongs in which column, how a date written in
 * another locale should be stored — happens here, once, rather than in each transport.
 */

export type CaptureShape = "row" | "note";

/** Where a view's captures land. Unset falls back to the older `newRowFile` behaviour. */
export interface CaptureTarget {
  readonly shape: CaptureShape;
  /** shape "row": the note whose table receives captured rows. */
  readonly notePath?: string;
  /** shape "row": the table under this heading. Empty = the note's first table. */
  readonly heading?: string;
  /** shape "row": write the table (headers from the view's columns) when it isn't there yet. */
  readonly createIfMissing?: boolean;
  /** shape "note": folder for captured notes. Empty = vault root. */
  readonly folder?: string;
}

/** One extracted field, before it's been matched to a column. Keys are whatever the source called them. */
export interface CaptureField {
  readonly key: string;
  readonly value: string;
}

export interface CapturePayload {
  readonly fields: readonly CaptureField[];
  /** The page or file this came from, if any. Used for provenance and duplicate detection. */
  readonly url?: string;
  readonly capturedAt?: string;
}

export interface MappedCapture {
  /** Column name → value, already normalized for that column's type. */
  readonly values: Readonly<Record<string, string>>;
  /** Fields that found no column. Kept so the review step can offer them rather than dropping them. */
  readonly unmapped: readonly CaptureField[];
}

export interface CaptureResult {
  readonly ok: boolean;
  readonly reason?: string;
  /** Path written to. */
  readonly path?: string;
  /** True when the target table had to be created. */
  readonly createdTable?: boolean;
}

/**
 * The minimum a column has to tell capture about itself.
 *
 * Structural on purpose: the plugin has two column shapes — the configured `ColumnConfig` and the
 * `ResolvedColumn` a view actually renders — and capture wants whichever the caller has to hand without
 * dragging the view layer into this module.
 */
export interface CaptureColumn {
  readonly name: string;
  /** Column type id: text, number, date, checkbox, tags, url, select... */
  readonly typeId: string;
  readonly role?: string;
  readonly options?: readonly { readonly value: string }[];
  readonly defaultValue?: string;
}
