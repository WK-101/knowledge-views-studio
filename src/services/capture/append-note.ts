/**
 * Appending a capture into a note that already exists.
 *
 * Creating a new file was the only page-shaped option, which quietly assumed every capture deserves its own
 * note. Plenty don't: a daily log, a running "Inbox" note, a topic note that collects everything on one
 * subject. For those, the capture belongs *inside* something — under a chosen heading, after what's already
 * there.
 *
 * Pure text-in, text-out, like the table writer next to it, and for the same reason: the decisions about
 * where content lands are exactly the ones worth testing without a vault.
 */

export interface AppendPlacement {
  /** Append under this heading. Empty = the end of the note. */
  readonly heading?: string;
  /** Write the heading (at level 2) when the note doesn't have it yet. */
  readonly createHeading?: boolean;
}

export interface AppendResult {
  readonly content: string;
  readonly ok: boolean;
  readonly reason?: string;
  /** True when the heading had to be written first. */
  readonly createdHeading?: boolean;
}

const headingPattern = (text: string): RegExp =>
  new RegExp(`^(#{1,6})\\s+${text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*$`, "im");

/**
 * Where a heading's section ends: the next heading of the same or a higher level, or the end of the text.
 *
 * Same-or-higher matters. `## Captured` owns everything under it *including* any `### subsections`, and an
 * append that stopped at the first `###` would land in the middle of the section rather than after it.
 */
function sectionEnd(lines: readonly string[], headingLine: number, level: number): number {
  for (let i = headingLine + 1; i < lines.length; i++) {
    const match = /^(#{1,6})\s/.exec(lines[i] ?? "");
    if (match !== null && (match[1] ?? "").length <= level) return i;
  }
  return lines.length;
}

/** Trim trailing blank lines from a slice position so appends don't accumulate gaps. */
function backUpOverBlanks(lines: readonly string[], from: number): number {
  let at = from;
  while (at > 0 && (lines[at - 1] ?? "").trim() === "") at--;
  return at;
}

/**
 * Append a block of markdown into a note.
 *
 * The block is separated from what precedes it by one blank line, however many trailing blanks the note
 * happened to have — repeated captures must not widen the gap each time.
 */
export function appendToNote(
  content: string,
  block: string,
  placement: AppendPlacement = {},
): AppendResult {
  const body = block.trim();
  if (body === "") return { content, ok: false, reason: "Nothing to append." };

  const nl = content.includes("\r\n") ? "\r\n" : "\n";
  const lines = content.split(/\r?\n/);
  const heading = (placement.heading ?? "").trim();

  if (heading === "") {
    const end = backUpOverBlanks(lines, lines.length);
    const kept = lines.slice(0, end);
    const prefix = kept.length === 0 ? [] : [...kept, ""];
    return { content: [...prefix, body, ""].join(nl), ok: true };
  }

  const found = lines.findIndex((line) => headingPattern(heading).test(line));
  if (found < 0) {
    if (placement.createHeading !== true) {
      return { content, ok: false, reason: `No heading “${heading}” in that note.` };
    }
    const end = backUpOverBlanks(lines, lines.length);
    const kept = lines.slice(0, end);
    const prefix = kept.length === 0 ? [] : [...kept, ""];
    return {
      content: [...prefix, `## ${heading}`, "", body, ""].join(nl),
      ok: true,
      createdHeading: true,
    };
  }

  const level = (/^(#{1,6})\s/.exec(lines[found] ?? "")?.[1] ?? "##").length;
  const end = backUpOverBlanks(lines, sectionEnd(lines, found, level));
  const before = lines.slice(0, end);
  const after = lines.slice(end);
  // One blank line between the section's existing content and the new block; the section's own heading
  // line counts as content when the section was empty.
  return { content: [...before, "", body, ...after].join(nl), ok: true };
}

/**
 * What an appended capture looks like inside a note: a source line, then the body.
 *
 * Deliberately not the full note template — a template writes frontmatter, and frontmatter in the middle of
 * someone's daily log would corrupt it. An append is a block, not a document.
 */
export function capturedAppendBlock(
  values: Readonly<Record<string, string>>,
  url: string,
  body: string,
): string {
  const titleKey = Object.keys(values).find((k) => k.toLowerCase() === "title");
  const title = titleKey !== undefined ? (values[titleKey] ?? "").trim() : "";
  const cleanUrl = url.trim();
  const source = cleanUrl !== "" ? `[${title === "" ? cleanUrl : title}](${cleanUrl})` : title;
  const parts = [source === "" ? "" : `**${source}**`, body.trim()].filter((part) => part !== "");
  return parts.join("\n\n");
}
