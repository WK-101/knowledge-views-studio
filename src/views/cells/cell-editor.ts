import type { App, Component } from "obsidian";
import type { ResolvedColumn } from "../view-model";

/** Context for turning a cell into an inline editor. */
export interface CellEditContext {
  readonly el: HTMLElement;
  readonly value: string;
  readonly column: ResolvedColumn;
  readonly app: App;
  readonly component: Component;
  /** Path of the note hosting this view — used to resolve links + save pasted attachments. */
  readonly sourcePath?: string;
  /** Commit a new raw value (triggers write-back). */
  readonly commit: (value: string) => void;
  /** Abandon the edit and restore the read-only cell. */
  readonly cancel: () => void;
  /** Distinct existing values for this column (for select/theme autocomplete). */
  readonly suggestions?: readonly string[];
}

/** Builds a type-appropriate inline editor. Parallels CellRenderer, keyed by type id. */
export interface CellEditor {
  readonly typeId: string;
  edit(ctx: CellEditContext): void;
}

export class CellEditorRegistry {
  private readonly editors = new Map<string, CellEditor>();
  private fallback: CellEditor | null = null;

  register(editor: CellEditor, asFallback = false): this {
    this.editors.set(editor.typeId, editor);
    if (asFallback) this.fallback = editor;
    return this;
  }

  get(typeId: string): CellEditor | null {
    return this.editors.get(typeId) ?? this.fallback;
  }
}
