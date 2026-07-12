import { parseDateMs } from "./types/date";

/**
 * Inference uses *stricter* tests than the column types' lenient runtime
 * parsers. At runtime we want `parseNumber("42 pages") === 42`, but for
 * inference the whole value must look numeric, otherwise a date like
 * `2021-01-01` (whose leading digits parse as a number) would be misclassified.
 */
function isStrictNumber(value: string): boolean {
  return /^-?\d+(\.\d+)?$/.test(value.replace(/,/g, ""));
}

function looksLikeDate(value: string): boolean {
  return /[-/]/.test(value) && parseDateMs(value) !== null;
}

/**
 * Header-name hints, checked first. Ordered most-specific to least. This powers
 * zero-config onboarding ("scan a folder, suggest a schema") while remaining
 * overridable per column.
 */
const NAME_HINTS: ReadonlyArray<readonly [RegExp, string]> = [
  [/^(note|title|name|file)$/i, "link"],
  [/^(created|modified|updated|date|due|published|start|end|deadline)$/i, "date"],
  [/^(year)$/i, "number"],
  [/^(tags?|keywords?|topics?|labels?)$/i, "tags"],
  [/^(status|state|stage|priority|category|type|kind)$/i, "select"],
  [/^(rating|score|stars?)$/i, "rating"],
  [/^(done|complete|completed|checked|read)$/i, "checkbox"],
  [/^(url|link|source|website|href|doi)$/i, "url"],
  [/^(image|cover|figure|thumbnail|photo|screenshot)$/i, "image"],
  [/^(content|notes?|description|summary|abstract|body|comment)$/i, "markdown"],
];

/**
 * Infer a column type from the header name and (optionally) sample cell values.
 * Name hints win; otherwise the data decides, defaulting to text.
 */
export function inferColumnType(header: string, samples: readonly string[] = []): string {
  const name = header.trim();
  for (const [pattern, id] of NAME_HINTS) {
    if (pattern.test(name)) return id;
  }

  const values = samples.map((s) => String(s ?? "").trim()).filter((s) => s !== "");
  if (values.length === 0) return "text";

  if (values.every(isStrictNumber)) return "number";
  if (values.every((s) => /^(true|false)$/i.test(s))) return "checkbox"; // e.g. Excel TRUE/FALSE cells
  if (values.every(looksLikeDate)) return "date";
  if (values.every((s) => /^\[\[[^\]]+\]\]$/.test(s))) return "link";
  if (values.every((s) => /!\[\[.+\]\]|!\[[^\]]*\]\(.+\)/.test(s))) return "image";
  if (values.every((s) => /^(https?:\/\/|obsidian:\/\/)/i.test(s))) return "url";

  return "text";
}
