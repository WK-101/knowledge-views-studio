/**
 * The wire contract between the KVS plugin and its browser companion.
 *
 * The single source of truth for both sides. The plugin re-exports these and the extension imports them
 * directly, so a change to a shape is a compile error in whichever half hasn't kept up — which is the whole
 * reason the two live in one repository rather than drifting apart in two.
 */

export interface SchemaColumn {
  readonly name: string;
  readonly typeId: string;
  readonly role?: string;
  /** Present for choice columns: the vocabulary this column already uses. */
  readonly options?: readonly string[];
}

export interface SchemaView {
  readonly id: string;
  readonly name: string;
  readonly columns: readonly SchemaColumn[];
  /** Whether this view can currently receive a capture, and in what shape. */
  readonly capture: { readonly writable: boolean; readonly shape?: "row" | "note"; readonly reason?: string };
}

export interface SchemaResponse {
  readonly vault: string;
  readonly protocol: number;
  readonly views: readonly SchemaView[];
}

export interface LookupRequest {
  readonly url?: string;
  readonly doi?: string;
  readonly viewIds?: readonly string[];
}

export interface LookupMatch {
  readonly viewId: string;
  readonly viewName: string;
  readonly on: string;
  readonly title: string;
  readonly filePath: string;
}

export interface CaptureRequest {
  readonly viewId: string;
  readonly fields: readonly { readonly key: string; readonly value: string }[];
  readonly url?: string;
}

export interface CaptureResponse {
  readonly ok: boolean;
  readonly path?: string;
  readonly createdTable?: boolean;
  readonly duplicate?: { readonly on: string; readonly filePath: string };
  readonly unmapped?: readonly string[];
  readonly reason?: string;
}

/** Protocol version. Bumped when a wire shape changes incompatibly, so the extension can refuse politely. */
export const BRIDGE_PROTOCOL = 1;
