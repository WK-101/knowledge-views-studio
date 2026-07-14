import iframeHtml from "../services/search/neural-iframe.html";

/**
 * The optional neural embedding engine.
 *
 * The built-in semantic engine learns from your vault alone and downloads nothing — but it can only
 * know that two words are related if your own notes happen to use them in similar contexts. It has
 * never heard of "car" and "automobile" being the same thing unless you taught it.
 *
 * This engine uses a real sentence-transformer (all-MiniLM-L6-v2), which learned that from a very large
 * corpus before it ever met your vault. It is meaningfully better at meaning. The price is honest and
 * stated plainly to the user before they turn it on:
 *
 *   - the model (~25 MB) and the runtime are fetched once, from Hugging Face and jsDelivr;
 *   - after that, everything runs on-device and no note text ever leaves the machine.
 *
 * The model runs inside a sandboxed iframe: it takes a string and returns a vector, and has no access
 * to the vault, the plugin, or anything else.
 */
export class NeuralEmbedder {
  private frame: HTMLIFrameElement | null = null;
  private seq = 0;
  private readonly pending = new Map<number, { resolve: (v: number[]) => void; reject: (e: Error) => void }>();
  private onMessage?: (e: MessageEvent) => void;
  private loaded = false;

  get isLoaded(): boolean {
    return this.loaded;
  }

  /** Create the sandbox and wait for the model to be ready. Downloads on first call. */
  async load(): Promise<void> {
    if (this.loaded) return;

    const frame = document.body.createEl("iframe", { cls: "kvs-neural-frame" });
    frame.setAttr("sandbox", "allow-scripts");
    frame.setAttr("srcdoc", iframeHtml);
    this.frame = frame;

    this.onMessage = (event: MessageEvent): void => {
      const data = event.data as { id?: number; ok?: boolean; vector?: number[]; error?: string };
      if (typeof data?.id !== "number") return;
      const waiter = this.pending.get(data.id);
      if (!waiter) return;
      this.pending.delete(data.id);
      if (data.ok && Array.isArray(data.vector)) waiter.resolve(data.vector);
      else if (data.ok) waiter.resolve([]);
      else waiter.reject(new Error(data.error ?? "The embedding model failed."));
    };
    window.addEventListener("message", this.onMessage);

    await new Promise<void>((resolve) => window.setTimeout(resolve, 300)); // let the frame boot
    await this.send("load", "");
    this.loaded = true;
  }

  /** Embed one string. Returns a normalised vector. */
  async embed(text: string): Promise<Float32Array> {
    if (!this.loaded) await this.load();
    const v = await this.send("embed", text.slice(0, 8000));
    return Float32Array.from(v);
  }

  private send(type: "load" | "embed", text: string): Promise<number[]> {
    const frame = this.frame;
    const win = frame?.contentWindow;
    if (!win) return Promise.reject(new Error("The embedding sandbox is not available."));
    const id = ++this.seq;
    return new Promise<number[]>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      window.setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(type === "load" ? "Timed out downloading the model. Check your connection." : "Timed out embedding text."));
        }
      }, type === "load" ? 180_000 : 30_000);
      win.postMessage({ id, type, text }, "*");
    });
  }

  unload(): void {
    if (this.onMessage) window.removeEventListener("message", this.onMessage);
    for (const [, waiter] of this.pending) waiter.reject(new Error("Unloaded."));
    this.pending.clear();
    this.frame?.remove();
    this.frame = null;
    this.loaded = false;
  }
}
