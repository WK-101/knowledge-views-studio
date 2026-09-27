# Parley audit: data layer and telecom (correctness and robustness)

Scope: `core/data` (contacts, keys, vault, backup, temporary contacts, circle, extras, Room, call history and archive, folder sync, time machine, journal) and `telecom` (InCallService, CallManager, CallNotifier, audio, proximity, screening, call clock), plus the app-side glue that decides their behaviour (`CallGate`, `CallTimePlanner`, `AppTelecomDependencies`, workers, `MainActivity`, `ContactEditScreen`).

How this was done: I read the code, with no build and no device. Every claim cites file:line. "Likely" marks an inference about platform behaviour that should be checked on a device.

---

## 1. Summary and scores

Overall the code is careful, and much better than typical for this size. There is plenty of defensive work: screening that fails open and answers exactly once, `NonCancellable` on destructive jobs, backups written to `.partial` and verified, a rotation pause when many contacts vanish, read-only data rows respected, raw ids tracked for temporary contacts, a lost archive key moved aside rather than wiped, a ring-volume boost that is restored after a crash, emergency exemptions for call limits, and Room schemas exported with no destructive fallback.

The problems are at the system level:
- **What survives a phone migration is incomplete, and nothing documents it.** Several user-authored stores are left out of the backup.
- **Safety paths still add friction for emergency calls.**
- **Some "whole data set" operations quietly work on a window.** Examples: the 3000 newest calls, or the personal profile only.
- **Scale and lifecycle problems in a few hot spots:** folder sync, and the contact editor's draft.
- There are **no tests at all** for `core/data` or `telecom`, including Room migrations.

| Sub-area | Score | One-line justification |
|---|---|---|
| Contacts write path (save/merge/split/delete, read-only, multi-account) | 7/10 | Careful diff-only writes and read-only row locking, but no optimistic concurrency, and undo copies can be skipped silently. |
| Keys and metadata re-keying (ContactKeys/MetaRekey) | 7/10 | Sound id+key resolution with plausibility checks. Writes are spread over several stores without a transaction, and it re-scans often. |
| Room schema and migrations | 7/10 | Schemas exported, auto-migrations only, no destructive fallback. No migration tests, and missing indexes for growing tables. |
| Backup / restore round-trip | 4.5/10 | Encrypted, verified and rotated well, but many user stores are missing, and part of the snapshot is read from `StateFlow.value`. |
| Call history, archive and retention | 6/10 | Good crypto handling. "Delete history for number" misses calls beyond the 3000-row window, and the whole archive is held in memory. |
| Vault moves and temporary contacts | 7/10 | Thoughtful. Moving a contact into the vault goes ahead with a lossy copy if the full record can't be read. |
| Telecom state machine (multi-call, waiting, conference, races) | 7.5/10 | Main-thread single owner, calls re-checked after every await, bounded timeouts. Minor stale caches. |
| Emergency-call safety | 6/10 | Limits and screening are exempt, but the confirm and SIM prompts apply to 112/911, the app lock has no emergency button, and the window only starts when the call ends. |
| Incoming-call UI (FSI, channels, DND) | 6/10 | Fallback for a revoked FSI permission exists. The Person has no URI (DND starred-contact exceptions, likely broken), and a demoted incoming channel is not detected. |
| Lifecycle and process death | 5/10 | The call path survives a Telecom rebind. The contact editor loses its draft on rotation or theme change. |
| Time handling | 7/10 | Elapsed clock for countdowns, allowances derived from dates, `Locale.ROOT`-safe case handling. Reminder scheduling drifts across DST. |
| Scale and edge cases (10k contacts, 50k calls, work profile, SIM swap) | 5/10 | Folder sync reads everything, full photos included, into memory every hour. Screening ignores work-profile contacts. Full reload on every provider change. |

---

## 2. Findings

Severity: Critical, High, Medium, Low. Effort: S (under a day), M (a few days), L (a week or more).

| id | sev | area | file:line | problem | evidence | recommended fix | effort |
|---|---|---|---|---|---|---|---|
| D1 | High | Call history | `core/data/.../history/CallHistory.kt:401-411, 447`; `CallLogRepository.kt:49, 83-86` | "Delete history for this number" (and `deleteRange`) only deletes system call-log rows among the **3000 newest** calls. Older provider rows survive. With the archive on, only their archive copy is deleted, and the next full sync (every 6 h, `:189-191`) re-archives them, so deleted calls come back. | `callsFor` filters `calls.value` (system log capped at `LIMIT 3000`). Archived entries have ids ≥ `ARCHIVE_ID_BASE` and are excluded from `providerIds` (`:385-388`). `CallLogRepository.deleteForNumber` has the same pattern. | Delete in the provider by number: query `Calls.CONTENT_FILTER_URI` plus `sameExact`, the way `purgeNumber` (`:417-430`) already does. Merge that with the archive rows before trashing. | S |
| D2 | High | Screening / work profile | `CallScreener.kt:220-225`; `ContactsRepository.kt:298-306` vs `:247-254` | "Block non-contacts" (and every rule that tests `isContact`) treats **work-profile contacts as strangers**. Caller lookup uses `PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI`, but `isContact` queries only `PhoneLookup.CONTENT_FILTER_URI`. A colleague's call can be rejected while caller ID would have shown "Work". | Code as cited. | Make `isContact` also try the enterprise URI when `WorkProfile.exists`. Add a regression test with a fake resolver. | S |
| D3 | High | Backup completeness | `backup/BackupRepository.kt:196-209`; `db/AppDatabase.kt:114-150, 202-221`; `calltime/CallingRepository.kt:18-33`; `history/HistoryPrefs.kt:22`; `calls/CallExtrasRepository.kt` | Encrypted backups **leave out user-authored data**: pinned notes, preferred messenger, relation links and last-nudged (the `contact_meta` columns outside the Circle), `call_notes`, `private_calls` (vault call history, which is also removed from the system log, so it is lost entirely), `temporary_contacts` (restored temporaries become permanent), call-time rules / supervision / allowances (`parley_calling`), history prefs (saved filters, data plans), call extras, and user spam packs. The blocked-call log is exported but never restored (`:539-563` ignores `snap.log`). See the store inventory in section 5. | grep shows no export path for these. `BackupExtras` implementors are only People, Circle and Extras (`AppContainer.kt:67`). | Add `BackupExtras` (or archive sections) for `contact_meta` (keyed by name and number like Circle), call notes, private calls (vault section), temporary flags, and `CallingRepository` JSON. Add a test that enumerates every Room entity and pref file against a backed-up allowlist, so a new store can't be forgotten. | M |
| D4 | High | Emergency | `app/.../CallGate.kt:25-41` | **Emergency numbers go through "Confirm before calling" and the SIM chooser.** `confirm = settings.confirmBeforeCall`, and `chooseSim` excludes only USSD service codes. On a dual-SIM phone with no default, dialling 112 first shows a SIM question, and with confirm on, a confirmation. The dial guard is skipped correctly (`DialGuard.kt:34`), but nothing else is. | Code as cited. `PhoneNumbers.isServiceCode` covers `*…#` only. | At the top of `check()`: `if (PhoneEnv.isEmergency(ctx, number)) return null`. In `place()`, skip `outgoingWarning` and the SIM question, and let Telecom pick the account. Add a unit test. | S |
| D5 | High | Lifecycle | `app/.../ui/contact/ContactEditScreen.kt:233-248`; `app/src/main/AndroidManifest.xml` (MainActivity has no `configChanges`) | The contact editor's draft, picked photo, account and labels are held in plain `remember`. **Rotation, dark-mode switch, font or locale change, split-screen resize, or process death while in the photo picker silently discards the edit**, and the editor reloads the original. | 31 `remember {}` calls and 0 `rememberSaveable` in the file. MainActivity declares no `configChanges`. | Keep the draft in a `ViewModel` with `SavedStateHandle` (serialise `ContactDetails` with the existing `ContactDetailsJson`), or `rememberSaveable` with a Saver. Also do this for the vault editor and the QR result sheet. | M |
| T1 | High (likely) | Incoming UI / DND | `telecom/.../CallNotifier.kt:182-186, 251-268` | The incoming CallStyle `Person` has **no URI** (no `tel:` and no contact lookup URI), and there is no `addPerson`. NotificationManager's zen filtering computes contact affinity from the people on the notification. With DND set to "calls from starred / contacts", the full-screen notification is likely intercepted even though Telecom rings for that caller: the phone rings with no answer UI shown. | No `setUri` or `addPerson` anywhere (grep). | `Person.Builder().setUri(contactLookupUri ?: "tel:$number")`, and also `addPerson(...)` on the builder. Verify on device: DND with starred only, call from a starred contact. | S |
| T2 | Medium | Incoming UI | `CallNotifier.kt:36-43, 83`; `app/.../NotificationHealth.kt:64-76` | The fallback direct launch fires only when the FSI permission is revoked or all notifications are off. If the user lowers **the incoming-calls channel** (to silent or blocked), there is no full-screen UI and no fallback, and the health check doesn't flag it. | `canUseFullScreen() \|\| notificationsAllowed()` only. | Also check `nm.getNotificationChannel(CH_INCOMING)?.importance < IMPORTANCE_HIGH` in both places. | S |
| T3 | Medium | Emergency | `app/.../MainActivity.kt:53-54`; `security/AppLock.kt` (LockScreen) | With the app lock on, Parley, the default dialer, **shows no way to place an emergency call** from its own lock screen. The keypad is behind biometric or credential authentication. | The LockScreen composable has only "Unlock". | Add an "Emergency call" button that opens a minimal keypad accepting only numbers for which `isEmergencyNumber` is true, or launches `TelecomManager.createLaunchEmergencyDialerIntent`. | S |
| T4 | Medium | Emergency window | `CallManager.kt:371-373`; `ScreeningGuard.kt:283-290`; `CallManager.kt:165` | The "no screening for 1 h after an emergency call" window **starts only when the emergency call is removed**. So (a) a call-back arriving *during* the emergency call is screened normally, and (b) if the process dies before `onCallRemoved`, the window never starts. It is also stored with `apply()` and based on the wall clock (`System.currentTimeMillis`), so a clock change can end it early. | Code as cited. | Note the start in `add()` for outgoing emergency calls, and extend on removal. In `add()` also treat `details.hasProperty(PROPERTY_EMERGENCY_CALLBACK_MODE / PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)` as exempt. Use `commit()` and store `elapsedRealtime` plus boot count. | S |
| D6 | High (scale) | Folder sync | `sync/FolderSync.kt:168-192` | Every run (hourly periodic, `FolderSyncWorker.kt:45`) reads **every `.vcf` file's bytes and every contact with full-resolution photos** into memory at once (`records.readAll(fullPhoto = true).associateBy`). With 10k contacts and photos this is hundreds of MB, so an OOM or a killed worker is likely. It also costs a lot of battery. | Code as cited. | Stream: hash local records one at a time and cache `localHash` by `(rawId, version)` from `RawContacts.VERSION`. Read file bytes only when the file's `COLUMN_LAST_MODIFIED`/size changed. Use thumbnails for hashing. | M |
| D7 | Medium | Folder sync | `FolderSync.kt:189-196, 235-241` | A **lookup-key change that can't be re-resolved** (`idFor` fails, for example after an account move) looks like "deleted here, unchanged there". The file is deleted and the deletion then spreads to the other devices. The mass-delete guard only trips when there are more than 3 deletions and more than 25% of entries. | Code as cited. | Keep the contact id and raw ids in `Entry` and resolve through `ContactKeys`-style `(id, key)`. Treat "unresolvable but raw contact still exists" as a key change, not a deletion. Require confirmation for any propagated deletion of a contact changed in the last N days. | M |
| D8 | Medium | Journal / undo | `JournalRepository.kt:26-35`; `ContactsRepository.kt:103-113, 830-834` | If a contact's snapshot fails (`records.read` throws or returns null), `mapNotNull` **skips it silently**, and the delete goes ahead **without an undo copy**. Only a thrown callback aborts the delete. | `runCatching { records.read(...) }.getOrNull() ?: return@mapNotNull null` | Return per-id success. In `delete`, abort (or exclude and report) ids without a snapshot. Same for `deleteRaws` (`:968-974`). | S |
| D9 | Low | Journal / undo | `JournalRepository.kt:41-49` | `restore` doesn't check `restored`. A double tap, or a retry after a slow first attempt, creates duplicate contacts. | No `if (e.restored) return` guard. | Guard, and mark restored inside a mutex before inserting (or use a compare-and-set update). | S |
| D10 | Medium | Contacts write | `ContactsRepository.kt:579-590, 728` | **No optimistic concurrency.** Edits are applied by data-row id against the snapshot taken when the editor opened. If a sync adapter changed or deleted rows meanwhile, an update to a vanished id affects 0 rows and the user's change is silently lost, or an older value overwrites a newer synced one. AOSP asserts `RawContacts.VERSION`. | No `newAssertQuery` or expected counts. | Add `ContentProviderOperation.newAssertQuery(rawUri).withValue(RawContacts.VERSION, v)`. On `OperationApplicationException`, reload and show "Changed elsewhere, review". | M |
| D11 | Low | Contacts write | `ContactsRepository.kt:728-745` | Save isn't atomic. The data batch commits, then linking (`setAggregation`, chunked) and the photo write run separately. A photo IO failure throws *after* the data was saved, so the UI reports failure for a save that happened. `join()` in chunks can leave a partial merge (`:952-962`). | Code as cited. | Wrap the photo write in `runCatching` and report "saved, photo failed". Put the aggregation exceptions in the same batch when under the op limit. | S |
| D12 | Medium | Vault | `vault/VaultMoves.kt:41-54` | `moveIn` continues when `records.read(...)` returns null. The vault then keeps only `ContactDetails` (losing IM/SIP, custom rows, extra raw data), and `purgeForVault` deletes the original **without a journal copy** (by design). | `record = records.read(...)?.let{...}` is nullable and passed through. | Treat `record == null` as failure (throw) before any delete. | S |
| D13 | Medium | Backup | `BackupRepository.kt:289, 299, 250`; `BlockRepository.kt:47-49` | Block rules and the vault list are read from `StateFlow.value` whose initial value is `emptyList()`. In a cold worker process (`BackupWorker`) a small address book can finish writing contacts before Room emits, giving a "successful" backup with **zero rules** and a skipped `vaultMissing` warning. | `stateIn(..., SharingStarted.Eagerly, emptyList())`. | Read from the DAO directly (`blocks.allRules()`, `vaultDao.all()`). | S |
| D14 | Medium | Call-time limits | `app/.../calltime/CallTimePlanner.kt:55-70`; `telecom/.../CallClock.kt:103-107` | Allowances (including **supervised mode**) are computed from the system call log. That log is capped at 3000 rows, excludes vault contacts' calls (moved to `private_calls`), and the user or any dialer can clear it. If the log isn't loaded within 1.5 s the plan is empty (fails open). The "a reboot can't reset it" claim holds; "clearing history resets it" does not. | Code as cited. | For supervised rules, keep a small tamper-evident usage ledger (per call: connect time, duration, rule key), include vault calls, and fail closed (warn) when usage can't be read. | M |
| D15 | Low | Metadata re-key | `people/ContactKeys.kt:96-99, 108-131` | `moveLocked` touches `contact_meta`, backgrounds, interactions, extras and temporaries without one transaction. A crash midway leaves duplicate or orphan rows (self-healing on the next sweep). `sweep` writes whole rows it read earlier (`r.copy(contactId=…)`), which can overwrite a pinned note edited by `CircleRepository.editMeta` meanwhile (that path doesn't take the mutex). | Code as cited. | Wrap the Room parts in `db.withTransaction`, and use a targeted `UPDATE contact_meta SET contactId=… WHERE lookupKey=…`. | S |
| D16 | Medium (perf) | Scale | `ContactsRepository.kt:63-90, 118-121`; `CallLogRepository.kt:36-43`; `ContactKeys.kt:103`; `AppContainer.kt:104-114` | Every provider change triggers a **full reload** (`conflate()` only, no debounce): all phones and contacts, or 3000 calls. During a Google sync of 10k contacts this runs back to back. The key sweep (15 s debounce) then stats a background file for **every** contact, and does about 2 provider queries per stored key. | Code as cited. | Debounce 300–500 ms. Build `ContactSummary` incrementally, or at least skip reloads while `ContactsContract.ProviderStatus` is busy. In the sweep, iterate only indexed background keys. | M |
| D17 | Medium (perf) | Journal size | `JournalRepository.kt:28`; `BackupRepository.kt:395-399` | Each journal snapshot stores **full-resolution photos**, and the rows are written one by one. A REPLACE restore or bulk delete of thousands of contacts journals all of them, which is slow and grows `parley.db` by hundreds of MB for 30 days. | `records.read(id, fullPhoto = true)` | Store the photo hash and keep blobs in the time-machine blob store (content-addressed), or keep thumbnails. Batch the inserts in one transaction. | M |
| D18 | Medium | Restore | `BackupRepository.kt:395-399` | A REPLACE restore deletes local contacts **including synced-account copies**, which then delete on the server at the next sync. The safety backup is local only. The UI copy should say so; a per-account opt-out would be safer. | `contacts.delete(ids)` over the whole `plan.toDelete`. | Default REPLACE to phone-only or local contacts, or show the per-account counts that will be deleted server-side. | S |
| D19 | Low | Vault history | `vault/VaultRepository.kt:350-373` | `sweepCallLog` inserts `private_calls` and then deletes provider rows, with no dedupe. If the delete throws a non-`SecurityException`, rows are duplicated on the next run. Only the last 30 days are swept, so older calls of a contact moved into the vault later stay visible in the system log. `delete()` (vault entry) isn't transactional. | Code as cited. | Dedupe key `(vaultId, date, type)` with a unique index. Sweep all dates once at `moveIn`. Wrap in `withTransaction`. | S |
| D20 | Medium | Tests | `core/data` (no `src/test`), `telecom` (no tests) | There are **no tests** for 12.6k + 5.1k lines, including 5 Room auto-migrations (`AppDatabase.kt:525`) and the telecom state machine. | `find -name test` shows only `core/common/src/test`. | Add a `MigrationTestHelper` test per version (1 to 6, using the exported schemas), Robolectric tests for `CallGate`/`CallScreener` emergency and work-profile cases, and a fake-`Call` harness for `CallManager` races. | M |
| D21 | Low | Schema | `db/AppDatabase.kt:48-68, 72-78, 214-221` | `blocked_calls` (queried by `number`/`time`), `call_rings.numberKey` and `call_notes.numberKey` have no indexes. Blocked rows (`allowed = 0`) are never pruned (`pruneAllowed` only removes allowed rows). | Entity definitions. | Add indexes (a new auto-migration), and prune blocked rows older than 1 year. | S |
| D22 | Low | Time | `app/.../work/RemindersWorker.kt:258-266` | The daily reminder hour is set by an initial delay computed from `LocalDateTime.now()` on a 24 h period. Across DST (and with WorkManager's natural drift) digests shift by about 1 h and are not realigned, because the period isn't re-anchored. | Code as cited. | Use a one-time work that re-enqueues itself for the next local `hour:00` in `ZoneId.systemDefault()` on each run. | S |
| D23 | Medium | Data wipe | (no implementation) | There is **no "Delete all Parley data"** even though there are about 25 stores (section 5). Users must use system "Clear storage". Uninstall leaves SAF-folder backups, vCard sync files and Markdown exports behind (expected, but not explained). | grep: no wipe or erase entry point in settings. | Add an in-app wipe that clears Room DBs, DataStores, prefs, `timemachine/`, the call-background dir, Keystore aliases and WorkManager jobs, and lists the external folders it cannot touch. | M |
| T5 | Low/Medium | Screening UX | `CallManager.kt:165-195`; `CallNotifier.kt:68` | Without the call-screening role, a call later rejected by rules rings through Telecom for up to 1.5 s while no UI is shown (the notification waits for screening). | `SCREEN_TIMEOUT_MS = 1500`; `ringing` excludes `isScreening`. | While screening a call that isn't yet allowed, call `silenceRinger()` and play the tone on allow (the custom-ringer machinery already exists), or nudge the user toward the screening role. | S |
| T6 | Low | SIM hot swap | `CallManager.kt:621-634` vs `clear()` `:430-460`; `CallGate.kt:28-30` | `accountNumbers` is never cleared, so a SIM's own number is stale after a swap. A remembered SIM that no longer exists still counts as a choice, so the SIM question is skipped and Telecom falls back to its own default or picker. | Code as cited. | Clear `accountNumbers` in `clear()`. In `CallGate`, ignore remembered ids that `sims.handle()` can't resolve. | S |
| T7 | Low | Proximity | `ProximityController.kt:362-364` | A proximity screen-off is taken when `audio.current == null` (the route isn't known yet), for example on Bluetooth before the first audio callback. | `earpiece = audio.current == null \|\| …` | Require an explicit EARPIECE route. | S |
| T8 | Low | Cold start | `AppTelecomDependencies.kt:291-292` | `startsEmergencyWindow` reads `settings.value`, which is the default (empty list) before DataStore loads, so user-listed "emergency extras" may be missed on a cold-start call. | `c.settings.settings.value` | Use the `loaded` flag with a fallback read, or cache the list in `ScreeningGuard` prefs. | S |
| T9 | Low | Lifecycle | `telecom/.../ui/InCallActivity.kt:77` | The keypad visibility is `remember`, not `rememberSaveable`. It is mostly covered by `configChanges`, but lost if Telecom rebinds after process death. | Code as cited. | `rememberSaveable`. | S |

Checked and fine (no finding):
- No foreground services are needed (the InCallService is bound by Telecom).
- `CallActionReceiver` is not exported.
- Every call in the screening service gets exactly one response.
- `endForLimit` never ends ringing calls, emergency calls, or calls inside the window.
- `CallClock` restarts from `connectTimeMillis` after a rebind, so a limit survives a crash.
- `RingBoost` is restored at app start (`BlockingSetup.kt:23`).
- `HistoryCrypto` key loss moves the archive aside.
- Contact inserts isolate failures per plan (`ContactRecordStore.kt:333-360`).
- `replaceContent` respects `IS_READ_ONLY` rows.
- `Locale.ROOT`: Kotlin `lowercase()`/`uppercase()` are locale-invariant.
- Allowance periods are derived from dates, not counters.

---

## 3. Patterns and root causes

1. **Features add stores, but backup has no registry.** Each feature invents its own persistence: Room tables, about 12 SharedPreferences files, 3 DataStores, and file dirs. Backup is opt-in per feature through `BackupExtras`, so new stores are forgotten by default (D3, D23). *Systemic fix:* a single `PersistentStore` registry (name, location, backup policy, wipe function), plus a unit test that fails when an entity or pref file isn't registered.
2. **"Snapshot of the UI state" is used as "the data".** Destructive or complete operations use flows built for display: the 3000-row call-log window (D1, D14), `StateFlow.value` with an empty default (D13), and the personal-profile-only lookup (D2). *Fix:* separate UI read models from repository queries. Anything destructive, security-relevant or for backup must query the source directly.
3. **Safety exemptions are scattered.** "Is this an emergency call?" is decided in 6 places (`DialGuard`, `CallClock.exempt`, `CallManager.isEmergency`, `CallLimits.isExempt`, `ScreeningGuard`, `AppTelecomDependencies`), and the gate that matters most (`CallGate`) forgot it (D4, T3, T4, T8). *Fix:* one `EmergencyPolicy` object (number check, call properties, window, user extras) used by every gate. Test it as a matrix: emergency × {confirm, SIM, lock, screening, limit}.
4. **Multi-step writes without transactions or version checks.** Examples: the contact save followed by the photo write, meta re-keying across stores, vault delete, the private-call sweep (D10, D11, D15, D19). These are mostly self-healing, but each is a hidden partial-failure mode. *Fix:* `withTransaction` for everything local, and `newAssertQuery(VERSION)` for provider edits.
5. **Whole-dataset loops on hot paths.** Full reloads on every observer tick, and folder sync reading everything with full photos (D6, D16, D17). *Fix:* incremental keys (`RawContacts.VERSION`, `CallLog._ID` high-water marks), debounce, and thumbnails for hashing.
6. **No tests below `core/common`.** Everything in `core/common` is well tested and pure, but the Android glue, where the platform-specific bugs above live, has none (D20).

---

## 4. Prioritised improvement plan

**Quick wins (1–3 days, mostly S):**
1. D4: skip confirm and SIM prompts for emergency numbers. T3: emergency button on LockScreen.
2. T1: `Person.setUri` / `addPerson` on call notifications. T2: incoming-channel importance check in the notifier and the health check.
3. D2: enterprise lookup in `isContact`.
4. D1: delete by number via the provider filter URI (reuse `purgeNumber`'s query).
5. D8, D9, D12: never delete without a journal or vault record, and make undo restore idempotent.
6. D13: read rules and vault from the DAO in backup.
7. T4: start the emergency window when the call is added, and honour the ECBM property.
8. T6, T7, T8, T9, D21: small cache, index and saveable fixes.

**Short term (1–2 weeks):**
1. D5: editor draft in a `ViewModel` with `SavedStateHandle`. Audit other long forms.
2. D3: back up `contact_meta` (notes, messenger, relations), call notes, private calls, temporary flags and `CallingRepository`. Restore the blocked log.
3. D10: version-asserted contact saves with a "changed elsewhere" flow.
4. D14: a usage ledger for supervised allowances, failing closed.
5. D20: Room `MigrationTestHelper` tests (1 to 6) and Robolectric tests for `CallGate`, `CallScreener` and `CallManager`.

**Structural (next release):**
1. A persistent-store registry that drives backup, wipe (D23) and tests.
2. A central `EmergencyPolicy`.
3. Incremental contact and call-log indexing and a streaming folder sync (D6, D7, D16, D17): content-addressed photos, change tokens.
4. Transactions around every multi-store write (D11, D15, D19).

---

## 5. Persistent-store inventory: backup, restore and wipe

"Wipe" means: is there an in-app delete-all? No in-app wipe exists for any store; system "Clear storage" or uninstall removes all app-private stores.

| Store | Location | Backed up | Restored | Notes |
|---|---|---|---|---|
| Contacts (provider) | ContactsContract | Yes (`writeContacts`, full photos) | Yes (merge / replace / enrich) | REPLACE deletes synced copies (D18). |
| System call log | CallLog provider | Yes | Yes (dedupe on number, date, duration, type) | Vault calls are not in it. |
| History archive `archived_calls` | `parley-history` Room | Yes (only rows missing from the provider) | Yes | |
| `keep_forever` | `parley-history` | Yes | Yes | |
| `trashed_calls` (30-day undo) | `parley-history` | No | – | Acceptable. |
| `block_rules` | `parley.db` | Yes (from `StateFlow.value`, D13) | Yes (dedupe; label rules remapped) | Hit counts are not kept. |
| System blocked numbers | BlockedNumberContract | Yes | Yes | |
| `blocked_calls` (blocked log) | `parley.db` | Yes (last 500 blocked) | **No** | Allowed and trace rows are not backed up. |
| `call_rings` | `parley.db` | No | – | Wangiri evidence is lost (minor). |
| `speed_dial` | `parley.db` | Yes | Yes | |
| `number_sim` | `parley.db` | Yes | Yes | Account ids are meaningless on another phone. |
| `journal` | `parley.db` | No (`writeJournal` exists but is unused) | – | Acceptable (local undo). |
| `temporary_contacts` | `parley.db` | **No** | – | Restored temporaries never expire (D3). |
| `contact_meta` | `parley.db` | Partial: reachOutDays, rhythm, yearlyEvents (Circle) | Partial | **pinnedNote, preferredMessenger, relationLinks, lastNudgedAt are not backed up.** |
| `interactions` | `parley.db` | Yes (Circle extras) | Yes (dedupe key) | |
| `vault_contacts` / `vault_numbers` | `parley.db` | Only when the vault is unlocked at backup time | Yes (dedupe on name and numbers) | Scheduled backups normally skip the vault, and rotation protects the last backup that has it. |
| `private_calls` | `parley.db` | **No** | – | Vault call history is lost on migration. |
| `call_notes` | `parley.db` | **No** | – | |
| App settings | DataStore (main) | Yes | Yes | |
| People prefs, private-name approvals, Me card | DataStore `people` + prefs | Yes | Yes | |
| Call backgrounds | files + index | Yes (capped at 6 MB) | Yes (matched by name and number) | |
| ExtrasStore (label policies, DND stars, simple mode, trip) | prefs `parley_extras` | Yes | Yes | |
| Circle config | prefs `parley_circle` | Yes | Yes | Reminder bookkeeping state is not backed up (fine). |
| CallingRepository (limits, allowances, supervision, reminders, USSD) | prefs `parley_calling` | **No** | – | Supervision setup is lost. |
| CallExtrasRepository (proximity, pocket guard, re-alert, ring facts) | prefs | **No** | – | |
| HistoryPrefs (archive on, saved filters, data plans) | DataStore `history` | **No** | – | |
| MessagingStore ("last messaged") | prefs `messaging` | No | – | Probably intentional (privacy). |
| BulkAddStore, UxPrefs, CrashStore, Diagnostics, Provenance, ParleyWriteLog | prefs | No | – | Acceptable. |
| Spam-list packs (incl. user packs) | device-protected `files/lists` | No | – | User-authored packs are lost. |
| TimeMachine | `files/timemachine` (plain JSON, 180 days) | No | – | Local by design. Plaintext contact history kept 180 days (worth disclosing). |
| FolderSync state, BackupPrefs, ScreeningGuard, RingBoost | prefs / files | No | – | By design. |
| Keystore keys (vault, history, interactions) | AndroidKeyStore | No | – | By design. Vault data travels re-encrypted under the archive key. |
| External: backup folder, vCard sync folder, Markdown export | SAF folders | – | – | Survive uninstall. There is no in-app wipe for any store (D23). |
