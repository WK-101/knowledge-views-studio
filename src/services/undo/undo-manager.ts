export interface UndoEntry {
  readonly label: string;
  /** Reverse the operation. May touch the vault and re-render. */
  readonly undo: () => Promise<void>;
}

/**
 * A bounded, multi-step stack of reversible operations. Free of Obsidian so it
 * can be unit-tested; hosts push entries whose `undo` closures do the real work.
 */
export class UndoManager {
  private readonly stack: UndoEntry[] = [];

  constructor(private readonly max = 50) {}

  push(entry: UndoEntry): void {
    this.stack.push(entry);
    while (this.stack.length > this.max) this.stack.shift();
  }

  canUndo(): boolean {
    return this.stack.length > 0;
  }

  size(): number {
    return this.stack.length;
  }

  /** Label of the most recent entry, without removing it. */
  peekLabel(): string | null {
    return this.stack.length > 0 ? this.stack[this.stack.length - 1]!.label : null;
  }

  /** Pop and run the most recent entry; returns its label, or null if empty. */
  async undo(): Promise<string | null> {
    const entry = this.stack.pop();
    if (!entry) return null;
    await entry.undo();
    return entry.label;
  }

  clear(): void {
    this.stack.length = 0;
  }
}
