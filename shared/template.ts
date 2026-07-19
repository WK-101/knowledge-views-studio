/**
 * Templates for captured notes.
 *
 * The syntax follows Obsidian Web Clipper — `{{title}}`, `{{content|markdown}}`, `{{date|date:"YYYY-MM-DD"}}`
 * — so anyone arriving with templates already written can bring them, and anything they've read about
 * clipping still applies. That compatibility is a starting point rather than a ceiling: filters live in a
 * registry, so KVS-specific ones sit alongside the familiar names without either having to know about the
 * other.
 *
 * Living in `shared/` is what makes the preview honest. The plugin renders the template when it writes the
 * note, and the extension renders the same template with the same code to show what you'll get — so a
 * preview can't drift from the result.
 *
 * Rendering never throws. A template is something a person edits by hand, usually while looking at a page
 * they want to keep, and failing the capture over a stray brace would lose the thing they were trying to
 * save. Unknown variables resolve to nothing and unknown filters pass their input through.
 */

export type TemplateFilter = (input: string, argument?: string) => string;

/** Strip characters a vault path can't hold, without destroying non-Latin titles. */
export function safeName(raw: string): string {
  const cleaned = raw
    .replace(/[\\/:*?"<>|#^[\]]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return cleaned === "" ? "Untitled" : cleaned.slice(0, 100);
}

function pad(value: number, width = 2): string {
  return String(value).padStart(width, "0");
}

/**
 * Format a date with the tokens people already expect from clipping tools.
 *
 * Deliberately a small, explicit set rather than a date library: these six tokens cover every filename and
 * frontmatter pattern anyone actually writes, and a template that silently accepted a token it then ignored
 * would be worse than one that never offered it.
 */
export function formatDate(value: string, pattern = "YYYY-MM-DD"): string {
  const date = value.trim() === "" ? new Date() : new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts: Record<string, string> = {
    YYYY: String(date.getFullYear()),
    MM: pad(date.getMonth() + 1),
    DD: pad(date.getDate()),
    HH: pad(date.getHours()),
    mm: pad(date.getMinutes()),
    ss: pad(date.getSeconds()),
  };
  return pattern.replace(/YYYY|MM|DD|HH|mm|ss/g, (token) => parts[token] ?? token);
}

/** Split on the separators a list of authors or keywords actually arrives with. */
function splitList(input: string, separator?: string): string[] {
  const pattern = separator === undefined || separator === "" ? /[,;、]/ : separator;
  return input
    .split(pattern)
    .map((part) => part.trim())
    .filter((part) => part !== "");
}

/**
 * The filter registry.
 *
 * Names on the left of the divide are Web Clipper's, kept so existing templates work. Below them are ones
 * that only make sense here, where a capture becomes a row in a view rather than a file in a folder.
 */
export const FILTERS: Readonly<Record<string, TemplateFilter>> = {
  // ---- Web Clipper compatible ----
  upper: (input) => input.toUpperCase(),
  lower: (input) => input.toLowerCase(),
  capitalize: (input) => (input === "" ? "" : input[0]!.toUpperCase() + input.slice(1)),
  title: (input) =>
    input.replace(/\w\S*/g, (word) => word[0]!.toUpperCase() + word.slice(1).toLowerCase()),
  trim: (input) => input.trim(),
  truncate: (input, argument) => {
    const limit = Number(argument ?? 100);
    if (!Number.isFinite(limit) || limit <= 0 || input.length <= limit) return input;
    return `${input.slice(0, limit).trimEnd()}…`;
  },
  replace: (input, argument) => {
    // replace:"from","to"
    const match = /^\s*"([^"]*)"\s*,\s*"([^"]*)"\s*$/.exec(argument ?? "");
    if (!match) return input;
    return input.split(match[1] ?? "").join(match[2] ?? "");
  },
  split: (input, argument) => splitList(input, argument).join("\n"),
  join: (input, argument) => splitList(input, "\n").join(argument ?? ", "),
  list: (input, argument) => splitList(input, argument).map((item) => `- ${item}`).join("\n"),
  date: (input, argument) => formatDate(input, argument?.replace(/^"|"$/g, "") ?? "YYYY-MM-DD"),
  safe_name: (input) => safeName(input),
  slug: (input) =>
    input
      .normalize("NFKD")
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, "-")
      .replace(/^-+|-+$/g, ""),
  blockquote: (input) =>
    input
      .split(/\r?\n/)
      .map((line) => `> ${line}`)
      .join("\n"),
  first: (input, argument) => splitList(input, argument)[0] ?? "",
  length: (input) => String(input.length),

  // ---- KVS's own ----
  /** An Obsidian link, so a captured field can point at a note instead of merely naming it. */
  wikilink: (input) => (input.trim() === "" ? "" : `[[${safeName(input)}]]`),
  /** Turn a value into tags, which is how a captured keyword list usually wants to land. */
  tags: (input, argument) =>
    splitList(input, argument)
      .map((tag) => `#${tag.replace(/\s+/g, "-").replace(/^#/, "")}`)
      .join(" "),
  /** Quote for YAML when a value would otherwise break frontmatter. */
  yaml: (input) => {
    const value = input.replace(/\r?\n/g, " ").trim();
    if (value === "") return '""';
    if (/^[-?:#&*!|>%@`[{]/.test(value) || value.includes(": ") || value.endsWith(":")) {
      return `"${value.replace(/"/g, '\\"')}"`;
    }
    return value;
  },
  /** Strip Markdown down to plain text, for a description that shouldn't carry formatting. */
  plain: (input) =>
    input
      .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/[*_`>#]/g, "")
      .replace(/\s+/g, " ")
      .trim(),
};

/** Apply one `name:argument` step. An unknown filter passes its input through rather than failing. */
function applyFilter(
  input: string,
  spec: string,
  filters: Readonly<Record<string, TemplateFilter>>,
): string {
  const trimmed = spec.trim();
  if (trimmed === "") return input;
  const at = trimmed.indexOf(":");
  const name = (at < 0 ? trimmed : trimmed.slice(0, at)).trim();
  const argument = at < 0 ? undefined : trimmed.slice(at + 1).trim();
  const filter = filters[name];
  if (filter === undefined) return input;
  try {
    return filter(input, argument);
  } catch {
    // A filter given something it can't handle shouldn't cost someone the capture.
    return input;
  }
}

/**
 * Split a template expression on `|`, ignoring pipes inside quotes.
 *
 * Needed because `replace:"a|b","c"` is legitimate and naive splitting would tear it in half.
 */
export function splitExpression(expression: string): string[] {
  const parts: string[] = [];
  let current = "";
  let inQuote = false;
  for (const char of expression) {
    if (char === '"') inQuote = !inQuote;
    if (char === "|" && !inQuote) {
      parts.push(current);
      current = "";
      continue;
    }
    current += char;
  }
  parts.push(current);
  return parts;
}

export interface RenderOptions {
  /** Extra filters for this render, merged over the standard ones. */
  readonly filters?: Readonly<Record<string, TemplateFilter>>;
}

/**
 * Render a template against a set of values.
 *
 * Variable lookup is case-insensitive and tolerant of spacing, because `{{ Title }}` and `{{title}}` are the
 * same intent and a template is written by hand.
 */
export function renderTemplate(
  template: string,
  values: Readonly<Record<string, string>>,
  options: RenderOptions = {},
): string {
  const lookup = new Map<string, string>();
  for (const [key, value] of Object.entries(values)) lookup.set(key.trim().toLowerCase(), value);

  // Resolve the filter set once. Custom filters override standard ones of the same name, which is what
  // makes the registry extensible without the standard set having to know they exist.
  const filters: Record<string, TemplateFilter> =
    options.filters === undefined ? { ...FILTERS } : { ...FILTERS, ...options.filters };

  return template.replace(/\{\{([^}]*)\}\}/g, (whole, expression: string) => {
    const parts = splitExpression(expression);
    const name = (parts.shift() ?? "").trim().toLowerCase();
    if (name === "") return whole;

    let value = lookup.get(name) ?? "";
    for (const step of parts) {
      value = applyFilter(value, step, filters);
    }
    return value;
  });
}

/** Every variable a template refers to, for showing what's available and what's unused. */
export function templateVariables(template: string): string[] {
  const found = new Set<string>();
  for (const match of template.matchAll(/\{\{([^}]*)\}\}/g)) {
    const name = splitExpression(match[1] ?? "")[0]?.trim().toLowerCase() ?? "";
    if (name !== "") found.add(name);
  }
  return [...found];
}

/** The variables a capture always provides, for documentation and the settings hint. */
export const STANDARD_VARIABLES: readonly string[] = [
  "title",
  "url",
  "domain",
  "author",
  "published",
  "description",
  "content",
  "selection",
  "date",
  "image",
  "tags",
];
