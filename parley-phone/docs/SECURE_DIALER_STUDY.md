# Secure Dialer study: what it claims, what its code does, and where Parley goes from here

*13 Oct 2026. A read-only study for the owner. It compares Parley 7.0.0 (`f7a2c3b5`) with Secure Dialer 1.6.0 (`io.github.securephoneapps.securedialer`), read in full at `main` `aa0f64a` (8 Oct 2026; tag `v1.6.0` is `94b3dd7`). The repository was cloned into an empty folder and only read: nothing was built, run or copied. "Read" here means every Kotlin file under `app/src/main` and `app/src/test`, the manifest, the Gradle files, the CI workflows, the XML rules, the English strings and the project's own documents (README, SECURITY, CHANGELOG, PROJECT_STATE, wiki). Evidence is given as `file:line`, with Secure Dialer paths relative to `app/src/main/java/com/example/`. IDs use the prefixes **SD** (gaps found in Secure Dialer) and **SX** (new ideas), with owner decisions **S1–S12**. These S numbers belong to this study only; they are unrelated to the S3 of AUDIT_2 and the security track.*

## 1. Summary

**Their claims, checked.** Secure Dialer is honest about one big thing: it really has no internet permission and no trackers. Most of its headline features are weaker than the README says, and its spam screening is not there at all.

| Headline claim | Verdict |
|---|---|
| Zero internet, zero trackers | **Real** |
| Local offline spam screening "(CallScreeningService)" | **Marketing only.** There is no `CallScreeningService`, and the "spam database" is never read when a call comes in. Only numbers written to Android's own block list are blocked, by Android |
| Outgoing caller ID (CLIR) masking | **Real but crude.** A prefix such as `#31#` is put in front of every number you dial. One preset is mislabelled ("Japan (1831)": 1831 is Australia's code; Japan's is 184) |
| Local call recording engine | **Partial, and mostly ineffective on stock Android 10+.** It records the microphone with the speaker forced on. No accessibility service, no root. No consent prompt; its own test calls the default "stealth" |
| Biometric / self-healing recording vault | **Partial.** The lock is a screen in front of plain `.m4a` files that are, by default, also copied to the public Downloads folder. "Self-healing" means re-listing files found on disk |
| Conference and waiting-call control | **Partial, with a bug.** Merge and swap work. There is no "end and answer" and no managing one person in a conference. Declining a waiting call from its notification **hangs up the call you are on** |
| SQLCipher AES-256 database with hardware keys | **Partial, with data-loss paths.** Settings stay in plain preferences. Any key problem silently deletes the database, and if SQLCipher fails to load the app runs on an in-memory database and loses everything written |
| Biometric app lock | **Bypassable.** Any app can open the full app past the lock with one intent extra, and during a call "Minimise" shows contacts and Recents over the lock screen |

Of the 35 features read (section 3): **14 real, 10 partial, 6 real but with a serious bug, 2 stubs and 3 marketing only.** The project is three months old (327 commits since 2 Jul 2026, almost all by one account), scaffolded from a Google AI Studio template (`com.example` namespace, `metadata.json` with a Gemini capability, `.env.example`), and its tests mostly run against an in-memory fallback, so the encryption and the call paths are never exercised.

**Where Parley leads.** Almost everywhere. Of the 35 features, Parley is **better at 23, equal at 5, partial at 1 (caller ID hiding) and lacks 4**, all four by design or by an earlier decision (call recording, a floating overlay, flash on ring, translations); 3 don't apply. Parley's spam screening, blocking, conference and waiting-call handling, app lock, encryption, backups, voicemail, fake call (Rescue call) and lock-screen privacy are each a generation ahead, and Parley asks for fewer permissions (no microphone, no overlay, no exact alarms).

**What Parley lacks that is worth building** (no new permission):

1. **SD-1. Hide my number** for a call, a person or a label. Today a Parley user must type `#31#` by hand. This is the one useful idea in Secure Dialer, and section 5 (SX-1) proposes a better version: right code per SIM country, never for emergency, service codes or internet calls, history that still names the person, and "Call again showing my number" when a hidden call is refused.
2. **SD-2. An honest answer about call recording** in Help: what Android allows, why Parley doesn't record, and what does work (the phone maker's own dialer, a root module).
3. **SD-3. A pointer to Android's own Flash notifications** (Android 14+) for people who want a flash on ring, instead of building one.

**Ideas that go beyond both apps** (section 5): **SX-1** Hide my number done right; **SX-2** Call receipt, a sealed and fingerprinted record of what was said and agreed, which covers most of why people want to record calls with companies, with no audio; **SX-3** "Something else is recording", a call-screen warning when another app is capturing sound during a call, with no permission; **SX-4** an honest self-check of Parley's sealed data that never deletes; **SX-5** a voice memo after a call through the phone's recorder app (owner decision: it is the user's own voice, not call audio).

**Recommended for call recording: no.** Section 4.2 explains what is and isn't possible on Android 10–16 without root. In short: a normal app can't record the other side of a cellular call; the workarounds are speakerphone-and-microphone (poor, often silenced), accessibility abuse (banned on Google Play, restricted by Android 13+, and the stalkerware route) or root. Add to that one-party and all-party consent laws. Parley keeps `RECORD_AUDIO` out, as its build already enforces.

## 2. The app

| | Secure Dialer |
|---|---|
| What | A default-dialer app (Contacts, Recents, keypad, in-call screen) with call recording, CLIR prefixes, a fake-call simulator, reminders, a floating call pill and themes |
| Version read | 1.6.0 (versionCode 12), `main` at `aa0f64a` (8 Oct 2026). Tags v1.0.0 (6 Jul 2026) to v1.6.0; one branch |
| Activity | 327 commits: 143 in July, 144 in August, 27 in September, 13 in October 2026. 322 by the owner account, 3 translation commits, 2 by Dependabot |
| Size | About 29,300 lines of Kotlin in 96 files under `app/src/main` (one module), 2,900 lines in 19 test files, 646 English strings, 9 translated locales (the README says 8 languages). Working tree 5.7 MB, mostly screenshots |
| Stack | Kotlin 2.0.21, Compose BOM 2024.09, Room 2.7 on `net.zetetic:android-database-sqlcipher` 4.5.4 (the deprecated artifact), minSdk 24, targetSdk 36 |
| Licence | **GPL-3.0** (file headers say "or later"); compatible with Parley's GPL-3.0. Nothing here needs their code |
| Distribution | GitHub releases and Obtainium; not on Google Play or F-Droid |
| Permissions | `READ/WRITE_CONTACTS`, `CALL_PHONE`, `READ/WRITE_CALL_LOG`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `MODIFY_AUDIO_SETTINGS`, **`RECORD_AUDIO`**, `VIBRATE`, `WAKE_LOCK`, Bluetooth, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, **`SYSTEM_ALERT_WINDOW`**, **`SCHEDULE_EXACT_ALARM`**; `INTERNET` removed (`AndroidManifest.xml:6`) |
| Components | Exported: `MainActivity` (launcher, DIAL, VIEW `tel:`, CALL_BUTTON) and `MyInCallService` (guarded by `BIND_INCALL_SERVICE`, takes over ringing). Not exported: two alarm receivers and a FileProvider. **No `CallScreeningService`** |

## 3. Their features, checked against their code and against Parley

"Real / partial / stub / marketing" judges Secure Dialer's code against its own claim. "Better / equal / partial / lack" judges Parley against Secure Dialer.

### 3.1 Privacy and security

| # | Claim | What the code does | Theirs | Parley | Verdict |
|---|---|---|---|---|---|
| 1 | No internet | `INTERNET` removed from the merged manifest (`AndroidManifest.xml:6`); no network library is active (`app/build.gradle.kts:119-175`, the Retrofit/OkHttp/Firebase lines are commented out) | Real | No internet and no network state, the build fails if either appears | **Equal** |
| 2 | No trackers or ads | None found | Real | None | **Equal** |
| 3 | Call notes, block list and settings encrypted with SQLCipher | The Room database is SQLCipher (`data/AppDatabase.kt:185-202`). **Settings are plain `SharedPreferences`** (`ui/viewmodel/DialerViewModel.kt:285-342`). If SQLCipher or the key fails for any reason, it **silently opens an in-memory database** meant for unit tests, so everything written is lost at the next restart (`AppDatabase.kt:203-208`). If the key can't open the file, it **deletes the database** and starts empty (`AppDatabase.kt:211-233`). Upgrading from a plain database encrypts it with the key as a **raw key** (`KEY "x'…'"`, `:283-284`) while every open passes the same bytes as a **passphrase**, so the check after migration fails and the migrated data is deleted (`:225-232`; high confidence from reading, not run) | Real with serious bugs | Personal data sealed per kind with Keystore-wrapped keys, unlock-bound for private details, "no plain-text fallbacks" (6.3), every write through `DurableFiles` | **Better** |
| 4 | Keys in the Keystore / StrongBox | AES-256-GCM Keystore key wraps a random database key, StrongBox tried first (`data/DatabaseKeyManager.kt:97-131`). The wrapped key lives in device-protected storage (`:38-45`) and the Keystore key is not bound to an unlocked phone. On **any** decryption error the wrapped key is removed and a new one made (`:65-70`), which, with item 3, wipes the data | Partial | Unlock-bound detail key, StrongBox preferred, fails closed and never re-keys over data | **Better** |
| 5 | Password-protected backups | PBKDF2-HMAC-SHA256, 250,000 rounds, AES-256-GCM (`data/BackupRestoreManager.kt:405-434`). The password is optional and the default export is **plain JSON** (`:180-181`); a failed decryption retries with 10,000 rounds (`:453-457`); SECURITY.md still says 10,000. No recordings, no contacts | Partial | scrypt, AES-256-GCM STREAM, signed, verified after writing, restore preview, capped KDF costs | **Better** |
| 6 | Biometric app lock | Uses `createConfirmDeviceCredentialIntent` (`MainActivity.kt:312-335`). But `onStart`, `onResume` and `handleIntent` set "authenticated" whenever a call exists **or the intent carries `SHOW_CALL_SCREEN`, `ANSWER_ON_LAUNCH` or `TRIGGER_FAKE_CALL`** (`MainActivity.kt:139-141`, `:288-293`, `:393-413`). `MainActivity` is exported, so any app (or `adb`) can open the whole app past the lock, and `ANSWER_ON_LAUNCH` also **answers a ringing call** for an app that has no answer permission (`:406-407`) | Real with a serious bug (bypassable) | App lock through `LockedActivity`; internal actions only through the non-exported `InternalEntry`; `ExportedComponentsTest` checks the merged manifest | **Better** |
| 7 | Lock screen | `MainActivity` is `showWhenLocked` in the manifest (`AndroidManifest.xml:62`) and turned on for every call (`MainActivity.kt:353-378`). During a call, **Minimise** shows the tabs (contacts, Recents, settings, backup export) over the lock screen (`ui/MainScreen.kt:496-569`); only Back is caught (`MainActivity.kt:114-120`). The incoming-call notification is `VISIBILITY_PUBLIC` with the name (`MyInCallService.kt:412`), and missed-call notifications have no public version | Partial | Separate non-exported call activity; "Caller on the lock screen" choice; notifications with public versions; widgets show only counts; duress rules | **Better** |
| 8 | Screen capture blocked | `FLAG_SECURE` on every release-build screen (`MainActivity.kt:82-84`) | Real | "Hide screen content" choice, and the call screen kept out of Recents' thumbnails for private callers (7.0) | **Equal** (different trade-off: theirs blocks all your own screenshots) |
| 9 | Contacts kept apart ("Custom Secure Contacts", CHANGELOG 1.4) | Every contact is written to Android's contacts provider (`DialerRepository.kt:258-270`); the encrypted database only caches a copy, rebuilt from Android on each sync (`DialerRepository.kt:124-170`) | Marketing only | Private contacts sealed outside Android's provider, still named when they call; archive; temporary contacts | **Better** |
| 10 | Numbers hashed for privacy | SIM history keys use **unsalted SHA-256** of the number (`util/SimCallTracker.kt:36-46`), which anyone can reverse by trying all numbers | Partial | Keyed hashes (HMAC) for every number key (SP-7, 7.1) | **Better** |

### 3.2 Calls

| # | Claim | What the code does | Theirs | Parley | Verdict |
|---|---|---|---|---|---|
| 11 | Offline spam screening with `CallScreeningService` in under 5 ms | **No `CallScreeningService` exists** (manifest; no class). The `spam_numbers` table can be edited and imported from CSV, but `isSpamNumber` is never called (`DialerRepositoryDelegates.kt:91`, `data/DialerDao.kt:236`), nor is the local `isBlocked` (`util/BlockedNumberContractManager.kt:38`). "Block unknown & private calls" is only an entry in the settings search (`ui/components/GeneralSettings.kt:171`) | Marketing only | `ParleyCallScreeningService` with fixed precedence, a "why" trace, allow rules, schedules, lists, tests | **Better** |
| 12 | Block list | Writes to Android's `BlockedNumberContract` (`BlockedNumberContractManager.kt:84-99`), which Telecom enforces itself. Wildcard rules (`123*`) are kept in Room (`DialerDao.kt:126-127`) and never enforced | Partial | Rules by number, prefix, pattern, country, label, schedule; Undo; emergency never blocked | **Better** |
| 13 | Outgoing caller ID masking (CLIR) | A global switch puts a prefix (default `#31#`) before **every** dialled number except emergency ones (`CallManager.kt:500-504`, `:537-540`). Presets `#31#`, `*67`, `141`, and "Japan (1831)", which is Australia's code (`ui/components/CallingAccountsSettings.kt:371-374`). No exception for service codes, USSD, voicemail or internet-calling accounts; contacts get it too. The "SIM settings shortcut" opens the calling-accounts screen, not Android's caller ID setting | Real, crude | No feature. Typing `#31#0612…` works because supplementary codes go to Telecom (`calltime/Ussd.kt`), and calling abroad leaves such numbers alone (`AssistedDial`) | **Partial** → SD-1, SX-1 |
| 14 | Emergency override | CLIR is skipped for a hard-coded list plus the deprecated `PhoneNumberUtils.isEmergencyNumber` (`CallManager.kt:506-521`); block skips them too (`BlockedNumberContractManager.kt:45`, `:80`) | Real | `EmergencyPolicy`: never blocked, limited, rewritten; emergency call-back window | **Better** |
| 15 | Conference | Merge with `conference()` (`CallManager.kt:439-453`); participants listed (`ui/components/ActiveCall.kt:191-200`). No split or hang-up of one person | Partial | Hold, add, merge, swap and manage conference (split, end one), tested (`ConferenceCallTest`) | **Better** |
| 16 | Waiting calls | Dialog with "Answer & hold" and "Decline" (`ui/components/InCallWaitingCallDialog.kt:150-175`); a waiting tone (`MyInCallService.kt:58-66`). **Bug:** the waiting call's notification Decline runs `CallManager.disconnect()`, which ends the **current** call, the one you are talking on (`MyInCallService.kt:345-347`, `:384-388`, `:208-213`; `CallManager.kt:462-468`). Selecting a held call also un-holds it on its own (`CallManager.kt:277-279`) | Real with a serious bug | Call-waiting sheet (hold & answer, end & answer, decline, reply); every notification action carries the call's ID (`CallActionReceiver`) | **Better** |
| 17 | Ringing | The app rings itself (`IN_CALL_SERVICE_RINGING`, `AndroidManifest.xml:110-112`). It checks only the ringer mode (`MyInCallService.kt:78-135`), so a call Android would keep quiet for Do Not Disturb still rings. It never implements `onSilenceRinger`, so the **volume key, Android's silence and its own flip-to-silence** (`ActiveCall.kt:258-266` calls `TelecomManager.silenceRinger`) **don't stop its ringtone**. The incoming channel bypasses Do Not Disturb (`MyInCallService.kt:632`) | Real with a serious bug | Telecom rings; Parley's ring styles step aside under Do Not Disturb (`CallRinger`); `onSilenceRinger` handled, with its own echo filtered | **Better** |
| 18 | Smart T9 dialpad | Name and number matching with highlighting (`util/T9HighlightHelper.kt`) | Real | T9 in 8 alphabets, CJK, ranking by recency | **Better** |
| 19 | Dual SIM | Choice per call; slot guessed by matching handles, plus vendor extras (`CallManager.kt:545-558`, `util/MultiSimManager.kt`); SIM in Recents partly from its own guess (`SimCallTracker`) | Real | Call pill per SIM, SIM that learns, per-label SIM | **Better** |
| 20 | Quick decline SMS without SMS permission | `call.reject(true, text)` (`CallManager.kt:604-624`) | Real | Reply with a message; Promise a time | **Equal** |
| 21 | Floating call pill ("Dynamic Island") | `SYSTEM_ALERT_WINDOW` overlay with name and photo while you use other apps, hidden on the lock screen (`util/DynamicIslandOverlayManager.kt:95-102`, `:160`); on by default; started even with no call from `DialerApplication.kt:22` | Real | Picture-in-picture, "Return to call" chip, and the status-bar call chip Android gives `CallStyle` notifications. No overlay permission | **Lack (by design)** |
| 22 | Pocket protection | Proximity sensor (`util/CallGestureSensorManager.kt`) | Real | Proximity screen-off once answered; pocket-dial guard | **Equal** |
| 23 | Flip to silence | Accelerometer, then `TelecomManager.silenceRinger` (see 17: doesn't stop their own ringtone) | Real with a bug | Flip to silence (`FlipSilencer`, tested) | **Better** |
| 24 | Shake gesture (code comment) | Detected but never wired (`ActiveCall.kt:258-266` passes no `onShake`) | Stub | — | n/a |
| 25 | Flash alerts on ring | Torch blinks for 45 s (`util/FlashLightManager.kt`) | Real | None; Android 14+ has its own Flash notifications (whether incoming calls flash varies by phone) | **Lack** → SD-3 |

### 3.3 Recording and the "vault"

| # | Claim | What the code does | Theirs | Parley | Verdict |
|---|---|---|---|---|---|
| 26 | Local call recording engine | `MediaRecorder` tries `VOICE_COMMUNICATION`, `MIC`, `VOICE_RECOGNITION`, `DEFAULT` in that order (`util/CallAudioRecorder.kt:132-137`; the README gives a different order). It switches the call to **speaker** and sets the call volume to 55 % so the microphone hears the other side (`util/CallAudioHelper.kt:35`, `:50-90`). Start from the screen, the notification (`MyInCallService.kt:529-574`) or **automatically on every call** (`CallManager.kt:390-401`). **No consent prompt and no announcement**: the start chime is off by default, plays only on your side, and a test enforces it as a "Stealth Privacy Mandate" (`app/src/test/.../CallRecordingComprehensiveTest.kt:241-249`). See 4.2 for why this mostly fails on stock Android 10+ | Partial | No recording; `RECORD_AUDIO` forbidden by the build | **Lack (by design)** |
| 27 | Recordings private ("intentional privacy-by-design", README FAQ) | Files are plain `.m4a` in app storage named `REC_<number>_<time>` (`CallAudioRecorder.kt:116-127`). **Auto-export to `Downloads/SecureDialer` is on by default** (`DialerViewModel.kt:302`; `CallManager.kt:433-434`), where Android indexes them as audio any app with audio-read access can open, the opposite of what the FAQ says | Marketing only | — | n/a |
| 28 | Biometric recording vault | A `BiometricPrompt` in front of the list (`util/VaultBiometricAuthHelper.kt:45-58`), skipped when the phone has no lock; files themselves not encrypted | Partial | — (Parley's equivalent, sealed private data behind an unlock-bound key, is stronger) | n/a |
| 29 | Self-healing vault: "zero dropped recordings after abrupt power cuts" | Re-lists files found on disk by parsing their names (`CallAudioRecorder.kt:269-330`, `DialerViewModel.kt:1164-1185`) and deletes files under 256 bytes. MPEG-4 from `MediaRecorder` gets its index only at `stop()`, so a file cut off by a power loss is listed but won't play | Partial | `DurableFiles` (old or new file, never empty), golden-backup tests | **Better** (as storage resilience) |

### 3.4 Everyday extras

| # | Claim | What the code does | Theirs | Parley | Verdict |
|---|---|---|---|---|---|
| 30 | Fake call simulator | Alarm, full-screen notification, a pretend call screen, then **writes the fake call into Android's call log** as a real call (`ui/MainScreen.kt:610`, `:679` → `DialerRepository.kt:463-487`). On by default (`util/FakeCallReceiver.kt:40`); its full-screen intent also sets "authenticated" (item 6) | Real with a serious bug | Rescue call: Parley's own call screen, never placed and never logged; hidden under duress | **Better** |
| 31 | Callback reminders | Exact alarms (`util/ReminderScheduler.kt:53`); **no boot receiver**, so every reminder is lost after a restart; marked done before the notification is shown | Partial | Remind me, To call, Next up | **Better** |
| 32 | Call notes | Notes per number in the encrypted database; a note prompt after a recording | Real | Pinned notes, notes during a call, Agenda, sealed, hidden under duress | **Better** |
| 33 | Voicemail tab | `hasVoicemail` is always false (`DialerRepositorySystemSync.kt:486`), and every row would show a **hard-coded sample transcript** (`ui/components/VoicemailTab.kt:195`, `res/values/strings.xml:166`) | Stub | Voicemail inbox from `VoicemailContract`: play, seek, transcription, mark heard | **Better** |
| 34 | Network names (CNAP) and "verified" callers | CNAP names kept for every number (`MyInCallService.kt:592-605`). A call is marked **verified** when it has a CNAP name (`DialerRepositorySystemSync.kt:422`), which proves nothing | Real, misleading | Network names opt-in and off by default, tagged "From the network"; STIR/SHAKEN badges from `callerNumberVerificationStatus` | **Better** |
| 35 | Call-log dashboard, themes (AMOLED, avatar shapes, tab order), speed dial, quick replies, 9 languages | Real UI. Notification texts are hard-coded English (`MyInCallService.kt:471-489`), as is the lock screen (`MainActivity.kt:223-244`) | Real | Insights, AMOLED, navigation-bar order, speed dial, replies; English only by design; no avatar shapes | **Better** for the dashboard; **Lack (by design)** for translations |

**Count (Parley against Secure Dialer):** better 23, equal 5 (1, 2, 8, 20, 22), partial 1 (13), lack 4 (21, 25, 26 and row 35's translations), 3 not applicable (24, 27, 28). **Secure Dialer against its own claims:** 14 real (some crude or misleading: 13, 34), 10 partial, 6 real but with a serious bug (3, 6, 16, 17, 23, 30), 2 stubs (24, 33), 3 marketing only (9, 11, 27).

### 3.5 Implementation quality, build and policy

- **Telecom use.** One `InCallService`, one global `CallManager` object with a single "current call" (`CallManager.kt:62-66`). Actions act on "the current call" rather than a call ID, which is how the waiting-call bug (16) happens. Contact lookups run synchronously on the binder thread when a call arrives (`MyInCallService.kt:250`, `:367`). The active-call notification is not a `CallStyle` notification and has hard-coded English action titles ("■ Stop Rec", "Hang Up").
- **Errors.** 226 `catch (_: Exception)` or `catch (_: Throwable)` blocks swallow failures without a trace, including the database fallback and every recording step.
- **Good things worth saying.** `tel:` links from other apps only fill the keypad and never call (`MainActivity.kt:430-440`). PendingIntents are immutable, receivers and the FileProvider are not exported, cloud and device-transfer backups exclude the database, keys and recordings (`res/xml/data_extraction_rules.xml`). Logging is almost absent. The backup cipher choice is sound.
- **Tests.** 118 tests in 19 files (Robolectric and Roborazzi screenshots). Several are tautologies: `DialerEmergencyRoutingTest.kt:50-51` asserts a string the test itself built. Because SQLCipher can't load on the JVM, every database test runs on the silent in-memory fallback, so the encryption, the key manager and the migration are never tested. No test covers a waiting call's notification or `onSilenceRinger`.
- **Build.** CI builds a debug APK and runs **no tests** (`.github/workflows/ci.yml`); lint is advisory (`abortOnError = false`, `app/build.gradle.kts:84-88`); `validate-wrappers: false`. The release signing config **falls back to the public debug key** when the secrets are missing (`app/build.gradle.kts:44-54`). The deprecated SQLCipher artifact 4.5.4 is older than the 16 KB page-size support Android 15 devices need. The Google Services and Secrets Gradle plugins are applied for nothing.
- **Play policy** (they don't publish there, but users meet the same rules elsewhere). `SCHEDULE_EXACT_ALARM` for reminders and fake calls doesn't meet Play's exact-alarm use cases; `SYSTEM_ALERT_WINDOW` needs a declaration; recording without disclosure conflicts with Play's user-data and deceptive-behaviour policies; auto-recording is the pattern Google restricted in 2022.

### 3.6 Lessons Parley already applies (checked)

Each of Secure Dialer's serious bugs maps to a Parley rule already in place, and a test guards most of them: notification actions carry the call ID (`CallActionReceiver` calls `CallManager.reject(id)`); `onSilenceRinger` is handled and Parley's own echo is filtered (`ParleyInCallService.kt:69`, `CallManager.onSystemSilence`); no plain-text or in-memory fallback (6.3); the Keystore failing never deletes data (SECURITY_MODEL "Where data lives"); internal actions are refused when sent by other apps (5.4, `ExportedComponentsTest`); Rescue call is never logged (6.2); number keys are keyed hashes (SP-7). Nothing new to fix in Parley from this list.

## 4. Gaps: are they worth closing?

Each gap is checked the same way: is it useful to Parley's users, and does it fit the principles (offline, private, no new permission without an owner decision, English only, menus of seven items or fewer, no new settings row if avoidable)?

| ID | Gap | Useful? | Fits? | Effort | Recommendation |
|---|---|---|---|---|---|
| **SD-1** | Hide my number (CLIR) for chosen calls | Medium–High: calling a business, a marketplace buyer, a dating match, a landlord; common request in dialer reviews | Yes: a dialling prefix needs no permission; lives in existing sheets and policies | S–M | **Yes, as SX-1, in 7.2** |
| **SD-2** | Call recording | High demand, low feasibility (4.2) | No: needs `RECORD_AUDIO` (forbidden by the build and AUDIT_3 §10), and on stock Android 10+ still can't hear the other side without accessibility abuse or root | L, and mostly broken | **No.** Add an honest Help entry, "Can Parley record calls?" (S) |
| **SD-3** | Flash on ring | Low–Medium (hard of hearing; phone face-up on a desk) | Building it needs no permission (`setTorchMode`), but it would be a new setting, and Android 14+ already has Settings › Accessibility › Flash notifications (calls included on many phones, to be checked on the device run) | S | **No feature.** One Help line and a link to Android's setting on 14+ |
| SD-4 | Floating call pill over other apps | Low: picture-in-picture, the "Return to call" chip and Android's status-bar call chip already cover it | No: needs `SYSTEM_ALERT_WINDOW`; Parley declares `HIDE_OVERLAY_WINDOWS` against exactly such windows | M | **No** |
| SD-5 | Avatar shapes, shake gesture, recording quality presets, translations | Low | Translations are ruled out (English only); the rest add settings for little value | — | **No** |

### 4.1 SD-1 in brief

Typing a prefix by hand works today but is easy to forget, wrong abroad (`*67` means nothing outside North America), and leaves `#31#…` in the dialled number, so history may not name the person. Secure Dialer's global switch shows the opposite failure: it hides your number from your mother too, and would prefix service codes. SX-1 (section 5) is the design.

### 4.2 Call recording on Android 10–16: what is and isn't possible

**What Android allows a normal app (not system, not root):**

- **The call's own audio streams are closed.** `AudioSource.VOICE_CALL`, `VOICE_UPLINK` and `VOICE_DOWNLINK` need `CAPTURE_AUDIO_OUTPUT`, a signature/privileged permission that no store or sideloaded app can hold (since Android 9 in practice). This is the only clean two-sided recording, and only the phone maker's dialer or a privileged system app gets it.
- **The microphone during a cellular call is usually silenced.** Since Android 10 the audio policy decides who may capture while a call is in progress, and ordinary apps receive silence then; accessibility services are the notable exception. Before that, since Android 9, any app not in the foreground already receives silence, and since Android 11 background microphone use needs a microphone-type foreground service started while visible. Phone makers vary: some ROMs leak the microphone, many don't. Being the default dialer does not change this.
- **Speakerphone plus microphone** (Secure Dialer's method) is, at best, a room recording: your voice clearly, theirs through the loudspeaker and the room, while `VOICE_COMMUNICATION` (its first choice) applies echo cancellation and noise suppression meant to remove exactly what comes out of the speaker. It also forces every recorded call onto the speaker, where anyone nearby hears both sides.
- **Accessibility services** are how most "call recorder" apps got around the silence. Google Play has banned using the Accessibility API for remote call audio recording since May 2022; Android 13+ blocks turning on accessibility for sideloaded apps until the user digs through "Allow restricted settings"; and the same route is what stalkerware uses. Parley must not ship an accessibility service for this.
- **Call screening audio.** Since Android 10, the default dialer may put an incoming call into background audio processing (`Call.enterBackgroundAudioProcessing`, with `AudioManager.MODE_CALL_SCREENING`) to talk to a caller before you answer, as Pixel's Call Screen does. It needs `RECORD_AUDIO`, covers only the moment before answering and only makes sense with speech recognition. Not recording, and not for Parley.
- **Root.** A privileged module (for example BCR, GPL, installed through Magisk as a system app holding `CAPTURE_AUDIO_OUTPUT`) records `VOICE_CALL` cleanly. It is a separate app; Parley can mention it in Help without endorsing it.
- **The phone maker's dialer.** Google's Phone app records in some countries and announces it to both sides; Samsung's and Xiaomi's dialers record where the law allows. That is the honest answer for most people who need recordings.

**Law and consent.** Many places allow recording a call you take part in (one-party consent: most US states, England and Wales for personal use). Many require everyone's consent (California, Florida, Washington, Illinois and others in the US; Germany makes secret recording a criminal offence, §201 StGB; several EU states); and in the EU a recording of someone else is personal data under the GDPR. An app that records silently, by default or on every call, puts its users at risk in exactly the places where privacy matters most. Secure Dialer's chime is local-only and off by default, its auto-record switch records everyone, and its exports land in a public folder.

**What Parley does instead.** Parley's answer to "I want proof of what was said" is the **Call receipt** (SX-2) and the call record already planned (OPENCONTACTS_LOGGER_STUDY NX-1): what was agreed, when, by whom, with a fingerprint, and no audio. The Help entry from SD-2 says plainly what Android allows and points to the phone maker's dialer.

## 5. Beyond both apps

**Principles** (Parley's, applied here):
- **No new permission.** Every idea below works with Parley's current manifest; SX-5 uses another app's recorder through an intent, which Parley's build allows.
- **Honest.** Parley says what it asked the network for, not what the network did ("asked to hide your number", never "your number is hidden").
- **Actions and per-use choices, not settings rows.** Policies live where policies already live (labels, a contact's call choices, Situations).
- **Private contacts and duress.** Each feature works for private contacts while they are visible, never exposes them while hidden or locked, and has a line in the duress test.

### 5.1 Ranked

| Rank | ID | Feature | Value | Effort |
|---|---|---|---|---|
| 1 | SX-1 | Hide my number, done right | High (daily for some) | M |
| 2 | SX-2 | Call receipt | High (disputes, companies, complaints) | S–M |
| 3 | SX-3 | Something else is recording | Medium–High (safety, stalkerware) | S–M |
| 4 | SX-4 | Honest self-check of sealed data | Medium (trust; rare but serious) | S |
| 5 | SX-5 | A voice memo after the call | Medium | M |

### 5.2 Each feature

**SX-1. Hide my number, done right.**
- *Problem:* people want their number hidden from some calls (a buyer on a marketplace, a business that will put them on a sales list, a first call to a dating match), not from everyone. Secure Dialer's single switch hides it from family too, uses one prefix whatever the network, and would prefix service codes. Typing `#31#` by hand is easy to forget and wrong in North America.
- *How:* three places, none of them a new settings row.
  1. **Per call:** the long-press Call sheet (Call with a reason) and the Call pill's long-press get **"Call with my number hidden"**. The keypad's ⋮ gets the same item only if it has room under seven.
  2. **Per person or label:** a contact's call choices and a label's policy (`LabelPolicy`, next to its SIM) get **"Hide my number when I call"**. A Situation (`Situation`) gets the same lever, so "Selling my sofa" or Travelling can hide it for a while and put it back when turned off.
  3. **The right code for the SIM:** the prefix comes from the calling SIM's country, not a setting: `#31#` (3GPP, the default), `*67` in North America, `141` in the UK, `1831` in Australia, `184` in Japan, kept in one small table with a test per entry. The reverse codes (`*31#`, `*82`, `1470`, `1832`, `186`) give **"Call with my number shown"** for people whose line hides it by default.
  - **Never prefixed:** emergency numbers and the emergency call-back window (`EmergencyPolicy`, `TelephonyManager.isEmergencyNumber`), service and USSD/MMI codes (`Ussd`), voicemail, internet-calling and SIP accounts (`InternetCalls`), numbers that already start with a code. Calling abroad (`AssistedDial`) converts the number first, then the code is added.
  - **History stays readable:** `NumberKeys` strips a leading CLIR code before matching, so Recents, number history and Insights name the person, with a small "number hidden" mark on that call (stored in `call_facts`, not in Android's log).
  - **Refused hidden calls:** some people and companies reject anonymous calls. When a hidden call ends before it rang with a network rejection, the call-ended screen (`CallFailure`) offers **"Call again showing my number"**, worded as a guess ("Some lines refuse hidden numbers").
  - **Honest call screen:** while dialling, one quiet line: "Asked the network to hide your number". Help explains that the network decides, that some carriers ignore it on VoLTE or need the service on the account, and how to check with a friend's phone.
- *Reuses:* `CallReason`'s sheet, `LabelPolicy`, the contact's call choices (`CallerChoices`), `Situation`, `SimChoice`, `AssistedDial`, `EmergencyPolicy`, `Ussd`, `InternetCalls`, `NumberKeys`, `call_facts`, `CallFailure`. *Privacy:* adds privacy; stores one boolean per policy and one fact per call. *Effort:* M. *Risk:* a wrong code for a country would break a call (hence the table's tests and the default `#31#`); `LabelPolicy` and `Situation` gain an optional field (older versions ignore it; backup round-trip test). Android's own "Caller ID: hide" network setting can't be set by an app (no public API); Help links to `TelecomManager.ACTION_SHOW_CALL_SETTINGS` where the phone offers it.

**SX-2. Call receipt.**
- *Problem:* the main reason people want call recording is proof: "the agent promised a refund by Friday", "the landlord agreed to fix the boiler". A recording is often illegal to make or to share, and nobody listens to an hour of hold music to find one sentence. Secure Dialer offers audio with no consent and no structure.
- *How:* on the call-ended screen and in a call's actions in Recents, **"Make a call receipt"**. It fills itself from what Parley already knows: who (name, number, the SIM), when (start, answer, end, with the time zone), how long, whether the network verified the caller (STIR/SHAKEN), the menu keys pressed (`MenuKeys`), reference numbers typed or pasted during the call, Agenda lines ticked, and promises. It then asks two optional questions: **"Who did you speak to?"** and **"What was agreed?"** (with a date picker for "by when"). The receipt is sealed, attached to the number's history and, if one is open, to its case file. **Share** makes a one-page PDF with a SHA-256 fingerprint of its content printed at the bottom, the same layout as the case-file report and NX-1's call record. Making one during the call is one tap from the notes field.
- *Reuses:* `CaseReport`/`CallExport` (PDF via `PrintManager`), `MenuKeys`, `Agenda`, `PromiseTimes`, `RecordCrypto`/`SealedDocument`, `call_facts`, the verification badge, `NumberOwners`. *Privacy:* made only when you ask; sealed; hidden under duress like notes; a private contact's receipt needs the unlock to share. *Effort:* S–M (most parts exist). *Risk:* overclaiming: the wording says "your notes of the call", never "evidence".

**SX-3. Something else is recording.**
- *Problem:* Secure Dialer shows how easy it is for an app to record the microphone during calls when a phone maker allows it, and stalkerware does the same through accessibility. Android 12+ shows a green microphone dot, but during a call the phone app itself lights it, so another recorder hides in plain sight.
- *How:* while a call is active, Parley listens to `AudioManager.registerAudioRecordingCallback` and reads `getActiveRecordingConfigurations()`. Both are public and need **no permission**; they report that some app is capturing and from which source, not which app (Android hides the identity from non-system apps), and since Android 10 whether Android silenced it (`isClientSilenced`). A normal cellular call doesn't appear there, because the modem carries its audio. If a capture is active and not silenced, the call screen's detail line says **"Another app is using the microphone"**, and More › **"Who's listening?"** explains, with buttons to Android's privacy dashboard and the microphone quick toggle (Android 12+). If Android silenced it: "An app tried to record; Android kept it silent." Nothing is stored except a ring-fact line in the number's history, if you tap "Remember this".
- *Reuses:* the call screen's detail line and More sheet, `RingFacts`/`call_facts`, Help topics. *Privacy:* reads only that a capture exists, never audio. *Effort:* S–M. *Risk:* false alarms from hotword detection, Voice Access or the car: ignore the `HOTWORD` source, wait a few seconds before saying anything, and test on several phones (device checklist row). If tests show OEMs report Parley's own Telecom audio there, the feature waits.

**SX-4. Honest self-check of sealed data.**
- *Problem:* Secure Dialer's "self-healing" deletes the database whenever its key fails, without saying so. Parley already fails closed, but a user whose phone lost Keystore keys (some OEM updates, a factory-reset-and-restore of app data) sees only empty screens or quiet errors.
- *How:* Help & troubleshooting gets **"Check Parley's data"**. It tries to open each sealed store (vault, private calls, archive, small records, case files, snapshots) without showing any content, and lists which open. For one that doesn't, it says what that means and offers **Restore from your latest backup** for that part, keeping the unreadable files aside rather than deleting them. The daily upkeep runs the same probe silently and, only when something can't be opened, shows one notification ("Parley can't open part of its data. Check it.").
- *Reuses:* the stores' existing open paths, the crash card, Backup's restore preview and part choices (SP-8), `DurableFiles`, the daily upkeep. *Privacy:* reads nothing out; under duress the hidden stores are reported as fine. *Effort:* S. *Risk:* a probe that itself fails on a locked phone: run it only when unlocked.

**SX-5. A voice memo after the call.**
- *Problem:* after a long call people want to say, not type, what they must remember. Parley can't use the microphone.
- *How:* on the call-ended screen and in "Anything to remember?", **"Record a voice memo"** asks the phone's recorder app for one recording (`MediaStore.Audio.Media.RECORD_SOUND_ACTION`). The returned audio is copied into Parley's sealed storage and attached to the call's note; the shared copy is released at once. It is never offered during a call (the recorder would be silenced and it would be call audio). On phones with no app for that intent, the button doesn't appear.
- *Reuses:* the photo flow's "ask another app, copy, forget" pattern (Take photo, 5.2), `RecordCrypto`, notes. *Privacy:* your own voice only, sealed, hidden under duress, in backups only if notes are. *Effort:* M. *Risk:* many phones have no app that answers this intent, so it may reach few people; AUDIT_3 §10 says "no call audio", and although this isn't call audio it is audio, so it needs the owner's yes (S7).

## 6. Plan

7.1 "Clearer records" is being built now; nothing from this study joins it. The OpenContacts and Logger study already places NX-1, NX-5 and NX-6 in 7.2 and NX-4 in 7.3.

| Release | From this study | Why here |
|---|---|---|
| **7.2** | **SX-1** Hide my number; **SD-2** and **SD-3** Help entries; **SX-4** self-check | SX-1 is the one real gap and builds on calling abroad and label policies. The Help lines and the self-check are S and need no UI elsewhere |
| **7.3** | **SX-2** Call receipt (with NX-1's call record, sharing its PDF and fingerprint); **SX-3** Something else is recording, after a device probe on three phones | SX-2 and NX-1 share one layout; SX-3 needs device results before it ships |
| **Later** | **SX-5** voice memo, only if S7 is yes and a device survey shows enough phones answer the intent | Reach is uncertain |

**Every item** lands with its Tools row or Help line, a device-checklist line and a duress-test line (the AUDIT_3 rule), adds no settings row, and keeps every menu at seven items or fewer. The only new choices sit inside existing sheets and policies: the Call sheet, a contact's call choices, a label's policy and a Situation.

## 7. Decisions for the owner

| # | Question | Recommended answer |
|---|---|---|
| S1 | Build Hide my number (SX-1) per call, per contact or label, and as a Situation lever, with the code chosen from the SIM's country? | **Yes, in 7.2.** No global switch: per call and per policy only |
| S2 | Which codes ship in the table? | `#31#`/`*31#` (default), North America `*67`/`*82`, UK `141`/`1470`, Australia `1831`/`1832`, Japan `184`/`186`; others only after a user confirms them on a real line |
| S3 | Offer "Call again showing my number" after a hidden call is refused? | **Yes**, worded as a guess |
| S4 | Add call recording (`RECORD_AUDIO`, speaker and microphone, or an accessibility service)? | **No.** Keep the build's ban. Add the Help entry "Can Parley record calls?" (SD-2) |
| S5 | Add Parley's own flash on ring or a floating call pill (`SYSTEM_ALERT_WINDOW`)? | **No** to both. A Help line points to Android's Flash notifications (SD-3) |
| S6 | Build the Call receipt (SX-2), with a fingerprint on the shared PDF and no signature? | **Yes, in 7.3**, alongside NX-1, wording "your notes of the call" |
| S7 | Allow a voice memo after the call through the phone's recorder app (SX-5), given AUDIT_3 §10's "no call audio"? | **Later, and only if a device survey shows it reaches enough phones.** It isn't call audio, but it is audio, so it is your call |
| S8 | Warn on the call screen when another app is capturing sound (SX-3)? | **Yes, in 7.3**, only after a three-phone probe shows no false alarms from Parley's own call |
| S9 | Add "Check Parley's data" and the silent daily probe (SX-4)? | **Yes, in 7.2.** One notification only when something can't be opened; never deletes. **Accepted as recommended (7.2):** the user-run "Check Parley's data" self-check shipped (`DataSelfCheck`): read-only, counts rows and confirms each sealed record decrypts, checks the line keys and a backup dry run, and reports in plain words. It never writes, migrates, deletes or re-creates anything; on any failure it only reports and points to Backup's restore. A Tools row and a Help entry, no new permission, English only. The silent daily probe and its one notification are not in this change |
| S10 | Add the SD-2 and SD-3 Help entries now? | **Yes, in 7.2.** Help text only. **Accepted as recommended (7.2):** both Help entries shipped as listed task pages (`HelpTopic.WHY_NO_RECORDING`, `HelpTopic.FLASH_ON_RING`): "Why Parley doesn't record calls" (from §4.2: Android closes call audio to apps, the microphone is muted to apps, the workarounds are unsafe or banned, consent law; it points to the written record Parley keeps — Case files now, Call receipt/record coming) and "A flashing light when the phone rings" (points to Android's own Settings › Accessibility › Flash notifications). No new permission, no new setting, English only |
| S11 | Adopt avatar shapes, a shake gesture, recording quality presets or translations? | **No** |
| S12 | Borrow any Secure Dialer code (GPL-3.0)? | **No.** Nothing here is better than Parley's own, and its crypto and call paths have the bugs listed in section 3 |

**Accepted as recommended (7.2).** SX-1 "Hide my number" (S1, S2, S3) is built in 7.2 as recommended: no global switch — per call (keypad long-press / call menu) and per policy (a contact's or a label's "Always hide my number", and a Situation's "Hide my number" lever); the code is chosen from the calling SIM's country, with the table of S2 and the `#31#`/`*31#` fallback; emergency numbers, service/USSD codes and internet/SIP calls are never prefixed; the code is stripped from history and redial so a person is still named; and "Call again showing your number" (S3) is offered, worded as a guess, after a hidden call the other side turns away. The code is `app.parley.common.calls.HideNumber`. (SD-2, SD-3 and SX-4, also accepted for 7.2, are separate items.)
