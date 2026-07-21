import { TFile, type App } from "obsidian";
import { normalizeUrl } from "../../../shared/protocol";
import {
  coerceAnnotation,
  withAnnotation,
  withoutAnnotation,
  annotationNoteBlock,
  type PageAnnotations,
  type StoredAnnotation,
} from "../../../shared/annotations";
import { appendToNote } from "../capture/append-note";
import { findDedicatedNote } from "../notes/dedicated-note";

/**
 * Where web annotations live in the vault.
 *
 * The sidecar is the machine's copy — anchors, colours, ids — and it is the *only* source the page painter
 * reads. The row cell and the dedicated note get human-readable copies at annotation time, and people can
 * edit those freely without breaking a single highlight, because nothing ever parses them back.
 *
 * It's one JSON file in the plugin's own directory, keyed by normalized URL. Not a note: annotations are
 * bookkeeping, and bookkeeping disguised as a note invites the hand-edits that would corrupt it. Being a
 * file in the vault directory still means it syncs wherever the vault syncs.
 */

/** Parse whatever the sidecar file holds, dropping what can't paint. */
export function parseStore(raw: string): Map<string, PageAnnotations> {
  const out = new Map<string, PageAnnotations>();
  if (raw.trim() === "") return out;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    // A corrupt sidecar loses its highlights; it must not lose the vault's ability to annotate at all.
    return out;
  }
  if (parsed === null || typeof parsed !== "object") return out;
  for (const [url, value] of Object.entries(parsed as Record<string, unknown>)) {
    const list = (value as { annotations?: unknown } | null)?.annotations;
    if (!Array.isArray(list)) continue;
    const annotations = list
      .map(coerceAnnotation)
      .filter((a): a is StoredAnnotation => a !== null);
    if (annotations.length > 0) out.set(url, { url, annotations });
  }
  return out;
}

export function serializeStore(store: ReadonlyMap<string, PageAnnotations>): string {
  const out: Record<string, PageAnnotations> = {};
  for (const [url, page] of store) {
    if (page.annotations.length > 0) out[url] = page;
  }
  return JSON.stringify(out, null, 2);
}

export interface WebAnnotationDeps {
  readonly app: App;
  /** Absolute vault-relative path of the sidecar file. */
  readonly storePath: string;
}

export class WebAnnotationService {
  private cache: Map<string, PageAnnotations> | null = null;

  constructor(private readonly deps: WebAnnotationDeps) {}

  private async load(): Promise<Map<string, PageAnnotations>> {
    if (this.cache !== null) return this.cache;
    try {
      const exists = await this.deps.app.vault.adapter.exists(this.deps.storePath);
      const raw = exists ? await this.deps.app.vault.adapter.read(this.deps.storePath) : "";
      this.cache = parseStore(raw);
    } catch {
      this.cache = new Map();
    }
    return this.cache;
  }

  private async persist(): Promise<void> {
    if (this.cache === null) return;
    await this.deps.app.vault.adapter.write(this.deps.storePath, serializeStore(this.cache));
  }

  /** Everything saved for a page, matched loosely the way the rest of the bridge matches URLs. */
  async list(url: string): Promise<readonly StoredAnnotation[]> {
    const store = await this.load();
    return store.get(normalizeUrl(url))?.annotations ?? [];
  }

  async save(annotation: StoredAnnotation): Promise<void> {
    const store = await this.load();
    const key = normalizeUrl(annotation.url);
    const page = store.get(key) ?? { url: key, annotations: [] };
    store.set(key, withAnnotation(page, annotation));
    await this.persist();
  }

  /** Remove by id, returning what was removed so its human copies can be cleaned up too. */
  async remove(url: string, id: string): Promise<StoredAnnotation | null> {
    const store = await this.load();
    const key = normalizeUrl(url);
    const page = store.get(key);
    if (page === undefined) return null;
    const removed = page.annotations.find((a) => a.id === id) ?? null;
    store.set(key, withoutAnnotation(page, id));
    await this.persist();
    return removed;
  }

  /** Remove every annotation for a page. Returns how many went. */
  async removeAll(url: string): Promise<number> {
    const store = await this.load();
    const key = normalizeUrl(url);
    const count = store.get(key)?.annotations.length ?? 0;
    if (count > 0) {
      store.delete(key);
      await this.persist();
    }
    return count;
  }

  /**
   * Append an annotation's markdown block under `## Annotations` in the page's dedicated note, if the page
   * has one. Creating the heading when the template lacks it is deliberate: a highlight should never be
   * silently dropped because a note was made from an older template.
   */
  async appendToDedicatedNote(
    matchKey: string,
    matchValue: string,
    annotation: StoredAnnotation,
  ): Promise<boolean> {
    const note = findDedicatedNote(this.deps.app, matchKey, matchValue);
    if (note === null || !(note instanceof TFile)) return false;
    const existing = await this.deps.app.vault.read(note);
    const result = appendToNote(existing, annotationNoteBlock(annotation), {
      heading: "Annotations",
      createHeading: true,
    });
    if (!result.ok) return false;
    await this.deps.app.vault.modify(note, result.content);
    return true;
  }
}
