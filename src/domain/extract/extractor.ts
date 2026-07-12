import type { Row, SourceFileMeta } from "../model";

export interface ExtractionInput {
  readonly file: SourceFileMeta;
  /** Markdown/text content; "" for binary-only sources (which read `bytes` instead). */
  readonly content: string;
  /** Raw bytes, present for binary sources (e.g. xlsx). Text extractors ignore it. */
  readonly bytes?: ArrayBuffer;
  /** Per-source options (e.g. xlsx sheet / header row). Text extractors ignore it. */
  readonly options?: Readonly<Record<string, string>>;
}

/**
 * A source extractor turns one note into zero or more rows. Tables are the
 * flagship source, but the registry is open: frontmatter, inline `key:: value`
 * fields, list items, and tasks can each be added as their own extractor
 * without touching the rest of the pipeline. This is what keeps the plugin from
 * being locked to a single use case.
 */
export interface SourceExtractor {
  readonly id: string;
  readonly label: string;
  /** File extensions (lowercased, no dot) this extractor applies to; defaults to `["md"]`. */
  readonly extensions?: readonly string[];
  extract(input: ExtractionInput): Row[];
}

export class ExtractorRegistry {
  private readonly extractors = new Map<string, SourceExtractor>();

  register(extractor: SourceExtractor): this {
    this.extractors.set(extractor.id, extractor);
    return this;
  }

  get(id: string): SourceExtractor | undefined {
    return this.extractors.get(id);
  }

  has(id: string): boolean {
    return this.extractors.has(id);
  }

  all(): SourceExtractor[] {
    return [...this.extractors.values()];
  }
}
