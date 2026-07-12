/** Minimal typed pub/sub. Listeners may unsubscribe safely during emit. */
export type Listener<T> = (value: T) => void;
export type Unsubscribe = () => void;

export class Emitter<T> {
  private readonly listeners = new Set<Listener<T>>();

  on(listener: Listener<T>): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit(value: T): void {
    for (const listener of [...this.listeners]) listener(value);
  }

  clear(): void {
    this.listeners.clear();
  }

  get size(): number {
    return this.listeners.size;
  }
}
