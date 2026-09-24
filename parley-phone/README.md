# Parley Phone

A privacy-focused, modern **Contacts + Dialer + In-call** app for Android, all in one app.

- **No internet permission.** The build fails if any dependency tries to add it.
- No ads, no trackers, no analytics, no accounts.
- Material 3 with dynamic colour.
- Open source under the GPL-3.0.

This project is independent of the rest of this repository.

## Features (v1.0)

| Area | What you get |
|---|---|
| **Dialer** | T9 smart search (names, initials, inner words, accents, Cyrillic/Greek, number substrings); speed dial on 2–9; voicemail on 1; `+` on 0; pause and wait characters; USSD/MMI and `*#*#…#*#*` codes; one call button per SIM on dual-SIM phones; optional confirm before calling |
| **In-call** | Full-screen incoming call over the lock screen; slide-to-answer or tap-to-answer (a setting); reply with a message; mute, keypad (DTMF), speaker; audio-route picker for Bluetooth and wired headsets; hold; add call; merge, swap and conference management; buttons driven by the call's capabilities; "End current call and answer"; proximity screen-off; call-style notification with mute and speaker buttons; STIR/SHAKEN "Verified" and "Possibly spoofed" badges; SIM picker |
| **Recents** | Grouped by person and day; filter chips (All, Missed, Incoming, Outgoing, Blocked); search; SIM labels; per-number history; delete; block and unblock; add to contacts; our own missed-call notification with Call back and Message |
| **Contacts** | Alphabetical list with sticky headers and a fast-scroll rail; label (group) filters; accent-insensitive search across names, numbers and e-mails; detail page; full editor (structured names, phones, e-mails, addresses, dates, websites, notes, labels, photo); choice of account (device, Google, CardDAV/DAVx⁵); favourites and frequently called; per-contact ringtone; send to voicemail; default SIM per number; QR share with a field picker; vCard share; lossless vCard 4.0 import and export (reads 2.1, 3.0, 4.0 and Apple/Google dialects) with an import report and optional duplicate skipping; spreadsheet CSV export and import; duplicate finder with merge and separate |
| **Blocking** | Offline and rule-based: exact, prefix and wildcard (`*`, `?`) rules; hidden numbers; non-contacts; neighbour spoofing; failed caller verification; reject or silence; system block list; log of blocked calls; optional call-screening role so blocked calls never ring |
| **Settings** | Light, dark or system theme; pure-black AMOLED; dynamic colour; compact density; start tab; sort by first or last name; quick replies; default account; device-setup helpers (full-screen permission, battery, Xiaomi pop-up permissions); privacy dashboard that confirms there is no internet permission |

**Not included:**

- **Call recording.** Android does not allow it for non-system apps.
- **Syncing visual voicemail ourselves.** That needs internet.
- **Online spam lookups.** By design, nothing is sent anywhere.

## Project layout

```
core/common   Pure Kotlin: number normalisation, T9, block-rule engine, duplicate finder (unit-tested)
core/data     ContactsContract, CallLog, SIMs, BlockedNumberContract, Room, DataStore, vCard
core/ui       Theme, avatars, shared components
telecom       InCallService, CallManager, notifications, in-call UI (no dependency on data/features)
app           Main activity, navigation, all screens, missed-call notifications
```

## Building

Requirements:
- JDK 17 or newer
- Android SDK with `platforms;android-36` and `build-tools;36.0.0`

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:common:test lint assembleDebug      # tests, lint, debug APK
./gradlew assembleRelease                          # signed if keystore.properties exists
```

Signing uses a `keystore.properties` file, which is never committed:

```
storeFile=/absolute/path/parley-release.jks
storePassword=...
keyAlias=parley
keyPassword=...
```

or the environment variables `PARLEY_KEYSTORE`, `PARLEY_KEYSTORE_PASSWORD`, `PARLEY_KEY_ALIAS` and `PARLEY_KEY_PASSWORD`.

The `checkReleasePermissions` / `checkDebugPermissions` tasks run before every assemble. They fail the build if the merged manifest contains INTERNET, location, camera, microphone, SMS, storage or ad-ID permissions.

## Docs

- [docs/RESEARCH.md](docs/RESEARCH.md): research brief
- [docs/PLAN.md](docs/PLAN.md): product and technical plan
- [docs/TESTING.md](docs/TESTING.md): device test checklist
- [docs/COMPETITIVE_ANALYSIS.md](docs/COMPETITIVE_ANALYSIS.md): full-code analysis of four open-source apps, and the roadmap
