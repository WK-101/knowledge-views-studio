import type { PageSnapshot } from "./extract";

/**
 * Turning a page into the values a note template refers to.
 *
 * Shared rather than kept in the extension because both halves need it: the companion renders a preview
 * with these values, and the plugin renders the note it actually writes. One definition means the preview
 * cannot drift from the result.
 *
 * The names follow Obsidian Web Clipper's, so a template written for that tool resolves here instead of
 * quietly producing blanks.
 */

/** A starting template, in Web Clipper's syntax so an existing one can be pasted over it. */
export const DEFAULT_NOTE_TEMPLATE = [
  "---",
  "title: {{title|yaml}}",
  "source: {{url}}",
  "author: {{author|yaml}}",
  'captured: {{date|date:"YYYY-MM-DD"}}',
  "---",
  "",
  "{{content}}",
].join("\n");

export const DEFAULT_FILENAME_TEMPLATE = "{{title|safe_name|truncate:80}}";

/**
 * The values a template can refer to.
 *
 * Named to match what Web Clipper offers, so a template written for that tool resolves here too rather than
 * quietly producing blanks.
 */
export function noteVariables(page: PageSnapshot, body: string): Record<string, string> {
  const byKey = new Map<string, string>();
  for (const tag of page.meta ?? []) byKey.set(tag.key.toLowerCase(), tag.content);

  const domain = (() => {
    try {
      return new URL(page.url).hostname.replace(/^www\./, "");
    } catch {
      return "";
    }
  })();

  return {
    title: page.title ?? "",
    url: page.url,
    domain,
    author: page.article?.byline ?? byKey.get("author") ?? byKey.get("citation_author") ?? "",
    published: byKey.get("article:published_time") ?? byKey.get("citation_publication_date") ?? "",
    description: page.excerpt ?? byKey.get("og:description") ?? byKey.get("description") ?? "",
    content: body,
    selection: page.selectionMarkdown ?? page.selection ?? "",
    date: new Date().toISOString(),
    image: byKey.get("og:image") ?? "",
    tags: byKey.get("keywords") ?? "",
  };
}
