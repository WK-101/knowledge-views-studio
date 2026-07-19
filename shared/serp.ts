import { normalizeUrl } from "./protocol";

/**
 * Recognising your own reading in a page of search results.
 *
 * The badge on the toolbar answers "do I have *this* page?". That's useful once you're already somewhere.
 * The more valuable question comes earlier, while you're deciding where to go at all: of these twenty
 * results, which have I read before? Answering it turns the vault from an archive you visit into something
 * that informs the choice — which is the whole difference between a collector and a companion.
 *
 * The logic for *which* links matter lives here, apart from the DOM, so it can be tested. Search pages are
 * hostile to this: they are dense with navigation, adverts, footers and their own internal links, and
 * marking one of those would be worse than marking nothing, because it makes the whole feature untrustworthy.
 */

/** Hosts we mark results on. Kept explicit — guessing at what counts as a search page would go badly. */
export const SEARCH_HOSTS: readonly string[] = [
  "google.com",
  "www.google.com",
  "scholar.google.com",
  "duckduckgo.com",
  "bing.com",
  "www.bing.com",
  "search.brave.com",
  "arxiv.org",
  "pubmed.ncbi.nlm.nih.gov",
  "www.semanticscholar.org",
];

/** Hosts whose own links are navigation, not results. */
const SELF_HOSTS = new Set(
  SEARCH_HOSTS.map((host) => host.replace(/^www\./, "")).concat([
    "google.com",
    "gstatic.com",
    "googleusercontent.com",
    "youtube.com",
    "bing.com",
    "microsoft.com",
    "duckduckgo.com",
  ]),
);

/**
 * Whether a link is plausibly a result rather than furniture.
 *
 * Deliberately strict. A false positive here means a "you've read this" mark on a footer link, which would
 * teach someone to distrust every mark on the page.
 */
export function looksLikeResult(href: string, pageHost: string): boolean {
  if (!/^https?:\/\//i.test(href)) return false;
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return false;
  }
  const host = url.hostname.replace(/^www\./, "").toLowerCase();
  if (host === "") return false;

  // A link back into the search engine is navigation, not a result — except on the academic sites, where
  // the results genuinely are their own pages.
  const academic = /arxiv\.org|pubmed|semanticscholar/.test(pageHost);
  if (!academic && SELF_HOSTS.has(host)) return false;
  if (academic && host !== pageHost.replace(/^www\./, "")) return false;

  // Bare domains and site sections are rarely the thing someone saved.
  const path = url.pathname.replace(/\/+$/, "");
  if (!academic && path === "") return false;
  return true;
}

/** The distinct, normalized URLs on a page worth asking the vault about. */
export function candidateUrls(hrefs: readonly string[], pageHost: string, limit = 60): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const href of hrefs) {
    if (!looksLikeResult(href, pageHost)) continue;
    const normalized = normalizeUrl(href);
    if (normalized === "" || seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(href);
    if (out.length >= limit) break;
  }
  return out;
}

/** Whether this page is one we mark at all. */
export function isSearchHost(host: string): boolean {
  const clean = host.replace(/^www\./, "").toLowerCase();
  return SEARCH_HOSTS.some((h) => h.replace(/^www\./, "") === clean);
}
