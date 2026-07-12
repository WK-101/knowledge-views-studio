import { QueryError, type BinaryOp, type Expr } from "./ast";
import { tokenize, type Token, type TokenType } from "./lexer";

const EOF_TOKEN: Token = { type: "eof", value: "", position: -1 };

/**
 * Recursive-descent parser with standard precedence:
 * ternary < or < and < equality < comparison < additive < multiplicative < unary < primary.
 * A bare identifier is a field reference; an identifier followed by `(` is a call.
 */
class Parser {
  private pos = 0;

  constructor(private readonly tokens: readonly Token[]) {}

  private peek(): Token {
    return this.tokens[this.pos] ?? EOF_TOKEN;
  }

  private advance(): Token {
    const token = this.peek();
    this.pos++;
    return token;
  }

  private check(type: TokenType, value?: string): boolean {
    const token = this.peek();
    return token.type === type && (value === undefined || token.value === value);
  }

  private match(type: TokenType, value?: string): boolean {
    if (this.check(type, value)) {
      this.advance();
      return true;
    }
    return false;
  }

  private expect(type: TokenType, value?: string): Token {
    if (this.check(type, value)) return this.advance();
    const token = this.peek();
    throw new QueryError(
      `Expected ${value ?? type} but found "${token.value || token.type}"`,
      token.position,
    );
  }

  parse(): Expr {
    if (this.peek().type === "eof") throw new QueryError("Empty expression", 0);
    const expr = this.ternary();
    if (this.peek().type !== "eof") {
      const token = this.peek();
      throw new QueryError(`Unexpected token "${token.value || token.type}"`, token.position);
    }
    return expr;
  }

  private ternary(): Expr {
    const test = this.or();
    if (this.match("operator", "?")) {
      const consequent = this.ternary();
      this.expect("operator", ":");
      const alternate = this.ternary();
      return { kind: "conditional", test, consequent, alternate };
    }
    return test;
  }

  private or(): Expr {
    let left = this.and();
    while (this.check("keyword", "or") || this.check("operator", "||")) {
      this.advance();
      left = { kind: "logical", op: "or", left, right: this.and() };
    }
    return left;
  }

  private and(): Expr {
    let left = this.equality();
    while (this.check("keyword", "and") || this.check("operator", "&&")) {
      this.advance();
      left = { kind: "logical", op: "and", left, right: this.equality() };
    }
    return left;
  }

  private equality(): Expr {
    let left = this.comparison();
    while (this.check("operator", "==") || this.check("operator", "!=")) {
      const op = this.advance().value as BinaryOp;
      left = { kind: "binary", op, left, right: this.comparison() };
    }
    return left;
  }

  private comparison(): Expr {
    let left = this.additive();
    while (
      this.check("operator", ">") ||
      this.check("operator", ">=") ||
      this.check("operator", "<") ||
      this.check("operator", "<=")
    ) {
      const op = this.advance().value as BinaryOp;
      left = { kind: "binary", op, left, right: this.additive() };
    }
    return left;
  }

  private additive(): Expr {
    let left = this.multiplicative();
    while (this.check("operator", "+") || this.check("operator", "-")) {
      const op = this.advance().value as BinaryOp;
      left = { kind: "binary", op, left, right: this.multiplicative() };
    }
    return left;
  }

  private multiplicative(): Expr {
    let left = this.unary();
    while (
      this.check("operator", "*") ||
      this.check("operator", "/") ||
      this.check("operator", "%")
    ) {
      const op = this.advance().value as BinaryOp;
      left = { kind: "binary", op, left, right: this.unary() };
    }
    return left;
  }

  private unary(): Expr {
    if (this.check("keyword", "not") || this.check("operator", "!")) {
      this.advance();
      return { kind: "unary", op: "not", operand: this.unary() };
    }
    if (this.check("operator", "-")) {
      this.advance();
      return { kind: "unary", op: "neg", operand: this.unary() };
    }
    return this.primary();
  }

  private primary(): Expr {
    const token = this.peek();
    switch (token.type) {
      case "number":
        this.advance();
        return { kind: "literal", value: Number(token.value) };
      case "string":
        this.advance();
        return { kind: "literal", value: token.value };
      case "field":
        this.advance();
        return { kind: "field", name: token.value };
      case "keyword":
        if (token.value === "true") {
          this.advance();
          return { kind: "literal", value: true };
        }
        if (token.value === "false") {
          this.advance();
          return { kind: "literal", value: false };
        }
        if (token.value === "null") {
          this.advance();
          return { kind: "literal", value: null };
        }
        throw new QueryError(`Unexpected keyword "${token.value}"`, token.position);
      case "identifier":
        this.advance();
        if (this.check("lparen")) return this.finishCall(token.value);
        return { kind: "field", name: token.value };
      case "lparen": {
        this.advance();
        const expr = this.ternary();
        this.expect("rparen");
        return expr;
      }
      default:
        throw new QueryError(`Unexpected token "${token.value || token.type}"`, token.position);
    }
  }

  private finishCall(name: string): Expr {
    this.expect("lparen");
    const args: Expr[] = [];
    if (!this.check("rparen")) {
      do {
        args.push(this.ternary());
      } while (this.match("comma"));
    }
    this.expect("rparen");
    return { kind: "call", name, args };
  }
}

export function parseExpression(input: string): Expr {
  return new Parser(tokenize(input)).parse();
}
