# Track B — Performance, storage & scalability audit: Parley 5.3.0 (HEAD e085d27)

Scope: `parley-phone/` (app, telecom, core/common, core/data, core/ui). This was a read-only static audit. No build was run and nothing was profiled, so every timing below is an estimate from code paths and typical Android costs. The estimates assume these rough per-operation costs:
- Keystore op through IPC: about 1–3 ms.
- Software AES-GCM on a small row: about 10–50 µs.
- `PhoneNumberUtils.formatNumber`: about 50–200 µs.
- Regex NFD normalize: about 2–5 µs.

Paths are relative to `parley-phone/`.

**What is already done well (no change needed).** The `StartGate` lean-process design is sound. `ContactsRepository` reloads incrementally using `CONTACT_LAST_UPDATED_TIMESTAMP`. The keypad search narrows candidates as you type and caches its encodings. Spam lists use an mmap index with binary search. The archive and records keys are software keys wrapped by Keystore (`HistoryCrypto`, `RecordCrypto`). Recents loads a preview page first. LazyColumn keys and contentType are set. `warmDispatcher` is limited to parallelism 2. The flip and proximity sensors live only while a call rings or is live, and wakelocks have timeouts. Both Room databases have `exportSchema=true` and additive AutoMigrations 1→8, with no destructive fallback.

---

## Findings

### F1 — Time-machine indexes: every snapshot index is fully parsed on every access, causing out-of-memory crashes and hundreds of MB on disk (CRITICAL)
**Evidence**
- `core/data/.../backup/TimeMachine.kt:84-87`: `snapshots()` reads and JSON-parses **every** `*.idx` file. Each file is a full `lookupKey → sha256` map of the whole address book, stored as plain JSON (`core/common/.../backup/Snapshots.kt:30-31`). The format has no delta and no compression.
- `snapshots()` is called by:
  - `snapshotIfDue` (`:91`), and again through `prune` (`:121`).
  - `memoryStamp()` (`:196`). This only needs the timestamps, which are already in the file names. It runs on every number-memory rebuild, including after each journaled edit (`NumberMemoryStore.kt:80-83,121`).
  - `oldestSnapshot` (`:219`), `UndoStorage.kt:46`, `SyncWatch.kt:63`, `history()` and `lastVersions()`.
- `KEEP_DAYS = 180` (`:222`). A new full index is written on any day where at least one contact changed (`:94-97` deduplicates only fully identical days).

**Impact**
- Disk: about 150 B per contact per day. That is about 135 MB at 5k contacts and about 540 MB at 20k contacts once 180 days have accumulated.
- Memory: parsing all indexes costs about 250 B per map entry. That is about 225 MB at 5k contacts and about 900 MB at 20k. The manifest has no `largeHeap`, so this is an out-of-memory crash. It would hit `MaintenanceWorker` daily, number-memory rebuilds, and the History & undo screen.
- At 1k contacts it is survivable (about 45 MB).

**Fix**
- Derive timestamps from file names; never parse an index just to list snapshots.
- Store each snapshot as a delta against the previous one (changed keys plus tombstones), or as gzip'd binary. Materialize only the snapshots a caller actually needs.
- Make `memoryStamp` read file names only.

**Effort / risk:** M / M (new index format plus a one-time conversion).

### F2 — Time-machine garbage collection decodes every record of every kept snapshot (HIGH)
**Evidence:** `TimeMachine.kt:152-160`. `collectGarbage` iterates `keep.forEach { idx -> idx.contacts.values.forEach { Snapshots.load(store,h) } }` with no deduplication by hash. Each load is a decrypt, gunzip and JSON parse. After day 180, `prune` (`:120-127`) drops one index **every day**, which triggers this.

**Impact:** About 180 × N record decodes per day. That is 0.9M at 5k contacts and 3.6M at 20k, which means minutes to hours of CPU inside a worker with only `batteryNotLow` as a constraint.

**Fix:** Collect `distinct()` hashes first and load each blob once. Better, record photo hashes in the index or in a side table so no record has to be loaded.

**Effort / risk:** S / L.

### F3 — Private-call list re-decrypted through the Keystore on every change, started even in call and worker processes, and never pruned (HIGH: call latency and battery)
**Evidence**
- `core/data/.../vault/VaultRepository.kt:183-186`: `privateCalls` uses `SharingStarted.Eagerly` and maps `list.mapNotNull { it.opened() }`.
- `opened()` (`:117-120`) calls `VaultCrypto.openCallerId` (`VaultCrypto.kt:315`). That key is an AndroidKeyStore AES key (`simpleKey`, `:277-282`), so **every row is a Keystore IPC**. Unlike vault summaries (`:192-197`), private calls have no cache.
- `vault` is built unconditionally at process start: `ParleyApp.kt:101` calls `warmStores()`, which touches `vault` (`AppContainer.kt:320`). That includes processes started for an incoming call, a worker or a widget. This contradicts the `StartGate` intent.
- `callerRows` is collected twice (`:178` and `:781`).
- No retention exists for `private_calls`. There is no delete-by-date query in `AppDatabase.kt`, and `sweepCallLog` only adds.
- `privateCallsOf` and `privateCallCount` (`:1004-1009`) load the entire table, blobs included, once per vault entry, so the backup is O(V × P). There is no index on `vaultId`.

**Impact**
- With 2k private calls, each table change costs about 2–6 s of Keystore time.
- In a process cold-started for a ringing call, this runs at the same time as screening's own Keystore work: `vault.lookup` does an HMAC per number form plus `openCallerId`. Screening has a 3 s budget (`ParleyCallScreeningService.kt:58`) and fails open on timeout. TEEs often serialize operations.

**Fix**
- Gate `contacts`, `privateCalls` and the second collector on `fullStart.sharing`, and keep only `VaultCallChoices` warm.
- Cache opened calls by id (like `summaries`), or seal private-call rows with a software subkey wrapped by Keystore (the `HistoryCrypto` pattern).
- Add `Index(vaultId)` and `Index(vaultId,date,type)`.
- Add a retention option.

**Effort / risk:** M / M.

### F4 — Number-memory rebuild does about 2–3 Keystore IPCs per distinct number and a full archive decrypt, daily and after each edit (HIGH: battery)
**Evidence**
- `core/data/.../memory/KeystoreMemoryKeys.kt:24-27`: `key()` is an AndroidKeyStore HMAC, and each `doFinal` is an IPC.
- `NumberMemoryIndex.kt:92-99`: `freshPart` hashes every `VaultNumberKeys.stored` form of every entry. The `hashes` cache is per rebuild only.
- The archive source fingerprint changes with every archived call (`CallHistory.kt:417-419`), so it is re-read daily (`MaintenanceWorker.kt:61`). That re-read is a full decrypt scan (`CallHistory.kt:422-426`, see F5).
- The notes source has `fingerprint() = null`, so it is always re-read and re-hashed (`NumberMemoryStore.kt:135`).
- Rebuilds also run after each journaled change (`NumberMemoryStore.kt:80-83`).

**Impact:** With 100k calls (about 10k distinct numbers): about 25k Keystore IPCs (about 25–75 s), plus about 100k AES-GCM decrypts and JSON decodes, plus F1's parse of all indexes, every day.

**Fix**
- Use a software HMAC key wrapped by a Keystore key (as `HistoryCrypto.mac` already does).
- Make the archive part incremental using an `archivedAt` or `id` watermark.
- Give the notes source a fingerprint (row count plus max update time).

**Effort / risk:** M / L–M (a key change means one full re-index).

### F5 — Call archive: unbounded by default, OFFSET pagination, and full decrypt scans for single-number operations (HIGH)
**Evidence**
- The archive is on by default (`HistoryPrefs.kt:26`) with `callLogRetentionDays = 0`, meaning keep forever (`Settings.kt:62`).
- `HistoryDao.page` uses `LIMIT :limit OFFSET :offset` (`history/HistoryDatabase.kt:68-69`), driven by `scanPages` (`CallHistory.kt:400-406`). That is O(n²/500) row steps.
- Full scans that decrypt every row:
  - `callsFor` (`:557-570`, used by `RangeDeleteDialog.kt:56` and `deleteRange`).
  - `purgeNumber` (`:577-603`).
  - `pastCallsForMemory` (`:422`).
  - `archivedNumbers` (`:429`).
  - `backupLines` (`:750-760`, plus `readProvider(null)`).
  - `planImport` (`:658-660`).
  - `purgeVault` whenever the set of private numbers changes (`:355-371`).
- `personKey` is indexed (`HistoryDatabase.kt:22`), but per-number paths do not query by it.

**Impact**
- Size: about 450–600 B per row including indexes, so 100k calls is about 45–60 MB in `parley-history.db`, growing forever.
- Each scan at 100k rows: about 200 pages, about 10M B-tree steps, and 100k AES-GCM and JSON decodes. That is several seconds to tens of seconds. It happens on the UI path "Delete calls with this number" and in daily work.

**Fix**
- Keyset pagination: `WHERE date < ? OR (date = ? AND id < ?) ORDER BY date DESC, id DESC LIMIT 500`.
- For per-number operations, query `WHERE personKey IN (:macs)` (current key plus legacy key) and decrypt only those rows.
- Offer a default retention (for example 3 years) or a storage notice.

**Effort / risk:** S–M / L.

### F6 — Journal stores a full-photo copy per contact, and `restored` sits after the blob, so list queries read every payload (HIGH: storage and I/O)
**Evidence**
- `JournalRepository.kt:34-44`: `records.read(id, fullPhoto = true)`, then photos base64-encoded inside JSON, then gzip (which does not shrink JPEG). One row per contact per action, written sequentially before the operation (`ContactsRepository.kt:1098`).
- Schema (`schemas/.../AppDatabase/8.json`, journal) column order: `…, time, payload BLOB, restored`. `JournalRow` queries select `restored` (`AppDatabase.kt:293`), so SQLite must walk each payload's overflow chain to reach it. This query backs the History & undo list, and `memoryStamp`/`recent()` (`JournalRepository.kt:48-52`).
- Missing indexes: `journal(time)` and `journal(contactKey)` (used at `AppDatabase.kt:296,308`).
- Daily `pruneJournal` never vacuums. Compaction runs only on a manual clear (`UndoStorage.kt:53`).

**Impact**
- Deleting or editing 5k contacts in bulk takes about 5k × (4+ provider queries + photo read) before anything happens, and writes about 0.5–1.5 GB into `parley.db` that is held for 30 days.
- The file never shrinks.
- Listing the journal reads the whole journal.

**Fix**
- Store photos content-addressed, either in the time-machine `FileBlobStore` or by hash reference, with the journal holding only the hash.
- Put payloads in a separate `journal_payload` table, or rebuild the table with `restored` before `payload` (manual migration v9).
- Add the indexes.
- Batch journaling in one transaction.
- Use `auto_vacuum=INCREMENTAL` (see F25).

**Effort / risk:** M / M.

### F7 — Contacts-tab search re-normalizes every field of every contact on each keystroke (HIGH: UI jank at 10k+ contacts)
**Evidence**
- `app/.../ui/people/PeopleUi.kt:76-97` calls `BroadSearch.match` for every contact (`core/common/.../people/BroadSearch.kt:45-64`). Per contact, that:
  - normalizes the query twice;
  - normalizes the name with Regex plus NFD (`TextSearch.kt:9-10,39-43`);
  - normalizes each e-mail, nickname, company, address, note, website, handle and custom field.
- With `preferNickname`, the full list is re-sorted with `Collator.compare` on every keystroke (`:92-95`).
- `secondLines` then re-runs `SecondLines.compute` over the whole filtered list (`:113-115`, `SecondLine.kt:59-61`). That normalizes every name again, and in NUMBER mode calls `PhoneNumberUtils.formatNumber` for every contact.
- `TextSearchIndex` (pre-folded rows) exists and is used by the keypad, but not here.

**Impact:** At 20k contacts × about 10 fields, about 200k normalizations per keystroke, roughly 0.3–1 s on mid-range phones. The first keystroke and clearing the search are the worst cases (full list). NUMBER second-line adds about 1–4 s.

**Fix**
- Pre-fold names, digits and a joined extra-text string once per index change, inside `PeopleIndex` or `PeopleUi`.
- Narrow from the previous result set when the query extends the previous one.
- Sort with precomputed `CollationKey`s.
- Format second lines lazily per visible row, or cache them per contact list.

**Effort / risk:** M / L.

### F8 — Deleting more than 500 contacts at once fails (provider batch limit) and is slow (HIGH: correctness at scale)
**Evidence**
- `ContactsRepository.kt:1097-1101` (`delete`) and `:1092-1095` (`deleteUnjournaled`) send one `applyBatch` with all operations, no chunking and no yield points. ContactsProvider rejects more than 500 operations between yield points; the code's own `Batches.MAX_OPS` comment notes this (`core/common/.../ContactText.kt:48-52`), and other call sites do use `Batches.chunks`.
- Before the delete, `AppViewModel.kt:404-409` runs `lookupKeyOf` and `relationMirrors.takeBack` sequentially per contact.
- The whole operation runs in `viewModelScope`, so it can be cancelled after journaling but before deleting.

**Impact:** Selecting all and deleting fails ("couldn't delete") once the selection exceeds about 500, after spending minutes journaling (F6).

**Fix:** Use `Batches.chunks` with `withYieldAllowed(true)`, run the operation in `c.scope` with `NonCancellable`, and journal in one transaction.

**Effort / risk:** S / L.

### F9 — Restore planning and the archive part of backup hold everything in memory (HIGH: out of memory on large books)
**Evidence**
- `BackupRepository.kt:591-595`: `records.readAll(fullPhoto=false).toList()` (thumbnails included) together with `opened.reader.contacts { it.toList() }`. The backup was written with `fullPhoto = true` (`:315`).
- `:648`: archive lines collected with `toList()`.
- `CallHistory.backupLines` (`:750-760`) builds a list of every archived line (100k objects) plus a HashSet of the whole provider log.

**Impact:** 20k contacts with about 30% full-resolution photos (about 100–300 KB each) puts hundreds of MB on the heap during planning, which means out of memory. Archive lines add about 30–50 MB.

**Fix**
- Plan from lightweight projections (key, name, phones, row hashes, photo hash) and stream photo bytes only when applying.
- Stream archive lines into the writer as a `Sequence`.

**Effort / risk:** M–L / M.

### F10 — PeopleIndex rescans all data rows on every contacts change and stays active forever (MEDIUM–HIGH: battery)
**Evidence**
- `core/data/.../people/PeopleIndex.kt:67-70,87-160`: every `contacts` emission re-queries Groups, RawContacts and `Data` across 11 mimetypes for **all** contacts, and keeps every note and address in RAM. This ignores the incremental diff that `ContactsRepository` already computes (`:209-247`).
- `StartGate.sharing` "never stops" (`StartGate.kt:47-53`), so after the UI has been opened once, every sync-adapter touch while the app is in the background triggers:
  - this full scan;
  - directory re-sort (`ContactDirectory.kt:26-31`);
  - T9 re-encode of all contacts (`KeypadViewModel.kt:69`) while subscribed;
  - `TextSearchIndex` rebuild;
  - a full `CallLogIndex.build` (`CallHistory.kt:164-178`, because the contact-list identity changed);
  - `PeopleUi` pipelines;
  - `ContactKeys.sweep` 15 s later (`AppContainer.kt:294-305`; see F18).

**Impact:** At 20k contacts, each change costs about 1–3 s of provider I/O plus about 0.5–1 s of CPU, repeated with chatty accounts.

**Fix**
- Make `PeopleIndex` incremental using the changed id set.
- Use `WhileSubscribed` for UI-only derivations.
- Stop following contacts while no activity has been started for N minutes, and resync on resume.

**Effort / risk:** M–L / M.

### F11 — `vault_contacts` stores `expiresAt` and `createdAt` after the large `detailBlob`, so caller-ID listings read every detail blob (MEDIUM–HIGH)
**Evidence**
- Schema 8 column order: `(id, callerIdBlob, detailBlob, expiresAt, createdAt)`.
- `VaultCallerRow` queries (`AppDatabase.kt:517-523`) exist to "leave details in the database" (comment at `:203-206`). However, `expiresAt` and `createdAt` sit after `detailBlob`, which is "up to hundreds of kB" with a photo, so SQLite still walks its overflow pages for every row.
- The call path's `vault.lookup` uses `dao.get(id)`, which is `SELECT *` (`VaultRepository.kt:835`).
- The listing runs through two collectors (`:178`, `:781`), and through `summariesNow`, `allNumbers` and `ringtonesNow`.

**Impact:** With hundreds of private contacts with photos, each listing or invalidation reads tens of MB. `lookup` reads the whole detail blob while the phone rings.

**Fix**
- Move details into a separate `vault_details(id PK, blob)` table (manual migration, test with `MigrationTestHelper`).
- Make `lookup` use the caller-row query.

**Effort / risk:** M / M.

### F12 — Call-log observer has no debounce, and each wave re-derives up to 8k calls in about 10 pipelines (MEDIUM)
**Evidence**
- `CallLogRepository.kt:40-47` reloads 3,000 rows on every notification. Contacts get a 750 ms debounce (`ContactsRepository.kt:169`); calls get none.
- A single call end produces several notifications: insert, cached-name update, mark-read, and Parley's own deletes and private sweep.
- `CallHistory`'s second observer (`:215`) syncs, then `reload()`, then emits `_archive` again, so there is a second wave.
- Each wave recomputes:
  - the `HistoryMerge` window (5,000 entries through the vault `LineSet` E.164 check, `:144-155`);
  - `callsWithPrivate` sort;
  - `CallLogIndex`;
  - Recents `group()`;
  - `frequents`;
  - `missedCount`, on the **main thread** because there is no `flowOn` (`AppViewModel.kt:276-277`);
  - `unreturnedMissed`, `unknownToday`, `callCounts`, keypad `results`, `planUsage`.
- Each pipeline recomputes `PhoneIdentity.key` per call.

**Impact:** About 3–5 waves × about 10 × 8k E.164 conversions after every call: 100–500 ms CPU bursts, including in the background.

**Fix**
- Apply `debounceAfterFirst(500)` and `distinctUntilChanged` (compare by ids and dates) to `calls`.
- Attach one precomputed line key per `CallEntry` list (a shared `Map<String,String>` cache keyed by number).
- Add `flowOn(Default)` to `missedCount`.

**Effort / risk:** S / L.

### F13 — Every call writes and reloads the archive; dedupe keys are held in memory (MEDIUM)
**Evidence**
- `HistoryWorker.checkSoon` runs after every call (`HistoryWorker.kt:47-53`) and calls `history.sync(false)`.
- In a fresh worker process, `sync` first runs `reload()`, which decrypts the newest 5,000 rows (`CallHistory.kt:290-293,229-247`).
- `keys()` loads **all** dedupe keys into a HashSet (`:286`). That set stays resident afterwards: about 150 B × 100k ≈ 15 MB.
- The insert already uses `IGNORE` on a unique `dedupeKey` index (`HistoryDatabase.kt:22,86-87`), so the set is redundant.

**Impact:** About 0.5–1.5 s of CPU and 15–20 MB of heap per call, plus battery.

**Fix**
- Drop `knownKeys` and rely on `insert` returning `-1`. Alternatively check `dedupeKey IN (:chunk)`.
- Skip `reload()` when nothing observes `_archive`, for example in a worker where the gate is closed.

**Effort / risk:** S / L.

### F14 — Folder sync runs hourly with no constraints, plus a contact-change trigger (MEDIUM: battery)
**Evidence**
- `app/.../work/FolderSyncWorker.kt:85`: `PeriodicWorkRequestBuilder(1, HOURS)` with no constraints.
- The content-URI trigger re-arms after every run (`:39,89-98`).
- Each run:
  - lists the Storage Access Framework (SAF) folder, one file per contact (`FolderSync.kt:561`);
  - runs `heads()`, two full provider queries (`ContactRecordStore.kt:147-164`);
  - parses and rewrites `folder_sync_state.json`, which has one entry per contact (`FolderSync.kt:381-410`);
  - then syncs shared labels and the Markdown export.
- By contrast, `MaintenanceWorker` and `BackupWorker` set `batteryNotLow`.

**Impact:** At 20k contacts, 24 runs a day, each listing 20k SAF documents (slow on cloud providers) and handling about 3 MB of JSON. More runs with chatty accounts.

**Fix**
- Add `setRequiresBatteryNotLow(true)`.
- Lengthen the period to 3–6 h when no shared labels are active.
- Skip writing state when nothing changed.
- Keep sync state in Room.

**Effort / risk:** S / L.

### F15 — Call screening does its lookups one after another, with duplicate PhoneLookup and vault lookups (MEDIUM: incoming-call latency)
**Evidence**
- `core/data/.../CallScreener.kt:259-292` runs, in sequence:
  - `contacts.isContact` (1–2 PhoneLookup queries);
  - `vault.lookup` (Keystore HMAC per form plus `dao.get`);
  - `contactDetails` (**the same PhoneLookup again**);
  - `privateCaller` (**`vault.lookup` again**);
  - `labelTitlesOrNull`;
  - `lists.lookup`;
  - `callLog.pastCalls` (provider);
  - `blocks.recentBlockedTimes` (DB);
  - `blocks.isSystemBlocked` (provider IPC);
  - `sims.ownNumbers`.
- The budget is `withTimeoutOrNull(3000)` with fail-open (`ParleyCallScreeningService.kt:58`).
- `CallManager` polls every 50 ms while screening (`CallManager.kt:392`).

**Impact:** About 50–200 ms warm, and 1 s or more cold (Room open plus Keystore, worse under F3's contention). On timeout the call is allowed, so a blocked number rings through.

**Fix**
- One PhoneLookup projection with id, name, starred and ringtone, reused for `isContact`.
- Reuse the first vault hit.
- Run the independent lookups concurrently with `async` (provider, DB, BlockedNumbers).
- Replace polling with awaiting a `StateFlow`.
- Add trace sections per lookup.

**Effort / risk:** S–M / L.

### F16 — Keypad T9: per-keystroke allocations and number cleaning per entry (LOW–MEDIUM)
**Evidence**
- `DialSearch.kt:64`: `e.contact.phones.map { it.number }` per tested entry.
- `T9.kt:239-246`: `PhoneNumbers.clean` plus `digits` for every number on every keystroke.
- `recencyBonus` does a `LineMap` lookup (E.164) per phone of each match (`:122-126`).
- `addRecentNumbers` runs `distinctBy { PhoneIdentity.key }` over the whole call list whenever there are fewer than 5 hits (`:140-148`).

**Impact:** About 5–20 ms per key at 20k contacts on low-end phones for the first 1–2 digits; narrowing helps from 3 digits.

**Fix:** Precompute digits per `Entry`; compute recency per contact id once per calls change; precompute distinct recent lines once.

**Effort / risk:** S / L.

### F17 — Recents re-runs grouping on every search keystroke (LOW–MEDIUM)
**Evidence:** `app/.../ui/home/RecentsViewModel.kt:152-160`. `query.debounce(80)` is a `combine` input to `group()` (`:246-267`). Grouping recomputes chip filters, `PhoneIdentity.key` and an index lookup for up to about 8k calls before the search filter is applied at `:267`.

**Impact:** About 10–40 ms of CPU per keystroke.

**Fix:** Cache the grouped list, then apply the query in a separate `map` stage.

**Effort / risk:** S / L.

### F18 — `ContactKeys.sweep` does per-contact work that does not depend on what changed (LOW–MEDIUM)
**Evidence**
- `core/data/.../people/ContactKeys.kt:302`: `current.keys.forEach { bg.forLookupKey(k) }`, a SHA-256 plus `stat` for every contact on every effective sweep.
- `meta.allMetaNow()` decrypts every pinned note (`security/SealedDaos.kt:105`).
- Sweeps run 15 s after every contacts change while the app is alive (`AppContainer.kt:294-305`).

**Fix:** Run the background-indexing pass once (store a flag); select only key and id columns when notes are not needed.

**Effort / risk:** S / L.

### F19 — Saving a contact with relations costs two provider queries per stored mirror (MEDIUM for heavy relation users)
**Evidence:** `core/data/.../people/RelationMirrors.kt:87`: `all.filter { c -> c.from == selfKey || contacts.currentOf(c.from, c.fromId)?.first == selfId }`. `currentOf` does `lookupContact` plus `lookupKeyOf` (`ContactsRepository.kt:1076-1082`). The `takeBack` variant (`:170`) compares `fromId` directly, without queries.

**Impact:** 500 mirrors means about 1,000 queries, roughly 1–3 s per save.

**Fix:** Pre-filter by `fromId == selfId` and resolve the rest through a single `contacts.lookupKeys()` map.

**Effort / risk:** S / L.

### F20 — Shared labels: first join is O(new cards × label members × keys) (MEDIUM for large shared labels)
**Evidence:** `core/data/.../sync/shared/SharedLabelEngine.kt:523`. `labelMembers.firstOrNull { … SharedCards.matchKeys(it.card).any { k -> k in keys } }` recomputes the members' match keys for every incoming card.

**Impact:** A 2k-member label means about 4M `matchKeys` calls on the first sync.

**Fix:** Build `matchKey → member` once per run.

**Effort / risk:** S / L.

### F21 — Contact-photo loader (MEDIUM: scrolling smoothness and memory)
**Evidence**
- `core/ui/.../Avatar.kt:46-67`:
  - opens the URI twice (bounds pass, then decode);
  - no deduplication of requests already in flight;
  - unbounded `Dispatchers.IO` fan-out during flings;
  - decodes are not cancellable;
  - always ARGB_8888;
  - the cache is keyed by pixel size, so the same photo is stored once per size;
  - the 16 MB cap ignores `memoryClass`.
- Widgets and missed-call notifications decode photos at full size with no sampling (`FavoritesWidget.kt:280`, `DialWidget.kt:68`, `MissedCallNotifier.kt:289`).

**Fix**
- Use `ContentResolver.loadThumbnail` or `ImageDecoder` with `setTargetSize`.
- Use `Dispatchers.IO.limitedParallelism(4)` and an in-flight `Deferred` map.
- Use RGB_565 or HARDWARE bitmaps for avatars.
- Size the cache from `memoryClass / 8`.
- Use `inSampleSize` in widgets and notifications.

**Effort / risk:** S / L.

### F22 — Contacts tab LazyColumn builds one lambda per row and reads broad state at the top (LOW–MEDIUM: recomposition)
**Evidence**
- `app/.../ui/home/ContactsTab.kt:156-197`: `rows.forEach { item(key = c.id) { … } }` registers about 20k items on each recomposition of the list scope.
- The scope reads `selection`, `secondLines`, `hints`, `index` and `settings` (`:78-91`). Toggling selection, an index refresh, or a hint change rebuilds the item list, and every visible row recomposes.
- Recents rows and keypad rows call `PhoneNumberUtils.formatNumber` during composition without `remember` (`KeypadTab.kt:872-895`, `Format.kt:18-21`).

**Fix**
- Use `items(rows, key, contentType)`.
- Pass per-row state through stable lambdas or `derivedStateOf { id in selection }`.
- `remember(number) { Format.number(…) }`.

**Effort / risk:** S / L.

### F23 — Daily private call-log sweep does Keystore lookups per call, and a COUNT without an index (MEDIUM: battery)
**Evidence**
- `VaultRepository.kt:913-940`: `lookup(number)` for every call in the last 30 days, which is a Keystore HMAC per number form plus `dao.get`.
- `storePrivateCall` runs `countPrivateCall(vaultId,date,type)` with no matching index (`AppDatabase.kt:570`).
- `MaintenanceWorker.kt:185` also loops `vault.allNumbers()` calling `ringFacts.forget` and `callQuality.forget`.

**Impact:** About 1.5k calls × 2–3 IPCs, so about 3–10 s daily.

**Fix:** Build the vault number set once per sweep (`LineSet` from `allNumbers()`) and look up only the hits; add the index.

**Effort / risk:** S / L.

### F24 — Missing indexes (LOW–MEDIUM; additive AutoMigration)
- `journal(time)` and `journal(contactKey)`: `AppDatabase.kt:293,296,308`.
- `private_calls(vaultId)` and `(vaultId,date,type)`: `:570,573`, `VaultRepository.kt:1004-1009`.
- `call_rings(startedAt)`: `ringsSince`/`rings(since)` and `pruneRings` (`:657,675`). The ReputationLearner reads 60 days daily.
- `temporary_contacts(expiresAt)`: small table; optional.

**Effort / risk:** S / L (`AutoMigration` 8→9).

### F25 — Databases never shrink after automatic pruning (LOW–MEDIUM: storage)
**Evidence:** `compactDatabase` (`UndoStorage.kt:75-84`) runs only on a manual "clear" (`UndoStorage.kt:53`, `CallHistory.kt:635`). Daily archive retention, journal pruning after 30 days, trash pruning after 30 days, and blocked-call pruning leave free pages behind, so the file keeps its high-water mark (critical after F6 spikes).

**Fix:** `PRAGMA auto_vacuum=INCREMENTAL` (needs one VACUUM), then `incremental_vacuum` in maintenance when `freelist_count/page_count > 20%`.

**Effort / risk:** S / L.

### F26 — Cold start with a large address book shows a spinner until the full provider load finishes (MEDIUM: perceived start time)
**Evidence**
- `MainActivity.kt:53` calls `startFull()`, then the full `summaries(null)`: Phone, Email and Contacts queries plus `PhoneIdentity.key` per number (`ContactsRepository.kt:243-306`).
- `ContactsTab.kt:96-99` shows a `CircularProgressIndicator` until `listing` is ready, which requires `directory` sort, `PeopleUi.searched` and `listing`.
- At the same time:
  - the archive decrypts 5,000 rows;
  - a full sync runs if the last one was more than 6 h ago (`CallHistory.kt:208-213`);
  - `numberMemory.follow()` runs and may rebuild (F4);
  - widgets start.

**Impact:** About 1–2.5 s to the first contact row at 20k contacts on mid-range phones (Recents uses its preview page and is faster).

**Fix**
- Keep a compact, sealed snapshot of the last list (id, name, photo URI, starred, first number) and render it at once, then swap.
- Defer the archive full sync and memory rebuild until after the first frame and idle (for example `delay` plus `fullStart` plus "UI idle").
- Optionally lower `CHANGE_QUIET_MS` impact.

**Effort / risk:** M / M (the cached list must be sealed; check with the privacy track).

### F27 — Collator sorts compare strings directly with no precomputed keys (LOW–MEDIUM)
**Evidence**
- `ContactDirectory.kt:24-31` and `NameOrder.apply` (`core/common/.../people/NameOrder.kt:37`) sort with `Collator.compare`.
- `AppViewModel.everyone` creates a **new** Collator and merges on every emission (`AppViewModel.kt:227-239`).
- `PeopleUi` re-sorts on each keystroke (F7).

**Impact:** About 300k compares at roughly 1 µs each, so 0.2–0.5 s per emission at 20k contacts when sorting by last name or with private contacts present.

**Fix:** Precompute `CollationKey` once per list (or `getCollationKey` cached per contact).

**Effort / risk:** S / L.

### F28 — Original contact photos have no overall size limit (LOW–MEDIUM: storage)
**Evidence:** `OriginalPhotos.kt:40-41,48-49`. Originals are kept byte for byte up to `MAX_BYTES = 20 MB` each (`core/common/.../photo/OriginalPhoto.kt:11`), with no total quota. Backups are capped at 4 MB for originals (`PeopleContainer.kt:233`), so most originals are not even backed up. Stale originals are cleared only when the contact is opened (`forContact`, `:144-162`) or a key sweep finds the contact deleted.

**Impact:** 500 framed photos at about 3 MB each is about 1.5 GB of app storage.

**Fix:** Re-encode originals larger than about 2 MP or 1.5 MB at high quality; add a storage line in Settings › Storage; sweep originals whose contacts are gone during maintenance.

**Effort / risk:** S / L.

### F29 — Smaller algorithmic issues (LOW)
- `Duplicates.nameKey` compiles a new `Regex` for every contact (`core/common/.../Duplicates.kt:40`). Hoist it to a constant.
- `HealthScanner` "shared number" rescans `byKey[k].distinctBy` for each owner, which is O(k²) for a widely shared switchboard number (`HealthScanner.kt:50-52`). Compute it once per key.
- `Batches.pairs` produces O(r²) AggregationException operations on merge (`ContactText.kt:55-60`). This is fine for normal merges; cap or chain-link groups larger than 30 raw contacts.
- `CallQualityStore.add` re-seals all 400 rows and rewrites about 80 KB of XML on every call (`CallQualityStore.kt:73-83,119-135`). Acceptable; could append instead.

### F30 — Migration safety (informational)
- Good: both databases use `exportSchema=true` with schemas checked in (`core/data/schemas/...` 1–8 and History 1), additive `AutoMigration`s, and no `fallbackToDestructiveMigration`.
- Gaps:
  - No downgrade handling: a 5.3 → 5.2 sideload crashes on open, because `parley.db` v8 is not known to v7.
  - The fixes in F6 and F11 need real `Migration`s that rebuild tables. Add `MigrationTestHelper` tests with sealed blobs.
  - `HistoryDatabase` is at v1 with no migration tests.

### F31 — Workers, sensors and wakelocks inventory (informational)
- **Workers**
  - Maintenance: daily, `batteryNotLow`, heavy (F2, F4, F5, F23).
  - Backup: every N days, `batteryNotLow` and `storageNotLow`.
  - FolderSync: hourly with no constraints, plus a content trigger (F14).
  - Reminders: daily, aligned to the chosen hour, no constraints. Fine.
  - History: 30 s after every call (F13).
  - FollowUp and ToCall: one-time.
  - SpamList: from maintenance.
- **Suggestion:** split Maintenance into "promises" (expiry, retention; keep daily) and "heavy" (snapshot, number memory, reputation, garbage collection) with `setRequiresDeviceIdle` or `setRequiresCharging`.
- **Sensors and wakelocks:** `FlipSilencer` registers only while a lone call rings (`FlipSilencer.kt:36-53`). `ProximityProbe` is one-shot with a 400 ms timeout. The proximity wakelock has a 4 h cap and is released on state change. The call-limit partial wakelock has a timeout. No issues found.

---

## Growth model (5k contacts, 100k calls, 3 years, defaults)

| Store | Growth | 3-year estimate | Bounded? |
|---|---|---|---|
| `parley-history.db` archived_calls | about 450–600 B per call including 3 indexes | 45–60 MB (100k calls) | **No** (retention 0 by default) |
| `timemachine/index` | about 150 B per contact per snapshot | **about 135 MB** at 5k contacts (540 MB at 20k) | 180 days, but heavy (F1) |
| `timemachine/blobs` | unique contact versions plus photo thumbnails | 20–60 MB | yes, by GC (which is costly, F2) |
| `parley.db` journal | 2–300 KB per contact per action | 0 to over 1 GB after bulk operations, never shrinks | 30 days with no VACUUM (F6, F25) |
| `private_calls` | about 250 B per call | grows with private calls | **No** (F3) |
| `contact_photos` originals | up to 20 MB each | about 0.5–1.5 GB with heavy photo use | **No** (F28) |
| number_memory index | about 100 B per line and hint | 2–5 MB | rebuilt |
| SharedPreferences (about 41 files) and 3 DataStores | mostly small | under 2 MB; `folder_sync_state.json` about 1–3 MB at 20k | yes |

---

## Ranked top 15

| # | Finding | Why it ranks here | Effort |
|---|---|---|---|
| 1 | F1 Time-machine indexes parsed in full | Out-of-memory crash at 5k+ contacts after months; up to 540 MB on disk | M |
| 2 | F2 Time-machine GC decodes everything daily | 0.9–3.6M decodes per day after day 180 | S |
| 3 | F3 Private calls: Keystore decrypt of every row, warmed in call processes | Hurts the incoming-call budget; no retention | M |
| 4 | F4 Number memory: Keystore HMAC per number plus full archive scan daily | 25–75 s of Keystore time per day | M |
| 5 | F5 Archive OFFSET scans and unbounded growth | Seconds of work for per-number deletes; 60 MB+ | S–M |
| 6 | F8 Bulk delete over 500 fails | Correctness bug at scale | S |
| 7 | F6 Journal stores full photos; blob column order | Gigabyte spikes, never shrinks, slow list | M |
| 8 | F7 Contacts search per keystroke | Visible jank at 10k+ contacts | M |
| 9 | F9 Restore plan held in memory | Out of memory on large restores | M–L |
| 10 | F10 PeopleIndex full rescans while hot forever | Background battery use | M–L |
| 11 | F11 `vault_contacts` column order | Hidden I/O, including on the call path | M |
| 12 | F15 Screening lookups serial and duplicated | Incoming-call latency and fail-open risk | S–M |
| 13 | F12 Call-log reload storms | CPU after every call | S |
| 14 | F13 HistoryWorker reload and resident keys | 15 MB heap; work after every call | S |
| 15 | F14 Hourly folder sync with no constraints | Battery | S |

Next in line: F21 photo loader, F26 cold start, F23 vault sweep, F24 indexes, F25 vacuum, F19 relation mirrors, F20 shared labels, F22 LazyColumn, F27 collator, F28 originals.

---

## Summary (≤300 words)

Parley's call path is carefully engineered. The `StartGate` keeps call-started processes lean, the contact list reloads incrementally, the keypad narrows its search as you type, and spam lists are memory-mapped. The scalability risks are in **long-lived stores and background upkeep**, which grow with years of use and with large address books.

**Critical.** The time machine re-parses all 180 full JSON indexes (one `lookupKey → hash` map per day) on every access: daily maintenance, number-memory stamps, History & undo. At 5k contacts that is about 135 MB on disk and roughly 225 MB of heap, so out-of-memory crashes are likely; at 20k it is about 540 MB / 900 MB (F1). Garbage collection then decodes every record of every snapshot daily (F2).

**High.**
- Many operations still go through the Android Keystore one row or one number at a time:
  - private calls are re-decrypted through the Keystore on every change, and this starts even in a process launched for an incoming call (F3);
  - number memory does about 2–3 Keystore HMAC calls per number on top of a full archive decrypt, daily (F4);
  - the daily vault sweep does the same per call (F23).
- The call archive is unbounded by default and scanned with OFFSET paging for single-number actions (F5).
- The undo journal keeps full-resolution photos per contact. Its list query reads every blob because `restored` sits after `payload`, and the database never vacuums (F6, F25). `vault_contacts` has the same column-order problem (F11).
- Bulk deletes over 500 contacts fail on the provider's batch limit (F8).
- Restore planning holds both address books, with photos, in memory (F9).

**UI.** Contacts search re-normalizes every field of every contact per keystroke (F7). Call-log changes trigger un-debounced recompute waves (F12). The Contacts LazyColumn rebuilds 20k item lambdas on selection changes (F22).

Most fixes are small and low-risk:
- keyset pagination and per-person queries;
- software HMAC and AES subkeys wrapped by Keystore (the pattern `HistoryCrypto` already uses);
- distinct-hash garbage collection;
- chunked batches;
- debounce;
- prepared search rows;
- additive indexes.

Two schema rebuilds (journal, `vault_contacts`) and a new snapshot-index format need migrations and tests.
