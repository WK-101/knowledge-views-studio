import { describe, it, expect } from "vitest";
import {
  STICKY_DEFAULT_H,
  STICKY_DEFAULT_W,
  STICKY_MAX_H,
  STICKY_MAX_W,
  STICKY_MIN_H,
  STICKY_MIN_W,
  coerceStickyNote,
  stickyCellText,
  stickyId,
  stickyMarker,
  withStickyNote,
  withoutStickyNote,
  type PageStickies,
} from "../shared/sticky";

const base = (over: Record<string, unknown> = {}): unknown => ({
  id: "abc123",
  url: "https://example.com/page",
  color: "green",
  body: "a **note**",
  x: 100,
  y: 200,
  w: 300,
  h: 240,
  createdAt: "2026-01-01T00:00:00.000Z",
  updatedAt: "2026-01-02T00:00:00.000Z",
  ...over,
});

describe("sticky note model", () => {
  it("reads a well-formed note through unchanged", () => {
    const note = coerceStickyNote(base());
    expect(note).not.toBeNull();
    expect(note!.id).toBe("abc123");
    expect(note!.color).toBe("green");
    expect(note!.body).toBe("a **note**");
    expect([note!.x, note!.y, note!.w, note!.h]).toEqual([100, 200, 300, 240]);
  });

  it("drops a note with no id, url, or usable body", () => {
    expect(coerceStickyNote(base({ id: "" }))).toBeNull();
    expect(coerceStickyNote(base({ url: "" }))).toBeNull();
    expect(coerceStickyNote(base({ body: "   " }))).toBeNull();
    expect(coerceStickyNote(null)).toBeNull();
    expect(coerceStickyNote(42)).toBeNull();
  });

  it("falls back to yellow for an unknown colour, keeping known ones", () => {
    expect(coerceStickyNote(base({ color: "chartreuse" }))!.color).toBe("yellow");
    expect(coerceStickyNote(base({ color: "magenta" }))!.color).toBe("magenta");
  });

  it("clamps size to bounds and defaults a missing or absurd one", () => {
    expect(coerceStickyNote(base({ w: 5, h: 5 }))!.w).toBe(STICKY_MIN_W);
    expect(coerceStickyNote(base({ w: 5, h: 5 }))!.h).toBe(STICKY_MIN_H);
    expect(coerceStickyNote(base({ w: 99999, h: 99999 }))!.w).toBe(STICKY_MAX_W);
    expect(coerceStickyNote(base({ w: 99999, h: 99999 }))!.h).toBe(STICKY_MAX_H);
    const note = coerceStickyNote(base({ w: "wide", h: undefined }));
    expect(note!.w).toBe(STICKY_DEFAULT_W);
    expect(note!.h).toBe(STICKY_DEFAULT_H);
  });

  it("clamps a negative position to zero and defaults a non-number", () => {
    const note = coerceStickyNote(base({ x: -50, y: "nope" }));
    expect(note!.x).toBe(0);
    expect(note!.y).toBe(24);
  });

  it("defaults updatedAt to createdAt when absent", () => {
    const note = coerceStickyNote({ ...(base() as object), updatedAt: undefined });
    expect(note!.updatedAt).toBe(note!.createdAt);
  });

  it("stickyId is ten lowercase-alphanumeric characters", () => {
    let seed = 0.5;
    const id = stickyId(() => (seed = (seed * 9301 + 49297) % 233280 / 233280));
    expect(id).toMatch(/^[a-z0-9]{10}$/);
  });

  it("adds and replaces by id, and removes by id", () => {
    const page: PageStickies = { url: "u", notes: [] };
    const a = coerceStickyNote(base({ id: "a" }))!;
    const b = coerceStickyNote(base({ id: "b", body: "second" }))!;
    const withA = withStickyNote(page, a);
    const withB = withStickyNote(withA, b);
    expect(withB.notes.map((n) => n.id)).toEqual(["a", "b"]);
    const reA = withStickyNote(withB, coerceStickyNote(base({ id: "a", body: "reworded" }))!);
    expect(reA.notes).toHaveLength(2);
    expect(reA.notes.find((n) => n.id === "a")!.body).toBe("reworded");
    const gone = withoutStickyNote(reA, "a");
    expect(gone.notes.map((n) => n.id)).toEqual(["b"]);
    // Removing something already gone is a no-op, not an error.
    expect(withoutStickyNote(gone, "a").notes.map((n) => n.id)).toEqual(["b"]);
  });

  it("cell text carries the hidden id marker and collapses the body to one line", () => {
    const note = coerceStickyNote(base({ id: "xyz", body: "line one\n\nline two   with   spaces" }))!;
    const text = stickyCellText(note);
    expect(text.startsWith(stickyMarker("xyz"))).toBe(true);
    expect(text).toBe(`${stickyMarker("xyz")}line one line two with spaces`);
    // Inline markdown survives the collapse.
    expect(stickyCellText(coerceStickyNote(base({ id: "m", body: "a **bold** [x](https://e.com)" }))!)).toBe(
      `${stickyMarker("m")}a **bold** [x](https://e.com)`,
    );
  });
});
