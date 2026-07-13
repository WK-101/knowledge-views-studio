import { Notice, setIcon, setTooltip, type MarkdownPostProcessorContext, type Plugin } from "obsidian";
import { calloutSourceLabel } from "../services/index";

/**
 * Decorate ingested annotation callouts (rendered from the Annotations section) with a small hover
 * toolbar: copy the highlight text, or copy a transclusion link to the block.
 */
export function registerAnnotationDecorator(plugin: Plugin, onDelete: (sourcePath: string, blockId: string) => void, onEdit: (sourcePath: string, blockId: string) => void): void {
  plugin.registerMarkdownPostProcessor((el, ctx) => {
    el.querySelectorAll<HTMLElement>(".callout").forEach((callout) => decorate(callout, ctx, onDelete, onEdit));
  });
}

function decorate(callout: HTMLElement, ctx: MarkdownPostProcessorContext, onDelete: (sourcePath: string, blockId: string) => void, onEdit: (sourcePath: string, blockId: string) => void): void {
  const titleEl = callout.querySelector<HTMLElement>(".callout-title-inner");
  const title = titleEl?.textContent ?? "";
  // Only our annotation callouts start with "p.<n> · …".
  if (!/^p\.\d+\s*·/.test(title.trim())) return;
  if (callout.querySelector(".kvs-anno-tools")) return;

  const text = firstParagraphText(callout);
  const blockId = readBlockId(callout, ctx);
  const note = baseName(ctx.sourcePath);
  const source = calloutSourceLabel(title); // "PDF" | "Zotero" | "Note"

  callout.addClass("kvs-has-anno"); // cheaper than :has(), which Obsidian discourages
  const tools = callout.createDiv({ cls: "kvs-anno-tools" });
  addBtn(tools, "copy", "Copy highlight text", async () => {
    if (text === "") return;
    await navigator.clipboard.writeText(text);
    new Notice("Highlight copied.");
  });
  if (blockId !== "") {
    addBtn(tools, "link", "Copy embed link (transclusion)", async () => {
      await navigator.clipboard.writeText(`![[${note}#^${blockId}]]`);
      new Notice("Embed link copied.");
    });
    addBtn(tools, "quote", "Copy as quote with reference", async () => {
      const quoted = text
        .split("\n")
        .map((l) => `> ${l}`)
        .join("\n");
      await navigator.clipboard.writeText(`${quoted}\n— [[${note}#^${blockId}]]`);
      new Notice("Quote copied.");
    });
    // Edit + delete only when the annotation lives in a PDF we can edit (not a Zotero-DB annotation).
    if (source === "PDF") {
      addBtn(tools, "pencil", "Edit colour / comment", () => onEdit(ctx.sourcePath, blockId));
      addBtn(tools, "trash-2", "Delete this annotation from the PDF", () => onDelete(ctx.sourcePath, blockId));
    }
  }
}

function addBtn(parent: HTMLElement, icon: string, tip: string, onClick: () => void | Promise<void>): void {
  const b = parent.createEl("button", { cls: "kvs-anno-tool" });
  setIcon(b, icon);
  setTooltip(b, tip);
  b.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    void onClick();
  });
}

function firstParagraphText(callout: HTMLElement): string {
  const content = callout.querySelector(".callout-content");
  const p = content?.querySelector("p");
  return (p?.textContent ?? "").trim();
}

function readBlockId(callout: HTMLElement, ctx: MarkdownPostProcessorContext): string {
  const info = ctx.getSectionInfo(callout);
  if (!info) return "";
  const lines = info.text.split("\n").slice(info.lineStart, info.lineEnd + 1);
  const m = /\^(anno-[\w-]+)/.exec(lines.join("\n"));
  return m?.[1] ?? "";
}

function baseName(path: string): string {
  const file = path.split("/").pop() ?? path;
  return file.replace(/\.md$/, "");
}
