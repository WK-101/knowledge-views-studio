import { normalizeUrl } from "../../shared/protocol";
import { candidateUrls, isSearchHost } from "../../shared/serp";

/**
 * Marking results you already have.
 *
 * Runs on search pages, collects the links that look like results, asks the vault which it recognises, and
 * marks those. The asking goes through the background worker rather than directly, because a content script
 * on a third-party page should never hold the vault token — a script injected into a page it shares with
 * whatever else that page loads is the last place a credential belongs.
 *
 * What comes back is only a list of URLs, never titles or paths, so even if this script were compromised it
 * could learn nothing beyond the answer to the question it asked.
 */

const MARK_CLASS = "kvs-known-mark";
const MARKED = "data-kvs-marked";

interface Messenger {
  runtime: { sendMessage(message: unknown): Promise<unknown> };
}
const messenger = (): Messenger | null => {
  const g = globalThis as unknown as { browser?: Messenger; chrome?: Messenger };
  return g.browser ?? g.chrome ?? null;
};

function injectStyle(): void {
  if (document.getElementById("kvs-serp-style") !== null) return;
  const style = document.createElement("style");
  style.id = "kvs-serp-style";
  // Understated on purpose: this annotates someone else's page, and anything louder would be vandalism.
  style.textContent = `
    .${MARK_CLASS} {
      display: inline-block;
      margin-left: 6px;
      padding: 0 5px;
      border: 1px solid rgba(107, 70, 193, 0.45);
      border-radius: 3px;
      background: rgba(107, 70, 193, 0.10);
      color: #6b46c1;
      font-size: 11px;
      line-height: 1.5;
      vertical-align: middle;
      white-space: nowrap;
    }
    @media (prefers-color-scheme: dark) {
      .${MARK_CLASS} { color: #b9a2f0; border-color: rgba(185, 162, 240, 0.4); }
    }
  `;
  document.head.appendChild(style);
}

/** Put the mark beside a link, once. */
function mark(anchor: HTMLAnchorElement): void {
  if (anchor.getAttribute(MARKED) === "1") return;
  anchor.setAttribute(MARKED, "1");
  const badge = document.createElement("span");
  badge.className = MARK_CLASS;
  badge.textContent = "In your vault";
  badge.title = "You already have this page in Knowledge Views";
  anchor.insertAdjacentElement("afterend", badge);
}

async function run(): Promise<void> {
  if (!isSearchHost(location.hostname)) return;
  const api = messenger();
  if (api === null) return;

  const anchors = Array.from(document.querySelectorAll("a[href]")) as HTMLAnchorElement[];
  const hrefs = anchors.map((a) => a.href);
  const candidates = candidateUrls(hrefs, location.hostname);
  if (candidates.length === 0) return;

  let response: unknown;
  try {
    response = await api.runtime.sendMessage({ type: "kvs-known", urls: candidates });
  } catch {
    return; // Vault closed, or the feature is off. Silence is correct on someone else's page.
  }
  const known = (response as { known?: unknown } | null)?.known;
  if (!Array.isArray(known) || known.length === 0) return;

  const wanted = new Set((known as string[]).map((u) => normalizeUrl(u)));
  injectStyle();
  for (const anchor of anchors) {
    if (wanted.has(normalizeUrl(anchor.href))) mark(anchor);
  }
}

// Results arrive after the first paint on most of these sites, and change again as you scroll or refine —
// so re-check when the page mutates, with a gap long enough that a busy page isn't queried continuously.
let timer: number | undefined;
const schedule = (): void => {
  window.clearTimeout(timer);
  timer = window.setTimeout(() => void run(), 600);
};

void run();
new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
