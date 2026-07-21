import { describe, it, expect } from "vitest";
import {
  generatePairingCode,
  generateToken,
  secretsMatch,
  beginPairing,
  completePairing,
  PAIRING_CODE_TTL_MS,
  type RandomBytes,
} from "../src/services/bridge/auth";
import { checkAccess, originAllowed, isViewExposed, corsHeaders, isExtensionOrigin } from "../src/services/bridge/policy";
import { BridgeRouter } from "../src/services/bridge/router";
import { defaultRoutes, type BridgeContext } from "../src/services/bridge/routes";
import { rowRefOf } from "../src/services/bridge/row-ref";
import { bearerToken, parseBody } from "../src/services/bridge/server";
import { snippetAround } from "../src/services/search/bridge-search";
import { DEFAULT_BRIDGE_SETTINGS, type BridgeRequest, type BridgeSettings } from "../src/services/bridge/types";

/** Deterministic bytes so generated secrets are predictable in tests. */
const fixed = (value: number): RandomBytes => (length) => new Uint8Array(length).fill(value);

const settings = (patch: Partial<BridgeSettings> = {}): BridgeSettings => ({
  ...DEFAULT_BRIDGE_SETTINGS,
  enabled: true,
  token: "the-real-token",
  ...patch,
});

const req = (patch: Partial<BridgeRequest> = {}): BridgeRequest => ({
  method: "GET",
  path: "/schema",
  token: "the-real-token",
  ...patch,
});

describe("bridge · secrets", () => {
  it("generates a short numeric code a person can retype", () => {
    const code = generatePairingCode(fixed(7));
    expect(code).toHaveLength(6);
    expect(code).toMatch(/^\d{6}$/);
  });

  it("generates a long token for software to keep", () => {
    expect(generateToken(fixed(3))).toHaveLength(40);
  });

  it("matches identical secrets and rejects everything else", () => {
    expect(secretsMatch("abc", "abc")).toBe(true);
    expect(secretsMatch("abc", "abd")).toBe(false);
    expect(secretsMatch("abc", "ab")).toBe(false);
    expect(secretsMatch("abc", "abcd")).toBe(false);
  });

  it("treats absent or empty secrets as no match", () => {
    expect(secretsMatch(null, "abc")).toBe(false);
    expect(secretsMatch("abc", undefined)).toBe(false);
    expect(secretsMatch("", "")).toBe(false);
  });

  it("compares the whole span rather than stopping at the first difference", () => {
    // A prefix that matches must not be treated any differently from one that doesn't — that difference is
    // exactly what a timing attack reads.
    expect(secretsMatch("aaaaaaaa", "aaaaaaab")).toBe(false);
    expect(secretsMatch("aaaaaaaa", "baaaaaaa")).toBe(false);
  });
});

describe("bridge · pairing", () => {
  it("issues a token for the right code", () => {
    const pending = beginPairing(1000, fixed(1));
    const result = completePairing(pending, pending.code, 1000, fixed(2));
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.token).toHaveLength(40);
  });

  it("tolerates whitespace around a typed code", () => {
    const pending = beginPairing(0, fixed(1));
    expect(completePairing(pending, `  ${pending.code} `, 0).ok).toBe(true);
  });

  it("rejects a wrong code", () => {
    const pending = beginPairing(0, fixed(1));
    const result = completePairing(pending, "000000", 0);
    expect(result.ok).toBe(false);
  });

  it("rejects an expired code", () => {
    const pending = beginPairing(0, fixed(1));
    const result = completePairing(pending, pending.code, PAIRING_CODE_TTL_MS + 1);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toMatch(/expired/i);
  });

  it("rejects when no pairing was started", () => {
    expect(completePairing(null, "123456", 0).ok).toBe(false);
  });
});

describe("bridge · policy", () => {
  it("refuses everything while the bridge is off", () => {
    const denial = checkAccess(req(), "read", settings({ enabled: false }));
    expect(denial?.status).toBe(503);
  });

  it("allows a valid token through", () => {
    expect(checkAccess(req(), "read", settings())).toBeNull();
  });

  it("rejects a wrong or missing token", () => {
    expect(checkAccess(req({ token: "nope" }), "read", settings())?.status).toBe(401);
    expect(checkAccess(req({ token: undefined }), "read", settings())?.status).toBe(401);
  });

  it("reports that nothing is paired rather than that the token is wrong", () => {
    const denial = checkAccess(req(), "read", settings({ token: null }));
    expect(denial?.status).toBe(401);
    expect(denial?.reason).toMatch(/paired/i);
  });

  it("keeps reading and writing as separate permissions", () => {
    const readOnly = settings({ allowWrite: false });
    expect(checkAccess(req(), "read", readOnly)).toBeNull();
    expect(checkAccess(req({ method: "POST", path: "/capture" }), "write", readOnly)?.status).toBe(403);

    const writeOnly = settings({ allowRead: false });
    expect(checkAccess(req(), "read", writeOnly)?.status).toBe(403);
    expect(checkAccess(req({ method: "POST" }), "write", writeOnly)).toBeNull();
  });

  it("lets pairing through without a token, since that's how a token is obtained", () => {
    expect(checkAccess(req({ token: undefined }), "public", settings({ token: null }))).toBeNull();
  });

  it("still refuses pairing when the bridge is off", () => {
    expect(checkAccess(req(), "public", settings({ enabled: false, token: null }))?.status).toBe(503);
  });

  it("enforces an origin allowlist when one is set", () => {
    const locked = settings({ allowedOrigins: ["chrome-extension://abc"] });
    expect(checkAccess(req({ origin: "chrome-extension://abc" }), "read", locked)).toBeNull();
    expect(checkAccess(req({ origin: "https://evil.example" }), "read", locked)?.status).toBe(403);
  });

  it("treats an empty allowlist as any EXTENSION, not anything at all", () => {
    // A page you're merely visiting can issue requests to 127.0.0.1. Permitting every origin would let any
    // website discover that this plugin is installed and probe its endpoints, so ordinary web origins are
    // refused unless deliberately listed.
    expect(originAllowed("chrome-extension://abc", [])).toBe(true);
    expect(originAllowed("moz-extension://abc", [])).toBe(true);
    expect(originAllowed("https://anything.example", [])).toBe(false);
    expect(originAllowed("http://localhost:3000", [])).toBe(false);
  });

  it("still honours an explicit allowlist literally, web origin or not", () => {
    expect(originAllowed("https://example.com", ["https://example.com"])).toBe(true);
  });

  it("recognises the extension schemes browsers actually use", () => {
    expect(isExtensionOrigin("chrome-extension://x")).toBe(true);
    expect(isExtensionOrigin("moz-extension://x")).toBe(true);
    expect(isExtensionOrigin("safari-web-extension://x")).toBe(true);
    expect(isExtensionOrigin("https://x")).toBe(false);
  });

  it("allows callers that send no origin at all", () => {
    // A script or curl on the same machine has no Origin header; the token still has to be right.
    expect(originAllowed(undefined, ["chrome-extension://abc"])).toBe(true);
  });
});

describe("bridge · exposed views", () => {
  it("exposes every view when no list is set", () => {
    expect(isViewExposed("any", settings({ exposedViewIds: null }))).toBe(true);
  });

  it("narrows to a chosen list", () => {
    const only = settings({ exposedViewIds: ["papers"] });
    expect(isViewExposed("papers", only)).toBe(true);
    expect(isViewExposed("private-journal", only)).toBe(false);
  });

  it("exposes nothing when the list is empty", () => {
    expect(isViewExposed("papers", settings({ exposedViewIds: [] }))).toBe(false);
  });
});

describe("bridge · CORS", () => {
  it("echoes a permitted origin rather than using a wildcard", () => {
    const headers = corsHeaders("chrome-extension://abc", settings({ allowedOrigins: ["chrome-extension://abc"] }));
    expect(headers["Access-Control-Allow-Origin"]).toBe("chrome-extension://abc");
  });

  it("omits the allow-origin header for a disallowed origin", () => {
    const headers = corsHeaders("https://evil.example", settings({ allowedOrigins: ["chrome-extension://abc"] }));
    expect(headers["Access-Control-Allow-Origin"]).toBeUndefined();
  });

  it("varies on origin so a proxy can't reuse the wrong response", () => {
    expect(corsHeaders("https://a.example", settings())["Vary"]).toBe("Origin");
  });
});

describe("bridge · router", () => {
  const build = (): BridgeRouter<{ hits: string[] }> =>
    new BridgeRouter<{ hits: string[] }>().registerAll([
      {
        method: "GET",
        path: "/schema",
        permission: "read",
        handler: (_r, ctx) => {
          ctx.hits.push("schema");
          return { status: 200, body: { ok: true } };
        },
      },
      {
        method: "POST",
        path: "/capture",
        permission: "write",
        handler: () => ({ status: 200, body: { captured: true } }),
      },
      { method: "POST", path: "/boom", permission: "read", handler: () => { throw new Error("/vault/secret.md exploded"); } },
    ]);

  it("dispatches to the matching route", async () => {
    const ctx = { hits: [] as string[] };
    const res = await build().dispatch(req(), settings(), ctx);
    expect(res.status).toBe(200);
    expect(ctx.hits).toEqual(["schema"]);
  });

  it("matches regardless of method casing, trailing slash, or query string", async () => {
    const ctx = { hits: [] as string[] };
    const router = build();
    for (const path of ["/schema/", "/schema?x=1"]) {
      expect((await router.dispatch(req({ path, method: "get" }), settings(), ctx)).status).toBe(200);
    }
  });

  it("enforces each route's own permission", async () => {
    const res = await build().dispatch(
      req({ method: "POST", path: "/capture" }),
      settings({ allowWrite: false }),
      { hits: [] },
    );
    expect(res.status).toBe(403);
  });

  it("does not run a handler for a denied request", async () => {
    const ctx = { hits: [] as string[] };
    await build().dispatch(req({ token: "wrong" }), settings(), ctx);
    expect(ctx.hits).toEqual([]);
  });

  it("denies before disclosing that an endpoint doesn't exist", async () => {
    // An unpaired caller shouldn't be able to map the surface by seeing which paths 404 and which 401.
    const res = await build().dispatch(req({ path: "/nope", token: "wrong" }), settings(), { hits: [] });
    expect(res.status).toBe(401);
  });

  it("404s an unknown path for a properly paired caller", async () => {
    const res = await build().dispatch(req({ path: "/nope" }), settings(), { hits: [] });
    expect(res.status).toBe(404);
  });

  it("turns a thrown handler into a 500 without leaking the message", async () => {
    const res = await build().dispatch(req({ method: "POST", path: "/boom" }), settings(), { hits: [] });
    expect(res.status).toBe(500);
    // The message names a vault path. It must not travel to the browser in any form.
    expect(JSON.stringify(res.body)).not.toContain("/vault/secret.md");
    expect(JSON.stringify(res.body)).not.toContain("exploded");
  });

  it("still reports the failure locally so it isn't simply swallowed", async () => {
    const seen: unknown[] = [];
    const router = build().setErrorReporter((error) => seen.push(error));
    await router.dispatch(req({ method: "POST", path: "/boom" }), settings(), { hits: [] });
    expect(seen).toHaveLength(1);
    expect(String(seen[0])).toContain("exploded");
  });

  it("can describe what it exposes, for the settings screen", () => {
    expect(build().list()).toContainEqual({ method: "GET", path: "/schema", permission: "read" });
  });
});

describe("bridge · server helpers", () => {
  it("reads a bearer token, tolerating casing and spacing", () => {
    expect(bearerToken("Bearer abc123")).toBe("abc123");
    expect(bearerToken("bearer   abc123  ")).toBe("abc123");
  });

  it("ignores anything that isn't a bearer scheme", () => {
    expect(bearerToken("Basic abc123")).toBeUndefined();
    expect(bearerToken(undefined)).toBeUndefined();
    expect(bearerToken("")).toBeUndefined();
  });

  it("treats an empty body as an empty object", () => {
    expect(parseBody("")).toEqual({});
    expect(parseBody("   ")).toEqual({});
  });

  it("parses JSON and reports invalid JSON distinctly from empty", () => {
    expect(parseBody('{"a":1}')).toEqual({ a: 1 });
    expect(parseBody("{not json")).toBeUndefined();
  });
});

describe("bridge · routes", () => {
  const profile = (id: string, name: string, patch: Record<string, unknown> = {}) =>
    ({ id, name, columns: [], newRowFile: "Library.md", ...patch }) as never;

  const columns = [
    { name: "Title", typeId: "text", role: "title" },
    { name: "URL", typeId: "url" },
  ];

  const makeContext = (overrides: Record<string, unknown> = {}) => {
    const committed: unknown[] = [];
    const context = {
      vaultName: "TestVault",
      settings: () => settings(),
      listProfiles: () => [profile("papers", "Papers"), profile("private", "Private")],
      viewData: () =>
        Promise.resolve({
          rows: [
            {
              cells: { Title: "Existing", URL: "https://example.com/known" },
              provenance: { filePath: "Library.md" },
            },
          ] as never,
          columns,
        }),
      capture: {
        commit: (_t: unknown, values: unknown) => {
          committed.push(values);
          return Promise.resolve({ ok: true, path: "Library.md" });
        },
      },
      onCaptured: () => undefined,
      pair: (code: string) => (code === "123456" ? { ok: true as const, token: "tok" } : { ok: false as const, reason: "no" }),
      ...overrides,
    };
    return { context, committed };
  };

  const routerWith = (): BridgeRouter<BridgeContext> =>
    new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());

  it("describes each view's columns, so an extension can build a form for it", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(req({ path: "/schema" }), settings(), context as unknown as BridgeContext);
    expect(res.status).toBe(200);
    const body = res.body as { views: { id: string; columns: unknown[] }[] };
    expect(body.views.map((v) => v.id)).toEqual(["papers", "private"]);
    expect(body.views[0]?.columns).toHaveLength(2);
  });

  it("hides views that aren't exposed", async () => {
    const { context } = makeContext({ settings: () => settings({ exposedViewIds: ["papers"] }) });
    const res = await routerWith().dispatch(
      req({ path: "/schema" }),
      settings({ exposedViewIds: ["papers"] }),
      context as never,
    );
    const body = res.body as { views: { id: string }[] };
    expect(body.views.map((v) => v.id)).toEqual(["papers"]);
  });

  it("reports a view as not writable when writing is turned off", async () => {
    const off = settings({ allowWrite: false });
    const { context } = makeContext({ settings: () => off });
    const res = await routerWith().dispatch(req({ path: "/schema" }), off, context as unknown as BridgeContext);
    const body = res.body as { views: { capture: { writable: boolean } }[] };
    expect(body.views[0]?.capture.writable).toBe(false);
  });

  it("finds something already saved, by url", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({ method: "POST", path: "/lookup", body: { url: "https://example.com/known" } }),
      settings(),
      context as never,
    );
    const body = res.body as { matches: { viewId: string; title: string }[] };
    expect(body.matches.length).toBeGreaterThan(0);
    expect(body.matches[0]?.title).toBe("Existing");
  });

  it("returns no matches for something unknown", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({ method: "POST", path: "/lookup", body: { url: "https://example.com/new" } }),
      settings(),
      context as never,
    );
    expect((res.body as { matches: unknown[] }).matches).toHaveLength(0);
  });

  it("rejects a lookup with nothing to look up", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({ method: "POST", path: "/lookup", body: {} }),
      settings(),
      context as never,
    );
    expect(res.status).toBe(400);
  });

  it("captures into a view and reports where it went", async () => {
    const { context, committed } = makeContext();
    const res = await routerWith().dispatch(
      req({
        method: "POST",
        path: "/capture",
        body: { viewId: "papers", fields: [{ key: "title", value: "A New Paper" }] },
      }),
      settings(),
      context as never,
    );
    expect(res.status).toBe(200);
    expect((res.body as { ok: boolean; path?: string }).path).toBe("Library.md");
    expect(committed).toHaveLength(1);
  });

  it("reports a duplicate alongside a successful capture rather than refusing", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({
        method: "POST",
        path: "/capture",
        body: { viewId: "papers", fields: [{ key: "title", value: "X" }], url: "https://example.com/known" },
      }),
      settings(),
      context as never,
    );
    const body = res.body as { ok: boolean; duplicate?: { on: string } };
    expect(body.ok).toBe(true);
    expect(body.duplicate?.on).toBe("URL");
  });

  it("answers the same way for a hidden view as for one that doesn't exist", async () => {
    // Otherwise the bridge could be used to discover which views were deliberately not exposed.
    const narrowed = settings({ exposedViewIds: ["papers"] });
    const { context } = makeContext({ settings: () => narrowed });
    const hidden = await routerWith().dispatch(
      req({ method: "POST", path: "/capture", body: { viewId: "private", fields: [] } }),
      narrowed,
      context as never,
    );
    const absent = await routerWith().dispatch(
      req({ method: "POST", path: "/capture", body: { viewId: "nope", fields: [] } }),
      narrowed,
      context as never,
    );
    expect(hidden.status).toBe(404);
    expect(absent.status).toBe(404);
    expect(hidden.body).toEqual(absent.body);
  });

  it("rejects a capture that names no view or carries no fields", async () => {
    const { context } = makeContext();
    const noView = await routerWith().dispatch(
      req({ method: "POST", path: "/capture", body: { fields: [] } }),
      settings(),
      context as never,
    );
    const noFields = await routerWith().dispatch(
      req({ method: "POST", path: "/capture", body: { viewId: "papers" } }),
      settings(),
      context as never,
    );
    expect(noView.status).toBe(400);
    expect(noFields.status).toBe(400);
  });

  it("reports when nothing in the payload matched the view", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({
        method: "POST",
        path: "/capture",
        body: { viewId: "papers", fields: [{ key: "totally-unknown", value: "x" }] },
      }),
      settings(),
      context as never,
    );
    expect(res.status).toBe(422);
  });

  it("exchanges a correct pairing code for a token, without needing one first", async () => {
    const { context } = makeContext();
    const unpaired = settings({ token: null });
    const res = await routerWith().dispatch(
      req({ method: "POST", path: "/pair", token: undefined, body: { code: "123456" } }),
      unpaired,
      context as never,
    );
    expect(res.status).toBe(200);
    expect((res.body as { token: string }).token).toBe("tok");
  });

  it("refuses a wrong pairing code", async () => {
    const { context } = makeContext();
    const res = await routerWith().dispatch(
      req({ method: "POST", path: "/pair", token: undefined, body: { code: "000000" } }),
      settings({ token: null }),
      context as never,
    );
    expect(res.status).toBe(401);
  });

  describe("promoting a row to its note", () => {
    const routerWith = (): BridgeRouter<BridgeContext> =>
      new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());

    const aRow = {
      cells: { Title: "A Read", URL: "https://x/a" },
      provenance: { filePath: "L.md", extractor: "table", locator: { row: 0 }, fingerprint: "f0" },
    };
    const refOf = rowRefOf(aRow.provenance);

    it("promotes a row and reports the note's path", async () => {
      const calls: unknown[] = [];
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        promote: (...args: unknown[]) => {
          calls.push(args);
          return Promise.resolve({ ok: true, path: "Notes/A Read.md", created: true });
        },
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/promote", body: { viewId: "papers", rowRef: refOf } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ ok: true, path: "Notes/A Read.md", created: true });
      expect(calls).toHaveLength(1);
    });

    it("is idempotent from the caller's side: finding reports created:false", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        promote: () => Promise.resolve({ ok: true, path: "Notes/A Read.md", created: false }),
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/promote", body: { viewId: "papers", rowRef: refOf } }),
        settings(),
        ctx,
      );
      expect((res.body as { created?: boolean }).created).toBe(false);
    });

    it("refuses a stale or forged handle rather than promoting whatever is there now", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        promote: () => Promise.resolve({ ok: true }),
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/promote", body: { viewId: "papers", rowRef: "forged" } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(409);
    });

    it("needs write permission — a note creation is a write", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        promote: () => Promise.resolve({ ok: true }),
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/promote", body: { viewId: "papers", rowRef: refOf } }),
        settings({ allowWrite: false }),
        ctx,
      );
      expect(res.status).toBe(403);
    });

    it("says promotion isn't available rather than pretending, when the vault can't", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/promote", body: { viewId: "papers", rowRef: refOf } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(503);
    });
  });

  describe("a capture the view will never show", () => {
    it("warns when the written file isn't one the view reads", async () => {
      // "I saved it and it isn't there" — a write into a file outside the view's sources is
      // indistinguishable from data loss unless the response says so at the moment it happens.
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [] as never[], columns }),
        capture: {
          commit: () => Promise.resolve({ ok: true, path: "Elsewhere/Captured.md" }),
          linkExistingNote: (v: unknown) => v,
        },
      }).context as unknown as BridgeContext;
      const router = new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());
      const res = await router.dispatch(
        req({
          method: "POST",
          path: "/capture",
          body: { viewId: "papers", url: "https://x/a", fields: [{ key: "Title", value: "T" }] },
        }),
        settings(),
        ctx,
      );
      const body = res.body as { ok: boolean; warning?: string };
      expect(body.ok).toBe(true);
      expect(body.warning).toContain("Elsewhere/Captured.md");
      expect(body.warning).toContain("won't appear");
    });

    it("stays silent when the row is visible where it was written", async () => {
      const visibleRow = {
        cells: { Title: "T" },
        provenance: { filePath: "Library.md", extractor: "table", locator: { row: 0 }, fingerprint: "f" },
      };
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [visibleRow] as never, columns }),
        capture: {
          commit: () => Promise.resolve({ ok: true, path: "Library.md" }),
          linkExistingNote: (v: unknown) => v,
        },
      }).context as unknown as BridgeContext;
      const router = new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());
      const res = await router.dispatch(
        req({
          method: "POST",
          path: "/capture",
          body: { viewId: "papers", url: "https://x/a", fields: [{ key: "Title", value: "T" }] },
        }),
        settings(),
        ctx,
      );
      expect((res.body as { warning?: string }).warning).toBeUndefined();
    });
  });

  describe("deleting from the browser", () => {
    const routerWith = (): BridgeRouter<BridgeContext> =>
      new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());
    const aRow = {
      cells: { Title: "A", URL: "https://x/a" },
      provenance: { filePath: "L.md", extractor: "table", locator: { row: 0 }, fingerprint: "f0" },
    };
    const ref = rowRefOf(aRow.provenance);

    it("deletes a row by handle, through the shared writer", async () => {
      const deleted: unknown[] = [];
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        deleteRows: (rows: unknown[]) => {
          deleted.push(...rows);
          return Promise.resolve(1);
        },
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/row/delete", body: { viewId: "papers", rowRef: ref } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(200);
      expect(deleted).toHaveLength(1);
    });

    it("refuses a stale handle rather than deleting whatever is there now", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        deleteRows: () => Promise.resolve(1),
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/row/delete", body: { viewId: "papers", rowRef: "forged" } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(409);
    });

    it("every delete needs write permission", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [aRow] as never, columns }),
        deleteRows: () => Promise.resolve(1),
        trashNoteForUrl: () => Promise.resolve("Notes/A.md"),
        webAnnotations: {
          list: () => Promise.resolve([]),
          save: () => Promise.resolve(),
          remove: () => Promise.resolve(null),
          removeAll: () => Promise.resolve(2),
          appendToDedicatedNote: () => Promise.resolve(false),
        },
      }).context as unknown as BridgeContext;
      for (const path of ["/row/delete", "/note/delete", "/annotations/clear"]) {
        const res = await routerWith().dispatch(
          req({ method: "POST", path, body: { viewId: "papers", rowRef: ref, url: "https://x/a" } }),
          settings({ allowWrite: false }),
          ctx,
        );
        expect(res.status, path).toBe(403);
      }
    });

    it("trashes the note rather than pretending one existed", async () => {
      const ctx = makeContext({ trashNoteForUrl: () => Promise.resolve(null) }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/note/delete", body: { url: "https://x/a" } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(404);
    });

    it("clears a page's highlights and says how many went", async () => {
      const ctx = makeContext({
        webAnnotations: {
          list: () => Promise.resolve([]),
          save: () => Promise.resolve(),
          remove: () => Promise.resolve(null),
          removeAll: () => Promise.resolve(3),
          appendToDedicatedNote: () => Promise.resolve(false),
        },
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotations/clear", body: { url: "https://x/a" } }),
        settings(),
        ctx,
      );
      expect(res.body).toEqual({ ok: true, removed: 3 });
    });

    it("lookup reports the page's dedicated note even when no row links it", async () => {
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [] as never[], columns }),
        noteForUrl: () => "Notes/A.md",
      }).context as unknown as BridgeContext;
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/lookup", body: { url: "https://x/a" } }),
        settings(),
        ctx,
      );
      expect((res.body as { note?: { path: string } }).note?.path).toBe("Notes/A.md");
    });
  });

  describe("annotating a page", () => {
    const routerWith = (): BridgeRouter<BridgeContext> =>
      new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());

    const wire = { id: "abcdefghij", anchor: { exact: "quoted words" }, color: "yellow", createdAt: "2026-01-01T00:00:00Z" };
    const pageRow = {
      cells: { Title: "A Read", URL: "https://x/a", Annotations: "" },
      provenance: { filePath: "L.md", extractor: "table", locator: { row: 0 }, fingerprint: "f0" },
    };
    const annColumns = [
      { name: "Title", typeId: "text" },
      { name: "URL", typeId: "url" },
      { name: "Annotations", typeId: "text" },
    ];

    const annCtx = (overrides: Record<string, unknown> = {}) => {
      const saved: unknown[] = [];
      const edits: unknown[] = [];
      const noteAppends: unknown[] = [];
      const ctx = makeContext({
        viewData: () => Promise.resolve({ rows: [pageRow] as never, columns: annColumns }),
        editCells: (list: unknown) => {
          edits.push(list);
          return Promise.resolve();
        },
        webAnnotations: {
          list: () => Promise.resolve([]),
          save: (a: unknown) => {
            saved.push(a);
            return Promise.resolve();
          },
          remove: () => Promise.resolve(null),
          appendToDedicatedNote: (...args: unknown[]) => {
            noteAppends.push(args);
            return Promise.resolve(true);
          },
        },
        ...overrides,
      }).context as unknown as BridgeContext;
      return { ctx, saved, edits, noteAppends };
    };

    it("lands the highlight in sidecar, cell, and note in one call", async () => {
      const { ctx, saved, edits, noteAppends } = annCtx();
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotate", body: { viewId: "papers", url: "https://x/a", annotation: wire } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(200);
      const body = res.body as { ok: boolean; wroteCell?: boolean; wroteNote?: boolean; rowRef?: string };
      expect(body.ok).toBe(true);
      expect(body.wroteCell).toBe(true);
      expect(body.wroteNote).toBe(true);
      expect(body.rowRef).toBeDefined();
      expect(saved).toHaveLength(1);
      expect(edits).toHaveLength(1);
      expect(noteAppends).toHaveLength(1);
    });

    it("appends the human-readable line, never machine bookkeeping, to the cell", async () => {
      const { ctx, edits } = annCtx();
      await routerWith().dispatch(
        req({ method: "POST", path: "/annotate", body: { viewId: "papers", url: "https://x/a", annotation: { ...wire, note: "my thought" } } }),
        settings(),
        ctx,
      );
      const first = (edits[0] as { column: string; value: string }[])[0];
      expect(first?.value).toBe("==quoted words== — my thought");
      expect(first?.value).not.toContain("yellow");
      expect(first?.value).not.toContain("abcdefghij");
    });

    it("creates the row first when the page has none — a highlight needs somewhere to land", async () => {
      const twoPhases = [
        { rows: [] as never[], columns: annColumns },
        { rows: [pageRow] as never[], columns: annColumns },
      ];
      const { ctx, saved } = annCtx({
        viewData: () => Promise.resolve(twoPhases.shift() ?? { rows: [pageRow] as never[], columns: annColumns }),
      });
      const res = await routerWith().dispatch(
        req({
          method: "POST",
          path: "/annotate",
          body: {
            viewId: "papers",
            url: "https://x/a",
            annotation: wire,
            fields: [{ key: "Title", value: "A Read" }, { key: "url", value: "https://x/a" }],
          },
        }),
        settings(),
        ctx,
      );
      const body = res.body as { ok: boolean; createdRow?: boolean };
      expect(body.ok).toBe(true);
      expect(body.createdRow).toBe(true);
      expect(saved).toHaveLength(1);
    });

    it("refuses a highlight with nothing to anchor", async () => {
      const { ctx } = annCtx();
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotate", body: { viewId: "papers", url: "https://x/a", annotation: { id: "x", anchor: { exact: "" } } } }),
        settings(),
        ctx,
      );
      expect(res.status).toBe(400);
    });

    it("needs write permission", async () => {
      const { ctx } = annCtx();
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotate", body: { viewId: "papers", url: "https://x/a", annotation: wire } }),
        settings({ allowWrite: false }),
        ctx,
      );
      expect(res.status).toBe(403);
    });

    it("lists a page's annotations for repainting, and needs only read", async () => {
      const stored = [{ ...wire, url: "https://x/a" }];
      const { ctx } = annCtx({
        webAnnotations: {
          list: () => Promise.resolve(stored),
          save: () => Promise.resolve(),
          remove: () => Promise.resolve(null),
          appendToDedicatedNote: () => Promise.resolve(false),
        },
      });
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotations", body: { url: "https://x/a" } }),
        settings({ allowWrite: false }),
        ctx,
      );
      expect(res.status).toBe(200);
      const body = res.body as { annotations: { id: string }[] };
      expect(body.annotations[0]?.id).toBe("abcdefghij");
      // The wire copy never carries the vault-side url key.
      expect(Object.keys(body.annotations[0] ?? {})).not.toContain("url");
    });

    it("removal cleans the exact cell line and reports it", async () => {
      const withLine = {
        ...pageRow,
        cells: { ...pageRow.cells, Annotations: "==quoted words==<br>==keep me==" },
      };
      const { ctx, edits } = annCtx({
        viewData: () => Promise.resolve({ rows: [withLine] as never, columns: annColumns }),
        webAnnotations: {
          list: () => Promise.resolve([]),
          save: () => Promise.resolve(),
          remove: () => Promise.resolve({ ...wire, url: "https://x/a" }),
          appendToDedicatedNote: () => Promise.resolve(false),
        },
      });
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/annotate/remove", body: { viewId: "papers", url: "https://x/a", id: "abcdefghij" } }),
        settings(),
        ctx,
      );
      expect((res.body as { removedFromCell?: boolean }).removedFromCell).toBe(true);
      const first = (edits[0] as { value: string }[])[0];
      expect(first?.value).toBe("==keep me==");
    });
  });

  describe("reading a view for a dashboard", () => {
    const routerWith = (): BridgeRouter<BridgeContext> =>
      new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());

    const viewRows = (n: number, readOnly?: readonly string[]) =>
      Array.from({ length: n }, (_, i) => ({
        cells: { Title: `Row ${String(i)}`, Status: i % 2 === 0 ? "Read" : "To read", URL: `https://x/${String(i)}` },
        provenance: {
          filePath: "Library.md",
          extractor: "table",
          locator: { row: i },
          fingerprint: `f${String(i)}`,
          ...(readOnly !== undefined ? { readOnlyFields: [...readOnly] } : {}),
        },
      }));

    const ctxFor = (n: number, readOnly?: readonly string[]) =>
      makeContext({
        viewData: () => Promise.resolve({ rows: viewRows(n, readOnly) as never, columns }),
      }).context as unknown as BridgeContext;

    it("returns a page of rows, each with a handle it can be edited by", async () => {
      const res = await routerWith().dispatch(req({ method: "POST", path: "/rows", body: { viewId: "papers" } }), settings(), ctxFor(5));
      expect(res.status).toBe(200);
      const body = res.body as { rows: { rowRef: string }[]; total: number };
      expect(body.total).toBe(5);
      expect(body.rows).toHaveLength(5);
      expect(body.rows.every((r) => typeof r.rowRef === "string" && r.rowRef.length > 0)).toBe(true);
    });

    it("pages a long view rather than sending all of it across the wire", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers", pageSize: 10, page: 2 } }),
        settings(),
        ctxFor(25),
      );
      const body = res.body as { rows: unknown[]; total: number; page: number };
      expect(body.total).toBe(25);
      expect(body.rows).toHaveLength(10);
      expect(body.page).toBe(2);
    });

    it("caps an absurd page size instead of obeying it", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers", pageSize: 99999 } }),
        settings(),
        ctxFor(300),
      );
      expect((res.body as { pageSize: number }).pageSize).toBeLessThanOrEqual(200);
    });

    it("narrows to rows matching a query", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers", query: "Row 3" } }),
        settings(),
        ctxFor(10),
      );
      expect((res.body as { total: number }).total).toBe(1);
    });

    it("narrows to one page, for showing what's already noted about it", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers", url: "https://x/2" } }),
        settings(),
        ctxFor(6),
      );
      expect((res.body as { total: number }).total).toBe(1);
    });

    it("matches that page even when the url is written differently", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers", url: "https://www.x/2/?utm_source=q" } }),
        settings(),
        ctxFor(6),
      );
      expect((res.body as { total: number }).total).toBe(1);
    });

    it("says which columns a row doesn't own, so the panel knows before anyone tries", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers" } }),
        settings(),
        ctxFor(2, ["Status"]),
      );
      expect((res.body as { rows: { readOnly?: string[] }[] }).rows[0]?.readOnly).toEqual(["Status"]);
    });

    it("needs read permission", async () => {
      const res = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "papers" } }),
        settings({ allowRead: false }),
        ctxFor(3),
      );
      expect(res.status).toBe(403);
    });

    it("answers for a hidden view exactly as for one that doesn't exist", async () => {
      // The context carries the settings that decide exposure, so it must agree with the request's.
      const ctx = makeContext({
        settings: () => settings({ exposedViewIds: ["papers"] }),
        viewData: () => Promise.resolve({ rows: viewRows(3) as never, columns }),
      }).context as unknown as BridgeContext;

      const hidden = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "private" } }),
        settings({ exposedViewIds: ["papers"] }),
        ctx,
      );
      const missing = await routerWith().dispatch(
        req({ method: "POST", path: "/rows", body: { viewId: "no-such-view" } }),
        settings({ exposedViewIds: ["papers"] }),
        ctx,
      );
      expect(hidden.status).toBe(missing.status);
      expect(hidden.body).toEqual(missing.body);
    });
  })
})

describe("bridge · search permission", () => {
  it("is a separate grant from reading", () => {
    // Someone may want capture without handing over the contents of their notes.
    const readOnly = settings({ allowSearch: false });
    expect(checkAccess(req(), "read", readOnly)).toBeNull();
    expect(checkAccess(req({ method: "POST", path: "/search" }), "search", readOnly)?.status).toBe(403);
  });

  it("allows searching once granted", () => {
    expect(checkAccess(req({ method: "POST" }), "search", settings({ allowSearch: true }))).toBeNull();
  });

  it("defaults to off, so it is never acquired by updating", () => {
    expect(DEFAULT_BRIDGE_SETTINGS.allowSearch).toBe(false);
  });

  it("still requires a valid token", () => {
    const granted = settings({ allowSearch: true, token: "real" });
    expect(checkAccess(req({ token: "wrong" }), "search", granted)?.status).toBe(401);
  });
});

describe("bridge · snippetAround", () => {
  it("returns short text unchanged", () => {
    expect(snippetAround("a short line", ["short"])).toBe("a short line");
  });

  it("cuts around the matched term rather than the start of the document", () => {
    const text = `${"x".repeat(400)} needle ${"y".repeat(400)}`;
    const snippet = snippetAround(text, ["needle"]);
    expect(snippet).toContain("needle");
    expect(snippet.length).toBeLessThan(300);
  });

  it("falls back to the opening when no term matches", () => {
    const snippet = snippetAround("z".repeat(500), ["absent"]);
    expect(snippet.startsWith("z")).toBe(true);
    expect(snippet.endsWith("…")).toBe(true);
  });

  it("marks where it cut with ellipses", () => {
    const text = `${"x".repeat(400)} needle ${"y".repeat(400)}`;
    expect(snippetAround(text, ["needle"]).startsWith("…")).toBe(true);
  });

  it("collapses whitespace and copes with empty input", () => {
    expect(snippetAround("  a\n\n  b  ", ["a"])).toBe("a b");
    expect(snippetAround("", ["a"])).toBe("");
  });

  it("ignores punctuation-only or single-character terms", () => {
    // A one-letter "term" would match almost anywhere and make the snippet meaningless.
    const text = `${"x".repeat(400)} target ${"y".repeat(400)}`;
    expect(snippetAround(text, ["-", "a"]).startsWith("x")).toBe(true);
  });
});

describe("bridge · ping and discovery", () => {
  const routerWith = (): BridgeRouter<BridgeContext> =>
    new BridgeRouter<BridgeContext>().registerAll(defaultRoutes());
  const ctx = {} as unknown as BridgeContext;

  it("answers so the companion can find the port without being told one", async () => {
    const res = await routerWith().dispatch(
      { method: "GET", path: "/ping", origin: "moz-extension://abc" },
      settings({ token: null }),
      ctx,
    );
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ kvs: true, protocol: 1 });
  });

  it("says nothing about the vault, even to a paired caller", async () => {
    const res = await routerWith().dispatch(
      { method: "GET", path: "/ping", origin: "chrome-extension://abc", token: "the-real-token" },
      settings(),
      ctx,
    );
    // Not the vault name, not whether anything is paired, not what views exist.
    expect(Object.keys(res.body as object).sort()).toEqual(["kvs", "protocol"]);
  });

  it("is NOT reachable from an ordinary web page", async () => {
    // Otherwise any site you visited could probe localhost and learn this plugin is installed.
    const res = await routerWith().dispatch(
      { method: "GET", path: "/ping", origin: "https://evil.example" },
      settings(),
      ctx,
    );
    expect(res.status).toBe(403);
  });

  it("stays silent while the bridge is switched off", async () => {
    const res = await routerWith().dispatch(
      { method: "GET", path: "/ping", origin: "moz-extension://abc" },
      settings({ enabled: false }),
      ctx,
    );
    expect(res.status).toBe(503);
  });
})
