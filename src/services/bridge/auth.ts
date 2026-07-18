/**
 * Pairing and tokens.
 *
 * Pure by design: no crypto module, no Obsidian, no clock beyond what's passed in — so every rule here can
 * be tested directly rather than inferred from a running server.
 *
 * The model is deliberately small. Someone opens settings, gets a short code, types it into the extension
 * once, and the extension receives a long random token it keeps. The code is the thing a person handles, so
 * it's short and expires quickly; the token is the thing software handles, so it's long and permanent until
 * revoked. Nothing else is ever accepted.
 */

/** How long a pairing code stays valid. Long enough to copy across, short enough not to linger. */
export const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;

const CODE_ALPHABET = "0123456789";
const TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

/** Random bytes, injectable so tests are deterministic. */
export type RandomBytes = (length: number) => Uint8Array;

export const defaultRandomBytes: RandomBytes = (length) =>
  crypto.getRandomValues(new Uint8Array(length));

function fromAlphabet(alphabet: string, length: number, random: RandomBytes): string {
  const bytes = random(length);
  let out = "";
  for (let i = 0; i < length; i++) out += alphabet[(bytes[i] ?? 0) % alphabet.length];
  return out;
}

/** A short code a person can read off the screen and type into the extension. */
export function generatePairingCode(random: RandomBytes = defaultRandomBytes): string {
  return fromAlphabet(CODE_ALPHABET, 6, random);
}

/** The long-lived secret the extension keeps once pairing succeeds. */
export function generateToken(random: RandomBytes = defaultRandomBytes): string {
  return fromAlphabet(TOKEN_ALPHABET, 40, random);
}

/**
 * Compare two secrets without leaking, through timing, how much of one matched.
 *
 * A plain `===` bails at the first differing character, so the time it takes reveals the length of the
 * matching prefix — enough to recover a token one character at a time. This always walks the full length.
 */
export function secretsMatch(expected: string | null | undefined, given: string | null | undefined): boolean {
  if (typeof expected !== "string" || typeof given !== "string") return false;
  if (expected.length === 0 || given.length === 0) return false;
  // Compare against a fixed span so differing lengths don't short-circuit either.
  const span = Math.max(expected.length, given.length);
  let diff = expected.length ^ given.length;
  for (let i = 0; i < span; i++) {
    diff |= (expected.charCodeAt(i) || 0) ^ (given.charCodeAt(i) || 0);
  }
  return diff === 0;
}

export interface PendingPairing {
  readonly code: string;
  readonly expiresAt: number;
}

/** Start a pairing: a fresh code and when it stops being valid. */
export function beginPairing(now: number, random: RandomBytes = defaultRandomBytes): PendingPairing {
  return { code: generatePairingCode(random), expiresAt: now + PAIRING_CODE_TTL_MS };
}

export type PairingOutcome =
  | { readonly ok: true; readonly token: string }
  | { readonly ok: false; readonly reason: string };

/**
 * Complete a pairing.
 *
 * A code is single-use by construction: the caller drops the pending pairing whichever way this goes, so a
 * wrong guess costs a whole new code rather than another attempt at the same one.
 */
export function completePairing(
  pending: PendingPairing | null,
  given: string,
  now: number,
  random: RandomBytes = defaultRandomBytes,
): PairingOutcome {
  if (pending === null) return { ok: false, reason: "No pairing is in progress." };
  if (now > pending.expiresAt) return { ok: false, reason: "That pairing code has expired." };
  if (!secretsMatch(pending.code, given.trim())) return { ok: false, reason: "That pairing code is not correct." };
  return { ok: true, token: generateToken(random) };
}
