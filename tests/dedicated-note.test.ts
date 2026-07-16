import { describe, it, expect, beforeEach } from "vitest";
import { dedicatedNoteKeyFor, normalizeIdentifier, indexNotesByFrontmatter, findInIndex, findDedicatedNote, getDedicatedNoteIndex, invalidateDedicatedNoteIndex } from "../src/services/notes/dedicated-note";
import type { App } from "obsidian";

// The frontmatter index is a process-wide cache keyed by (field, generation); reset it before each test so
// one test's fake vault can't be served to another (production has a single app, invalidated by events).
beforeEach(() => invalidateDedicatedNoteIndex());

describe("dedicatedNoteKeyFor — which frontmatter field links a row to its note", () => {
  it("defaults to 'doi' for academic-kit views", () => {
    expect(dedicatedNoteKeyFor({ academicKit: true })).toBe("doi");
  });
  it("is empty for non-academic views with nothing configured", () => {
    expect(dedicatedNoteKeyFor({})).toBe("");
    expect(dedicatedNoteKeyFor({ academicKit: false })).toBe("");
  });
  it("honours an explicit key over the academic default", () => {
    expect(dedicatedNoteKeyFor({ academicKit: true, dedicatedNoteKey: "isbn" })).toBe("isbn");
    expect(dedicatedNoteKeyFor({ academicKit: false, dedicatedNoteKey: "uid" })).toBe("uid");
  });
});

describe("normalizeIdentifier — equivalent forms compare equal", () => {
  it("strips DOI url/scheme prefixes and lowercases", () => {
    expect(normalizeIdentifier("doi", "https://doi.org/10.1/ABC")).toBe("10.1/abc");
    expect(normalizeIdentifier("doi", "https://dx.doi.org/10.1/abc")).toBe("10.1/abc");
    expect(normalizeIdentifier("doi", "doi: 10.1/ABC")).toBe("10.1/abc");
    expect(normalizeIdentifier("doi", "  10.1/abc  ")).toBe("10.1/abc");
  });
  it("all DOI forms of the same paper normalize identically", () => {
    const a = normalizeIdentifier("doi", "10.5555/3295222");
    const b = normalizeIdentifier("doi", "https://doi.org/10.5555/3295222");
    expect(a).toBe(b);
  });
  it("non-DOI keys just trim and lowercase", () => {
    expect(normalizeIdentifier("isbn", "  978-X  ")).toBe("978-x");
  });
  it("empty stays empty", () => {
    expect(normalizeIdentifier("doi", "")).toBe("");
    expect(normalizeIdentifier("doi", "   ")).toBe("");
  });
});

// A tiny fake App/vault/metadataCache to exercise the index without Obsidian. The functions only read
// .path/.basename off files and .frontmatter off the cache, so plain objects suffice; the whole thing is
// cast once to App at the boundary.
function fakeApp(files: { path: string; basename: string; frontmatter: Record<string, unknown> }[]): App {
  const tfiles = files.map((f) => ({ path: f.path, basename: f.basename }));
  const fmByPath = new Map(files.map((f) => [f.path, f.frontmatter]));
  return {
    vault: { getMarkdownFiles: () => tfiles },
    metadataCache: { getFileCache: (file: { path: string }) => ({ frontmatter: fmByPath.get(file.path) }) },
  } as unknown as App;
}

describe("indexNotesByFrontmatter + findInIndex — match a row to its note by frontmatter", () => {
  const app = fakeApp([
    { path: "Papers/Attention.md", basename: "Attention", frontmatter: { doi: "10.5555/3295222", title: "Attention" } },
    { path: "Lit/BERT.md", basename: "BERT", frontmatter: { doi: "https://doi.org/10.18653/v1/N19-1423" } },
    { path: "Notes/random.md", basename: "random", frontmatter: { tags: ["misc"] } },
  ]);

  it("finds a note regardless of folder or url form of the DOI", () => {
    const index = indexNotesByFrontmatter(app, "doi");
    // Row holds the bare DOI; note stored the URL form — still matches.
    expect(findInIndex(index, "doi", "10.18653/v1/N19-1423")?.basename).toBe("BERT");
    // Row holds the URL form; note stored the bare DOI — still matches.
    expect(findInIndex(index, "doi", "https://doi.org/10.5555/3295222")?.basename).toBe("Attention");
  });

  it("returns null when no note carries that identifier", () => {
    const index = indexNotesByFrontmatter(app, "doi");
    expect(findInIndex(index, "doi", "10.0/nope")).toBeNull();
  });

  it("ignores notes without the key", () => {
    const index = indexNotesByFrontmatter(app, "doi");
    expect([...index.values()].some((f) => f.basename === "random")).toBe(false);
  });

  it("one-shot findDedicatedNote works end to end", () => {
    expect(findDedicatedNote(app, "doi", "10.5555/3295222")?.basename).toBe("Attention");
    expect(findDedicatedNote(app, "doi", "")).toBeNull();
    expect(findDedicatedNote(app, "", "10.5555/3295222")).toBeNull();
  });

  it("first note wins if two share a DOI (a stray duplicate can't hide the original)", () => {
    const dup = fakeApp([
      { path: "A/original.md", basename: "original", frontmatter: { doi: "10.1/x" } },
      { path: "B/copy.md", basename: "copy", frontmatter: { doi: "10.1/x" } },
    ]);
    expect(findDedicatedNote(dup, "doi", "10.1/x")?.basename).toBe("original");
  });
});

describe("getDedicatedNoteIndex — process cache that only rebuilds when the vault changes", () => {
  // A fake whose file list can change between builds, to prove caching vs. rebuild.
  function mutableApp(getFiles: () => { path: string; basename: string; frontmatter: Record<string, unknown> }[]): App {
    return {
      vault: { getMarkdownFiles: () => getFiles().map((f) => ({ path: f.path, basename: f.basename })) },
      metadataCache: {
        getFileCache: (file: { path: string }) => ({ frontmatter: getFiles().find((f) => f.path === file.path)?.frontmatter }),
      },
    } as unknown as App;
  }

  it("returns the same index object on repeated reads (no rescan) until invalidated", () => {
    let files = [{ path: "A.md", basename: "A", frontmatter: { doi: "10.1/a" } }];
    const app = mutableApp(() => files);
    invalidateDedicatedNoteIndex();
    const first = getDedicatedNoteIndex(app, "doi");
    const second = getDedicatedNoteIndex(app, "doi");
    expect(second).toBe(first); // identical reference = served from cache, vault not rescanned

    // Change the vault, but WITHOUT invalidating — the stale cache is still returned (that's the point:
    // search/sort/scroll re-renders don't rescan).
    files = [{ path: "A.md", basename: "A", frontmatter: { doi: "10.1/a" } }, { path: "B.md", basename: "B", frontmatter: { doi: "10.2/b" } }];
    expect(getDedicatedNoteIndex(app, "doi")).toBe(first);
    expect(findInIndex(getDedicatedNoteIndex(app, "doi"), "doi", "10.2/b")).toBeNull();

    // After invalidation (what a metadata-change event triggers), the next read rebuilds and sees B.
    invalidateDedicatedNoteIndex();
    const rebuilt = getDedicatedNoteIndex(app, "doi");
    expect(rebuilt).not.toBe(first);
    expect(findInIndex(rebuilt, "doi", "10.2/b")?.basename).toBe("B");
  });

  it("rebuilds when the requested key changes", () => {
    const app = mutableApp(() => [{ path: "A.md", basename: "A", frontmatter: { doi: "10.1/a", isbn: "978-x" } }]);
    invalidateDedicatedNoteIndex();
    const byDoi = getDedicatedNoteIndex(app, "doi");
    const byIsbn = getDedicatedNoteIndex(app, "isbn");
    expect(byIsbn).not.toBe(byDoi);
    expect(findInIndex(byIsbn, "isbn", "978-x")?.basename).toBe("A");
  });
});
