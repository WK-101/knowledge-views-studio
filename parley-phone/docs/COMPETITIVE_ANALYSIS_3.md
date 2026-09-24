# Parley vs. six more open-source apps, and the consolidated roadmap

*24 Sep 2026. Round 3. Round 1: [COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md); round 2: [COMPETITIVE_ANALYSIS_2.md](COMPETITIVE_ANALYSIS_2.md), updated with the findings from this round. **§8 replaces round 2's §7 as the plan we implement.***

**What was read.** The full source of each app: every Kotlin, Java and Dart file, the manifests, layouts and preference XML, English strings and build files. Also each issue tracker, sorted by reactions and comments.

| App | Source | Lines read | What it is |
|---|---|---|---|
| **Call Blocker** `com.callblocker` | [gitlab abelardodiaz/web26-050-call-blocker](https://gitlab.com/abelardodiaz/web26-050-call-blocker) v0.3.4 | ~7.3k | Small Compose blocker (list plus prefix), no dialer |
| **SpamBlocker** `spam.blocker` | [aj3423/SpamBlocker](https://github.com/aj3423/SpamBlocker) v5.17 | ~17k in full + ~24k skimmed (of ~52k) | The most feature-rich FOSS blocker: rule engine, SMS, online lookups, workflows |
| **Call Limiter** `com.thirumalai.calllimiter` | [Thiru-Malai/CallLimiter](https://github.com/Thiru-Malai/CallLimiter) v0.3.10 | ~5.5k | Hangs up a chosen number's calls when its time budget runs out |
| **Emerald Dialer** `ru.henridellal.dialer` | [HenriDellal/emerald-dialer](https://github.com/HenriDellal/emerald-dialer) v1.0.23 | ~6.1k | Lightweight T9 dialer front-end with 25 keypad alphabets |
| **WhatsOpen** `com.harshalbhatia.whatsopen` | [harshalbhatia/whatsopen](https://github.com/harshalbhatia/whatsopen) v1.1.3 | ~3k | Opens a WhatsApp chat with an unsaved number |
| **Logger** `com.logger.app` | [Sanmeet007/logger](https://github.com/Sanmeet007/logger) v3.4.2 (Flutter) | ~14.3k | Call-log viewer, analytics, export and import |

## Implementation status (v3.0)

Everything in round 2 and round 3 is built, except:

- **Sandbox profile** (round-2 §5.4 step 5): **skipped for now**, by decision. It would give Parley device-management (profile-owner) rights over a work profile it creates, which is a large new privilege and security surface. It may come later as a separate optional app, like the list updater.
- **India telemarketing template:** shipped as opt-in and warn-only. India's regulator says calls from its 140 series shouldn't be tagged or filtered by apps, so it may be removed.
- **Portugal template:** not shipped; no official telemarketing prefix was found.

The whole merged code was reviewed. All 30 confirmed findings were fixed, 4 of them critical: an emergency call-back could be cut by a call limit; a transient key error wiped the call archive; SIM allow rules didn't apply while screening; restored label rules pointed at the wrong label. The on-device checklist is in [TESTING.md §9](TESTING.md).

---

## 1. Verdict

- **Scope.** None of the six is a full phone app. Parley remains the only one of the fourteen apps analysed so far that combines contacts, dialer, in-call screen, screening, backup and vault with no INTERNET permission.
- **The same bug again.** SpamBlocker and Logger both treat a contacts lookup that *failed* as "not a contact", the bug Parley fixed in 966f8cd. That makes **four apps across two rounds**. It is a genuinely common trap, so we keep a regression test for it and mention it in the store listing ("never blocks a contact, even when the phone is busy").
- **Two new Parley bugs found in this round:**
  1. **Keypad search misses Ukrainian, Belarusian, Bulgarian, Serbian and Macedonian letters** (і ї є ґ ў ѝ ј љ њ ћ ђ џ ѕ ѓ ќ). `T9.kt` has no mapping for them, and its accent-stripping fallback only checks the Latin and Greek tables. Names like "Олексій" and "Ігор" don't match. Fix in v2.1 (S).
  2. **The screening service logs a blocked call before answering Android.** `CallScreener.screen()` writes the blocked-call log inside the 3-second budget, *before* `respondToCall`. A slow database write delays the answer. Fix: respond first, log afterwards (S).
- **One wording issue to verify on devices.** The AOSP call-log provider historically keeps only about 500 recent rows per account when Telecom inserts calls. Parley's privacy screen says "Call history kept until you delete it", which may not be true on every phone. The fix is a private call-history archive (Logger item L1 below), or softer wording.
- **Three new capability areas that no earlier round covered:**
  - **Messaging numbers you haven't saved**, from WhatsOpen: WhatsApp, Signal, Telegram and Viber links sent straight to the app, never through a browser.
  - **Call time limits and plan minutes**, from Call Limiter. As the phone app, Parley can do this more reliably with no extra permissions.
  - **Call history as data**, from Logger: a full archive, readable exports and offline insights.
- **What this round changes in round 2:**
  - SpamBlocker and Call Blocker reshape the blocking plan:
    - **fixed precedence**, not numeric priorities;
    - **a stored "why" trace** for each call;
    - **schedules and quiet hours**;
    - **label-based rules**;
    - an **"Expecting a call" snooze**;
    - **per-rule SIM**, which replaces per-SIM profiles.
  - Two Android facts constrain the design (§3.1):
    - Android's screening service never sees hidden numbers or which SIM a call came in on.
    - It skips contacts unless the screening app can read contacts.

---

## 2. App by app

### 2.1 Call Blocker (`com.callblocker`)

**Architecture.** Compose, Hilt and Room, organised as "clean architecture". It blocks through a CallScreeningService on Android 10 and later, and through PHONE_STATE plus `endCall` on Android 9. It has no dialer.

**Headline flaw: it never requests a runtime permission or the screening role.** There is no RoleManager call and no permission launcher anywhere.
- READ_CONTACTS is declared but never requested. **AOSP only passes calls from contacts to a screening app that holds READ_CONTACTS.** The author misdiagnosed this as a "Samsung contact bypass" and tells users to *delete the contact*.
- The `Call.Details` given to a screening service has a **null account handle**. Calls with restricted, unknown or payphone presentation **never reach screening**. So its per-SIM and private-number blocking are dead code, and the README still advertises them.

**Other bugs:**
- Number normalisation only handles Mexico and the US (`+52 1…` never matches).
- Stored entries are never normalised.
- User-typed `_` and `%` act as SQL wildcards.
- Restore never restores settings and duplicates the call history.
- It writes to its database *before* `respondToCall`, with no timeout.
- `allowBackup=true`, incoming numbers in logcat, plaintext backups written to public Downloads.

**Worth copying:**
- the "blocking method" diagnostic row, which Parley should do properly (see B15);
- filling in the SIM from the call log after a block, for screening-only mode;
- its clean `.cbbk` format (AES-GCM, PBKDF2), which Parley can import.

**Avoid:**
- permanently disabled "Coming soon" switches;
- a README that claims features the code doesn't have;
- a forced dark theme;
- easter-egg settings;
- trash icons with no undo.

**User asks:**
- #2: block numbers that are the wrong length for their region.
- F-Droid forum: "a simple, maintained blocker".

### 2.2 SpamBlocker (`spam.blocker`)

**Architecture.**
- Compose, plain SQLite and WorkManager.
- The engine is a list of `IChecker`s with numeric priorities. The first non-null result wins.
- It sits *next to* the user's own dialer and SMS app. It holds the screening role, listens for `SMS_RECEIVED` and takes over SMS notifications.
- It has 17 permissions, including INTERNET, SEND_SMS, READ_SMS, usage access, overlay, WRITE_SETTINGS and notification-listener access.

**What it has:**
- **Quick features:** contacts (lenient allow, or strict "block non-contacts"), STIR/SHAKEN, spam database with a time-to-live, an on-device Naive Bayes classifier for SMS, repeat callers (with max *and min* interval), numbers you dialled, numbers you answered (with a minimum duration), off time, an **emergency window** with extra trigger numbers, recent apps, meeting mode.
- **Regex rules:**
  - Matched on number, contact name or group, **contact prefix** (a clinic's other lines), caller name (CNAP), offline **geolocation**, carrier, or **database prefix**.
  - Each rule can set: allow or block; call, SMS or both; a **schedule**; a **SIM slot**; how to block (reject, silence, or answer and hang up); a notification channel.
- **SMS/push alerts:** allow calls for N seconds after an SMS or another app's notification matches a pattern (for example, the delivery driver).
- **Instant query:** several HTTP lookup services raced in parallel inside the 5-second screening deadline.
- **Reporting:** automatic reports wait a **1-hour regret window**, cancelled by a call-back, a dial-back, or saving the number as a contact.
- **"Bots":** about 25 chainable workflow actions (HTTP, CSV/XML parsing, importing lists, changing rules in memory for one call, backup).
- **History:** expandable **decision trace**, "what would current rules do" indicators on past calls, a **test dialog**, and a **priority-conflict detector**.
- **Backup:** grouped by category.

**Bugs:**
1. **Fail-closed contact lookup.** A provider exception means "not a contact", so the call is blocked, and the number can even be *reported online*. This is the fourth app with this bug.
2. **Unit mix-up in "throttle after short answered calls".** It compares seconds with milliseconds, so after any normal call it blocks everyone for the throttle window.
3. **Recurring calendar events are ignored:** it queries `Events`, not `Instances`, and a null title crashes it.
4. **The "simulation" test has real side effects:** it sends real auto-reply SMS, sends real reports, and updates rate-limit state.
5. **Exported service with no permission.** `PublicSMSScreeningService` answers "is this number a contact?" to any app, so apps without contacts access can probe the address book.
6. **"Remote setup" over plain HTTP.** Cleartext traffic is allowed, so an attacker on the network can replace every rule, including SMS-sending workflows.
7. **Backups include API tokens by default,** are plaintext, and `allowBackup=true`.
8. **Number handling:** `clearNumber` strips every leading zero, which merges `00` with the trunk `0`.
9. **Speed:** whole call-log types are scanned in Kotlin inside the screening deadline, and regexes are recompiled per call.
10. **Ringtone per rule** overwrites the *system* ringtone, so a crash leaves it changed.
11. **"Answer and hang up"** confirms to spammers that the number is live.

**Pain points:**
- Other apps' screening bypassed and the system dialer rings anyway (#362, 46 comments; #665).
- Duplicate notifications and log entries (#29, #385).
- Caller card for *allowed* calls (#553).
- Bots redialling to get past "repeat callers" (#604).
- "How do I remove INTERNET?" (#147).
- Rules per SIM (#169, #59).
- Sharing lists with family (#549, #375).
- A million-number import (#594).
- Test a real call (#386).
- "How priority works" is FAQ #1 (#166).
- Profiles and schedules (#414, #359).

**Parley answers the biggest of these by design.** It *is* the dialer, so a blocked call can't ring anyway (`ScreeningGuard` re-applies the decision inside the InCallService). It owns the missed-call notification and the call log. It has no INTERNET permission.

**Deliberately not adopting:**
- the workflow engine;
- online lookups;
- SMS screening;
- meeting mode and recent apps (need usage access);
- push alerts (need notification access);
- answer and hang up;
- an overlay.

Each would break Parley's permission promise or bloat the app. The "Expecting a call" snooze (D5) covers most of what people use them for.

**Note for any future lookup design.** A SHA-1 hash of a phone number is *not* private: there are only about 10¹⁰ possible numbers, so it is trivially reversible.

### 2.3 Call Limiter (`com.thirumalai.calllimiter`)

**What "limiting" means here:** each chosen number (or all numbers) gets a time budget, per call or per day. When it runs out, the call is hung up. A beep plays in the earpiece first (`ToneGenerator(STREAM_VOICE_CALL)`; the caller can't hear it), with a double vibration. A countdown notification shows the time left, and tapping it cancels the limit.

**How it's built:**
- an always-on foreground service;
- the deprecated `PhoneStateListener`;
- `Handler.postDelayed`;
- `TelecomManager.endCall()`;
- 11 permissions, including the MANAGE_OWN_CALLS trick for the foreground-service type.

**Bugs:**
- Crash on an empty number (#32, during a WhatsApp call).
- Crash after permissions are denied (#59).
- Double callbacks, one with and one without the number, so the global limit gets keyed on "".
- **`endCall()` rejects a waiting call instead of ending the limited one.**
- Timing drifts while the phone sleeps.
- The notification is re-posted every second.
- **The daily quota resets on any reboot or clock change.**
- The quota can be bypassed through the emergency buffer.
- **The global limit defaults to 0 s:** it cuts every call.
- Typed and picked numbers never match (raw vs national format).
- Crash on Android 8–9.
- Hang-up is silently disabled if the ANSWER_PHONE_CALLS permission is denied.
- Editing a limit is only possible by long-press.

**Use cases from the tracker:**
- self-control;
- elderly relatives (#54);
- a carrier that charges after 2 hours (#20);
- sound and vibration choice (#62, #65);
- battery (#63).

**Why Parley can do it better.** Parley holds real `Call` objects. It can:
- start the budget when the call is actually answered (`connectTimeMillis`), with no guessed start buffer;
- end *that specific* call with `call.disconnect()`;
- run nothing between calls.

### 2.4 Emerald Dialer (`ru.henridellal.dialer`)

**What it is.** A front-end that fires `ACTION_CALL` at the system phone app: there is no in-call screen and no default-dialer role. The call log sits above the keypad and switches to T9 results when you type.

**Standouts:**
- **Keypad alphabets as data:** 25 languages, each defined as a regex per digit in string resources. Keys show **two rows of letters**: Latin plus the local alphabet.
- **Arabic:** optional vowel marks and Arabic-Indic digits.
- **Hebrew:** final letter forms grouped with their base letters.
- **0 = space and 1 = punctuation** in name searches.
- **Pinyin T9** for Chinese: a backtracking matcher over initials and full syllables, including characters with several readings.
- **Hardware keyboards:** on QWERTY phones letters search as text; on 12-key phones the on-screen keypad hides; Enter calls the focused result; the D-pad works.
- An **editable number** with a cursor and paste.
- **One result row per phone number**, labelled "(Primary)".
- **Delete a number's calls by date range.**

**Bugs:**
- **Ё uses the *Latin* Ë** in the Russian, Ukrainian and Sakha keymaps, so "Алёна" never matches.
- **Default contact sources exclude Google-synced contacts,** which is probably the cause of the top bug, #39 "T9 not working".
- Regexes recompiled on every keystroke, and no recency ranking.
- Names only from the call log's cached name (#29).
- `*#06#` calls `getImei()`, which is impossible for third-party apps.
- No content descriptions anywhere.
- Text sized in dp, so it ignores the system font size.

**Parley check.** "Empty call button redials the last number" (round-2 A7) **is already built** (`KeypadTab.callNow()`).

### 2.5 WhatsOpen (`com.harshalbhatia.whatsopen`)

**What it is.** Three tabs: by number, call log (200 entries, filters), clipboard. It also accepts shared text and registers for `tel:` links. It opens WhatsApp through `https://api.whatsapp.com/send?phone=` with an **explicit package**, so no browser is involved; consumer WhatsApp wins over Business.

**Bugs:**
- **National-format call-log numbers get the wrong country** (#5: Pakistani `03…` becomes +32 Belgium).
- The trunk `0` isn't stripped.
- "+1 555-123-4567" is split on spaces and opens +55 Brazil.
- The clipboard is read on every resume, so Android 12+ shows its "pasted" toast.
- Shared text auto-launches WhatsApp without confirmation.
- The `tel:` registration hijacks phone links.
- The phone number is written to logcat.

**Pain points:**
- the permissions (#4);
- the wrong country (#5).

**Link recipes Parley can use** (sent with an explicit package, never falling back to a browser):
- WhatsApp: `https://wa.me/<digits>?text=` (`com.whatsapp` / `com.whatsapp.w4b`).
- Signal: `sgnl://signal.me/#p/+E164` (`org.thoughtcrime.securesms`, `im.molly.app`).
- Telegram: `tg://resolve?phone=<digits>`. The recipient's privacy settings may prevent it.
- Viber: `viber://chat?number=%2B<digits>` (verify on a device).
- Threema: has no phone-number link; it only works through a saved contact.
- SMS: `smsto:`.

### 2.6 Logger (`com.logger.app`)

**What it is.** Flutter with Riverpod. Four tabs: Logs, Analytics, Tracklist, Settings.
- **Logs:** grouped by day; **tap a day header for that day's summary**; swipe to call, SMS or WhatsApp; a details sheet with a "quick filter" for the number; 4 grouping modes.
- **Filters:** number, unknown only, SIM, duration, 10 call types, date presets, and **saved presets**.
- **Analytics:** counts, average, longest and total talk time, an in/out donut chart, most-called, top 5 longest.
- **Tracklist:** up to 10 names, each with an interaction score that decays with time, days since the last call, weekday bars.
- **Export:** CSV, JSON and **iCal (one event per call)**, with strftime-style file-name tokens.
- **Import:** its own CSV format.
- **Billing:** round calls up to whole minutes.

**Bugs:**
- **Rounding overwrites the stored durations,** so exports are corrupted.
- iCal share and open produce empty files.
- Only the name is escaped in CSV, with no formula-injection guard, and the importer only accepts LF line endings (#52).
- Import doesn't deduplicate and marks every call as NEW.
- **A failed contact lookup is cached as "no contact"** (the fourth fail-closed app).
- The trunk `0` isn't stripped, so the same person counts as two.
- Main-thread lookups; the whole log is copied across isolates about 11 times.
- File-name tokens can produce `/` and `:`.
- The privacy text isn't true: plaintext export files stay in the cache.
- Tracking is keyed on display name (#55).

**Pain points:**
- The log thins out over time; a memorial use case (#27, #53).
- Notes in exports (#54).
- In/out totals and weekly talk time (#57, #49).
- All of a contact's numbers (#55).
- Billing and carrier totals (#40, #41, #28).
- Import errors (#52).
- Import from Callyzer (#26).

---

## 3. What this round changes in Parley's design

### 3.1 Two platform facts (confirmed in AOSP `CallScreeningService` docs, and shown by Call Blocker's dead code)

1. **The screening service gets no SIM account handle, and hidden, unknown or payphone callers never reach it.**
   - Parley already checks hidden callers inside `CallManager.onCallAdded` (the InCallService path, `handlePresentation`), so hidden-number blocking works *while Parley is the phone app*.
   - Per-SIM rules must also be evaluated there.
   - In screening-only mode, both have to be shown as unavailable.
2. **Contacts only reach a screening app that holds READ_CONTACTS.**
   - Parley holds it through the dialer role, so contacts always win, including vault contacts.
   - If Parley is only the screening app and contacts access is off, the banner must say so.

### 3.2 Fixed precedence, not numeric priorities

SpamBlocker's #1 FAQ is "how does priority work?", and it needed a conflict detector. Parley keeps a fixed order that users can predict:

**emergency window › contacts and vault › allow rules (including snooze, dialled, answered, label, contact prefix) › block rules › lists › default.**

Repeat callers can override *soft* reasons only (as today). They never override an explicit block rule, and instant redials (under the minimum interval) don't count.

### 3.3 Every decision gets a trace

The blocked log and number history store the evaluated steps, for example "Contact? no → Allow rules: none → Rule 'Telemarketing' matched → Silence". A "!" marks a lookup that failed open. Allowed calls get "Why did this ring?". The trace is shared by the blocked log, Recents long-press, the dry run and coverage replay.

---

## 4. New adopt items from round 3

Round-2 item codes (A = calling, B = blocking, C = contacts, D = trust) are kept. New codes: **M** = messaging, **T** = time and plan, **H** = history, **K** = keypad. Where a round-2 item changes, the change is recorded in COMPETITIVE_ANALYSIS_2.md.

### M: messaging numbers you haven't saved (WhatsOpen)

| # | Feature | Effort | Where |
|---|---|---|---|
| M1 | **`MessengerLinks`** (pure Kotlin in `core/common`, unit-tested per app): E.164 from libphonenumber with the SIM country as the hint, an explicit package, and no browser fallback ("Install or enable WhatsApp" instead) | S | Shared |
| M2 | **"Message on…" sheet** listing only installed messengers (`<queries>` package entries). Remembers the last choice, and asks between WhatsApp and Business when both are installed. One line of privacy text: *"Opens the app directly, not through a browser. The messenger itself checks whether this number is registered."* | S | Recents long-press, number history, keypad chip next to Call, caller card for unknown numbers, missed-call notification ("WhatsApp back") |
| M3 | **Text-selection action** ("Call / Message with Parley") and **shared `text/plain`**. Uses libphonenumber `findNumbers`; a picker when several numbers are found; **always confirm**. Never read the clipboard on resume; offer a Paste chip instead. | S–M | New translucent sheet activity; `MainActivity.handleIntent` |
| M4 | **"Chat, then decide"**: when you come back from a messenger to an unknown number, offer "Save as a temporary contact (deletes in 7 days)?" | S | Snackbar |
| M5 | **Draft text** ("Send my details") and a local "Last messaged via Signal · 2 days ago" note | S | Sheet and number history |

### T: call time and plan minutes (Call Limiter, plus Logger's billing)

| # | Feature | Effort | Where |
|---|---|---|---|
| T1 | **Talk-time reminders** (soft, the default): "30 min" as an earpiece beep and/or a haptic pattern. Never hangs up. | S | Settings › Calls; per contact |
| T2 | **"Wrap-up" controls in the call:** +2 / +5 min, "End in 1 min", "Don't end" | S | In-call "More" sheet |
| T3 | **Countdown in the notification and the "Return to call" chip** using the chronometer (`setChronometerCountDown`, `setWhen(end)`), no per-second re-posts. Actions "+5 min" and "Don't end". | S | CallStyle notification |
| T4 | **Timing that survives sleep:** anchored to `elapsedRealtime`, a bounded wake lock only while a limited call is active | S | `telecom` |
| T5 | **Hard call-length limit** (opt-in) per contact, label, SIM or globally; incoming and/or outgoing. Warns at T−N s, then `call.disconnect()` on **that** call. The budget starts at `connectTimeMillis`. A remaining-time ring shows in the call. | M | Contact detail "Call time limit", label page, Settings › Calls › Limits |
| T6 | **Daily and weekly quota**, computed from the call history and reset lazily by date, never on reboot or clock change. When the quota is used up, outgoing calls ask for confirmation and incoming calls can be silenced. | S–M | Same editor; shared dial confirmation sheet |
| T7 | **Supervised mode:** limits protected by the app lock. Emergency numbers and favourites marked "never limit" are always exempt. | S | Limits settings |
| T8 | **Plan meter per SIM:** allowance, cycle day, billing increment (1 / 30 / 60 s, *calculated*, never written back), counted number types (`getNumberType`), 80% warning, and "212 of 300 min · 9 days left" | M | Settings › SIMs › Plan (the same page as per-SIM settings) |

### H: call history as data (Logger)

| # | Feature | Effort | Where |
|---|---|---|---|
| H1 | **Full call-history archive**: every call-log row mirrored into Parley's encrypted database; Recents reads the provider plus the archive. Follows the retention setting; a **"Keep forever"** option per person. First verify whether the provider trims old rows, and fix the privacy-screen wording. | M | Settings › Calls › "Keep full call history"; number history |
| H2 | **Readable export** of the current filtered view or one person: CSV (RFC 4180, CRLF, optional BOM, formulas neutralised, notes column), JSON, **ICS**, and a printable PDF through `PrintManager`. Temporary files deleted after sharing. | S–M | Recents overflow › Export…; number history |
| H3 | **Day summary**: tap a Recents day header | S | Recents |
| H4 | **Saved filters as chips** (SIM + type + period + duration) | S | Recents filter row |
| H5 | **Insights screen** (offline): pick a period; in, out and missed counts **and talk time separately**; weekly bars; top people by time and by count; per-SIM split; "calls you didn't return" | M | Recents top-bar icon (**not** a tab) |
| H6 | **Per-contact insights** (replaces round-2 C12): all of the contact's numbers as E.164, last call, average per month, a weekday × hour heatmap, trend, "usually answers after 6 pm" | S–M | Contact detail › Calls; number history |
| H7 | **Keep-in-touch interval suggested from your call rhythm** ("you usually talk every 9 days") | S | Keep-in-touch editor |
| H8 | **Import call history from CSV** (Logger's format and generic CSVs): dry run, deduplication, NEW=0, import report | S–M | Settings › Backup |
| H9 | **One shared `CallLogIndex`**, off the main thread, feeding H5/H6, T6/T8, B12 (personal reputation) and B13 (dry run) | M | `core/data` |

### K: keypad and search (Emerald Dialer)

| # | Feature | Effort | Where |
|---|---|---|---|
| K1 | **T9 alphabet fix** (bug): Ukrainian, Belarusian, Bulgarian, Serbian and Macedonian letters, a Cyrillic accent-stripping fallback, tests | S | `core/common/T9.kt` |
| K2 | **0 = space, 1 = punctuation** as explicit word separators in queries | S | `T9.match` |
| K3 | **Every number in the results:** dial the super-primary number, label "Mobile · Primary", show other numbers as extra rows | S | Keypad results |
| K4 | **Editable number**: tap to place the cursor, long-press to paste, Paste chip when the clipboard holds a number (no automatic reading) | S | Keypad display |
| K5 | **`*#06#` honest sheet**: "Android shows the IMEI only to the system → Open About phone" | S | Keypad |
| K6 | **Keypad alphabet choice** with a second row of letters on each key: one table per layout (Ukrainian and Bulgarian conflict), plus Hebrew (final forms) and Arabic (vowel marks stripped, Arabic-Indic digits). Suggested from the scripts found in your contacts. | M | Settings › Calls › "Keypad letters" |
| K7 | **Hardware keyboard and flip-phone support**: key handling, Call/Enter, D-pad through results, on-screen keypad hidden on 12-key and QWERTY devices | S–M | `KeypadTab` |
| K8 | **Chinese, Japanese and Korean search with no bundled data**: `android.icu.text.Transliterator` (API 29), stored romanised keys, initials or full-syllable matching, plus the contact's phonetic name | M | `core/data` + `T9` |
| K9 | **Incremental narrowing** (refine the previous results) for 5k+ contacts on low-end phones | S | `AppViewModel.dialResults` |
| K10 | **Delete a number's calls by date range** (with the 30-day undo) | S | Number history overflow |

### B additions: blocking (SpamBlocker and Call Blocker)

| # | Feature | Effort | Where |
|---|---|---|---|
| B14 | **Invalid-number rule**: silence numbers libphonenumber says are impossible or invalid for their region (Call Blocker #2). Default *silence*. | S | Blocking toggle next to "neighbour spoofing" |
| B15 | **Screening-path banner and self-check**: "Parley is your phone app: every call is checked, including contacts and hidden numbers", or "Screening only: hidden numbers and the SIM can't be seen". One tap to fix each gap. | S | Top of Blocking |
| B16 | **Rule match preview**: "Will match +52 444…, 444…, 01 444…"; normalised on save; a warning for a foreign country code; `_` and `%` taken literally | S | Rule editor |
| B17 | **Schedules and quiet hours**: "Active: always / schedule" per rule and per toggle, plus a global "Off hours" ("Only contacts at night"); pure `CallPolicy` with an injected clock | S–M | Rule editor; Blocking "Off hours" card |
| B18 | **Label rules**: "Block everyone in label *Spam*" (it syncs across your phones through your contacts account); "Only *Family* rings at weekends" | S | Rule-type picker; label page overflow |
| B19 | **Dialled and answered allow** (numbers you called or talked to ≥ N s in the last X days) plus a **minimum interval for repeat callers** (instant redials don't count) | S | Blocking › "Always let through" |
| B20 | **Caller-name, region and line-type rules**: CNAP contains…, from region…, not from my region, VoIP / premium / shared-cost | S | Rule-type picker |
| B21 | **"Expecting a call" snooze**: let unknown numbers through for 30 min / 2 h / 1 h globally. Quick Settings tile, keypad overflow, blocked-call notification; expires on its own, with a chip. Replaces SpamBlocker's SMS, push and recent-app alerts, offline. | S | QS tile, Blocking header |
| B22 | **Contact-prefix allow**: "Also allow this office's other lines" (all but the last N digits) | S | Contact detail overflow |
| B23 | **Emergency extras**: extra numbers that start the emergency window (a GP, a school), a visible countdown, a reset | S | Blocking › Emergency |
| B24 | **Ringtone per reason and per rule**, played by Parley's own ringer (no WRITE_SETTINGS hack). Merges round-2 A8 and C3. | S | Rule editor, label page, contact |
| B25 | **Offline reporting hand-off**: "Report to carrier" opens the SMS app with `smsto:7726` prefilled; "Report to regulator" opens the complaint page in the browser, with a confirmation | S | Blocked log, Recents long-press |
| B26 | **Import Call Blocker data** (JSON and `.cbbk` with your password), plus CSV **column mapping** for any list | S | Blocking › Import |
| B27 | **Busy auto-reply without SMS permission**: "Anna called during quiet hours – Reply 'In a meeting'" opens the SMS app prefilled | S | Notification |

---

## 5. Parley checks from this round

| Check | Result |
|---|---|
| Fail-closed contact lookup (SpamBlocker, Logger) | Fixed in 966f8cd; keep the regression test |
| T9 covers all Cyrillic alphabets (Emerald lesson) | **Bug**: no Ukrainian, Belarusian, Bulgarian, Serbian or Macedonian letters → K1 |
| Screening responds before writing its log (Call Blocker) | **Needs fixing**: `CallScreener.screen()` logs a block before `respondToCall`, inside the 3-second timeout. Respond first, log afterwards |
| Hidden numbers blocked when screening can't see them | OK as the phone app (`CallManager` checks `handlePresentation`). The screening-only mode must say so (B15) |
| Phone numbers in logcat (WhatsOpen, Call Blocker, Call Limiter) | OK: Parley has no `Log.d/v/i` calls. Add R8 `-assumenosideeffects` for `Log.d/v` as defence in depth |
| Empty call button redials (round-2 A7) | Already built (`KeypadTab.callNow`) |
| Call-log retention promise (Logger #27) | **Verify**: the provider may trim to about 500 rows per account; `PrivacyScreen` says "kept until you delete it"; Recents loads 3,000 rows. H1 or softer wording |
| Wrong-country numbers when messaging (WhatsOpen #5) | Not applicable yet; M1 uses libphonenumber with the SIM country |
| Call-log restore deduplicates and keeps flags (Logger) | OK (`BackupRepository.restoreCallLog`) |
| Exported services answering "is this a contact?" (SpamBlocker) | OK: Parley's exported components are the picker, which needs user interaction, and system-bound services |

---

## 6. UI lessons added from this round

**Avoid:**
- permanently disabled "Coming soon" switches, and store text that claims missing features (Call Blocker);
- one endless settings page with exposed priorities, regex and JSON (SpamBlocker);
- a "simulation" that has real side effects (SpamBlocker);
- analytics as a bottom tab (Logger);
- switches in Settings that unlock filters, and raw phone-account IDs (Logger);
- bulk deletes without confirmation, and generic "Something went wrong" errors (Logger);
- per-second notification re-posts, tap-to-cancel as the only control, and state reset on reboot (Call Limiter);
- long-press-only editing (Call Limiter, and YACB in round 2);
- tapping a call-log row to place a call (Emerald, the fifth example);
- reading the clipboard on resume, auto-launching apps from shared text, and hijacking `tel:` links (WhatsOpen).

**Copy:**
- **collapsed sections that show summary chips**, and a help line for each feature with a real use case (SpamBlocker);
- presets named after situations, not mechanisms;
- a **decision trace** when you expand a blocked call;
- two-script letters on keypad keys (Emerald);
- tap a day header for a summary, saved filter chips, "Apply" enabled only when the filter changed (Logger).

---

## 7. Store listing lines earned in this round

- "Parley *is* your phone app, so a blocked call can't ring anyway." (SpamBlocker #362, #665)
- "Never blocks a contact, even when the phone is busy." (the fail-closed bug in four apps)
- "Message any number on Signal, WhatsApp or Telegram without saving it, and without reading your clipboard."
- "Call time limits and plan minutes, with no extra permissions and nothing running between calls." (Call Limiter needs 11 permissions and an always-on service)
- "Search names on the keypad in your alphabet, including Ukrainian, Hebrew, Arabic and Chinese." (after K1, K6 and K8)

---

## 8. Consolidated roadmap (replaces round-2 §7)

Effort: S ≈ a day, M ≈ a few days, L ≈ a week or more. Items are grouped so each release is coherent and testable.

### v2.1: fixes plus high-impact, small items

**Fixes first:**
- K1 (T9 alphabets);
- screening responds before logging;
- R8 log stripping;
- privacy-screen retention wording (until H1 lands);
- D1 regression-test suite, extended by rounds 2 and 3.

**Calling:**
- A1 call-waiting sheet;
- A2 on-hold strip;
- A3 "Return to call" chip;
- A4 notification health;
- A5 adaptive audio button;
- A6 call haptics;
- A12 accessibility pass, including the keypad;
- T1 talk-time reminders;
- T2 wrap-up controls;
- T3 chronometer countdown;
- T4 sleep-safe timing.

**Blocking:**
- B1 allow rules, with the fixed precedence (§3.2);
- B2/B3 verdicts plus the stored trace (§3.3);
- B6 per-verdict channels;
- B8 multi-select block plus "search on the web" with confirmation;
- B10 wangiri guard;
- B14 invalid numbers;
- B15 screening-path banner;
- B16 rule preview;
- B19 dialled and answered allow, plus repeat minimum interval;
- B21 "Expecting a call" snooze;
- B23 emergency extras;
- B25 offline reporting.

**Messaging:**
- M1 link builder;
- M2 "Message on…" sheet;
- M3 text-selection and share entries.

**Keypad:**
- K2 separators;
- K3 every number in results;
- K4 editable number;
- K5 `*#06#` sheet.

**Contacts:**
- C1 second line;
- C3 label actions;
- C4 favourites order;
- C6 duplicate warning.

**History:**
- H2 export;
- H3 day summary;
- H4 saved filters.

**Privacy:** "Who can see your contacts", steps 1–4 and 6 (round-2 §5.4).

### v2.2: the bigger building blocks

- **Shared history:** H9 `CallLogIndex`, then H1 archive, H5 insights, H6 per-contact insights, H7 rhythm suggestions, H8 CSV import.
- **Time and plan:**
  - T5 hard limits;
  - T6 quotas;
  - T7 supervised mode;
  - T8 plan meter, on a combined **per-SIM page** with per-rule SIM (round-2 B9, changed).
- **Blocking:**
  - B4/B5/B7/B13 spam list packs, with regulator ranges, sharing and the dry run (which must have no side effects);
  - B11 outgoing warning;
  - B12 personal reputation with a regret window;
  - B17 schedules;
  - B18 label rules;
  - B20 name, region and line-type rules;
  - B22 contact prefix;
  - B24 ringtones per reason;
  - B26 Call Blocker import;
  - B27 busy reply.
- **Keypad:**
  - K6 alphabets;
  - K7 hardware keyboards;
  - K8 Chinese, Japanese and Korean;
  - K9 narrowing;
  - K10 date-range delete.
- **Messaging:** M4, M5.
- **Calling:** A9 landscape, A10 pending SIM, A11 hang-up tile, A13 USSD sheet.
- **Contacts:** C2, C5, C7–C11, C13.

### v3: separate decisions

- B4c companion list-updater app;
- C15 protected private-name provider for other apps;
- the **sandbox profile** (round-2 §5.4 step 5);
- rule-pack template gallery (SpamBlocker's per-country sharing, done as signed files).
