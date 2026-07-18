import { setIcon, setTooltip } from "obsidian";
import { getField } from "../../domain/index";
import { stripInlineMarkdown } from "../../util/markdown";
import { renderEmptyState } from "../empty-state";
import { renderProgressively } from "../progressive";
import { optNumber, optString } from "../view-options";
import { findColumnByRole, type ResolvedColumn } from "../view-model";
import { openRowDetail } from "../row-detail-modal";
import { collectGalleryImages, type GalleryItem } from "./collect";
import type { KnowledgeView, ViewRenderContext } from "../view";

/** Cap on rendered thumbnails, so a huge board doesn't paint thousands of <img> at once. */
const MAX_IMAGES = 500;
const SIZE = { min: 90, max: 340, def: 180 };
const ASPECT = { min: 50, max: 260, def: 100 }; // ×100 (100 = 1:1 square); CSS aspect-ratio = width/height

function titleColumn(columns: readonly ResolvedColumn[]): ResolvedColumn | undefined {
  return findColumnByRole(columns, "title") ?? columns.find((c) => c.typeId === "link") ?? columns[0];
}

function renderItem(grid: HTMLElement, item: GalleryItem<ResolvedColumn>, ctx: ViewRenderContext, title: ResolvedColumn | undefined): void {
  const card = grid.createDiv({ cls: "kvs-gallery-card" });
  const frame = card.createDiv({ cls: "kvs-gallery-frame" });
  const renderer = ctx.cellRenderers.get("image");
  if (renderer) {
    renderer.render({ el: frame, value: item.embed, column: item.column, app: ctx.app, sourcePath: ctx.sourcePath, component: ctx.component });
  }
  const caption = card.createDiv({ cls: "kvs-gallery-caption" });
  const label = title ? stripInlineMarkdown(getField(item.row, title.name)).trim() : "";
  caption.createSpan({ cls: "kvs-gallery-cap-title", text: label !== "" ? label : item.column.label });
  caption.createSpan({ cls: "kvs-gallery-cap-col", text: item.column.label });
  // Clicking the card opens the row's detail — but not when the click lands on the image itself, which
  // the dashboard's image zoom handles (opening the lightbox). Its handler runs on the container, which
  // is above this card in the bubble path, so a guard here is needed to avoid firing both.
  card.addEventListener("click", (e) => {
    if ((e.target as HTMLElement).closest("img")) return;
    openRowDetail(ctx, item.row);
  });
}

/** A labelled range slider that updates a CSS var live (input) and persists on release (change). */
function slider(parent: HTMLElement, icon: string, label: string, tip: string, min: number, max: number, value: number, onInput: (v: number) => void, onChange: (v: number) => void): void {
  const wrap = parent.createDiv({ cls: "kvs-gallery-slider" });
  setIcon(wrap.createSpan({ cls: "kvs-gallery-slider-ic" }), icon);
  wrap.createSpan({ cls: "kvs-gallery-slider-label", text: label });
  setTooltip(wrap, tip);
  const input = wrap.createEl("input", { cls: "kvs-gallery-range" });
  input.type = "range";
  input.min = String(min);
  input.max = String(max);
  input.step = "5";
  input.value = String(value);
  input.addEventListener("input", () => onInput(Number(input.value)));
  input.addEventListener("change", () => onChange(Number(input.value)));
}

function renderControls(toolbar: HTMLElement, ctx: ViewRenderContext, root: HTMLElement, size: number, aspect: number, fit: string): void {
  const controls = toolbar.createDiv({ cls: "kvs-gallery-controls" });

  // Fit — Fill (cover) vs Fit (contain), as a segmented control.
  controls.createSpan({ cls: "kvs-gallery-ctl-label", text: "Fit" });
  const fitSeg = controls.createDiv({ cls: "kvs-seg kvs-gallery-fitseg" });
  const btns = new Map<string, HTMLElement>();
  for (const [id, label] of [["cover", "Fill"], ["contain", "Fit"]] as const) {
    const b = fitSeg.createEl("button", { cls: "kvs-seg-btn", text: label });
    b.toggleClass("is-on", fit === id);
    setTooltip(b, id === "cover" ? "Fill the frame (crop to fit)" : "Fit the whole image (no crop)");
    btns.set(id, b);
    b.addEventListener("click", () => {
      root.style.setProperty("--kvs-gallery-fit", id);
      for (const x of btns.values()) x.removeClass("is-on");
      b.addClass("is-on");
      ctx.onSetViewOption?.("galleryFit", id);
    });
  }

  slider(controls, "scaling", "Size", "Card size", SIZE.min, SIZE.max, size,
    (v) => root.style.setProperty("--kvs-gallery-size", `${v}px`),
    (v) => ctx.onSetViewOption?.("galleryCardSize", v));
  slider(controls, "rectangle-horizontal", "Ratio", "Image aspect ratio", ASPECT.min, ASPECT.max, aspect,
    (v) => root.style.setProperty("--kvs-gallery-aspect", String(v / 100)),
    (v) => ctx.onSetViewOption?.("galleryAspect", v));
}

function renderGallery(ctx: ViewRenderContext): void {
  const { container, result, profile } = ctx;
  container.empty();
  const root = container.createDiv({ cls: "kvs-view kvs-gallery-view" });

  const target = optString(profile.view.options, "imageColumn");
  const size = optNumber(profile.view.options, "galleryCardSize", SIZE.def);
  const aspect = optNumber(profile.view.options, "galleryAspect", ASPECT.def);
  const fit = optString(profile.view.options, "galleryFit", "cover") === "contain" ? "contain" : "cover";
  root.style.setProperty("--kvs-gallery-size", `${size}px`);
  root.style.setProperty("--kvs-gallery-aspect", String(aspect / 100));
  root.style.setProperty("--kvs-gallery-fit", fit);

  const title = titleColumn(ctx.columns);
  const scanColumns = target !== "" ? ctx.columns.filter((c) => c.name === target) : ctx.columns;
  const scopeLabel = target !== "" ? (scanColumns[0]?.label ?? target) : "all columns";

  const groups = result.groups ?? [{ key: null as string | null, rows: result.rows }];
  const sections: { key: string | null; items: GalleryItem<ResolvedColumn>[] }[] = [];
  let total = 0;
  for (const group of groups) {
    if (total >= MAX_IMAGES) break;
    const items = collectGalleryImages(group.rows, scanColumns, MAX_IMAGES - total);
    if (items.length > 0) sections.push({ key: group.key, items });
    total += items.length;
  }

  const toolbar = root.createDiv({ cls: "kvs-gallery-toolbar" });
  toolbar.createSpan({ cls: "kvs-gallery-count", text: `${total >= MAX_IMAGES ? `${MAX_IMAGES}+` : total} image${total === 1 ? "" : "s"} · ${scopeLabel}` });
  toolbar.createDiv({ cls: "kvs-tb-spacer" });
  renderControls(toolbar, ctx, root, size, aspect, fit);

  if (total === 0) {
    if (result.total === 0) {
      renderEmptyState(root, ctx, "gallery");
    } else {
      const empty = root.createDiv({ cls: "kvs-gallery-empty" });
      empty.createDiv({ cls: "kvs-gallery-empty-title", text: `No images found in ${scopeLabel}.` });
      empty.createDiv({ cls: "kvs-gallery-empty-sub", text: target !== "" ? "Try “all columns”, or pick a different column in view settings." : "Add image embeds to your rows, e.g. ![[picture.png]]." });
    }
    return;
  }

  for (const section of sections) {
    let grid: HTMLElement;
    // The sentinel must sit outside the grid, or the CSS grid would lay it out as another cell.
    let host: HTMLElement;
    if (section.key !== null) {
      const sec = root.createDiv({ cls: "kvs-gallery-group" });
      const head = sec.createDiv({ cls: "kvs-cards-group-header" });
      head.createSpan({ cls: "kvs-group-key", text: section.key });
      head.createSpan({ cls: "kvs-group-count", text: ` · ${section.items.length}` });
      grid = sec.createDiv({ cls: "kvs-gallery-grid" });
      host = sec;
    } else {
      grid = root.createDiv({ cls: "kvs-gallery-grid" });
      host = root;
    }
    // Galleries are the heaviest layout — every card carries an image — so they benefit most from drawing
    // in chunks rather than building the whole grid before the first paint.
    renderProgressively({
      items: section.items,
      renderItem: (item) => renderItem(grid, item, ctx, title),
      sentinelHost: host,
      component: ctx.component,
    });
  }
}

export const galleryView: KnowledgeView = {
  type: "gallery",
  label: "Gallery",
  icon: "images",
  paginates: false, // a gallery shows every matching image, not a single page
  optionSpecs: [
    {
      key: "imageColumn",
      label: "Image column",
      kind: "field",
      fieldFilter: "any",
      description: "Which column's images to show. Leave as “—” to collect images from every column. Size, aspect ratio, and fit are adjustable directly in the gallery toolbar.",
    },
  ],
  render: renderGallery,
};
