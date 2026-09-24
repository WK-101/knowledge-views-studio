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
| **Messaging** | **Message any number without adding it to your contacts** on WhatsApp, Signal, Telegram or Viber — opened directly in the app, never through a browser, with the right country for local numbers; from Recents, number history, the keypad, the missed-call notification, or **text you select or share in any app**; "save as a temporary contact?" afterwards (private by default); newer WhatsApp versions may still ask to sync contacts, which you can decline; "Send my details"; **"Message a number"** Quick Settings tile and launcher shortcut (empty field, Paste chip that reads the clipboard only when tapped, country chip); Telegram **"Open profile"**; **"Messaged numbers"** list with per-item delete, clear all, "don't keep a record" and automatic expiry; **"Add several numbers…"** from pasted or shared text (review against contacts and private contacts, naming pattern, save to a label, privately or as temporary, one undo per batch); **"Introduce myself…"** to a list, one prefilled chat at a time, you press Send |
| **Keypad** | Keypad alphabets (Russian, Ukrainian, Belarusian, Bulgarian, Serbian, Greek, Hebrew, Arabic) with a second row of letters; **Chinese, Japanese and Korean** search by syllables or initials; `0`/`1` as word separators; every number of a contact in the results; editable number with paste (no clipboard snooping); **hardware keyboards and flip phones**; honest `*#06#` sheet; carrier **USSD replies** in a dialog |
| **Calling extras** | **Call-waiting sheet** (hold & answer, end & answer, decline, reply); on-hold strip with swap/merge; "Return to call" chip; **notification health check**; audio button that adapts to Bluetooth; call haptics; two-pane layout in landscape; **Quick Settings tile to hang up**; per-contact call-screen picture; full TalkBack labels |
| **People (v3.1)** | **"Message on…" for saved and private contacts** (each number's chat icon and the Message tile; messengers open by number, directly in the app, even when WhatsApp can't see your contacts) with a **remembered way to message each person**; **messenger handles** (Matrix, Threema ID, Telegram and Signal usernames, Discord, XMPP, SIP…) with hints and in-app links; **"My card"** at the top of Contacts (QR code or vCard with the parts you choose); **set or clear the default number or e-mail**; **"Other fields"** from other apps and accounts, read-only; about **70 relation types** (vCard 4.0 and family words) with a contact picker; **richer private-contact call screen** (encrypted photo, job, "who is this", note for calls); **search by address, note, company, website or handle** ("Matched: address"); **work-profile caller ID**; opt-in **contacts directory** so an approved phone app can show private names |
| **Contacts extras** | "Second line" under names (company, nickname, account…); label filters (unlabelled, any/all), merge labels, **label pages** with message/email all and a label ringtone; drag-to-reorder favourites with pinch-to-zoom grid; actionable "Saved in" chips (edit this copy, move losslessly, unlink); **"already exists" warning** while typing; account diagnostics; **SIM import/copy**; date of death; "why did this change?" line; **Who can see your contacts** (honest per-app audit, private by default, one-contact sharing, GrapheneOS Contact Scopes pointer); protected private-name lookup for approved apps; diagnostics export with masked numbers |
| **Calls & voicemail (v3.1)** | **Voicemail inbox** in Recents (chip with a badge): play on speaker or earpiece, seek, transcription, mark heard, call back, share the audio, delete; **richer missed-call notifications** (photo, SIM, time, one per caller with a count, "Why didn't it ring?", Call back · Message on… · Block); optional **missed-call re-alert** every 5–30 min that respects Do Not Disturb; **post-call card** for unknown numbers (Block, Save privately, Message on…, Report); the **SIM on the answer control**; proximity-sensor switch; keypad tones on touch with roll-over and **DTMF held while pressed**; **pocket-dial guard** for favourites, the widget and shortcuts; **"Why did my phone ring, or not?"** (Do Not Disturb, ringer, ringtone, where it was answered) in number history; link to "Power button ends call"; Recents clears the missed-call count and loads the newest 100 calls first |
| **Look & feel** | Contact page whose photo and name dock into the top bar, grouped sections and labelled Call / Message / Video / Email tiles; editor with grouped cards, "+ Add" and "−" and more fields revealed in place; opt-in **swipe actions** on contacts and Recents (with Undo for Delete); grey monogram or emoji avatars; Material 3 Expressive with dynamic colour; avatars and names animate into the contact page; navigation rail on tablets and foldables; light/dark/AMOLED (system-bar icons follow Parley's theme); compact density; optional call/message buttons on contact rows; **one header on every tab** (title, search that expands in place, the tab's actions, "Lock now" with the app lock); **customisable navigation bar** (show, hide and reorder tabs); **Settings in categories** with grouped cards and a **search over every setting**; Android's "App settings" gear opens Parley's Settings |

**Not included:**

- **Call recording.** Android does not allow it for non-system apps.
- **Syncing visual voicemail ourselves.** That needs internet. Parley shows what the carrier's voicemail app or Android has already downloaded (see "Voicemail" below).
- **Online spam lookups.** By design, nothing is sent anywhere.
- **A separate Android Auto app.** Android Auto's own phone screen already shows the same contacts and call history; a car app for calling needs Google Play review, so it's planned for the Play release.
- **Whole-address-book transfer by animated QR.** That would need Parley to have camera access. "Move to a new phone" sends one encrypted file instead.

## Where things are

**Home header** (every tab: title · search · the tab's actions · ⋮)

| Tab | Header actions | Its own ⋮ items |
|---|---|---|
| Favorites | Search (filters favourites and frequent) | — (sort and "Reorder" stay above the grid) |
| Recents | Search, Call insights | Export…, Messaged numbers, Call history settings (filter chips, including **Voicemail** with its badge, and saved filters stay above the list) |
| Contacts | Search (also addresses, notes, companies, websites, handles), Labels, Lock now (with the app lock) | Select all, Add several numbers…, Find & merge duplicates, Contacts settings (label/account/private filter chips and "Temporary (n)" stay above the list, then "My card"); multi-select ⋮ adds "Introduce myself…" and "Copy as text" |
| Keypad | Search (all contacts), Speed dial | Speed dial, SIMs & plan minutes, Keypad settings |

Shared ⋮ items on every tab: Birthdays & dates, Temporary contacts, Recently deleted, Tidy up contacts, Blocked numbers, Expecting a call…, Lock now (with the app lock), Settings.

**Navigation bar.** Settings › Appearance › Navigation bar shows, hides and reorders the tabs (at least one stays); the rail on wide screens follows it and "Open on" offers only visible tabs. A hidden tab still opens from links: dialling a number (`tel:`, ACTION_DIAL, headset Call button) opens the Keypad and a missed-call notification opens Recents; the opened tab then appears in the bar, in its usual place, until you switch to another tab.

**Settings** (search covers every row below; the registry is `SettingsCatalog` in `core:common`)

| Category | Settings |
|---|---|
| Appearance | Theme, Pure black, Wallpaper colours · Navigation bar, Open on · List density, Call & message buttons on contacts, Swipe actions (with a preview row), Avatars · Sort names by, Second line under names, Prefer nicknames |
| Calls | Default phone app · Answer by, Confirm before calling, Vibrate on call events, Ringtone for unknown callers · Remind me of missed calls, Voicemail · Ask before pocket calls, Turn the screen off at your ear, Power button ends call · SIMs, SIM & calling accounts, Call forwarding/waiting/voicemail |
| Keypad | Keypad tones, Keypad vibration · Keypad letters, Speed dial, USSD replies |
| Call time | Reminders & limits, Plan minutes per SIM |
| Blocking & spam | Blocking & screening, Let repeat callers through, Expecting a call · Spam lists, Rule templates, Test a call, Import & share rules |
| Contacts | Save new contacts to, Labels, Temporary contacts, Add several numbers, Find & merge duplicates, Contact health check · Import .vcf/.csv (other CSV layouts open a column mapping), Import from SIM, Export .vcf/.csv, Export one account · Birthdays & dates, Birthday reminders, Reminder time, Keep-in-touch nudges |
| Recents & history | Keep full call history, Kept calls & recently deleted, Keep call history (retention) · Show SIM in call history, Call insights, Import call history from CSV |
| Messaging | Quick reply messages, My card (replaces "My details"; also at the top of Contacts) · Messaged numbers, Forget messaged numbers after |
| Privacy & security | App lock, Lock again after, Hide screen content · Hide private contacts, Private call history · Privacy dashboard, Who can see your contacts, Let apps show private names, Private names in other phone apps, App permissions |
| Backup & sync | Backup & restore, Sync between your phones · Recently deleted & changed, What changed (time machine) |
| Notifications & device | Notification health check, Notification settings, Full-screen incoming calls, Battery optimisation, Xiaomi permissions |
| About | Version, Export diagnostics (optionally with the contacts tables, masked), Keep crash reports |

**Messaging extras (v3.1).** Quick Settings tile and launcher long-press shortcut "Message a number" open an empty number sheet (nothing is shown over the lock screen). Several numbers in selected, shared or pasted text offer "Save all…", which opens Contacts › ⋮ › Add several numbers…; its result screen has "Undo this batch", "Delete this batch" (batches are remembered for 30 days) and "Introduce myself…". "Messaged numbers" is in the privacy dashboard, Settings › Messaging and Recents ⋮. A contact CSV that isn't Parley's own format (Google, Outlook, "Name,Phone", semicolons, tabs, one column) opens "Choose columns" with guessed columns and a preview before importing.

**Temporary contacts.** Type a number on the keypad and choose "Save temporary contact" (name; 1, 7 or 30 days or custom; whether its call history goes too; private by default, or "Save visible to other apps"), or pick "Delete automatically" on a contact's page. They're listed in Contacts (chip "Temporary (n)" and ⋮) and Settings › Contacts › Temporary contacts, with the time left and Extend / Keep permanently / Delete now.

**Voicemail.** Recents › chip "Voicemail" (the badge counts unheard messages). It reads Android's voicemail store (`VoicemailContract`). Android's contacts provider (`VoicemailPermissions` in AOSP `ContactsProvider`) gives the **default or system dialer** full read and write access to every voicemail, including the audio, without `READ_VOICEMAIL`/`WRITE_VOICEMAIL`: those are signature/privileged permissions that no store app can hold, and Parley doesn't declare them. So the inbox works only while Parley is the default phone app; otherwise the chip is hidden. The messages come from whichever app fills that store: the carrier's visual voicemail app, or Android's built-in visual voicemail on carriers that support it. Parley has no internet access, so it can't sync or download a message itself: "Download" only asks the owning app to fetch it. Delete marks the voicemail deleted so the owning app can remove it from the mailbox on its next sync. "Call voicemail" (and long-press 1) always works, and "Voicemail settings" opens Android's voicemail settings (`TelephonyManager.ACTION_CONFIGURE_VOICEMAIL`). Some carriers deliver visual voicemail only to the phone maker's own dialer; then only "Call voicemail" helps.

**After a call and when you miss one.** An unknown number gets a post-call card on the call-ended screen (Block opens the rule editor with the number, Save privately saves a private temporary contact for 7 days, Message on…, Report). Missed calls get one notification per caller with the contact photo, SIM, time, a count and "Why didn't it ring?" (from the stored screening decision and the ring facts), plus Call back, Message on… and, for numbers that aren't contacts, Block (it asks to unlock first). Settings › Calls › Remind me of missed calls re-alerts every 5–30 minutes for up to 3 hours, never in Do Not Disturb, using an inexact alarm (no exact-alarm permission); opening Recents stops it. The number history's "Why it rang, or didn't" shows, for each incoming call, Do Not Disturb, the ringer mode and volume, vibration, which ringtone played and where the call was answered (Bluetooth device, speaker, another device). These facts stay on the phone for 60 days.

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
- "Message any number on Signal, WhatsApp or Telegram without adding it to your contacts, and without reading your clipboard."
- "Call time limits and plan minutes, with no extra permissions and nothing running between calls."
- "Search names on the keypad in your alphabet, including Ukrainian, Hebrew, Arabic and Chinese."

## Who can see your contacts

Android doesn't let any contacts app decide what other apps see: any app you've allowed "Contacts" can read every contact on the phone, from every account. Settings › Privacy › "Who can see your contacts" lists those apps and links to Android's settings to change them, explains private contacts (kept out of the system address book, so only Parley shows their names), notes that picking a contact for another app shares only that contact (optionally just one number), and on GrapheneOS points to Contact Scopes.

**Your own card ("Me").** Android's profile contact (ContactsContract.Profile) has been readable with the ordinary contacts permission since Android 6 (READ_PROFILE and WRITE_PROFILE no longer exist), so Parley shows it, but it doesn't write to it: anything in the profile can be read by every app with contacts access. "My card" is kept inside Parley (the old "My details" were moved into it) and shared only when you choose, as a QR code or a vCard.

**Private names in other phone apps** (I7, off by default): turning it on enables a contacts Directory provider (`android.content.ContactDirectory`, authority `<package>.privatedirectory`). Android's Contacts Provider then lists "Parley private contacts" as a directory and forwards other apps' lookups to it as itself, adding the real app's package (`Directory.CALLER_PACKAGE_PARAM_KEY`, trusted only when the Contacts Provider is the caller; checked against AOSP ContactsProvider2 and ContactDirectoryManager). Parley answers only the directory list and `phone_lookup` for one exact number (E.164, keyed hash), with the same per-app approval, hourly limit and log as the lookup below, and nothing in discreet mode. Limits: only phone apps that look callers up in contact directories see the names (Google Phone does, per the Alternate app; others may only look in local contacts); a phone app inside a work profile can't reach it (Android lets the personal side look into the work profile, not the other way round); no photo, no contact to open.

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
