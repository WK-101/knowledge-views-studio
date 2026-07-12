/**
 * Expression AST shared by advanced filters and computed columns. A single,
 * well-defined node set with one evaluator — unlike the legacy code, whose
 * advanced-query evaluator was invoked with reversed arguments (bug 3.2).
 */

export class QueryError extends Error {
  readonly position?: number;

  constructor(message: string, position?: number) {
    super(message);
    this.name = "QueryError";
    this.position = position;
  }
}

export type BinaryOp = "==" | "!=" | ">" | ">=" | "<" | "<=" | "+" | "-" | "*" | "/" | "%";
export type LogicalOp = "and" | "or";
export type UnaryOp = "not" | "neg";

export type Expr =
  | { readonly kind: "literal"; readonly value: string | number | boolean | null }
  | { readonly kind: "field"; readonly name: string }
  | { readonly kind: "unary"; readonly op: UnaryOp; readonly operand: Expr }
  | { readonly kind: "binary"; readonly op: BinaryOp; readonly left: Expr; readonly right: Expr }
  | { readonly kind: "logical"; readonly op: LogicalOp; readonly left: Expr; readonly right: Expr }
  | {
      readonly kind: "conditional";
      readonly test: Expr;
      readonly consequent: Expr;
      readonly alternate: Expr;
    }
  | { readonly kind: "call"; readonly name: string; readonly args: readonly Expr[] };
