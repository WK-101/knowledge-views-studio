# Performance: baseline profile, benchmarks and budgets

Parley ships a baseline profile so the code people wait on (start-up to Recents, a contact's page, the keypad, the
incoming-call screen)
is compiled ahead of time at install, rather than interpreted until the phone's background dexopt gets to it. The
`androidx.profileinstaller` library installs it on sideloaded and F-Droid installs too, which get no cloud profiles.

## What is in the repository

| Path | What |
|---|---|
| `app/src/main/baseline-prof.txt` | A hand-written profile covering start-up (`ParleyApp`, the data container, `MainActivity`, `AppViewModel`, the home screen with Recents, the keypad and Contacts), a contact's page (its view model, sections and the photo as picked) and the call path (`CallManager`, `CallNotifier`, the screening service, the in-call screen). It works without a device, so profile installation works now. |
| `app/src/release/generated/baselineProfiles/` | The measured profile and startup profile, once generated on a device (below). Merged with the hand-written one. |
| `baselineprofile/` | A `com.android.test` module with the `androidx.baselineprofile` plugin: the generator (`BaselineProfileGenerator`) and the macrobenchmarks. CI compiles and packages it (`:baselineprofile:assembleBenchmarkRelease`), so it can't rot between device runs. |

The benchmarks:

| Test | Measures |
|---|---|
| `StartupBenchmark` | Cold start to Recents with 3000 calls and 3000 contacts (`StartupTimingMetric`), without a profile and with it. |
| `RecentsScrollBenchmark` | Frame timing while flinging Recents with 3000 calls. |
| `ContactPageBenchmark` | A contact's page opened from another app ("view contact"), from a cold process, with 3000 contacts and 3000 calls: time to the first frame (`StartupTimingMetric`) and the page's frames while it loads and scrolls. |
| `KeypadTypingBenchmark` | Frame timing while typing a number on the keypad with 3000 contacts to search. |
| `IncomingCallBenchmark` | On an emulator: Parley's `Parley.addToNotification` and `Parley.screenCall` trace sections and the call screen's frames for an incoming call, from a cold process. Skipped without an emulator console token. |

Each test runs twice: `CompilationMode.None()` (what a first launch without a profile gets) and
`CompilationMode.Partial(BaselineProfileMode.Require)` (with the profile), so the gain is visible.

## Regenerating the profile on a device

1. Use a test phone or an emulator (Android 10 or later; an emulator image with Google APIs is fine). The generator and
   the benchmarks **add 3000 contacts and 3000 call-log entries** (numbers starting +1 555 01, a range reserved for
   fiction) the first time they run. Don't run them on your own phone.
2. Install nothing by hand: Gradle builds and installs the `nonMinifiedRelease` / `benchmarkRelease` variants itself.
   Each journey grants Parley its permissions and makes it the default phone app through the shell (`pm grant`,
   `cmd role add-role-holder`), so onboarding is skipped and nothing needs tapping.
3. Generate:

   ```sh
   cd parley-phone
   ./gradlew :app:generateBaselineProfile
   ```

   This writes `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`. Review the diff
   and commit them. Keep `app/src/main/baseline-prof.txt` as the fallback for code the journeys don't reach.

4. For the incoming-call journey in the profile and the incoming-call benchmark, pass the emulator console token (the
   benchmark APK rings the emulator through its console at 10.0.2.2:5554; Parley itself still has no network access):

   ```sh
   ./gradlew :app:generateBaselineProfile \
     -Pandroid.testInstrumentationRunnerArguments.consoleToken="$(cat ~/.emulator_console_auth_token)"
   ```

   Add `-Pandroid.testInstrumentationRunnerArguments.consolePort=5556` for a second emulator.

## How to run on a device

One command runs every benchmark, each without and with the profile:

```sh
cd parley-phone
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

What it needs and does:

- **One device connected** over USB or `adb connect` (`adb devices` lists exactly one), Android 10 or later, unlocked,
  screen on and staying on (Developer options › Stay awake), not in battery saver. It must be a **test device**: the
  run adds 3000 contacts and 3000 calls, makes Parley the default phone app and replaces an installed Parley (the
  benchmark build is signed with the debug key, so uninstall a release-signed Parley first).
- Gradle builds the `benchmarkRelease` variant (R8 like the release, debug-signed), installs it with the test APK, runs
  the classes above and uninstalls both. A full run takes about 20 to 30 minutes.
- `IncomingCallBenchmark` runs only on an emulator with the console token (step 4 above); elsewhere it is skipped.

Which devices, and why:

| Class | Example | Use it for |
|---|---|---|
| Mid-range, current Android | A Pixel "a" model or a Galaxy A5x | The targets in docs/AUDIT.md §5 (cold start < 400 ms, ring to call screen < 300 ms, < 1% slow frames) are for this class. |
| Low-end, Android 10 to 12 | A phone with 2 to 3 GB of RAM and eMMC storage (e.g. a Galaxy A0x or Moto E) | The worst case: start-up and the incoming call are slowest here, and the profile helps most. No target; watch for regressions. |
| Emulator (x86_64, Google APIs image) | Android Studio's Pixel 6 image, Android 14 | The incoming-call benchmark, and before/after comparisons on one machine. Not for absolute numbers. |

Run each class twice and keep the better run of each test (the first run after install also does background dexopt).
Then fill in the table below with the medians the JSON reports (`timeToInitialDisplayMs`, `frameDurationCpuMs` P50/P90,
`frameOverrunMs` P90, the trace sections' medians), and the device, Android version and Parley version.

### Instrumented smoke tests

`app/src/androidTest` holds a few seconds of checks on a real phone or emulator: Parley starts and draws its window,
survives being recreated, and opens from a dial intent without calling. They need the same kind of test device (one
connected, unlocked, Android 10 or later) and change nothing on it:

```sh
cd parley-phone
./gradlew :app:connectedDebugAndroidTest
```

A plain build only compiles them (`./gradlew :app:compileDebugAndroidTestKotlin`); nothing in CI runs them yet,
because CI has no device or emulator.

### Results

Not measured yet: no device run has been recorded for 4.4. Fill in one row per device and build.

| Date | Parley | Device (class) | Android | Cold start to Recents, ms (no profile / profile) | Contact page cold, ms | Recents fling, frame P90 ms | Keypad typing, frame P90 ms | Ring to notification, ms | Screening verdict, ms |
|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | |

## Running the benchmarks

```sh
cd parley-phone
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
# one class:
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.parley.baselineprofile.StartupBenchmark
```

Results (JSON and Perfetto traces) land in `baselineprofile/build/outputs/connected_android_test_additional_output/`.
Compare the `None` and `BaselineProfile` rows: `timeToInitialDisplayMs` for start-up, `frameDurationCpuMs` P50/P90/P99 and
`frameOverrunMs` for scrolling and typing, and the trace-section durations for the incoming call.

Benchmarks on an emulator are good for comparing before and after on the same machine; absolute numbers belong to a
real mid-range phone with the screen on, not charging-throttled, and the same data.

### Removing the benchmark data

```sh
adb shell content delete --uri content://call_log/calls --where "\"number LIKE '+155501%'\""
```

Contacts: delete the "Bench …" contacts in Parley (select all in a search for "Bench") or clear the Contacts Storage
app's data on a test device.

## Trace sections

Parley marks the call path for Perfetto and systrace (`android.os.Trace`, async sections, so they may cross threads):

| Section | From → to |
|---|---|
| `Parley.warmCallPath` | Process start → the call path's settings, rules and lists are in memory. |
| `Parley.screenToRespond` | `CallScreeningService.onScreenCall` → `respondToCall`. |
| `Parley.screenCall` | The in-call service's own screening of a ringing call → its verdict. |
| `Parley.addToNotification` | Telecom adds an incoming call → Parley's first notification for it is posted. |

Record with `adb shell perfetto -o /data/misc/perfetto-traces/call.pftrace -t 20s -a app.parley.phone sched gfx view am`
(or the Perfetto UI's "Record new trace" with *Atrace userspace annotations* for `app.parley.phone`), ring the phone,
and look for the sections on Parley's process tracks.

## Budgets and measurements

The release APK has a size budget of 12 MiB (the ≤ 12 MB target in docs/AUDIT.md §5): `./gradlew :app:checkReleaseApkSize`
fails above it, and CI runs it.

### 5.4: data at scale

The 5.3 audit (docs/audit/round2/PERFORMANCE.md) estimated what grows with the address book and the years. What
changed, and how it was measured (JVM and Robolectric unit tests on the build machine, not a phone; the timings are
for comparison, a phone is several times slower):

**Time-machine index** (`SnapshotLogTest.halfAYearOfSnapshotsStaysSmall`, core:common). 180 daily snapshots, 1% of
contacts changing a day, a few added and deleted. The old format kept one full `lookup key → hash` map per day and
read all of them for any question; the new index keeps each contact's changes only, read once and held in memory
(up to 150,000 versions, a few MB).

| Contacts | Versions kept | Index on disk | Old format on disk | Add a day | Read the index | Two whole snapshots | One contact's history | Drop a day and find unused blobs |
|---|---|---|---|---|---|---|---|---|
| 5,000 | 15,688 | 734 KiB | 76 MiB (433 KiB a day) | 16 ms | 18 ms | 15 ms | 5 µs | 45 ms |
| 20,000 | 62,734 | 2.9 MiB | 307 MiB (1.7 MiB a day) | 36 ms | 53 ms | 208 ms | 7 µs | 257 ms |

The old format also parsed every day's map for each question: about 250 bytes of heap per entry, so roughly 225 MB at
5,000 contacts and 900 MB at 20,000 (an out-of-memory crash on any phone). Cleanup now finds unused blobs from the index
(distinct hashes, and the photo hashes noted when each record was written) instead of opening every record of every
day (0.9 million decodes a day at 5,000 contacts). The old files move over the first time the index is read: each
day's file is read once, and each distinct record once for its photos (`TimeMachineIndexTest`, core:data).

**Contacts provider batches** (`ProviderBatchesTest`, core:data). Every bulk write (deleting contacts, label changes,
undoing a restore, health fixes, unlinking) goes in batches of at most 400 operations with a yield point every 100;
the test's provider refuses more than 500 between yield points, as Android's does. Deleting 1,100 contacts takes three
batches; before, it was one batch the provider refused.

**Private calls and number memory** (`PrivateCallsTest`, `NumberMemoryIndexTest`, `MemoryTallyTest`, core:data).
Listing private calls opens each with a software key: 0 Keystore operations for 50 calls (before: one per call, after
every change, 2–6 s for 2,000 calls by the audit's estimate). Number memory hashes numbers in software (before: about
25,000 Keystore operations a day with 100,000 archived calls) and reads only the calls archived since its last
rebuild. In a process started for a call, the vault builds no listing at all.

**Call screening** (`CallScreenerTest`, core:data). One PhoneLookup per call (before: two for a contact) and one
vault lookup (before: two for a private contact); the contacts provider, the vault, the spam lists, the call log, the
blocked-call log, Android's block list and the SIMs are asked at the same time, inside the 3 s budget.

### Contacts search over every field

The Contacts search looks at every field (`ContactSearch`, core/common). Each contact is prepared once, off the main
thread, whenever the address book changes (`PeopleIndex`, which follows Android's change notifications): its texts
folded, its numbers in their national and international digit forms. A keystroke then folds only the query and scans
prepared strings. `ContactSearchSpeedTest` (core:common) measures what the list runs per keystroke after its 80 ms
debounce, `ContactListSearch.run`: the label and field filters, the search over every field with the "Matched: …"
field, and with nicknames shown the renaming and the sort, over 5,000 contacts with names, numbers, emails, addresses,
work, notes, dates, relations, websites, custom fields and labels. It doesn't include turning the matched fields into
hint strings or the list's letter headers (one pass each over the results). `PeopleIndexSearchTest` (core:data,
Robolectric) measures the matching alone over 5,000 address-book contacts read through the index. The medians are a
few ms per query on a laptop and are printed by the tests. The tests don't enforce a wall-clock frame budget (a loaded
CI machine would fail it); they check that the time grows in proportion with the contacts (four times the contacts
may take at most ten times as long, so per-keystroke re-reading or pairwise work fails) and only a generous absolute
bound. Private contacts are searched over their opened details, held in memory only (`PrivateSearch`) and dropped
under the vault's own rule (60 s idle, the screen off, a lock); nothing is indexed on disk. Their docs are published
at most every 750 ms while they open, since each publish rebuilds the list's search data.

### 4.5: opening a private contact

Reported on a phone: "a private contact opens slowly and takes a few seconds, a non-private contact opens quickly".
Measured with `PrivateOpenCostTest` (core:data, Robolectric; the meter counts Keystore operations and the bytes they
decrypt, `VaultCrypto.Meter`): Ana, made private from an address-book contact with a 400 kB photo, among 31 private
contacts.

| | Caller-ID key operations | Detail key operations | Bytes the detail key decrypts | Key lookups |
|---|---|---|---|---|
| 4.4, every open | 33 (every private contact listed to find this one, then twice for Ana) | 2 (details, then "detailsLost") | 1,119,198 (the whole 559,599-byte blob, twice) | 35 (one per operation) |
| Now, first open of an entry sealed before 4.5 | 2 | 1 | 559,599 (its last whole opening; it is split then) | 2 |
| Now, every later open | 2 | 1 | 460 | 2 (none once looked up in the session) |
| Now, within a minute of the last open | 1 | 0 | 0 | 0 |

Why it was slow: for a contact made private the sealed details carried its whole address-book record, the photo
(up to 512 kB, base64) and its carried interactions, and the detail key is in StrongBox where the phone has one: a
secure element on a slow bus, meant for small amounts of data. The page decrypted all of it twice and showed nothing
until then, after opening every private contact's caller-ID copy (and reading every sealed blob from the database) to
find this one; any change to the address book or the vault started it all again. A kept original photo (up to 20 MB)
was also opened whole for the header. Device contacts never touch the Keystore.

What changed (docs/CONTACT_MODEL.md, "Opening a private contact"): the details are sealed in two parts and the page
opens only the small one; the page shows the caller-ID copy at once and fills in; one opening per page, re-read only
when that entry changes; a minute's memory of opened details while the phone is unlocked; summaries and key handles
are reused; listings read only caller-ID copies; the header photo of a private contact comes from a sealed 1024 px
copy.

### 4.4: from 13.3 MiB to 11.7 MiB

Measured on the unsigned release build (`./gradlew :app:assembleRelease`, no keystore), 4.3.0 against 4.4, entry sizes
as stored in the APK. Nothing Parley does was removed.

| Part | 4.3.0 | 4.4 | Saved | Why |
|---|---|---|---|---|
| Release APK | 13,971,162 bytes (13.32 MiB) | 12,256,720 bytes (11.69 MiB) | 1,714,442 | All of the below, plus a smaller zip directory (1,205 files instead of 1,246). |
| `resources.arsc` (stored uncompressed, as Android requires) | 3,837,564 | 2,290,724 | 1,546,840 | **Locale filters** (`androidResources.localeFilters`, the 8 languages of Parley's own strings). Material 3 and Compose brought 67 strings in 76 more languages and regional variants; each language is a string table with a 4-byte slot for every one of Parley's 4,066 strings, so each cost about 17 KB although it held 67 strings. **R8 resource shrinking** (`android.r8.optimizedResourceShrinking`) also drops 163 resources only removed code used (35 strings, unused notification layouts and the splash variant with an icon background). The two were measured together. |
| `classes.dex` (stored uncompressed) | 8,446,136 | 8,373,924 | 72,212 | Scanning a picture for Aztec, Data Matrix and PDF417 codes calls those three ZXing readers by name instead of `MultiFormatReader`, which referenced every 1D barcode reader and MaxiCode, so R8 had to keep them though they were never used. |
| Native libraries | 60,292 | 37,392 | 22,900 | `libdatastore_shared_counter.so` (4 ABIs) is loaded only by multi-process DataStore, which Parley doesn't use. |
| `res/` files | 27,695 | 15,476 | 12,219 | R8 resource shrinking (41 unused layouts and drawables). |
| ez-vcard resources | 15,391 | 7,812 | 7,579 | The hCard HTML template and its placeholder picture: Parley never writes HTML (that writer needs FreeMarker, which isn't included). |
| Geocoder place names | 1,117,302 | 1,117,302 | 0 | Already trimmed to the shipped languages in 4.0 (`GeoLanguages`). The English data (China alone 388 KB) is what names the place for every country, so it stays. |

Looked at and left alone:

- **Kotlin metadata** is already stripped by R8 (no `kotlin.Metadata` in the dex); R8 full mode is the default in AGP 8.
- **`-repackageclasses`** saved 17 KB of dex; not worth a class-naming change that can only be checked on a device.
- **Compressed dex** (`packaging.dex.useLegacyPackaging = true`) would take about 4.5 MB off the download, but Android
  then keeps an uncompressed copy of the dex after install, so Parley would take more space on the phone. Not done.
- **Material icons**: R8 keeps only the icons used (about 108 KB of code).
- **Library translations for users of other languages**: with the filters, a phone in (for example) Italian shows the
  few Material labels (date picker, bottom sheet) in English, like the rest of Parley; Parley's own 8 languages are
  unchanged and the system's per-app language list still offers exactly those 8.

### Phase 2 (4.0)

Measured on the unsigned release build (R8, resource shrinking) before and after the Phase 2 performance work:

| | Before | After |
|---|---|---|
| Release APK | 14,605,719 bytes (13.93 MiB) | 13,388,334 bytes (12.77 MiB), profile installer and splash screen included |
| `classes.dex` | 8,282,916 bytes; 37,878 method references; 7,390 classes | 7,966,484 bytes; 34,865 method references; 7,128 classes |
| AppCompat classes kept by R8 | 191 | 0 |
| Geocoder place names (compressed) | 1,570,856 bytes, 35 languages | 1,117,302 bytes, 6 languages |
| `resources.arsc` | 3,980,016 bytes | 3,746,364 bytes |
| `assets/dexopt/baseline.prof` | 6,797 bytes, library rules only | 8,277 bytes, with Parley's own start-up and call-path code |

Where the savings came from: the app lock uses the platform `BiometricPrompt` (API 29+) instead of
`androidx.biometric`, which pulled in AppCompat; the geocoder keeps English, German, Spanish, French, Portuguese and
Arabic place names only (no data exists for Hindi or Urdu), and other languages ask in English.

## APK size at 4.5.0

4.5.0 is 12,815,680 bytes (12.22 MiB), 0.22 MiB over the 12 MiB target after 4.5's features (number memory, personal reputation, family safety, sync watchdog, profiles). The budget in `app/build.gradle.kts` is 12.5 MiB until the next trim. The two largest remaining levers are product decisions:

- **Offline caller-location data** (libphonenumber geocoder, English names): China (86) is 0.79 MB and Australia (61) 0.40 MB of the shipped prefix files.
- **Translations** in `resources.arsc` (2.3 MB with eight locales; Parley is English-only for now and the newest screens aren't translated).

## English-only (4.6)

Parley ships English only from 4.6: the eight translation folders and the language picker are gone, `localeFilters` keeps only English, and a language picked in an older version is reset once at start. The budget is back at 12 MiB.
