/**
 * Turning a web page into capture fields.
 *
 * Deliberately free of the DOM. The content script does the reading — meta tags, JSON-LD blocks, the
 * selection — and hands the results here as plain data; everything that *decides* anything happens in this
 * file. That split is what makes page interpretation testable at all, since the alternative is asserting
 * against a headless browser for logic that is really just string handling.
 *
 * It also means the plugin can reuse this. A URL pasted into the in-app capture command and a page clipped
 * from the browser should produce the same fields, and they will, because it's the same code.
 */

export interface RawMeta {
  /** `name`, `property` or `itemprop` — whichever the tag used. */
  readonly key: string;
  readonly content: string;
}

export interface PageSnapshot {
  readonly url: string;
  readonly title?: string;
  readonly meta?: readonly RawMeta[];
  /** Parsed `application/ld+json` blocks. Anything unparseable should simply be omitted. */
  readonly jsonLd?: readonly unknown[];
  /** Text the user had selected, if any. */
  readonly selection?: string;
  /** First paragraph or summary, when the page offers one. */
  readonly excerpt?: string;
}

export interface ExtractedField {
  readonly key: string;
  readonly value: string;
}

/** Meta keys worth keeping, normalized to the alias vocabulary the plugin's mapper understands. */
const META_KEYS: Readonly<Record<string, string>> = {
  "og:title": "og:title",
  "og:description": "og:description",
  "og:url": "og:url",
  "og:site_name": "og:site_name",
  "og:image": "og:image",
  "article:published_time": "og:article:published_time",
  "article:author": "og:article:author",
  "twitter:title": "twitter:title",
  "twitter:description": "twitter:description",
  description: "description",
  author: "author",
  keywords: "keywords",
  "citation_title": "citation_title",
  "citation_author": "citation_author",
  "citation_doi": "citation_doi",
  "citation_publication_date": "citation_publication_date",
  "dc.title": "dc.title",
  "dc.creator": "dc.creator",
  "dc.date": "dc.date",
  "dc.description": "dc.description",
};

/** Schema.org properties worth lifting, mapped to the same vocabulary. */
const SCHEMA_KEYS: Readonly<Record<string, string>> = {
  name: "schema:name",
  headline: "schema:headline",
  description: "schema:description",
  datePublished: "schema:datepublished",
  author: "schema:author",
  creator: "schema:author",
  keywords: "schema:keywords",
  publisher: "schema:publisher",
  isbn: "schema:isbn",
  url: "schema:url",
  image: "schema:image",
  doi: "schema:doi",
  ratingValue: "schema:ratingvalue",
  price: "schema:price",
};

function clean(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

/**
 * Flatten whatever Schema.org gives us into a string.
 *
 * Real pages are inconsistent about this: an author can be a string, an object with a name, or a list of
 * either, and all three mean the same thing to a reader. Rather than handle only the tidy case, each shape
 * collapses to the text a person would have written.
 */
function schemaValue(raw: unknown): string {
  if (typeof raw === "string") return clean(raw);
  if (typeof raw === "number" || typeof raw === "boolean") return String(raw);
  if (Array.isArray(raw)) {
    return raw.map(schemaValue).filter((v) => v !== "").join(", ");
  }
  if (raw !== null && typeof raw === "object") {
    const record = raw as Record<string, unknown>;
    const name = record["name"];
    if (typeof name === "string") return clean(name);
  }
  return "";
}

/** Walk a JSON-LD block, including `@graph` containers, yielding each node that looks like an entity. */
function jsonLdNodes(block: unknown, depth = 0): Record<string, unknown>[] {
  if (depth > 4 || block === null || typeof block !== "object") return [];
  if (Array.isArray(block)) return block.flatMap((b) => jsonLdNodes(b, depth + 1));
  const record = block as Record<string, unknown>;
  const graph = record["@graph"];
  if (graph !== undefined) return jsonLdNodes(graph, depth + 1);
  return [record];
}

/**
 * Read a page snapshot into capture fields.
 *
 * Sources are added in ascending order of trust, and a key already present is never replaced — so an
 * explicit Schema.org `datePublished` beats a generic meta tag, and a user's own selection beats both. The
 * mapper downstream then decides which column each field belongs to; nothing here assumes a schema.
 */
export function extractFields(page: PageSnapshot): ExtractedField[] {
  const fields: ExtractedField[] = [];
  const seen = new Set<string>();
  const add = (key: string, value: string): void => {
    const text = clean(value);
    if (text === "" || seen.has(key)) return;
    seen.add(key);
    fields.push({ key, value: text });
  };

  // The user's selection is the most deliberate signal on the page: they chose it.
  if (page.selection !== undefined && clean(page.selection) !== "") {
    add("description", page.selection);
  }

  for (const block of page.jsonLd ?? []) {
    for (const node of jsonLdNodes(block)) {
      for (const [property, alias] of Object.entries(SCHEMA_KEYS)) {
        const value = schemaValue(node[property]);
        if (value !== "") add(alias, value);
      }
    }
  }

  for (const tag of page.meta ?? []) {
    const alias = META_KEYS[tag.key.toLowerCase()];
    if (alias !== undefined) add(alias, tag.content);
  }

  if (page.excerpt !== undefined) add("description", page.excerpt);
  if (page.title !== undefined) add("title", page.title);
  add("url", page.url);

  return fields;
}

/** A DOI found anywhere in the page's fields or URL, normalized. Useful for the duplicate check. */
export function findDoi(page: PageSnapshot, fields: readonly ExtractedField[]): string | null {
  const pattern = /\b10\.\d{4,9}\/[^\s"'<>]+/i;
  const haystacks = [page.url, ...fields.map((f) => f.value)];
  for (const text of haystacks) {
    const match = pattern.exec(text);
    if (match) return match[0].replace(/[.,;)\]]+$/, "");
  }
  return null;
}
