# Round 3, Track B: Performance, storage and scalability (Parley 6.2.1, HEAD a67fd3d6)

**Method.** This is a read-only static audit of `parley-phone/`. No Gradle run and no device: every timing below is an estimate from the code paths and from these typical Android costs:

| Operation | Typical cost |
|---|---|
| AndroidKeyStore operation (TEE) | 2–5 ms (StrongBox 20–50 ms); the TEE serialises operations |
| Software AES-GCM, small row | 10–30 µs |
| libphonenumber parse, `isPossible` and `format` | 20–60 µs (100 µs on low-end devices) |
| `PhoneLookup` provider query, cold | 5–30 ms |
| Room first open | 20–50 ms |
| SharedPreferences XML | about 2–4 ms per 100 KB to parse or write, plus an fsync |

The release APK was measured from `dist/parley-6.2.1.apk`, with a small dex and ZIP reader in a scratch directory.

Paths are relative to `parley-phone/`. "Round 2" means `docs/audit/round2/PERFORMANCE.md` (F1–F31) and `docs/AUDIT_2.md` (P1–P10, L1–L3).

---

## 0. Summary

**Top findings, ranked:**

| # | Finding | Severity | Effort |
|---|---|---|---|
| B1 | Every process start opens every private contact's caller-ID copy through the Keystore, including the process started for a ringing call. This is the ringtone sweep in `ParleyApp`. It competes with a **1.5 s** fail-open screening budget | High | S |
| B2 | Backups hold **every contact photo in memory**: the writer until it finishes, and the reader (up to a 256 MB cap). A large photo-rich address book either runs out of memory in `BackupWorker` or writes a backup that **can never be restored** | High | M |
| B3 | **No fsync anywhere.** There are 41 write-to-tmp-then-rename sites, including the wrapped key files (`history.keys`, `records.keys`…) and the *only* copy of an archived contact, which is written just before the contact is purged and the deletion synced | High (rare, irreversible) | S |
| B4 | Recall decrypts, gunzips and parses **every snapshot version** each time it activates, and number memory does the same daily. At 10k contacts that is 2–5 s before "Search everything" answers | Med-High | S |
| B5 | The E.164 cache (4,096 entries, cleared all at once when full) **thrashes at 10k contacts**. Every `LineMap` build, Recents regroup and ring-path `lastCallWith` then re-parses with libphonenumber | Med-High | S |
| B6 | One incoming call resolves the same caller **7–9 times**: `PhoneLookup` plus a vault lookup at 1–3 Keystore HMACs each | Medium | S–M |
| B7 | Each call ends in a **burst of whole-document SharedPreferences rewrites**, about 0.5–1 MB re-sealed and fsynced. `QueuedWork` waits on the main thread when the call screen stops | Medium | M |
| B8 | Number memory's archive summary **falls back to a full archive decrypt** whenever any row has left the archive. Once retention starts trimming (after 5 years, or sooner with a shorter setting) that happens **every day** | Medium | S |
| B9 | Cold start: the Contacts list waits for every caller-ID copy to be opened through the Keystore (the "appear all at once" rule in 6.2). That is about 1–2.5 s with 500 private contacts | Medium | M |
| B10 | The baseline profile is the hand-written 5.x one (77 lines). The 6.x call-path and start-up code is **not covered**, and no generated profile is committed | Medium | S |

**APK.** 6,735,391 B (6.42 MiB). Dex is 82% of it, libphonenumber data plus its ZIP headers 13%, and `resources.arsc` 6%. About 0.45 MB can be saved safely (§6).

---

## 1. What is good: keep it

- **The `StartGate` lean process is real now.**
  - Vault listings, the archive reload, number memory's `follow()` and the network-name sweep all wait for `fullStart` (`VaultRepository.kt:218-232,868-880`, `CallHistory.kt:962-978`, `AppContainer.kt:458-466`).
  - P1 is fixed. **B1 is the one leak.**
- **Private calls** use a software key wrapped by the Keystore (`PrivateCallSeal.kt:16-19`), so F3 is fixed. **Number memory** hashes in software (`KeystoreMemoryKeys.kt:16-25`) and updates its archive part incrementally (`NumberMemoryStore.kt:130-139`), so F4 is mostly fixed (see B8).
- **The time machine** is a single version log (`TimeMachine.kt:76-100`) with a cache cap of 150k entries (`:365`), so F1 and F2 are fixed. **The journal** keeps photos once by hash (Room v11 migration, `Migrations.kt:15-50`), so F6 is fixed.
- **Screening runs its lookups in parallel** (`CallScreener.kt:309-323`). Every call-screen extra has its own timeout and fails open (`CallManager.kt:311,426,438`). The Situations look is memory-only and capped at 400 ms (`SituationsController.kt:122-134`).
- **Lists.**
  - Contacts rows use `key`/`contentType` and per-row `derivedStateOf` (`ContactsTab.kt:191-206`).
  - Recents reads network names through one reader per store version (`NetworkNameStore.kt:63-75`, `RecentsViewModel.kt:170-182`) and prefetches place names off the main thread (`:189-200`).
  - The avatar loader decodes with `inSampleSize` into a 16 MB LRU (`core/ui/.../Avatar.kt:45-69`).
- **Background work.**
  - Maintenance is one daily job with `batteryNotLow` (`MaintenanceWorker.kt:99-108`).
  - Folder sync coalesces runs at 20 minutes and waits for good battery (`FolderSyncSchedule.kt:12-26`, `FolderSyncWorker.kt:63-108`), so F14 is fixed.
  - Backups need both battery and storage not low (`BackupWorker.kt:41-44`).
- **Bounded stores.** Case files (100 cases, 300 calls each, 30 references), menu memory (200 numbers), To call (200 items), number advice (500), ring facts and call quality (400 rows / 60 days), network names (1,000 rows / 400 days).
- **APK.** Compressed dex (L1) is shipped, the 86/61 geocoder files are gone (L2), resource names are collapsed and R8 optimises resource shrinking.

---

## 2. Findings

### B1 — The ringtone sweep opens every private contact through the Keystore at every process start, ringing call included (HIGH)

**Evidence**
- `app/.../ParleyApp.kt:83-86`: at every `onCreate`, with no `fullStart` gate, it launches `CallerTunes.sweep(container)` and `regrant`.
- `app/.../ui/contact/CallerTuneUi.kt:133-139`. `sweep` calls:
  - `c.contacts.customRingtones()`, a provider query;
  - `c.vault.ringtonesNow()`, which opens **every** caller-ID copy;
  - only then `prune`, which looks at the `tunes/` folder. The folder is empty for nearly everyone.
- `core/data/.../vault/VaultRepository.kt:1103-1104`: `dao.callerRowsNow().map { summarize(it) … }`. This runs one after another, with none of the parallel openers the listing has (`:236-250`). `summarize` calls `VaultCrypto.openCallerId`, an AndroidKeyStore AES key (`VaultCrypto.kt:321,345-348`): one IPC per private contact.
- Budgets:
  - `ScreeningCoordinator.kt:73`: `SCREEN_TIMEOUT_MS = 1500` when Parley is the phone app;
  - `ParleyCallScreeningService.kt:60`: 3 s.
  - Both **fail open**.
- During those same seconds the call path does its own Keystore work: vault number HMACs (`VaultRepository.kt:925-930`) and the `openCallerId` of the matched entry.

**Impact**
- With 300 private contacts, about 300 serialised TEE operations is roughly 0.6–1.5 s inside the cold ring window. That is enough to push screening past 1.5 s, so **a blocked number rings through**.
- It also runs in every worker, widget and broadcast process, and again at every cold start of the UI, where it races the listing's openers for the same rows.

**Fix**
- Wait for `fullStart.await()`.
- Return at once when `filesDir/tunes` is empty, before reading anything.
- Read ringtones from the `summaries` cache, or from the listing once loaded, never by opening rows.
- `regrant` is cheap (one directory listing) and can stay.

**Effort:** S (about 10 lines). **Test:** a unit test with no tunes asserts no vault call; an assertion in `StartGate` tests.

---

### B2 — Backups hold every contact photo in memory, on writing and on restoring (HIGH)

**Evidence**

Writer:
- `core/common/.../backup/BackupArchive.kt:230`: `private val photos = TreeMap<String, ByteArray>()`.
- `:278`: every contact's photo goes into it (`photos.putIfAbsent(h, b)`).
- `:407-412`: it is emptied only in `finish()`, after every section.
- `BackupRepository.kt:327` writes contacts with `readAll(fullPhoto = true)`, which means Android's display photos (720 px or more, about 80–250 KB each).

Reader:
- `BackupArchive.kt:624-664` keeps every `photos/*.bin` entry in `kept`, against `maxInMemoryTotalBytes = 256 MB` (`:147-148`).
- Past that cap it throws `"Entry … exceeds in-memory limit"` (`:647-649`).
- `photo()` copies again from `kept` (`:534`).

Round 2's F9 fixed restore *planning* (`BackupRepository.kt:610`, hashes only). The archive itself still holds the bytes.

**Impact**
- 10k contacts with 25% photos at 120 KB is about 300 MB.
  - The scheduled `BackupWorker` runs out of memory every day or week, with a "Backup failed" notification and no backup.
  - On a larger heap the backup is written, but **the reader refuses it at the 256 MB cap: an unrestorable backup** that the user believes is good.
- 2–3k photos already reach about 200–300 MB.
- The F9 remainder is also still open: `CallHistory.backupLines()` (`CallHistory.kt:886-893`) builds a list of every archived call plus a HashSet of the whole provider log. That is about 15–30 MB at 50k calls.

**Fix**
- Writer:
  - keep only `hash → rawId` (about 100 B each);
  - after the last section, re-read each photo from the provider by raw id, in sorted hash order, so the ZIP stays deterministic;
  - or spool them to a sealed temp file in `noBackupFilesDir`.
- Reader:
  - during the verify pass, spool photo entries to a sealed temp file, keeping an index `hash → (offset, length)`;
  - `photo(h)` reads one;
  - lower `maxInMemoryTotalBytes` to the JSON sections only.
- Make `backupLines` a `Sequence` streamed into the writer.
- Add a test with 2,000 synthetic 150 KB photos under `-Xmx128m`.

**Effort:** M. Format unchanged.

---

### B3 — No durable writes: 41 write-to-tmp-then-rename sites, none with fsync (HIGH: rare but irreversible)

**Evidence**
- `grep renameTo(` finds 41 sites in 20 files. `grep 'AtomicFile|fd.sync|getFD().sync|channel.force'` finds **none**.
- Wrapped keys:
  - `HistoryCrypto.kt:61-65`: a new key is written to `.tmp` and renamed, with no sync.
  - Data sealed with it lands in Room straight away, and SQLite does fsync.
  - The same class wraps the `records.keys` and `memory.keys` stores (`KeystoreMemoryKeys.kt:23`).
- The only copy of an archived contact:
  - `ArchiveStore.kt:111,224-228` writes the `.rec` and `.card` with no sync;
  - then `purgeForVault` (`:115`) deletes the contact from the provider, which is durable and syncs the deletion to the account.
- Also unsynced:
  - `AppPinStore`, `Concealment`, `PrivateTrash`;
  - vault photos (`VaultRepository`);
  - `ContactListHead`, `SharedLabelState`, `FamilyShieldStore`;
  - the time-machine blobs (`TimeMachine.kt:44-49`) and `NumberMemoryIndex.kt:193-206`.

**Impact**
- On ext4 and f2fs a rename can reach the disk before the data (`auto_da_alloc` covers only some cases). A crash, battery pull or kernel panic a few seconds after the write leaves a zero-length or missing file.
- For `history.keys` or `records.keys` the next start sees an unreadable wrap and `KeyLostException`. Every archived call, sealed note, case file and To call item is **permanently unreadable**.
- For an archived contact, the person is gone from the address book, the cloud and Parley.
- The window is small (seconds after the write), but these writes happen at moments that matter: first run, archiving, PIN setup.

**Fix**
- One helper, `DurableFile.write(file, bytes)`, using `androidx.core.util.AtomicFile`, which syncs before rename. `core-ktx` is already a dependency.
- A detekt rule banning `File.renameTo` outside it.
- In `ArchiveStore.archive`, sync the directory entry before the provider purge.

**Effort:** S (helper plus mechanical replacement), low risk.

---

### B4 — Recall and number memory decode every snapshot version on each use (MEDIUM–HIGH at 10k contacts)

**Evidence**
- `app/.../ui/recall/RecallUi.kt:80-82`: Recall turns on whenever the query is non-blank and either the chip is on or the contact list matched nobody. Typing a number that isn't saved is enough.
- `:102-104`: the `stored` corpus is **reloaded every time `active` flips** (`distinctUntilChanged` on null versus `Privacy`).
- `RecallSources.kt:166-171` calls `TimeMachine.peopleForMemory()`.
- `TimeMachine.kt:336-348`: for **every** span (each version of every contact in 180 days, current ones included) it calls `store.get(hash)`. That is a file read, AES-GCM open, gunzip and a `RecordJson` decode.
- The same call runs in number memory's `snapshots` source whenever the snapshot stamp changes, which is daily if any contact changed (`NumberMemoryStore.kt:124-128`). It runs in `follow()` at UI start when the index is a day old (`:84-88`).
- `NumberMemory.snapshotHints` (`core/common/.../memory/NumberMemory.kt:151-174`) then keys every number with `PhoneIdentity.key`; see B5.

**Impact**
- At 10k contacts there are at least 10k file decodes, at about 0.2–0.5 ms each: **2–5 s** (8–10 s on low-end devices) before Recall shows anything.
- It repeats for each new search session, and drains battery daily.
- Recall also re-reads all `contact_meta` (decrypted), the journal's deleted entries, private trash and every archive card on each activation (`RecallSources.kt:56-71`).

**Fix**
- Memoise `peopleForMemory()` in `TimeMachine` by `memoryStamp`. Keep a `hash → Person` cache (about 100 B per version), so a new day decodes only the new versions.
- Keep Recall's `Stored` for the view model's life, keyed by the stores' stamps: journal stamp, meta version, archive cards, trash stamp.
- Give the snapshot "gone" hints from the number-memory index instead of re-deriving them.

**Effort:** S.

---

### B5 — The E.164 cache is too small and clears all at once, so it thrashes at 10k contacts (MEDIUM–HIGH)

**Evidence**
- `core/common/.../NumberText.kt:31-40,130-131`: a `ConcurrentHashMap` keyed by `region|raw`, `CACHE_SIZE = 4096`, `if (size >= CACHE_SIZE) clear()`.
- Users that each touch more distinct numbers than that:
  - `ContactDirectory.kt:34-38` builds `numberIndex` (`LineMap`) over every phone of every contact, about 15k numbers at 10k contacts, Eager in `AppViewModel.kt:235-236`. `LineMap.putIfAbsent` and `get` call `e164` (`PhoneIdentity.kt:203-214`).
  - Recents regroups the whole window on each keystroke (debounced to 80 ms). It calls `keyOf`, `index[number]` and the archived and vault keys per call, up to about 6k calls (`RecentsViewModel.kt:172-180,262-292`).
  - `frequents` (`AppViewModel.kt:297-306`), `unknownToday`, `unreturnedMissed` and `snapshotHints` do the same.
  - On the ring path, `lastCallWith` scans `history.calls` with `PhoneIdentity.same` (`CallHistory.kt:165-166`, from `AppTelecomDependencies.kt:247-249`).

**Impact**
- Once the working set passes 4,096 numbers, each pass clears the cache several times, so the hit rate falls towards 0.
- A `numberIndex` rebuild is about 15k parses, 0.3–0.9 s on Default.
- A Recents keystroke regroup with more than 4k distinct numbers is 0.3–1 s, so the search lags.
- `lastCallWith` costs up to about 6k parses (0.1–0.3 s) inside the 2 s caller lookup.

**Fix**
- Compute each `ContactSummary` phone's and each `CallEntry`'s line key **once** at load. Add a `line` field filled by `ContactsRepository` and `CallLogRepository`/`CallHistory`.
- Make the cache a real LRU (`LruCache(32_768)`) or a two-generation map, so it never clears wholesale.

**Effort:** S (the cache); S–M (precomputed keys).

---

### B6 — One ringing call resolves the same caller 7–9 times (MEDIUM: ring latency and Keystore contention)

**Evidence.** `contacts.lookup` and `vault.lookup` calls for one incoming call, each independent and none memoised:

| Path | Location | Lookups |
|---|---|---|
| Screening | `CallScreener.kt:309-312` | contacts, vault and archive per number part |
| `callerInfo` | `AppTelecomDependencies.kt:130,168,185` | contact, then vault, then archive |
| `isSavedCaller` | `AppTelecomDependencies.kt:238-241` | called from `CallManager.kt:355`, `:394` (in the car) and post-call |
| "Never calls you" | `NeverCallsYouFacts.kt:72,81` | `lookupAll` and vault |
| Agenda | `AgendaStore.kt:63,68,74` | contacts, vault and archive |
| Number memory | `NumberMemoryStore.kt:51` | vault |
| Call-time plan | `CallTimePlanner.kt:46,76` | contacts and vault |
| Safe words | `AppTelecomDependencies.kt:609-611` | contacts and vault |

- After the call: `onRingFacts`, `onCallQuality` and `onNetworkName` (`:495,582,643-645`) each look up the vault again.
- Each `vault.lookup` does an AndroidKeyStore HMAC for **each** number form, up to 3 (`VaultRepository.kt:928-929`, `VaultCrypto.kt:583-595`).

**Impact**
- An unknown caller costs about 15–25 Keystore IPCs and about 8 provider queries during the ring.
- Warm that is about 50–150 ms. Cold, with B1, it decides whether screening makes 1.5 s.
- It also delays "Never calls you" and the agenda card.

**Fix**
- A `CallerResolution` memo in `AppTelecomDependencies`: number and region map to `(contact?, vault hit?, archived?)`, with a 60 s TTL. Clear it on contact or vault change.
- Inject it into `CallScreener` (its `archivedCaller` hook already exists).
- Cache `VaultCrypto.hmac(input)` results in a small LRU (256 entries). Alternatively derive vault number fingerprints with a software HMAC key wrapped by the Keystore, as `HistoryCrypto` does; that needs a re-key.

**Effort:** S (memo and LRU); M (software HMAC).

---

### B7 — Each call ends in a burst of whole-document SharedPreferences rewrites (MEDIUM: jank and flash wear)

**Evidence**
- `SealedLineStore.kt:88-96,158-169`: every `add`/`update`/`forget` **re-seals every row** (`write(next)` seals each row again) and `putString`s the whole document with `apply()`.
- Stores on this pattern:
  - ring facts, up to 400 rows (`CallExtrasRepository.kt:107-109`);
  - call quality, up to 400 rows;
  - network names, **up to 1,000 rows** (`NetworkNameStore.kt:131-132`).
- At call end, `AppTelecomDependencies.kt:491-499,578-592,639-654` writes all three.
- Also written per call as whole documents:
  - the case file (`CaseFileStore.kt:124-131`);
  - menu memory (`MenuMemoryStore.kt:84`);
  - To call (`ToCallStore.kt:110`).
- `SharedPreferencesImpl` writes the **whole XML file** with fsync. Pending `apply()` writes are awaited on the main thread by `QueuedWork.waitToFinish()` in `ActivityThread.handleStopActivity` and `handleServiceArgs`/`handleStopService`, which is exactly when the call screen and `InCallService` stop.

**Impact**
- A full network-names document is about 1,000 × 300 B = 300 KB. Ring facts and call quality are about 100–150 KB each, and a busy case file 50–500 KB.
- One call can re-seal about 2,000 rows (20–60 ms of CPU) and write 0.5–1 MB with fsyncs. On slow eMMC that is 50–200 ms of main-thread wait when the call screen closes, a known ANR source.
- Worst case for case files: 100 × 300 calls is about 3 MB of JSON, so a 4 MB sealed string in one XML file. It is parsed in full on first access.

**Fix**
- Move SealedLineStore rows into a table in the history database: `(lineKey, kind, startedAt, sealedBlob)` with an index on `(kind, lineKey)`.
  - Insert one row per fact.
  - Prune with `DELETE … WHERE startedAt < ?` and a `LIMIT`-based cap.
  - Format change only; the code already has a migration hook (`merge`).
- Give sealed documents (case files, To call, menus, family safety, situations) one file each through `DurableFile` (B3), written on IO, never through `QueuedWork`.
- Split case files per case (`cases/<id>.sealed`), so a call rewrites only its own case.

**Effort:** M.

---

### B8 — Number memory falls back to a full archive decrypt once retention trims, daily (MEDIUM: battery after years of use)

**Evidence**
- `CallHistory.kt:458-483`: the incremental tally is used only when `count - seenCount == countAfter(seenMax)`, meaning nothing left the archive.
- The daily `applyRetention` (`MaintenanceWorker.kt:182`, `CallHistory.kt:599-607`) deletes the oldest rows once the archive is older than the setting. The check then fails and `scanArchive` decrypts **every** row (`:467`).
- The 5-year default kicks in after 5 years of use; a "1 year" or "90 days" setting kicks in at once.

**Impact:** about 50k AES-GCM opens plus JSON decodes, 3–6 s of CPU, every day in the maintenance run. The first rebuild at UI start (`follow()`) does the same.

**Fix**
- Record the retention cut-off in the tally: rows deleted by date before `cutoff` only subtract.
- Alternatively keep per-number `(count, oldestAt)` and drop numbers whose newest call is past the cut-off.
- Do a full rescan only after a user delete, which is a privacy promise, or weekly.

**Effort:** S.

---

### B9 — Cold-start Contacts list waits for every caller-ID copy to open through the Keystore (MEDIUM)

**Evidence**
- `AppViewModel.kt:255-270`: `everyone` stays null until `vault.listing` is non-null. This is the 6.2 "private contacts appear all at once" rule.
- `VaultRepository.kt:218-250`: the first listing opens every caller-ID copy, 4 openers in parallel, with each open an AndroidKeyStore AES operation (`VaultCrypto.kt:345-348`). TEEs mostly serialise, so the parallelism helps little.
- The cached list head (`PeopleContainer.kt:48`) hides this only for the first screenful.

**Impact:** 500 private contacts is about 1–2.5 s before the full list, the fast-scroll rail and search work, on every cold start.

**Fix**
- Seal a listing cache (summaries without details) with the records key, as `ContactListHead` does. Verify it against the table's `(id, length(callerIdBlob))` stamp and open only changed rows.
- Alternatively move caller-ID copies to a software key wrapped by a non-auth Keystore key, the `PrivateCallSeal` pattern. That needs a security-track sign-off.

**Effort:** M.

---

### B10 — Baseline profile: 6.x code is missing and nothing generated is committed (MEDIUM: start-up and first ring)

**Evidence**
- `app/src/main/baseline-prof.txt` has 77 lines, last changed 2026-09-29 (5.x). `assets/dexopt/baseline.prof` in the APK is 10 KB.
- `app/src/release/generated/baselineProfiles/` is empty, although `baselineProfile { saveInSrc = true }` is set (`app/build.gradle.kts`).
- Not covered: code that runs at **every start or every ring**:

| Area | Classes |
|---|---|
| Vault | `data/vault/**` (vault lookup, `VaultCrypto`, `PrivateCallSeal`) |
| Security | `data/security/**` (`RecordCrypto`, `SealedMetaDao`, `Concealment`) |
| Call data | `data/calls/**` (`SealedLineStore`, network names, `FamilyShieldStore` in `data/sync/shared`) |
| Number memory | `data/memory/**` |
| Archive and Situations | `data/archive/**`, `data/situations/**`, `app/situations/SituationTriggers` |
| Call-path helpers | `app/calls/**` (`NeverCallsYouFacts`, `NetworkNames`, `PrivateCallLogSweep`, `MissedCallNotifier`) |
| Recall | `ui/recall/**`, `common/recall/**` |
| Agenda and case files | `data/circle/AgendaStore`, `data/cases/**` |

**Impact:** these paths run interpreted or JIT-compiled until background dexopt, which is days on some sideloaded and F-Droid installs. That costs an estimated 20–40% more CPU on the first rings and cold starts after each update.

**Fix**
- Add wildcard rules for the packages above.
- Run `:app:generateBaselineProfile` once on an emulator and commit it (L11 is still open).
- Add an incoming-call-with-private-contacts journey, and a Recall journey, to `baselineprofile/`.

**Effort:** S.

---

### B11 — The family shield parses whole shared-label states on the ring path (LOW–MEDIUM)

**Evidence**
- `CallScreener.kt:163` calls `family?.load()` before `gather`, one after the other.
- `FamilyShieldStore.kt:66-75` reads `SharedLabelStateStore.read()` (`SharedLabelState.kt:312-314`). That unseals and parses **every** label's whole state: entries, history, journal, members, stamps. Only `shieldOn` labels' verdicts are used.
- `complete == false` (a state that couldn't be opened, e.g. during a key upgrade) makes **every call** re-read them.

**Impact:** 0 ms with no shared labels. With two or three large shared labels it is tens to a few hundred ms per cold ring, in series before screening's lookups.

**Fix:** keep a small sealed `shield-index` file (title, mode, key, matches) written whenever a state changes (`update()` already exists), and read only that.

**Effort:** S.

---

### B12 — Archived-contact lookup reads every card file on first use, including during a ring (LOW–MEDIUM)

**Evidence**
- `ArchiveStore.kt:65-80`: `load()` opens and decrypts every `*.card` file.
- `lookup()` (`:87-93`) waits for it. It is called by screening (`AppContainer.kt:113`), `callerInfo`, `isSavedCaller`, the agenda and network names.
- There is no limit on archived contacts.

**Impact:** 1,000 archived contacts means about 1,000 file opens and decrypts (0.2–0.5 s) in a cold ring process.

**Fix:** one sealed `index.sealed` holding (id, name, company, numbers), rewritten on archive and unarchive with `DurableFile`. Card files are then read only to open one.

**Effort:** S.

---

### B13 — Situations schedules WorkManager on every process start, including the ring process (LOW)

**Evidence**
- `ParleyApp.kt:133` calls `SituationTriggers.install` on every start.
- `SituationTriggers.kt:140` calls `wm.cancelUniqueWork(WORK)` whenever no Situation has a window, which is most phones.
- That starts WorkManager (its Room database plus scheduler reconciliation) and writes to its database in a cold ring process.

**Impact:** about 30–80 ms of CPU and I/O competing with screening, and a database write on every cold start.

**Fix:** skip the cancel when no job is known to be queued. Keep a pref flag `scheduled`, or check `getWorkInfosForUniqueWork` lazily after `fullStart`.

**Effort:** S.

---

## 3. Incoming-call latency: estimated budget (Parley is the phone app)

| Step (runs in parallel unless noted) | Warm | Cold process | What dominates |
|---|---|---|---|
| Process plus `Application.onCreate` (main thread) | — | 150–300 ms | Class loading (B10), `DataContainer` constructor |
| Start-up IO competing (not awaited) | — | 0.1–1.5 s | **B1** (N Keystore opens), B13, `warmStores` (about 15 prefs files), `NumberInfo.warm` (geocoder file), `appPin.load` (Keystore unwrap), `PrivateCallLogSweep` |
| Screening (1.5 s fail-open) | 40–120 ms | 0.3–1.2 s; worst > 1.5 s | Room open, B11 (in series), `PhoneLookup`, vault HMAC ×3 (B6), archive cards (B12), spam-list mmap |
| `callerInfo` (2 s) | 50–150 ms | 0.2–0.6 s | 6 parallel provider and meta reads; `lastCallWith` (B5) |
| "Never calls you" (1.5 s, organisations only) | 20–80 ms | 0.1–0.4 s | `lookupAll`, labels, `callsFor` through the archive person index (good) |
| Number memory (2 s, unknown only) | 5–20 ms | 0.1–0.4 s | Reads the whole `index.bin` (2–6 MB at 50k calls) once per process, plus vault HMACs |
| Agenda, case file, safe words | 10–50 ms each | 50–200 ms | Repeated lookups (B6) |
| Situations look | < 1 ms | < 5 ms | Memory only (good) |
| Network name write (after the call) | 20–60 ms | same | B7 (1,000-row re-seal) |

**Target after B1, B6, B10 and B11:** cold screening under 400 ms at 10k contacts, 500 private contacts and 1,000 archived; warm under 100 ms. Measure with `IncomingCallBenchmark` plus the existing `Parley.screenCall` trace section.

---

## 4. Lists and search at 10k contacts and 50k calls

- **Contacts (10k).** Good: stable keys, `contentType`, `derivedStateOf` per row, the list head at cold start. What remains is B5 (index builds), B9 (waiting for the vault) and `ContactDetailViewModel.kt:336-337`, which subscribes to `meta.allMeta()`. That decrypts **every** `contact_meta` row on each open and each meta change; `SealedDaos.kt:115` decrypts per subscriber, and `CircleUi.kt:40,60` does it twice. Fix: one shared, decrypted `StateFlow` in the container (S).
- **Recents.** The window is bounded (system log plus 5,000 archived, `CallHistory.kt:986`). Grouping is O(n) per keystroke, which is fine once B5 is fixed. Minor: `Format.shortWhen` and `Format.duration` run in composition without `remember` (`RecentsTab.kt:433,444`).
- **Favourites and the combined view.** `frequents` scans 60 days of calls (`AppViewModel.kt:297-306`), which is fine. B5 applies.
- **Recall.**
  - Each keystroke stops at 50 results per group, and callers are named once per distinct number (`Recall.kt:371-424`). Words-only queries search only the in-memory window, so they stay O(window).
  - Date queries page the archive by date index (`RecallUi.kt:166-181`), which scales.
  - The cost is the corpus **load**: B4.
  - The engine is rebuilt on every `history.calls` emission, which is acceptable.
- **Search (5.3.1 builder).** Prepared docs are fine. No regression was found.

---

## 5. Storage: every store

For SharedPreferences, "Rewrite" means the whole XML file is written with fsync on `apply()`. A corrupt XML file reads as **empty** with no signal, and the next write replaces it.

| Store | Bound / pruning | Write pattern | Durability and corruption | Breaks at 10k contacts / 50k calls / 5 years / 1,000 entries? |
|---|---|---|---|---|
| `parley.db` v11 (15 tables) | journal 30 d; `call_rings` 60 d; `blocked_calls` pruned; `call_usage` pruned; **interactions unbounded** (ALWAYS log mode, `CircleRepository.kt:231`); `private_calls` kept by design; tidy/vacuum daily | Row-level, WAL | SQLite fsync; exported schema, additive migrations plus manual v11 | Fine. Optional: `interactions(time)` and `call_notes(callDate)` indexes, `call_trash(deletedAt)` |
| `history.db` (`archived_calls`, `keep_forever`, `call_trash`) | 5-year default retention; trash 30 d | Row-level; window of 5,000 decrypted | fsync | 50k rows is about 20 MB; fine apart from B8 and the `backupLines` list (B2) |
| Ring facts, call quality, **network names** (SealedLineStore) | 400 / 400 / 1,000 rows; 60 / 60 / 400 d | **Rewrite and re-seal all** | Unreadable rows kept as they are (good); the XML risk above | **B7** |
| `parley_reputation` | 90-day window, rebuilt daily | Rewrite daily | Same | Fine; about 100–300 KB at 50k calls |
| `case_files` | 100 cases × 300 calls × 30 references, plus 500 stopped | **Rewrite the whole document** per call or edit | Sealed; read-fail means no write (good) | 1,000 entries is about 100 KB, fine. Worst case about 4 MB (B7). Split per case |
| `to_call`, `menu_memory`, `family_safety`, `parley_situations*`, `messaging` | 200 / 200 / small / small; messaging pruned by retention (**unbounded with "keep everything"**) | Rewrite | Same | Fine. Cap messaging at 2,000 |
| `parley_number_advice` | 500 per set | Rewrite | Same | Fine |
| `number_memory/index.bin` | Rebuilt; size ≈ entries × number forms (sealed hint duplicated per form, `NumberMemoryIndex.kt:122-125`) | Whole file, tmp+rename, **no fsync** (B3) | Corrupt means rebuild (good) | 2–6 MB at 50k calls and 10k numbers; cached in memory for the process's life. Store each sealed hint once with a form → id table: about −50% |
| `timemachine/` (blobs + `versions.bin`) | 180 days; GC by distinct hash | Per changed contact | No fsync; a damaged log is set aside and recovered (good) | Disk is fine; **B4** CPU |
| `archive/` (`.card` + `.rec` per contact) | **No cap** | Per contact | **No fsync before purge (B3)** | B12 at 1,000 |
| `vault_trash/`, `vault_photos/`, `shared_labels/`, `call_backgrounds/`, `contact_photos/` (originals) | 30 d / per contact / bounded journals / per contact / **no total quota** (originals up to 100 MB each, `OriginalPhoto.kt:17`) | Per file | No fsync | Originals: a total quota (F28 still open) |
| Agenda | Inside pinned notes (`contact_meta`) | Row | fsync | Fine |
| Chapters | Label policy in `parley_extras` / `LabelReferences` | Rewrite (small) | — | Fine |
| DataStore (`settings`, `people`, `history`) | Small | Atomic file plus fsync (DataStore does this right) | Good | Fine (see §6 for size) |
| Backups (SAF) | Rotation | Streamed ZIP | — | **B2** |

**Count:** 51 SharedPreferences files, 25 file stores, 3 DataStores and 2 Room databases (`PersistentStores.kt`).

### Consolidation: recommended

1. **Facts per call** (ring facts, call quality, network names, number advice, the reputation index) go into one `call_facts` table in `history.db`, which is already keyed and sealed with the archive key. This removes B7's re-seal and lets pruning be SQL. M.
2. **Sealed documents** (case files per case, To call, menus, family safety, situations, the shield index, the archive index) go to `noBackupFiles/docs/<name>.sealed` through one `SealedDocStore`, using `DurableFile`, an in-memory copy, and IO writes off `QueuedWork`. Backup sections are unchanged. M.
3. **Bookkeeping flags.** About 22 tiny prefs files become two files:
   - `local_state`: `folder_sync_runs`, `folder_sync_notice`, `temporary_due`, `private_call_sweep`, `parley_migrations`, `record_sealing`, `vault`, `vault_keys`, `markdown_export`, `sync_watch`, `relation_mirrors`, `contact_key_moves`, `parley_writes`, `ux`, `diagnostics`, `account_diagnostics`, `lists_updater`, `dial_widgets`, `favorites_widgets`;
   - `call_path_state`: `parley_screening_guard`, `parley_ring_boost`, `parley_missed_realert`, `rescue_call`.

   Each prefs file costs a loader thread, a resident map and an inode, and `warmStores` parses about 15 of them at every cold start. This saves about 20–40 ms of cold-start IO and simplifies `DataWipe` and `PersistentStores`. S–M. Keep the keys and copy once.

---

## 6. APK size (6.2.1: 6,735,391 B)

**Where the bytes go:**

| Part | Compressed | Raw | Share |
|---|---|---|---|
| `classes.dex` (9,392 classes; 43,801 method refs; 33,981 strings) | 5,280,161 | 10,754,864 | 78% |
| libphonenumber geocoder data (599 files) | 540,176 | 1,070,060 | 8% |
| libphonenumber metadata, alternate formats and short numbers (541 files) | 127,045 | 333,437 | 2% |
| ZIP local and central headers (1,211 entries; 1,141 of them libphonenumber) | about 222,000 | — | 3% |
| `resources.arsc` (stored; about 6,244 strings) | 417,400 | 417,400 | 6% |
| `res/`, manifest, `baseline.prof`, licences | about 50,000 | — | < 1% |
| `libandroidx.graphics.path.so` × 4 ABIs | 37,392 | 37,392 | 0.6% |

**Growth:** 6.0.0 = 6,485,778 → 6.1.0 = 6,593,518 → 6.2.0 = 6,718,699 → 6.2.1 = 6,735,391. That is about +83 KB per feature release, almost all dex.

**Safe reductions:**

| # | Change | Saves (measured or estimated) | Risk / effort |
|---|---|---|---|
| A1 | Pack libphonenumber **metadata** into one DEFLATE asset and load it through `PhoneNumberUtil.createInstance(MetadataLoader)`, a supported API | **about 165 KB** (measured: 235 KB with headers → 70 KB solid) | Low / S–M |
| A2 | Same for the **geocoder** files, with a small prefix-file reader (`PhonePrefixMap.readExternal`, `MappingFileProvider` are public) | about 136 KB (646 → 510 KB); with xz, about 300 KB more, but that needs a decoder | Medium / M |
| A3 | Move the last 3 DataStores to SharedPreferences or `SealedDocStore` (73 kept `androidx.datastore` classes plus shaded protobuf-lite) | about 60–90 KB compressed (est.) | Low / M (migration) |
| A4 | Merge the 742 duplicate strings and delete dead ones (round-2 L10, open) | about 25–40 KB of arsc | Low / S |
| A5 | `abiFilters` arm-only in a `release` variant, keeping x86_64 for the benchmark build | 20 KB | Low / S |

Not recommended: dropping more geocoder countries (it costs users their area names), or `-repackageclasses` tweaks (already flat).

---

## 7. Background work

| Job | Trigger | Constraints | Cost and notes |
|---|---|---|---|
| `MaintenanceWorker` | Daily | battery not low | **Heavy:** key sweep, full archive catch-up, retention (B8), snapshot (`readAll` of every contact, about 5–15 s at 10k), number memory (B4/B8), reputation (all calls), spam lists. Fine once a day, but at least 10–30 s of CPU at 10k / 50k |
| `FolderSyncWorker` | Every 4 h (1 h with shared labels), content trigger, at start | battery not low, 20-min coalescing | Good |
| `HistoryWorker` | 30 s after each call | none | Duplicates the full app's own `cr.changes` sync (`CallHistory.kt:973-975`) when the UI process is alive. Skip when `fullStart.isOpen` (S) |
| `RemindersWorker` | Daily at the chosen hour | none | `UPDATE` re-enqueued at every full start (`RemindersWorker.kt:249-252`). Harmless; keep |
| `BackupWorker` | 1 or 7 days | battery and storage not low | **B2** |
| `SituationWorker` | At window edges | none | B13 |
| `ToCallWorker`, `WidgetLockWorker`, `SpamListWorker`, `UserJobWorker`, `FollowUpWorker` (legacy, kept by rule) | One-off | — | Fine |

No duplicated periodic jobs: the old daily jobs are cancelled (`MaintenanceWorker.kt:97-101`). Estimated battery: under 0.5% a day at 10k / 50k, dominated by maintenance and hourly folder sync with shared labels.

---

## 8. Memory

| Holder | Bound | Note |
|---|---|---|
| `PhotoCache` 16 MB + `OriginalBitmaps` 24 MB + `ReachViaApps` (32 icons) | Fixed LRU | **No `onTrimMemory`** anywhere (`grep ComponentCallbacks2` finds none). On a 192 MB `memoryClass` device that is 21% of the heap. Size by `memoryClass / 8` and evict on `TRIM_MEMORY_UI_HIDDEN` (S) |
| `TimeMachine.cached` | ≤ 150k entries, about 15–25 MB | Kept for the process's life after the daily snapshot. Drop it after maintenance, or hold it with a `SoftReference` (S) |
| `NumberMemoryIndex.cache` | Index size (2–6 MB) | Process life. Fine (sealed bytes only) |
| `NumberInfo.cache` (place names), `RecentsViewModel.lines` | **Unbounded** | Grows with distinct numbers seen; a few MB over years. Cap with an LRU (S) |
| Archive window | 5,000 decrypted calls (about 2–3 MB) | Plaintext by design; fine |
| Vault `openedMain` | Time-limited, forgotten at screen-off (`ParleyApp.kt:102-111`) | Good |
| Recall corpus | While active | B4 |
| Backup reader and writer | **Up to 256 MB+** | B2 |
| `PeopleBackupExtras.exportOriginals` | Reads a whole original (up to 100 MB) **before** checking the 4 MB budget (`PeopleContainer.kt:173-175`) | Check the file length first (S) |

---

## 9. Still open from round 2

- F9 (archive lines list): see B2.
- F28 (originals have no total quota; the per-photo cap was raised to 100 MB).
- L3 (DataStores): A3.
- L10 (duplicate strings): A4.
- L11 (generated baseline profile): B10.

Everything else in P1–P10 was verified as fixed.

---

## 10. Nits

- `NumberMemoryIndex` seals each hint once but stores it once **per number form** (`:122-125`). Index it by form, then by row id.
- The `notes` source has `fingerprint() = null` (`NumberMemoryStore.kt:148`), so every rebuild decrypts all meta, interactions and call notes. A stamp from Room's invalidation would do.
- `NumberMemoryStore.relationsStamp()` and `ownerNames()` each call `allMetaNow()`; one rebuild decrypts `contact_meta` 3 times (`:106,170-189`).
- `FamilyShieldStore.load()` runs one after another before `gather`. Start it inside `gather`'s `coroutineScope`.
- `ConcurrentHashMap` caches in `object NumberInfo` / `NumberFacts` (`NumberFacts` clears at 500, which is too small for a list pass).
- `SealedLineStore.rows(now)` filters the whole list on every `forNumber`. Cache a `byKey` map alongside `cache`.
- `CallHistory.lastCallWith` is a linear scan. Use `history.index` when it is built.

---

## 11. Prioritized plan

| Order | Item | Why first | Effort |
|---|---|---|---|
| 1 | **B1**: gate the ringtone sweep behind `fullStart`, return early with no tunes, no vault opens | Spam rings through on cold start; 10 lines | S |
| 2 | **B3**: `DurableFile` with `AtomicFile`, replace the 41 renames (keys and archive first), detekt ban | Irreversible data loss; mechanical | S |
| 3 | **B2**: stream backup photos (writer re-reads by raw id; reader spools sealed photos to disk); stream `backupLines` | Unrestorable backups at scale | M |
| 4 | **B10**: profile rules for 6.x packages, a committed generated profile, ring-with-private and Recall journeys | Cheap win on every start and ring | S |
| 5 | **B6 + B11 + B12**: per-call `CallerResolution` memo, vault HMAC LRU, shield index file, archive index file | Ring path under 400 ms cold | S–M |
| 6 | **B5**: LRU E.164 cache (32k); precomputed line keys on `ContactSummary` / `CallEntry` | 10k-contact jank everywhere | S → M |
| 7 | **B4 + B8**: memoise `peopleForMemory` by hash; keep the Recall corpus by stamps; retention-aware tally | Recall in under 300 ms; daily battery | S |
| 8 | **B7 + consolidation 1–3**: `call_facts` table, `SealedDocStore`, two bookkeeping prefs files | Post-call ANR risk; fewer stores | M |
| 9 | **B9**: sealed vault-listing cache | Cold Contacts with many private contacts | M |
| 10 | **A1 → A4**: libphonenumber metadata pack, strings, DataStore → prefs, then geocoder pack | about 0.25–0.45 MB | S–M |
| 11 | Memory: trim hooks and memory-class-sized caches; drop the time-machine cache after maintenance | Low-RAM devices | S |

**Measurement gate** (the existing macrobenchmarks on an emulator plus one low-end arm64 device), seeded with 10k contacts, 500 private, 1,000 archived and 50k archived calls:

| Measure | Target |
|---|---|
| Cold screening (`Parley.screenCall` trace) | < 400 ms |
| Cold start to full Contacts list | < 1 s |
| Recents search keystroke | < 50 ms |
| Recall first result | < 300 ms |
| Automatic backup with 3k photos | completes under a 256 MB heap |
| Daily maintenance CPU | < 15 s |

`BenchmarkData.SIZE` is 3,000 today (`baselineprofile/.../BenchmarkData.kt:16`). Raise it to 10,000 and add private and archived seeds.
