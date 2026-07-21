import { Modal, Notice, Setting, TFile, type App, type Plugin } from "obsidian";
import { annotationId, boundingRect, type AnnotationRect, type KvsAnnotation } from "../domain/index";
import { allPaperAttachments, parseThemeMap, pdfAnnotationStore, removeAnnotationCallout, renderAnnotation, replaceAnnotationCallout, type AnnotationTarget } from "../services/index";
import { type AnnotationSyncOptions } from "./annotation-sync";
import { ZOTERO_PALETTE } from "../../shared/annotations";

/**
 * The highlighter's swatches — the canonical Zotero palette, so a highlight drawn on a PDF here is the same
 * colour Zotero would give it and the same the web annotator paints. Derived from ZOTERO_PALETTE (display
 * names title-cased) so all eight, magenta and gray included, stay in lockstep with the one source of truth.
 */
export const HIGHLIGHT_COLORS: readonly { name: string; hex: string }[] = ZOTERO_PALETTE.map((c) => ({
  name: c.name.charAt(0).toUpperCase() + c.name.slice(1),
  hex: c.hex,
}));

// ---- pdf.js viewer access (undocumented internals; all optional/guarded) ----

interface PageViewportLike {
  convertToPdfPoint(x: number, y: number): [number, number];
  convertToViewportPoint(x: number, y: number): [number, number];
}
interface PageViewLike {
  div?: HTMLElement;
  viewport?: PageViewportLike;
}
interface EventBusLike {
  on(name: string, cb: (e: { pageNumber?: number }) => void): void;
  off(name: string, cb: (e: { pageNumber?: number }) => void): void;
}
interface PdfViewerLike {
  currentPageNumber: number;
  currentScaleValue?: string;
  pagesCount: number;
  container?: { scrollTop: number };
  eventBus?: EventBusLike;
  getPageView?(index: number): PageViewLike | undefined;
}

function pdfViewerFor(app: App, file: TFile): PdfViewerLike | null {
  for (const leaf of app.workspace.getLeavesOfType("pdf")) {
    const view = leaf.view as unknown as { file?: { path?: string }; viewer?: Record<string, unknown> };
    if (view.file?.path !== file.path) continue;
    const v = view.viewer;
    const child = v?.child as Record<string, unknown> | undefined;
    const inner = child?.pdfViewer as Record<string, unknown> | undefined;
    const candidates = [v?.pdfViewer, child?.pdfViewer, inner?.pdfViewer, inner] as (PdfViewerLike | undefined)[];
    const hit = candidates.find((c) => c && typeof c.currentPageNumber === "number");
    if (hit) return hit;
  }
  return null;
}

/** The PDF view's root DOM element for a file. */
function pdfContainerFor(app: App, file: TFile): HTMLElement | null {
  for (const leaf of app.workspace.getLeavesOfType("pdf")) {
    const view = leaf.view as unknown as { file?: { path?: string }; containerEl?: HTMLElement };
    if (view.file?.path === file.path) return view.containerEl ?? null;
  }
  return null;
}

/** The scrollable element that holds the PDF pages (found in the DOM — no pdf.js internals needed). */
function pdfScrollEl(app: App, file: TFile): HTMLElement | null {
  const container = pdfContainerFor(app, file);
  if (!container) return null;
  const known = container.querySelector<HTMLElement>(".pdf-viewer-container");
  if (known) return known;
  let el: HTMLElement | null = container.querySelector<HTMLElement>(".page")?.parentElement ?? null;
  while (el && el !== container.parentElement) {
    if (el.scrollHeight > el.clientHeight + 4) return el;
    el = el.parentElement;
  }
  return null;
}

/**
 * Deferred-overlay annotator. A new highlight is drawn instantly as a live overlay directly on the
 * pdf.js `.page` element (percentage-positioned, so it tracks scroll + zoom for free) and queued; the
 * PDF file is only written when the reader leaves the document — so highlighting never triggers a
 * mid-reading reload. Overlays re-draw via a Mutation/scroll observer, persist across the commit
 * reload, and clicking a pending overlay removes it (a quick undo before it's committed).
 */
interface PendingHl {
  readonly ann: KvsAnnotation;
  readonly fracs: { l: number; t: number; w: number; h: number }[];
}
interface PendingDel {
  readonly target: AnnotationTarget;
  readonly page: number;
  readonly fracs: { l: number; t: number; w: number; h: number }[];
}

export class PdfOverlayManager {
  private readonly pending = new Map<string, PendingHl[]>();
  private readonly pendingRemovals = new Map<string, PendingDel[]>();
  private readonly observers = new Map<HTMLElement, MutationObserver>();
  private readonly scrollPos = new Map<string, ScrollPos>();
  private redrawQueued = false;

  constructor(
    private readonly app: App,
    private readonly syncOptions: () => AnnotationSyncOptions,
  ) {}

  hasPending(filePath: string): boolean {
    return (this.pending.get(filePath)?.length ?? 0) > 0;
  }

  /** Create a highlight from the current selection, draw it as an overlay, and queue it (no write). */
  async addHighlightFromSelection(color: string): Promise<void> {
    const ctx = await selectionContext(this.app, false);
    if (!ctx) return;
    const { file, range, pageNum, frame, pageEl, viewport, pdfW, pdfH } = ctx;
    const rects = selectionRects(range, frame, pdfW, pdfH, viewport);
    if (rects.length === 0) {
      new Notice("Couldn't map that selection to the page.");
      return;
    }
    // Overlay fractions relative to the page element (what the reader sees) — independent of the PDF-
    // coordinate math used for the eventual write.
    const pr = pageEl.getBoundingClientRect();
    const fracs: PendingHl["fracs"] = [];
    for (const cr of Array.from(range.getClientRects())) {
      if (cr.width < 1 || cr.bottom < pr.top - 2 || cr.top > pr.bottom + 2) continue;
      fracs.push({ l: (cr.left - pr.left) / pr.width, t: (cr.top - pr.top) / pr.height, w: cr.width / pr.width, h: cr.height / pr.height });
    }
    const text = (activeWindow.getSelection()?.toString() ?? "").replace(/\s+/g, " ").trim();
    const base = { attachment: file.path, page: pageNum, kind: "highlight" as const, text, rects };
    const ann: KvsAnnotation = { id: annotationId(base), kind: "highlight", text, comment: "", page: pageNum, rects, source: "manual", attachment: file.path, color };
    const arr = this.pending.get(file.path) ?? [];
    arr.push({ ann, fracs });
    this.pending.set(file.path, arr);
    activeWindow.getSelection()?.removeAllRanges();
    this.captureActiveScroll();
    this.ensureObserver(file);
    this.redrawPage(file, pageNum);
  }

  /** Open an editor for an existing highlight (colour + comment), then queue the change. */
  async editAnnotation(notePath: string, blockId: string): Promise<void> {
    const found = await this.findAnnotation(notePath, blockId);
    if (!found) {
      new Notice("Couldn't find that annotation in an attached PDF.");
      return;
    }
    new EditHighlightModal(this.app, found.ann.color ?? "#ffd400", found.ann.comment, (color, comment) => {
      void this.queueEdit(notePath, blockId, color, comment);
    }).open();
  }

  private async findAnnotation(notePath: string, blockId: string): Promise<{ pdf: TFile; att: string; ann: KvsAnnotation } | null> {
    const note = this.app.vault.getAbstractFileByPath(notePath);
    if (!(note instanceof TFile)) return null;
    const idPrefix = blockId.replace(/^anno-/, "").slice(0, 8);
    const content = await this.app.vault.read(note);
    for (const att of allPaperAttachments(content).filter((a) => a.isLink && a.kind === "pdf")) {
      const pdf = this.app.metadataCache.getFirstLinkpathDest(att.target, note.path);
      if (!(pdf instanceof TFile)) continue;
      const bytes = await this.app.vault.readBinary(pdf);
      const ann = (await pdfAnnotationStore.read(bytes.slice(0), att.target)).find((a) => a.id.slice(0, 8) === idPrefix);
      if (ann) return { pdf, att: att.target, ann };
    }
    return null;
  }

  /** Change a highlight's colour/comment: queue removal of the old + add of the updated one (same
   *  geometry), and rewrite its callout now. Committed on leave, like every other edit. */
  async queueEdit(notePath: string, blockId: string, color: string, comment: string): Promise<void> {
    const note = this.app.vault.getAbstractFileByPath(notePath);
    if (!(note instanceof TFile)) return;
    try {
      const found = await this.findAnnotation(notePath, blockId);
      if (!found) {
        new Notice("Couldn't find that annotation in an attached PDF.");
        return;
      }
      const { pdf, ann } = found;
      const updated: KvsAnnotation = { ...ann, color, comment };
      const dels = this.pendingRemovals.get(pdf.path) ?? [];
      dels.push({ target: { page: ann.page, rect: boundingRect(ann.rects) }, page: ann.page, fracs: [] });
      this.pendingRemovals.set(pdf.path, dels);
      const adds = this.pending.get(pdf.path) ?? [];
      adds.push({ ann: updated, fracs: [] });
      this.pending.set(pdf.path, adds);
      const content = await this.app.vault.read(note);
      const callout = renderAnnotation(updated, { themeMap: parseThemeMap(this.syncOptions().themeSpec ?? ""), linkFor: (a) => `${a.attachment}#page=${a.page}` });
      await this.app.vault.modify(note, replaceAnnotationCallout(content, blockId, callout));
      new Notice("Annotation updated (applies to the PDF when you leave it).");
    } catch (error) {
      fail("update the annotation", error);
    }
  }

  private container(file: TFile): HTMLElement | null {
    return pdfContainerFor(this.app, file);
  }

  private ensureObserver(file: TFile): void {
    const container = this.container(file);
    if (!container || this.observers.has(container)) return;
    const obs = new MutationObserver(() => this.scheduleRedraw());
    obs.observe(container, { childList: true, subtree: true });
    this.observers.set(container, obs);
  }

  private scheduleRedraw(): void {
    if (this.redrawQueued) return;
    this.redrawQueued = true;
    window.setTimeout(() => {
      this.redrawQueued = false;
      for (const path of this.pending.keys()) {
        const f = this.app.vault.getAbstractFileByPath(path);
        if (f instanceof TFile) this.restoreMissing(f); // only add layers that are missing — never touch existing (no flicker)
      }
    }, 100);
  }

  private drawPageLayer(file: TFile, pageEl: HTMLElement, adds: PendingHl[], dels: PendingDel[]): void {
    pageEl.querySelector(".kvs-ov-layer")?.remove();
    if (adds.length === 0 && dels.length === 0) return;
    const layer = pageEl.createDiv({ cls: "kvs-ov-layer" });
    for (const item of adds) {
      for (const f of item.fracs) {
        const hl = layer.createDiv({ cls: "kvs-ov-hl" });
        this.place(hl, f);
        hl.style.background = item.ann.color ?? "#ffd400";
        hl.setAttr("aria-label", "Pending highlight — click to remove");
        hl.addEventListener("click", (e) => {
          e.stopPropagation();
          this.removePending(file, item.ann.id, item.ann.page);
        });
      }
    }
    for (const del of dels) {
      for (const f of del.fracs) {
        const mark = layer.createDiv({ cls: "kvs-ov-del" });
        this.place(mark, f);
        mark.setAttr("aria-label", "Marked for removal — applied when you leave the PDF");
      }
    }
  }

  private place(el: HTMLElement, f: { l: number; t: number; w: number; h: number }): void {
    el.style.left = `${f.l * 100}%`;
    el.style.top = `${f.t * 100}%`;
    el.style.width = `${f.w * 100}%`;
    el.style.height = `${f.h * 100}%`;
  }

  private pagesWithOverlays(file: TFile): Set<number> {
    const pages = new Set<number>();
    for (const i of this.pending.get(file.path) ?? []) pages.add(i.ann.page);
    for (const d of this.pendingRemovals.get(file.path) ?? []) pages.add(d.page);
    return pages;
  }

  /** Force-redraw one page's overlay (used when its pending set changes). */
  private redrawPage(file: TFile, pageNum: number): void {
    const pageEl = this.container(file)?.querySelector<HTMLElement>(`.page[data-page-number="${pageNum}"]`);
    if (!pageEl) return;
    const adds = (this.pending.get(file.path) ?? []).filter((i) => i.ann.page === pageNum);
    const dels = (this.pendingRemovals.get(file.path) ?? []).filter((d) => d.page === pageNum);
    this.drawPageLayer(file, pageEl, adds, dels);
  }

  /** Add overlays only where a page has none yet — leaves existing ones untouched (no flicker). */
  private restoreMissing(file: TFile): void {
    const container = this.container(file);
    if (!container) return;
    for (const pageNum of this.pagesWithOverlays(file)) {
      const pageEl = container.querySelector<HTMLElement>(`.page[data-page-number="${pageNum}"]`);
      if (!pageEl || pageEl.querySelector(".kvs-ov-layer")) continue;
      const adds = (this.pending.get(file.path) ?? []).filter((i) => i.ann.page === pageNum);
      const dels = (this.pendingRemovals.get(file.path) ?? []).filter((d) => d.page === pageNum);
      this.drawPageLayer(file, pageEl, adds, dels);
    }
  }

  private clearOverlays(file: TFile): void {
    this.container(file)
      ?.querySelectorAll(".kvs-ov-layer")
      .forEach((l) => l.remove());
  }

  private removePending(file: TFile, id: string, pageNum: number): void {
    const arr = (this.pending.get(file.path) ?? []).filter((i) => i.ann.id !== id);
    if (arr.length > 0) this.pending.set(file.path, arr);
    else this.pending.delete(file.path);
    this.redrawPage(file, pageNum);
  }

  /** Erase committed highlights overlapping the selection — deferred: they're marked now and removed
   *  from the PDF on leave, so there's no on-the-spot reload. */
  async queueEraseAtSelection(): Promise<void> {
    const ctx = await selectionContext(this.app, true);
    if (!ctx || !ctx.bytes) return;
    const { file, range, pageNum, frame, pdfW, pdfH, viewport } = ctx;
    const sel = selectionRects(range, frame, pdfW, pdfH, viewport);
    try {
      const matched = (await pdfAnnotationStore.read(ctx.bytes.slice(0), file.path)).filter((a) => a.page === pageNum && a.rects.some((ar) => sel.some((sr) => overlaps(ar, sr))));
      if (matched.length === 0) {
        new Notice("No highlight found under that selection.");
        return;
      }
      const dels = this.pendingRemovals.get(file.path) ?? [];
      for (const a of matched) {
        const fracs = a.rects.map((r) => ({ l: r.x0 / pdfW, t: 1 - r.y1 / pdfH, w: (r.x1 - r.x0) / pdfW, h: (r.y1 - r.y0) / pdfH }));
        dels.push({ target: { page: a.page, rect: boundingRect(a.rects) }, page: a.page, fracs });
      }
      this.pendingRemovals.set(file.path, dels);
      activeWindow.getSelection()?.removeAllRanges();
      this.captureActiveScroll();
      this.ensureObserver(file);
      this.redrawPage(file, pageNum);
      new Notice(`${matched.length} highlight(s) marked for removal (applied when you leave the PDF).`);
    } catch (error) {
      fail("erase the highlight", error);
    }
  }

  /** Queue a deletion (by callout block id) for the PDF that holds it, and drop the callout now.
   *  The PDF write is deferred to the flush, so deleting doesn't reload the PDF on the spot. */
  async queueDelete(notePath: string, blockId: string): Promise<void> {
    const note = this.app.vault.getAbstractFileByPath(notePath);
    if (!(note instanceof TFile)) return;
    const idPrefix = blockId.replace(/^anno-/, "").slice(0, 8);
    const content = await this.app.vault.read(note);
    const pdfs = allPaperAttachments(content).filter((a) => a.isLink && a.kind === "pdf");
    try {
      for (const att of pdfs) {
        const pdf = this.app.metadataCache.getFirstLinkpathDest(att.target, note.path);
        if (!(pdf instanceof TFile)) continue;
        const bytes = await this.app.vault.readBinary(pdf);
        const match = (await pdfAnnotationStore.read(bytes.slice(0), att.target)).find((a) => a.id.slice(0, 8) === idPrefix);
        if (!match) continue;
        const arr = this.pendingRemovals.get(pdf.path) ?? [];
        arr.push({ target: { page: match.page, rect: boundingRect(match.rects) }, page: match.page, fracs: [] });
        this.pendingRemovals.set(pdf.path, arr);
        await this.app.vault.modify(note, removeAnnotationCallout(content, blockId)); // instant note feedback
        new Notice("Annotation removed (applies to the PDF when you leave it).");
        return;
      }
      new Notice("Couldn't find that annotation in an attached PDF.");
    } catch (error) {
      fail("delete", error);
    }
  }

  /** Record the reader's position for the active PDF while it's visible (called on scroll + on edits). */
  captureActiveScroll(): void {
    const f = this.app.workspace.getActiveFile();
    if (!f || f.extension.toLowerCase() !== "pdf") return;
    const el = pdfScrollEl(this.app, f);
    if (!el || el.offsetParent === null) return; // must be visible
    const pos = capturePos(el);
    if (pos) this.scrollPos.set(f.path, pos);
  }

  /** Restore the remembered position — but only once the view is actually visible and laid out, so a
   *  reload deferred until the reader returns still lands in the right place. */
  private restoreScrollFor(file: TFile, attempt = 0): void {
    const pos = this.scrollPos.get(file.path);
    if (!pos) return;
    const el = pdfScrollEl(this.app, file);
    if (el && el.offsetParent !== null && el.scrollHeight > el.clientHeight && el.querySelector(".page")) {
      restorePos(el, pos);
      if (attempt < 4) window.setTimeout(() => this.restoreScrollFor(file, attempt + 1), 130); // nudge as pages settle
      return;
    }
    if (attempt < 120) window.setTimeout(() => this.restoreScrollFor(file, attempt + 1), 100);
  }

  /** On tab/window change: commit pending edits for files you've left, and restore scroll for the PDF
   *  you're arriving at (so a deferred reload doesn't strand you at the top). */
  onLeafChange(): void {
    const activePath = this.app.workspace.getActiveFile()?.path;
    for (const path of new Set([...this.pending.keys(), ...this.pendingRemovals.keys()])) {
      if (path !== activePath) void this.flush(path);
    }
    const af = this.app.workspace.getActiveFile();
    if (af && af.extension.toLowerCase() === "pdf") this.restoreScrollFor(af);
  }

  /** Commit queued adds + removals to the PDF (incremental). Overlays persist briefly to mask the
   *  reload, then clear once pdf.js has rendered the embedded version. */
  async flush(filePath: string): Promise<void> {
    const adds = this.pending.get(filePath) ?? [];
    const removals = this.pendingRemovals.get(filePath) ?? [];
    if (adds.length === 0 && removals.length === 0) return;
    const file = this.app.vault.getAbstractFileByPath(filePath);
    if (!(file instanceof TFile)) return;
    try {
      let bytes = await this.app.vault.readBinary(file);
      if (removals.length > 0) bytes = await pdfAnnotationStore.removeAnnotations(bytes, removals.map((d) => d.target));
      const out = adds.length > 0 ? await pdfAnnotationStore.addAnnotations(bytes, adds.map((i) => i.ann)) : bytes;
      await this.app.vault.modifyBinary(file, out);
      this.restoreScrollFor(file);
      void this.syncOptions;
      window.setTimeout(() => {
        if (this.pending.get(filePath) === adds) this.pending.delete(filePath);
        if (this.pendingRemovals.get(filePath) === removals) this.pendingRemovals.delete(filePath);
        this.clearOverlays(file);
        const c = this.container(file);
        if (c && this.observers.has(c) && this.pending.size === 0) {
          this.observers.get(c)!.disconnect();
          this.observers.delete(c);
        }
      }, 2500);
    } catch (error) {
      fail("save highlights", error);
    }
  }

  async flushAll(): Promise<void> {
    for (const path of new Set([...this.pending.keys(), ...this.pendingRemovals.keys()])) await this.flush(path);
  }
}

/** Wire the floating toolbar (swatches + eraser) to the overlay manager. */
export function registerPdfAnnotatorToolbar(plugin: Plugin, manager: PdfOverlayManager): void {
  let bar: HTMLElement | null = null;
  const hide = (): void => {
    bar?.remove();
    bar = null;
  };
  /**
   * Pressing a swatch must not disturb the selection it is about to highlight — hence `preventDefault`
   * on the *press*, not a click handler.
   *
   * On touch that press is `touchstart`. The compatibility `mousedown` a browser synthesizes for a tap
   * arrives *after* `touchend`, by which point the selection it was supposed to protect is already gone.
   * Preventing the default on `touchstart` also suppresses those synthesized mouse events, so this binds
   * both without the action ever firing twice.
   */
  const onPress = (el: HTMLElement, action: () => void): void => {
    const handler = (e: Event): void => {
      e.preventDefault();
      e.stopPropagation();
      action();
      hide();
    };
    el.addEventListener("mousedown", handler);
    el.addEventListener("touchstart", handler);
  };

  const show = (rect: DOMRect): void => {
    hide();
    bar = document.body.createDiv({ cls: "kvs-hl-bar" });
    for (const c of HIGHLIGHT_COLORS) {
      const sw = bar.createDiv({ cls: "kvs-hl-swatch" });
      sw.setCssProps({ "--kvs-swatch": c.hex });
      sw.setAttr("aria-label", `Highlight ${c.name}`);
      onPress(sw, () => void manager.addHighlightFromSelection(c.hex));
    }
    const eraser = bar.createDiv({ cls: "kvs-hl-erase" });
    eraser.setText("⌫");
    eraser.setAttr("aria-label", "Remove highlight under selection");
    onPress(eraser, () => void manager.queueEraseAtSelection());
    const top = rect.top - 42 < 8 ? rect.bottom + 8 : rect.top - 42;
    bar.style.left = `${Math.max(8, Math.min(window.innerWidth - 240, rect.left))}px`;
    bar.style.top = `${top}px`;
  };

  // `mouseup` alone meant the bar never appeared on a phone: a touch selection is made by long-press and
  // then adjusted with drag handles, and each of those ends in `touchend`, not `mouseup`. Binding both
  // means the bar also follows the selection as the handles are dragged, which is the behaviour you want
  // anyway. `show()` begins with `hide()`, so a device that fires both events simply redraws.
  const syncBar = (e: Event): void => {
    if (bar && bar.contains(e.target as Node)) return;
    window.setTimeout(() => {
      const sel = activeWindow.getSelection();
      if (!sel || sel.rangeCount === 0 || sel.toString().trim() === "" || !anchorPage(sel)) {
        hide();
        return;
      }
      show(sel.getRangeAt(0).getBoundingClientRect());
    }, 0);
  };
  plugin.registerDomEvent(document, "mouseup", syncBar);
  plugin.registerDomEvent(document, "touchend", syncBar);
  plugin.registerDomEvent(document, "keydown", (e) => e.key === "Escape" && hide());
  plugin.registerDomEvent(document, "scroll", hide, true);
}

// ---- eraser + delete (immediate writes; these act on committed PDF annotations) ----



// ---- shared selection → coordinates ----

interface SelCtx {
  file: TFile;
  range: Range;
  pageNum: number;
  frame: DOMRect;
  pageEl: HTMLElement;
  bytes: ArrayBuffer | null;
  pdfW: number;
  pdfH: number;
  viewport: PageViewportLike | null;
}

function anchorPage(sel: Selection): HTMLElement | null {
  const node = sel.getRangeAt(0).startContainer;
  const el = (node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as HTMLElement)) ?? null;
  return el?.closest<HTMLElement>("[data-page-number], .page") ?? null;
}

/** Frame the selection is measured against: the text layer (exactly over the render), not the .page. */
function pageFrame(pageEl: HTMLElement): DOMRect {
  const layer = pageEl.querySelector<HTMLElement>(".textLayer") ?? pageEl.querySelector<HTMLElement>(".canvasWrapper") ?? pageEl;
  return layer.getBoundingClientRect();
}

function pageViewport(app: App, file: TFile, pageNum: number): PageViewportLike | null {
  try {
    return pdfViewerFor(app, file)?.getPageView?.(pageNum - 1)?.viewport ?? null;
  } catch {
    return null;
  }
}

async function selectionContext(app: App, needBytes: boolean): Promise<SelCtx | null> {
  const file = app.workspace.getActiveFile();
  if (!file || file.extension.toLowerCase() !== "pdf") {
    new Notice("Open a PDF (as its own tab) to annotate it.");
    return null;
  }
  const sel = activeWindow.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.toString().trim() === "") {
    new Notice("Select some text in the PDF first.");
    return null;
  }
  const pageEl = anchorPage(sel);
  if (!pageEl) {
    new Notice("Couldn't find the PDF page for that selection.");
    return null;
  }
  const pageNum = Number(pageEl.getAttribute("data-page-number") ?? pageEl.dataset.pageNumber ?? "0");
  const viewport = pageViewport(app, file, pageNum);
  let bytes: ArrayBuffer | null = null;
  let pdfW = 0;
  let pdfH = 0;
  if (needBytes || !viewport) {
    bytes = await app.vault.readBinary(file);
    ({ width: pdfW, height: pdfH } = await pageSize(bytes.slice(0), pageNum));
    if (pageNum < 1 || (!viewport && pdfW === 0)) {
      new Notice("Couldn't read the PDF page.");
      return null;
    }
  }
  return { file, range: sel.getRangeAt(0), pageNum, frame: pageFrame(pageEl), pageEl, bytes, pdfW, pdfH, viewport };
}

/** Map a selection's client rects to PDF coordinates. Exact via pdf.js viewport, else fraction fallback. */
function selectionRects(range: Range, frame: DOMRect, pdfW: number, pdfH: number, viewport: PageViewportLike | null): AnnotationRect[] {
  const rects: AnnotationRect[] = [];
  for (const cr of Array.from(range.getClientRects())) {
    if (cr.bottom < frame.top - 2 || cr.top > frame.bottom + 2 || cr.width < 1) continue;
    if (viewport) {
      const p1 = viewport.convertToPdfPoint(cr.left - frame.left, cr.top - frame.top);
      const p2 = viewport.convertToPdfPoint(cr.right - frame.left, cr.bottom - frame.top);
      rects.push({ x0: Math.min(p1[0], p2[0]), y0: Math.min(p1[1], p2[1]), x1: Math.max(p1[0], p2[0]), y1: Math.max(p1[1], p2[1]) });
    } else {
      const fracL = (cr.left - frame.left) / frame.width;
      const fracR = (cr.right - frame.left) / frame.width;
      const fracT = (cr.top - frame.top) / frame.height;
      const fracB = (cr.bottom - frame.top) / frame.height;
      rects.push({ x0: fracL * pdfW, x1: fracR * pdfW, y0: (1 - fracB) * pdfH, y1: (1 - fracT) * pdfH });
    }
  }
  return rects;
}

function overlaps(a: AnnotationRect, b: AnnotationRect): boolean {
  return a.x0 <= b.x1 && a.x1 >= b.x0 && a.y0 <= b.y1 && a.y1 >= b.y0;
}

function fail(what: string, error: unknown): void {
  console.error(`[KVS] couldn't ${what}:`, error);
  new Notice(`Couldn't ${what}: ${error instanceof Error ? error.message : "unexpected error"}`);
}

async function pageSize(bytes: ArrayBuffer, pageNum: number): Promise<{ width: number; height: number }> {
  const { PDFDocument } = await import("pdf-lib");
  const doc = await PDFDocument.load(bytes);
  const page = doc.getPages()[pageNum - 1];
  return page ? page.getSize() : { width: 0, height: 0 };
}

// ---- scroll preservation across the (deferred) reload ----

interface ScrollPos {
  page: number;
  offset: number;
}

/** Capture the reader's position as the top-visible page + offset into it (robust to the file growing;
 *  uses offsetTop so capture and restore share the same basis and cancel any wrapper offset). */
function capturePos(scrollEl: HTMLElement): ScrollPos | null {
  const pages = scrollEl.querySelectorAll<HTMLElement>(".page[data-page-number]");
  const scrollTop = scrollEl.scrollTop;
  let last: { page: number; top: number } | null = null;
  for (const page of Array.from(pages)) {
    if (page.offsetTop > scrollTop + 1) break; // pages are in order; stop past the viewport top
    last = { page: Number(page.getAttribute("data-page-number")), top: page.offsetTop };
  }
  if (!last && pages.length > 0) {
    const first = pages[0]!;
    last = { page: Number(first.getAttribute("data-page-number")), top: first.offsetTop };
  }
  return last ? { page: last.page, offset: scrollTop - last.top } : null;
}

function restorePos(scrollEl: HTMLElement, pos: ScrollPos): boolean {
  const page = scrollEl.querySelector<HTMLElement>(`.page[data-page-number="${pos.page}"]`);
  if (!page) return false;
  scrollEl.scrollTop = page.offsetTop + pos.offset;
  return true;
}

/** Modal to edit a highlight's colour and comment. */
class EditHighlightModal extends Modal {
  private color: string;
  private comment: string;
  constructor(
    app: App,
    color: string,
    comment: string,
    private readonly onSubmit: (color: string, comment: string) => void,
  ) {
    super(app);
    this.color = color;
    this.comment = comment;
  }
  override onOpen(): void {
    this.setTitle("Edit highlight");
    const swatches = new Setting(this.contentEl).setName("Colour");
    const row = swatches.controlEl.createDiv({ cls: "kvs-hl-bar" });
    row.addClass("kvs-hl-bar-inline");
    for (const c of HIGHLIGHT_COLORS) {
      const sw = row.createDiv({ cls: "kvs-hl-swatch" });
      sw.setCssProps({ "--kvs-swatch": c.hex });
      const mark = (): void => {
        row.querySelectorAll(".kvs-hl-swatch").forEach((e) => e.removeClass("is-selected"));
        sw.addClass("is-selected");
        this.color = c.hex;
      };
      if (c.hex.toLowerCase() === this.color.toLowerCase()) mark();
      sw.addEventListener("click", mark);
    }
    new Setting(this.contentEl).setName("Comment").addTextArea((t) => {
      t.setValue(this.comment).onChange((v) => (this.comment = v));
      t.inputEl.rows = 3;
      t.inputEl.addClass("kvs-input-full");
    });
    new Setting(this.contentEl).addButton((b) =>
      b
        .setButtonText("Save")
        .setCta()
        .onClick(() => {
          this.close();
          this.onSubmit(this.color, this.comment);
        }),
    );
  }
  override onClose(): void {
    this.contentEl.empty();
  }
}
