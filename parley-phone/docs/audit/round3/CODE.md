# Track CODE: code quality, shared code, size, tests and build (Parley 6.2.1, `a67fd3d6`)

*8 Oct 2026. Read-only audit. No Gradle was run. Counts come from grep, find and small Python scans over `*/src/main` (build directories excluded). APK figures come from `dist/parley-6.2.1.apk` and `dist/parley-5.5.0.apk`. Test timings come from the JUnit XML left in `*/build/test-results` (5 Oct, so about the 6.2.0 tree). They are indicative, not current.*

*Round 2 (`docs/audit/round2/CODE.md`, 5.3.0) is the baseline. Items it raised and that shipped are listed as verified in §1 and are not reported again.*

---

## 0. Summary

**What is good, and the plan must not break it:**
- The layer boundaries still hold:
  - `core/common` has 0 `import android.*`.
  - `telecom` has 0 references to `app.parley.data`.
  - New 6.x screens (Situations, Cases, Recall, Chapters, Archive, Family shield) build on the kit: `ParleyScaffold`/`SettingsScaffold`, `ParleyListItem`, `SwitchRow` and `Section`. There are no raw `ListItem`s or `Switch`es outside the 14 baselined `DesignSystemComponent` entries.
- Shared code from round 2 is in use. `PhoneIdentity` has a detekt ban on `PhoneNumbers`, and only 3 small digit-equality bypasses remain. The other shared pieces:
  - `catching {}`
  - `PrivateNotice`
  - `Codecs` (one place for `Json {}` configs)
  - the `BackupExtras` registry check
  - `SealedLineStore`, which 6.2.1's `NetworkNameStore` reused instead of copying
- Dead code is low: about 25 functions (about 120 lines) and 5 strings, down from 54.
- The detekt baseline has been flat since 5.7, so 6.x code passes the rules.
- There is no lint baseline, and lint aborts on error.
- Room migrations are validated step by step against the exported schemas.

**What has grown since 5.3:**
- Main Kotlin grew 132,551 → 159,323 lines (+20%). App +12.5k, common +6.9k, data +5.3k, telecom +1.9k.
- The APK grew 6.29 → 6.42 MiB from 5.5 to 6.2.1. The dex is 10.75 MB raw, and each new line of source costs about 40 B of raw dex or about 22 B of download.
- The 6.x features added code faster than shared plumbing. Each one re-implemented four things:
  - who owns a number;
  - whether private data may show now;
  - how a small sealed document is stored;
  - how a file is written atomically.

  These are now the main sources of drift, and one of them is a visible bug (CODE-01).

**Top findings by value:**

| # | Finding | Severity | Effort | Lines saved |
|---|---|---|---|---|
| CODE-01 | "Who owns this number" is resolved in 12+ places; Archive (6.2) and network names (6.2.1) reached only some of them | High (correctness) | M | about 150 |
| CODE-02 | Privacy gating (hide private / duress / app lock / notes) is re-derived per feature, with 5 different predicates | High (privacy consistency) | M | about 100 |
| CODE-03 | 6.x sealed-document stores are copies of one pattern (To call → Menu memory → Case files, plus 4 card stores) | Medium–High | M | about 300 |
| CODE-04 | 30+ hand-rolled tmp-and-rename "atomic" writes, with 5 failure semantics and no `fsync` anywhere | Medium (durability) | S | about 100 |
| CODE-05 | The `DataContainer` service locator: 17 stores take the whole container, `vm.c.*` grew 542 → 620, constructor-launch races | Medium (architecture) | L, incremental | small |
| CODE-06 | Adding one contact field touches about 24 files; two hand-written org.json codecs for `ContactDetails` | Medium | M | about 110–200 |
| CODE-07 | God composables grew: `ContactEditScreen()` is one 699-line function (the file grew 1,349 → 1,594 lines) | Medium (maintainability) | M each | about 0 net |
| CODE-08 | Error handling: 1,011 `runCatching` against 172 `catching`, three names for one helper, and a baseline of 489 that is not shrinking | Medium | S–M | 0 (−489 baseline) |
| CODE-09 | Test health: untested custom detekt rules, regex source-scan tests, 20 copies of the same fixture, sleeps in polling loops | Medium | S–M | about 200 test lines |
| CODE-10 | Build health: about 94 stale baseline entries, +75 suppressions, Gradle android blocks repeated in 6 modules, testShared compiled twice | Low–Medium | S | about 90 kts lines |
| CODE-11 | Remaining size levers: DataStore (3 stores) still ships shaded protobuf; two JSON stacks | Low–Medium (APK) | S–M | about 110 KB raw dex |

**Achievable reduction without losing features:**
- About **1,500–1,800 main lines** (about 1% of main), plus about 200 test lines and about 90 Gradle lines.
- About **110–130 KB of download**: about 50 KB from DataStore and about 35 KB from the lines.
- An optional, owner-decided **−540 KB** from moving the remaining geocoder data to the Lists companion. This is not a pure saving: it changes where offline area names come from.

The code is already fairly tight after round 2. The value of this round is consistency and safety more than bytes.

---

## 1. Verified fixed since round 2 (do not re-open)

| Round-2 item | Status at 6.2.1 | Evidence |
|---|---|---|
| A1 compressed dex | Shipped | `app/build.gradle.kts:96` `dex.useLegacyPackaging = true`; APK 12.19 → 6.29 MiB at 5.5 |
| A2 geocoder China/Australia | Shipped | `86_en`/`61_en` absent; geocoder now 540 KB compressed |
| A5 one number path | Shipped | `PhoneNumbersOutsideIdentity` rule (`tools/detekt-rules/.../ParleyRules.kt:96`); `PhoneIdentity.kt` is the façade |
| A8 `catching {}` + rule | Shipped, but adoption is stalled (CODE-08) | `core/common/.../Results.kt:10`; `RunCatchingInSuspend` rule |
| A9 ContactDetailScreen sealed dialog state | Shipped (`a42bfbce`) | — |
| A10 CallManager split | Shipped: 1,691 → 1,130 lines with collaborators (`4a3f49d8`) | `telecom/.../CallManager.kt` |
| A11 `ContactsRepository.save` split | Shipped (`d42fde25`) | 1,400 lines, `save` no longer in the top 25 |
| A12 notifications | Mostly shipped: `PrivateNotice` exists; Rescue copies CallNotifier (CODE-04b) | `app/.../work/PrivateNotice.kt` |
| A14/A15 raw ListItem / PersonRow | Shipped: raw `ListItem`/`Switch` banned | `config/detekt/detekt.yml` `DesignSystemComponent`; 14 baselined |
| A20 Json configs | Shipped: `Codecs.kt` (6 named configs); 2 local `Json {}` remain | `core/common/.../Codecs.kt:11-26` |
| A22 strings | Unused 54 → 5 | see §6 |
| A26/A27 Kover | Plugin wired behind `-Pcoverage`, no thresholds | `build.gradle.kts:12-27` |

---

## 2. Size and shape

### 2.1 Lines per module (main Kotlin, with test ratio)

| Module | main LOC | 5.3 → 6.2.1 | files | test LOC | @Test | test/main |
|---|---|---|---|---|---|---|
| core/common | 41,181 | +6,905 | 309 | 27,748 | 2,149 | 0.67 |
| core/data | 30,298 | +5,338 | 139 | 11,193 | 389 | 0.37 (was 0.22) |
| core/ui | 2,450 | +213 | 25 | 134 | 7 | 0.05 |
| telecom | 13,598 | +1,862 | 69 | 2,654 | 194 | 0.20 |
| app | 71,796 | +12,454 | 365 | 7,330 | 370 | **0.10** (was 0.08) |
| lists-updater | 825 | 0 | 7 | 0 | 0 | 0 |
| baselineprofile / detekt-rules | 505 / 175 | — | 10 | 0 | 0 | — |
| **Total** | **160,828** | +26.8k | | | | |

**Largest feature packages:**
- app: `ui/contact` 12,295; `ui/home` 6,801; `ui/people` 5,099 (6,849 with its subpackages); `ui/blocking` 4,334; `ui/settings` 4,101; `ui/history` 3,590; `messaging` 3,210.
- core/data: `people` 4,924; `vault` 2,477; `backup` 2,245; `sync/shared` 2,099.
- core/common: `people` 7,687; `calls` 5,075; `backup` 3,566; `vcard` 3,097.
- telecom: `ui` 7,325.

The 68 main files added since 5.7 hold 11,260 lines.

### 2.2 Top 25 files (main)

| # | Lines | File | Δ since 5.3 |
|---|---|---|---|
| 1 | 1,594 | `app/.../ui/contact/ContactEditScreen.kt` | +245 |
| 2 | 1,400 | `core/data/.../ContactsRepository.kt` | −19 |
| 3 | 1,240 | `core/common/.../vcard/VCardMapper.kt` | |
| 4 | 1,148 | `core/data/.../vault/VaultRepository.kt` | +109 |
| 5 | 1,130 | `telecom/.../CallManager.kt` | −561 (split) |
| 6 | 1,116 | `telecom/.../ui/InCallScreen.kt` | +37 |
| 7 | 1,004 | `app/.../ui/home/KeypadTab.kt` | +15 |
| 8 | 1,000 | `core/data/.../history/CallHistory.kt` | |
| 9 | 902 | `core/data/.../sync/FolderSync.kt` | |
| 10 | 899 | `core/data/.../backup/BackupRepository.kt` | |
| 11 | 862 | `core/data/.../sync/shared/SharedLabelEngine.kt` | |
| 12 | 852 | `app/.../messaging/NumberActionActivity.kt` | |
| 13 | 835 | `app/.../ui/contact/EditorViewModel.kt` | |
| 14 | 832 | `core/data/.../db/AppDatabase.kt` | |
| 15 | 816 | `core/data/.../records/ContactRecordStore.kt` | |
| 16 | 812 | `core/common/.../CallPolicy.kt` | |
| 17 | 766 | `app/.../ui/sync/shared/SharedLabelScreens.kt` | |
| 18 | 745 | `app/.../ui/settings/SettingsPages.kt` | |
| 19 | 745 | `app/.../ui/qr/QrResultSheet.kt` | |
| 20 | 731 | `app/.../ui/blocking/BlockingScreen.kt` | |
| 21 | 711 | `app/.../AppTelecomDependencies.kt` | +82 |
| 22 | 706 | `core/common/.../backup/BackupArchive.kt` | |
| 23 | 700 | `core/data/.../people/OriginalPhotos.kt` | |
| 24 | 677 | `app/.../messaging/ReachSheet.kt` | |
| 25 | 664 | `app/.../ui/home/RecentsTab.kt` (tied with `ContactDetailViewModel.kt`) | |

The wiring files grew with every feature:
- `AppContainer.kt` (`DataContainer`): 341 → 485 lines.
- `TelecomGraph.kt`: 361 → 430 lines, now 9 hook interfaces.
- `CallScreener.kt`: 426 → 539 lines.

### 2.3 Top 25 functions (main, brace-matched span; three CSV/quote false positives removed)

| # | Lines | Function | Location |
|---|---|---|---|
| 1 | 699 | `ContactEditScreen` | `ui/contact/ContactEditScreen.kt:234` |
| 2 | 530 | `BlockingScreen` | `ui/blocking/BlockingScreen.kt:126` |
| 3 | 450 | `KeypadTab` | `ui/home/KeypadTab.kt:192` |
| 4 | 362 | `VCardMapper.fromVCard` | `common/vcard/VCardMapper.kt:702` |
| 5 | 331 | `SharedLabelEngine.run` | `data/sync/shared/SharedLabelEngine.kt:274` |
| 6 | 325 | `FolderSync.run` | `data/sync/FolderSync.kt:542` |
| 7 | 299 | `BackupScreen` | `ui/backup/BackupScreen.kt:83` |
| 8 | 281 | `fields` (editor) | `ui/contact/ContactEditScreen.kt:559` |
| 9 | 274 | `BulkAddScreen` | `messaging/BulkAddScreen.kt:112` |
| 10 | 272 | `RuleEditorScreen` | `ui/blocking/RuleEditorScreen.kt:102` |
| 11 | 253 | `VCardMapper.toVCard` | `common/vcard/VCardMapper.kt:417` |
| 12 | 229 | `NumberReach` | `messaging/ReachSheet.kt:449` |
| 13 | 223 | `NumberHistoryScreen` | `ui/history/NumberHistoryScreen.kt:108` |
| 14 | 219 | `SelectionBar` | `ui/home/SelectionBar.kt:88` |
| 15 | 199 | `LabelScreen` | `ui/people/LabelScreens.kt:374` |
| 16 | 190 | `SpamListsScreen` | `ui/blocking/SpamListsScreen.kt:80` |
| 17 | 187 | `HomeScreen` | `ui/home/HomeScreen.kt:112` |
| 18 | 184 | `CallPolicy…run` (screening) | `common/CallPolicy.kt:481` |
| 19 | 182 | `ManageLabelsScreen` | `ui/people/LabelScreens.kt:102` |
| 20 | 182 | `RestoreFlow` | `ui/backup/RestoreFlow.kt:72` |
| 21 | 180 | `LabelPolicySection` | `ui/extras/LabelPolicySection.kt:63` |
| 22 | 177 | `ImportCallsScreen` | `ui/history/ImportCallsScreen.kt:62` |
| 23 | 170 | `ContactsTab` | `ui/home/ContactsTab.kt:89` |
| 24 | 168 | `ParleyRootContent` | `ui/ParleyRoot.kt:80` |
| 25 | 166 | `CallTimeScreen` | `ui/calltime/CallTimeScreen.kt:65` |

20 of the 25 are composables. The non-UI ones (sync engines, vCard mapping, screening) are long but linear and well covered by tests. The composables are the risk (CODE-07).

---

## 3. Findings

### CODE-01. "Who owns this number" is resolved in 12+ places, and the 6.2/6.2.1 owners reached only some of them

**Severity:** High (visible inconsistency, privacy drift). **Effort:** M.

**Evidence.** The chain "device contact → private contact → archived contact → network name → bare number" is written by hand in each feature, with different orders, region arguments and privacy rules.

Archived contacts (6.2) are handled in:
- `AppTelecomDependencies.kt:193,240,645`
- `MissedCallNotifier.kt:219`
- `AgendaStore.kt:74`
- `AppContainer.kt:123` (screening)

They are **not** handled in these, which also run `contacts.lookup` followed by `vault.lookup`:
- `calls/ToCallReminders.kt:198-199` (`nameOf`)
- `calls/ExpectedCallHints.kt:85,135-136`
- `messaging/NumberActionActivity.kt:607-608`
- `rescue/RescueCalls.kt:246-250`
- `calltime/CallTimePlanner.kt`
- `messaging/ChatThenDecide.kt`
- `messaging/BulkAddScreen.kt`
- `ui/people/cards/CardInbox.kt`
- `data/people/CallerCards.kt`

Network names (6.2.1) are handled only through `NetworkNames.readers` (Recents, Recall), `NumberHistoryScreen` and `MissedCallNotifier`.

The region argument also differs. `ToCallReminders.kt:199` and `ExpectedCallHints.kt:85` call `c.vault.lookup(number)` with no region, while `AgendaStore.kt:72` passes `PhoneEnv.countryIso(c.appContext, accountId)`.

**Impact:**
- The To call reminder notification for an archived contact shows the bare number. The missed-call notification for the same person shows their name.
- An archived contact on the To call list is treated as "unknown" by expected-call hints. `ExpectedCallHints.toCallAdded` returns early only for saved and private numbers, so archived ones get an "expecting a call" window that saved contacts never get.
- On a dual-SIM phone abroad, a private number can match in one feature and not in another.
- Every new owner kind needs about 12 edits, and 6.2 shows that some get missed.

**Recommendation.**
1. Add `NumberOwners` to `core/data`, next to `ContactDirectory`:

   ```kotlin
   sealed interface Owner {
       data class Contact(...) : Owner
       data class Private(...) : Owner
       data class Archived(...) : Owner
       data class Network(...) : Owner
       object Unknown : Owner
   }
   suspend fun owner(number: String, accountId: String?, use: Use): Owner
   // Use = SCREEN, NOTIFICATION, LOCK_SCREEN, CALL_PATH
   ```

   It applies the region (`PhoneEnv.countryIso(ctx, accountId)`), the order, and the privacy rules from CODE-02 once.
2. Move the 12 sites onto it.
3. Add a test that iterates `Owner` kinds × `Use` values.

**Saves:** about 150 lines, and closes a class of bugs. Keep `AppTelecomDependencies`' call-path lookup memory-only, as it is today: pass `Use.CALL_PATH`.

### CODE-02. Privacy gating is re-derived per feature, with five different predicates

**Severity:** High (privacy consistency). **Effort:** M.

**Evidence.** "May private data show now?" is computed in place across the codebase:
- 62 reads of `hideVault` outside the settings code
- 71 `Concealment.*` calls in 26 files
- 6 `AppLock.locked.value` checks

The variants:

| Site | Predicate |
|---|---|
| `calls/NumberSignals.kt:168` | `!hideVault && !Concealment.hiding` |
| `data/circle/AgendaStore.kt:52-54` | `!hideVault && !hides(PRIVATE_CONTACTS) && !hides(NOTES) && !VaultCrypto.detailNeedsUnlock()` |
| `data/circle/AgendaStore.kt:70` | `hideVault \|\| hides(PRIVATE_CONTACTS)` |
| `messaging/AgendaShare.kt:96` | `catching { hideVault }.getOrDefault(true) \|\| hides(PRIVATE_CONTACTS)` |
| `calls/CaseFileBridge.kt:63-64` | `!(AppLock.locked && settings.appLock) && !hides(NOTES)` |
| `AppTelecomDependencies.kt:510` | `hideVault \|\| Concealment.hiding` |
| `AppTelecomDependencies.kt:528` | `AppLock.locked && settings.appLock` (`CaseFileBridge` has its own copy) |
| `data/recall/RecallSources.kt:32,122-158` | its own `Access(privateShown)` plus 4 `hides()` checks |

`DuressPolicy.effective` already forces `hideVault = true` while hiding (`core/common/.../security/Duress.kt:120-126`). The `|| Concealment.hides(PRIVATE_CONTACTS)` clauses are therefore redundant. Features copy them because the contract isn't written down in one place.

One concept also has four names:
- **Hide private contacts** in the interface (GLOSSARY)
- `hideVault` in settings
- "discreet mode" in comments (139 mentions)
- `Concealed.PRIVATE_CONTACTS`

**Impact.** Each new feature picks a predicate, so the duress promise depends on every author choosing correctly. Security reviews need to read 26 files instead of one.

**Recommendation.**
1. Add `PrivacyView` to `core/data/security`. It is an immutable snapshot built from `settings.current()`, `Concealment.state` and an app-supplied `locked()` hook:
   - `privateListed` (shown in lists and search)
   - `privateReadable` (shown and the detail key unlocked)
   - `notesShown`
   - `circleNotesShown`
   - `safeWordsShown`
   - `appLocked`
   - `lockScreen: LockScreenCaller`
2. Expose it as `c.privacy.now()` and as `c.privacy.flow`.
3. Add a detekt rule (an extension of the existing rule set) that bans `Concealment.hides`/`hiding` and `settings…hideVault` outside `data/security`, `SettingsRepository` and `PrivacyView`. Ratchet it with the baseline.
4. Rename `hideVault` → `hidePrivate` while you are there. The datastore key can stay.

**Saves:** about 100 lines, and one place to review. This pairs with CODE-01: `NumberOwners` consumes `PrivacyView`.

### CODE-03. The 6.x sealed-document stores are copies of one pattern

**Severity:** Medium–High (duplication in security-sensitive storage). **Effort:** M.

**Evidence.** `ToCallStore` (4.4), `MenuMemoryStore` and `CaseFileStore` (6.1) are the same class with a different document type. Each has:
- a `Mutex`
- `@Volatile loaded`/`unsaved`
- `loadLocked()` with `openTextOrThrow` and the `UnreadableException` → "refuse changes" rule
- `flushLocked()` with `RecordSealing.markPending`
- `update(f)`
- `resealPlain()`
- a `backupExtras` that loads, checks `available`, filters private numbers with `runCatching { isPrivate(it) }`, and encodes

Locations:
- `calls/ToCallStore.kt:36-158`
- `calls/MenuMemoryStore.kt:28-128`
- `cases/CaseFileStore.kt:53-185`

Close variants of the same pattern:
- `calls/FamilySafetyStore.kt:54-187`
- `people/CardStores.kt:42-73` (`MyCardIdentity`)
- `people/CardStores.kt:201-259` (`ShareLedgerStore`)
- `people/CardStores.kt:344-398` (`CardLinkStore`)

By contrast, the per-line pattern was already generalised as `SealedLineStore<T>` (`calls/SealedLineStore.kt:18`), and 6.2.1's `NetworkNameStore` reused it. That is the model to follow.

**Impact:** about 70 duplicated lines per store. A fix to the "unreadable means refuse, never overwrite" rule has to be made seven times. The `runCatching { isPrivate(it) }` copies sit in the detekt baseline: `baseline.xml:1252,1388`.

**Recommendation.**
- Add `SealedDocument<T>(context, file, key, encode, decode, sanitize = { it })` in `core/data/security`. It provides:
  - `state: StateFlow<T>`
  - `available`
  - `load()`
  - `update(f): T?`
  - `resealPlain()`
  - `backupSection(name, section, forBackup: (T, isPrivate) -> T, merge)`
- The stores keep only their domain operations.
- The prefs file and key stay identical, so there is no migration. The existing `ToCallStoreTest`, `MenuMemoryStoreTest`, `CaseFileStoreTest` and `CardStoresTest` guard the format.

**Saves:** about 300 lines.

### CODE-04. Hand-rolled atomic writes (30+), and two more notification copies

**Severity:** Medium. **Effort:** S.

**(a) Atomic writes.** "Write `x.tmp`, then `renameTo`" appears at least 30 times, with at least five failure behaviours:
- `check(...)`: `ArchiveStore.kt:225-227`, `HistoryCrypto.kt:63-65`, `OriginalPhotos.kt:268-276`
- delete-then-rename, with the second result ignored: `AppPinStore.kt:129-134`, `FolderSync.kt:421-424`, `TimeMachine.kt:157-159`
- result ignored: `VaultRepository.kt:534-536,985-987`, `PrivateTrash.kt:67-69`, `ContactListHead.kt:35-37`
- returns a Boolean: `FamilyShieldStore.kt:114-116`, `TimeMachine.kt:63-65`
- deletes the tmp file on failure: `CallBackgrounds.kt:181-184`, `OriginalPhotos.kt:398-400`

There are also copies in `SpamListStore`, `SharedLabelState`, `ExchangeFolders`, `CarriedSections`, `NumberMemoryIndex`, `Concealment.kt:166-170`, and in app `TemplateGallery`/`CallerTuneUi`.

**No write calls `fd.sync()`** (grep finds none).

The impact:
- `AppPinStore` can lose the PIN record silently if both renames fail.
- A crash right after a rename can leave a zero-length file on file systems without replace-on-rename heuristics.

Durability is a question for the data and security tracks. The duplication is this track's.

Recommendation: add `AtomicFiles.write(file, bytes): Boolean`, plus `writeText`, in `core/data`, using `FileOutputStream` + `fd.sync()` + rename, with one fallback and one log. Replace the copies. **Saves:** about 100 lines.

**(b) Notifications.**
- `telecom/RescueNotifier.kt:30-145` re-implements CallNotifier's builders: `person()` (`:118`) against `CallNotifier.kt:259`, `publicVersion()` (`:110`) against `CallNotifier.kt:349`, `lockMode()`, and the incoming/ongoing CallStyle skeletons.
- Behaviour already differs. Rescue always uses `VISIBILITY_PRIVATE` and has no `Person.setUri`. For a call that is never placed this is arguably right, but it is accidental.
- `JobNotices.kt:27` and `MissedCallNotifier.kt:278` each keep their own `publicVersion` next to `PrivateNotice`.

Recommendation: a `CallNotificationTemplate` in telecom that takes `(title, person, intents, visibility)`, used by both notifiers, and route the public versions through `PrivateNotice`. **Saves:** about 80 lines.

### CODE-05. The cost of the `DataContainer` service locator

**Severity:** Medium (architecture). **Effort:** L, done incrementally.

**Evidence:**
- `core/data/.../AppContainer.kt` holds `class DataContainer`. The file name doesn't match; it is suppressed through `MatchingDeclarationName`. The class has 485 lines and 58 `by lazy` members, with 23 post-construction callback assignments such as `s.onScreened`, `h.onForget`, `h.onDeleted`, `v.labelGroups` and `contacts.beforeChange`.
- 32 mutable `var hook: (…)? = null` callbacks exist across data, app and telecom.
- 17 core/data classes take the **whole** container (`ArchiveStore(this)`, `AgendaStore(this)`, `ExtrasStore(this)`, `PeopleContainer(this)`, `NumberMemoryStore(this)`, `SyncWatch(this)`, `BulkAddStore(this)`, `TemporaryContactStore(this)`, `ContactExport`, `DataWipe`, `RecallSources`, `ReputationLearner`, `LockTransitions`, …). Their real dependencies can only be found by reading them.
- In the app module, `vm.c.*` appears 620 times in 139 files (542 at 5.3), and 447 composables take `vm: AppViewModel`.
- The 6.x UI packages have **no view model**: `ui/situations`, `ui/cases`, `ui/recall` and `ui/people/chapters` call `vm.c.cases.update`, `vm.c.extras.updateChapters` and so on from composables. Examples: `ChapterUi.kt:117-157` and `CaseScreen.kt:152,244,309`.
- 34 test files construct a full `DataContainer`.
- Constructor-time launches have caused declaration-order races. `3197710e` fixed "the vault's listing used its summary cache, declared after it", and a contact-list instance came before it. 11 `init {}` blocks still launch coroutines:
  - `SettingsRepository.kt:90`
  - `AppContainer.kt:454`
  - `CallHistory.kt:962`
  - `VaultRepository.kt:868`
  - `MessagingStore.kt:85`
  - `ArchiveStore.kt:65`
  - `AppViewModel.kt:493`
  - `ToCallModel.kt:106`
  - `RecentsViewModel.kt:94`
  - `PeopleUi.kt:220`
  - `PrivateSearch.kt:65`

**Impact:**
- A change to one store can break an unrelated test or startup path.
- The "is this private?" lambda `{ n -> vault.lookup(n) != null }` is copied 6 times inside the container.
- New features copy the `(c: DataContainer)` shape because it is the easiest path.

**Recommendation.** Don't adopt a DI framework: the APK cost and churn aren't worth it. Instead:
1. Freeze the pattern with a detekt rule: no new constructor parameter of type `DataContainer` outside `AppContainer.kt`/`PeopleContainer.kt`, with the existing ones baselined. Stores take narrow lambdas or interfaces, as `MenuMemoryStore(context, isPrivate)` already does.
2. Add a rule, or a review checklist item: no `launch` inside `init {}`. Use an explicit `start()` that `DataContainer.startFull()` calls.
3. Give each 6.x feature a small UI façade like the existing `RecallUi`/`CircleUi` in `AppViewModel`: `CasesUi`, `ChaptersUi`, `SituationsUi`. Composables call intent methods instead of `vm.c.*`. Ratchet `vm.c.` with a count check in CI.
4. Rename `AppContainer.kt` → `DataContainer.kt`.

**Saves:** small (about 50 lines), but it shrinks the blast radius.

### CODE-06. One contact field touches about 24 files; two hand-written codecs for `ContactDetails`

**Severity:** Medium. **Effort:** M.

**Evidence.**
- Fields added in 6.2 fan out widely: `citizenship` appears in 24 main files (140 references) and `nativeName` in 28.
- Among the files that every field needs:
  - `ContactDetailsJson.kt` (109 lines, vault storage, compact keys)
  - `ContactDraftJson.kt` (125 lines, editor saved state)
  - `RecordDetails.kt`, `ExtraRows.kt`, `ContactEditRebase.kt`, `VaultMoves.kt`, `ContactModels.kt`
  - `VCardMapper.kt`, `ContactCsv.kt`, `EditorForm.kt`, `ContactSearch.kt`, `ContactFilters.kt`, `DetailsSearch.kt`
  - 6 editor UI files
- Both codecs are org.json, field by field.

**Impact:** a field forgotten in `ContactDraftJson` is lost on process death in the editor. One forgotten in `ContactDetailsJson` is lost when a contact is made private. Nothing fails at compile time.

**Recommendation:**
1. Replace `ContactDraftJson` with `@Serializable` on `ContactDetails` and `Codecs.full`. Drafts are short-lived saved state, so no cross-version format applies. **Saves:** about 110 lines.
2. Keep `ContactDetailsJson`, because it is a stored format. Add a reflective round-trip test that builds a `ContactDetails` with every property set to a non-default value (via its `copy` parameters) and checks that each codec, `RecordDetails` and the vCard mapper return it. That turns "forgot a field" into a red test.
3. Optionally, later: describe fields once (a field table with kind, mimetype and column) and drive `ExtraRows`/`RecordDetails`/rebase from it. That would save a further about 100–200 lines, at M–L risk.

### CODE-07. God composables grew again

**Severity:** Medium (maintainability, recomposition). **Effort:** M per screen.

**Evidence** (see §2.3):
- `ContactEditScreen()` is one 699-line function with 17 `remember` state variables and 4 dialogs. The file grew 1,349 → 1,594 lines, the largest in the repo, with native names, languages and citizenship added in 6.2.
- `BlockingScreen()`: 530 lines, 13 launches, 15 direct `vm.c`/`c.` calls.
- `KeypadTab()`: 450 lines.
- `BackupScreen()`: 299 lines, 11 state variables, 9 launches.
- The detekt `LongMethod` threshold is 80, and composables live in the baseline (64 `LongMethod`, 191 `CyclomaticComplexMethod` entries).

**Impact.** Most screen bugs in recent reviews come from these files. Their state is hard to test outside `UiSmokeTest`.

**Recommendation.** Apply the 5.7 `ContactDetailScreen` treatment (`a42bfbce`): one sealed `Dialog` state, sections as separate composables taking immutable models, and actions through `EditorViewModel` (which already exists, at 835 lines). Do `ContactEditScreen` first, then `BlockingScreen`. **Net lines:** about 0. The value is review and recomposition cost.

### CODE-08. Error handling and coroutine consistency

**Severity:** Medium. **Effort:** S–M (mechanical).

**Evidence.**

`runCatching` and `catching` by module:

| Module | `runCatching` | `catching` |
|---|---|---|
| app | 368 | 80 |
| core/data | 404 | 74 |
| telecom | 158 | 17 |
| core/common | 77 | 1 |
| **Total** | **1,011** | **172** |

- At 5.3 there were about 1,000 `runCatching`, so the count is unchanged.
- `suspendRunCatching`, a deprecated alias of `catching` (`Results.kt:19`), still has 99 uses. That is three names for two behaviours.
- `RunCatchingInSuspend` has 489 baseline entries at 5.7 and 489 at HEAD. The rule's own comment says they are "burned down over time", and that isn't happening.
- New 6.x files still mix: 36 `runCatching`, 55 `catching`, 12 `try`.
- Coroutine scopes:
  - 16 hand-made `CoroutineScope(SupervisorJob() + Main…)`, three of them file-level in new call-screen UI (`AgendaUi.kt:68`, `MenuMemoryUi.kt:70`, `CaseFileUi.kt:50`).
  - 137 `rememberCoroutineScope()`.
  - 206 `withContext(Dispatchers.IO)` in app, because repositories are still not main-safe.
- Callbacks against Flow: 32 callback hooks in telecom and 48 in app, next to 448 `collectAsStateWithLifecycle`.

**Impact.** A cancelled screen or job can carry on and report "failed". Reviewers can't tell intended swallowing from accidental swallowing.

**Recommendation:**
1. Run a mechanical codemod per module, core/data and telecom first: `runCatching` → `catching` at the 489 baselined sites. This is behaviour-identical except that cancellation is rethrown.
2. Delete `suspendRunCatching`.
3. Regenerate the baseline.
4. Add one `TelecomScopes.writes` for the three call-screen file scopes.

**Lines:** 0 net, −489 baseline entries.

### CODE-09. Test suite health

**Severity:** Medium. **Effort:** S–M.

**Good:**
- core/data tests grew 5.5k → 11.2k lines.
- Telecom has 22 test files, among them `CallPathTest`, `EmergencyCallTest`, `CallManagerScreeningTest` and `RescueCallTest`.
- The vault has 7 test files, and duress has 2 (`DuressUnlockTest`, `DuressSafetySwitchesTest`).
- Backup has round-trip tests, and migrations are validated.

**Gaps on critical paths** (no test references the class):
- `SpamListStore` (439 lines; pack install with atomic swap)
- `BlockRepository` (249)
- `FamilySafetyStore` (242; safe words; only indirect coverage)
- `PrivateTrash` (158; sealed deleted private contacts; one indirect use in `PrivateLabelStoreTest:175`)
- `backup/StoreSections.kt` (296) and `SyncWatch` (242)
- `TemporaryContactStore` (232)
- telecom `CallRtt` (321), `CallEndRecorder` (194), `DriveGate` (202), `RescueNotifier` (145)
- `MaintenanceWorker` and `RemindersWorker`

In total, 116 non-UI classes (14.3k lines) in data, telecom and app have no test that names them. **The 4 custom detekt rules (`tools/detekt-rules`, 175 lines) have no tests**, yet the phone-identity and cancellation guarantees depend on them.

**Brittle source-scan tests** (7 files):
- `app/.../ui/PrivateMarkTest.kt` (150 lines) contains its own Kotlin lexer and a hard-coded list of 7 file names. It is a lint rule written as a test.
- `HubAndRemindersRoutesTest.kt:104-117` and `AdvancedGroupsTest.kt:16-71` regex-parse composable source for `switchRow|linkRow|menuRow|choiceRow|item|blended("key"`. A DSL rename silently weakens them.
- `PersistentStoresTest.kt:46-83` regex-matches `getSharedPreferences("…")` and `const val FILE`. It is valuable, but blind to computed names.

**Slow tests and polling:**
- From the 5 Oct results, the whole suite takes about 252 s: common 85 s, data 94 s, app 57 s, telecom 16 s.
- The slowest suites:
  - `CallerTuneTest` 30.1 s for 10 tests (pure JVM audio synthesis)
  - `ImportFuzzTest` 23.5 s
  - `UiSmokeTest` 15.6 s
  - `ContactsScaleTest` 13.5 s
  - `AudioRoutingTest` 8.6 s
- 11 `Thread.sleep` polling loops, among them `AppTestbed.kt:111`, `WidgetCallTest.kt:84,91`, `ToCallRemindersTest.kt:70` and `SituationsControllerTest.kt:252` (1.5 s). There is a known flaky branch (`parley-flaky`, `83c421ef`).

**Duplication:**
- 20 test files each define a private `call(...)` `CallEntry` builder, and 6 define `contact(...)`.
- `core/data/src/testShared` is compiled twice: added as a `srcDir` to both core/data and app (`app/build.gradle.kts:124`).
- One duplicate test name, `CallWaitingTest`, exists in common and telecom. They test different layers, which is fine.

**Recommendation:**
1. Unit-test the 4 detekt rules with `detekt-test` (S).
2. Turn `PrivateMarkTest` into a detekt rule (S).
3. Assert the settings tests against `SettingsCatalog` data instead of source text (M).
4. Add `testFixtures` (AGP `testFixtures.enable = true`) for core/common and core/data with `Fixtures.call()`/`contact()`, replacing the `srcDir` trick. **Saves:** about 200 test lines.
5. Replace sleep loops with `runTest`/`advanceUntilIdle` or an idling hook in `AppTestbed`.
6. Cut `CallerTuneTest` to short samples, or tag it as slow.
7. Add focused tests for `SpamListStore`, `PrivateTrash`, `FamilySafetyStore` and `CallRtt`.
8. Set a Kover floor per module at today's value, so coverage can only rise.

### CODE-10. Build health

**Severity:** Low–Medium. **Effort:** S.

**Detekt baseline.** 1,435 entries: 973 at 5.3, plus 494 when `RunCatchingInSuspend` was added in 5.4, minus 27 others. It has been unchanged since 5.7 (`db8c42f2`), which is good.

About 94 entries are stale:
- 59 `MaxLineLength`, 31 `RunCatchingInSuspend` and others whose snippet no longer exists
- 2 for deleted files (`MarkdownExport.kt`, `MarkdownExportSection.kt`)

Stale entries hide nothing today, but they make the baseline unreviewable. Signature-keyed entries such as `MenuMemoryStore.<no name provided>$runCatching { isPrivate(it) }` would also mask an identical new site in any anonymous object of that file.

**Suppressions.** `@Suppress` grew 148 → 223 (+75). The rules most suppressed:

| Rule | Count |
|---|---|
| `CyclomaticComplexMethod` | 90 |
| `DEPRECATION` | 40 |
| `LongParameterList` | 35 |
| `TooGenericExceptionCaught` | 29 |
| `MatchingDeclarationName` | 23 |

About 48 have no comment on the same or the previous line. Combined with the baseline, about 280 complexity findings are excused.

**Lint.** There is no lint baseline. `abortOnError = true` in every module, with a shared `lint.xml`. This is good, so keep it.

**Gradle.** There is no convention plugin or `build-logic`:
- The same `compileSdk = 36`, `minSdk = 29`, Java 17 `compileOptions`, `jvmTarget` and `lint {}` block is repeated in 6 module scripts (39 matching lines).
- The version catalog is used consistently: no raw coordinates in any `.kts`.
- `core/data` still exposes `api(libs.androidx.datastore.preferences)`.

**Recommendation:**
1. Run `detektBaseline` once to drop the stale entries.
2. Add a CI grep that fails on an `@Suppress` without a reason comment.
3. Move the shared android block into a root `subprojects { plugins.withId("com.android.library") { … } }`, or a 40-line `build-logic` convention. **Saves:** about 90 lines of `.kts`.
4. Put the SDK levels in `libs.versions.toml`.

### CODE-11. Remaining size levers

**Severity:** Low–Medium (APK). **Effort:** S–M.

**Evidence.** The 6.2.1 APK is 6,735,391 B:

| Part | Raw | Compressed |
|---|---|---|
| `classes.dex` | 10.75 MB | 5.28 MB (82%) |
| Geocoder data (599 files) | | 540 KB |
| `resources.arsc` (must stay stored) | 417 KB | 417 KB |
| Phone metadata | | 127 KB |

Remaining levers:
- **DataStore** is still used by 3 stores: `SettingsRepository.kt:36`, `HistoryPrefs.kt:22`, `PeoplePrefs.kt:57`. The release dex still carries the shaded protobuf (86 `datastore.preferences.protobuf` strings). Round 2 measured about 110 KB raw dex for DataStore plus protobuf.
- **Two JSON stacks:** org.json in 29 files (352 sites; the largest are `SharedLabelState.kt` with 49 and `VaultRepository.kt` with 25) next to kotlinx.serialization in 66 files. New 6.2 code (`ArchiveStore.kt`, 10 sites) still chose org.json. org.json is part of the platform, so it costs no APK. The cost is two codec styles to review.

**Recommendation:**
1. Move the 3 DataStores to `SharedPreferences` behind the same `Flow` API, with a one-time migration that reads the `.preferences_pb` file. **Saves:** about 110 KB raw dex, or about 50 KB of download.
2. Write new stored formats in kotlinx only. Migrate org.json opportunistically, never for stored formats without golden tests.
3. Optionally, as an owner decision: ship the remaining geocoder data through the Lists companion (−540 KB). Without it, offline area names for unsaved numbers come only from the companion.

---

## 4. Duplication catalogue and the shared abstraction for each

| Pattern | Copies today | Shared abstraction (location) | Est. lines saved |
|---|---|---|---|
| Number → owner (contact, private, archived, network) | 12+ sites (CODE-01) | `NumberOwners.owner(number, accountId, Use)` (`core/data/people`) | about 150 |
| Privacy gating (hide private, duress, app lock, notes) | 26 files, 5 predicates (CODE-02) | `PrivacyView` (`core/data/security`) + detekt ban | about 100 |
| Sealed small-document store | 3 identical + 4 variants (CODE-03) | `SealedDocument<T>` (`core/data/security`) | about 300 |
| Sealed per-line facts | Already shared: `SealedLineStore` (Ring facts, Call quality, Network names) | Keep; route `ReputationStore` through it if its rows fit | 0–60 |
| Atomic file write | 30+ (CODE-04a) | `AtomicFiles.write` (`core/data`) | about 100 |
| Call-style notification | `CallNotifier` + `RescueNotifier`; 3 `publicVersion` copies | `CallNotificationTemplate` (telecom); `PrivateNotice` for public versions | about 80 |
| Backup sections for document stores | 3 copies of load→check→filter private→encode | `SealedDocument.backupSection(…)` | included above |
| Recall and Number memory corpus | Both read 7 of the same sources (journal deletions, messaged numbers, call notes, meta, private trash, snapshots, vault summaries) and filter them for privacy separately (`RecallSources.kt:54-170`, `NumberMemoryIndex.kt`) | `KeptSources` (`core/data/memory`): one privacy-filtered corpus with stamps, consumed by both | about 100 |
| `ContactDetails` JSON | 2 hand-written codecs (CODE-06) | `@Serializable` for drafts + a reflective field test | about 110 |
| Test `CallEntry`/contact builders | 20 + 6 files | `testFixtures` `Fixtures` | about 200 (test) |
| Gradle android block | 6 modules | Root convention | about 90 (kts) |
| "Is private?" lambda in the container | 6 (`AppContainer.kt:187,190,195,289,353`) | `private val isPrivateNumber` | about 10 |
| Ad-hoc digit equality | `MeCard.kt:50-53,86`, `MessengerPrefs.kt:80` | `PhoneIdentity.same` | 0 (correctness) |
| Hand-rolled call-screen scopes | 3 file-level (CODE-08) | `TelecomScopes.writes` | about 10 |

**Checked and found adequately shared (no action needed):**
- Dialogs: `ConfirmDialog` 108 uses, `ParleyDialog` 87.
- Sheets: `ParleySheet` 25.
- Settings rows: `SwitchRow` 63, `LinkRow` 46.
- Scaffolds: every screen uses `ParleyScaffold`/`SettingsScaffold`.
- `EmptyState`: 40 uses.
- List rows in 6.x screens use the kit. The remaining 293 `ParleyListItem` calls are uniform kit calls, not copies.

---

## 5. Consistency notes

- **Naming.** One concept, four names (CODE-02). `AppContainer.kt` holds `DataContainer`. `c` means the container in data and app, and also a contact or context in places.
- **Audit-ID references.** About 445 code comments cite plan IDs ("I21", "L6", "P7"). These collide across documents: `L6` is "assisted dialling" in code comments (`AppContainer.kt:208`, `Concealment.kt:172`) and "clipboard helpers" in `AUDIT_2.md` §3. Prefer a short reason over an ID, or prefix the document (`PLAN-I21`).
- **Comment density** is healthy and matches risk: common 24% of non-blank lines, data 19%, telecom 15%, app 10%.
- **Flow and callbacks.** The data layer exposes `StateFlow`s, but wiring is done with 32 nullable callback vars set after construction. That is temporal coupling: a store used before its hook is set silently does nothing. Prefer constructor lambdas (see CODE-05).

---

## 6. Dead code and strings

- **Unreferenced declarations** (one occurrence repo-wide, baselineprofile excluded): about 25, about 120 lines. Examples:
  - `ui/blocking/BlockingHooks.kt:285` `ExpectingCallMenuItem` (14 lines)
  - `ui/circle/CircleMemory.kt:88` `rememberPersonMemory`
  - `ui/people/PeopleUi.kt:288,293` `clearFields`, `moveFavorite`
  - `data/ContactsRepository.kt:699,1061` `newContactTarget`, `deleteUnjournaled`
  - `data/CallLogRepository.kt:209` `deleteAll`
  - `data/db/AppDatabase.kt:361,618` `clearJournalPhotos`, `findByHmac`
  - `data/people/PeopleIndex.kt:36` `AccountCount`
  - `data/CallScreener.kt:251` `currentSettingsNow`
  - `common/NumberText.kt:59` `e164Digits`
  - `core/ui` `ltrOrNull`, `bottomOnly`, `slowSpatial`
- **Test-only in main:** about 35. Examples: `CallManager.playDtmf:991`, `PhoneKeyMigration.rekeyMap:31`, `PhotoFrame.fromCrop/storedCrop`, `Snapshots.InMemoryBlobStore`. Move test-only helpers to `testFixtures`, or mark them `@VisibleForTesting internal`.
- **Strings:** 5,922 strings and 377 plurals. **5 unused** (`edit_language_hint`, `set_group_lists`, `set_group_keys`, `set_group_messaged`, `hist_export_import`). Duplicate values grew 742 → 852 copies in 469 groups; "Name" appears ×18 and "Call" ×14. With English only, most duplicates are deliberate context keys, so merge only exact UI twins. Low value.

---

## 7. Maintainability risks (riskiest to change) and what would make each safer

| Area | Why risky | What would make it safer |
|---|---|---|
| Call path: `CallManager` (1,130 lines, global `object`), `AppTelecomDependencies` (711, 55 overrides), `CallScreener` (539), `TelecomGraph` (9 hook interfaces) | Global mutable state; every feature adds a hook (Agenda, Case files and Menu memory hooks in 6.x); emergency and rescue interplay | Keep the 22 telecom test files green. Add contract tests per hook interface (fake `TelecomDependencies` with every hook throwing, so the call still connects). Add `RescueNotifier`/`CallRtt` tests |
| Vault: `VaultRepository` (1,148) + `VaultCrypto` (598), `PrivateTrash`, duress (`Concealment`) | Stored formats plus key lifecycle; privacy rules spread over 26 files | `PrivacyView` (CODE-02); golden-format fixtures (the `SealedFormatFixturesTest` pattern) for vault entries and trash; tests for `PrivateTrash` |
| Backup: `BackupRepository` (899), `BackupArchive` (706), 13 `BackupExtras` parts with order dependencies (`AppContainer.kt:318-334`: archive first, Situations last) | Order is encoded in a list comment | Encode the order as data (`BackupExtras.phase`) and assert it; keep `BackupRoundTripTest` as the gate; add `StoreSections` tests |
| Container start-up | Lazy cycles (vault ↔ contacts ↔ contactKeys ↔ people), init-launch races (CODE-05) | No launch in `init`; `start()` methods; a start-up test that builds every lazy on a background thread in random order |
| Contact model fan-out | About 24 files per field (CODE-06) | Reflective field round-trip test; draft codec generated |
| Sync engines: `SharedLabelEngine.run` (331), `FolderSync.run` (325) | Long linear functions with mass-delete guards | They are well tested (`SharedLabelSyncTest`, `FolderSyncTest`, exchange tests). Split only alongside behaviour work |

---

## 8. Prioritised refactor plan

Rules for every phase: no behaviour or stored-format change unless stated; no new permissions; settings ceiling untouched (none of these add settings); English only.

### Phase A: fixes and guard rails (S, 1–2 days)

1. CODE-01 hot fix: add archived and network names to `ToCallReminders.nameOf` and `ExpectedCallHints` now, before the full resolver lands. Pass the region.
2. CODE-04a: `AtomicFiles.write` with `fsync`; replace the `AppPinStore`, `Concealment`, `PrivateTrash`, `VaultRepository` and `ArchiveStore` copies first.
3. Delete the dead code (§6) and the 5 strings. Regenerate the detekt baseline (−94 stale entries).
4. Tests for the 4 detekt rules; `PrivateMarkTest` → detekt rule.
5. CI check: `@Suppress` needs a reason; count ratchet on `vm.c.` and on `(c: DataContainer)` constructors.

**Expected:** about −250 main lines, −94 baseline entries, about −5 KB APK.

### Phase B: shared plumbing (M, 1–1.5 weeks)

1. `PrivacyView` + detekt ban (CODE-02).
2. `NumberOwners` (CODE-01), consuming `PrivacyView`.
3. `SealedDocument<T>` for To call, Menu memory, Case files, then Family safety and the card stores (CODE-03).
4. `CallNotificationTemplate` + public versions through `PrivateNotice` (CODE-04b).
5. `KeptSources` shared by Recall and Number memory.
6. `@Serializable` editor drafts + reflective field round-trip test (CODE-06).

**Expected:** about −860 main lines, about −35 KB raw dex (about −18 KB download). Most of the value is consistency: one privacy predicate and one owner resolution.

### Phase C: size and build (S–M, 3–4 days)

1. DataStore → SharedPreferences for the last 3 stores, with migration (CODE-11).
2. Gradle convention block and `testFixtures`; drop the `srcDir` double compile (CODE-10, CODE-09).
3. Shared test fixtures; replace sleep loops; slim `CallerTuneTest`.

**Expected:** about −110 KB raw dex (about −50 KB download), −90 kts lines, about −200 test lines, and faster and less flaky unit tests (the target is under 200 s).

### Phase D: structure (L, incremental over releases)

1. Split `ContactEditScreen`, then `BlockingScreen`, `KeypadTab` and `BackupScreen`, using the sealed dialog state and section pattern (CODE-07).
2. UI façades for Cases, Chapters and Situations; stop new `vm.c.*` (CODE-05).
3. `runCatching` → `catching` codemod per module; delete `suspendRunCatching` (CODE-08).
4. Kover floors per module; tests for `SpamListStore`, `PrivateTrash`, `FamilySafetyStore`, `CallRtt`, `StoreSections` and the workers.
5. Optional: field table for contact rows; org.json → kotlinx for non-stored JSON.

**Expected:** about −300 to −600 main lines (mostly from dialog and state dedupe and codecs), −489 baseline entries, about −10 to −25 KB dex.

### Totals

| Phase | Main lines | Other lines | APK (download) |
|---|---|---|---|
| A | about −250 | −94 baseline entries | about −5 KB |
| B | about −860 | — | about −18 KB |
| C | about 0 | −90 kts, about −200 test | about −50 KB |
| D | about −300 to −600 | −489 baseline entries | about −5 to −12 KB |
| **Total** | **about −1,400 to −1,700 (about 1%)** | | **about −80 to −90 KB** (−540 KB more with the optional geocoder move) |

The line savings are modest because round 2 already removed most of the copy-paste. The main return of this plan is that the four places where 6.x features drift are the ones it consolidates: owner resolution, privacy gating, sealed documents and atomic writes. The next round of features then inherits correct behaviour instead of copying it.
