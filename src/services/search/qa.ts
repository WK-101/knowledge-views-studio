/**
 * Extractive question answering: split documents into passages and rank them against a question. There
 * is no text generation (that needs a language model) — the "answer" is the set of most relevant
 * passages from the vault, with their sources. Pure + testable; the retrieval pipeline that feeds it
 * lives in the indexer.
 */
import { tokenize } from "./tokenize";

const STOPWORDS = new Set([
  "the", "a", "an", "of", "to", "in", "on", "and", "or", "is", "are", "was", "were", "be", "been", "being",
  "for", "with", "as", "by", "at", "that", "this", "it", "its", "from", "what", "how", "why", "when", "where",
  "who", "whom", "which", "do", "does", "did", "can", "could", "should", "would", "will", "shall", "may",
  "might", "i", "you", "we", "they", "he", "she", "my", "your", "our", "their", "about", "into", "than",
  "then", "there", "here", "so", "if", "but", "not", "no", "yes", "s", "t",
]);

export interface Passage {
  readonly text: string;
  readonly start: number;
  readonly end: number;
}

/** Content terms of a question (stopwords + question words removed). */
export function questionTerms(question: string): string[] {
  return [...new Set(tokenize(question).filter((t) => t.length > 1 && !STOPWORDS.has(t)))];
}

/** Split text into passages at sentence boundaries, merging short fragments up to ~minLen chars. */
export function splitPassages(text: string, minLen = 50, maxLen = 320): Passage[] {
  const out: Passage[] = [];
  const re = /[^.!?\n]+[.!?]*/g;
  let m: RegExpExecArray | null;
  let start = -1;
  let end = -1;
  const flush = (): void => {
    if (start < 0) return;
    const t = text.slice(start, end).trim();
    if (t !== "") out.push({ text: t, start, end });
    start = -1;
  };
  while ((m = re.exec(text)) !== null) {
    const seg = m[0]!;
    if (seg.trim() === "") continue;
    if (start < 0) start = m.index;
    end = m.index + seg.length;
    if (end - start >= minLen || end - start >= maxLen) flush();
  }
  flush();
  return out;
}

/** Keyword score of a passage against question terms: idf of matched terms, scaled by coverage. */
export function scorePassageKeyword(qTerms: readonly string[], passageTokens: readonly string[], idf: (t: string) => number): number {
  if (qTerms.length === 0) return 0;
  const present = new Set(passageTokens);
  let score = 0;
  let matched = 0;
  for (const t of qTerms) {
    if (present.has(t)) {
      score += Math.max(0.1, idf(t));
      matched++;
    }
  }
  const coverage = matched / qTerms.length;
  return score * (0.4 + 0.6 * coverage); // reward passages that cover more of the question
}
