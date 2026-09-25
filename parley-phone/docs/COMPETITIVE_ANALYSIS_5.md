# Parley 3.1 vs. Bondwidth: from phone book to people you keep

*25 Sep 2026. Round 5. Earlier rounds: [1](COMPETITIVE_ANALYSIS.md), [2](COMPETITIVE_ANALYSIS_2.md), [3](COMPETITIVE_ANALYSIS_3.md), [4](COMPETITIVE_ANALYSIS_4.md).*

**What was read.**
- The full source of [phonepvr/friends](https://github.com/phonepvr/friends) at `5cce7ce` (16 Jun 2026). That is about 28k lines: 170 Kotlin/XML files, the tests, the CI workflows, the F-Droid runbook and all 8 store screenshots.
- A separate survey of what people say about keep-in-touch / personal CRM apps and Android dialers in 2025–26. It covers Hacker News, forums, GitHub issues and store reviews. Reddit search returned nothing, so no Reddit threads are included.

| Project | What it is |
|---|---|
| **Bondwidth** `com.phonepvr.friends` (GPL-3.0, minSdk 26) | An offline dialer and contacts app with a relationship companion on top. You pick people you want to stay close to ("bonds") and give each a check-in cadence. It logs your calls with them automatically, reminds you of birthdays and anniversaries, has a "Width" dashboard (health score, call analytics, year in review) and a widget. It also shows a daily quote. The phone side takes only small parts from Fossify; most of it is new Compose code. No INTERNET permission, and CI fails if one appears. |

## 1. Verdict

Bondwidth's phone and contacts parts are thinner than Parley's. It fails at call waiting, dual-SIM account selection, `#` codes and the proximity sensor. Its dialpad Call button can dial the top match instead of the typed number. Its contact editor drops data. Parley was already ahead on everything we did last round, and still is.

**Bondwidth's real contribution is a different idea of what a phone app is for.** It treats the address book as a small set of people you want to keep, not a list of numbers. It turns the call log into a quiet record of those relationships. Parley already has pieces of this, but they are scattered:
- a per-contact "Keep in touch" interval with a suggested value;
- a daily nudge;
- call insights with unreturned calls;
- a Birthdays & dates screen;
- call notes and a pinned note.

None of these pieces has a home, and the only thing that counts as "being in touch" is an answered call.

The research shows why people abandon apps like this, and also how to win:
1. **They have to log everything by hand.** "If a tool does not fill itself in, it slowly empties out."
2. **The reminders feel like guilt.** Daily reminders are "less of a promise and more of a threat".
3. **Fixed cadences feel robotic.**
4. **They don't trust a startup with their social graph.** UpHabit shut down in April 2026, and Clay was renamed Mesh.

Parley can beat both Bondwidth and the cloud CRMs, because it runs on the device, fills itself in from data it already holds, and never nags. So the plan for round 5 is a **Circle**: one calm place for the people you care about. It is built on Parley's existing contacts, call log and notes, with no second database of "people", and it is shaped by the four complaints above.

A few other items are worth taking from Bondwidth:
- picture-in-picture during calls;
- a Block & decline button on the incoming-call screen;
- a guided fix when Android blocks a sideloaded app from becoming the default dialer (it calls this "restricted settings");
- its photo processor;
- a proper F-Droid release setup.

This round also found **six Parley bugs**; one breaks the F-Droid build.

## 2. Parley bugs found this round (fix first)

| # | Severity | Bug | Where | Fix |
|---|---|---|---|---|
| G1 | **High** (distribution) | F-Droid strips the `signingConfigs` block and `signingConfig` lines from the build script. Our release block still calls `signingConfigs.getByName("release")`, so an F-Droid build fails with "SigningConfig 'release' not found". | `app/build.gradle.kts:49` (and the same pattern in `lists-updater`) | Look the config up with `findByName`, only when the keystore env var or properties are present, and never refer to it otherwise. Check this by building with the signing block deleted. |
| G2 | **High** | The contact photo writer decodes the whole image in one go. A 50 MP photo can run out of memory, HEIC decoding depends on the platform, and EXIF rotation is ignored, so portrait photos come out sideways. | `ContactsRepository.writePhoto` | Use a processor like Bondwidth's (`ContactPhotoProcessor`): read the bounds first, downsample with `inSampleSize`, rotate using `ExifInterface`, crop to a square, scale to 720 px, then write the JPEG. Use `ImageDecoder` on Android 9+, which also handles HEIC. |
| G3 | **Medium** (privacy) | "Hide screen content" (FLAG_SECURE) is applied to the main activity and the number-action sheet, but not to the in-call screen. The caller's name, number and notes can appear in screenshots, recordings, the recents thumbnail and casts. | `telecom/.../InCallActivity.kt` | Pass the setting through `TelecomDependencies` and apply it in `onCreate`/`onResume`, as `AppLock.applySecure` does. |
| G4 | **Medium** (privacy) | Birthday and keep-in-touch notifications use the default visibility, so "Call Sam — 42 days" shows in full on the lock screen. They also mirror to watches. | `RemindersWorker.notify` | `VISIBILITY_PRIVATE` with a neutral public version ("Reminder"), and `setLocalOnly(true)`. |
| G5 | **Low** | Notification ids collide. Birthday notifications use `contactId` and nudges use `10000 + contactId`, so contact 10 005's birthday replaces contact 5's nudge. A birthday and an anniversary on the same day for one contact replace each other. | `RemindersWorker` | Use tags `birthday:<contactId>:<eventId>` and `nudge:<contactId>` with id 0. |
| G6 | **Medium** (product) | "Keep in touch" counts only answered calls (`durationSec > 0`). A 30-minute video call on Signal, a visit or a birthday message don't count, so you get nudged about someone you saw yesterday. This is exactly the "doesn't know what I actually do" complaint. | `RemindersWorker.nudges` | Fixed by R2/R3 below: any logged interaction resets the clock. |

We also re-checked that R8 minify plus resource shrinking still gives byte-identical output across two clean builds. This is needed for F-Droid's reproducible-build check (see D2).

## 3. What Bondwidth teaches

### 3.1 Ideas worth taking

- **The list is the to-do list.** Bonds are sorted by how urgent they are: overdue first, then due soon, then on track. Each card shows a coloured status chip and "last spoke N days ago". There is no separate "tasks" screen.
- **Who you call is the whole log.** Calls are matched to people automatically. They are deduplicated with a unique key (`digits@timestamp`), so running the sync again is always safe. Missed calls and zero-length calls don't count; manual entries and "Mark as wished" do.
- **"Log this as an interaction?"** When you start WhatsApp, Signal, SMS or a call from the app, a snackbar asks this. It takes one tap and needs no permissions, because the app already knows it started the conversation. This is the cheapest answer to manual-logging fatigue.
- **"Mark as wished"** is an action on the birthday notification. It records an interaction and marks the occasion as done.
- **Health score with a trend and no history table.** Each bond gets a value: on track 1.0, due soon 0.7, never contacted 0.4, and overdue falls from 0.5 towards 0 over one cadence period. The average gives the score. For "vs last month", the same calculation is run again on history cut off 30 days ago. Nothing extra is stored.
- **Bonded reach.** "You called 7 of your 12 bonds in the last 30 days." Other stats: callers split into bonds, saved contacts and unknown numbers; "worth a call back" (bonds whose latest call is an unreturned miss); a year in review (most connected, longest gap, occasions acknowledged).
- **The person page as a stack of cards.** The avatar, a cadence card and a 365-day summary sit side by side. Below them are the reach-out icons, then a "Stay in touch" card that opens a sheet of presets. Birthday and anniversary slots show "add one?" when they are empty. An extended FAB reads "Log interaction", and everything rarely used is in the ⋮ menu.
- **Picture-in-picture during calls.** It has a Minimize button and a small card showing the caller and "Muted".
- **One-tap "Block & reject"** on the incoming-call screen.
- **A guided fix when the default-dialer request silently fails** on sideloaded installs. It is a step-by-step dialog that deep-links to App info → ⋮ → Allow restricted settings.
- **A few smaller things:**
  - permission prompts that each name what still works without the permission;
  - "Back up first?" before imports;
  - a banner and an occasional notification when a backup is overdue;
  - one-time coach marks;
  - call-direction colours kept out of dynamic colour, with separate light and dark values, on a tinted circle;
  - distinct haptics for answer and decline;
  - the caller's name stays on screen while "Call ended" shows.
- **Warm copy.** "Even a Tuesday 'thinking of you' counts." The copy avoids blame.
- **An F-Droid release runbook.** Version fields are literals that F-Droid's update checker can read. The signing config depends on an env var, so F-Droid's removal of it doesn't break the build. It covers reproducible builds, and the fastlane metadata is complete: title, changelogs per versionCode, and screenshots.

### 3.2 Mistakes to avoid (these become our test cases)

**Phone side:**
- Call waiting doesn't work: the second call gets no ring and no answer controls.
- A dual-SIM `SELECT_PHONE_ACCOUNT` request is never answered, so the call hangs at "Connecting…".
- The dialpad Call button dials the top match, not the typed number.
- Numbers are cut at `#`.
- Returning to the app bounces you back into the call, so "Add call" doesn't work.
- There is no proximity sensor handling.
- The ringer ignores Do Not Disturb.

Parley handles all of these today. We will add regression tests for the `#` codes and for dialling the typed number.

**Relationship side:**
- Saving a bond deletes its custom events.
- Editing a contact wipes birthdays that were stored only on the bond.
- Editing a call-log entry resets its time to midnight.
- Accepting "Log this?" and then syncing the call log records the call twice.
- Birthday reminders repeat every day for the whole 7-day lead window, even after "wished".
- A person's second event notification overwrites their first.
- "Remove bond" has no undo.
- A search with no results says "Your circle is empty".
- The call-log sync reads the whole year once per person, several times a day.

**Contacts side:**
- Every address and website except the first is dropped.
- The structured name and job title are lost.
- Multi-account contacts are written to the first account only.
- vCard import creates duplicates while promising not to.
- The whole address book is re-read on the main thread.
- Restore wipes favourites.
- Dates are hard-coded as dd/MM/yyyy.

### 3.3 What we are not copying

- **The separate `Person` database copied from the system contacts.** Parley keys its data to the contact's `lookupKey`, and `MetaRekey` keeps that key working when contacts merge. The Circle stays a *view* over contacts, with no second database.
- **A 0–100 "health" grade on the main screen.** Research shows scores and overdue counters make people feel guilty and leave. We keep the calculation, but show it only in Insights, worded as reach and trend, and it can be turned off.
- **Daily quotes.** They are pleasant filler that doesn't fit a utility app.
- **Tap-only answer, and an app-owned ringer that ignores Do Not Disturb.** Parley already does better.
- **Hilt.** Manual DI is fine at our size.

## 4. What people complain about, and our answer

| Complaint (research) | How common | Parley answer | Item |
|---|---|---|---|
| Logging everything by hand; the app "empties out" | Main reason people quit | Contacts fill themselves in from calls, from messages you start in Parley, and from the post-call memory prompt | R2, R3, R9 |
| Guilt and nag notifications | Very common | One weekly digest by default, a cap on nudges, "not now" backs off and never escalates, no overdue counters | R4 |
| Fixed cadence feels robotic | Common | "Natural rhythm" mode: learn each person's usual gap and nudge only when a gap is unusual for them | R4 |
| Privacy of the social graph; vendor shutdown | Very common among privacy-minded users | Already Parley's core: no INTERNET, encrypted backups, open formats. Add a Markdown export of notes | C5 |
| Two copies of every contact (CRM vs phone) | Common | The Circle is a view over system contacts, not a copy | R1 |
| WhatsApp and Signal contact goes undetected | Common | "Log this?" after messages started in Parley (no permission needed). Optional notification-access detection is deferred | R3, §6 |
| Importing 800 contacts buries the 30 that matter | Common | The Circle holds only people you pick, suggested from your call history | R1 |
| Google Phone 2025 redesign: merged tabs, grouped call log, moved controls | Very loud | Tabs can already be reordered and hidden. Add a setting for chronological or grouped calls, and a promise that updates never rearrange your layout | U6, P8 |
| Pocket answers and accidental declines | Common | Already covered by slide to answer and the proximity guard. Add an optional confirm-before-decline for simple mode | X4 |
| Duplicates and "lost" contacts after a sync | Very common | Already covered: duplicate finder, time machine, journal, health check | — |
| Call recording | Top request in Fossify Phone | Not possible without the microphone permission, which Parley deliberately doesn't hold. The alternative is post-call notes | R9 |
| Dual-SIM friction | Common | Already covered: remembered SIM per contact. Add per-label SIM | X3 |

## 5. What to build

### 5.1 Circle: the people you keep (R)

| # | Item | Details and placement |
|---|---|---|
| R1 | **Circle view** | A new optional **Circle** tab, hidden by default and enabled in Settings → Bottom bar. When the tab is hidden, the Circle appears as a collapsible section at the top of Favourites. It lists people with Keep in touch set, sorted by urgency, each with a status chip (Due / Soon / Fine), "last in touch 12 d · call", and one-tap Call / Message. The first time, it offers "Suggested from your calls": your 10 most-called contacts with their suggested rhythm, and one tap adds them. Empty search results and an empty circle get different messages. |
| R2 | **Interactions** | Log a meeting, message, video call or other contact with an optional note. It's stored encrypted in Room, keyed to the contact's `lookupKey`. The contact page gets a **timeline** that merges calls, interactions, notes and dates, grouped by month. Editing an entry keeps its time; deleting one can be undone. Any interaction resets the keep-in-touch clock. |
| R3 | **"Log this?"** | After Parley starts SMS, WhatsApp, Signal, Telegram or a video call for a Circle contact, a snackbar asks "Log as a message with Sam?" with Log and Undo. A dedupe key (channel, contact, 10-minute bucket) stops repeats, and calls are never logged twice because the call log is the only source for calls. You can switch it to Always, Ask or Never per channel. |
| R4 | **Kind reminders** | For each person the rhythm is either **Every N days** or **Natural**. Natural uses the median gap between contacts ×1.5, with a minimum of 7 days, re-learned monthly. Delivery is either **Weekly digest** (default: one Sunday notification with up to 3 people: one due, one with an upcoming date, one "haven't heard from in a while") or **As they come due**, capped at N per week. "Not now" snoozes and doubles the next gap, and nothing escalates. Wording is warm ("Sam might enjoy hearing from you"). There are no counters or red badges. |
| R5 | **Better date reminders** | Lead time: on the day, 1, 3 or 7 days before. It fires once on the lead day and once on the day, never daily. **Mark as wished** records an interaction and cancels the remaining reminders for that occasion. Each event gets its own notification tag (fixes G5). Notifications are private on the lock screen (G4). |
| R6 | **Reach in Insights** | A new "People" card in Insights, not on the home screen: **reach** ("you were in touch with 9 of 14 in your circle this month", with an arrow compared with last month, worked out from history), **open loops** (their missed call you haven't returned, your unanswered attempt), **who usually reaches out first** (private, per person, hideable) and **year in review** (most in touch, longest gap, occasions acknowledged; shown when at least 20 entries exist). The whole card can be turned off. |
| R7 | **Circle widget** | A resizable RemoteViews widget (no Glance dependency) with upcoming dates (14 days) and up to 3 people from the Circle digest, each with a Call button. With app lock on, the widget shows only counts, not names, until the device is unlocked. |
| R8 | **Remember what matters** | For known contacts, an opt-in **post-call memory prompt** reuses the PostCallCard: "Anything to remember?" with a note field and chips (*their news*, *I promised…*, *follow up in 1 w / 1 m*). "Follow up in" creates a one-off reminder. The last note and any open promises appear on the incoming-call screen (only when unlocked, or if the user allows it on the lock screen) and in a **pre-call peek** sheet before dialling from contact detail. |
| R9 | **Promises** | Note lines starting with `[ ]` become open items for that person. They show in the pre-call peek and on the contact page, and can be ticked off. There's no language parsing; this is a simple convention the note editor explains, with a checkbox button. |
| R10 | **Life events** | Custom dates (new job, moved, baby) can be marked **remember yearly**, which gives a gentle "1 year since Ana's new job" in the digest. This reuses Parley's existing custom events. |

### 5.2 Phone (P)

| # | Item |
|---|---|
| P1 | **Picture-in-picture during calls.** Automatic PiP when you leave the call screen (Android 12+ `setAutoEnterEnabled`), with RemoteActions for Mute and Hang up, and a card showing caller, timer and a Muted indicator. The main app never bounces you back into the call; the Return to call chip stays. |
| P2 | **Block & decline** on the incoming screen, behind ⋮ or a long-press so it can't be hit by accident. It writes the block rule first, then declines. The post-call card offers Undo. |
| P3 | **In-call screen honours "Hide screen content"** (G3). |
| P4 | **Default-dialer rescue.** If the role request returns within about 300 ms without showing a dialog, show a version-aware guide with a button that opens App info. The guide is never shown when the user pressed Cancel on a real dialog. The same guide sits in Settings → Default apps and in onboarding for sideloaded installs. |
| P5 | **Clear call history.** First it offers "Export first?" (CSV / encrypted), then confirms. It also works on a filtered set, for example "all calls from unknown numbers". |
| P6 | **Call failure banner** with Retry and the reason, for example "No SIM selected" or "Airplane mode", kept until dismissed. The caller's name stays on the "Call ended" screen. |
| P7 | **Distinct haptics** for answer and decline, plus a haptic when a call connects (optional). |
| P8 | **Call log layout:** grouped (current), chronological (every call on its own row) or grouped by day. It's a setting and also a quick toggle in the Recents ⋮ menu. |
| P9 | **Regression tests** for Bondwidth's bugs: `#` in dialled numbers, the Call button dialling the typed number rather than the top match, and the call-waiting sheet. |

### 5.3 Contacts and data (C)

| # | Item |
|---|---|
| C1 | **Photo processor** (G2): bounded memory, EXIF rotation, HEIC, square crop, 720 px, used by the editor, vCard import and QR transfer. |
| C2 | **"Back up first?"** before large imports, bulk merges and bulk deletes, if the last backup is more than 7 days old. It offers a one-tap quick encrypted backup. |
| C3 | **Backup reminder:** a quiet banner in Settings and Backup once a backup is overdue (the user chooses 14 or 30 days), plus at most one notification a month. Dismissing it snoozes for a set time and never silences it for good. |
| C4 | **Birthday slots on the contact page:** empty Birthday and Anniversary chips show "Add birthday?" inline and save straight to the system contact, so dates are never stored only in Parley. |
| C5 | **Markdown notes export:** one `.md` file per person (front-matter: name, phones, dates, labels, then notes, promises and the interaction timeline), written to a folder the user picks. It's one-way and reuses the folder-sync machinery, which suits Obsidian users. |

### 5.4 Layout and wording (U)

| # | Item |
|---|---|
| U1 | **Onboarding permissions page:** one row per permission with the reason and what still works without it ("Without call log: you can still call; Recents stays empty"), a single "Allow all" button, and per-row toggles. For sideloaded installs on Android 13+, the default-dialer step explains restricted settings before it can fail. |
| U2 | **Coach marks**, shown once each and dismissible, for gestures users can't discover: long-press a keypad key for speed dial, swipe actions in Recents, header search, the Circle suggestions, and "Log this?". One setting resets them. |
| U3 | **Call colours kept fixed:** incoming, outgoing, missed and blocked each get fixed hues with separate light and dark values, shown on a tinted circle behind the icon. They are the same in Recents, history, the timeline and the widget, and don't change with the wallpaper colour. |
| U4 | **Contact page order:** header, then the action row (call, message, messengers), then a **Stay in touch** card (rhythm, last in touch, next date; tap to change), then dates, numbers, the timeline and notes. The Log interaction FAB appears only for Circle contacts; for everyone else it is in ⋮. |
| U5 | **Empty states:** "No matches for 'xyz'" is always different from "Nothing here yet". Every empty state has one clear action. |
| U6 | **Layout promise:** an update never changes your tab order, start tab or call-log layout. New tabs arrive hidden, and "What's new" appears once as a dismissible card (never a forced screen) with the option to try a new layout. |
| U7 | **Writing guide** in `docs/`: warm, blame-free reminder wording, and no counters that shame. It's used for all new strings in the 8 languages. |

### 5.5 Beyond both (X): ideas nobody does well yet

| # | Item |
|---|---|
| X1 | **Good time to call.** From your own history with this person: the hours when they usually answer, or call you. It's combined with their local time (Parley already works out the caller's local time from the country code). The pre-call peek and contact page show "Usually free 6–9 pm · it's 7:40 pm there", computed on the device, and only once there are at least 8 answered calls. |
| X2 | **Trip mode.** Pick a city (or type one) and Parley lists contacts whose address, notes or number area code match it: "You're in Lisbon: Ana, Marco." There's no location permission; the city is typed, not detected. |
| X3 | **Label policies.** A label (e.g. "Family") can carry a preferred SIM, a ringtone (already built), a reminder rhythm for new members, and an "Allow through Do Not Disturb" setting. That last one marks members as starred and links to the system DND setting for starred contacts, and explains the trade-off. |
| X4 | **Simple mode (elder/assisted).** A photo grid of up to 9 people, large answer buttons, confirm-before-decline, a larger keypad, and optionally the caller's name spoken aloud (TTS is local and needs no microphone). A relative sets it up and exports it as an encrypted file or QR. It is imported on the other phone, with no account. |
| X5 | **Handshake card exchange.** The existing QR transfer gets an automatic "Met at ___ on 25 Sep" note on the received contact. With one tap, both phones show their own QR code to each other. |
| X6 | **Weekly digest as the default reminder** (R4), with one serendipity pick: someone you haven't talked to in over a year, and never the same person twice in a row. |

### 5.6 Distribution (D)

| # | Item |
|---|---|
| D1 | **F-Droid signing guard** (G1) for both apps, with a Gradle check that builds with the signing config removed. |
| D2 | **Reproducible build check:** a script that builds twice from clean and compares the APKs, ignoring the signature (`apksigcopier compare`-style). The results are recorded in `docs/RELEASING.md`. |
| D3 | **Fastlane metadata complete:** `title.txt`, `short_description.txt`, `changelogs/<versionCode>.txt` for 1–5, screenshots (placeholders the user replaces with real ones), in English plus the 7 locales we ship. |
| D4 | **An F-Droid build recipe** (`fdroid/app.parley.phone.yml`) plus `docs/RELEASING.md`: tag convention `v3.2.0`, literal versionCode/Name, how Binaries/AllowedAPKSigningKeys work, and how to submit the Lists companion as a separate app. |

## 6. Deliberately not doing (this round)

- **Reading WhatsApp and Signal notifications to detect contact.** It would be accurate, but notification access is the broadest read permission on Android, and a single bug would expose message content. R3 covers the common case (contact you start from Parley) with no permission at all. We will revisit this only as a separate, opt-in module after an audit.
- **Voice memo transcription and call recording.** Both need RECORD_AUDIO, which our permission guard deliberately forbids. Text notes and promises cover the need.
- **On-device AI summaries of notes.** No offline system API is reliable on minSdk 29, and a bundled model would add hundreds of MB.
- **Guilt grades and streaks.** Research shows these drive people away (see §3.3).

## 7. Roadmap v3.2 (proposed)

1. **Fixes first:** G1–G5 and C1. Small, independent, shipped in the first wave.
2. **Wave A, Circle core:** R2 (interactions and the timeline data layer), then R1, R3, R4, R5 and U4. R2 must land first, because the other items read from it.
3. **Wave B, in parallel with A:**
   - Phone: P1–P9.
   - Onboarding and layout: U1, U2, U3, U5, U6 and C2–C4.
   - Distribution: D1–D4.
4. **Wave C, once A has merged:** R6–R10 and X1–X6, which build on the interactions and notes. Then C5.
5. **Localisation** of all new strings into the 8 languages, the U7 writing guide, a full review round, and the release **3.2.0** (versionCode 5) plus Lists 1.1.1 if its signing guard changed.

Device checks go in TESTING.md §14:
- the PiP and bounce-back behaviour;
- the default-dialer rescue on a sideloaded install on Android 14/15/16;
- the digest timing;
- "Log this?" with each messenger;
- the widget with app lock on.
