# Parley Phone

A privacy-focused, modern **Contacts + Dialer + In-call** app for Android, all in one app.

- **No internet permission.** The build fails if any dependency tries to add it.
- No ads, no trackers, no analytics, no accounts.
- Material 3 with dynamic colour.
- Open source under the GPL-3.0.

This project is independent of the rest of this repository.

## Features (v3.0)

| Area | What you get |
|---|---|
| **Dialer** | T9 smart search (names, initials, inner words, accents, Cyrillic/Greek, number substrings, private contacts), ranked by how recently and often you call; speed dial on 2–9; voicemail on 1; `+` on 0; pause and wait characters; USSD/MMI and `*#*#…#*#*` codes; one call button per SIM; optional confirm before calling |
| **In-call** | Full-screen incoming call over the lock screen; slide or tap to answer; **Ignore** (stop ringing without rejecting); **caller card** with your pinned note and "last call 3 days ago · 4 min"; **offline caller location** for unknown numbers; reply with a message; mute, keypad, speaker; Bluetooth and wired audio routes; hold, add, merge, swap and conference management; **notes during a call**; proximity screen-off; call-style notification; STIR/SHAKEN badges; SIM picker; optional **ringtone for unknown callers** |
| **Recents** | Grouped by person and day; filter chips; search; SIM labels; location for unknown numbers; **long-press actions** (edit number before calling, copy, create or add to contact, block, delete); per-number history with call notes; private calls shown with a lock; missed-call notification with Call back and Message |
| **Contacts** | Sticky headers and fast-scroll rail; label filters; **multi-select** (star, share, add to label, message all, merge, export, delete); full editor with relations, **year-optional date picker**, country flags, all account types; **photo viewer**, clickable links in notes, long-press to copy anything; birthdays with age and countdown; **relations**, "last talked", **keep-in-touch reminders**, call notes; **messenger actions** (Signal, WhatsApp, Telegram, Threema… with a preferred app per contact); **home-screen shortcuts** and a **direct-dial widget**; QR share and **encrypted QR share**; lossless vCard 4.0 and CSV import/export with an import report; duplicate finder; **contact health check** with one-tap fixes; **birthdays & dates** screen with daily reminders (no calendar permission) |
| **Works with other apps** | Parley is the system contact picker (contacts, numbers, e-mails, addresses; multi-select), handles insert/edit, quick contact, show-or-create and `.vcf` files |
| **Privacy & security** | **Private vault**: encrypted contacts invisible to all other apps that still show their name when they call, private call history, discreet mode and Quick Settings tile; **app lock** (biometric/device credential; incoming calls always show); hide screen content; **temporary contacts** that delete themselves; call-history retention; privacy dashboard |
| **Never lose a contact** | **Recently deleted & changed** (30-day undo of every delete, edit and merge); **time machine** (daily incremental snapshots for 6 months: see what changed and restore any version); **encrypted backups** to a folder you choose (scheduled, verified after writing, smart rotation, restore preview with merge, undo); **move to a new phone**; **sync between your phones** through a Syncthing/Nextcloud folder with no server |
| **Blocking** | Fixed, predictable precedence (emergency › contacts › allow rules › block rules › spam lists › checks) with a stored **"why" trace** for every screened call; **allow rules** and "Allow for 24 h"; block rules by number, prefix, pattern, caller name, country, line type (VoIP, premium…) or **label**, each with a **schedule**, a SIM, a notification level and hit counters; **off hours** ("only Family rings at night") with a one-tap SMS reply; **"Expecting a call"** snooze (Quick Settings tile); numbers you called or talked to; repeat callers with a minimum redial interval; invalid numbers, neighbour spoofing, failed verification; offline **spam-list packs** (`.parleylist`, signed, folder subscription, built-in ARCEP ranges for France) that warn by default; **test a call** and **replay last week** with no side effects; one-ring "wangiri" and premium-rate warnings before you dial; import from Call Blocker, YACB, NoPhoneSpam or CSV, share your rules as a signed list; **rule templates** (regulator ranges for FR, UK, DE, IT, ES, IN and US toll-free, quiet nights, foreign or invalid numbers) installed and removed as a group, shared as signed files or QR codes; optional **Parley Lists** companion app for automatic FTC and community list updates; carrier/regulator reporting hand-off; ringtone per rule or label and "ring loud" with crash-safe volume restore |
| **Call time** | Talk-time reminders (a beep only you hear); "+2 / +5 min" and "End in 1 min" during a call; optional **call-length limits** per contact, label, SIM or all calls that end only that call (never emergency calls or the emergency call-back window); **daily/weekly allowances** that a reboot can't reset; countdown in the notification; **supervised mode** behind the app lock; **plan minutes per SIM** with billing increments and an 80 % warning |
| **Call history** | Parley's own **encrypted call-history archive** (keeps calls Android trims, "keep forever" per person, 30-day undo for deletes); **Insights** (talk time in/out, weekly bars, top people, per-SIM split, calls you didn't return); per-contact patterns ("usually answers after 6 pm", weekday × hour heatmap, suggested keep-in-touch interval); tap a day for its summary; saved filters; export as **CSV, JSON, calendar (ICS) or PDF**; import from CSV (Parley, Logger or any columns) |
| **Messaging** | **Message any number without saving it** on WhatsApp, Signal, Telegram or Viber — opened directly in the app, never through a browser, with the right country for local numbers; from Recents, number history, the keypad, the missed-call notification, or **text you select or share in any app**; "save as a temporary contact?" afterwards; "Send my details" |
| **Keypad** | Keypad alphabets (Russian, Ukrainian, Belarusian, Bulgarian, Serbian, Greek, Hebrew, Arabic) with a second row of letters; **Chinese, Japanese and Korean** search by syllables or initials; `0`/`1` as word separators; every number of a contact in the results; editable number with paste (no clipboard snooping); **hardware keyboards and flip phones**; honest `*#06#` sheet; carrier **USSD replies** in a dialog |
| **Calling extras** | **Call-waiting sheet** (hold & answer, end & answer, decline, reply); on-hold strip with swap/merge; "Return to call" chip; **notification health check**; audio button that adapts to Bluetooth; call haptics; two-pane layout in landscape; **Quick Settings tile to hang up**; per-contact call-screen picture; full TalkBack labels |
| **Contacts extras** | "Second line" under names (company, nickname, account…); label filters (unlabelled, any/all), merge labels, **label pages** with message/email all and a label ringtone; drag-to-reorder favourites with pinch-to-zoom grid; actionable "Saved in" chips (edit this copy, move losslessly, unlink); **"already exists" warning** while typing; account diagnostics; **SIM import/copy**; date of death; "why did this change?" line; **Who can see your contacts** (honest per-app audit, private by default, one-contact sharing, GrapheneOS Contact Scopes pointer); protected private-name lookup for approved apps; diagnostics export with masked numbers |
| **Look & feel** | Material 3 Expressive with dynamic colour; avatars and names animate into the contact page; navigation rail on tablets and foldables; light/dark/AMOLED; compact density; optional call/message buttons on contact rows |

**Not included:**

- **Call recording.** Android does not allow it for non-system apps.
- **Syncing visual voicemail ourselves.** That needs internet.
- **Online spam lookups.** By design, nothing is sent anywhere.
- **A separate Android Auto app.** Android Auto's own phone screen already shows the same contacts and call history; a car app for calling needs Google Play review, so it's planned for the Play release.
- **Whole-address-book transfer by animated QR.** That would need Parley to have camera access. "Move to a new phone" sends one encrypted file instead.

## Permissions Parley doesn't ask for, and why

The build fails if any of these appear in the merged manifest (`checkReleasePermissions` / `checkDebugPermissions`), so this list is enforced, not promised.

| Permission | Why Parley doesn't need it |
|---|---|
| Internet, network state | Nothing is sent anywhere: no spam lookups, no analytics, no crash reports, no ads. Backups and sync go to a folder you choose. |
| Microphone (RECORD_AUDIO) | Android doesn't let non-system apps record calls, and Parley doesn't pretend to. |
| Camera | QR codes are shown by Parley and scanned by your own camera app. |
| Location | Caller location comes from an offline number database, not from where you are. |
| SMS (read or send) | "Message" opens your SMS app with the text prefilled; you press Send. |
| Storage | Files go through Android's file picker, one file at a time, only when you choose one. |
| See all installed apps (QUERY_ALL_PACKAGES) | "Who can see your contacts" lists only apps with a home-screen icon. |
| Advertising ID | There are no ads. |

The permissions Parley does use, and what each one is for, are listed in Settings › Privacy dashboard. `READ_SYNC_SETTINGS` (granted at install, no personal data) lets the health check tell you when contacts sync is off for an account.

**Store listing lines** (from COMPETITIVE_ANALYSIS_3 §7; use each only once its feature has shipped, never to claim a missing one)

- "Parley *is* your phone app, so a blocked call can't ring anyway."
- "Never blocks a contact, even when the phone is busy."
- "Edits only what you changed."
- "No permission it doesn't use. No internet, so no leaks."
- "Message any number on Signal, WhatsApp or Telegram without saving it, and without reading your clipboard."
- "Call time limits and plan minutes, with no extra permissions and nothing running between calls."
- "Search names on the keypad in your alphabet, including Ukrainian, Hebrew, Arabic and Chinese."

## Who can see your contacts

Android doesn't let any contacts app decide what other apps see: any app you've allowed "Contacts" can read every contact on the phone, from every account. Settings › Privacy › "Who can see your contacts" lists those apps and links to Android's settings to change them, explains private contacts (kept out of the system address book, so only Parley shows their names), notes that picking a contact for another app shares only that contact (optionally just one number), and on GrapheneOS points to Contact Scopes.

**Private-name lookup for other apps** (off by default): an app that declares and is granted the `app.parley.permission.LOOKUP_PRIVATE_NAME` permission can query `content://app.parley.phone.privatenames/lookup/<number>` and gets at most one row (`display_name`, `photo_uri`) for that exact number. Lists, prefixes and wildcards are refused; each app must also be approved in Parley (it asks with a notification the first time), queries are rate-limited, and every request is logged in Privacy without the number. Debug builds use `...LOOKUP_PRIVATE_NAME_DEBUG` and `app.parley.phone.debug.privatenames`.

## Project layout

```
core/common   Pure Kotlin (unit-tested): numbers, T9, block rules, duplicates, dates, lossless ContactRecord,
              vCard/CSV mapping, backup crypto + archive + retention + merge planning + snapshots
core/data     ContactsContract, CallLog, SIMs, blocking, Room, DataStore, vault, backup, time machine,
              folder sync, journal, health check, offline number info
core/ui       Theme, avatars, shared components
telecom       InCallService, CallManager, notifications, in-call UI (no dependency on data/features)
app           Main activity, navigation, all screens, missed-call notifications
lists-updater Optional "Parley Lists" companion app (app.parley.lists): downloads public spam lists
              (depends on core:common and core:ui only)
```

## Building

Requirements:
- JDK 17 or newer
- Android SDK with `platforms;android-36` and `build-tools;36.0.0`

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:common:test lint assembleDebug      # tests, lint, debug APK
./gradlew assembleRelease                          # signed if keystore.properties exists
./gradlew :core:common:buildSpamPack --args="--ftc dnc.csv --out ftc.parleylist --id gov.ftc.dnc --name 'FTC reported calls' --key my.key"
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

### Parley Lists (optional companion)

`:lists-updater` builds a second app, **Parley Lists** (`app.parley.lists`, debug `app.parley.lists.debug`). It has INTERNET and no contacts, phone, call-log or SMS permission; its `check<Variant>UpdaterPermissions` task fails the build on anything beyond network access and WorkManager's scheduling permissions. It downloads only static public files, never sending a phone number:

- **US FTC Do Not Call reported calls**: the key-less daily CSV `https://www.ftc.gov/sites/default/files/DNC_Complaint_Numbers_YYYY-MM-DD.csv` (weekdays; see the [FTC data page](https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data)). Each day is counted once and cached, then a 7/30/90-day window becomes a signed `.parleylist` using the same `core:common` builder as Parley (score from the report count; scam, robocall or telemarketing category by vote). The FTC's JSON API (`api.ftc.gov/v0/dnc-complaints`) needs an api.data.gov key and returns 50 records per request, so it isn't used.
- **France ARCEP ranges**: built in.
- **Community packs**: any HTTPS link to a `.parleylist`, kept byte for byte so the publisher's signature still verifies.

Updates run through WorkManager (by default daily, on unmetered networks, while idle). Packs are served read-only by `content://app.parley.lists.packs/packs[/<id>]`, protected by the signature permission `app.parley.permission.READ_LISTS` (`..._DEBUG` in debug builds). Parley and Parley Lists both declare it, so it only works when both are signed with the same key: release builds use the same `keystore.properties` / environment variables, and debug builds use the debug key. Parley copies subscribed packs, verifies them like any list file, and refreshes them in its daily worker. There is no `sharedUserId`.

## Docs

- [docs/RESEARCH.md](docs/RESEARCH.md): research brief
- [docs/PLAN.md](docs/PLAN.md): product and technical plan
- [docs/TESTING.md](docs/TESTING.md): device test checklist
- [docs/COMPETITIVE_ANALYSIS.md](docs/COMPETITIVE_ANALYSIS.md): full-code analysis of four open-source apps, and the roadmap
