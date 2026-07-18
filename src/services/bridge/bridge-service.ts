import type { App } from "obsidian";
import { beginPairing, completePairing, type PendingPairing } from "./auth";
import { BridgeRouter } from "./router";
import { defaultRoutes, type BridgeContext } from "./routes";
import { BridgeServer } from "./server";
import type { BridgeLogEntry, BridgeSettings } from "./types";

/**
 * The bridge as a whole: settings in, a running (or deliberately not running) server out.
 *
 * Everything is assembled here rather than wired into the plugin, so the pieces stay swappable — the router
 * takes routes rather than owning them, the server takes a router rather than knowing what a route is, and
 * this class is the only thing that knows all three exist. Adding an endpoint later means registering it;
 * nothing else has to change.
 */

/** How many recent requests to remember for the settings screen. */
const LOG_LIMIT = 50;

export interface BridgeServiceOptions {
  readonly app: App;
  readonly settings: () => BridgeSettings;
  /** Persist a settings change (the token, after pairing). */
  readonly saveSettings: (patch: Partial<BridgeSettings>) => void | Promise<void>;
  readonly context: () => Omit<BridgeContext, "settings" | "vaultName" | "pair">;
}

export class BridgeService {
  private readonly router: BridgeRouter<BridgeContext>;
  private readonly server: BridgeServer<BridgeContext>;
  private pending: PendingPairing | null = null;
  private entries: BridgeLogEntry[] = [];
  private lastError: string | null = null;

  constructor(private readonly options: BridgeServiceOptions) {
    this.router = new BridgeRouter<BridgeContext>()
      .registerAll(defaultRoutes())
      .setErrorReporter((error, request) => {
        // Stays local: recorded where the person can see it, never returned to the caller.
        this.log({
          at: Date.now(),
          method: request.method,
          path: request.path,
          status: 500,
          note: error instanceof Error ? error.message : String(error),
        });
      });

    this.server = new BridgeServer<BridgeContext>({
      router: this.router,
      settings: () => this.options.settings(),
      context: () => this.buildContext(),
      log: (entry) => this.log(entry),
    });
  }

  private buildContext(): BridgeContext {
    return {
      ...this.options.context(),
      vaultName: this.options.app.vault.getName(),
      settings: () => this.options.settings(),
      pair: (code) => this.completePairing(code),
    };
  }

  /** Start if enabled, stop if not. Safe to call whenever settings change. */
  async sync(): Promise<void> {
    const settings = this.options.settings();
    if (!settings.enabled) {
      await this.server.stop();
      this.lastError = null;
      return;
    }
    if (this.server.isRunning() && this.server.port() === settings.port) return;
    this.lastError = await this.server.restart();
  }

  async stop(): Promise<void> {
    await this.server.stop();
  }

  isRunning(): boolean {
    return this.server.isRunning();
  }

  /** The last start failure, if any — a port already in use is the usual one. */
  error(): string | null {
    return this.lastError;
  }

  /** The endpoints currently exposed, so settings can describe them rather than hard-coding a list. */
  endpoints(): readonly { method: string; path: string; permission: string }[] {
    return this.router.list();
  }

  /** Begin pairing and return the code to show. Replaces any pairing already in progress. */
  startPairing(now = Date.now()): string {
    this.pending = beginPairing(now);
    return this.pending.code;
  }

  cancelPairing(): void {
    this.pending = null;
  }

  pendingPairing(): PendingPairing | null {
    return this.pending;
  }

  private completePairing(code: string): { ok: true; token: string } | { ok: false; reason: string } {
    const result = completePairing(this.pending, code, Date.now());
    // Single-use either way: a wrong guess costs a fresh code rather than another try at the same one.
    this.pending = null;
    if (result.ok) void this.options.saveSettings({ token: result.token });
    return result;
  }

  /** Forget the paired client. The extension will have to pair again. */
  async revoke(): Promise<void> {
    this.pending = null;
    await this.options.saveSettings({ token: null });
  }

  log(entry: BridgeLogEntry): void {
    this.entries = [entry, ...this.entries].slice(0, LOG_LIMIT);
  }

  activity(): readonly BridgeLogEntry[] {
    return this.entries;
  }

  clearActivity(): void {
    this.entries = [];
  }
}
