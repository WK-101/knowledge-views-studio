/**
 * How the copy menu turns a selection into text for the clipboard.
 *
 * Pure string functions, kept out of the content script so they can be tested directly: the page URL is a
 * parameter, not read from `location`, so a test can pin it. Three formats, matching the menu:
 *
 *  - **quote** — the text in typographic quotes, collapsed to one line, to drop inside a sentence;
 *  - **blockquote** — a Markdown `>` block, one prefix per line, to paste as a quotation;
 *  - **markdown-link** — the text as the label of a link to the page it came from.
 */

export type CopyFormat = "quote" | "blockquote" | "markdown-link";

/** Collapse runs of whitespace (including newlines) to single spaces, trimmed — for the one-line formats. */
function collapse(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/** `“the selection”` — collapsed, in typographic double quotes. */
export function asQuote(text: string): string {
  return `\u201c${collapse(text)}\u201d`;
}

/** A Markdown blockquote: every line prefixed with `> `, blank lines kept as a bare `>`. */
export function asBlockquote(text: string): string {
  return text
    .trim()
    .split(/\r?\n/)
    .map((line) => (line.trim() === "" ? ">" : `> ${line.trim()}`))
    .join("\n");
}

/** `[the selection](url)` — brackets in the label escaped so they can't break the link. */
export function asMarkdownLink(text: string, url: string): string {
  const label = collapse(text).replace(/([[\]])/g, "\\$1");
  return `[${label}](${url})`;
}

/** Format a selection for the clipboard in the chosen style. */
export function formatCopy(format: CopyFormat, text: string, url: string): string {
  switch (format) {
    case "quote":
      return asQuote(text);
    case "blockquote":
      return asBlockquote(text);
    case "markdown-link":
      return asMarkdownLink(text, url);
  }
}
