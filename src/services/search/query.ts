import { tokenize } from "./tokenize";

/**
 * Query AST. A parsed query is a boolean tree over terms and phrases, each optionally scoped to a field.
 * The engine turns this into set operations over postings, then scores the survivors with BM25.
 */
export type QueryNode =
  | { readonly type: "term"; readonly field?: string; readonly value: string }
  | { readonly type: "phrase"; readonly field?: string; readonly terms: readonly string[] }
  | { readonly type: "regex"; readonly source: string; readonly flags: string }
  | { readonly type: "and"; readonly children: readonly QueryNode[] }
  | { readonly type: "or"; readonly children: readonly QueryNode[] }
  | { readonly type: "not"; readonly child: QueryNode }
  | { readonly type: "empty" };

export interface ParseOptions {
  /** Operator applied between adjacent terms with no explicit AND/OR (default "and"). */
  readonly defaultOp?: "and" | "or";
}

interface Tok {
  readonly t: "term" | "phrase" | "regex" | "and" | "or" | "not" | "(" | ")";
  readonly v?: string;
  readonly field?: string;
  readonly flags?: string;
}

/** Lex a raw query string into tokens, recognising "phrases", field: prefixes, -exclusion, and parens. */
function lex(input: string): Tok[] {
  const toks: Tok[] = [];
  let i = 0;
  const n = input.length;
  while (i < n) {
    const ch = input[i]!;
    if (/\s/.test(ch)) {
      i++;
      continue;
    }
    if (ch === "(") {
      toks.push({ t: "(" });
      i++;
      continue;
    }
    if (ch === ")") {
      toks.push({ t: ")" });
      i++;
      continue;
    }
    if (ch === "/") {
      let end = -1;
      for (let k = i + 1; k < n; k++) {
        if (input[k] === "\\") {
          k++;
          continue;
        }
        if (input[k] === "/") {
          end = k;
          break;
        }
      }
      if (end !== -1) {
        const src = input.slice(i + 1, end);
        let f = end + 1;
        let flags = "";
        while (f < n && "gimsuy".includes(input[f]!)) flags += input[f++];
        if (src !== "") toks.push({ t: "regex", v: src, flags });
        i = f;
        continue;
      }
    }
    if (ch === "-" && i + 1 < n && !/[\s()]/.test(input[i + 1]!)) {
      toks.push({ t: "not" });
      i++;
      continue;
    }
    // optional leading `field:` prefix
    let field: string | undefined;
    let j = i;
    const fm = /^([\p{L}\p{N}_]+):/u.exec(input.slice(i));
    if (fm) {
      field = fm[1]!.toLowerCase();
      j = i + fm[0].length;
    }
    if (input[j] === '"') {
      const end = input.indexOf('"', j + 1);
      const raw = end === -1 ? input.slice(j + 1) : input.slice(j + 1, end);
      toks.push({ t: "phrase", v: raw, ...(field ? { field } : {}) });
      i = end === -1 ? n : end + 1;
      continue;
    }
    let k = j;
    while (k < n && !/[\s()"]/.test(input[k]!)) k++;
    const word = input.slice(j, k);
    i = k;
    if (!field) {
      const up = word.toUpperCase();
      if (up === "AND") {
        toks.push({ t: "and" });
        continue;
      }
      if (up === "OR") {
        toks.push({ t: "or" });
        continue;
      }
      if (up === "NOT") {
        toks.push({ t: "not" });
        continue;
      }
    }
    if (word !== "") toks.push({ t: "term", v: word, ...(field ? { field } : {}) });
  }
  return toks;
}

/** Insert the default operator between adjacent operands so "foo bar" becomes "foo <op> bar". */
function insertImplicit(toks: readonly Tok[], op: "and" | "or"): Tok[] {
  const out: Tok[] = [];
  for (let i = 0; i < toks.length; i++) {
    const cur = toks[i]!;
    out.push(cur);
    const next = toks[i + 1];
    if (!next) break;
    const endsOperand = cur.t === "term" || cur.t === "phrase" || cur.t === "regex" || cur.t === ")";
    const startsOperand = next.t === "term" || next.t === "phrase" || next.t === "regex" || next.t === "not" || next.t === "(";
    if (endsOperand && startsOperand) out.push({ t: op });
  }
  return out;
}

/** Normalise a raw term/phrase value through the same tokeniser the index uses. */
function leaf(field: string | undefined, value: string): QueryNode | null {
  const terms = tokenize(value);
  if (terms.length === 0) return null;
  if (terms.length === 1) return { type: "term", value: terms[0]!, ...(field ? { field } : {}) };
  return { type: "phrase", terms, ...(field ? { field } : {}) };
}

/** Recursive-descent parser with precedence OR < AND < NOT < atom. */
class Parser {
  private pos = 0;
  constructor(private readonly toks: readonly Tok[]) {}

  private peek(): Tok | undefined {
    return this.toks[this.pos];
  }
  private next(): Tok | undefined {
    return this.toks[this.pos++];
  }

  parse(): QueryNode {
    if (this.toks.length === 0) return { type: "empty" };
    const node = this.parseOr();
    return node ?? { type: "empty" };
  }

  private parseOr(): QueryNode | null {
    let left = this.parseAnd();
    const children: QueryNode[] = left ? [left] : [];
    while (this.peek()?.t === "or") {
      this.next();
      const right = this.parseAnd();
      if (right) children.push(right);
    }
    if (children.length === 0) return null;
    if (children.length === 1) return children[0]!;
    left = { type: "or", children };
    return left;
  }

  private parseAnd(): QueryNode | null {
    const children: QueryNode[] = [];
    let node = this.parseNot();
    if (node) children.push(node);
    while (this.peek()?.t === "and") {
      this.next();
      node = this.parseNot();
      if (node) children.push(node);
    }
    if (children.length === 0) return null;
    if (children.length === 1) return children[0]!;
    return { type: "and", children };
  }

  private parseNot(): QueryNode | null {
    if (this.peek()?.t === "not") {
      this.next();
      const child = this.parseNot();
      return child ? { type: "not", child } : null;
    }
    return this.parseAtom();
  }

  private parseAtom(): QueryNode | null {
    const tok = this.peek();
    if (!tok) return null;
    if (tok.t === "(") {
      this.next();
      const inner = this.parseOr();
      if (this.peek()?.t === ")") this.next();
      return inner;
    }
    if (tok.t === "regex") {
      this.next();
      return { type: "regex", source: tok.v ?? "", flags: tok.flags ?? "" };
    }
    if (tok.t === "term" || tok.t === "phrase") {
      this.next();
      return leaf(tok.field, tok.v ?? "");
    }
    // stray operator/paren — skip it
    this.next();
    return null;
  }
}

/** Parse a query string into an AST. Never throws: malformed input degrades to whatever parsed. */
export function parseQuery(input: string, options: ParseOptions = {}): QueryNode {
  const toks = insertImplicit(lex(input), options.defaultOp ?? "and");
  return new Parser(toks).parse();
}

/** Collect the scoring keys (field-scoped terms) from positive (non-NOT) leaves, for BM25. */
export function scoringTerms(node: QueryNode, out: string[] = []): string[] {
  switch (node.type) {
    case "term":
      out.push(node.field ? `${node.field}\u0000${node.value}` : node.value);
      break;
    case "phrase":
      for (const t of node.terms) out.push(node.field ? `${node.field}\u0000${t}` : t);
      break;
    case "and":
    case "or":
      for (const c of node.children) scoringTerms(c, out);
      break;
    case "regex":
    case "not":
    case "empty":
      break;
  }
  return out;
}
