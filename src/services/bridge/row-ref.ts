import type { Row, RowProvenance } from "../../domain/index";
import { getField } from "../../domain/fields";

/**
 * Naming a row across the wire, safely.
 *
 * Editing something that already exists means the caller has to say *which* row — and the obvious way to do
 * that, sending the file path and position back, is exactly the way to get it wrong. A caller that can name
 * a location can name any location, which turns "update this row" into "write to this path". The token might
 * be authenticated, but a bug at either end becomes a write to somewhere nobody intended.
 *
 * So a reference here is opaque and, crucially, is never *dereferenced*. It's derived from the row's own
 * provenance, and on the way back in it is only ever **matched** against rows the vault itself produced. A
 * forged or stale reference matches nothing and the edit is refused. The plugin never learns a path from the
 * caller; it only recognises one it already knew.
 */

/** A small, stable, non-cryptographic digest — enough to tell rows apart, not a secret. */
function digest(input: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < input.length; i++) {
    const code = input.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 0x01000193) >>> 0;
    h2 = Math.imul(h2 + code + i, 0x85ebca6b) >>> 0;
  }
  return `${h1.toString(36)}${h2.toString(36)}`;
}

/**
 * A handle for one row.
 *
 * Built from everything that identifies where the row lives — file, extractor, position, and the
 * fingerprint of its content. Including the fingerprint is deliberate: if the row changes underneath, the
 * handle stops matching, and an edit made against a stale view of the data is refused rather than applied to
 * whatever now occupies that position.
 */
export function rowRefOf(provenance: RowProvenance): string {
  const locator = Object.entries(provenance.locator ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(",");
  return digest([provenance.filePath, provenance.extractor, locator, provenance.fingerprint].join("\u0000"));
}

/** Find the row a reference names, or null when nothing matches it any more. */
export function findRowByRef(rows: readonly Row[], rowRef: string): Row | null {
  const wanted = rowRef.trim();
  if (wanted === "") return null;
  return rows.find((row) => rowRefOf(row.provenance) === wanted) ?? null;
}

export interface EditableCheck {
  readonly allowed: readonly { readonly column: string; readonly value: string }[];
  readonly skipped: readonly { readonly column: string; readonly reason: string }[];
}

/**
 * Decide which of the requested changes may actually be written.
 *
 * `readOnlyFields` marks values a row doesn't own — a spreadsheet formula's result, a field owned by Zotero,
 * anything folded in from another source. In the app those are blocked by the editing surface itself, which
 * means nothing *below* that surface enforces it. A bridge that called the writer directly would sail
 * straight past the guard and overwrite a computed value with a literal, which is silent corruption of the
 * worst kind: the number still looks like a number.
 *
 * So the check is repeated here rather than assumed. Refusals are reported back with a reason instead of
 * being dropped quietly, because a caller that thinks it saved something is worse off than one told it
 * couldn't.
 */
export function editableChanges(
  row: Row,
  values: readonly { readonly key: string; readonly value: string; readonly mode?: "set" | "append" }[],
  columns: readonly { readonly name: string }[],
): EditableCheck {
  const known = new Map(columns.map((c) => [c.name.toLowerCase(), c.name]));
  const readOnly = new Set((row.provenance.readOnlyFields ?? []).map((f) => f.toLowerCase()));

  const allowed: { column: string; value: string }[] = [];
  const skipped: { column: string; reason: string }[] = [];

  for (const { key, value, mode } of values) {
    const name = known.get(key.trim().toLowerCase());
    if (name === undefined) {
      skipped.push({ column: key, reason: "This view has no such column." });
      continue;
    }
    if (readOnly.has(name.toLowerCase())) {
      skipped.push({ column: name, reason: "This value is computed or owned by another source." });
      continue;
    }
    if (mode === "append") {
      // The final value is composed HERE, against the row the vault just produced — not by the caller —
      // so appending can't be used to replay a stale copy of the cell over a newer one.
      const current = getField(row, name).trim();
      const addition = value.trim();
      if (addition === "") {
        skipped.push({ column: name, reason: "Nothing to append." });
        continue;
      }
      allowed.push({ column: name, value: current === "" ? addition : `${current}<br>${addition}` });
      continue;
    }
    allowed.push({ column: name, value });
  }
  return { allowed, skipped };
}
