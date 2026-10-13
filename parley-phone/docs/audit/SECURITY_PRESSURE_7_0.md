# Security pressure test, 7.0

The owner asked: "Pressure test all encryption features, private contacts, and backup. Are they robust enough to be a
top-of-the-line app with no leaks?" This is the answer: what was attacked, how, what held, what was fixed in 7.0, and
what is left for an owner decision. It builds on round 3 (docs/audit/round3/SECURITY.md) and does not repeat what that
round verified as fixed; docs/SECURITY_MODEL.md is the reference for how each part is meant to work.

**Short answer.** The cryptography held under every attack tried: no forged, altered, truncated or downgraded backup
opened, the KDF costs are capped, nonces don't repeat, keys never sit plain on disk, and writes survive a crash. The
leaks found were all *around* the encryption, where private data meets Android: the recents thumbnail, the keyboard's
memory, Android's call log, and plain number keys in two bookkeeping tables. Six were fixed in this release (one High,
four Medium, one Low and a robustness fix); the rest are low-risk, documented, and two need an owner decision.

## Method

1. Read SECURITY_MODEL.md, round 3's report and AUDIT_3's SA rows, then the code of every store, key and exit point
   listed below. Each claim in the model was checked against the code, not the other way round.
2. Threat model (below) applied one attacker at a time, asking "what does this person get that the model says they
   don't?".
3. Pressure tests written where a unit test can attack the real code: exhaustive bit flips and cuts of a backup,
   crafted archives (hostile names, duplicates, bombs, oversized sections, random damage), KDF and version downgrades,
   nonce uniqueness, concurrent and interrupted writes. Leak checks became fixes with focused tests.
4. Each check below is marked **Pass** (held), **Fixed** (a real issue, fixed in 7.0 with a test), or **Accepted**
   (a residual risk, with the reasoning) and, where the owner should choose, **Owner**.

## Threat model

| Attacker | What they have | In scope |
|---|---|---|
| Thief of a **locked** phone | The device, no PIN | Yes |
| Someone holding the **unlocked** phone for a moment, or a shoulder surfer | The screen, Recents, the keyboard's suggestions, other apps | Yes |
| Someone who **coerces** you to open Parley | The duress PIN's session | Yes |
| A **malicious app** on the phone | Its own permissions (call log, contacts, overlays, IPC) | Yes |
| Someone with a **copy of a backup**, or write access to its folder | The file; time to guess offline; the ability to swap files | Yes |
| A **forensic image** of app storage from a running phone, without the Keystore | Every file Parley wrote | Yes (Keystore protects) |
| Crash, battery pull or process kill **mid-write** | — | Yes |
| Restore on **another phone or an older version** | — | Yes |
| Root, a compromised OS or Keystore, hardware attacks | — | No (as in the model) |

## Findings

| ID | Severity | Finding | Result |
|---|---|---|---|
| SP-1 | High | Archiving left the person's name on every old call in Android's call log, readable by any app with call-log access; three Parley paths also wrote stored names back (undo of a call delete, restore with the archive off, CSV import). Same for private contacts (OG-1, docs/OPENCONTACTS_LOGGER_STUDY.md) | **Fixed** |
| SP-2 | Medium | Without the app lock, Android's recents thumbnail kept a picture of a private contact's page or a list with private names, shown to whoever opens Recents and still there after the contacts were hidden or locked; Android stores it outside Parley's keys | **Fixed** |
| SP-3 | Medium | The keyboard learned what was typed about private contacts (names, numbers, notes, searches) and could suggest it in other apps or sync it; only safe words were protected | **Fixed** |
| SP-4 | Medium | A private contact's number was written in the clear (only file-based encryption) with the time of each call: in `call_rings` (every incoming ring, 60 days) and in the call-time ledger `call_usage` (35 days, when an allowance is set). A forensic image got the numbers the sealed private history hides | **Fixed** |
| SP-5 | Low | Two writes of the same file at once shared one `.tmp`: one could truncate it while the other renamed it into place, so a torn file (or, with a crash, a damaged key file) was possible | **Fixed** |
| SP-6 | Low | An empty passphrase made the backup reader throw an `IllegalArgumentException` instead of "wrong passphrase" (the restore screen already blocked it) | **Fixed** |
| SP-7 | Low | Plain number keys for private contacts remain in `call_notes` (a note's number and call date), `number_sim` (a chosen SIM), `speed_dial`, and `private_calls`' date, duration and type beside the sealed number | **Accepted / Owner** |
| SP-8 | Low | A backup that opens with your passphrase can be forged by anyone who saw one of your backups (the public-key wrap); it is shown as unsigned or from an unknown phone, and safety settings are never restored silently, but contacts, blocking and the other data parts are ticked by default | **Accepted / Owner** |
| SP-9 | Low | Deleted rows stay in SQLite's free pages until reused (`secure_delete` is off). Sealed rows stay sealed; the plain columns of SP-7 linger a little longer | **Accepted / Owner** |
| SP-10 | Low | Passphrases and PINs pass through Compose text fields as Java strings, which can't be wiped | **Accepted** |
| SP-11 | Low | The in-call screen's note field (a separate activity) doesn't yet ask the keyboard not to learn | **Accepted** (follow-up) |
| SP-12 | Info | Sealed rows carry no associated data binding them to their row (round 2's C-16): swapping needs write access to the database (root) | **Accepted** |

### SP-1 (High): names in Android's call log — fixed

*Before.* Android's call log caches each call's name (`CACHED_NAME`, label, lookup link, photo). Archiving a contact
removes it from the address book, but every old call kept the name; Google's Phone, Android Auto and any app holding
`READ_CALL_LOG` read it. Parley also wrote names back on three paths. A private contact made from a phone contact kept
its old calls' names the same way when "Private call history" is off.

*Fix.* `CallLogNames` (core/common `history/`) decides which name may be written: only one a visible address-book
contact owns now (archived contacts have left the address book; a private contact's number never counts), failing
closed when the lookup fails. `callLogNames(contacts, vault)` applies it to the restore (`BackupRepository`,
`CallHistory.ProviderRestore`), the undo of a call delete (`CallHistory.undoDeleteLocked`) and the CSV import
(`CallHistory.runImport`). `CallLogRepository.clearCachedNames` takes the cached fields off every call with an
archived contact's numbers right after archiving (`ArchiveStore.archive`), and the daily upkeep does the same for every
archived and private number no visible contact owns (`MaintenanceWorker`, "call log names"), which also covers calls
from older versions. The calls themselves stay; Parley names its own Recents live, so nothing changes in Parley.
Making a private contact visible again ("Move to phone") still writes its calls back with the name: that person is now
visible on purpose. Tests: `CallLogNamesTest`.

### SP-2 (Medium): recents thumbnail — fixed

*Before.* `setRecentsScreenshotEnabled(false)` (Android 13+) and the FLAG_SECURE fallback were applied only with the app
lock. With it off, the default, the thumbnail of a private contact's page was saved by the system and shown in Recents.

*Fix.* `WindowShield` (core/common `security/`) decides the window: the thumbnail is blank while the app lock is on (as
before) **or** while private contacts can be on Parley's screen (`privateExposed`: some exist and `PrivacyView`
shows them, or details were just opened). `MainActivity` feeds it from `c.privacy.flow`, the vault's listing and its
unlocked window; before the listing is read it counts as exposed. Nothing changes for people without private contacts
or with them hidden. Tests: `WindowShieldTest`.

### SP-3 (Medium): the keyboard's memory — fixed

*Before.* Only the safe-word field asked the keyboard not to learn (`IME_FLAG_NO_PERSONALIZED_LEARNING`).

*Fix.* `NoKeyboardLearning` moved to core/ui (`KeyboardPrivacy.kt`), keeping one composition tree when switched, so
nothing typed is lost. It now wraps the whole app while private contacts can show (the same `privateExposed`), the
editor whenever it edits or creates a private contact (so the very first one is covered), and every dialog on a
private contact's page. Tests: `KeyboardPrivacyTest`.

### SP-4 (Medium): plain private numbers — fixed

*Fix.* The ring path (`AppTelecomDependencies.onRingFinished`) no longer records ring lengths for a private contact's
number (they serve one-ring-scam and spam guesses for numbers nobody saved, and private numbers are never scored); the
call-time ledger keeps a private contact's call without its number (`lineKey` null): the allowance still counts it,
from the sealed private history for per-person rules and by SIM or in total from the ledger. Both checks go through
`NumberOwners.privateIn` and fail closed. Rows written before, and rows for numbers that become private later, are
cleared where the network's names already were (`DataContainer`, at every full start and for each new private number:
`BlockRepository.forgetRings`, `CallUsageLedger.forgetLines`, every key form via `PhoneIdentity.lookupKeysIn`). No
schema change. Tests: `CallLogNamesTest.private_lines_are_cleared_under_every_key_a_store_may_hold`.

### SP-5, SP-6 (Low) — fixed

`DurableFiles.writeOrThrow` now takes turns per file (64 striped locks by path, reentrant): `DurableFilesTest.
concurrent_writes_of_one_file_never_tear_it` runs 8 threads × 25 slow writes and finds one whole payload. An empty
passphrase is a wrong one (`BackupCrypto.open`): `BackupPressureTest.the_wrong_or_an_empty_passphrase…`.

### SP-7 (Low): remaining plain number keys — accepted, owner decision

These hold a private contact's number (not the name) beside a time, protected by file-based encryption only:
`call_notes.numberKey` (the note itself is sealed), `number_sim`, `speed_dial` (both the user's own choice), and the
private history's call times. Sealing them means keying lookups by an HMAC, a migration of four stores read on the
call path. **Owner:** do it in 7.1 (recommended, together with C-16's blob binding), or accept.

### SP-8 (Low): forged backups — accepted, owner decision

The signature design already tells a forgery apart (`ArchiveOrigin`: unsigned, unknown signer, altered) and the restore
screen warns; safety settings and private-name approvals are never restored silently. A forger still needs you to
choose their file and confirm. **Owner:** untick the data parts by default for an unsigned, unknown or altered backup
(one line in `RestoreFlow`), or keep today's behaviour.

### SP-9 (Low): SQLite free pages — accepted, owner decision

`PRAGMA secure_delete` would overwrite deleted rows at a write cost on every delete (the history prune deletes in bulk).
Everything personal is sealed, so remnants are ciphertext under keys that "Delete all data" destroys. **Owner:** turn it
on for `parley.db` only (small, few deletes), or accept.

## Every check

### Key management

| Check | Result |
|---|---|
| Wrapped software keys (`history.keys`, `records.keys`, `vault_calls.keys`, `vault_summaries.keys`, `memory.keys`) never written plain; written with `DurableFiles` before anything is sealed with them | Pass |
| Lost vs temporary Keystore errors (`isPermanent`, `isProvable`): data never re-keyed on a guess | Pass |
| Detail key: auth-bound (300 s, biometric strong or device credential), `setUnlockedDeviceRequired`, StrongBox first, upgraded after the first authentication on a phone that gains a lock | Pass |
| Detail key survives a new fingerprint (`setInvalidatedByBiometricEnrollment(false)`) | Accepted: whoever can enrol a finger knows the screen lock, which opens the key anyway; the Parley PIN is the guard against that person, and losing every private contact to a new fingerprint would be worse |
| Caller-ID and HMAC keys without authentication (incoming calls on a locked phone) | Accepted, as designed and documented |
| "Delete all data" deletes every alias (crypto-shredding what remains) | Pass |

### Private contacts and every exit

| Check | Result |
|---|---|
| Caller-ID copy holds only what the lock screen needs; details under the detail key | Pass |
| Cold-start caches (`PrivateSummaryCache`, the list head): sealed, private rows dropped at lock or duress | Pass |
| Search, Recall, Next up, widgets, shortcuts, Direct Share, the system contact picker | Pass: widgets, shortcuts and pickers read only the address book (`ContactCapability.HOME_SCREEN_SHORTCUT` off for private contacts); no Direct Share targets; Android's picker never sees private contacts |
| Notifications and their public versions; duress | Pass (`DuressWalkTest`, round 3 fixes) |
| Clipboard | Pass: `Clipboard.copy` marks clips sensitive |
| Recents thumbnail | **Fixed** (SP-2) |
| Keyboard learning | **Fixed** (SP-3); in-call note field: SP-11 |
| Android's call log names | **Fixed** (SP-1) |
| Plain number keys in Parley's tables | **Fixed** for rings and the ledger (SP-4); others SP-7 |
| Logs | Pass: R8 removes every `android.util.Log` call in release; no `printStackTrace`/`System.out` in app code; crash reports keep frames without messages |
| WorkManager inputs (stored plain in its database) | Pass: job ids, generic labels, an export's own URI; no names or numbers |
| Cache folders | Pass: shared files swept after an hour, camera shots and framed avatars swept, backup photo spool sealed with a key held only in memory and unlinked at once |
| Saved instance state | Pass: text typed for a private contact stays out of it (`ContactDialog.PrivateSaver`) |
| Android backup and device transfer | Pass: `allowBackup=false`, every domain excluded from cloud backup and device transfer |
| Exported components, providers, PendingIntents | Pass: every exported activity is a `LockedActivity` or listed (`ExportedComponentsTest`); the Directory provider is disabled until approved and fails closed; no `FLAG_MUTABLE`; the only broadcast is package-scoped |
| FileProvider paths | Pass: cache sub-folders and ringtones only; no private storage |

### App lock and duress

| Check | Result |
|---|---|
| PIN storage: scrypt (2^14, 8, 1) hashes only, one salt, sealed record, never in backups | Pass |
| Constant work: one derivation, both comparisons, every time | Pass |
| Brute force: 5 free, then 30 s doubling to 1 h, counted before checking, elapsed-time based, restart restarts the wait, count kept in memory when it can't be stored | Pass: a 4-digit PIN takes over a year at the screen |
| "Change PIN" is no oracle and is rate limited | Pass |
| What duress reveals: lists, notes, insights, case files, safe words, backups, exports, notifications | Pass (`DuressWalkTest`); "What still differs" in SECURITY_MODEL stays accurate |

### Encrypted backup

| Check | Result |
|---|---|
| Format: STREAM over AES-256-GCM, 64 KiB segments, header as AAD of every segment | Pass |
| KDF: scrypt 2^15/8/1 for new files; PBKDF2 600k readable; read caps (PBKDF2 ≤ 2M, scrypt ≤ 2^16, ≤ 64 MB, p ≤ 4), refused before deriving; at most 2 bundle derivations per attempt | Pass (`BackupPressureTest.kdf_settings…`, `BackupSignatureTest`) |
| Every bit flip anywhere, every truncation, extension, segment reordering, a header moved to another backup | Pass (`BackupPressureTest`, `BackupCryptoTest`) |
| Version downgrade (envelope, key bundle, archive format) | Pass: unknown versions refused; the version is in the AAD |
| Nonce uniqueness: per-segment counter + last flag under a fresh prefix and key per backup; 96-bit random nonces for sealed records | Pass |
| Passphrase strength: "Strong" required for backups and encrypted vCards | Pass |
| Passphrase in memory | Accepted (SP-10); derived keys and DEKs are wiped after use |
| Restore of crafted archives: zip slip names, duplicates, unlisted or missing entries, zip bombs, oversized sections, random damage | Pass (`BackupPressureTest`). An optional section named like `x-../x` passes the name check (its folder part is the literal `x-..`) but is inert: readers ask only for their own fixed folders (`x-tunes/`…) and take file names without `/`, `.` or `..` |
| Signatures: device ECDSA key, endorsed by the bundle; stripping or swapping shows as unsigned or altered | Pass; see SP-8 |
| Golden backups from every earlier format still restore | Pass (`GoldenBackupsTest`); no format changed in 7.0 |
| Restore on an older version | Pass: optional `x-` sections are verified and ignored by older readers |

### Sealed stores and writes

| Check | Result |
|---|---|
| `SealedDocument`: unreadable never taken for empty, never written plain, index written last | Pass |
| `StatePrefs` / `PreferenceFile`: flags and settings only (no personal content); moves committed before the old file goes | Pass |
| Case files, booking codes, call rounds, check-ins: sealed, out of backups during duress, call screen only while unlocked | Pass |
| Power cut at any step of a write | Pass (`DurableFilesTest`) |
| Concurrent writes of one file | **Fixed** (SP-5) |

## Owner decisions

1. **SP-7:** seal the remaining number keys with an HMAC in 7.1 (recommended), or accept.
2. **SP-8:** untick a suspicious backup's data parts by default, or keep today's warning only.
3. **SP-9:** turn on `secure_delete` for `parley.db`, or accept.

No new permission, setting or menu item was added.
