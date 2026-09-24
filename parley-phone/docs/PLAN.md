# Parley Phone: Product and Technical Plan

*Companion to [RESEARCH.md](RESEARCH.md). Working name: "Parley".*

## 1. Product principles

These decide trade-offs, in priority order:

1. **Calls never fail because of us.** Ringing, answering, audio routing and emergency calls come before any feature.
2. **Nothing leaves the device.** No INTERNET permission, no analytics, no accounts. CI enforces this.
3. **The user decides the layout.** The modern Material 3 Expressive look is the default. Density, tab set and order, answer gesture, and a big-button mode are all settings.
4. **Every permission is explained.** An in-app Privacy dashboard lists each permission, why we need it, and what breaks without it.
5. **Fast.** Cold start under 500 ms on mid-range phones. T9 results within one frame. The incoming-call UI appears within 300 ms of `onCallAdded`.

## 2. Scope by release

### v1.0: the core replacement

This is the first APK delivered.

**Dialer**
- T9 smart search: name tokens, initials, number substrings, accent-insensitive.
- Paste-number detection.
- USSD/MMI codes via `handleMmi`.
- Voicemail key (long-press 1).
- Speed dial (long-press 2–9).
- SIM picker, with default SIM remembered per contact.
- Optional confirm-before-dial.

**In-call**
- Incoming calls: full-screen over the lock screen, plus a heads-up CallStyle notification.
- Answer gesture: tap, swipe, or both, selectable in settings.
- Reject with a quick SMS reply. This hands off to the user's SMS app, so we don't need `SEND_SMS`.
- Ongoing-call controls:
  - mute, keypad (DTMF), speaker;
  - audio-route sheet (earpiece / speaker / Bluetooth / wired), using `CallEndpoint` on API 34+ and `CallAudioState` below that;
  - hold, add call, merge, swap, split, manage conference.
- All buttons are shown or hidden based on the call's capabilities.
- Proximity wake lock.
- CallStyle ongoing-call notification with mute and speaker actions.
- Per-contact ringtone and vibration, and respect for do-not-disturb and silent mode.

**Call log**
- Calls grouped by person and day.
- Filter chips: All / Missed / Incoming / Outgoing / Blocked.
- Per-number history, search, and delete.
- App-owned missed-call notification with Call back and Message actions.

**Contacts**
- List with fast-scroll index, favourites, and search across all fields.
- Contact detail page with a per-contact call history.
- Create and edit with an account picker (Device / Google / CardDAV).
- Photo via Photo Picker.
- Groups and labels.
- vCard import and export through the system file picker.
- Duplicate finder and merge via `AggregationExceptions`.
- QR share with a field picker.

**Blocking**
- Adds to the system block list.
- Local rules:
  - prefix and wildcard patterns;
  - hidden numbers;
  - non-contacts;
  - neighbour-spoofing guard.
- Action per rule: reject, silence, or silence and hide.
- Blocked-calls log.

**Settings and trust**
- Theme: system, light, dark, or AMOLED; dynamic colour on/off.
- Density.
- Tabs.
- Answer gesture.
- Privacy dashboard.
- OEM setup assistant for Xiaomi, Samsung and Huawei.

### v1.x: differentiators

- **Encrypted private vault.** Hidden contacts, with an optional private call history (copied, then removed from the system log).
- **Encrypted full backup.** Contacts, rules, notes, speed dial and settings go into one archive, with optional scheduling to a folder the user picks.
- **Notes on calls and contacts.** Tags. Callback reminders.
- **Call statistics.** Birthdays view.
- **Simple mode.** Big buttons and tap-to-answer only.
- **Importable spam blocklists.** CSV/JSON files the user supplies.
- **Voicemail tab.** Plays voicemails already synced by the system or carrier.

### Not planned

We say this in the README and in the app.

- **Call recording.** The OS doesn't allow it for non-system apps. A Shizuku spike may come later.
- **Own visual-voicemail sync.** Needs internet.
- **Online caller ID lookups.** A separate optional companion could be considered later; never in the core APK.
- **SMS.** Possible as a future separate phase.

## 3. Architecture

### Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x | Standard for Android |
| UI | Jetpack Compose + Material 3 (dynamic colour on 31+) | Modern look, fast to build |
| Pattern | MVVM with unidirectional state (`StateFlow<UiState>`, events in, state out) | Testable, simple |
| DI | Manual (small `AppContainer`) | No annotation processing, smaller APK, faster builds. Can move to Hilt later if needed. |
| Local DB | Room (block rules, notes, speed dial, SIM preferences, T9 index, vault) | Type-safe, migrations |
| Preferences | DataStore | — |
| Async | Coroutines + Flow; a `ContentObserver` turned into a Flow for contacts and the call log | — |
| Lists | `LazyColumn` with stable keys; in-memory contact projection (fine to 10k+), Paging only if profiling demands it | Simpler and fast |
| vCard | `ez-vcard` (pure Java, no network) | Full vCard 2.1/3/4 fidelity |
| QR | ZXing core, generate only | Small, offline |
| Vault encryption | Android Keystore AES-GCM key, fields encrypted before Room writes | No native libraries such as SQLCipher, so no 16 KB page-size issues |
| Backup encryption | Password → PBKDF2-HMAC-SHA256 (high iterations) → AES-GCM, JCE only | Portable, no native code |

### SDK levels

- `minSdk 29` (Android 10), the first release with RoleManager.
- `targetSdk` and `compileSdk` 36.
- Code paths for below API 31 (CallStyle, dynamic colour) and below API 34 (`CallEndpoint`) are kept minimal.

### Module layout

```
parley-phone/
├─ app/                      Single-activity Compose shell, navigation, onboarding, settings, AppContainer
├─ core/model/               Plain Kotlin data classes (Contact, CallEntry, BlockRule, SimAccount…)
├─ core/common/              Phone-number normalisation (E.164), T9 mapping, transliteration, utils
├─ core/data/                Repositories: ContactsRepository (ContactsContract), CallLogRepository,
│                            BlockRepository (BlockedNumberContract + Room rules), SimRepository,
│                            SettingsRepository (DataStore), Room database
├─ core/ui/                  Theme (M3, dynamic colour, AMOLED), shared components, avatars
├─ telecom/                  *** The critical path; fewest dependencies, most tests ***
│                            ParleyInCallService, CallManager (StateFlow of calls), CallNotifier (CallStyle + FSI),
│                            Ringer (ringtone, vibration, DND), AudioRouteController, ProximityController,
│                            ParleyCallScreeningService, InCallActivity (showWhenLocked)
├─ feature/dialer/           Dialpad + T9 results, SIM picker
├─ feature/calllog/          History, filters, per-number history
├─ feature/contacts/         List, detail, editor, groups, merge, vCard, QR
└─ feature/blocking/         Rules UI, blocked log
```

**Rule:** `telecom/` must not depend on any `feature/*` module. It uses `core/data` only through a narrow `CallerInfoProvider` interface with a hard timeout (e.g. 150 ms). A slow contact lookup can then never delay ringing; the number is shown first and the name added when it arrives.

### The incoming-call path

```
Telecom ──bind──▶ ParleyInCallService.onCallAdded(call)
                   ├─ CallManager.add(call)            (in memory, main thread, O(1))
                   ├─ policy check (hidden number / rules) ─▶ reject/silence early if needed
                   ├─ CallNotifier.postIncoming(call)  (CallStyle + fullScreenIntent, BEFORE any I/O)
                   ├─ Ringer.start(call)               (default ringtone first, per-contact one if found within timeout)
                   └─ launch coroutine: CallerInfoProvider.lookup(number) ─▶ update notification + UI
InCallActivity (Compose) observes CallManager.calls: StateFlow ─▶ renders ringing / active / multi-call UI
```

### Manifest permissions (complete list)

| Permission | Granted by | Purpose |
|---|---|---|
| `CALL_PHONE`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS` | Dialer role | Placing calls, SIM accounts, headset answer |
| `READ_CALL_LOG`, `WRITE_CALL_LOG` | Dialer role | History, delete, private-history mode |
| `READ_CONTACTS`, `WRITE_CONTACTS`, `GET_ACCOUNTS` | Dialer role | Contacts app, account picker |
| `READ_VOICEMAIL`, `WRITE_VOICEMAIL`, `ADD_VOICEMAIL` | Dialer role | Voicemail tab (v1.x) |
| `POST_NOTIFICATIONS` | Dialer role | Call and missed-call notifications |
| `USE_FULL_SCREEN_INTENT` | Install (side-load) | Incoming call over the lock screen |
| `WAKE_LOCK`, `VIBRATE`, `MODIFY_AUDIO_SETTINGS` | Install (normal) | Proximity sensor, ringing, audio routing |
| `BLUETOOTH_CONNECT` | Runtime, optional | Bluetooth device names in the route sheet |
| `READ_PHONE_NUMBERS` | Runtime, optional | Only for the neighbour-spoofing guard |

**Explicitly absent:**
- `INTERNET`
- `RECORD_AUDIO`, `CAMERA`
- `SEND_SMS` / `READ_SMS`
- location permissions
- `QUERY_ALL_PACKAGES`
- `SYSTEM_ALERT_WINDOW` as a request (the role grants the app-op; we don't depend on it)
- storage permissions

CI fails the build if the merged manifest contains any of these.

### Known platform gotchas we design for

- **Edge-to-edge (enforced at target 35+).** Use `enableEdgeToEdge()` and insets everywhere, including the in-call screen.
- **Predictive back (target 36).** Use `BackHandler` only.
- **Orientation locks ignored at 600dp or wider.** The in-call layout must also work in landscape.
- **Private and hidden calls never reach `CallScreeningService`.** Handle them in the InCallService.
- **Emergency calls.** Always use `TelecomManager.placeCall`. Never block. Always show the UI and never throw on an unknown call state.
- **Car mode.** Don't declare `CAR_MODE_UI`.
- **Number matching.** Normalise to E.164 using the SIM's country ISO, falling back to the network country, for lookups and rules everywhere.

## 4. Milestones

| # | Milestone | Deliverable | Exit criteria |
|---|---|---|---|
| M0 | **Foundation** | Gradle project, SDK installed here, CI script, release keystore, theme, empty tabs, no-INTERNET guard | `assembleRelease` produces a signed APK here; permission check passes |
| M1 | **Telecom core** | Role request, InCallService, CallManager, incoming, ongoing and multi-call UI, notifications, ringer, audio routes, proximity, SIM picker | Call placed and received on your phone; lock-screen answer works; Bluetooth switch works; hold, merge and swap work where the carrier supports them |
| M2 | **Dialer + call log** | T9 engine, dialpad, speed dial, USSD, history with filters, missed-call notification | T9 under 16 ms on 5k contacts (unit benchmark); history loads under 200 ms |
| M3 | **Contacts** | List, detail, editor, accounts, groups, photos, vCard, duplicates and merge, QR | Round-trip vCard test; merge/unlink verified |
| M4 | **Blocking** | Rules engine, screening service, blocked log, neighbour guard | Rule-engine unit tests incl. normalisation edge cases (+CC and 0-prefix, wildcards) |
| M5 | **Polish and trust** | Settings (density, tabs, gesture, AMOLED), Privacy dashboard, OEM assistant, accessibility pass (TalkBack labels, 200% font) | Lint clean; accessibility checks; **v1.0 APK delivered** |
| M6+ | **v1.x** | Vault, encrypted backup, notes, statistics, simple mode, blocklist import, voicemail tab | One at a time |

## 5. Quality and testing

- **Unit tests (JVM):**
  - T9 matcher and transliteration;
  - E.164 normaliser;
  - rule engine;
  - duplicate detector;
  - vCard round-trip;
  - backup encryption round-trip;
  - CallManager state machine with fake `Call` states.
- **Robolectric:** repositories against a fake ContactsProvider and CallLog; notification building.
- **Static checks:** Android Lint (warnings as errors on `telecom/`), plus a merged-manifest permission check.
- **Manual device matrix.** You provide this, since an emulator can't run here (no KVM). The script:
  1. incoming call when unlocked, locked and screen off;
  2. answer and reject with each gesture;
  3. Bluetooth headset;
  4. call waiting, then swap, then merge;
  5. dual SIM outgoing with each SIM;
  6. USSD code;
  7. hidden-number blocking;
  8. **emergency-number dial test without connecting** (use the carrier's test procedure; never call emergency services for real).
- **Performance:** measure cold start and incoming-call-to-UI latency on your device. A baseline profile is added in M5 if needed; it stays disabled for reproducible F-Droid builds.

## 6. Build and release

- **Toolchain:**
  - Android SDK command-line tools with `platforms;android-36` and `build-tools;36.0.0`;
  - AGP 8.13.x on Gradle 8.14 and JDK 21;
  - Kotlin JVM target 17;
  - Gradle wrapper committed with `distributionSha256Sum`.
- **Release build:** R8 minify and resource shrinking; `dependenciesInfo { includeInApk = false }`; `vcsInfo.include = false` (reproducibility).
- **Signing:** a release keystore generated once. Its password is kept out of git and stored in your secrets. The keystore itself must be backed up by you. **Losing it means users can't update.**
- **Outputs:**
  - a universal APK attached to each milestone;
  - later, F-Droid and IzzyOnDroid metadata;
  - a GitHub release with the SHA-256 of the APK.
- **License:** GPL-3.0 is recommended. Copyleft makes a ZipoApps-style closed-source takeover impossible, and it matches Fossify, so code can be exchanged with it.

## 7. Open decisions for you

1. **App name.** "Parley" is a placeholder.
2. **License.** GPL-3.0 is recommended, or Apache-2.0 if you want permissive terms.
3. **Where the code lives.** This folder inside the current repo is fine for now. A dedicated repository is better for F-Droid and a clean history.
4. **Minimum Android version.** Android 10 is recommended. Android 12 simplifies the code but drops older phones.
5. **Distribution.** Side-load or F-Droid first, or also Google Play later? Play adds a Permissions Declaration review and developer verification.
6. **Your test phone(s).** Make, model and Android version. OEM quirks (Samsung, Xiaomi, Pixel) change what we test first.
