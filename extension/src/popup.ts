import { startSurface } from "./lib/surface";

// A glance: capture what's in front of you, highlight it, or search. Anything that means working through
// a list belongs in the sidebar, which doesn't vanish when you click the page.
document.addEventListener("DOMContentLoaded", () => startSurface("popup"));
