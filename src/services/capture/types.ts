/**
 * Capture: turning something from outside the vault into a row or a note.
 *
 * The shape here is deliberately transport-agnostic. A payload is just named fields plus a source, so the
 * same pipeline serves an in-app command today and the browser companion later without either knowing about
 * the other. Everything that decides *meaning* — which field belongs in which column, how a date written in
 * another locale should be stored — happens here, once, rather than in each transport.
 */

export type CaptureShape = "row" | "note";

/**
 * The recurring notes a row can be captured into. These mirror the three periods every daily-notes workflow
 * settles on; deliberately a closed set so the path/format logic stays a lookup rather than open-ended.
 */
export type PeriodicKind = "daily" | "weekly" | "monthly";

/**
 * How to address a periodic note (today's daily note, this week's, this month's).
 *
 * Everything is optional so a view can say "daily" and inherit the rest from the vault's own daily-notes
 * configuration — the point is to write into the *same* file the user's Periodic Notes / core Daily Notes
 * setup would, never a parallel duplicate. When a field is set it overrides that inheritance.
 */
export interface PeriodicTarget {
  /** Which recurrence. Defaults to "daily" when the destination is periodic but this is unset. */
  readonly period?: PeriodicKind;
  /** moment format string for the file name (e.g. "YYYY-MM-DD"). Empty = the period's default. */
  readonly format?: string;
  /** Folder the periodic note lives in. Empty = vault root. */
  readonly folder?: string;
  /** Vault-relative path to a template file used when the note has to be created. Empty = a bare note. */
  readonly template?: string;
}

/** Where a view's captures land. Unset falls back to the older `newRowFile` behaviour. */
export interface CaptureTarget {
  readonly shape: CaptureShape;
  /**
   * shape "row": whether rows go to a single fixed note or into a recurring (daily/weekly/monthly) note
   * resolved fresh each time. Unset = "file" (the original behaviour, a single `notePath`).
   */
  readonly destination?: "file" | "periodic";
  /** shape "row" + destination "periodic": how the recurring note is addressed. */
  readonly periodic?: PeriodicTarget;
  /** shape "row": the note whose table receives captured rows. */
  readonly notePath?: string;
  /** shape "row": the table under this heading. Empty = the note's first table. */
  readonly heading?: string;
  /** shape "row": write the table (headers from the view's columns) when it isn't there yet. */
  readonly createIfMissing?: boolean;
  /** shape "note": folder for captured notes. Empty = vault root. */
  readonly folder?: string;
  /**
   * shape "note": how the note is written. Uses the shared template syntax, which follows Obsidian Web
   * Clipper's, so a template written for that tool works here. Empty = a sensible default.
   */
  readonly noteTemplate?: string;
  /** shape "note": how the file is named. Same syntax. */
  readonly fileNameTemplate?: string;
}

/** One extracted field, before it's been matched to a column. Keys are whatever the source called them. */
export interface CaptureField {
  readonly key: string;
  readonly value: string;
}

export interface CapturePayload {
  readonly fields: readonly CaptureField[];
  /** Note-shaped capture: the body the caller extracted, and what to call the file. */
  readonly note?: {
    readonly fileName?: string;
    readonly body?: string;
    readonly template?: string;
    /** Append into this note (under a heading when given) rather than creating a new one. */
    readonly appendTo?: {
      readonly path?: string;
      readonly heading?: string;
      readonly createHeading?: boolean;
    };
  };
  /** Save as this shape regardless of how the view is configured. */
  readonly shape?: "row" | "note";
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
