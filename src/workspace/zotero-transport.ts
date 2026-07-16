/**
 * Zotero's local API lives on http://127.0.0.1:23119. Obsidian's requestUrl is unreliable for loopback
 * plain-HTTP (returns status 0), and a browser fetch is blocked by Zotero's origin check. Node's http
 * module (available in the Obsidian desktop app) behaves like curl — no origin header, no CORS — which
 * is exactly what Zotero's local API expects. Falls back to status 0 where Node isn't available.
 */
interface NodeHttp {
  request(
    options: { hostname: string; port: number; path: string; method: string; headers: Record<string, string> },
    cb: (res: { statusCode?: number; setEncoding(e: string): void; on(ev: string, fn: (chunk: string) => void): void }) => void,
  ): { on(ev: string, fn: (err?: unknown) => void): void; setTimeout(ms: number, fn: () => void): void; destroy(): void; write(chunk: string): void; end(): void };
}

function nodeHttp(): NodeHttp | null {
  const req = (window as unknown as { require?: (m: string) => unknown }).require;
  try {
    return req ? (req("http") as NodeHttp) : null;
  } catch {
    return null;
  }
}

export function createZoteroFetcher(timeoutMs = 15000): (url: string) => Promise<{ status: number; json?: unknown; text?: string; reason?: string }> {
  return (url: string) =>
    new Promise((resolve) => {
      const http = nodeHttp();
      if (!http) {
        resolve({ status: 0, reason: "no-node-http" });
        return;
      }
      try {
        const u = new URL(url);
        const request = http.request(
          {
            hostname: u.hostname,
            port: Number(u.port) || 80,
            path: u.pathname + u.search,
            method: "GET",
            headers: { "Zotero-API-Version": "3", Accept: "application/json", "User-Agent": "obsidian-kvs" },
          },
          (res) => {
            let data = "";
            res.setEncoding("utf8");
            res.on("data", (c) => (data += c));
            res.on("end", () => {
              let json: unknown;
              try {
                json = JSON.parse(data);
              } catch {
                json = undefined;
              }
              resolve({ status: res.statusCode ?? 0, json, text: data });
            });
          },
        );
        // Surface the underlying network error (ECONNREFUSED, EHOSTUNREACH, …) instead of a bare status 0,
        // and distinguish a timeout from a refused connection — the two mean very different things.
        request.on("error", (err) => resolve({ status: 0, reason: `error: ${errText(err)}` }));
        request.setTimeout(timeoutMs, () => {
          request.destroy();
          resolve({ status: 0, reason: `timeout after ${timeoutMs}ms` });
        });
        request.end();
      } catch (err) {
        resolve({ status: 0, reason: `threw: ${errText(err)}` });
      }
    });
}

function errText(err: unknown): string {
  if (err && typeof err === "object") {
    const e = err as { code?: string; message?: string };
    return e.code || e.message || "unknown";
  }
  return String(err);
}

/**
 * A POST variant of {@link createZoteroFetcher}, for Better BibTeX's JSON-RPC endpoint (which needs POST +
 * a JSON body). Same Node-http transport, same reasons. Returns parsed JSON or a status-0 failure with a
 * reason. Used only to fetch exact BBT citation keys — everything else is GET.
 */
export function createZoteroPoster(timeoutMs = 15000): (url: string, body: unknown) => Promise<{ status: number; json?: unknown; reason?: string }> {
  return (url: string, body: unknown) =>
    new Promise((resolve) => {
      const http = nodeHttp();
      if (!http) {
        resolve({ status: 0, reason: "no-node-http" });
        return;
      }
      try {
        const payload = JSON.stringify(body);
        const u = new URL(url);
        const request = http.request(
          {
            hostname: u.hostname,
            port: Number(u.port) || 80,
            path: u.pathname + u.search,
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              Accept: "application/json",
              "Content-Length": String(new TextEncoder().encode(payload).length),
              "User-Agent": "obsidian-kvs",
            },
          },
          (res) => {
            let data = "";
            res.setEncoding("utf8");
            res.on("data", (c) => (data += c));
            res.on("end", () => {
              let json: unknown;
              try {
                json = JSON.parse(data);
              } catch {
                json = undefined;
              }
              resolve({ status: res.statusCode ?? 0, json });
            });
          },
        );
        request.on("error", (err) => resolve({ status: 0, reason: `error: ${errText(err)}` }));
        request.setTimeout(timeoutMs, () => {
          request.destroy();
          resolve({ status: 0, reason: `timeout after ${timeoutMs}ms` });
        });
        request.write(payload);
        request.end();
      } catch (err) {
        resolve({ status: 0, reason: `threw: ${errText(err)}` });
      }
    });
}
