# Track A: Code quality, consistency and size (Parley 5.3.0, HEAD e085d27)

Method: read-only. Counts come from grep/find/python over `*/src/main` (build dirs excluded). APK and dex figures come from the
release artifacts already on disk: `app/build/outputs/apk/release/app-release.apk` (versionName **5.0.0**, built 2026-10-02,
11,692,296 B = 11.15 MiB) with its `mapping.txt` and `usage.txt`, analysed with `apkanalyzer dex packages`. That build is
three minor versions behind HEAD, so treat the KB figures as ±5%. The structure findings are from HEAD.

## Baseline numbers

| Module | main LOC | files | test LOC | @Test | test/main |
|---|---|---|---|---|---|
| core/common (pure JVM) | 34,276 | 261 | 21,959 | 1,745 | 0.64 |
| core/data | 24,960 | 116 | 5,470 | 219 | 0.22 |
| telecom | 11,736 | 54 | 2,237 | 169 | 0.19 |
| app | 59,342 | 300 | 4,852 | 249 | **0.08** |
| core/ui | 2,237 | 21 | 114 | 6 | 0.05 |
| lists-updater | 825 | 7 | 0 | 0 | 0 |

- APK: classes.dex is 9,372 KB and **stored uncompressed**. The geocoder data is 1,091 KB, resources.arsc 482 KB (stored), and the rest is under 150 KB.
- Dex (8.47 MB of method/class data): Parley's own code is 4.79 MB (57%) and Compose 2.21 MB. The biggest libraries are navigation 172 KB, ezvcard 134 KB, material-icons 133 KB, datastore 112 KB (79 KB of it protobuf), zxing 107 KB, work 107 KB, kotlinx.serialization 74 KB, room 63 KB, libphonenumber code 31 KB and fragment 21 KB.
- Layering is clean, and these items are confirmed:
  - core/common has 0 `import android.*`.
  - telecom has 0 references to `app.parley.data`.
  - The detekt `DesignSystemComponent` rule works: there are 0 raw `AlertDialog(` and 0 raw `ModalBottomSheet(` outside core/ui.
- The quality debt sits mainly in **app** (0.08 test ratio, service-locator access) and in **duplicated plumbing**.

---

## Findings

### A1. Release dex is stored uncompressed, which accounts for 40% of the APK. Severity: High (size). Effort: S. Risk: Low
- **Evidence:**
  - In `app-release.apk`, `classes.dex` has `compress_type=0` and is 9,596,984 B.
  - zlib -9 shrinks it to 4,703,674 B.
  - `minSdk = 29` means AGP defaults to `useLegacyPackaging = false`, so dex is stored for mmap.
  - F-Droid and sideload users download the APK byte for byte, unlike Play, which compresses on the wire.
- **Recommendation:** in `app/build.gradle.kts`, set `packaging { dex { useLegacyPackaging = true } }`.
  - The APK goes from about 11.2 MiB to about **6.5 MiB (−4.7 MB, −42%)**.
  - The cost: on install, ART extracts the dex, which adds about 9 MB of on-device storage and a slightly slower first install. Baseline-profile compilation is unaffected.
  - Decide this explicitly, and change the 12 MiB `checkReleaseApkSize` budget so it tracks the metric you actually care about (download size against installed size).

### A2. The libphonenumber geocoder ships 1.09 MB of place names; two files are half of it. Severity: Medium (size). Effort: S. Risk: Low–Med (feature)
- **Evidence:**
  - The APK has 601 files under `com/google/i18n/phonenumbers/geocoding/data`, totalling 1,091 KB compressed.
  - `86_en` (China) is 379 KB, `61_en` (Australia) 184 KB, `55_en` 77 KB, `49_en` 48 KB and `91_en` 40 KB.
  - The top 15 files are 803 KB.
  - The non-English geocoder files (fr/es/pt/de/ar) are only 8 KB, so trimming languages gains nothing (`GeoLanguages.SHIPPED`, `core/common/.../GeoLanguages.kt:12`).
  - The only consumer is `core/data/.../NumberInfo.kt:18,32`.
- **Recommendation:** add `86_en` and `61_en` to the `resources.excludes` list in `app/build.gradle.kts`. Their numbers then fall back to the country name, which the geocoder already returns when no prefix file exists. This saves **about 560 KB**.
- Optionally, go to country-level only (exclude all of `geocoding/data/*`) and save about 1.08 MB. You lose city or area names, so this is a product decision.

### A3. Resource names are not collapsed: a 139 KB key pool sits in the stored arsc. Severity: Low–Med (size). Effort: S–M. Risk: Low
- **Evidence:**
  - Parsing `resources.arsc` gives a key-string pool of 138,820 B for 5,560 keys. Names such as `set_amoled_title` appear verbatim.
  - The arsc is stored, not compressed, so this is direct APK bytes.
  - There are 0 `getIdentifier(` calls in the repo.
- **Recommendation:** collapse resource names in release with `aapt2 optimize --collapse-resource-names`, either through AGP's resource optimisation or a post-`optimizeReleaseResources` step. Saves about **120–130 KB**. It is safe because nothing looks up resources by name.

### A4. `FollowUpWorker` is stripped by R8 although its own comment says it still runs queued work. Severity: High (functional bug). Effort: S. Risk: Low
- **Evidence:**
  - `app/src/main/kotlin/app/parley/work/FollowUpWorker.kt:1-20` says it "still runs the ones set before, already in WorkManager's queue".
  - Nothing in main code references the class.
  - `usage.txt:53665` lists `app.parley.work.FollowUpWorker` as a whole class removed.
  - WorkManager's consumer rule is only `-keepnames class * extends ListenableWorker` ("Keep … if not removed during shrinking", configuration.txt:185).
  - Result: any follow-up enqueued by an older version fails in release with a class-not-found error in `WorkerFactory`.
- **Recommendation:** pick one of:
  - Add `-keep class app.parley.work.FollowUpWorker { <init>(...); }`.
  - Better: delete the worker and, in the maintenance migration, `cancelAllWorkByTag`/`cancelUniqueWork` the legacy jobs, after converting them to To-call entries.
- Also add a unit test that every `CoroutineWorker` subclass named in persisted work survives R8.

### A5. Two E.164 implementations, and the identity façade is bypassed. Severity: High (correctness/consistency). Effort: M. Risk: Med
- **Evidence:**
  - `PhoneNumbers.toE164` (`core/common/.../PhoneNumbers.kt:38,54-92`) is a hand-written heuristic with its own `CountryCodes` table of 73 lines: trunk prefixes, Mexico special cases, the NANP 10-digit rule.
  - `NumberText.toE164` (`NumberText.kt:31-38`) parses with libphonenumber first and falls back to the heuristic.
  - Call sites: `NumberText.toE164` 13, `PhoneNumbers.toE164` 23, `PhoneIdentity.e164` 0.
  - The `PhoneIdentity` façade's own documentation says "use that everywhere else". Yet `PhoneNumbers.same(` is called 29 times against `PhoneIdentity.same(` 12 times, and `PhoneNumbers.digits/clean` 40 times directly.
  - Further digit-only normalisers: `EmergencyPolicy.asciiDigits` (6 calls), `SyncWatchdog.digits` (`core/common/.../backup/SyncWatchdog.kt:220`), and 16 inline `filter { it.isDigit() }`.
  - Two paths for one number can produce different keys. Example: a national number that libphonenumber judges "possible" but the heuristic rejects.
- **Recommendation:**
  - Make `PhoneIdentity` the only public API, with `PhoneNumbers` made `internal`.
  - Route its `e164` through one implementation: libphonenumber with the heuristic as fallback, which is exactly `NumberText.toE164`.
  - Add a detekt `ForbiddenMethodCall` rule for `PhoneNumbers.*` outside `app.parley.common`.
- This removes about 60–100 lines. More importantly, it removes a class of key-mismatch bugs. Keep `PhoneKeyMigration` in mind for any change to stored keys.

### A6. UI talks to repositories directly: AppViewModel is a service locator and long work runs in composition scopes. Severity: High (architecture). Effort: L. Risk: Med
- **Evidence:**
  - `vm: AppViewModel` is a parameter in **143 files**.
  - Composables reach through `vm.c.<repo>` **542 times**. The largest are `vm.c.people` 69, `history` 63, `circle` 47, `contacts` 39 and `lists` 32.
  - There are only 6 ViewModels: App, ContactDetail, Editor, Keypad, Paste and Recents.
  - Composition-bound work: 132 `rememberCoroutineScope()`, 107 `produceState`, 129 `LaunchedEffect` and 231 `scope.launch` in `ui/`. Of the `scope.launch` calls, 128 hit `vm.c.*` within 3 lines, and 22 are writes (export, import, restore, save, delete).
  - Example: the vCard and CSV export in `ui/settings/SettingsPages.kt:389-410` runs in `rememberCoroutineScope`. Leaving Settings › Contacts mid-export cancels it and leaves a partial file.
  - The app scope `c.scope` exists and is used in only about 5 places, for example `LabelPolicySection.kt:248` and `CallHistorySettings.kt:78`.
- **Recommendation:**
  - (1) Writes and anything touching user files go through `c.scope` or WorkManager, behind a `UseCase` or the repository, never a composable scope.
  - (2) Introduce screen ViewModels for the 5 heaviest screens: ContactDetail is already half-done, then Blocking, Backup, Settings › Contacts and SharedLabels.
  - (3) Add a detekt rule forbidding `vm.c.` inside `@Composable` functions, baselined so the count only goes down.

### A7. Repositories are not main-safe, so UI layers re-dispatch (88 `withContext(IO)` in UI). Severity: Medium. Effort: M. Risk: Low
- **Evidence:**
  - `withContext(Dispatchers.IO)` appears 88 times in `app/ui`, 16 in `messaging` and 12 in `telecom`, against 254 in core/data.
  - `ContactsRepository` exposes 29 public non-suspend functions next to 26 suspend ones. The blocking ones run ContentResolver queries: `lookup`, `organizations`, `events`, `groups`, `labelTitlesOf`, `numbersOf`, `lookupKeys`, `rawIds`, and `accounts()` through `DeviceAccounts.targets`.
  - The UI repeats the same shape about 30 times: `produceState { value = withContext(IO) { runCatching { vm.c.x.y() }.getOrDefault(..) } }`. Examples are `ui/calls/CallFactsUi.kt:42`, `RingFactsUi.kt:40`, `journal/UndoStorageSheet.kt:81` and `calltime/CallTimeScreen.kt:78`.
  - 472 hard-coded `Dispatchers.IO` and 71 `Dispatchers.Default`, with no injected dispatcher.
- **Recommendation:**
  - Make repository read APIs `suspend` and main-safe. Keep blocking variants `internal` for the telecom hot path, or name them `…Blocking`.
  - Add `@Composable fun <T> rememberLoad(key, default, block: suspend () -> T)` to core/ui. It saves about 2 lines per call site (about 60 lines) and centralises error policy.
  - Inject an `IoDispatcher` through `DataContainer` so tests can use a `StandardTestDispatcher`.

### A8. `runCatching` everywhere swallows cancellation; error handling is unprincipled. Severity: Medium. Effort: M. Risk: Low
- **Evidence:**
  - 1,011 `runCatching` in production: app 397, data 406, telecom 141, common 67.
  - Another 236 `catch (_: X)` and 201 `catch (e: Exception)`.
  - Only 40 production references to `CancellationException`. None of the `runCatching` sites rethrow it, so `runCatching { withContext/await/delay }` inside coroutines turns cancellation into a "failure" value (6 direct instances found by regex, many more indirect through suspend repo calls).
  - 13 toasts show raw `e.message` to users, for example `ui/history/ExportSheet.kt:61`, `ClearHistoryDialog.kt:147`, `SettingsPages.kt:510` and `SelectionBar.kt:232`.
  - The detekt baseline still carries 42 `TooGenericExceptionCaught` and 13 `SwallowedException` entries.
- **Recommendation:**
  - Add `inline fun <T> catching(block: () -> T): Result<T>` to core/common. It rethrows `CancellationException` and, optionally, logs through `CrashStore`.
  - Ban bare `runCatching` in suspend code with a custom detekt rule.
  - Map exceptions to user strings in one place, a `UserError` mapper, rather than interpolating `message`.

### A9. ContactDetailScreen is a 936-line composable with 21 dialog flags. Severity: Medium (maintainability/recomposition). Effort: M. Risk: Low
- **Evidence:**
  - `ui/contact/ContactDetailScreen.kt:222` declares `ContactDetailScreen`, which runs to line about 1158 (936 lines).
  - Lines 234-272 hold 21 `var … by remember { mutableStateOf(false/null) }` dialog or sheet flags: `menu`, `confirmDelete`, `showQr`, `simFor`, `pinDialog`, `reachOut`, `secureQr`, `copyToSim`, `messageSheet`, `webLink`, `editNote`, `logDialog`, `peekNumber`, `reasonFor`, `editEntry`, `confirmPrivate`, `confirmVisible`, `confirmPrivateQr` and others.
  - The screen takes `AppViewModel`, although a `ContactDetailViewModel` (533 lines) exists.
  - Other mega-composables:
    - `BlockingScreen` 533 lines (`ui/blocking/BlockingScreen.kt:126`)
    - `KeypadTab` 441 (`ui/home/KeypadTab.kt:189`)
    - `BackupScreen` 291
    - `RuleEditorScreen` 268
    - `BulkAddScreen` 268
    - `ContactEditScreen.fields` 210
- **Recommendation:**
  - Replace the flags with one `sealed interface DetailDialog` held in the ViewModel, and render dialogs with a single `when`.
  - Split the screen into section composables: header, phones, timeline and the dialog host.
  - Expect −100 to 150 lines and much smaller recomposition scopes. Apply the same pattern to BlockingScreen and KeypadTab.

### A10. CallManager is a 1,691-line global `object` with eight responsibilities. Severity: Medium. Effort: L. Risk: Med–High (call path)
- **Evidence:**
  - `telecom/.../CallManager.kt:82` declares `object CallManager`: 131 functions, 67 imports and mutable maps of sessions, ids and timers. Tests reach it through `resetForTest` (line 1523).
  - The sections are:
    - session registry (88-243)
    - screening and auto-answer (175-243)
    - ringer, vibration and in-car announcement (361-500)
    - UI mapping and texts (616-1000, about 380 lines of `toUi`, `rangThroughText`, `dropText`, `disconnectText` and others)
    - end facts and quality recording (833-997, 1183-1230)
    - actions (1006-1250)
    - hold mode (1253-1303)
    - redial (1303-1340)
  - It has good test coverage: 15 test files.
- **Recommendation:** extract pure pieces first, which are low risk because they are behaviour-preserving moves:
  - `CallUiMapper`, about 380 lines, mostly pure functions of `CallSession`/`Call.Details` that can be unit-tested without Telecom.
  - `RingerControl`, about 140 lines.
  - `HoldMode`, about 50 lines.
  - Leave the session registry and actions in `CallManager`. Do not convert the `object` to a class in the same release.

### A11. ContactsRepository is a 1,419-line god class with a 260-line `save()`. Severity: Medium. Effort: M. Risk: Med
- **Evidence:**
  - `core/data/.../ContactsRepository.kt` has 91 functions and 72 imports.
  - `save()` runs from line 749 to about 1010, with 8 local helper functions (`insert`, `update`, `delete`, `single`, `multi`, `handleRow`, `cv`, `nameOf`).
  - The class also covers listing and caching (176-360), caller lookup (364-470), detail load (501-612), accounts, groups, aggregation join/separate (1168-1253), vault purge (1288), URIs and journal.
- **Recommendation:** split into four classes:
  - `ContactsReader` (list and details)
  - `ContactWriter` (save and diff, already testable through `ContactsRepositoryWriteTest`)
  - `ContactGroups`
  - `ContactAggregation`
- The `save()` diff logic (the multi-row diff at 884-917) belongs in core/common as a pure `RowEdits` planner, which partly exists already, so it can get JVM tests.

### A12. Notification builders are copy-pasted about 10 times. Severity: Medium (duplication). Effort: S. Risk: Low
- **Evidence:**
  - 29 `NotificationCompat.Builder(` calls in 12 files and 23 `NotificationChannel(` creations in 11 files.
  - `ReminderChannels.kt` (56 lines) exists but is used only by some callers.
  - `work/SyncWatchdogNotice.kt:33-70`, `work/FolderSyncNotice.kt:42-70` and `work/BackupReminder.kt:30-60` are near-identical, about 30 lines each. Each sets:
    - a public version with title only
    - `BigTextStyle`
    - `VISIBILITY_PRIVATE`
    - `setLocalOnly`
    - `setAutoCancel`
    - a `PendingIntent.getActivity(MainActivity, action)` with a magic request code (78, 79, 80, 81…)
    - a `try { notify } catch (SecurityException)`
  - The same shape appears in `HistoryWorker.kt:62-78`, `MaintenanceWorker.kt:82-89`, `BackupWorker.kt:59-66`, `DueTemporaries.kt:107-118`, `RemindersWorker.kt:177-181` and `FollowUpWorker.kt:57-68`.
  - Request codes overlap ad hoc: 30 / 30+i / 30+req, and 80 / 80+i / 81 / 81+ordinal (`MissedCallNotifier.kt:124,145`, `BlockingNotifier.kt:122`, `DueTemporaries.kt:112,160`, `SyncWatchdogNotice.kt:40`, `CallNotifier.kt:339`). They only stay distinct because the actions differ.
- **Recommendation:**
  - Add `PrivateNotice.post(context, channel: NotificationRegistry.Channel, id, title, text, publicTitle, openAction, priority)` to the app.
  - Put channel creation and request codes into the existing `NotificationRegistry` (`core/common`, 152 lines).
  - Saves about **200–250 lines** and makes the lock-screen privacy policy uniform.

### A13. Settings texts are kept in three parallel copies, which already disagree. Severity: Medium. Effort: M. Risk: Low
- **Evidence:**
  - `core/common/.../SettingsCatalog.kt` (501 lines) holds English `title`, `summary` and `keywords` for about 170 entries.
  - `app/.../ui/settings/SettingsText.kt` (265 lines) maps 166 keys to `Triple(R.string.*_title, *_summary, *_kw)`.
  - `strings_settings.xml` holds 443 `set_*_title|summary|kw` strings, plus 30 `_kw` strings in other files.
  - Of the 95 entries parsed, **21 catalog titles differ from the displayed resource**, so search matches text the user never sees.
  - The English copy only existed for multilingual search, but the app has been English-only since 4.6 (`localeFilters += "en"`).
- **Recommendation:**
  - Keep only keys, category and place in `SettingsCatalog`.
  - Generate or derive the `SettingsText` map from a naming convention (`set_<key>_title`, and so on), or move the catalog into app as one table of `SettingRow(key, @StringRes title, …)`.
  - Saves about **450–550 Kotlin lines** and removes the drift.

### A14. 227 raw `ListItem(` calls bypass the core/ui rows. Severity: Medium (consistency). Effort: M. Risk: Low
- **Evidence:**
  - Adoption so far: `ParleyListItem` 82, `SwitchRow` 48, `LinkRow` 30 and `ChoiceRow` 4, against **227 raw `ListItem(`** calls and 24 raw `Switch(` (11 hand-rolled switch rows inside a ListItem).
  - 116 raw ListItems carry `Modifier.clickable`, which makes them de-facto LinkRows.
  - Raw ListItems skip `rowColors()` and the list-density modifier, so rows look and size differently across screens.
  - Worst files: `ui/people/PrivacyScreens.kt` 10, `ui/backup/BackupScreen.kt` 10, `ui/blocking/BlockingScreen.kt` 9, `ui/sync/FolderSyncScreen.kt` 7 and `ui/extras/LabelPolicySection.kt` 7.
- **Recommendation:**
  - Extend the detekt `DesignSystemComponent` map with `"ListItem" to "ParleyListItem/InfoRow/LinkRow"` and `"Switch" to "SwitchRow"`.
  - Migrate file by file. Each migrated row saves 2–4 lines, about **400 lines** in total.

### A15. Contact and person rows are reimplemented 26 times, and "primary phone" 17 times. Severity: Medium. Effort: M. Risk: Low
- **Evidence:**
  - 26 `leadingContent = { Avatar(` rows, for example:
    - `ui/history/PeopleCard.kt:199` (`ContactLine`)
    - `ui/history/InsightsScreen.kt:265` (`PersonRow`)
    - `ui/calls/ToCallScreen.kt:204`
    - `ui/birthdays/BirthdaysScreen.kt:103`
    - `ui/family/FamilySafetyScreens.kt:272,330`
    - `ui/circle/CircleTab.kt:222`
    - `ui/temporary/TemporaryContacts.kt:340`
    - `picker/PickerScreen.kt:133`
    - `telecom/.../InCallScreen.kt:1063`
    - and others
  - The canonical `ContactRow` lives in a feature file (`ui/home/ContactsTab.kt:218`), not in core/ui.
  - `phones.firstOrNull { it.isPrimary } ?: phones.firstOrNull()` is repeated 17 times, with no `ContactSummary.primaryNumber`.
  - `Section()` is a shared header defined in `ui/contact/ContactDetailScreen.kt:1242` and imported by 25 files. Four more private header wrappers exist: `PeopleCard.kt:190`, `QualityCard.kt:132` (a different style), `ToCallScreen.kt:185` and `BulkAddScreen.kt:401`.
- **Recommendation:**
  - Add `PersonRow(name, photoUri, supporting, isCompany, badge, trailing, onClick, onLongClick)` to core/ui (core/ui already owns `Avatar`).
  - Add a `primaryNumber` extension in core/common.
  - Move `Section` to core/ui next to `ListSectionHeader`.
  - Saves about **250 lines**.

### A16. Six clipboard-write and five clipboard-read helpers behave differently. Severity: Medium (privacy consistency). Effort: S. Risk: Low
- **Evidence (writers):**
  - `ui/common/Intents.kt:46` sets sensitive by default, uses the pre-33 literal key and is wrapped in `runCatching`.
  - `ui/qr/QrActions.kt:30` is not sensitive by default and only marks sensitive on API 33+.
  - `messaging/MessengerLauncher.kt:59` has **no try/catch**.
  - `messaging/ReachSheet.kt:496` is inline with no try/catch.
  - `ui/people/CopyAsText.kt:38-47` is inline and also toasts on 33+, so the user sees a double confirmation.
  - `telecom/.../InCallScreen.kt:997` is another copy.
- **Evidence (readers):**
  - `KeypadTab.kt:730-753`
  - `PasteDetails.kt:235`
  - `AddressMapLinks.kt:221`
  - `NumberActionActivity.kt:497`
  - `BulkAddScreen.kt:283`
- **Recommendation:** put `Clipboard.copy(context, text, label, sensitive = true, confirm = true)` and `Clipboard.readText(context, max)` in core/ui, which telecom can also use. Saves about 70 lines and gives one sensitive-flag policy.

### A17. Six safe-`startActivity` helpers plus 40 inline variants. Severity: Low–Med. Effort: S. Risk: Low
- **Evidence:**
  - The helpers:
    - `QrActions.start` (`ui/qr/QrActions.kt:46`)
    - `Intents.launch` (`ui/common/Intents.kt:16`)
    - `Context.startSafely` (`ui/settings/SettingsPages.kt:184`)
    - `ContactMessaging.start` (`ui/contact/ContactMessaging.kt:117`)
    - `AddressMapLinks.launch` (`:124`)
    - `BlockingActions.launch` (`blocking/BlockingActions.kt:72`)
  - Inline variants: 28 `runCatching { startActivity` and 12 `catch ActivityNotFoundException`.
- **Recommendation:** one `Context.startOrSay(intent, @StringRes missing): Boolean` in core/ui. Saves about 60 lines.

### A18. Three near-identical "sealed per-line facts" stores. Severity: Medium. Effort: M. Risk: Med (stored data)
- **Evidence:**
  - `core/data/.../calls/CallQualityStore.kt` (164 lines), `ReputationStore.kt` (138) and `RingFactsStore` (in `CallExtrasRepository.kt`, 168) each re-implement the same machinery:
    - the `KeySource`/`Keys` interface (`lineMac`, `seal`/`open` through `CallHistory.sealAux`)
    - the `Loaded(rows, unread)` cache
    - the MAC-prefixed key
    - `MAX_ROWS` and `KEEP_DAYS` pruning
    - a `version` StateFlow
    - prefs JSON
  - After renaming, `CallQualityStore` and `RingFactsStore` differ in only 176 lines out of about 330.
- **Recommendation:** use a generic `SealedLineStore<T>(file, KSerializer<T>, maxRows, keepDays, timestampOf)`. Saves about **250 lines**. Keep the on-disk format byte-identical and cover it with the existing `CallQualityStoreTest`/`ReputationStoreTest`.

### A19. AES-GCM, HKDF and Keystore wrappers are duplicated across 6 crypto files. Severity: Medium (security review surface). Effort: M. Risk: High (format compatibility)
- **Evidence:**
  - These pairs are identical:
    - private `gcm(mode,key,nonce,data,aad)` in `common/sync/shared/SharedLabelCrypto.kt:139-144` and in `common/backup/SyncCrypto.kt:131-136`
    - `ensure()` in both files
  - `BackupCrypto.kt:486-512` has its own `gcmSeal`/`gcmOpenOrNull`/`hkdf`.
  - `common/blocking/ListImport.kt:226-233` has its own PBKDF2 and GCM, ignoring `backup/Kdf.kt`.
  - Five Keystore-wrapped-key implementations: `HistoryCrypto.kt` (`wrap`/`unwrap` 49-63), `VaultCrypto.kt`, `KeystoreMemoryKeys.kt`, `DeviceSigner.kt` and `PersistentStores.kt`.
- **Recommendation:**
  - Add a single `Aead` object to core/common: `seal(key, plain, aad, random)` returning nonce‖ct, and `openOrNull`. Add `Hkdf` and reuse `Kdf` for ListImport.
  - Add `KeystoreKey(alias)` to core/data for wrap and unwrap.
  - Do not merge key domains or change any wire format. This is a pure helper extraction, with the existing `CryptoRoundTripTest`, `BackupRoundTripTest` and `RecordSealingTest` as gates.
  - Saves about 120 lines and shrinks the audit surface noticeably.

### A20. Two JSON stacks and 33 hand-configured `Json {}` instances. Severity: Low–Med. Effort: M. Risk: Med
- **Evidence:**
  - kotlinx.serialization: 33 `= Json {` in core/common with mixed options (`ignoreUnknownKeys` ×32, `encodeDefaults=false` ×17 against `true` ×7, `explicitNulls` mixed), 146 `@Serializable` and 40 `fun decode(String?)` wrappers.
  - org.json: 27 files and 182 `JSONObject(` in core/data, hand-written.
  - `ContactDetails` alone has two hand-written codecs with different key schemes: `core/data/.../ContactDraftJson.kt` (109 lines) and `ContactDetailsJson.kt` (94, vault).
  - With `RecordJson` and `VCardMapper`, adding a contact field means editing 4 or more mappers, and no reflection test checks that every field round-trips.
- **Recommendation:**
  - (1) `object Codecs { val lenient = Json{ignoreUnknownKeys=true; encodeDefaults=false; explicitNulls=false}; val full = … }` plus `class JsonCodec<T>(serializer, default, onError)`. Saves about 150 lines.
  - (2) A test that builds a `ContactDetails` with every property non-default (through reflection) and asserts round-trip through both JSON codecs, vCard and backup.
  - (3) Longer term, move core/data's org.json blobs to kotlinx with `@SerialName` keeping the existing keys.

### A21. Circle and Favourites widgets duplicate their lifecycle plumbing. Severity: Low. Effort: S. Risk: Low
- **Evidence:**
  - `shortcuts/CircleWidget.kt` (292 lines) and `FavoritesWidget.kt` (409 lines) duplicate:
    - `onUpdate`/`onDisabled`/`onAppWidgetOptionsChanged`/`onReceive`
    - `refreshAsync`
    - `ids()`
    - `@Volatile shownLocked`
    - `refreshIfShownLocked`
    - `refresh()` with `withTimeoutOrNull`
    - the `observe()` screen-unlock receiver
  - The `observe()` blocks differ in only 27 lines.
- **Recommendation:** an abstract `LockAwareWidget<Content>` base class with `load()` and `views()` hooks. Saves about 100 lines.

### A22. Strings: 5,646 strings, 742 duplicate copies, 54 unreferenced (24 dead vault UI). Severity: Low (size), Medium (maintenance). Effort: S–M. Risk: Low
- **Evidence:**
  - 5,646 `<string|plurals|string-array>` definitions. The largest files are `strings_data.xml` 1,318, `strings_settings.xml` 796, `strings_main.xml` 715 and `strings_blocking.xml` 701.
  - **742 exact-duplicate values**. App alone has 4,801 strings with 4,155 distinct values.
  - 106 near-duplicate groups differing only in case or punctuation ("Save" against "Save…", "Call %1$s" against "Call %1$s?").
  - Generic verbs are defined repeatedly: "Call" ×16, "Not now" ×9, "Remove" ×8, "Cancel" ×8, "Open" ×8, "Message" ×8, "Delete" ×7. That is 99 surplus copies of 20 verbs.
  - 14 telecom strings duplicate app strings verbatim (`incall_emergency_call` = `lock_emergency_call`).
  - 54 strings are referenced nowhere. 24 of them are `vault_*` in `strings_data.xml` (`vault_title`, `vault_call`, `vault_sms`, `vault_about`…), left over from removed Vault screens: `ui/vault` is now 37 lines. Others are `edit_add_address`, `edit_add_date`, `me_add_number` and `incall_slide_to_answer`.
  - R8 already drops unreferenced strings from the APK, and aapt2 deduplicates identical values, so the APK gain is small (under 10 KB arsc).
- **Recommendation:**
  - Delete the 54 strings.
  - Add `core/ui` common action strings (`action_call`, `action_cancel`, …) and migrate when touching files.
  - Turn on lint `UnusedResources` as an error with `checkDependencies = true` in app.
  - Saves about 800 resource lines over time.

### A23. Dead and test-only code. Severity: Low. Effort: S. Risk: Low
- **Evidence:**
  - 52 functions are never referenced in main code, and 25 of those are not referenced by tests either. Examples:
    - `ScreenOutcome.isBoosted` (telecom)
    - `BlockingHooks.ExpectingCallMenuItem` (`ui/blocking/BlockingHooks.kt:291`)
    - `RecentsTab.callTypeIcon` (`:464`)
    - `DetailParts.groupRowColors` (`:81`)
    - `CircleMemory.rememberPersonMemory` (`:79`)
    - `PeopleUi.moveFavorite` (`:155`)
    - `BlockingText.replaySummary` (`:279`)
    - `core/ui` `ltrOrNull`, `bottomOnly`, `slowSpatial`
    - `ContactsRepository.newContactTarget` (`:622`), `deleteUnjournaled` (`:1092`), `contactUri` (`:1354`)
    - `CallLogRepository.deleteAll`
    - `AppDatabase.findByHmac` (`:554`)
    - `VaultCrypto.randomBytes`
  - Classes used only by tests or removed by R8:
    - `CallRoute`/`CallRoutes` (`common/ReachApps.kt`)
    - `ContactConversion` (`people/ContactVariants.kt`)
    - `ImProtocol` (`people/Handles.kt`)
    - `LabelMerge` (`people/LabelFilter.kt`)
    - `AccountCount` (`data/people/PeopleIndex.kt`)
    - `CirclePlannerDefaults` (`ui/circle/CircleContact.kt`)
    - `InMemoryBlobStore` (a test fake in main, `backup/Snapshots.kt`)
  - Correction to R8's view: `CsvPackConverter`, `ReportTally` and `FtcDncSource` are used by `:lists-updater`, so they are not dead.
- **Recommendation:** delete them, or move the fakes to `testFixtures`, in about 300–400 lines.

### A24. Mixed persistence: Room, 6 DataStores, 54 SharedPreferences files and loose files. Severity: Low–Med. Effort: M–L. Risk: Med
- **Evidence:**
  - 54 `getSharedPreferences(` calls in 39 files, each with its own file name (`vault_keys`, `ux`, `sync_watch`, `record_crypto`, `messaging`, `me_card`, `folder_sync`, `favorites_widgets`…).
  - 6 Preferences DataStores.
  - `SettingsRepository.kt:150-258` hand-maps 55 keys twice (`toSettings`, `write`).
  - DataStore plus its protobuf costs 112 KB of dex, only for settings.
- **Recommendation:**
  - Short term: make the settings mapper table-driven (`Field(key, get, set)`), saving about 60 lines.
  - Medium term: move the 6 DataStores to SharedPreferences behind a tiny `Flow` wrapper, since prefs are already the majority. This removes the datastore dependency (**about 110 KB dex**) and makes behaviour uniform. A one-time migration is needed.

### A25. Unneeded or over-broad dependencies and keep rules. Severity: Low (size). Effort: S. Risk: Low
- **`androidx.fragment`** (21 KB dex):
  - It is used only so `LockedActivity` can extend `FragmentActivity` (`security/LockedActivity.kt:32`), plus `as? FragmentActivity` casts in 8 files.
  - No Fragment API is used, and `AppLock` uses the platform `android.hardware.biometrics.BiometricPrompt` (`AppLock.kt:37`).
  - Switch to `ComponentActivity` and drop the dependency.
- **ezvcard keep rules** (`app/proguard-rules.pro:2-3`):
  - `-keep class ezvcard.property.** { *; }` and `ezvcard.io.scribe.** { *; }` pin 93 KB (property 48 KB, scribe 45 KB) unshrunk and unobfuscated.
  - Narrow them to `-keepclassmembers class ezvcard.property.** { <init>(...); }` (needed for `VCardProperty.copy()` reflection) and test a release round-trip. Estimated gain 30–60 KB.
- **`api(libs.ezvcard)`** in `core/common/build.gradle.kts`: ezvcard is imported in only 3 core/common files. Use `implementation` to stop the API leak into telecom and app.
- **`:lists-updater`** depends on all of `:core:common` but imports only `app.parley.common.spam.*` (10 classes). Its 2.9 MB release APK carries 124 KB of libphonenumber metadata and ezvcard resources. Extract a `:core:spam` module, which also shrinks the internet-permission app's attack surface.
- **x86/x86_64 copies of `libandroidx.graphics.path.so`**: about 20 KB, so `abiFilters` is optional.
- **navigation-compose**: 172 KB for a single `NavHost` with 18 `toRoute<`. A hand-rolled typed back stack would save about 150 KB but is L effort. Low priority.
- **material3 `1.5.0-alpha14`** is pinned for Expressive APIs used in 5 files (11 `ExperimentalMaterial3ExpressiveApi`). Note this as a stability risk, not a size one.

### A26. The baseline profile is hand-written only, and the generated profile was never committed. Severity: Low–Med (perf). Effort: S (needs a device). Risk: Low
- **Evidence:**
  - `app/src/main/baseline-prof.txt` is 77 lines of wildcard rules (`HSPLapp/parley/data/ContactsRepository**->**(**)**`).
  - `app/src/release/generated/baselineProfiles/` does not exist, although `:baselineprofile` has 9 journey and benchmark classes.
  - There is no startup profile, so no dex layout optimisation.
  - The APK's `assets/dexopt/baseline.prof` is 9.7 KB.
- **Recommendation:** run `:app:generateBaselineProfile` on an emulator in CI (a managed device), commit the result, and enable `dexLayoutOptimization` with the startup profile. Wildcards over god classes such as `ContactsRepository**` precompile cold code too, so narrow them after generation.

### A27. Test coverage is lopsided; security and UI-heavy app code is untested. Severity: Medium. Effort: M–L. Risk: n/a
- **Evidence:**
  - app's test/main ratio is 0.08 and there is no coverage tool (no kover or jacoco in any `*.kts`).
  - These large files have no test that references them:
    - `ContactEditScreen` 1,349 lines
    - `ContactDetailScreen` 1,257
    - `SettingsPages` 780
    - `BlockingScreen` 734
    - `QrResultSheet` 728
    - `SharedLabelScreens` 705
    - `ReachSheet` 673
    - `AppTelecomDependencies` 629 (the whole telecom↔data adapter)
    - `IncomingControls` 600
    - `security/AppLock.kt` 477 (PIN, duress and biometric flows; only `ExportedComponentsTest` touches it)
    - `CircleRepository` 460
    - `CardStores` 454
  - lists-updater has 0 tests.
  - core/common is well covered (1,745 tests).
- **Recommendation:**
  - Add Kover with per-module thresholds, ratcheting them.
  - Priority tests:
    - (1) `AppTelecomDependencies` contract tests against fake repos.
    - (2) `AppLock`/`PinEntry` state machine, after extracting its logic to core/common.
    - (3) `CircleRepository`.
  - UI files get easier to test after A6 and A9 move logic into ViewModels.

### A28. Smaller consistency items
- **Static-analysis debt:** the detekt baseline holds 974 entries: 436 MaxLineLength, 198 CyclomaticComplexMethod, 65 LongMethod, 44 LoopWithTooManyJumpStatements, 43 ComplexCondition and 42 TooGenericExceptionCaught. Ratchet it with a CI check that the baseline only shrinks.
- **`TelecomDependencies` defaults hide gaps:** the interface (`telecom/.../TelecomGraph.kt`, 361 lines) has about 60 members, most with no-op defaults, so a forgotten override silently disables a feature. Split it into the existing hook interfaces and remove the defaults from the app-implemented ones.
- **Gradle copy-paste:** the permission allow-list tasks in `app/build.gradle.kts` and `lists-updater/build.gradle.kts` are duplicated (about 40 lines each). Move them to a `build-logic` convention plugin.
- **No Compose stability config:** core/common is a JVM module without the Compose compiler, so `ContactSummary`, `CallEntry` and `AppSettings` (with `List` fields) are unstable for Compose. Strong skipping limits the damage, but a `stability_config.conf` listing `app.parley.common.**` would let skipping use equality. Effort S.

---

## Size and LOC summary (estimates)

| Lever | APK saving | Lines saved |
|---|---|---|
| A1 compress dex | **−4.7 MB** (download/APK file) | 0 |
| A2 drop 86_en and 61_en geocoder files | −560 KB (−1.08 MB if country-only) | 0 |
| A3 collapse resource names | −120 to −130 KB | 0 |
| A24 DataStore → prefs | −110 KB | ~60 |
| A25 ezvcard keep narrowing, drop fragment | −50 to −80 KB | ~10 |
| A13 settings text single source | small | ~500 |
| A14 + A15 rows / PersonRow / Section | ~20–40 KB dex | ~650 |
| A12 notifications | ~10 KB | ~220 |
| A18 sealed line stores | ~10 KB | ~250 |
| A20 JSON codecs | small | ~150 |
| A19 crypto helpers | small | ~120 |
| A16 + A17 clipboard / start helpers | small | ~130 |
| A21 widgets | small | ~100 |
| A23 dead code | ~15 KB | ~350 |
| A9 + A10 + A11 splits | ~0 | ~300 net (mainly maintainability) |
| **Total** | **≈ −5.6 MB file size (11.2 → ≈5.6 MiB)** | **≈ 2,800–3,000 lines (≈2.3% of main)** |

Rule of thumb from this build: Parley code costs about 50 B of dex per source line (4.79 MB for about 96k app, telecom, common and data lines), so 3k lines is roughly 150 KB of dex.

## Ranked top 15 (benefit ÷ risk)

1. **A4** Fix the R8-stripped `FollowUpWorker`. This is a real release bug. S, low risk.
2. **A1** Compress dex: −4.7 MB APK for F-Droid and sideload users. S, low risk; decide the trade-off and change the budget metric.
3. **A5** One E.164/identity path, with `PhoneNumbers` made internal. Correctness. M, medium risk.
4. **A6** Move writes and exports out of composition scopes, and stop `vm.c.*` in composables (ratchet with detekt). L, medium risk; start with exports and imports.
5. **A8** A cancellation-safe `catching {}` plus a detekt ban on `runCatching` in suspend code, and stop showing raw `e.message`. M, low risk.
6. **A2** Exclude `86_en` and `61_en` geocoder data: −560 KB. S, low risk.
7. **A12** A `PrivateNotice` helper plus `NotificationRegistry` request codes: −220 lines. S, low risk.
8. **A13** A single source for settings texts: −500 lines, and fixes the 21 drifted titles. M, low risk.
9. **A14 + A15** Extend the detekt map to `ListItem`/`Switch`, add `PersonRow`, `primaryNumber`, and `Section` in core/ui: −650 lines and consistent density and colours. M, low risk.
10. **A16** One clipboard helper with a consistent sensitive flag, also fixing the uncaught `setPrimaryClip` calls. S, low risk.
11. **A3** Collapse resource names: −125 KB. S, low risk.
12. **A9** ContactDetailScreen dialog state as a sealed class, and split it into sections. M, low risk.
13. **A18 + A19** A generic sealed line store and shared AEAD/HKDF/Keystore helpers: −370 lines and a smaller crypto-review surface. M; high risk only if formats change, so keep them byte-identical.
14. **A27** Add Kover. Test `AppTelecomDependencies`, `AppLock` and `CircleRepository`. M.
15. **A25 + A24** Drop `androidx.fragment`, narrow the ezvcard keeps, `implementation(ezvcard)`, `:core:spam` for lists-updater, then DataStore → prefs: −160 to −190 KB. S–M.

## Summary (≤300 words)

Parley's architecture boundaries are sound:
- core/common is pure JVM.
- telecom never touches core/data.
- A custom detekt rule keeps dialogs, sheets and top bars on core/ui.

The debt is in the app module and in repeated plumbing:
- **App module coupling:** 143 screens take the shared `AppViewModel` and call repositories directly (`vm.c.*`, 542 sites), with only 6 ViewModels. 132 composition-scoped coroutine scopes run writes and exports, so leaving Settings mid-export cancels it. Repositories are not main-safe, which forces 88 `withContext(IO)` calls in UI code.
- **Error handling:** about 1,000 `runCatching` calls swallow coroutine cancellation.
- **God units:** a 936-line `ContactDetailScreen` composable with 21 dialog flags, `CallManager` (1,691 lines, a global object) and `ContactsRepository` (1,419 lines, with a 260-line `save()`).
- **Correctness risks:**
  - `FollowUpWorker` is removed by R8 in release, although its own comment says it still runs queued work.
  - Two E.164 implementations coexist, and the `PhoneIdentity` façade is bypassed about 70 times.
- **Duplication:**
  - about 10 copy-pasted private notifications
  - 6 clipboard writers and 5 readers with different privacy flags
  - 6 safe-start helpers
  - 3 near-identical sealed per-line stores
  - duplicated AES-GCM/HKDF/Keystore helpers
  - 33 `Json {}` configs plus two hand-written ContactDetails codecs
  - 26 hand-rolled contact rows
  - 227 raw `ListItem`s bypassing core/ui rows
  - settings texts kept three times, with 21 already drifting
  - 742 duplicate string values; 54 strings and about 25 functions are dead
- **Consolidation payoff:** about 2.8–3k lines can go without losing features.
- **APK size:** the single biggest lever is that `classes.dex` is stored uncompressed (9.4 MB). Enabling legacy dex packaging cuts the APK from about 11.2 to about 6.5 MiB, at the cost of on-device extraction. Further cuts:
  - China and Australia geocoder files (−560 KB)
  - collapsed resource names (−125 KB)
  - DataStore → prefs (−110 KB)
  - narrower ezvcard keep rules
  - dropping the unused `androidx.fragment`
- **Tests:** strong in core/common (1,745) but weak in app (ratio 0.08, no coverage tool). `AppTelecomDependencies`, `AppLock` and `CircleRepository` are untested.
- **Baseline profile:** hand-written wildcards only; no generated profile has been committed.
