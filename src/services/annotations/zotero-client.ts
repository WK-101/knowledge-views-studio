import type { KvsAnnotation } from "../../domain/index";
import { normalizeDoiValue, parseZoteroAnnotation, type ZoteroItem } from "./zotero";

export type JsonFetcher = (url: string) => Promise<{ status: number; json?: unknown; text?: string; reason?: string }>;
export type Debug = (msg: string) => void;

function asItems(payload: unknown): ZoteroItem[] {
  return Array.isArray(payload) ? (payload as ZoteroItem[]) : [];
}

/** Find the Zotero item key(s) whose DOI matches — the seamless path (no manual zotero:// link). */
export async function findZoteroKeysByDoi(baseUrl: string, doi: string, fetcher: JsonFetcher, debug?: Debug): Promise<string[]> {
  return (await zoteroDoiLookup(baseUrl, doi, fetcher, debug)).keys;
}

/**
 * Look up a DOI in Zotero and return both the HTTP status and any matching item keys. Surfacing the status
 * matters: a caller can then tell "Zotero couldn't be reached" (status 0) from "reached, but this DOI isn't
 * in the library" (200 with no keys) from "reached, but the API errored" (other status). Reporting the
 * wrong one of these is what made "Fill from Zotero" confusingly claim Zotero was unreachable.
 *
 * This uses the same `/items?q=…` search endpoint as the actual fill, so its status reflects the real
 * operation — not a separate probe endpoint that could succeed or fail independently.
 */
export async function zoteroDoiLookup(baseUrl: string, doi: string, fetcher: JsonFetcher, debug?: Debug): Promise<{ status: number; keys: string[]; reason?: string }> {
  const base = baseUrl.replace(/\/+$/, "");
  const target = normalizeDoiValue(doi);
  if (target === "") return { status: 200, keys: [] };
  const url = `${base}/items?q=${encodeURIComponent(target)}&qmode=everything&format=json&limit=50`;
  const res = await fetcher(url);
  debug?.(`DOI query "${target}" → status ${res.status}${res.reason ? ` (${res.reason})` : ""}`);
  if (res.status !== 200) return { status: res.status, keys: [], ...(res.reason ? { reason: res.reason } : {}) };
  const items = asItems(res.json ?? (res.text ? (JSON.parse(res.text) as unknown) : []));
  debug?.(`DOI query returned ${items.length} item(s); DOIs: ${items.map((i) => i.data?.DOI ?? "(none)").join(", ") || "—"}`);
  const match = (d: string): boolean => {
    const n = normalizeDoiValue(d);
    return n !== "" && (n === target || n.includes(target) || target.includes(n));
  };
  let keys = items.filter((it) => it.data?.DOI && match(it.data.DOI) && it.key).map((it) => it.key!);
  // Fallback: if the quicksearch matched but no DOI field lined up, use the top-level items it returned.
  if (keys.length === 0) {
    keys = items.filter((it) => it.key && it.data && !it.data.parentItem && it.data.itemType !== "attachment" && it.data.itemType !== "annotation" && it.data.itemType !== "note").map((it) => it.key!);
    if (keys.length > 0) debug?.(`No exact DOI field match; falling back to ${keys.length} top-level result(s).`);
  }
  return { status: 200, keys: [...new Set(keys)] };
}

/** Probe the Zotero local API and return a human-readable status for the settings test button. */
export async function testZoteroConnection(baseUrl: string, fetcher: JsonFetcher): Promise<string> {
  const base = baseUrl.replace(/\/+$/, "");
  const res = await fetcher(`${base}/items?limit=1&format=json`);
  if (res.status === 0) return "Couldn't reach Zotero. Make sure Zotero is running, and that Settings → Advanced → \u201cAllow other applications on this computer to communicate with Zotero\u201d is on. (If it still fails, try 127.0.0.1 instead of localhost.)";
  if (res.status === 403) return "Zotero is running but the local API is off. Turn on Settings → Advanced → \u201cAllow other applications on this computer to communicate with Zotero\u201d in Zotero.";
  if (res.status === 404) return "Connected, but that path looks wrong. Check the base URL (default: http://localhost:23119/api/users/0).";
  if (res.status === 200) return "Connected to Zotero. 🎉";
  return `Zotero returned status ${res.status}.`;
}

/**
 * Fetch annotations from Zotero's local API for the given item/attachment keys. A key may point at an
 * attachment (its children are annotations) or a parent item (its children are attachments, whose
 * children are annotations) — so we descend one level when needed. `fetcher` is injected for testing.
 */
/**
 * Fetch annotations for the given item/attachment keys. Zotero's API does NOT allow `/children` on
 * attachment items, so we (1) resolve the attachment keys (an attachment key is used directly; a
 * regular item's attachments come from its `/children`), then (2) fetch all annotations and keep the
 * ones whose `parentItem` is one of those attachments. `fetcher` is injected for testing.
 */
export async function fetchZoteroAnnotations(baseUrl: string, keys: readonly string[], fetcher: JsonFetcher, debug?: Debug): Promise<KvsAnnotation[]> {
  const base = baseUrl.replace(/\/+$/, "");
  const attachmentKeys = await resolveAttachmentKeys(base, keys, fetcher, debug);
  debug?.(`resolved ${attachmentKeys.size} attachment key(s): ${[...attachmentKeys].join(", ") || "—"}`);
  if (attachmentKeys.size === 0) return [];
  const res = await fetcher(`${base}/items?itemType=annotation&format=json`);
  debug?.(`all-annotations query → status ${res.status}`);
  if (res.status !== 200) return [];
  const all = asItems(res.json ?? (res.text ? (JSON.parse(res.text) as unknown) : []));
  debug?.(`library has ${all.length} annotation item(s)`);
  const out: KvsAnnotation[] = [];
  for (const a of all) {
    const parent = a.data?.parentItem;
    if (parent && attachmentKeys.has(parent)) {
      const parsed = parseZoteroAnnotation(a, `zotero:${parent}`);
      if (parsed) out.push(parsed);
    }
  }
  debug?.(`matched ${out.length} annotation(s) to this paper`);
  return out;
}

/** Turn item/attachment keys into the set of attachment keys whose annotations we want. */
async function resolveAttachmentKeys(base: string, keys: readonly string[], fetcher: JsonFetcher, debug?: Debug): Promise<Set<string>> {
  const attachments = new Set<string>();
  for (const key of keys) {
    const item = await getItem(base, key, fetcher);
    const type = item?.data?.itemType;
    debug?.(`item ${key}: itemType ${type ?? "(not found)"}`);
    if (type === "attachment") {
      attachments.add(key);
    } else if (type) {
      // Regular item — /children IS allowed here; collect its attachment children.
      const children = await getChildren(base, key, fetcher);
      for (const c of children) if (c.data?.itemType === "attachment" && c.key) attachments.add(c.key);
    }
  }
  return attachments;
}

async function getItem(base: string, key: string, fetcher: JsonFetcher): Promise<ZoteroItem | null> {
  const res = await fetcher(`${base}/items/${key}?format=json`);
  if (res.status !== 200) return null;
  const payload = res.json ?? (res.text ? (JSON.parse(res.text) as unknown) : null);
  return payload && typeof payload === "object" && !Array.isArray(payload) ? (payload) : null;
}

async function getChildren(base: string, key: string, fetcher: JsonFetcher): Promise<ZoteroItem[]> {
  const res = await fetcher(`${base}/items/${key}/children?format=json&limit=200`);
  if (res.status !== 200) return []; // e.g. 400 "/children cannot be called on attachment items"
  const payload = res.json ?? (res.text ? (JSON.parse(res.text) as unknown) : []);
  return asItems(payload);
}
