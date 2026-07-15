import { FuzzySuggestModal, Menu, Modal, Notice, Setting, TFile, setIcon, type App, type MarkdownPostProcessorContext, type Plugin } from "obsidian";
import { parseAttachments, serializeAttachments, attachmentName, type Attachment, type AttachmentKind } from "../services/index";
import { syncPaperAnnotations, type AnnotationSyncOptions } from "./annotation-sync";
import { isZotFlowAvailable, openInZotFlow } from "../services/annotations/zotflow-interop";

const KIND_BADGE: Record<AttachmentKind, string> = {
  pdf: "PDF", epub: "EPUB", image: "IMG", word: "DOC", excel: "XLS", powerpoint: "PPT", web: "WEB", file: "FILE",
};

/** Register the `kvs-paper` attachment shelf. Attachments are stored as lines in the block itself. */
export function registerAttachmentPanel(plugin: Plugin, syncOptions: () => AnnotationSyncOptions): void {
  plugin.registerMarkdownCodeBlockProcessor("kvs-paper", (source, el, ctx) => {
    renderPanel(plugin.app, source, el, ctx, syncOptions);
  });
}

function renderPanel(app: App, source: string, el: HTMLElement, ctx: MarkdownPostProcessorContext, syncOptions: () => AnnotationSyncOptions): void {
  const attachments = parseAttachments(source);
  const root = el.createDiv({ cls: "kvs-attach" });

  // Drag a file from your computer straight onto the panel to attach it (copied into the vault).
  root.addEventListener("dragover", (e) => {
    if (e.dataTransfer?.types.includes("Files")) {
      e.preventDefault();
      root.addClass("kvs-attach-drag");
    }
  });
  root.addEventListener("dragleave", (e) => {
    if (!root.contains(e.relatedTarget as Node)) root.removeClass("kvs-attach-drag");
  });
  root.addEventListener("drop", (e) => {
    const files = Array.from(e.dataTransfer?.files ?? []);
    if (files.length === 0) return;
    e.preventDefault();
    root.removeClass("kvs-attach-drag");
    void (async () => {
      const added: Attachment[] = [];
      for (const f of files) {
        const p = await importFileToVault(app, f);
        if (p) added.push({ target: p, isLink: true, kind: kindOf(extOf(p)) });
      }
      if (added.length > 0) await rewriteBlock(app, ctx, el, [...parseAttachments(source), ...added]);
    })();
  });

  const head = root.createDiv({ cls: "kvs-attach-head" });
  head.createSpan({ cls: "kvs-attach-title", text: "Attachments" });
  const actions = head.createDiv({ cls: "kvs-attach-actions" });
  const sync = actions.createEl("button", { cls: "kvs-attach-sync" });
  setIcon(sync.createSpan({ cls: "kvs-attach-add-ic" }), "highlighter");
  sync.appendText("Sync annotations");
  sync.setAttr("title", "Read annotations from attached PDFs and Zotero into this note (re-run after removing a PDF to clear them)");
  sync.addEventListener("click", () => {
    const file = app.vault.getAbstractFileByPath(ctx.sourcePath);
    if (file instanceof TFile) void syncPaperAnnotations(app, file, syncOptions());
  });
  const add = actions.createEl("button", { cls: "kvs-attach-add" });
  setIcon(add.createSpan({ cls: "kvs-attach-add-ic" }), "paperclip");
  add.appendText("Add");
  add.addEventListener("click", (e) => openAddMenu(app, e, () => attachments, ctx, el));

  if (attachments.length === 0) {
    root.createDiv({ cls: "kvs-attach-empty", text: "No attachments yet — add a PDF, EPUB, Office doc, image, or a web / Zotero link." });
    return;
  }
  const grid = root.createDiv({ cls: "kvs-attach-grid" });
  attachments.forEach((att, i) => renderCard(app, grid, att, ctx, el, () => attachments, i));
}

function renderCard(
  app: App,
  grid: HTMLElement,
  att: Attachment,
  ctx: MarkdownPostProcessorContext,
  el: HTMLElement,
  all: () => Attachment[],
  index: number,
): void {
  const card = grid.createDiv({ cls: `kvs-attach-card kvs-attach-${att.kind}` });
  const open = (): void => openAttachment(app, att, ctx.sourcePath);

  const thumb = card.createDiv({ cls: "kvs-attach-thumb" });
  const imgFile = att.isLink && att.kind === "image" ? app.metadataCache.getFirstLinkpathDest(att.target, ctx.sourcePath) : null;
  if (imgFile instanceof TFile) {
    const img = thumb.createEl("img", { cls: "kvs-attach-img" });
    img.src = app.vault.getResourcePath(imgFile);
  } else {
    thumb.createSpan({ cls: "kvs-attach-badge", text: KIND_BADGE[att.kind] });
  }

  const body = card.createDiv({ cls: "kvs-attach-body" });
  const name = body.createDiv({ cls: "kvs-attach-name", text: attachmentName(att) });
  name.setAttr("title", att.target);
  body.createDiv({ cls: "kvs-attach-sub", text: att.isLink ? KIND_BADGE[att.kind] : att.target.replace(/^https?:\/\//, "").split("/")[0] ?? att.target });

  card.addEventListener("click", (e) => {
    if ((e.target as HTMLElement).closest(".kvs-attach-remove")) return;
    open();
  });

  // When ZotFlow is installed, offer its richer reader as a per-file choice on right-click. Our own
  // reader stays the default (plain click); this is an addition, never a replacement — a user who wants
  // the two plugins to work as one system gets that, and a user without ZotFlow never sees it.
  if (att.isLink && (att.kind === "pdf" || att.kind === "epub") && isZotFlowAvailable(app)) {
    card.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      const file = app.metadataCache.getFirstLinkpathDest(att.target, ctx.sourcePath);
      const menu = new Menu();
      menu.addItem((i) =>
        i
          .setTitle("Open in ZotFlow reader")
          .setIcon("book-open")
          .onClick(() => {
            if (!(file instanceof TFile)) {
              open(); // link didn't resolve to a vault file — fall back to our opener
              return;
            }
            void openInZotFlow(app, file).then((ok) => {
              if (!ok) open(); // ZotFlow declined (seam changed / load error) — fall back silently
            });
          }),
      );
      menu.addItem((i) =>
        i
          .setTitle("Open in KVS reader")
          .setIcon("file-text")
          .onClick(() => open()),
      );
      menu.showAtMouseEvent(e);
    });
  }

  const remove = card.createEl("button", { cls: "kvs-attach-remove", attr: { "aria-label": "Remove attachment" } });
  setIcon(remove, "x");
  remove.addEventListener("click", (e) => {
    e.stopPropagation();
    const next = all().filter((_a, i) => i !== index);
    void rewriteBlock(app, ctx, el, next);
  });
}

function openAttachment(app: App, att: Attachment, sourcePath: string): void {
  if (att.isLink) {
    void app.workspace.openLinkText(att.target, sourcePath, true);
  } else {
    window.open(att.target, "_blank");
  }
}

function openAddMenu(app: App, event: MouseEvent, all: () => Attachment[], ctx: MarkdownPostProcessorContext, el: HTMLElement): void {
  const menu = new Menu();
  menu.addItem((i) =>
    i
      .setTitle("Add a file from your computer…")
      .setIcon("upload")
      .onClick(() => pickComputerFiles(app, (paths) => void rewriteBlock(app, ctx, el, [...all(), ...paths.map((p) => ({ target: p, isLink: true as const, kind: kindOf(extOf(p)) }))]))),
  );
  menu.addItem((i) =>
    i
      .setTitle("Attach a vault file…")
      .setIcon("folder")
      .onClick(() => {
        new FilePickModal(app, (file) => {
          void rewriteBlock(app, ctx, el, [...all(), { target: file.path, isLink: true, kind: kindOf(file.extension) }]);
        }).open();
      }),
  );
  menu.addItem((i) =>
    i
      .setTitle("Attach a link / URL…")
      .setIcon("link")
      .onClick(() => {
        new LinkPromptModal(app, (url, label) => {
          const isLink = !/^(https?:|zotero:)/i.test(url);
          const att: Attachment = { target: url, isLink, kind: kindFromTarget(url, !isLink), ...(label ? { label } : {}) };
          void rewriteBlock(app, ctx, el, [...all(), att]);
        }).open();
      }),
  );
  menu.showAtMouseEvent(event);
}

function extOf(path: string): string {
  const dot = path.lastIndexOf(".");
  return dot >= 0 ? path.slice(dot + 1) : "";
}

/** Copy an external File into the vault's configured attachment folder; returns its vault path. */
async function importFileToVault(app: App, file: File): Promise<string | null> {
  try {
    const buf = await file.arrayBuffer();
    const path = await app.fileManager.getAvailablePathForAttachment(file.name);
    const created = await app.vault.createBinary(path, buf);
    return created.path;
  } catch (error) {
    console.error("[KVS] couldn't import attachment:", error);
    new Notice(`Couldn't add "${file.name}".`);
    return null;
  }
}

/** Open a native file picker; import each chosen file into the vault. */
function pickComputerFiles(app: App, onImported: (paths: string[]) => void): void {
  const input = createEl("input");
  input.type = "file";
  input.multiple = true;
  input.addEventListener("change", () => {
    void (async () => {
      const paths: string[] = [];
      for (const f of Array.from(input.files ?? [])) {
        const p = await importFileToVault(app, f);
        if (p) paths.push(p);
      }
      if (paths.length > 0) onImported(paths);
    })();
  });
  input.click();
}

function kindOf(ext: string): AttachmentKind {
  return kindFromTarget(`x.${ext}`, false);
}
function kindFromTarget(target: string, isUrl: boolean): AttachmentKind {
  // Reuse the pure classifier via a throwaway parse.
  return parseAttachments(isUrl ? target : `[[${target}]]`)[0]?.kind ?? (isUrl ? "web" : "file");
}

async function rewriteBlock(app: App, ctx: MarkdownPostProcessorContext, el: HTMLElement, next: readonly Attachment[]): Promise<void> {
  const info = ctx.getSectionInfo(el);
  const file = app.vault.getAbstractFileByPath(ctx.sourcePath);
  if (!info || !(file instanceof TFile)) return;
  const body = serializeAttachments(next);
  await app.vault.process(file, (data) => {
    const lines = data.split("\n");
    const block = ["```kvs-paper", ...(body ? body.split("\n") : []), "```"];
    lines.splice(info.lineStart, info.lineEnd - info.lineStart + 1, ...block);
    return lines.join("\n");
  });
}

/** Fuzzy-pick a non-markdown vault file to attach. */
class FilePickModal extends FuzzySuggestModal<TFile> {
  constructor(app: App, private readonly onPick: (file: TFile) => void) {
    super(app);
    this.setPlaceholder("Pick a file to attach (PDF, EPUB, Office, image…)");
  }
  getItems(): TFile[] {
    return this.app.vault
      .getFiles()
      .filter((f) => f.extension.toLowerCase() !== "md")
      .sort((a, b) => a.path.localeCompare(b.path));
  }
  getItemText(file: TFile): string {
    return file.path;
  }
  onChooseItem(file: TFile): void {
    this.onPick(file);
  }
}

/** Prompt for a URL / Zotero link (+ optional label). */
class LinkPromptModal extends Modal {
  private url = "";
  private label = "";
  constructor(app: App, private readonly onSubmit: (url: string, label: string) => void) {
    super(app);
  }
  override onOpen(): void {
    this.setTitle("Attach a link");
    new Setting(this.contentEl).setName("URL or zotero:// link").addText((t) => {
      t.setPlaceholder("https://…  or  zotero://open-pdf/…").onChange((v) => (this.url = v));
      t.inputEl.addClass("kvs-input-full");
    });
    new Setting(this.contentEl).setName("Label (optional)").addText((t) => t.onChange((v) => (this.label = v)));
    new Setting(this.contentEl).addButton((b) =>
      b
        .setButtonText("Attach")
        .setCta()
        .onClick(() => {
          const url = this.url.trim();
          if (url === "") return;
          this.close();
          this.onSubmit(url, this.label.trim());
        }),
    );
  }
  override onClose(): void {
    this.contentEl.empty();
  }
}
