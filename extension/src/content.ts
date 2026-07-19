import { Readability } from "@mozilla/readability";
import TurndownService from "turndown";
import type { PageSnapshot, RawMeta, RawTable } from "../../shared/extract";

/**
 * Reading the page, properly.
 *
 * This replaces the self-contained function the popup used to inject. That approach was fine for scraping
 * meta tags, but capturing a note means capturing the *article* — and identifying which part of a page is
 * the article, then turning it into Markdown, needs real libraries that can't ride along inside a function
 * serialized across the extension boundary.
 *
 * So this is a content script that answers messages instead. The popup injects it, asks, and gets a reply.
 * That also sidesteps a trap the earlier version hit: an injected *file* is wrapped in an IIFE whose value
 * never comes back, so returning a result from one silently yields nothing.
 *
 * Readability works on a **clone**. It rearranges the document it's given, and rearranging the page someone
 * is reading — while they watch — would be an unforgivable thing for a capture tool to do.
 */

interface ReadArticle {
  readonly markdown: string;
  readonly excerpt: string;
  readonly byline: string;
  readonly title: string;
  readonly length: number;
}

function turndown(): TurndownService {
  const service = new TurndownService({
    headingStyle: "atx",
    hr: "---",
    bulletListMarker: "-",
    codeBlockStyle: "fenced",
    emDelimiter: "*",
  });

  // Keep the parts of a page that carry meaning and that Turndown drops or mangles by default.
  service.addRule("strikethrough", {
    filter: ["del", "s"],
    replacement: (content) => `~~${content}~~`,
  });
  service.addRule("figure", {
    filter: "figure",
    replacement: (content) => `\n\n${content.trim()}\n\n`,
  });
  // Anything purely presentational is noise in a note.
  service.remove(["script", "style", "noscript", "iframe", "form", "button"]);
  return service;
}

/** Extract the article and convert it, or return null when the page isn't article-shaped. */
function readArticle(): ReadArticle | null {
  try {
    // Clone: Readability mutates what it parses, and the reader is still looking at the original.
    const clone = document.cloneNode(true) as Document;
    const article = new Readability(clone).parse();
    const html = article?.content ?? "";
    if (article === null || html === "") return null;
    return {
      markdown: turndown().turndown(html).trim(),
      excerpt: (article.excerpt ?? "").replace(/\s+/g, " ").trim(),
      byline: (article.byline ?? "").replace(/\s+/g, " ").trim(),
      title: (article.title ?? "").trim(),
      length: article.length ?? 0,
    };
  } catch {
    // A page Readability can't make sense of is common; the metadata path still works.
    return null;
  }
}

/** The selection as Markdown, so a highlighted table or list keeps its shape. */
function readSelectionMarkdown(): string {
  const selection = window.getSelection();
  if (selection === null || selection.rangeCount === 0 || selection.isCollapsed) return "";
  try {
    const container = document.createElement("div");
    for (let i = 0; i < selection.rangeCount; i++) {
      container.appendChild(selection.getRangeAt(i).cloneContents());
    }
    return turndown().turndown(container.innerHTML).trim();
  } catch {
    return selection.toString().replace(/\s+/g, " ").trim();
  }
}

function readMeta(): RawMeta[] {
  const out: RawMeta[] = [];
  for (const tag of Array.from(document.querySelectorAll("meta"))) {
    const key = tag.getAttribute("property") ?? tag.getAttribute("name") ?? tag.getAttribute("itemprop");
    const content = tag.getAttribute("content");
    if (key !== null && content !== null && content.trim() !== "") out.push({ key, content });
  }
  return out;
}

function readJsonLd(): unknown[] {
  const blocks: unknown[] = [];
  for (const script of Array.from(document.querySelectorAll('script[type="application/ld+json"]'))) {
    const text = script.textContent ?? "";
    if (text.trim() === "") continue;
    try {
      blocks.push(JSON.parse(text));
    } catch {
      // Malformed JSON-LD is common on real sites and the other sources usually still describe the page.
    }
  }
  return blocks;
}

function readTables(): RawTable[] {
  const tables: RawTable[] = [];
  for (const table of Array.from(document.querySelectorAll("table")).slice(0, 12)) {
    const rowNodes = Array.from(table.querySelectorAll("tr"));
    if (rowNodes.length < 2) continue;
    const cellsOf = (tr: Element): string[] =>
      Array.from(tr.querySelectorAll("th, td")).map((c) => (c.textContent ?? "").replace(/\s+/g, " ").trim());
    const headerRow = rowNodes.find((tr) => tr.querySelector("th") !== null) ?? rowNodes[0];
    if (headerRow === undefined) continue;
    const headers = cellsOf(headerRow);
    if (headers.length === 0) continue;
    const rows = rowNodes.filter((tr) => tr !== headerRow).slice(0, 300).map(cellsOf);
    const caption = (table.querySelector("caption")?.textContent ?? "").replace(/\s+/g, " ").trim();
    tables.push({ headers, rows, ...(caption !== "" ? { caption } : {}) });
  }
  return tables;
}

/** Everything the popup needs about this page, gathered in one pass. */
function readSnapshot(): PageSnapshot {
  const article = readArticle();
  const selectionMarkdown = readSelectionMarkdown();
  const plainSelection = (window.getSelection()?.toString() ?? "").replace(/\s+/g, " ").trim();
  const tables = readTables();

  return {
    url: location.href,
    title: (article?.title !== undefined && article.title !== "" ? article.title : document.title).trim(),
    meta: readMeta(),
    jsonLd: readJsonLd(),
    ...(plainSelection !== "" ? { selection: plainSelection } : {}),
    ...(article?.excerpt !== undefined && article.excerpt !== "" ? { excerpt: article.excerpt } : {}),
    ...(tables.length > 0 ? { tables } : {}),
    ...(article !== null
      ? {
          article: {
            markdown: article.markdown,
            byline: article.byline,
            wordCount: Math.round(article.length / 5),
          },
        }
      : {}),
    ...(selectionMarkdown !== "" ? { selectionMarkdown } : {}),
  };
}

/** The extension namespace, whichever this browser calls it. */
interface MessagingApi {
  readonly runtime: {
    readonly onMessage: {
      addListener(
        fn: (message: unknown, sender: unknown, sendResponse: (response: unknown) => void) => boolean,
      ): void;
    };
  };
}

function messaging(): MessagingApi | null {
  const g = globalThis as unknown as { browser?: MessagingApi; chrome?: MessagingApi };
  return g.browser ?? g.chrome ?? null;
}

// Injected more than once across a session, so registering the listener twice has to be impossible —
// otherwise every reply would be sent as many times as the script has been injected.
const marker = "__kvsCompanionReady";
const scope = window as unknown as Record<string, boolean>;
const api = messaging();
if (scope[marker] !== true && api !== null) {
  scope[marker] = true;
  api.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    const request = message as { type?: string } | null;
    if (request?.type !== "kvs-read-page") return false;
    try {
      sendResponse({ ok: true, snapshot: readSnapshot() });
    } catch (error) {
      sendResponse({ ok: false, error: error instanceof Error ? error.message : "Couldn't read the page." });
    }
    return true;
  });
}
