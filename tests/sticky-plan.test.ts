import { describe, it, expect } from "vitest";
import { cellWithoutSticky, stickyColumn, upsertStickyLine } from "../src/services/bridge/sticky-plan";
import { coerceStickyNote, stickyCellText, stickyMarker, type StickyNote } from "../shared/sticky";

const note = (id: string, body: string): StickyNote =>
  coerceStickyNote({
    id,
    url: "https://example.com/p",
    color: "yellow",
    body,
    x: 0,
    y: 0,
    w: 260,
    h: 200,
    createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z",
  })!;

describe("sticky-plan · which column, and cell upsert/remove by id", () => {
  it("honours a declared column, case-insensitively", () => {
    const columns = [{ name: "URL" }, { name: "My Scribbles" }, { name: "Notes" }];
    expect(stickyColumn(columns, "my scribbles")).toBe("My Scribbles");
  });

  it("falls back through the name guesses when nothing is declared", () => {
    expect(stickyColumn([{ name: "URL" }, { name: "Sticky Notes" }])).toBe("Sticky Notes");
    expect(stickyColumn([{ name: "URL" }, { name: "Notes" }])).toBe("Notes");
    expect(stickyColumn([{ name: "URL" }, { name: "Title" }])).toBeNull();
  });

  it("falls back to the guess when a declared column no longer exists", () => {
    expect(stickyColumn([{ name: "Notes" }], "Gone")).toBe("Notes");
  });

  it("appends a note's line when the cell has none for it", () => {
    const out = upsertStickyLine("", note("a", "first"));
    expect(out).toBe(stickyCellText(note("a", "first")));
  });

  it("keeps other lines when appending, joined with <br>", () => {
    const existing = "some other text";
    const out = upsertStickyLine(existing, note("a", "mine"));
    expect(out).toBe(`some other text<br>${stickyCellText(note("a", "mine"))}`);
  });

  it("replaces a note's line in place on update, keeping its position", () => {
    const cell = [stickyCellText(note("a", "old")), "neighbour", stickyCellText(note("b", "other"))].join("<br>");
    const out = upsertStickyLine(cell, note("a", "new body"));
    const parts = out.split("<br>");
    expect(parts[0]).toBe(stickyCellText(note("a", "new body")));
    expect(parts[1]).toBe("neighbour");
    expect(parts[2]).toBe(stickyCellText(note("b", "other")));
    // Exactly one line for id "a" — no duplicate appended.
    expect(out.split(stickyMarker("a"))).toHaveLength(2);
  });

  it("removes a note's line by id, matched however the body was reworded", () => {
    const cell = [stickyCellText(note("a", "whatever it says now")), "keep me"].join("<br>");
    // Remove by id, not by matching the old text.
    expect(cellWithoutSticky(cell, "a")).toBe("keep me");
  });

  it("returns null when the cell holds no line for the id (so it isn't rewritten)", () => {
    expect(cellWithoutSticky("just text<br>and more", "a")).toBeNull();
    expect(cellWithoutSticky(stickyCellText(note("b", "x")), "a")).toBeNull();
  });
});
