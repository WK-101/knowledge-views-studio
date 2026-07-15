import { Platform } from "obsidian";

/**
 * What kind of machine we are running on.
 *
 * Kept behind a tiny interface rather than calling `Platform.isMobile` at each site, for two reasons.
 * The first is testability: every rule that *depends* on the device can then be a pure function taking a
 * `DeviceProfile`, and can be tested for a phone from a desktop test runner. The second is honesty — a
 * single seam makes it possible to answer "what actually changes on mobile?" by reading one list, rather
 * than by grepping for scattered `if (isMobile)` and hoping the grep was complete.
 */
export interface DeviceProfile {
  /** Phone or tablet: a battery, a slower CPU, and a webview with less memory to spend. */
  readonly mobile: boolean;
  /** A phone specifically: also a *small* screen, which is a layout question, not a capability one. */
  readonly phone: boolean;
}

export const DESKTOP: DeviceProfile = { mobile: false, phone: false };

export function currentDevice(): DeviceProfile {
  return { mobile: Platform.isMobile, phone: Platform.isPhone };
}
