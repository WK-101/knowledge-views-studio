/** Full-screen image preview. Click anywhere or press Escape to close. */
export function openImageLightbox(src: string): void {
  const overlay = document.body.createDiv({ cls: "kvs-lightbox" });
  const image = overlay.createEl("img", { cls: "kvs-lightbox-img" });
  image.src = src;

  const close = (): void => {
    overlay.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = (event: KeyboardEvent): void => {
    if (event.key === "Escape") close();
  };
  overlay.addEventListener("click", close);
  document.addEventListener("keydown", onKey);
}

const ZOOMABLE = ".kvs-td, .kvs-card, .kvs-kanban-card, .kvs-row-detail, .kvs-gallery-card";

/**
 * Delegate image clicks within a KVS container to the lightbox. Attaching once
 * to a persistent root covers every future re-render inside it.
 */
export function wireImageZoom(container: HTMLElement): void {
  container.addEventListener("click", (event) => {
    const target = event.target as HTMLElement | null;
    const image = target?.closest("img");
    if (!(image instanceof HTMLImageElement)) return;
    if (!image.closest(ZOOMABLE)) return;
    event.preventDefault();
    event.stopPropagation();
    openImageLightbox(image.currentSrc || image.src);
  });
}
