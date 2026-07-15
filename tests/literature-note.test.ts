import { describe, expect, it } from "vitest";
import { TFile } from "obsidian";
import {
  buildLiteratureNote,
  createOrOpenLiteratureNote,
  findLiteratureNote,
  indexLiteratureNotes,
} from "../src/services/notes/literature-note";
import type { ZoteroLibraryItem } from "../src/services/zotero/provider";
import type { KvsAnnotation } from "../src/domain/index";

/**
 * Literature notes turn a Zotero paper into a first-class Obsidian note. The two things that must be right:
 * the note content (real frontmatter that makes it queryable + a durable Zotero link), and idempotency —
 * a paper must never get two notes, matched by its Zotero key regardless of title or location.
 */

function item(over: Partial<ZoteroLibraryItem>): ZoteroLibraryItem {
  return {
    key: "ABCD", libraryId: 0, version: 1, itemType: "journalArticle", title: "Attention Is All You Need",
    creators: "Vaswani et al.", year: "2017", publication: "NeurIPS", doi: "10.5555/x", url: "https://arxiv.org/abs/1706.03762",
    tags: ["transformers", "deep learning"], collections: [], dateAdded: "2020-01-01", dateModified: "2020-06-01",
    citeKey: "vaswani2017attention", attachmentKeys: [], extra: { abstract: "We propose the Transformer." }, ...over,
  };
}

describe("buildLiteratureNote — a real, queryable Obsidian note", () => {
  it("writes metadata into frontmatter, including the durable zotero-key", () => {
    const md = buildLiteratureNote(item({}));
    expect(md).toContain('title: "Attention Is All You Need"');
    expect(md).toContain('authors: "Vaswani et al."');
    expect(md).toContain("year: 2017");
    expect(md).toContain('journal: "NeurIPS"');
    expect(md).toContain('doi: "10.5555/x"');
    expect(md).toContain('cite-key: "vaswani2017attention"');
    expect(md).toContain('zotero-key: "ABCD"'); // the find-or-create anchor
  });

  it("tags the note as literature and carries the paper's tags", () => {
    const md = buildLiteratureNote(item({}));
    expect(md).toContain("tags: [literature, transformers, deep learning]");
  });

  it("includes a link back to Zotero, the abstract, and a Notes section for the reader", () => {
    const md = buildLiteratureNote(item({}));
    expect(md).toContain("[Open in Zotero](zotero://select/library/items/ABCD)");
    expect(md).toContain("## Abstract");
    expect(md).toContain("We propose the Transformer.");
    expect(md).toContain("## Annotations");
    expect(md).toContain("## Notes");
  });

  it("carries the DOI in frontmatter", () => {
    const md = buildLiteratureNote(item({}));
    expect(md).toContain('doi: "10.5555/x"');
  });

  it("still produces a matchable note for a sparse item (no abstract, no citekey)", () => {
    const md = buildLiteratureNote(item({ doi: "", url: "", citeKey: "", extra: {} }));
    expect(md).toContain('zotero-key: "ABCD"');
    expect(md).toContain("# Attention Is All You Need");
  });

  it("keeps the title readable even with quotes in it", () => {
    const md = buildLiteratureNote(item({ title: 'A "quoted" title' }));
    expect(md).toContain('# A "quoted" title');
  });
});

describe("custom template support", () => {
  it("substitutes placeholders in a user template", () => {
    const template = "# {{title}}\nby {{authors}} ({{year}})\nkey: {{key}}\n[[{{citeKey}}]]";
    const md = buildLiteratureNote(item({}), template);
    expect(md).toContain("# Attention Is All You Need");
    expect(md).toContain("by Vaswani et al. (2017)");
    expect(md).toContain("[[vaswani2017attention]]");
  });

  it("injects zotero-key frontmatter when a custom template omits it (so matching still works)", () => {
    // A user template with no frontmatter at all.
    const md = buildLiteratureNote(item({}), "# {{title}}\n\nMy notes here.");
    expect(md).toMatch(/^---\nzotero-key: "ABCD"\n---/);
    expect(md).toContain("# Attention Is All You Need");
  });

  it("injects zotero-key into an existing frontmatter block that lacks it", () => {
    const template = '---\ntitle: "{{title}}"\n---\n\n# {{title}}';
    const md = buildLiteratureNote(item({}), template);
    expect(md).toContain('zotero-key: "ABCD"');
    expect(md).toContain('title: "Attention Is All You Need"');
  });

  it("does not double-inject when the template already has zotero-key", () => {
    const template = '---\nzotero-key: "{{key}}"\n---\n\n# {{title}}';
    const md = buildLiteratureNote(item({}), template);
    expect((md.match(/zotero-key:/g) ?? []).length).toBe(1);
  });

  it("leaves unknown placeholders untouched", () => {
    const md = buildLiteratureNote(item({}), "{{title}} {{unknownField}}");
    expect(md).toContain("Attention Is All You Need {{unknownField}}");
  });

  it("falls back to the built-in default for an empty template", () => {
    const md = buildLiteratureNote(item({}), "");
    expect(md).toContain("## Abstract");
    expect(md).toContain("## Notes");
  });
});

// ---- A minimal in-memory App for the find-or-create logic ----

class FakeVault {
  files = new Map<string, { file: TFile; content: string }>();
  folders = new Set<string>();
  getMarkdownFiles(): TFile[] {
    return [...this.files.values()].map((f) => f.file);
  }
  getAbstractFileByPath(path: string): TFile | null {
    return this.files.get(path)?.file ?? (this.folders.has(path) ? new TFile() : null);
  }
  async createFolder(path: string): Promise<void> {
    this.folders.add(path);
  }
  async create(path: string, content: string): Promise<TFile> {
    const file = new TFile();
    file.path = path;
    file.basename = path.split("/").pop()!.replace(/\.md$/, "");
    this.files.set(path, { file, content });
    return file;
  }
  async read(file: TFile): Promise<string> {
    return this.files.get(file.path)?.content ?? "";
  }
  async modify(file: TFile, content: string): Promise<void> {
    this.files.set(file.path, { file, content });
  }
}

class FakeMetadataCache {
  constructor(private readonly vault: FakeVault) {}
  getFileCache(file: TFile): { frontmatter?: Record<string, unknown> } | null {
    const content = this.vault.files.get(file.path)?.content ?? "";
    const m = /zotero-key:\s*"([^"]+)"/.exec(content);
    return m ? { frontmatter: { "zotero-key": m[1] } } : { frontmatter: {} };
  }
}

function fakeApp(): { app: { vault: FakeVault; metadataCache: FakeMetadataCache }; vault: FakeVault } {
  const vault = new FakeVault();
  const metadataCache = new FakeMetadataCache(vault);
  return { app: { vault, metadataCache }, vault };
}

describe("find-or-create is idempotent by Zotero key", () => {
  it("creates a new note the first time", async () => {
    const { app, vault } = fakeApp();
    const result = await createOrOpenLiteratureNote(app as never, item({}), { folder: "Lit" });
    expect(result.created).toBe(true);
    expect(vault.files.size).toBe(1);
    expect(result.file.path).toBe("Lit/vaswani2017attention.md");
  });

  it("opens the existing note the second time — never a duplicate — even if the title changed", async () => {
    const { app, vault } = fakeApp();
    const first = await createOrOpenLiteratureNote(app as never, item({}), { folder: "Lit" });
    expect(first.created).toBe(true);
    // Same key, different title/folder: must resolve to the existing note.
    const second = await createOrOpenLiteratureNote(app as never, item({ title: "Renamed Paper" }), { folder: "OtherFolder" });
    expect(second.created).toBe(false);
    expect(second.file.path).toBe(first.file.path);
    expect(vault.files.size).toBe(1); // no duplicate
  });

  it("indexes notes by key and finds them", async () => {
    const { app } = fakeApp();
    await createOrOpenLiteratureNote(app as never, item({ key: "K1" }), { folder: "Lit" });
    await createOrOpenLiteratureNote(app as never, item({ key: "K2", citeKey: "smith2020", title: "Another" }), { folder: "Lit" });
    const index = indexLiteratureNotes(app as never);
    expect(index.size).toBe(2);
    expect(findLiteratureNote(app as never, "K1")).not.toBeNull();
    expect(findLiteratureNote(app as never, "MISSING")).toBeNull();
  });

  it("seeds annotations into the note when supplied", async () => {
    const { app, vault } = fakeApp();
    const anns: KvsAnnotation[] = [
      { id: "a1", kind: "highlight", text: "key passage", comment: "", page: 1, rects: [], source: "zotero", attachment: "zotero:ATT" },
    ];
    const result = await createOrOpenLiteratureNote(app as never, item({}), { folder: "Lit", annotations: anns });
    const content = vault.files.get(result.file.path)!.content;
    expect(content).toContain("key passage");
  });

  it("disambiguates the filename when two different papers share a sanitized name", async () => {
    const { app, vault } = fakeApp();
    await createOrOpenLiteratureNote(app as never, item({ key: "K1", citeKey: "", title: "Same Name" }), { folder: "Lit" });
    await createOrOpenLiteratureNote(app as never, item({ key: "K2", citeKey: "", title: "Same Name" }), { folder: "Lit" });
    const paths = [...vault.files.keys()].sort();
    expect(paths).toEqual(["Lit/Same Name (2).md", "Lit/Same Name.md"]);
  });
});
