/**
 * Offline distributional semantics via two-level Random Indexing (Kanerva / Sahlgren).
 *
 * No model, no network, fully deterministic. Each term gets a sparse random "index vector"; a term's
 * "context vector" accumulates the index vectors of the terms it co-occurs with across the vault. Terms
 * used in similar contexts therefore end up with similar context vectors — so a document (or query)
 * represented as the weighted sum of its terms' context vectors captures *topic*, letting a search find
 * related notes even without exact word overlap. It approximates LSA without an expensive SVD.
 */

export interface SemanticConfig {
  readonly dim: number; // vector dimension
  readonly nonzeros: number; // nonzero entries per index vector
  readonly window: number; // co-occurrence window (±tokens)
}
export const DEFAULT_SEMANTIC: SemanticConfig = { dim: 200, nonzeros: 10, window: 4 };

function hash32(s: string, salt: number): number {
  let h = (2166136261 ^ salt) >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface IndexVector {
  readonly pos: number[];
  readonly sign: number[];
}

export interface SemanticHit {
  readonly id: string;
  readonly score: number;
}

export class SemanticModel {
  private readonly cfg: SemanticConfig;
  private readonly idxCache = new Map<string, IndexVector>();
  private readonly context = new Map<string, Float32Array>();
  private readonly df = new Map<string, number>();
  private readonly docs: { id: string; vec: Float32Array }[] = [];
  private nDocs = 0;

  constructor(cfg: SemanticConfig = DEFAULT_SEMANTIC) {
    this.cfg = cfg;
  }

  get size(): number {
    return this.docs.length;
  }
  get vocab(): number {
    return this.context.size;
  }

  /** Deterministic sparse ternary index vector for a term (cached). */
  private indexVector(term: string): IndexVector {
    let iv = this.idxCache.get(term);
    if (iv) return iv;
    const rnd = mulberry32(hash32(term, 0x9e3779b9));
    const pos: number[] = [];
    const sign: number[] = [];
    const used = new Set<number>();
    while (pos.length < this.cfg.nonzeros) {
      const p = Math.floor(rnd() * this.cfg.dim);
      if (used.has(p)) continue;
      used.add(p);
      pos.push(p);
      sign.push(rnd() < 0.5 ? -1 : 1);
    }
    iv = { pos, sign };
    this.idxCache.set(term, iv);
    return iv;
  }

  private ctx(term: string): Float32Array {
    let v = this.context.get(term);
    if (!v) {
      v = new Float32Array(this.cfg.dim);
      this.context.set(term, v);
    }
    return v;
  }

  /** Pass 1: accumulate co-occurrence + document frequency for one document's tokens. */
  observe(tokens: readonly string[]): void {
    this.nDocs++;
    const seen = new Set<string>();
    const W = this.cfg.window;
    for (let i = 0; i < tokens.length; i++) {
      const term = tokens[i]!;
      if (!seen.has(term)) {
        seen.add(term);
        this.df.set(term, (this.df.get(term) ?? 0) + 1);
      }
      const v = this.ctx(term);
      const lo = Math.max(0, i - W);
      const hi = Math.min(tokens.length - 1, i + W);
      for (let j = lo; j <= hi; j++) {
        if (j === i) continue;
        const iv = this.indexVector(tokens[j]!);
        for (let k = 0; k < iv.pos.length; k++) {
          const p = iv.pos[k]!;
          v[p] = (v[p] ?? 0) + iv.sign[k]!;
        }
      }
    }
  }

  /** A tf-idf-weighted sum of term context vectors, L2-normalised. Used for both docs and queries. */
  vectorFor(tokens: readonly string[]): Float32Array {
    const tf = new Map<string, number>();
    for (const t of tokens) tf.set(t, (tf.get(t) ?? 0) + 1);
    const out = new Float32Array(this.cfg.dim);
    for (const [term, f] of tf) {
      const c = this.context.get(term);
      if (!c) continue;
      const idf = Math.log(1 + this.nDocs / (1 + (this.df.get(term) ?? 0)));
      const w = (1 + Math.log(f)) * idf;
      for (let k = 0; k < out.length; k++) out[k]! += w * c[k]!;
    }
    let norm = 0;
    for (let k = 0; k < out.length; k++) norm += out[k]! * out[k]!;
    norm = Math.sqrt(norm) || 1;
    for (let k = 0; k < out.length; k++) out[k]! /= norm;
    return out;
  }

  /** Pass 2: compute + store a document's vector (call after all observe()). */
  addDocVector(id: string, tokens: readonly string[]): void {
    this.docs.push({ id, vec: this.vectorFor(tokens) });
  }

  /** Rank stored documents by cosine similarity to the query tokens. */
  search(tokens: readonly string[], limit = 50): SemanticHit[] {
    if (this.docs.length === 0) return [];
    const q = this.vectorFor(tokens);
    const out: SemanticHit[] = [];
    for (const d of this.docs) {
      let dot = 0;
      for (let k = 0; k < q.length; k++) dot += q[k]! * d.vec[k]!;
      if (dot > 0) out.push({ id: d.id, score: dot });
    }
    out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    return out.slice(0, limit);
  }

  /** Cosine similarity between two token sets (for a "related notes" affordance). */
  similarity(a: readonly string[], b: readonly string[]): number {
    const va = this.vectorFor(a);
    const vb = this.vectorFor(b);
    let dot = 0;
    for (let k = 0; k < va.length; k++) dot += va[k]! * vb[k]!;
    return dot;
  }

  /** Serialise for persistence (structured-clone friendly — keeps typed arrays). */
  toSnapshot(): SemanticSnapshot {
    return {
      version: 1,
      cfg: this.cfg,
      nDocs: this.nDocs,
      dfTerms: [...this.df.keys()],
      dfCounts: Int32Array.from(this.df.values()),
      ctxTerms: [...this.context.keys()],
      ctxVecs: [...this.context.values()],
      docIds: this.docs.map((d) => d.id),
      docVecs: this.docs.map((d) => d.vec),
    };
  }

  static fromSnapshot(s: SemanticSnapshot): SemanticModel {
    const m = new SemanticModel(s.cfg);
    m.nDocs = s.nDocs;
    for (let i = 0; i < s.dfTerms.length; i++) m.df.set(s.dfTerms[i]!, s.dfCounts[i]!);
    for (let i = 0; i < s.ctxTerms.length; i++) m.context.set(s.ctxTerms[i]!, s.ctxVecs[i]!);
    for (let i = 0; i < s.docIds.length; i++) m.docs.push({ id: s.docIds[i]!, vec: s.docVecs[i]! });
    return m;
  }
}

export interface SemanticSnapshot {
  readonly version: 1;
  readonly cfg: SemanticConfig;
  readonly nDocs: number;
  readonly dfTerms: string[];
  readonly dfCounts: Int32Array;
  readonly ctxTerms: string[];
  readonly ctxVecs: Float32Array[];
  readonly docIds: string[];
  readonly docVecs: Float32Array[];
}
