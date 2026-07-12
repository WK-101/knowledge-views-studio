import type { ColumnType } from "../column-type";

export interface WikiLink {
  readonly target: string;
  readonly alias?: string;
}

/** Extract every `[[target|alias]]` (ignoring #section / ^block) from a string. */
export function parseWikiLinks(raw: string): WikiLink[] {
  const out: WikiLink[] = [];
  const re = /\[\[([^\]|#^]+)(?:[#^][^\]|]*)?(?:\|([^\]]+))?\]\]/g;
  const s = String(raw ?? "");
  let m: RegExpExecArray | null;
  while ((m = re.exec(s)) !== null) {
    const target = (m[1] ?? "").trim();
    if (target === "") continue;
    const alias = (m[2] ?? "").trim();
    out.push(alias ? { target, alias } : { target });
  }
  return out;
}

/** Resolve `[[Target|Alias]]` to the alias (or target); pass plain text through. */
export function linkTarget(raw: string): string {
  const s = String(raw ?? "").trim();
  const match = s.match(/^\[\[([^\]|]+)(?:\|([^\]]+))?\]\]$/);
  if (match) return String(match[2] ?? match[1] ?? "").trim();
  return s;
}

export const LINK: ColumnType = {
  id: "link",
  label: "Note link",
  operators: ["contains", "not-contains", "equals", "not-equals", "is-empty", "is-not-empty"],
  isEmpty: (raw) => linkTarget(raw) === "",
  toComparable: (raw) => ({ kind: "string", value: linkTarget(raw).toLowerCase() }),
  toPlainText: (raw) => linkTarget(raw),
  validate: () => null,
};
