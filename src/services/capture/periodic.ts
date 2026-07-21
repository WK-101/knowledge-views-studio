import { normalizePath, type App } from "obsidian";
import type { PeriodicKind, PeriodicTarget } from "./types";

/**
 * Addressing a recurring note — today's daily note, this week's, this month's.
 *
 * The point of this module is that KVS can capture into the *same* file the vault's own daily-notes workflow
 * uses, rather than a parallel one of its own. So the path arithmetic lives here as a pure function (testable
 * without a vault, and without Obsidian's bundled moment), and the read of the user's existing configuration
 * lives here too — untyped, because both the core Daily Notes plugin and the community Periodic Notes plugin
 * expose their settings as plain objects with no published type.
 */

/** The moment format each period falls back to when neither the view nor the vault config names one. */
export const DEFAULT_PERIODIC_FORMAT: Record<PeriodicKind, string> = {
  daily: "YYYY-MM-DD",
  weekly: "gggg-[W]ww",
  monthly: "YYYY-MM",
};

/**
 * The vault path of the periodic note for *now*.
 *
 * `formatWith` is how the current moment is turned into text — bound to `moment().format` in real use, or to
 * a fixed clock in a test — which is what keeps this pure and free of the bundled-moment call-signature
 * problem. A blank folder means the vault root; a format that itself contains "/" (some setups nest by year)
 * is honoured because the whole thing is normalized at the end.
 */
export function periodicNotePath(spec: PeriodicTarget, formatWith: (fmt: string) => string): string {
  const period: PeriodicKind = spec.period ?? "daily";
  const fmt = (spec.format ?? "").trim() || DEFAULT_PERIODIC_FORMAT[period];
  const name = formatWith(fmt).trim();
  if (name === "") return "";
  const folder = (spec.folder ?? "").trim().replace(/^\/+|\/+$/g, "");
  const rel = folder === "" ? `${name}.md` : `${folder}/${name}.md`;
  return normalizePath(rel);
}

/** What we could learn about a period from the vault's own configuration, and which plugin told us. */
export interface PeriodicDefaults {
  readonly format?: string;
  readonly folder?: string;
  readonly template?: string;
  readonly source: "periodic-notes" | "daily-notes";
}

/** Read a property off an untyped object, or undefined. Guards against non-objects at each hop. */
function prop(obj: unknown, key: string): unknown {
  if (obj === null || typeof obj !== "object") return undefined;
  return (obj as Record<string, unknown>)[key];
}

/** Coerce to a non-empty trimmed string, or undefined. */
function asStr(v: unknown): string | undefined {
  return typeof v === "string" && v.trim() !== "" ? v.trim() : undefined;
}

/**
 * Auto-detect a period's format/folder/template from whatever the vault already uses.
 *
 * The community Periodic Notes plugin is preferred because it covers all three periods and is the one this
 * feature is meant to sit beside; its per-period settings live at `plugins.plugins["periodic-notes"]
 * .settings[period]`. For the daily period we fall back to the core Daily Notes plugin
 * (`internalPlugins.plugins["daily-notes"].instance.options`) so a vault that never installed Periodic Notes
 * still auto-configures. Returns null when neither is present (or the period is disabled), and the caller
 * keeps KVS's own defaults.
 */
export function readPeriodicDefaults(app: App, period: PeriodicKind): PeriodicDefaults | null {
  const pnForPeriod = prop(prop(prop(prop(app, "plugins"), "plugins"), "periodic-notes"), "settings");
  const forPeriod = prop(pnForPeriod, period);
  if (forPeriod !== undefined && prop(forPeriod, "enabled") !== false) {
    return {
      source: "periodic-notes",
      ...(asStr(prop(forPeriod, "format")) ? { format: asStr(prop(forPeriod, "format")) } : {}),
      ...(asStr(prop(forPeriod, "folder")) ? { folder: asStr(prop(forPeriod, "folder")) } : {}),
      ...(asStr(prop(forPeriod, "template")) ? { template: asStr(prop(forPeriod, "template")) } : {}),
    };
  }

  if (period === "daily") {
    const opts = prop(
      prop(prop(prop(prop(app, "internalPlugins"), "plugins"), "daily-notes"), "instance"),
      "options",
    );
    if (opts !== undefined) {
      return {
        source: "daily-notes",
        ...(asStr(prop(opts, "format")) ? { format: asStr(prop(opts, "format")) } : {}),
        ...(asStr(prop(opts, "folder")) ? { folder: asStr(prop(opts, "folder")) } : {}),
        ...(asStr(prop(opts, "template")) ? { template: asStr(prop(opts, "template")) } : {}),
      };
    }
  }
  return null;
}
