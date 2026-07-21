/**
 * Talking to the Zotero running on this machine.
 *
 * Zotero listens on 127.0.0.1:23119 with two distinct surfaces, and this client uses both for what each is
 * for. The **connector protocol** (`/connector/…`) is what Zotero's own browser extension speaks: ping,
 * save items, place them in a collection via a session. The **local API** (`/api/users/0/…`, Zotero 7+) is
 * read access to the library: items, collections, full-text search. Saving goes through the connector
 * because that's the path Zotero maintains for exactly this; reading goes through the API because the
 * connector offers none.
 *
 * Same origin story as the bridge: the extension already holds the 127.0.0.1 host permission, so talking
 * to Zotero asks the person for nothing new.
 */

export const ZOTERO_BASE = "http://127.0.0.1:23119";

/** Headers Zotero's connector endpoints expect; without them some versions refuse the call. */
const CONNECTOR_HEADERS = {
  "Content-Type": "application/json",
  "X-Zotero-Connector-API-Version": "3",
};

export interface ZoteroStatus {
  readonly running: boolean;
  /** Zotero 7+ exposes the read API; older versions can save but not be searched. */
  readonly searchable: boolean;
}

export interface ZoteroCollection {
  readonly key: string;
  readonly name: string;
  /** Nesting depth, for indented display. */
  readonly depth: number;
}

export interface ZoteroHit {
  readonly key: string;
  readonly title: string;
  readonly itemType: string;
  readonly url?: string;
  readonly doi?: string;
  /** For annotation/note items: the text itself, so a match shows what matched. */
  readonly excerpt?: string;
}

export interface ZoteroSaveItem {
  readonly title: string;
  readonly url: string;
  readonly doi?: string;
  readonly abstract?: string;
}

/** Is Zotero there, and how much of it? */
export async function zoteroStatus(): Promise<ZoteroStatus> {
  let running = false;
  try {
    const ping = await fetch(`${ZOTERO_BASE}/connector/ping`, { method: "GET" });
    running = ping.ok;
  } catch {
    return { running: false, searchable: false };
  }
  if (!running) return { running: false, searchable: false };
  try {
    const api = await fetch(`${ZOTERO_BASE}/api/users/0/settings`, { method: "GET" });
    return { running: true, searchable: api.ok };
  } catch {
    return { running: true, searchable: false };
  }
}

/** Read a raw local-API item into a hit, or null for the kinds a search shouldn't surface. */
export function readZoteroItem(raw: unknown): ZoteroHit | null {
  if (raw === null || typeof raw !== "object") return null;
  const data = (raw as { data?: Record<string, unknown> }).data;
  const key = (raw as { key?: unknown }).key;
  if (data === undefined || typeof key !== "string" || key === "") return null;
  const itemType = typeof data["itemType"] === "string" ? data["itemType"] : "";
  if (itemType === "attachment") return null;

  const text = (name: string): string => (typeof data[name] === "string" ? (data[name] as string) : "");
  const isNoteLike = itemType === "annotation" || itemType === "note";
  const body = itemType === "annotation" ? text("annotationText") || text("annotationComment") : text("note");
  const plain = body.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
  const title = text("title") !== "" ? text("title") : isNoteLike ? plain.slice(0, 80) : "(untitled)";
  if (isNoteLike && plain === "") return null;

  return {
    key,
    title,
    itemType,
    ...(text("url") !== "" ? { url: text("url") } : {}),
    ...(text("DOI") !== "" ? { doi: text("DOI") } : {}),
    ...(isNoteLike ? { excerpt: plain.slice(0, 240) } : {}),
  };
}

/** Search the library — items, links, DOIs, and (qmode=everything) notes and annotations too. */
export async function zoteroSearch(query: string, limit = 12): Promise<ZoteroHit[]> {
  const q = query.trim();
  if (q === "") return [];
  const url = `${ZOTERO_BASE}/api/users/0/items?q=${encodeURIComponent(q)}&qmode=everything&limit=${String(limit)}`;
  const response = await fetch(url);
  if (!response.ok) return [];
  const body = (await response.json()) as unknown;
  if (!Array.isArray(body)) return [];
  return body.map(readZoteroItem).filter((hit): hit is ZoteroHit => hit !== null);
}

/** Flatten Zotero's collection list into display order with depths. */
export function orderCollections(
  raw: readonly { key: string; name: string; parent: string | false }[],
): ZoteroCollection[] {
  const byParent = new Map<string | false, { key: string; name: string; parent: string | false }[]>();
  for (const c of raw) {
    const list = byParent.get(c.parent) ?? [];
    list.push(c);
    byParent.set(c.parent, list);
  }
  const out: ZoteroCollection[] = [];
  const walk = (parent: string | false, depth: number): void => {
    for (const c of (byParent.get(parent) ?? []).sort((a, b) => a.name.localeCompare(b.name))) {
      out.push({ key: c.key, name: c.name, depth });
      walk(c.key, depth + 1);
    }
  };
  walk(false, 0);
  return out;
}

export async function zoteroCollections(): Promise<ZoteroCollection[]> {
  const response = await fetch(`${ZOTERO_BASE}/api/users/0/collections?limit=100`);
  if (!response.ok) return [];
  const body = (await response.json()) as unknown;
  if (!Array.isArray(body)) return [];
  const raw = body
    .map((entry: unknown) => {
      const data = (entry as { data?: Record<string, unknown> } | null)?.data;
      if (data === undefined) return null;
      const key = typeof data["key"] === "string" ? data["key"] : "";
      const name = typeof data["name"] === "string" ? data["name"] : "";
      const parentRaw = data["parentCollection"];
      if (key === "" || name === "") return null;
      return { key, name, parent: typeof parentRaw === "string" ? parentRaw : (false as const) };
    })
    .filter((c): c is { key: string; name: string; parent: string | false } => c !== null);
  return orderCollections(raw);
}

/** The webpage item the connector will save — the shape Zotero's own extension sends. */
export function webpageItem(item: ZoteroSaveItem): Record<string, unknown> {
  return {
    itemType: "webpage",
    title: item.title,
    url: item.url,
    accessDate: new Date().toISOString().slice(0, 10),
    ...(item.abstract !== undefined && item.abstract !== "" ? { abstractNote: item.abstract } : {}),
    // A DOI on a webpage item lands in extra, where Zotero's own importers put it.
    ...(item.doi !== undefined && item.doi !== "" ? { extra: `DOI: ${item.doi}` } : {}),
  };
}

/** A session id in the format the connector uses: eight base36 characters. */
export function sessionId(random: () => number = Math.random): string {
  return Array.from({ length: 8 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(random() * 36)]).join("");
}

export interface ZoteroSaveOutcome {
  readonly ok: boolean;
  /** True when the item was also placed in the requested collection, not just saved. */
  readonly placed?: boolean;
  readonly reason?: string;
}

/**
 * Save a page to Zotero, optionally into a chosen collection.
 *
 * Two steps, like Zotero's own connector: `saveItems` with a session, then `updateSession` to point the
 * session at the target collection. The second step is best-effort — if a Zotero version refuses it, the
 * item is still saved wherever Zotero's currently-selected collection is, and the outcome says so rather
 * than claiming placement that didn't happen.
 */
export async function zoteroSave(item: ZoteroSaveItem, collectionKey?: string): Promise<ZoteroSaveOutcome> {
  const session = sessionId();
  try {
    const saved = await fetch(`${ZOTERO_BASE}/connector/saveItems`, {
      method: "POST",
      headers: CONNECTOR_HEADERS,
      body: JSON.stringify({ items: [webpageItem(item)], sessionID: session, uri: item.url }),
    });
    if (!saved.ok) {
      return { ok: false, reason: `Zotero refused the save (${String(saved.status)}).` };
    }
  } catch {
    return { ok: false, reason: "Zotero isn't reachable — is it running?" };
  }

  if (collectionKey === undefined || collectionKey === "") return { ok: true };
  try {
    const placed = await fetch(`${ZOTERO_BASE}/connector/updateSession`, {
      method: "POST",
      headers: CONNECTOR_HEADERS,
      body: JSON.stringify({ sessionID: session, target: `C${collectionKey}`, tags: "" }),
    });
    return placed.ok ? { ok: true, placed: true } : { ok: true, placed: false };
  } catch {
    return { ok: true, placed: false };
  }
}

/** A link that opens the item in Zotero itself. */
export function zoteroSelectLink(key: string): string {
  return `zotero://select/library/items/${key}`;
}
