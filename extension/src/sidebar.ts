import { startSurface } from "./lib/surface";

// The full surface: capture, review what was captured, highlight, edit, search, and work through your
// views — all without the panel closing the moment you touch the page.
document.addEventListener("DOMContentLoaded", () => startSurface("sidebar"));
