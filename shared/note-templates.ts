/**
 * A library of named, reusable note templates.
 *
 * Until now a view's capture template was a single raw string tucked inside its `captureTarget`. That's fine
 * for one view but it can't be shared, named, or picked from — every view starts from a blank textarea, and
 * "the Academic paper layout" lives in one place and nowhere else. This module makes templates first-class:
 * named objects the settings own, that a view (or, later, a per-site rule) points to by id.
 *
 * It's shared, not plugin-only, because both halves speak about templates: the plugin authors the note, and
 * the companion will show the same names in its picker. Everything here is pure data + pure functions — the
 * rendering still goes through `shared/template.ts`, and the variables through `shared/note.ts`, so a named
 * template is just a stored body/filename that feeds the exact same engine an inline one does.
 */

export interface NoteTemplate {
  /** Stable id a `captureTarget.noteTemplateId` (or a rule) refers to. */
  readonly id: string;
  /** Human name shown in pickers and the library. */
  readonly name: string;
  /** The note body, in the same `{{variable|filter}}` syntax as an inline template. */
  readonly body: string;
  /** Optional file-name template. Empty = the caller's default (`{{title|safe_name|truncate:80}}`). */
  readonly filename?: string;
  /** One line describing what the template is for, shown under its name in the gallery. */
  readonly description?: string;
}

const DEFAULT_FILENAME = "{{title|safe_name|truncate:80}}";

/**
 * The starter gallery.
 *
 * Ships a handful of good templates so the library is never an empty, intimidating blank — WuCai's lesson:
 * novices want a starting point, not a syntax reference. Each uses only variables that resolve today
 * (`shared/note.ts`) and filters that exist (`shared/template.ts`), so pasting one and saving Just Works.
 * These are the seed of the user's library; once copied in they're theirs to edit or delete.
 */
export const STARTER_TEMPLATES: readonly NoteTemplate[] = [
  {
    id: "starter-article",
    name: "Article",
    description: "A web article: title, source, author, and the readable body.",
    filename: DEFAULT_FILENAME,
    body: [
      "---",
      "title: {{title|yaml}}",
      "source: {{url}}",
      "author: {{author|yaml}}",
      "published: {{published|date:\"YYYY-MM-DD\"}}",
      "captured: {{date|date:\"YYYY-MM-DD\"}}",
      "tags: [article]",
      "---",
      "",
      "> {{description|blockquote}}",
      "",
      "{{content}}",
    ].join("\n"),
  },
  {
    id: "starter-academic",
    name: "Academic paper",
    description: "A paper: authors, year, DOI, and abstract, ready for a literature note.",
    filename: "{{title|safe_name|truncate:100}}",
    body: [
      "---",
      "title: {{title|yaml}}",
      "authors: {{author|yaml}}",
      "year: {{published|date:\"YYYY\"}}",
      "source: {{url}}",
      "type: paper",
      "tags: [literature, unread]",
      "---",
      "",
      "## Abstract",
      "",
      "{{description}}",
      "",
      "## Notes",
      "",
      "{{content}}",
    ].join("\n"),
  },
  {
    id: "starter-recipe",
    name: "Recipe",
    description: "A recipe page: source, cover image, and the steps.",
    filename: DEFAULT_FILENAME,
    body: [
      "---",
      "title: {{title|yaml}}",
      "source: {{url}}",
      "site: {{domain}}",
      "tags: [recipe]",
      "---",
      "",
      "![cover]({{image}})",
      "",
      "{{content}}",
    ].join("\n"),
  },
  {
    id: "starter-youtube",
    name: "YouTube video",
    description: "A video: channel, link, thumbnail, and description.",
    filename: DEFAULT_FILENAME,
    body: [
      "---",
      "title: {{title|yaml}}",
      "channel: {{author|yaml}}",
      "source: {{url}}",
      "captured: {{date|date:\"YYYY-MM-DD\"}}",
      "tags: [video]",
      "---",
      "",
      "![thumbnail]({{image}})",
      "",
      "{{description}}",
    ].join("\n"),
  },
  {
    id: "starter-bookmark",
    name: "Bookmark",
    description: "A minimal saved link: title, source, and a one-line summary.",
    filename: DEFAULT_FILENAME,
    body: [
      "---",
      "title: {{title|yaml}}",
      "source: {{url}}",
      "site: {{domain}}",
      "captured: {{date|date:\"YYYY-MM-DD\"}}",
      "tags: [bookmark]",
      "---",
      "",
      "{{description}}",
    ].join("\n"),
  },
];

/** Read a string field off an untyped object, trimmed, or "". */
function str(raw: Record<string, unknown>, key: string): string {
  const v = raw[key];
  return typeof v === "string" ? v : "";
}

/**
 * Coerce one stored (untrusted) value into a NoteTemplate, or null.
 *
 * A template with no id or no name is unusable in a picker, so it's dropped rather than kept as a nameless
 * ghost. The body is allowed to be empty (a template that only sets a filename is legal); everything else is
 * normalized to a string.
 */
export function coerceNoteTemplate(raw: unknown): NoteTemplate | null {
  if (raw === null || typeof raw !== "object") return null;
  const obj = raw as Record<string, unknown>;
  const id = str(obj, "id").trim();
  const name = str(obj, "name").trim();
  if (id === "" || name === "") return null;
  const filename = str(obj, "filename").trim();
  const description = str(obj, "description").trim();
  return {
    id,
    name,
    body: str(obj, "body"),
    ...(filename !== "" ? { filename } : {}),
    ...(description !== "" ? { description } : {}),
  };
}

/** Coerce a stored list, dropping invalid entries and de-duplicating by id (first writer wins). */
export function normalizeNoteTemplates(raw: unknown): NoteTemplate[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: NoteTemplate[] = [];
  for (const item of raw) {
    const t = coerceNoteTemplate(item);
    if (t === null || seen.has(t.id)) continue;
    seen.add(t.id);
    out.push(t);
  }
  return out;
}

/** Find a template by id, or null. */
export function findNoteTemplate(templates: readonly NoteTemplate[], id: string): NoteTemplate | null {
  if (id.trim() === "") return null;
  return templates.find((t) => t.id === id) ?? null;
}
