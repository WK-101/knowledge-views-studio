import { checkAccess } from "./policy";
import type { BridgePermission, BridgeRequest, BridgeResponse, BridgeSettings } from "./types";

/**
 * Routing.
 *
 * A registry rather than a switch, because the endpoint list is going to keep growing — search, annotations,
 * whatever the companion turns out to need — and each addition should be one registration, not a change to
 * a dispatcher everyone else depends on. Routes declare the permission they need and the router enforces it,
 * so a new endpoint can't accidentally ship without an access check.
 *
 * Pure: dispatch takes a request and returns a response, with no server, socket or Obsidian import in sight.
 * That's what makes the security rules testable rather than merely reviewable.
 */

export interface Route<Ctx> {
  readonly method: string;
  readonly path: string;
  readonly permission: BridgePermission;
  readonly handler: (request: BridgeRequest, context: Ctx) => Promise<BridgeResponse> | BridgeResponse;
}

export class BridgeRouter<Ctx> {
  private readonly routes: Route<Ctx>[] = [];
  /** Where internal failures go. Kept local — never sent to the caller. */
  private onError: ((error: unknown, request: BridgeRequest) => void) | null = null;

  /** Report internal failures somewhere useful (the activity log, the console) without disclosing them. */
  setErrorReporter(report: (error: unknown, request: BridgeRequest) => void): this {
    this.onError = report;
    return this;
  }

  register(route: Route<Ctx>): this {
    this.routes.push(route);
    return this;
  }

  registerAll(routes: readonly Route<Ctx>[]): this {
    for (const route of routes) this.register(route);
    return this;
  }

  /** The routes currently registered, for the settings screen to describe what's exposed. */
  list(): readonly { method: string; path: string; permission: BridgePermission }[] {
    return this.routes.map((r) => ({ method: r.method, path: r.path, permission: r.permission }));
  }

  private find(method: string, path: string): Route<Ctx> | undefined {
    const m = method.toUpperCase();
    const p = path.split("?")[0]?.replace(/\/+$/, "") ?? path;
    return this.routes.find((r) => r.method === m && r.path === (p === "" ? "/" : p));
  }

  async dispatch(request: BridgeRequest, settings: BridgeSettings, context: Ctx): Promise<BridgeResponse> {
    const route = this.find(request.method, request.path);
    if (!route) {
      // Deny before revealing that a path is unknown, so an unpaired caller can't map the surface by
      // watching which paths 404 and which 401.
      const denial = checkAccess(request, "read", settings);
      if (denial) return { status: denial.status, body: { error: denial.reason } };
      return { status: 404, body: { error: "No such endpoint." } };
    }

    const denial = checkAccess(request, route.permission, settings);
    if (denial) return { status: denial.status, body: { error: denial.reason } };

    try {
      return await route.handler(request, context);
    } catch (error) {
      // The message can carry vault paths and stack frames, so it goes to the local reporter and never to
      // the caller. The browser learns only that something failed.
      this.onError?.(error, request);
      return { status: 500, body: { error: "The request could not be completed." } };
    }
  }
}
