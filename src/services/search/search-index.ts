import { parseQuery, type QueryNode } from "./query";
import { tokenize } from "./tokenize";

/** A unit of indexed content. `text` is the body; `fields` are small named facets (title, author, …)
 *  that are both searchable field-scoped and folded into the body so a plain query finds them too. */
export interface IndexDoc {
  readonly id: string;
  readonly text: string;
  readonly fields?: Readonly<Record<string, string>>;
  readonly source: string; // e.g. "note" | "row" | "pdf" | "docx" — used for scope filters + boosts
  readonly format?: string; // e.g. "pdf" | "docx" — used for format filters
  readonly location?: string; // page / heading / cell — for display + jump-to
  readonly meta?: Readonly<Record<string, string | number>>;
}

export interface SearchResult {
  readonly id: string;
  readonly score: number;
  readonly source: string;
  readonly format?: string;
  readonly location?: string;
  readonly meta?: Readonly<Record<string, string | number>>;
}

export interface SearchOptions {
  readonly limit?: number;
  readonly sources?: ReadonlySet<string>; // include only these sources
  readonly formats?: ReadonlySet<string>; // include only these formats
  readonly folders?: readonly string[]; // include only docs whose path is under one of these folders
  readonly matchMode?: "all" | "any"; // operator between adjacent terms (default "all")
  readonly fuzzy?: boolean; // expand terms to prefix + edit-distance matches (typo tolerance)
  readonly boosts?: Readonly<Record<string, number>>; // per-source score multiplier
  readonly fieldBoosts?: Readonly<Record<string, number>>; // extra weight for matches in a field (title, heading, tag)
}

/** Compact postings for one term: parallel arrays, docs strictly ascending (append-only keeps them so),
 *  positions concatenated flat and sliced by `tfs` — avoids an array-per-doc and scales to millions of
 *  postings. `offsets` (cumulative tf sums) is built lazily only when phrase matching needs it. */
interface Postings {
  docs: number[];
  tfs: number[];
  pos: number[];
  offsets?: number[];
}

interface DocMeta {
  readonly id: string;
  readonly source: string;
  readonly format?: string;
  readonly location?: string;
  readonly path?: string; // source file path (for folder scoping)
  readonly meta?: Readonly<Record<string, string | number>>;
  readonly length: number; // token count of the body stream (for BM25 length norm)
}

export interface IndexSnapshot {
  readonly v: 1;
  readonly docs: (DocMeta | null)[];
  readonly terms: string[];
  readonly postings: { docs: number[]; tfs: number[]; pos: number[] }[];
  readonly liveCount: number;
  readonly liveLengthSum: number;
  readonly deletedCount: number;
}

const K1 = 1.2;
const B = 0.75;
const FIELD_SEP = "\u0000";

/**
 * An in-memory inverted index with BM25 ranking, boolean + phrase queries, field scoping, per-source
 * filters/boosts, incremental add/remove (tombstoned for O(1) delete), and structured-clone-friendly
 * serialisation for persistence. Built for scale: integer doc refs, flat postings, lazy offsets.
 */
export class SearchIndex {
  private docs: (DocMeta | null)[] = [];
  private readonly refById = new Map<string, number>();
  private readonly postings = new Map<string, Postings>();
  private liveCount = 0;
  private liveLengthSum = 0;
  private deletedCount = 0;
  private sortedTerms: string[] | null = null; // lazy sorted vocabulary for prefix expansion

  get size(): number {
    return this.liveCount;
  }

  has(id: string): boolean {
    return this.refById.has(id);
  }

  /** Add (or replace) a document. Replacing tombstones the old copy first. */
  add(doc: IndexDoc): void {
    if (this.refById.has(doc.id)) this.remove(doc.id);
    const ref = this.docs.length;
    const bodyTokens = tokenize(doc.text);
    const fields = doc.fields ? Object.entries(doc.fields) : [];
    // Body stream = text + all field values, so a plain query also matches titles/authors.
    const stream = bodyTokens.slice();
    for (const [, v] of fields) for (const t of tokenize(v)) stream.push(t);
    this.indexTokens(ref, "", stream);
    for (const [name, v] of fields) this.indexTokens(ref, name.toLowerCase(), tokenize(v));
    this.docs.push({
      id: doc.id,
      source: doc.source,
      length: stream.length,
      ...(doc.format ? { format: doc.format } : {}),
      ...(doc.location ? { location: doc.location } : {}),
      ...(typeof doc.meta?.["path"] === "string" ? { path: doc.meta["path"] } : {}),
      ...(doc.meta ? { meta: doc.meta } : {}),
    });
    this.refById.set(doc.id, ref);
    this.liveCount++;
    this.liveLengthSum += stream.length;
  }

  /** Remove a document (tombstone; postings are reclaimed by compact()). */
  remove(id: string): void {
    const ref = this.refById.get(id);
    if (ref === undefined) return;
    const meta = this.docs[ref];
    if (meta) {
      this.liveCount--;
      this.liveLengthSum -= meta.length;
    }
    this.docs[ref] = null;
    this.refById.delete(id);
    this.deletedCount++;
  }

  private indexTokens(ref: number, field: string, tokens: readonly string[]): void {
    if (tokens.length === 0) return;
    const byTerm = new Map<string, number[]>();
    for (let i = 0; i < tokens.length; i++) {
      const term = tokens[i]!;
      const arr = byTerm.get(term);
      if (arr) arr.push(i);
      else byTerm.set(term, [i]);
    }
    for (const [term, positions] of byTerm) {
      const key = field === "" ? term : `${field}${FIELD_SEP}${term}`;
      let p = this.postings.get(key);
      if (!p) {
        p = { docs: [], tfs: [], pos: [] };
        this.postings.set(key, p);
        this.sortedTerms = null; // vocabulary grew — invalidate prefix cache
      }
      p.docs.push(ref); // ref increases monotonically → docs stays sorted
      p.tfs.push(positions.length);
      for (const pos of positions) p.pos.push(pos);
      p.offsets = undefined; // invalidate lazy cache
    }
  }

  /** Build a display result for a doc id (used to render semantic hits, which carry their own score). */
  resultFor(id: string, score: number): SearchResult | null {
    const ref = this.refById.get(id);
    if (ref === undefined) return null;
    const meta = this.docs[ref];
    if (!meta) return null;
    return {
      id: meta.id,
      score,
      source: meta.source,
      ...(meta.format ? { format: meta.format } : {}),
      ...(meta.location ? { location: meta.location } : {}),
      ...(meta.meta ? { meta: meta.meta } : {}),
    };
  }

  /** Search and return ranked results. Never throws on bad queries. */
  search(query: string, options: SearchOptions = {}): SearchResult[] {
    const ast = parseQuery(query, { defaultOp: options.matchMode === "any" ? "or" : "and" });
    if (ast.type === "empty") return [];
    const fuzzy = options.fuzzy ?? false;
    const scoreKeys = new Set<string>();
    const candidates = this.evaluate(ast, fuzzy, scoreKeys, true);
    if (candidates.size === 0) return [];
    // Boost matches in high-signal fields (a hit in a title/heading/tag outranks one buried in the body).
    const fieldBoosts = options.fieldBoosts ?? { title: 3, heading: 2, tag: 1.6 };
    const keyBoost = new Map<string, number>();
    for (const key of scoreKeys) {
      keyBoost.set(key, 1);
      if (!key.includes(FIELD_SEP)) {
        for (const [field, boost] of Object.entries(fieldBoosts)) keyBoost.set(`${field}${FIELD_SEP}${key}`, boost);
      }
    }
    const keys = [...keyBoost.keys()];
    const idf = new Map<string, number>();
    for (const key of keys) idf.set(key, this.idf(key));
    const avgdl = this.liveCount > 0 ? this.liveLengthSum / this.liveCount : 1;
    const folders = options.folders && options.folders.length > 0 ? options.folders.map((f) => (f.endsWith("/") ? f : `${f}/`)) : null;
    const results: SearchResult[] = [];
    for (const ref of candidates) {
      const meta = this.docs[ref];
      if (!meta) continue; // tombstoned
      if (options.sources && !options.sources.has(meta.source)) continue;
      if (options.formats && meta.format !== undefined && !options.formats.has(meta.format)) continue;
      if (folders && !inFolders(meta.path, folders)) continue;
      let score = 0;
      for (const key of keys) {
        const tf = this.tfOf(key, ref);
        if (tf === 0) continue;
        const w = idf.get(key)!;
        score += keyBoost.get(key)! * ((w * (tf * (K1 + 1))) / (tf + K1 * (1 - B + (B * meta.length) / avgdl)));
      }
      const boost = options.boosts?.[meta.source];
      if (boost !== undefined) score *= boost;
      results.push({
        id: meta.id,
        score,
        source: meta.source,
        ...(meta.format ? { format: meta.format } : {}),
        ...(meta.location ? { location: meta.location } : {}),
        ...(meta.meta ? { meta: meta.meta } : {}),
      });
    }
    results.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    return results.slice(0, options.limit ?? 50);
  }

  // ---- prefix expansion (fuzzy / partial matching) ----

  private ensureSortedTerms(): string[] {
    if (!this.sortedTerms) this.sortedTerms = [...this.postings.keys()].sort();
    return this.sortedTerms;
  }

  /** Posting keys beginning with `prefix` (bounded), for term expansion. */
  private termsWithPrefix(prefix: string, limit: number): string[] {
    const terms = this.ensureSortedTerms();
    let lo = 0;
    let hi = terms.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (terms[mid]! < prefix) lo = mid + 1;
      else hi = mid;
    }
    const out: string[] = [];
    for (let i = lo; i < terms.length && out.length < limit; i++) {
      if (!terms[i]!.startsWith(prefix)) break;
      out.push(terms[i]!);
    }
    return out;
  }

  /** Posting keys a term should match: exact, prefix, and (fuzzy) edit-distance neighbours. */
  private expandKeys(field: string | undefined, value: string, fuzzy: boolean): string[] {
    const exact = field ? `${field}${FIELD_SEP}${value}` : value;
    if (!fuzzy || value.length < 3) return [exact];
    const keys = new Set<string>(this.termsWithPrefix(exact, 50));
    keys.add(exact);
    // Edit-distance neighbours (transposition/substitution/insertion) for default-field terms.
    if (field === undefined && value.length >= 4) {
      const maxEdits = value.length <= 5 ? 1 : 2;
      const terms = this.ensureSortedTerms();
      let added = 0;
      for (const t of terms) {
        if (added >= 50) break;
        if (t.includes(FIELD_SEP) || keys.has(t)) continue;
        if (Math.abs(t.length - value.length) > maxEdits) continue;
        if (boundedEdit(value, t, maxEdits) <= maxEdits) {
          keys.add(t);
          added++;
        }
      }
    }
    return [...keys];
  }

  /** Default-field posting keys whose term matches the regex (bounded). */
  private termsMatchingRegex(source: string, flags: string, limit: number): string[] {
    let re: RegExp;
    try {
      re = new RegExp(source, flags.replace(/g/g, ""));
    } catch {
      return [];
    }
    const out: string[] = [];
    for (const t of this.ensureSortedTerms()) {
      if (t.includes(FIELD_SEP)) continue;
      if (re.test(t)) {
        out.push(t);
        if (out.length >= limit) break;
      }
    }
    return out;
  }

  // ---- boolean evaluation over postings ----

  private evaluate(node: QueryNode, fuzzy: boolean, scoreKeys: Set<string>, collect: boolean): Set<number> {
    switch (node.type) {
      case "term": {
        const keys = this.expandKeys(node.field, node.value, fuzzy);
        if (collect) for (const k of keys) scoreKeys.add(k);
        const s = new Set<number>();
        for (const k of keys) for (const r of this.postings.get(k)?.docs ?? []) s.add(r);
        return s;
      }
      case "phrase": {
        // phrases need exact adjacency — don't prefix-expand their terms
        if (collect) for (const t of node.terms) scoreKeys.add(node.field ? `${node.field}${FIELD_SEP}${t}` : t);
        return this.phraseSet(node.field, node.terms);
      }
      case "regex": {
        const keys = this.termsMatchingRegex(node.source, node.flags, 200);
        if (collect) for (const k of keys) scoreKeys.add(k);
        const s = new Set<number>();
        for (const k of keys) for (const r of this.postings.get(k)?.docs ?? []) s.add(r);
        return s;
      }
      case "or": {
        const s = new Set<number>();
        for (const c of node.children) if (c.type !== "not") for (const r of this.evaluate(c, fuzzy, scoreKeys, collect)) s.add(r);
        return s;
      }
      case "and": {
        let s: Set<number> | null = null;
        for (const c of node.children) {
          if (c.type === "not") continue;
          const cs = this.evaluate(c, fuzzy, scoreKeys, collect);
          s = s === null ? cs : intersect(s, cs);
          if (s.size === 0) break;
        }
        if (s === null) s = this.allLiveRefs();
        for (const c of node.children) if (c.type === "not") for (const r of this.evaluate(c.child, fuzzy, scoreKeys, false)) s.delete(r);
        return s;
      }
      case "not": {
        const s = this.allLiveRefs();
        for (const r of this.evaluate(node.child, fuzzy, scoreKeys, false)) s.delete(r);
        return s;
      }
      case "empty":
        return new Set();
    }
  }

  private docsSet(key: string): Set<number> {
    const p = this.postings.get(key);
    return p ? new Set(p.docs) : new Set();
  }

  private allLiveRefs(): Set<number> {
    const s = new Set<number>();
    for (const ref of this.refById.values()) s.add(ref);
    return s;
  }

  private ensureOffsets(p: Postings): number[] {
    if (p.offsets) return p.offsets;
    const offs = new Array<number>(p.docs.length + 1);
    offs[0] = 0;
    for (let i = 0; i < p.tfs.length; i++) offs[i + 1] = offs[i]! + p.tfs[i]!;
    p.offsets = offs;
    return offs;
  }

  private positionsOf(key: string, ref: number): number[] {
    const p = this.postings.get(key);
    if (!p) return [];
    const idx = lowerBound(p.docs, ref);
    if (idx >= p.docs.length || p.docs[idx] !== ref) return [];
    const offs = this.ensureOffsets(p);
    return p.pos.slice(offs[idx], offs[idx + 1]);
  }

  /** Docs where `terms` occur consecutively (position p, p+1, …). */
  private phraseSet(field: string | undefined, terms: readonly string[]): Set<number> {
    if (terms.length === 0) return new Set();
    const keys = terms.map((t) => (field ? `${field}${FIELD_SEP}${t}` : t));
    if (terms.length === 1) return this.docsSet(keys[0]!);
    // candidate docs = intersection of all terms' docs
    let cand: Set<number> | null = null;
    for (const key of keys) {
      const cs = this.docsSet(key);
      cand = cand === null ? cs : intersect(cand, cs);
      if (cand.size === 0) return new Set();
    }
    const out = new Set<number>();
    for (const ref of cand!) {
      const first = new Set(this.positionsOf(keys[0]!, ref));
      let ok = false;
      for (const start of first) {
        let matched = true;
        for (let k = 1; k < keys.length; k++) {
          if (!this.positionsOf(keys[k]!, ref).includes(start + k)) {
            matched = false;
            break;
          }
        }
        if (matched) {
          ok = true;
          break;
        }
      }
      if (ok) out.add(ref);
    }
    return out;
  }

  // ---- BM25 helpers ----

  private tfOf(key: string, ref: number): number {
    const p = this.postings.get(key);
    if (!p) return 0;
    const idx = lowerBound(p.docs, ref);
    return idx < p.docs.length && p.docs[idx] === ref ? p.tfs[idx]! : 0;
  }

  /** Public idf for a plain term (used by extractive QA to weight passage matches). */
  termIdf(term: string): number {
    return this.idf(term);
  }

  private idf(key: string): number {
    const p = this.postings.get(key);
    // live document frequency (postings may include tombstoned refs)
    let df = 0;
    if (p) for (const ref of p.docs) if (this.docs[ref]) df++;
    const n = this.liveCount;
    return Math.log(1 + (n - df + 0.5) / (df + 0.5));
  }

  // ---- maintenance + persistence ----

  /** Fraction of stored docs that are tombstoned — a signal to compact(). */
  get wastedFraction(): number {
    return this.docs.length === 0 ? 0 : this.deletedCount / this.docs.length;
  }

  /** Rebuild without tombstones, renumbering refs — call when wastedFraction is high. */
  compact(): void {
    const live: IndexDoc[] = [];
    // We can't recover token text from postings, so compaction rebuilds from a fresh add stream is not
    // possible here; instead squeeze tombstones out of the ref space by remapping.
    const remap = new Map<number, number>();
    let next = 0;
    const newDocs: DocMeta[] = [];
    for (let ref = 0; ref < this.docs.length; ref++) {
      const m = this.docs[ref];
      if (m) {
        remap.set(ref, next++);
        newDocs.push(m);
      }
    }
    for (const [, p] of this.postings) {
      const docs: number[] = [];
      const tfs: number[] = [];
      const pos: number[] = [];
      const offs = this.ensureOffsets(p);
      for (let i = 0; i < p.docs.length; i++) {
        const nr = remap.get(p.docs[i]!);
        if (nr === undefined) continue;
        docs.push(nr);
        tfs.push(p.tfs[i]!);
        for (let o = offs[i]!; o < offs[i + 1]!; o++) pos.push(p.pos[o]!);
      }
      p.docs = docs;
      p.tfs = tfs;
      p.pos = pos;
      p.offsets = undefined;
    }
    for (const [key, p] of [...this.postings]) if (p.docs.length === 0) this.postings.delete(key);
    this.docs = newDocs;
    this.refById.clear();
    newDocs.forEach((m, i) => this.refById.set(m.id, i));
    this.deletedCount = 0;
    this.sortedTerms = null;
    void live;
  }

  toSnapshot(): IndexSnapshot {
    const terms: string[] = [];
    const postings: { docs: number[]; tfs: number[]; pos: number[] }[] = [];
    for (const [key, p] of this.postings) {
      terms.push(key);
      postings.push({ docs: p.docs, tfs: p.tfs, pos: p.pos });
    }
    return { v: 1, docs: this.docs, terms, postings, liveCount: this.liveCount, liveLengthSum: this.liveLengthSum, deletedCount: this.deletedCount };
  }

  static fromSnapshot(snap: IndexSnapshot): SearchIndex {
    const idx = new SearchIndex();
    idx.docs = snap.docs;
    idx.liveCount = snap.liveCount;
    idx.liveLengthSum = snap.liveLengthSum;
    idx.deletedCount = snap.deletedCount;
    snap.docs.forEach((m, ref) => {
      if (m) idx.refById.set(m.id, ref);
    });
    for (let i = 0; i < snap.terms.length; i++) {
      const p = snap.postings[i]!;
      idx.postings.set(snap.terms[i]!, { docs: p.docs, tfs: p.tfs, pos: p.pos });
    }
    return idx;
  }
}

/** Levenshtein distance with an early-exit cap — returns max+1 as soon as the distance exceeds `max`. */
function boundedEdit(a: string, b: string, max: number): number {
  const la = a.length;
  const lb = b.length;
  if (Math.abs(la - lb) > max) return max + 1;
  let prev = new Array<number>(lb + 1);
  for (let j = 0; j <= lb; j++) prev[j] = j;
  for (let i = 1; i <= la; i++) {
    const cur = new Array<number>(lb + 1);
    cur[0] = i;
    let rowMin = i;
    for (let j = 1; j <= lb; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      const v = Math.min(prev[j]! + 1, cur[j - 1]! + 1, prev[j - 1]! + cost);
      cur[j] = v;
      if (v < rowMin) rowMin = v;
    }
    if (rowMin > max) return max + 1;
    prev = cur;
  }
  return prev[lb]!;
}

/** Whether a doc's path sits under any of the given folder prefixes (already normalised to end in /). */
function inFolders(path: string | undefined, folders: readonly string[]): boolean {
  if (typeof path !== "string") return false;
  return folders.some((f) => path.startsWith(f));
}

/** Intersect two ref sets, iterating the smaller. */
function intersect(a: Set<number>, b: Set<number>): Set<number> {
  const [small, large] = a.size <= b.size ? [a, b] : [b, a];
  const out = new Set<number>();
  for (const r of small) if (large.has(r)) out.add(r);
  return out;
}

/** First index i in a sorted array where arr[i] >= target. */
function lowerBound(arr: readonly number[], target: number): number {
  let lo = 0;
  let hi = arr.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (arr[mid]! < target) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}
