import { describe, it, expect } from "vitest";
import {
  citeKeyColumnName,
  noteLinkColumnName,
  promotedFillColor,
  promotedNotesEnabled,
  resolveNoteLinkColumn,
  wikilinkTarget,
} from "../src/views/promoted-detect";

// Mirrors the Paper Library's configured columns (note the relation "Cites" must NOT win).
const libraryCols = [
  { name: "Cite key", type: "citekey" },
  { name: "Tags", type: "tags" },
  { name: "DOI", type: "doi" },
  { name: "Cites", type: "relation" },
  { name: "Note", type: "link" },
];

describe("promoted-note detection", () => {
  it("finds the Note column by name (not the relation Cites column)", () => {
    expect(noteLinkColumnName(libraryCols)).toBe("Note");
  });

  it("falls back to a link-typed column when none is named Note", () => {
    expect(noteLinkColumnName([{ name: "Paper file", type: "link" }, { name: "X", type: "text" }])).toBe("Paper file");
  });

  it("finds a Note column even if its type isn't link (auto-column views)", () => {
    expect(noteLinkColumnName([{ name: "Note", type: "text" }])).toBe("Note");
  });

  it("returns null when there's no note/link column", () => {
    expect(noteLinkColumnName([{ name: "Title", type: "text" }])).toBeNull();
  });

  it("extracts the wikilink target", () => {
    expect(wikilinkTarget("[[vaswani2017]]")).toBe("vaswani2017");
    expect(wikilinkTarget("[[Papers/vaswani2017|Attention]]")).toBe("Papers/vaswani2017");
    expect(wikilinkTarget("")).toBeNull();
    expect(wikilinkTarget("no link here")).toBeNull();
  });

  it("finds the cite-key column by type or name", () => {
    expect(citeKeyColumnName(libraryCols)).toBe("Cite key");
    expect(citeKeyColumnName([{ name: "Key", type: "citekey" }])).toBe("Key");
    expect(citeKeyColumnName([{ name: "X", type: "text" }])).toBeNull();
  });

  it("resolves an explicit link column, falling back to auto-detect", () => {
    const cols = [{ name: "URL", type: "text" }, { name: "My Notes", type: "text" }, { name: "Note", type: "link" }];
    // Explicit choice wins, case-insensitively.
    expect(resolveNoteLinkColumn("my notes", cols)).toBe("My Notes");
    // Empty = auto-detect (the "Note"/link column).
    expect(resolveNoteLinkColumn("", cols)).toBe("Note");
    expect(resolveNoteLinkColumn(undefined, cols)).toBe("Note");
    // A stale explicit choice (renamed away) falls back to auto rather than linking nothing.
    expect(resolveNoteLinkColumn("Gone", cols)).toBe("Note");
    // No candidate at all → null.
    expect(resolveNoteLinkColumn("", [{ name: "Title", type: "text" }])).toBeNull();
  });

  it("treats promoted notes as on unless explicitly off", () => {
    expect(promotedNotesEnabled({})).toBe(true);
    expect(promotedNotesEnabled({ promotedNotes: true })).toBe(true);
    expect(promotedNotesEnabled({ promotedNotes: false })).toBe(false);
  });

  it("resolves the promoted-row fill colour, defaulting and honouring 'none'", () => {
    expect(promotedFillColor("green")).toBe("green");
    expect(promotedFillColor("magenta")).toBe("magenta");
    expect(promotedFillColor("none")).toBeNull();
    expect(promotedFillColor(undefined)).toBe("purple"); // default
    expect(promotedFillColor("chartreuse")).toBe("purple"); // unknown → default, not dropped
  });
});
