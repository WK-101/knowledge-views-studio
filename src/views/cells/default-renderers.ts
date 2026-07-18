import { MarkdownRenderer, Notice, setIcon, setTooltip } from "obsidian";
import { CellRendererRegistry, type CellRenderContext, type CellRenderer } from "./cell-renderer";
import { splitTags } from "../../domain/columns/types/tags";
import { splitList } from "../../domain/columns/types/list";
import { formatAuthorsShort, splitAuthors, doiUrl, arxivUrl, pmidUrl, doiRegistrant, doiPrefix } from "../../domain/columns/types/academic";
import { linkTarget } from "../../domain/columns/types/link";
import { toBoolean } from "../../domain/columns/types/checkbox";
import { toRating, RATING_MAX } from "../../domain/columns/types/rating";
import { extractImageEmbeds } from "../../util/markdown";

const MARKDOWN_HINT = /[*_`~$[\]<>]|!\[|^\s*[-*+]\s|^\s*\d+[.)]\s|^\s*#{1,6}\s|^\s*>/m;

function renderMarkdown(ctx: CellRenderContext, markdown: string, afterRender?: () => void): void {
  // Table cells store line breaks as <br>; convert them back to newlines so Obsidian renders
  // block structure — nested/sub bullets, numbered lists, task lists, quotes, code, headings —
  // natively in the dashboard, matching what exports produce.
  const normalized = markdown.replace(/<br\s*\/?>/gi, "\n");
  const done = MarkdownRenderer.render(ctx.app, normalized, ctx.el, ctx.sourcePath, ctx.component);
  if (afterRender) void done.then(afterRender).catch(() => undefined);
  else void done;
}

/** Plain text when possible (fast); Markdown only when the value looks like it. */
function renderInline(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  if (MARKDOWN_HINT.test(value)) renderMarkdown(ctx, value);
  else ctx.el.setText(value);
}

function renderPlain(ctx: CellRenderContext): void {
  ctx.el.setText(ctx.value.trim());
}

function renderLink(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const inner = value.startsWith("[[") ? value.slice(2).replace(/\]\]$/, "") : linkTarget(value);
  const [rawTarget, alias] = inner.split("|");
  const target = (rawTarget ?? value).trim();
  const anchor = ctx.el.createEl("a", { text: (alias ?? target).trim(), cls: "internal-link kvs-internal-link" });
  anchor.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    void ctx.app.workspace.openLinkText(target, ctx.sourcePath, event.ctrlKey || event.metaKey);
  });
}

/** Relations hold one or more `[[links]]`; render each as a clickable internal link. */
function renderRelation(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const wrap = ctx.el.createDiv({ cls: "kvs-relations" });
  const matches = [...value.matchAll(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g)];
  if (matches.length === 0) {
    wrap.setText(value);
    return;
  }
  for (const m of matches) {
    const target = (m[1] ?? "").trim();
    const anchor = wrap.createEl("a", { text: (m[2] ?? target).trim(), cls: "internal-link kvs-internal-link" });
    anchor.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      void ctx.app.workspace.openLinkText(target, ctx.sourcePath, event.ctrlKey || event.metaKey);
    });
  }
}

function renderUrl(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const anchor = ctx.el.createEl("a", { text: value, href: value, cls: "kvs-url" });
  anchor.setAttr("target", "_blank");
  anchor.setAttr("rel", "noopener");
}

function renderImage(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const markdown = extractImageEmbeds(value).length > 0 ? value : `![[${value}]]`;
  ctx.el.addClass("kvs-cell-image");
  // Obsidian builds the <img> itself, so the loading hints go on afterwards. This matters most in a gallery:
  // the browser then skips fetching and decoding every image that's scrolled off screen, which is the
  // difference between a grid of images painting immediately and stalling on the whole set.
  renderMarkdown(ctx, markdown, () => {
    for (const img of Array.from(ctx.el.querySelectorAll("img"))) {
      img.setAttribute("loading", "lazy");
      img.setAttribute("decoding", "async");
    }
  });
}

function renderCheckbox(ctx: CellRenderContext): void {
  const input = ctx.el.createEl("input", { cls: "kvs-checkbox" });
  input.type = "checkbox";
  input.checked = toBoolean(ctx.value);
  input.disabled = true; // inline editing arrives in the write-back phase
}

function renderRating(ctx: CellRenderContext): void {
  if (ctx.value.trim() === "") return;
  const score = Math.min(RATING_MAX, Math.max(0, Math.round(toRating(ctx.value))));
  const span = ctx.el.createSpan({ cls: "kvs-rating" });
  span.setText("★".repeat(score) + "☆".repeat(RATING_MAX - score));
}

function searchForTag(app: CellRenderContext["app"], tag: string): void {
  const plugins = (
    app as unknown as {
      internalPlugins?: {
        getPluginById(id: string): { instance?: { openGlobalSearch(query: string): void } } | null;
      };
    }
  ).internalPlugins;
  plugins?.getPluginById("global-search")?.instance?.openGlobalSearch(`tag:#${tag}`);
}

function renderTags(ctx: CellRenderContext): void {
  const tags = splitTags(ctx.value);
  if (tags.length === 0) return;
  // A flex-wrap row of pills: chips wrap between tags but each tag stays whole (see CSS nowrap),
  // so a single multi-word tag never splits across lines. Build the links directly so every tag
  // renders — including emoji and other Unicode Obsidian's inline tag parser would drop.
  const wrap = ctx.el.createDiv({ cls: "kvs-tags" });
  for (const raw of tags) {
    const name = raw.replace(/^#+/, "").trim();
    if (name === "") continue;
    // Optionally show only the last segment of a nested tag (#a/b/c → "c"), like the basetag plugin.
    // The full path is kept for the link, search, and tooltip so graph/search behaviour is unchanged.
    const nested = ctx.shortenTags && name.includes("/");
    const label = nested ? (name.split("/").pop() ?? name) : name;
    const link = wrap.createEl("a", { cls: nested ? "tag kvs-tag-link kvs-tag-nested" : "tag kvs-tag-link", text: `#${label}` });
    link.setAttr("href", `#${name}`);
    if (nested) setTooltip(link, `#${name}`);
    link.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      searchForTag(ctx.app, name);
    });
  }
}

function renderSelect(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value !== "") ctx.el.createSpan({ cls: "kvs-pill", text: value });
}

function renderList(ctx: CellRenderContext): void {
  const items = splitList(ctx.value);
  if (items.length === 0) return;
  const wrap = ctx.el.createDiv({ cls: "kvs-list" });
  for (const item of items) wrap.createSpan({ cls: "kvs-pill kvs-list-item", text: item });
}

// ---- Academic kit renderers -------------------------------------------------

/** A small copy-to-clipboard button appended to a cell. */
/**
 * Fire `action` on a genuine single click, but cancel it if a second click (a double-click) follows within a
 * short window. Cells enter edit mode on double-click, so without this the cell's own affordances — the copy
 * button, the "open" chip — fired on each of the double-click's two clicks (hence "copy twice, then edit").
 * The click is swallowed (preventDefault/stopPropagation) but the double-click is left to bubble so the cell
 * still starts editing.
 */
function clickUnlessDblclick(el: HTMLElement, action: () => void): void {
  let timer: number | null = null;
  el.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
      return; // second click → this is a double-click; cancel the single-click action
    }
    timer = window.setTimeout(() => {
      timer = null;
      action();
    }, 220);
  });
  el.addEventListener("dblclick", () => {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
    // deliberately not stopping propagation: the cell's dblclick handler should still start editing
  });
}

function addCopyButton(ctx: CellRenderContext, text: string, tooltip: string): void {
  const btn = ctx.el.createEl("a", { cls: "clickable-icon kvs-copy-btn" });
  setIcon(btn, "copy");
  setTooltip(btn, tooltip);
  clickUnlessDblclick(btn, () => void navigator.clipboard.writeText(text).then(() => new Notice("Copied")));
}

/**
 * A reference identifier (DOI / arXiv / PubMed). A DOI is a *link*, not reading material — a cell full of
 * "10.1145/3292500.3330701" is unreadable and eats the width. So the default is a compact chip ("DOI ↗") that
 * opens the paper and copies on hover, with the full identifier one tooltip away. Two other per-view modes:
 * "full" keeps the whole string (the old behaviour), and "publisher" shows the registrant ("Nature ↗",
 * "ACM ↗") so the column reads meaningfully. The mode lives on `column.display`, defaulting to compact.
 */
function renderExternalId(ctx: CellRenderContext, url: (v: string) => string, label: string): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const mode = ctx.column.display ?? "compact";
  const href = url(value);
  const wrap = ctx.el.createDiv({ cls: "kvs-ref" });

  if (mode === "full") {
    const anchor = wrap.createEl("a", { text: value, href, cls: "kvs-ref-link" });
    anchor.setAttr("rel", "noopener");
    setTooltip(anchor, `Open ${label}`);
    clickUnlessDblclick(anchor, () => window.open(href, "_blank"));
    addCopyButton(ctx, value, `Copy ${label}`);
    return;
  }

  // Compact chip (default) or publisher label. For a DOI in publisher mode, show the registrant (falling back
  // to the bare prefix, then the generic label); arXiv/PubMed have no registrant, so they keep their label.
  let text = label;
  if (mode === "publisher" && ctx.column.typeId === "doi") {
    text = doiRegistrant(value) ?? doiPrefix(value) ?? label;
  }
  const chip = wrap.createEl("a", { href, cls: "kvs-ref-chip" });
  chip.setAttr("rel", "noopener");
  chip.createSpan({ cls: "kvs-ref-chip-label", text });
  setIcon(chip.createSpan({ cls: "kvs-ref-chip-icon" }), "external-link");
  setTooltip(chip, `${value} — open ${label}`);
  clickUnlessDblclick(chip, () => window.open(href, "_blank"));
  addCopyButton(ctx, value, `Copy ${label}`);
}

function renderCiteKey(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const key = value.replace(/^@/, "");
  const wrap = ctx.el.createDiv({ cls: "kvs-ref kvs-citekey" });
  wrap.createSpan({ cls: "kvs-citekey-text", text: `@${key}` });
  addCopyButton(ctx, `[@${key}]`, "Copy Pandoc citation [@key]");
}

function renderAuthors(ctx: CellRenderContext): void {
  const value = ctx.value.trim();
  if (value === "") return;
  const short = formatAuthorsShort(value);
  const span = ctx.el.createSpan({ cls: "kvs-authors", text: short });
  const full = splitAuthors(value).join("; ");
  if (full !== short) setTooltip(span, full);
}

/** Build a registry wired with a renderer for every built-in column type. */
export function createDefaultCellRendererRegistry(): CellRendererRegistry {
  const registry = new CellRendererRegistry();
  const text: CellRenderer = { typeId: "text", render: renderInline };
  registry.register(text, true); // fallback for unknown types
  registry.register({ typeId: "markdown", render: (ctx) => renderMarkdown(ctx, ctx.value.trim()) });
  registry.register({ typeId: "number", render: renderNumberStyled });
  registry.register({ typeId: "date", render: renderPlain });
  registry.register({ typeId: "link", render: renderLink });
  registry.register({ typeId: "relation", render: renderRelation });
  registry.register({ typeId: "url", render: renderUrl });
  registry.register({ typeId: "image", render: renderImage });
  registry.register({ typeId: "checkbox", render: renderCheckbox });
  registry.register({ typeId: "rating", render: renderRating });
  registry.register({ typeId: "tags", render: renderTags });
  registry.register({ typeId: "list", render: renderList });
  registry.register({ typeId: "citekey", render: renderCiteKey });
  registry.register({ typeId: "authors", render: renderAuthors });
  registry.register({ typeId: "doi", render: (ctx) => renderExternalId(ctx, doiUrl, "DOI") });
  registry.register({ typeId: "arxiv", render: (ctx) => renderExternalId(ctx, arxivUrl, "arXiv") });
  registry.register({ typeId: "pmid", render: (ctx) => renderExternalId(ctx, pmidUrl, "PubMed") });
  registry.register({ typeId: "select", render: renderSelect });
  return registry;
}

/**
 * A number, drawn as a bar or a ring rather than a bare figure.
 *
 * A column of "62", "18", "94" is a column of numbers. The same column as bars is a column you can *see*
 * — which of these is nearly done, which has barely started — without reading a single digit. That is the
 * whole point, so a value we cannot parse falls back to showing the raw text rather than a misleading
 * empty bar.
 */
function renderNumberStyled(ctx: CellRenderContext): void {
  const style = ctx.column.display ?? "plain";
  const raw = ctx.value.trim();
  if (style === "plain" || raw === "") {
    renderPlain(ctx);
    return;
  }
  const n = Number(raw.replace(/[,\s%]/g, ""));
  if (!Number.isFinite(n)) {
    renderPlain(ctx);
    return;
  }
  const max = ctx.column.displayMax && ctx.column.displayMax > 0 ? ctx.column.displayMax : 100;
  const pct = Math.max(0, Math.min(1, n / max));

  if (style === "ring") {
    const wrap = ctx.el.createDiv({ cls: "kvs-num-ring" });
    wrap.setCssProps({ "--kvs-pct": String(pct) });
    wrap.createDiv({ cls: "kvs-num-ring-track" });
    wrap.createSpan({ cls: "kvs-num-ring-text", text: String(n) });
    setTooltip(wrap, `${n} of ${max}`);
    return;
  }

  // bar
  const wrap = ctx.el.createDiv({ cls: "kvs-num-bar" });
  const track = wrap.createDiv({ cls: "kvs-num-bar-track" });
  const fill = track.createDiv({ cls: "kvs-num-bar-fill" });
  fill.setCssProps({ "--kvs-pct": `${Math.round(pct * 100)}%` });
  wrap.createSpan({ cls: "kvs-num-bar-text", text: String(n) });
  setTooltip(wrap, `${n} of ${max}`);
}
