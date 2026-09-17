# Kairo — Hidden-volume (plausible-deniability) vault: design & why it isn't shipped yet

Kairo's Vault encrypts individual notes with a passphrase-derived AES-GCM envelope
(`PortableCrypto` / `NoteVault`). This document specifies what a *deniable* vault —
one that survives coercion ("unlock it or else") — would require, and states plainly
why shipping a partial version would be **worse than shipping nothing**.

This is a design record (R2-D frontier). It is deliberately not a feature yet.

## The threat it targets (T5 — coerced disclosure)

The adversary has the unlocked device in hand and can compel a passphrase: a border
stop, an abusive partner, a "hand it over" moment. Encryption at rest (T3) does
nothing here — the user is forced to open the door. A deniable design lets the user
hand over *a* passphrase that reveals innocuous decoy content, while the real content
stays not just hidden but **unprovable to exist**.

## Why the obvious version is false security

The tempting shortcut — "a second 'duress' passphrase that decrypts to decoys" — fails
the only test that matters, because **the real data's existence must be unprovable**:

- A vaulted note today is a visible row whose body is a recognizable `PortableCrypto`
  envelope (`{"magic":"kairo-encrypted-backup",...}`). Anyone imaging the DB or a
  backup can *count the encrypted notes*. A duress passphrase that opens only some of
  them advertises that the others exist — so the coercer simply says "now the real
  one." Deniability that can be disproven is a trap: it invites escalation and the user
  is worse off than with an honest "yes, I have private notes, here they are."
- The backup format, the row count, the FTS index, the `notes_vault_check` verifier,
  attachment blobs, and even file sizes are all **existence oracles**. Closing one and
  leaving the others is not deniability.

So the bar is absolute: **an adversary with full at-rest access (DB + backups +
KeyStore state) and one valid passphrase must not be able to demonstrate that a second
passphrase, or any further data, exists.** Nothing less is worth building.

## What a real design requires

### 1. Indistinguishable container (the core)
Both the decoy and the hidden content must live in one fixed-size, fully-random-looking
blob, so the *presence* of hidden data is statistically undetectable.

- Allocate a single container of fixed size `N` (e.g. user picks 16/64/256 MB),
  initialised with CSPRNG bytes. Ciphertext and unused space are both random, so free
  space is indistinguishable from data.
- **Outer (decoy) volume**: key `K_d = KDF(pass_d, salt)`, header + FS placed at the
  container start.
- **Hidden volume**: key `K_h = KDF(pass_h, salt)`, header at a passphrase-derived
  offset in the *tail* region, its blocks interleaved so they look like the outer
  volume's free space. Its header is itself encrypted, so without `pass_h` there is
  nothing that parses as a header — the region is just random bytes.
- Opening: try to authenticate the outer header, then attempt a hidden header at the
  derived offset. A wrong passphrase authenticates neither (AEAD tag fails) → the
  mount simply "fails", revealing nothing about which volume, if any, exists.
- This is the VeraCrypt/TrueCrypt hidden-volume model, adapted to a file the app owns.

### 2. Memory-hard KDF (hard dependency)
Both keys derive via **Argon2id**, not PBKDF2. The whole blob is offline and
attacker-controlled; PBKDF2 is too cheap to protect a duress secret against a motivated
adversary. This is gated on the R2-B Argon2id decision — a deniable vault should not
ship on PBKDF2. (The envelope/marker seam from R2-B is where it plugs in.)

### 3. No existence oracles anywhere
- The hidden volume is **excluded from every backup and export** (like the DB key and
  sync passphrase already are), or backed up only as opaque container bytes.
- No separate verifier row, no FTS entries, no attachment rows, no distinct file — the
  container is one file of fixed size regardless of contents.
- Writing to the outer volume must not corrupt hidden blocks the outer key can't see
  (the classic hidden-volume risk): either a **"protect hidden volume" mount mode**, or
  accept that outer-volume writes can destroy the hidden volume and document it.

### 4. Honest UX and irreversibility
- Setup makes the fixed cost explicit (the container reserves `N` bytes forever).
- There is **no recovery**: lose `pass_h` and the hidden volume is gone; there is no
  "forgot passphrase". A duress reveal must look identical to a device with no hidden
  volume at all.
- The feature must never leave a UI trace (no "Hidden vault" toggle visible after
  setup, no menu item that only appears when one exists) — that too is an oracle.

## Why it's not in the app today

1. **Argon2id isn't in yet** (R2-B), and a deniable vault on PBKDF2 is not worth
   shipping.
2. **Rolling our own hidden-volume container is high-risk crypto engineering.** The
   interleaving, the "protect hidden volume" write path, and the no-oracle guarantee
   are exactly where deniable-encryption implementations get subtly broken — and a
   subtle break here doesn't degrade gracefully, it gets a user hurt. This is the one
   place "ship a partial version" is actively dangerous.
3. **The honest limit already documented** (see `docs/SECURITY.md` / the re-audit) is
   the correct interim posture: Kairo defends the lost/stolen device (T3) well and
   states plainly that it does not defend coerced disclosure (T5). Saying so is safer
   than a deniability feature a coercer can disprove.

## If/when it is built
- Land Argon2id first (R2-B).
- Prototype the container format and an adversarial "existence test" harness (given the
  container + outer passphrase, prove you cannot detect the hidden volume) **before** any
  UI. The test harness is the deliverable that earns the feature the right to exist.
- Keep it entirely offline and permission-free, consistent with the rest of Kairo.

_This document is intent and rationale; it ships no code and changes no behavior._
