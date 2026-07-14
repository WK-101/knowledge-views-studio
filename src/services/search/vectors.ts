/**
 * A dense vector index: document id -> embedding, with cosine search.
 *
 * This is the storage and ranking layer, shared by both semantic engines:
 *   - the built-in one (Random Indexing), which learns from your vault and downloads nothing;
 *   - the optional neural one, whose embeddings come from a real sentence-transformer.
 *
 * Keeping it separate means the maths is pure and testable, and swapping the engine that *produces*
 * the vectors changes nothing about how they are stored, searched, or persisted.
 */

export interface VectorHit {
  readonly id: string;
  readonly score: number;
}

/** Cosine similarity of two equal-length vectors. Assumes neither is all-zero (guarded below). */
export function cosine(a: Float32Array, b: Float32Array): number {
  const n = Math.min(a.length, b.length);
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let i = 0; i < n; i++) {
    const x = a[i]!;
    const y = b[i]!;
    dot += x * y;
    na += x * x;
    nb += y * y;
  }
  if (na === 0 || nb === 0) return 0;
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

/** L2-normalise in place, so later cosine work is a plain dot product. */
export function normalize(v: Float32Array): Float32Array {
  let n = 0;
  for (let i = 0; i < v.length; i++) n += v[i]! * v[i]!;
  n = Math.sqrt(n);
  if (n === 0) return v;
  for (let i = 0; i < v.length; i++) v[i] = v[i]! / n;
  return v;
}

/** True when a vector carries some signal — i.e. it is not all zeros. A query made only of words the
 *  index has never seen produces an all-zero vector, which can only ever return nothing. */
export function hasSignal(v: Float32Array): boolean {
  for (let i = 0; i < v.length; i++) if (v[i] !== 0) return true;
  return false;
}

export class VectorIndex {
  private readonly ids: string[] = [];
  private readonly vecs: Float32Array[] = [];
  private readonly byId = new Map<string, number>();

  get size(): number {
    return this.ids.length;
  }

  add(id: string, vec: Float32Array): void {
    const existing = this.byId.get(id);
    if (existing !== undefined) {
      this.vecs[existing] = vec;
      return;
    }
    this.byId.set(id, this.ids.length);
    this.ids.push(id);
    this.vecs.push(vec);
  }

  get(id: string): Float32Array | undefined {
    const i = this.byId.get(id);
    return i === undefined ? undefined : this.vecs[i];
  }

  /** Nearest documents to a query vector. `exclude` drops ids you already have (e.g. the note itself). */
  search(query: Float32Array, limit = 20, exclude?: (id: string) => boolean): VectorHit[] {
    if (this.ids.length === 0 || !hasSignal(query)) return [];
    const out: VectorHit[] = [];
    for (let i = 0; i < this.ids.length; i++) {
      const id = this.ids[i]!;
      if (exclude?.(id)) continue;
      const score = cosine(query, this.vecs[i]!);
      if (score > 0) out.push({ id, score });
    }
    out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    return out.slice(0, limit);
  }

  /** Documents most like a given document — the primitive behind "related notes". */
  similarTo(id: string, limit = 10, exclude?: (other: string) => boolean): VectorHit[] {
    const vec = this.get(id);
    if (!vec) return [];
    return this.search(vec, limit, (other) => other === id || (exclude?.(other) ?? false));
  }

  /** Mean of several vectors — how a whole note (many chunks) gets one representation. */
  static mean(vectors: readonly Float32Array[]): Float32Array {
    if (vectors.length === 0) return new Float32Array(0);
    const dim = vectors[0]!.length;
    const out = new Float32Array(dim);
    for (const v of vectors) for (let i = 0; i < dim; i++) out[i] = out[i]! + (v[i] ?? 0);
    for (let i = 0; i < dim; i++) out[i] = out[i]! / vectors.length;
    return normalize(out);
  }

  toSnapshot(): { ids: string[]; vecs: Float32Array[] } {
    return { ids: [...this.ids], vecs: [...this.vecs] };
  }

  static fromSnapshot(s: { ids: string[]; vecs: Float32Array[] }): VectorIndex {
    const idx = new VectorIndex();
    for (let i = 0; i < s.ids.length; i++) idx.add(s.ids[i]!, s.vecs[i]!);
    return idx;
  }
}
