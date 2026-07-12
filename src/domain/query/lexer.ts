import { QueryError } from "./ast";

export type TokenType =
  | "number"
  | "string"
  | "identifier"
  | "field"
  | "operator"
  | "lparen"
  | "rparen"
  | "comma"
  | "keyword"
  | "eof";

export interface Token {
  readonly type: TokenType;
  readonly value: string;
  readonly position: number;
}

const KEYWORDS: ReadonlySet<string> = new Set(["and", "or", "not", "true", "false", "null"]);
const TWO_CHAR: ReadonlySet<string> = new Set(["==", "!=", ">=", "<=", "&&", "||"]);
const ONE_CHAR: ReadonlySet<string> = new Set([">", "<", "+", "-", "*", "/", "%", "!", "?", ":"]);

const isDigit = (ch: string): boolean => ch >= "0" && ch <= "9";
const isIdentStart = (ch: string): boolean => /[A-Za-z_]/.test(ch);
const isIdentPart = (ch: string): boolean => /[A-Za-z0-9_]/.test(ch);

/** Turn an expression string into a flat token stream (always ends with eof). */
export function tokenize(input: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;

  while (i < input.length) {
    const ch = input.charAt(i);

    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      i++;
      continue;
    }

    if (isDigit(ch)) {
      let j = i + 1;
      while (j < input.length && (isDigit(input.charAt(j)) || input.charAt(j) === ".")) j++;
      tokens.push({ type: "number", value: input.slice(i, j), position: i });
      i = j;
      continue;
    }

    if (ch === '"' || ch === "'") {
      let j = i + 1;
      let value = "";
      while (j < input.length && input.charAt(j) !== ch) {
        if (input.charAt(j) === "\\" && j + 1 < input.length) {
          value += input.charAt(j + 1);
          j += 2;
          continue;
        }
        value += input.charAt(j);
        j++;
      }
      if (j >= input.length) throw new QueryError("Unterminated string literal", i);
      tokens.push({ type: "string", value, position: i });
      i = j + 1;
      continue;
    }

    if (ch === "[") {
      let j = i + 1;
      let value = "";
      while (j < input.length && input.charAt(j) !== "]") {
        value += input.charAt(j);
        j++;
      }
      if (j >= input.length) throw new QueryError("Unterminated field reference", i);
      tokens.push({ type: "field", value: value.trim(), position: i });
      i = j + 1;
      continue;
    }

    if (isIdentStart(ch)) {
      let j = i + 1;
      while (j < input.length && isIdentPart(input.charAt(j))) j++;
      const word = input.slice(i, j);
      if (KEYWORDS.has(word.toLowerCase())) {
        tokens.push({ type: "keyword", value: word.toLowerCase(), position: i });
      } else {
        tokens.push({ type: "identifier", value: word, position: i });
      }
      i = j;
      continue;
    }

    const two = input.slice(i, i + 2);
    if (TWO_CHAR.has(two)) {
      tokens.push({ type: "operator", value: two, position: i });
      i += 2;
      continue;
    }
    if (ch === "(") {
      tokens.push({ type: "lparen", value: "(", position: i });
      i++;
      continue;
    }
    if (ch === ")") {
      tokens.push({ type: "rparen", value: ")", position: i });
      i++;
      continue;
    }
    if (ch === ",") {
      tokens.push({ type: "comma", value: ",", position: i });
      i++;
      continue;
    }
    if (ONE_CHAR.has(ch)) {
      tokens.push({ type: "operator", value: ch, position: i });
      i++;
      continue;
    }

    throw new QueryError(`Unexpected character "${ch}"`, i);
  }

  tokens.push({ type: "eof", value: "", position: input.length });
  return tokens;
}
