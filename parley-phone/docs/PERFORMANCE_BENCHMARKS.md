# Performance: baseline profile, benchmarks and budgets

Parley ships a baseline profile so the code people wait on (start-up to Recents, the keypad, the incoming-call screen)
is compiled ahead of time at install, rather than interpreted until the phone's background dexopt gets to it. The
`androidx.profileinstaller` library installs it on sideloaded and F-Droid installs too, which get no cloud profiles.

## What is in the repository

| Path | What |
|---|---|
| `app/src/main/baseline-prof.txt` | A hand-written profile covering start-up (`ParleyApp`, the data container, `MainActivity`, `AppViewModel`, the home screen with Recents, the keypad and Contacts) and the call path (`CallManager`, `CallNotifier`, the screening service, the in-call screen). It works without a device, so profile installation works now. |
| `app/src/release/generated/baselineProfiles/` | The measured profile and startup profile, once generated on a device (below). Merged with the hand-written one. |
| `baselineprofile/` | A `com.android.test` module with the `androidx.baselineprofile` plugin: the generator (`BaselineProfileGenerator`) and the macrobenchmarks. |

The benchmarks:

| Test | Measures |
|---|---|
| `StartupBenchmark` | Cold start to Recents with 3000 calls and 3000 contacts (`StartupTimingMetric`), without a profile and with it. |
| `RecentsScrollBenchmark` | Frame timing while flinging Recents with 3000 calls. |
| `KeypadTypingBenchmark` | Frame timing while typing a number on the keypad with 3000 contacts to search. |
| `IncomingCallBenchmark` | On an emulator: Parley's `Parley.addToNotification` and `Parley.screenCall` trace sections and the call screen's frames for an incoming call, from a cold process. Skipped without an emulator console token. |

Each test runs twice: `CompilationMode.None()` (what a first launch without a profile gets) and
`CompilationMode.Partial(BaselineProfileMode.Require)` (with the profile), so the gain is visible.

## Regenerating the profile on a device

1. Use a test phone or an emulator (Android 10 or later; an emulator image with Google APIs is fine). The generator and
   the benchmarks **add 3000 contacts and 3000 call-log entries** (numbers starting +1 555 01, a range reserved for
   fiction) the first time they run. Don't run them on your own phone.
2. Install nothing by hand: Gradle builds and installs the `nonMinifiedRelease` / `benchmarkRelease` variants itself.
   On first launch Parley shows its onboarding; open the app once and finish it (or skip it), and make Parley the
   default phone app if you want the incoming-call journey.
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

The release APK has a size budget of 16 MiB: `./gradlew :app:checkReleaseApkSize` fails above it, and CI runs it.

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
