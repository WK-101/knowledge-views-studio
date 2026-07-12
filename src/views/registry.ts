import type { KnowledgeView } from "./view";

/** Registry of views. `get` falls back to the first-registered view (the table). */
export class ViewRegistry {
  private readonly views = new Map<string, KnowledgeView>();
  private fallbackType: string | null = null;

  register(view: KnowledgeView, asFallback = false): this {
    this.views.set(view.type, view);
    if (asFallback || this.fallbackType === null) this.fallbackType = view.type;
    return this;
  }

  get(type: string | undefined): KnowledgeView | undefined {
    if (type) {
      const found = this.views.get(type);
      if (found) return found;
    }
    return this.fallbackType ? this.views.get(this.fallbackType) : undefined;
  }

  has(type: string): boolean {
    return this.views.has(type);
  }

  all(): KnowledgeView[] {
    return [...this.views.values()];
  }
}
