import { describe, it, expect } from "vitest";
import type { App } from "obsidian";
import {
  DEFAULT_PERIODIC_FORMAT,
  periodicNotePath,
  readPeriodicDefaults,
} from "../src/services/capture/periodic";
import { effectiveTarget } from "../src/services/capture/parse";
import type { CaptureTarget } from "../src/services/capture/types";

/** A formatWith stub that records the format strings it was asked for and returns a fixed rendered value. */
function clock(rendered: string): ((fmt: string) => string) & { calls: string[] } {
  const calls: string[] = [];
  const fn = (fmt: string): string => {
    calls.push(fmt);
    return rendered;
  };
  return Object.assign(fn, { calls });
}

describe("periodicNotePath", () => {
  it("uses the daily default format at the vault root", () => {
    const f = clock("2026-07-21");
    expect(periodicNotePath({}, f)).toBe("2026-07-21.md");
    expect(f.calls).toEqual([DEFAULT_PERIODIC_FORMAT.daily]);
  });

  it("uses the right default for weekly and monthly", () => {
    const w = clock("2026-W30");
    expect(periodicNotePath({ period: "weekly" }, w)).toBe("2026-W30.md");
    expect(w.calls).toEqual([DEFAULT_PERIODIC_FORMAT.weekly]);

    const m = clock("2026-07");
    expect(periodicNotePath({ period: "monthly" }, m)).toBe("2026-07.md");
    expect(m.calls).toEqual([DEFAULT_PERIODIC_FORMAT.monthly]);
  });

  it("honours an explicit format", () => {
    const f = clock("21 July 2026");
    expect(periodicNotePath({ format: "DD MMMM YYYY" }, f)).toBe("21 July 2026.md");
    expect(f.calls).toEqual(["DD MMMM YYYY"]);
  });

  it("falls back to the period default when the format is blank", () => {
    const f = clock("2026-07-21");
    periodicNotePath({ period: "daily", format: "   " }, f);
    expect(f.calls).toEqual([DEFAULT_PERIODIC_FORMAT.daily]);
  });

  it("places the note in the configured folder, trimming stray slashes", () => {
    expect(periodicNotePath({ folder: "Journal/Daily" }, clock("2026-07-21"))).toBe(
      "Journal/Daily/2026-07-21.md",
    );
    expect(periodicNotePath({ folder: "/Journal/" }, clock("2026-07-21"))).toBe("Journal/2026-07-21.md");
  });

  it("returns an empty path when the name renders empty (so callers can refuse it)", () => {
    expect(periodicNotePath({}, clock(""))).toBe("");
  });
});

/** Build a fake Obsidian app exposing only the untyped config shapes readPeriodicDefaults reaches into. */
function fakeApp(opts: {
  periodic?: Record<string, unknown>;
  dailyNotes?: Record<string, unknown> | null;
}): App {
  return {
    plugins: opts.periodic ? { plugins: { "periodic-notes": { settings: opts.periodic } } } : { plugins: {} },
    internalPlugins: {
      plugins: {
        "daily-notes":
          opts.dailyNotes === undefined ? undefined : { instance: opts.dailyNotes === null ? {} : { options: opts.dailyNotes } },
      },
    },
  } as unknown as App;
}

describe("readPeriodicDefaults", () => {
  it("reads the Periodic Notes plugin settings for a period", () => {
    const app = fakeApp({
      periodic: {
        daily: { enabled: true, format: "YYYY-MM-DD", folder: "Journal", template: "Templates/Daily.md" },
        weekly: { enabled: true, format: "gggg-[W]ww", folder: "Weeks" },
      },
    });
    expect(readPeriodicDefaults(app, "daily")).toEqual({
      source: "periodic-notes",
      format: "YYYY-MM-DD",
      folder: "Journal",
      template: "Templates/Daily.md",
    });
    expect(readPeriodicDefaults(app, "weekly")).toEqual({
      source: "periodic-notes",
      format: "gggg-[W]ww",
      folder: "Weeks",
    });
  });

  it("falls back to core Daily Notes for the daily period", () => {
    const app = fakeApp({ dailyNotes: { format: "YYYY-MM-DD", folder: "Daily" } });
    expect(readPeriodicDefaults(app, "daily")).toEqual({
      source: "daily-notes",
      format: "YYYY-MM-DD",
      folder: "Daily",
    });
  });

  it("skips a disabled Periodic Notes period, falling through to core Daily Notes for daily", () => {
    const app = fakeApp({
      periodic: { daily: { enabled: false, format: "X" } },
      dailyNotes: { folder: "Daily" },
    });
    expect(readPeriodicDefaults(app, "daily")).toEqual({ source: "daily-notes", folder: "Daily" });
  });

  it("returns null when nothing configures the period", () => {
    expect(readPeriodicDefaults(fakeApp({}), "weekly")).toBeNull();
    expect(readPeriodicDefaults(fakeApp({ dailyNotes: { folder: "Daily" } }), "weekly")).toBeNull();
  });
});

describe("effectiveTarget with a periodic destination", () => {
  it("is valid without a static notePath", () => {
    const target: CaptureTarget = { shape: "row", destination: "periodic", periodic: { period: "daily" } };
    expect(effectiveTarget({ captureTarget: target })).toBe(target);
  });

  it("still falls back to null for a fixed destination with no path", () => {
    expect(effectiveTarget({ captureTarget: { shape: "row", destination: "file", notePath: "" } })).toBeNull();
  });
});
