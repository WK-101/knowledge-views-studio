import { describe, it, expect } from "vitest";
import {
  STARTER_TEMPLATES,
  coerceNoteTemplate,
  normalizeNoteTemplates,
  findNoteTemplate,
  type NoteTemplate,
} from "../shared/note-templates";
import { renderTemplate } from "../shared/template";

describe("STARTER_TEMPLATES", () => {
  it("ships a gallery with unique ids and names", () => {
    expect(STARTER_TEMPLATES.length).toBeGreaterThanOrEqual(5);
    const ids = STARTER_TEMPLATES.map((t) => t.id);
    const names = STARTER_TEMPLATES.map((t) => t.name);
    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(names).size).toBe(names.length);
    expect(ids.every((id) => id.startsWith("starter-"))).toBe(true);
  });

  it("every starter renders through the template engine with real variables", () => {
    const vars: Record<string, string> = {
      title: "My Page",
      url: "https://example.com/a",
      domain: "example.com",
      author: "A. Writer",
      published: "2026-01-02T00:00:00Z",
      description: "A short summary.",
      content: "Body text.",
      date: "2026-07-22T10:00:00Z",
      image: "https://example.com/cover.png",
      tags: "one, two",
    };
    for (const t of STARTER_TEMPLATES) {
      const out = renderTemplate(t.body, vars);
      expect(typeof out).toBe("string");
      expect(out).toContain("My Page"); // {{title|yaml}} resolved
      expect(out).not.toContain("{{title"); // no unresolved title token
    }
  });
});

describe("coerceNoteTemplate", () => {
  it("accepts a valid object and trims id/name/filename/description", () => {
    expect(
      coerceNoteTemplate({ id: " a ", name: " Article ", body: "x", filename: " f ", description: " d " }),
    ).toEqual({ id: "a", name: "Article", body: "x", filename: "f", description: "d" });
  });

  it("allows an empty body but drops empty filename/description", () => {
    expect(coerceNoteTemplate({ id: "a", name: "A", body: "", filename: "", description: "" })).toEqual({
      id: "a",
      name: "A",
      body: "",
    });
  });

  it("rejects missing id, missing name, or a non-object", () => {
    expect(coerceNoteTemplate({ name: "A", body: "x" })).toBeNull();
    expect(coerceNoteTemplate({ id: "a", body: "x" })).toBeNull();
    expect(coerceNoteTemplate({ id: "  ", name: "A" })).toBeNull();
    expect(coerceNoteTemplate("nope")).toBeNull();
    expect(coerceNoteTemplate(null)).toBeNull();
  });
});

describe("normalizeNoteTemplates", () => {
  it("drops invalid entries and de-duplicates by id (first writer wins)", () => {
    const raw = [
      { id: "a", name: "First" },
      { id: "a", name: "Dup" },
      { name: "no id" },
      { id: "b", name: "Second", body: "y" },
    ];
    const out = normalizeNoteTemplates(raw);
    expect(out.map((t) => t.id)).toEqual(["a", "b"]);
    expect(out[0]!.name).toBe("First");
  });

  it("returns an empty array for non-array input", () => {
    expect(normalizeNoteTemplates(undefined)).toEqual([]);
    expect(normalizeNoteTemplates({})).toEqual([]);
  });
});

describe("findNoteTemplate", () => {
  const lib: NoteTemplate[] = [{ id: "x", name: "X", body: "" }];
  it("finds by id, misses cleanly", () => {
    expect(findNoteTemplate(lib, "x")?.name).toBe("X");
    expect(findNoteTemplate(lib, "y")).toBeNull();
    expect(findNoteTemplate(lib, "")).toBeNull();
  });
});
