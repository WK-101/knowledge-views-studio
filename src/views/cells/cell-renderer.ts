import type { App, Component } from "obsidian";
import type { ResolvedColumn } from "../view-model";

/** Context for rendering one cell's value into an element. */
export interface CellRenderContext {
  readonly el: HTMLElement;
  readonly value: string;
  readonly column: ResolvedColumn;
  readonly app: App;
  readonly sourcePath: string;
  readonly component: Component;
  /** Show nested tags by their last segment only. */
  readonly shortenTags?: boolean;
}

/**
 * Renders a cell for a given column type. This is the UI counterpart to the pure
 * {@link ColumnType}: types own logic, renderers own DOM. Both are keyed by the
 * same type id, so a new column type ships its logic and its rendering together.
 */
export interface CellRenderer {
  readonly typeId: string;
  render(ctx: CellRenderContext): void;
}

export class CellRendererRegistry {
  private readonly renderers = new Map<string, CellRenderer>();
  private fallback: CellRenderer | null = null;

  register(renderer: CellRenderer, asFallback = false): this {
    this.renderers.set(renderer.typeId, renderer);
    if (asFallback) this.fallback = renderer;
    return this;
  }

  get(typeId: string): CellRenderer | null {
    return this.renderers.get(typeId) ?? this.fallback;
  }

  has(typeId: string): boolean {
    return this.renderers.has(typeId);
  }
}
