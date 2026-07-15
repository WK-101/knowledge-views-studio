import { enableHandleDrag } from "../util/pointer-drag";

/** Minimal popover used by the dashboard toolbar menus. One open at a time. */
export interface PopoverHandle {
  /** Close the popover. */
  readonly close: () => void;
  /** Re-run the builder into the same element, preserving open state. */
  readonly rerender: () => void;
}

let current: { readonly el: HTMLElement; readonly cleanup: () => void } | null = null;

export function closePopover(): void {
  if (current) {
    current.cleanup();
    current = null;
  }
}

export function openPopover(
  anchor: HTMLElement,
  build: (content: HTMLElement, handle: PopoverHandle) => void,
): void {
  closePopover();
  const el = document.body.createDiv({ cls: "kvs-popover" });

  const place = (): void => {
    const rect = anchor.getBoundingClientRect();
    el.style.top = `${rect.bottom + 4 + window.scrollY}px`;
    el.style.left = `${rect.left + window.scrollX}px`;
    // Cap to the room below the anchor so a tall menu scrolls instead of running off-screen.
    el.style.maxHeight = `${Math.max(180, window.innerHeight - rect.bottom - 16)}px`;
    const box = el.getBoundingClientRect();
    if (box.right > window.innerWidth - 8) {
      el.style.left = `${Math.max(8, window.innerWidth - box.width - 8) + window.scrollX}px`;
    }
  };

  const handle: PopoverHandle = {
    close: closePopover,
    rerender: () => {
      el.empty();
      build(el, handle);
      place();
    },
  };

  // Close on an outside press. This must fire on mousedown, *before* an inside click handler can call
  // handle.rerender() (which empties `el` and detaches the clicked node) — otherwise a later click
  // check would see the now-orphaned target as "outside" and wrongly close the popover.
  const onDocPointerDown = (event: MouseEvent): void => {
    const target = event.target as Node;
    if (!el.contains(target) && !anchor.contains(target)) closePopover();
  };
  const onKey = (event: KeyboardEvent): void => {
    if (event.key === "Escape") closePopover();
  };

  // Defer so the click that opened the popover doesn't immediately close it.
  window.setTimeout(() => document.addEventListener("mousedown", onDocPointerDown), 0);
  document.addEventListener("keydown", onKey);
  current = {
    el,
    cleanup: () => {
      document.removeEventListener("mousedown", onDocPointerDown);
      document.removeEventListener("keydown", onKey);
      el.remove();
    },
  };

  build(el, handle);
  place();
}

/**
 * Make `handle` a drag grip that reorders its `row` within a list. `onReorder` is
 * called with (fromIndex, toIndex) on drop — mirrors the grip-drag reordering of
 * sort keys and properties in the Bases toolbar.
 */
export function enableRowDrag(
  handle: HTMLElement,
  row: HTMLElement,
  index: number,
  onReorder: (from: number, to: number) => void,
): void {
  // The grip carries its own index, so the row under the pointer can be asked where it wants to land
  // without a `dataTransfer` — which is just as well, since `dataTransfer` does not exist on touch.
  row.dataset.kvsIndex = String(index);
  let target: HTMLElement | null = null;

  const rowUnder = (event: PointerEvent): HTMLElement | null => {
    const el = document.elementFromPoint(event.clientX, event.clientY);
    return el instanceof HTMLElement ? el.closest<HTMLElement>("[data-kvs-index]") : null;
  };
  const clearTarget = (): void => {
    target?.removeClass("kvs-drop-target");
    target = null;
  };

  enableHandleDrag(handle, {
    onStart: () => row.addClass("kvs-dragging"),
    onMove: (event) => {
      const over = rowUnder(event);
      if (over === target) return;
      clearTarget();
      if (over && over !== row) {
        target = over;
        target.addClass("kvs-drop-target");
      }
    },
    onEnd: () => {
      const to = target ? Number(target.dataset.kvsIndex) : NaN;
      clearTarget();
      row.removeClass("kvs-dragging");
      if (!Number.isNaN(to) && to !== index) onReorder(index, to);
    },
  });
}
