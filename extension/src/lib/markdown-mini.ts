/**
 * A tiny, safe Markdown→HTML renderer for sticky-note bodies.
 *
 * The content script runs inside pages we don't control and has no access to Obsidian's own renderer, so a
 * sticky note that wants to show *rendered* markdown needs its own. Pulling in a full library would be
 * heavy and, worse, most of them pass raw HTML through — exactly the wrong default for text that will be
 * injected into a page. So this does a deliberately small subset, and does it safely:
 *
 *  1. **Everything is HTML-escaped first.** No markup in the source survives as live HTML; a `<script>` in a
 *     note is text, always.
 *  2. **Only a known set of constructs is then re-introduced**, by building elements — headings, bold,
 *     italic, strikethrough, inline code, fenced code, links, blockquotes, and both list kinds.
 *  3. **Links are restricted to safe schemes** (http, https, mailto, obsidian). Anything else — `javascript:`
 *     above all — renders as plain text, never as an href.
 *
 * The output is a string of HTML the caller drops into a shadow-root node. It is not a spec-complete
 * CommonMark implementation and isn't trying to be; it's the common scribble vocabulary, rendered without
 * ever trusting the input.
 */

/** Escape the characters that would otherwise be read as HTML. */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Only these schemes may become a live link; everything else stays inert text. */
function safeHref(url: string): string | null {
  const trimmed = url.trim();
  if (/^(https?:|mailto:|obsidian:)/i.test(trimmed)) return trimmed;
  // A bare domain or path is treated as https so "example.com" links sensibly; anything with a hostile
  // scheme (javascript:, data:) has already failed the test above and falls through to null.
  if (/^[^\s:]+\.[^\s:]+/.test(trimmed) && !trimmed.includes(":")) return `https://${trimmed}`;
  return null;
}

/** A char no ordinary note text carries, so a code-span placeholder can't collide with real digits. */
const SENTINEL = String.fromCharCode(0);
const CODE_PLACEHOLDER = new RegExp(`${SENTINEL}(\\d+)${SENTINEL}`, "g");

/** The sentinel-delimited placeholder for a pulled-out code span. */
function codePlaceholder(index: number): string {
  return `${SENTINEL}${String(index)}${SENTINEL}`;
}

/** Inline markup within an already-escaped line: code, bold, italic, strikethrough, links. */
function renderInline(escaped: string): string {
  let out = escaped;
  // Inline code first, pulled out into placeholders so no later pass (emphasis, links) can reach inside a
  // code span. Restored verbatim at the very end.
  const codeSpans: string[] = [];
  out = out.replace(/`([^`]+)`/g, (_m, code: string) => {
    codeSpans.push(`<code>${code}</code>`);
    return codePlaceholder(codeSpans.length - 1);
  });
  // Links: [label](url) — label may hold other inline markup, applied after; url is scheme-checked.
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label: string, url: string) => {
    const href = safeHref(url);
    return href === null ? `${label}` : `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer noopener">${label}</a>`;
  });
  out = out.replace(/\*\*([^*]+)\*\*/g, (_m, t: string) => `<strong>${t}</strong>`);
  out = out.replace(/__([^_]+)__/g, (_m, t: string) => `<strong>${t}</strong>`);
  // Italic: single * or _ not part of a ** already consumed. Kept simple: no nesting across the marker.
  out = out.replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, (_m, pre: string, t: string) => `${pre}<em>${t}</em>`);
  out = out.replace(/(^|[^_])_([^_\s][^_]*?)_(?!_)/g, (_m, pre: string, t: string) => `${pre}<em>${t}</em>`);
  out = out.replace(/~~([^~]+)~~/g, (_m, t: string) => `<del>${t}</del>`);
  // Restore the code spans now that every text pass is done.
  out = out.replace(CODE_PLACEHOLDER, (_m, n: string) => codeSpans[Number(n)] ?? "");
  return out;
}

interface Block {
  readonly html: string;
}

/** Render Markdown text to a safe HTML string. */
export function renderMarkdown(source: string): string {
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let i = 0;

  const paragraph: string[] = [];
  const flushParagraph = (): void => {
    if (paragraph.length === 0) return;
    const inner = paragraph.map((l) => renderInline(escapeHtml(l))).join("<br>");
    blocks.push({ html: `<p>${inner}</p>` });
    paragraph.length = 0;
  };

  while (i < lines.length) {
    const line = lines[i] ?? "";

    // Fenced code: ``` … ``` — contents are escaped and otherwise untouched.
    if (/^```/.test(line)) {
      flushParagraph();
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i] ?? "")) {
        code.push(lines[i] ?? "");
        i++;
      }
      i++; // skip the closing fence (or run off the end)
      blocks.push({ html: `<pre><code>${escapeHtml(code.join("\n"))}</code></pre>` });
      continue;
    }

    // Blank line — a paragraph break.
    if (line.trim() === "") {
      flushParagraph();
      i++;
      continue;
    }

    // Horizontal rule.
    if (/^ {0,3}([-*_])( *\1){2,} *$/.test(line)) {
      flushParagraph();
      blocks.push({ html: "<hr>" });
      i++;
      continue;
    }

    // Heading.
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flushParagraph();
      const level = heading[1]!.length;
      blocks.push({ html: `<h${String(level)}>${renderInline(escapeHtml(heading[2]!.trim()))}</h${String(level)}>` });
      i++;
      continue;
    }

    // Blockquote — consecutive `>` lines.
    if (/^ {0,3}>/.test(line)) {
      flushParagraph();
      const quote: string[] = [];
      while (i < lines.length && /^ {0,3}>/.test(lines[i] ?? "")) {
        quote.push((lines[i] ?? "").replace(/^ {0,3}>\s?/, ""));
        i++;
      }
      blocks.push({ html: `<blockquote>${quote.map((l) => renderInline(escapeHtml(l))).join("<br>")}</blockquote>` });
      continue;
    }

    // Unordered list.
    if (/^ {0,3}[-*+]\s+/.test(line)) {
      flushParagraph();
      const items: string[] = [];
      while (i < lines.length && /^ {0,3}[-*+]\s+/.test(lines[i] ?? "")) {
        items.push((lines[i] ?? "").replace(/^ {0,3}[-*+]\s+/, ""));
        i++;
      }
      blocks.push({ html: `<ul>${items.map((t) => `<li>${renderInline(escapeHtml(t))}</li>`).join("")}</ul>` });
      continue;
    }

    // Ordered list.
    if (/^ {0,3}\d+[.)]\s+/.test(line)) {
      flushParagraph();
      const items: string[] = [];
      while (i < lines.length && /^ {0,3}\d+[.)]\s+/.test(lines[i] ?? "")) {
        items.push((lines[i] ?? "").replace(/^ {0,3}\d+[.)]\s+/, ""));
        i++;
      }
      blocks.push({ html: `<ol>${items.map((t) => `<li>${renderInline(escapeHtml(t))}</li>`).join("")}</ol>` });
      continue;
    }

    paragraph.push(line);
    i++;
  }
  flushParagraph();

  return blocks.map((b) => b.html).join("\n");
}
