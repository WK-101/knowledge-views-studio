import { Platform } from "obsidian";
import { asString } from "../../util/coerce";
import { corsHeaders } from "./policy";
import type { BridgeRouter } from "./router";
import type { BridgeLogEntry, BridgeRequest, BridgeSettings } from "./types";

/**
 * The socket end of the bridge.
 *
 * Deliberately thin. Everything that decides anything — who may call, what each endpoint does, what comes
 * back — lives in the pure modules next door; this only moves bytes between a socket and them. That split is
 * what lets the access rules be unit-tested, since a test can dispatch a request without opening a port.
 *
 * Two properties are load-bearing and worth stating plainly. It binds to loopback only, so nothing on the
 * network can reach it; browsers also treat `http://127.0.0.1` as a secure context, which avoids the
 * self-signed-certificate friction that similar plugins hit. And it is desktop-only: mobile Obsidian has no
 * Node server to speak of, so on a phone the bridge simply never exists.
 */

/** Minimal shape of Node's http module — kept local so nothing here depends on @types/node. */
interface NodeResponse {
  statusCode: number;
  setHeader(name: string, value: string): void;
  end(body?: string): void;
}
interface NodeRequest {
  readonly method?: string;
  readonly url?: string;
  readonly headers: Record<string, string | string[] | undefined>;
  /** Ask Node for text chunks rather than Buffers, so the body can be assembled without a Buffer import. */
  setEncoding(encoding: string): void;
  on(event: string, listener: (chunk?: unknown) => void): void;
  destroy(): void;
}
interface NodeServer {
  listen(port: number, host: string, callback?: () => void): void;
  close(callback?: () => void): void;
  on(event: string, listener: (error: unknown) => void): void;
}

export interface BridgeServerOptions<Ctx> {
  readonly router: BridgeRouter<Ctx>;
  readonly settings: () => BridgeSettings;
  readonly context: () => Ctx;
  readonly log: (entry: BridgeLogEntry) => void;
}

function headerValue(headers: NodeRequest["headers"], name: string): string | undefined {
  const raw = headers[name] ?? headers[name.toLowerCase()];
  if (Array.isArray(raw)) return raw[0];
  return typeof raw === "string" ? raw : undefined;
}

/** Pull the token out of an `Authorization: Bearer …` header. */
export function bearerToken(authorization: string | undefined): string | undefined {
  if (authorization === undefined) return undefined;
  const m = /^Bearer\s+(.+)$/i.exec(authorization.trim());
  return m ? m[1]!.trim() : undefined;
}

/** Parse a JSON body, tolerating an empty one. Returns undefined when the text isn't valid JSON. */
export function parseBody(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === "") return {};
  try {
    return JSON.parse(trimmed);
  } catch {
    return undefined;
  }
}

export class BridgeServer<Ctx> {
  private server: NodeServer | null = null;
  private listeningPort: number | null = null;

  constructor(private readonly options: BridgeServerOptions<Ctx>) {}

  isRunning(): boolean {
    return this.server !== null;
  }

  port(): number | null {
    return this.listeningPort;
  }

  /**
   * Start listening. Resolves to an error message rather than throwing, because the common failures — a port
   * already taken, no Node available — are things to tell someone in settings, not crashes.
   */
  async start(): Promise<string | null> {
    if (this.server !== null) return null;
    const settings = this.options.settings();
    if (!settings.enabled) return null;
    // Desktop only. Mobile Obsidian has no Node http module, and "your vault is reachable over a port" is a
    // very different promise on a phone anyway.
    if (!Platform.isDesktop) return "The browser bridge is desktop only.";

    let http: { createServer(handler: (req: NodeRequest, res: NodeResponse) => void): NodeServer };
    try {
      // Required lazily so that merely loading the plugin on a platform without Node doesn't fail.
      // A runtime require behind the Platform.isDesktop guard above — the pattern Obsidian's own lint rule
      // prescribes for Node built-ins. A static import would be evaluated on mobile, where there is no http
      // module to load and the plugin must still start cleanly.
      // The remaining obsidianmd/no-nodejs-modules warning is expected and deliberately left in place: that
      // rule can't be disabled, and its own guidance is exactly what's done here — a guarded require.
      // eslint-disable-next-line @typescript-eslint/no-require-imports, no-undef -- guarded by Platform.isDesktop above
      http = require("http") as typeof http;
    } catch {
      return "This platform has no local server support.";
    }

    return new Promise<string | null>((resolve) => {
      let settled = false;
      const done = (value: string | null): void => {
        if (!settled) {
          settled = true;
          resolve(value);
        }
      };
      try {
        const server = http.createServer((req, res) => this.handle(req, res));
        server.on("error", (error: unknown) => {
          const message = error instanceof Error ? error.message : String(error);
          this.server = null;
          this.listeningPort = null;
          done(message.includes("EADDRINUSE") ? `Port ${settings.port} is already in use.` : message);
        });
        // Loopback only. Never 0.0.0.0 — that would put the vault on the network.
        server.listen(settings.port, "127.0.0.1", () => {
          this.server = server;
          this.listeningPort = settings.port;
          done(null);
        });
      } catch (error) {
        done(error instanceof Error ? error.message : String(error));
      }
    });
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    this.listeningPort = null;
    if (server === null) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  /** Restart so a changed port or permission takes effect without reloading the plugin. */
  async restart(): Promise<string | null> {
    await this.stop();
    return this.start();
  }

  private handle(req: NodeRequest, res: NodeResponse): void {
    const settings = this.options.settings();
    const origin = headerValue(req.headers, "origin");
    const method = (req.method ?? "GET").toUpperCase();
    const path = (req.url ?? "/").split("?")[0] ?? "/";

    const send = (status: number, body: unknown, note?: string): void => {
      res.statusCode = status;
      res.setHeader("Content-Type", "application/json; charset=utf-8");
      for (const [name, value] of Object.entries(corsHeaders(origin, settings))) res.setHeader(name, value);
      res.end(JSON.stringify(body ?? {}));
      if (settings.logRequests) {
        this.options.log({ at: Date.now(), method, path, status, ...(note !== undefined ? { note } : {}) });
      }
    };

    if (method === "OPTIONS") {
      send(204, {});
      return;
    }

    // Text chunks, so the body arrives as strings rather than Buffers.
    req.setEncoding("utf8");
    let size = 0;
    let text = "";
    let aborted = false;
    req.on("data", (chunk?: unknown) => {
      if (aborted) return;
      const part = asString(chunk);
      size += part.length;
      if (size > settings.maxBodyBytes) {
        // Stop reading rather than buffering an unbounded body.
        aborted = true;
        send(413, { error: "That request is too large." }, "body too large");
        req.destroy();
        return;
      }
      text += part;
    });

    req.on("end", () => {
      if (aborted) return;
      const body = parseBody(text);
      if (body === undefined) {
        send(400, { error: "The request body wasn't valid JSON." });
        return;
      }
      const request: BridgeRequest = {
        method,
        path,
        origin,
        token: bearerToken(headerValue(req.headers, "authorization")),
        body,
      };
      void this.options.router
        .dispatch(request, settings, this.options.context())
        .then((response) => send(response.status, response.body))
        .catch(() => send(500, { error: "The request could not be completed." }));
    });
  }
}
