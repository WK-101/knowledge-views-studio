/**
 * One drag implementation for mouse, touch and pen.
 *
 * The plugin used HTML5 drag-and-drop (`draggable`, `dragstart`, `drop`). Those events do not merely
 * behave badly on touch — **they never fire at all**. So moving a board card, reordering a sort key and
 * resizing a column were *silently impossible* on a phone, while the manifest claimed mobile support.
 * That is the same class of untruth as the `minAppVersion` we corrected in Phase 106, and it gets the
 * same treatment: make the claim true rather than quieter.
 *
 * Pointer Events are the one API that covers every input device, so there is a single code path here
 * instead of a mouse path and a touch path that drift apart.
 *
 * Two gestures, because not every drag surface is *only* a drag surface:
 *
 *   - **A handle** (a resize grip, a reorder grip) exists to be dragged and does nothing else. Claiming
 *     the gesture the instant it starts is safe on any device.
 *   - **A card** is also something you scroll past and tap. Claiming its gesture immediately would make
 *     a board unscrollable on a phone. So on touch a card drag begins only after a **long press**, and
 *     movement before that press completes means the user was scrolling — we let them.
 *
 * The question *"when does a press become a drag?"* is the part that is easy to get subtly wrong and
 * impossible to eyeball, so it is a pure state machine, tested on its own, with no DOM in sight.
 */

export type DragPhase = "pending" | "active" | "cancelled";

export interface DragState {
  readonly phase: DragPhase;
  /** Where the press began — every threshold is measured from here. */
  readonly x: number;
  readonly y: number;
  /** Touch (or pen) rather than mouse: the press must be *held* before it can become a drag. */
  readonly touch: boolean;
}

export interface DragThresholds {
  /** Mouse: how far the pointer must move before a press is a drag rather than a click. */
  readonly mouseSlop: number;
  /** Touch: how far a finger may wander during the hold before we conclude it was a scroll, not a drag. */
  readonly touchSlop: number;
  /** Touch: how long to hold still before a press becomes a drag. */
  readonly longPressMs: number;
}

export const DEFAULT_THRESHOLDS: DragThresholds = {
  mouseSlop: 4,
  touchSlop: 10,
  longPressMs: 350,
};

const distance = (state: DragState, x: number, y: number): number => Math.hypot(x - state.x, y - state.y);

/** A pointer went down. Nothing is a drag yet — that is what the machine is for. */
export function pressStart(x: number, y: number, pointerType: string): DragState {
  return { phase: "pending", x, y, touch: pointerType !== "mouse" };
}

/**
 * The pointer moved. For a mouse, movement *is* the intent to drag. For a finger, movement before the
 * hold completes is the opposite: it means "I am scrolling", and the press must be abandoned so the
 * list keeps scrolling normally.
 */
export function pressMove(state: DragState, x: number, y: number, t: DragThresholds = DEFAULT_THRESHOLDS): DragState {
  if (state.phase !== "pending") return state;
  const moved = distance(state, x, y);
  if (state.touch) return moved > t.touchSlop ? { ...state, phase: "cancelled" } : state;
  return moved > t.mouseSlop ? { ...state, phase: "active" } : state;
}

/** The long press elapsed with the finger still on the card: now it is a drag. */
export function pressHold(state: DragState): DragState {
  return state.phase === "pending" && state.touch ? { ...state, phase: "active" } : state;
}

/** The gesture was taken away from us (the browser started panning, the window blurred, …). */
export function pressCancel(state: DragState): DragState {
  return { ...state, phase: "cancelled" };
}

// ---------------------------------------------------------------------------
// DOM bindings. Thin on purpose: every decision above is already made and tested.
// ---------------------------------------------------------------------------

export interface HandleDragHandlers {
  readonly onStart?: (event: PointerEvent) => void;
  readonly onMove: (event: PointerEvent) => void;
  readonly onEnd?: (event: PointerEvent) => void;
}

/**
 * A dedicated handle — a resize grip, a reorder grip. Dragging it can never have meant "scroll", so it
 * activates immediately on every device. The pointer is *captured*, so the drag keeps tracking even when
 * the finger or cursor leaves the few pixels the handle occupies (which, on a 1px column divider, is
 * always). Pair with `touch-action: none` in CSS so the browser does not pan the pane instead.
 */
export function enableHandleDrag(handle: HTMLElement, handlers: HandleDragHandlers): void {
  handle.addEventListener("pointerdown", (event: PointerEvent) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    handle.setPointerCapture(event.pointerId);
    handle.addClass("kvs-drag-live");
    handlers.onStart?.(event);

    const move = (e: PointerEvent): void => {
      if (e.pointerId === event.pointerId) handlers.onMove(e);
    };
    const finish = (e: PointerEvent): void => {
      if (e.pointerId !== event.pointerId) return;
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", finish);
      handle.removeEventListener("pointercancel", finish);
      handle.removeClass("kvs-drag-live");
      handlers.onEnd?.(e);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", finish);
    handle.addEventListener("pointercancel", finish);
  });
}

export interface LongPressDragHandlers {
  /** Refuse the gesture before it starts (e.g. this row cannot be written back to). */
  readonly canDrag?: () => boolean;
  readonly onStart: (x: number, y: number) => void;
  readonly onMove: (x: number, y: number) => void;
  readonly onDrop: (x: number, y: number) => void;
  readonly onCancel: () => void;
}

/**
 * A surface that is *also* scrollable and tappable — a board card. Mouse: drag begins on movement, so
 * the desktop gesture is unchanged. Touch: drag begins on a long press, and a finger that moves first is
 * scrolling and is left alone.
 *
 * Once a touch drag is live the list must stop scrolling under it. `touch-action` cannot do that job
 * here — the card has to *stay* scrollable right up until the press wins — so the default is blocked
 * directly, which is the one thing that requires a non-passive listener.
 */
export function enableLongPressDrag(
  el: HTMLElement,
  handlers: LongPressDragHandlers,
  thresholds: DragThresholds = DEFAULT_THRESHOLDS,
): void {
  el.addEventListener("pointerdown", (event: PointerEvent) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (handlers.canDrag && !handlers.canDrag()) return;

    let state = pressStart(event.clientX, event.clientY, event.pointerType);
    let timer = 0;

    const blockScroll = (e: TouchEvent): void => {
      if (state.phase === "active") e.preventDefault();
    };
    // A long press on a phone raises the system selection/context menu — which would land in the middle
    // of the drag we just started. Only suppressed for touch: right-click must still work on desktop.
    const blockMenu = (e: Event): void => {
      if (state.touch && state.phase !== "cancelled") e.preventDefault();
    };
    document.addEventListener("touchmove", blockScroll, { passive: false });
    el.addEventListener("contextmenu", blockMenu);

    const cleanup = (): void => {
      window.clearTimeout(timer);
      document.removeEventListener("touchmove", blockScroll);
      el.removeEventListener("contextmenu", blockMenu);
      el.removeEventListener("pointermove", onMove);
      el.removeEventListener("pointerup", onUp);
      el.removeEventListener("pointercancel", onCancel);
      el.removeClass("kvs-dragging");
    };

    const begin = (x: number, y: number): void => {
      el.addClass("kvs-dragging");
      handlers.onStart(x, y);
    };

    /** A drag that ends on the element it began on still fires a click. Swallow exactly one. */
    const swallowClick = (): void => {
      const swallow = (e: MouseEvent): void => {
        e.preventDefault();
        e.stopPropagation();
      };
      el.addEventListener("click", swallow, { capture: true, once: true });
      window.setTimeout(() => el.removeEventListener("click", swallow, { capture: true }), 400);
    };

    const onMove = (e: PointerEvent): void => {
      if (e.pointerId !== event.pointerId) return;
      const wasActive = state.phase === "active";
      state = pressMove(state, e.clientX, e.clientY, thresholds);
      if (state.phase === "cancelled") {
        cleanup();
        return;
      }
      if (state.phase !== "active") return;
      if (!wasActive) begin(e.clientX, e.clientY);
      handlers.onMove(e.clientX, e.clientY);
    };

    const onUp = (e: PointerEvent): void => {
      if (e.pointerId !== event.pointerId) return;
      const dropped = state.phase === "active";
      cleanup();
      if (!dropped) return;
      swallowClick();
      handlers.onDrop(e.clientX, e.clientY);
    };

    const onCancel = (e: PointerEvent): void => {
      if (e.pointerId !== event.pointerId) return;
      const wasActive = state.phase === "active";
      state = pressCancel(state);
      cleanup();
      if (wasActive) handlers.onCancel();
    };

    el.addEventListener("pointermove", onMove);
    el.addEventListener("pointerup", onUp);
    el.addEventListener("pointercancel", onCancel);

    if (state.touch) {
      timer = window.setTimeout(() => {
        const held = pressHold(state);
        if (held.phase !== "active") return;
        state = held;
        // Only now is the gesture ours; capturing here (rather than on pointerdown) leaves the browser
        // free to treat an ordinary swipe as the scroll it is.
        el.setPointerCapture(event.pointerId);
        begin(state.x, state.y);
        handlers.onMove(state.x, state.y);
      }, thresholds.longPressMs);
    }
  });
}

// ---------------------------------------------------------------------------
// The floating card that follows the pointer. HTML5 drag-and-drop drew this for free; a pointer drag
// has to draw it, which is the price of it working on a phone at all.
// ---------------------------------------------------------------------------

let ghost: HTMLElement | null = null;

/** Show a translucent copy of `source` under the pointer. Never interactive: it must not eat hit-tests. */
export function showDragGhost(source: HTMLElement, x: number, y: number): void {
  hideDragGhost();
  const el = source.cloneNode(true) as HTMLElement;
  el.addClass("kvs-drag-ghost");
  el.removeClass("kvs-dragging");
  el.style.width = `${source.getBoundingClientRect().width}px`;
  document.body.appendChild(el);
  ghost = el;
  moveDragGhost(x, y);
}

export function moveDragGhost(x: number, y: number): void {
  if (!ghost) return;
  ghost.style.left = `${x}px`;
  ghost.style.top = `${y}px`;
}

export function hideDragGhost(): void {
  ghost?.remove();
  ghost = null;
}

/**
 * What is under the pointer, ignoring the ghost. The ghost is `pointer-events: none` in CSS, so the hit
 * test sees straight through it — which is the whole reason that rule exists.
 */
export function dropTargetAt(x: number, y: number, selector: string): HTMLElement | null {
  const el = document.elementFromPoint(x, y);
  return el instanceof HTMLElement ? el.closest<HTMLElement>(selector) : null;
}
