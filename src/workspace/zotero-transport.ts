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
  ): { on(ev: string, fn: () => void): void; setTimeout(ms: number, fn: () => void): void; destroy(): void; end(): void };
}

function nodeHttp(): NodeHttp | null {
  const req = (window as unknown as { require?: (m: string) => unknown }).require;
  try {
    return req ? (req("http") as NodeHttp) : null;
  } catch {
    return null;
  }
}

export function createZoteroFetcher(): (url: string) => Promise<{ status: number; json?: unknown; text?: string }> {
  return (url: string) =>
    new Promise((resolve) => {
      const http = nodeHttp();
      if (!http) {
        resolve({ status: 0 });
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
        request.on("error", () => resolve({ status: 0 }));
        request.setTimeout(5000, () => {
          request.destroy();
          resolve({ status: 0 });
        });
        request.end();
      } catch {
        resolve({ status: 0 });
      }
    });
}
