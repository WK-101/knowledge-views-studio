# Parley vs. four more open-source apps + "who can see your contacts"

*24 Sep 2026. Round 2 of the competitive analysis (round 1: [COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md)).*

> **Updated after round 3** ([COMPETITIVE_ANALYSIS_3.md](COMPETITIVE_ANALYSIS_3.md), six more apps). Changed items are marked **(r3)**. The release plan in §7 is replaced by round 3's §8.

**What was read.** The full source of each app: every Kotlin and Java file, the manifests, layouts, menus, preference XML, English strings and build files. Also each issue tracker, sorted by reactions and comments, with the top threads read in full, plus forum discussion wherever it existed.

| App | Source | Lines read | State |
|---|---|---|---|
| **Fossify Contacts** `org.fossify.contacts` | [FossifyOrg/Contacts](https://github.com/FossifyOrg/Contacts) v1.6.0 + contact code in [Fossify Commons](https://github.com/FossifyOrg/Commons) | ~14.7k | Active; View-based contacts manager, no dialer |
| **Koler** `com.chooloo.www.koler` | [Chooloo/koler](https://github.com/Chooloo/koler) v1.6.1 + unmerged `compose` branch | ~15k | Stalled since 2023; dialer + in-call, no contacts editing |
| **Yet Another Call Blocker** `dummydomain.yetanothercallblocker` | [xynngh/YetAnotherCallBlocker](https://gitlab.com/xynngh/YetAnotherCallBlocker) + [LibPhoneNumberInfo](https://gitlab.com/xynngh/LibPhoneNumberInfo) | ~13k | Abandoned (last release 2021); blocker, no dialer |
| **NovaDial** `com.novadial.phone` | [dhilipmpms/NovaDial](https://github.com/dhilipmpms/NovaDial) v1.5.2 | ~19k | 3 months old, one developer; Fossify Phone fork with an editor added |

For the feature idea, AOSP ContactsProvider, PermissionController roles, DevicePolicyManager, the Android 17 Contact Picker docs, GrapheneOS Contact Scopes source, and the Play policy announcements were read. Sources are cited inline.

---

## 1. Verdict

None of the four is a threat on scope. Parley is the only one of the five apps that combines a full contacts manager, a dialer, an in-call screen, rule-based screening, encrypted backup and a vault, all without INTERNET. Their value to us is different:

1. **Their bugs are a free test plan.** Each app has at least one data-loss or call-reliability bug visible in the code. Three of them made us check Parley:
   - **Real Parley bug, now fixed.** If Android's contacts database failed or was busy during an incoming call, Parley treated the caller as a stranger. With "block non-contacts" on, a real contact could be blocked.
     - Fossify avoids this with a three-way lookup result: found, not found, or couldn't check.
     - `ContactsRepository.isContact()` now does the same, and screening lets the call ring when it can't check (commit 966f8cd).
   - **Parley was already safe from the rest.**
     - USSD `#` truncation (NovaDial): Parley builds call links with `Uri.fromParts`.
     - Wrong SIM extra (Koler): Parley uses `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE`.
     - Swap greyed out on GSM (Koler): Parley shows Swap whenever another call is on hold.
     - Proximity sensor during speaker calls (Koler): Parley only turns the screen off for earpiece calls.
     - A `noHistory` call screen (Koler): not the case in Parley.
2. **Their users' pain points line up with Parley's strengths.**
   - Fossify's most-reacted issues are lossless fields and editing only what changed. Parley has the lossless ContactRecord and diff-based saves.
   - Koler's are the call notification and getting back to a call. Parley uses CallStyle notifications with a full-screen intent.
   - YACB's are "no overlay" and "no allow list". Parley has its own incoming-call screen and screening rules.
   - We should *say so* in the store listing (§6).
3. **The genuinely new ideas to adopt**, ranked in §4:
   - call-waiting and on-hold layouts (NovaDial, Koler);
   - offline, legally clean **spam list packs** (the YACB lesson, done properly);
   - allow rules, both permanent and temporary;
   - a "second line" under names to tell same-name people apart;
   - labels with their own ringtone and "message everyone";
   - drag-to-reorder favourites;
   - a "Return to call" chip;
   - per-number call statistics.
4. **Per-app contact visibility is only partly possible.** Android gives no app, not even the default dialer, control over what other apps read from the contacts provider. Parley can offer these, in order of strength:
   - keep contacts out of the provider (the vault, extended to "private by default");
   - a contacts-access audit that sends you to Android Settings to revoke access;
   - hand over a single contact when an app asks you to pick one;
   - an advanced, optional **sandbox profile** where untrusted apps see only the contacts you choose.

   True per-app, per-contact scoping needs the OS: GrapheneOS Contact Scopes today, and partly Android 17's system Contact Picker. Details in §5.

---

## 2. App-by-app

### 2.1 Fossify Contacts

**Architecture.**
- XML Views, three optional tabs: Contacts, Favorites, Groups.
- The real logic lives in the shared Commons library, which also serves Fossify Phone, Messages and Calendar.
- "Private" contacts are kept in a Room database.
- No INTERNET permission. `allowBackup="true"`.

**Worth copying:**
- Tapping the avatar opens the contact; tapping the row does the configurable action (call, view or edit).
- On the detail page, **tapping an account ("source") row edits that one raw contact**. It is the best storage UX we have seen.
- **Contact counts per account** in the filter, export and backup dialogs.
- A "hide year" checkbox in the date picker.
- **Pinch-to-change grid columns and drag-to-reorder** in Favorites.
- Group ringtone, SMS-all and email-all in the group's top bar.
- Search that ignores accents, covers notes, company and websites, and ranks prefix matches first.
- Empty states that say *why* the list is empty.

**Bugs to turn into Parley regression tests:**
- **Plain-text addresses are lost on export.** An inverted `isEmpty` fold writes an empty structured ADR.
- **"Other" and custom-label dates are dropped on export**, and so are starred and ringtone.
- **One bad card aborts the rest of an import.**
- Each save deletes and re-inserts every data row, which loses row ids, `IS_SUPER_PRIMARY` and unmodelled columns, and marks everything dirty for sync.
- Group memberships are deleted across the whole aggregate but re-added to only one raw contact.
- Labels are looked up by comparing against localised strings.
- **"Merge duplicates" matches on display name alone.** Two different people named "John Smith" become one, and bulk delete removes both.
- **The private-contacts provider is exported with no permission**, so any app can read names, numbers and birthdays. The "private" database is unencrypted and included in cloud backup.
- Backups are plaintext `.vcf` with no rotation or verification, at a fixed 6 am, on Android 11+ only.

**Top user asks** (reactions or comments):
- Structured addresses (#30, 19; Simple Contacts #364, 25/50c).
- All Nextcloud/RFC fields (#85, 18).
- Default storage for new contacts (#9, 18).
- Relation field (#90).
- **Choose the extra line shown in lists** (#102, 11).
- Editable display name (#129).
- Accounts missing on Android 14 / LineageOS 21 (#83, 38c; #186).
- Call button in the list (#69).
- Choose what to share / share by QR (#82, #13).
- Merge duplicates (#37).
- **A "no group" filter and multi-group filter** (#46, #297).
- Custom date labels (#154).
- Phonetic names (#193).
- **Contacts vanishing with no explanation** (#393, 16c; #237).
- Group ringtone not working (#213).
- Trash (#117).
- **Copying contacts to and from the SIM** (#99).

### 2.2 Koler

**Architecture.**
- A reusable `chooloolib` library plus a thin app module, built with Views, MotionLayout and Hilt.
- LiveData, RxJava and coroutines are mixed together.
- Contact editing is handed off to the system app.
- A half-finished Compose rewrite sits on a branch.

**Worth copying:**
- A collapsing big-title header, with one search bar filtering both tabs.
- Every secondary surface is a thumb-reachable bottom sheet.
- The empty Call button fills in the last outgoing number.
- An **audio-route picker only when Bluetooth or a wired headset is present**, filtered by `supportedRouteMask`; otherwise a plain Speaker toggle.
- An "X is on hold" banner.
- A **landscape in-call layout** with the caller on the left and controls on the right.
- In the Compose branch:
  - it reads `EXTRA_OUTGOING_CALL_EXTRAS` in `onBind`, so "Calling via SIM 2…" shows before the `Call` object exists;
  - it has an explicit **`EXTRA_INCOMING`** UI state for ringing while a call is already active.

**Bugs visible in the code:**
- **POST_NOTIFICATIONS is missing** at target 33, so on Android 13+ there is no call notification. Combined with a `noHistory` call activity, users can't return to or hang up a call. This is issue #557, the top open bug.
- **The wrong SIM extra** (`VoicemailContract.EXTRA_PHONE_ACCOUNT_HANDLE`) means the chosen SIM is ignored (#290).
- **Swap only when `CAPABILITY_SWAP_CONFERENCE` is set**, which is a CDMA-only capability.
- Block/unblock is never visible (#310).
- The proximity wake lock is held on speaker (#453).
- The timer and proximity lock are lost after screen off/on.
- Call callbacks leak (a new wrapper per access, never unregistered).
- A `ToneGenerator` is created per keypress and never released.
- Recents search builds SQL by string concatenation, so "+44" or "O'Brien" can't be searched.
- Private numbers show as blank rows.
- Five unused dangerous permissions, including RECORD_AUDIO.
- **Accessibility:** icon-only in-call actions with no content descriptions.

**Top user asks:**
- Cannot return to or end a call (#557, 12👍, plus 6 duplicates).
- Incoming UI missing (#544).
- Heads-up vs full screen (#372, #238).
- Dual-SIM choice ignored (#290, #503).
- T9 (#214).
- Slide to answer (#376).
- Missed-call notification (#318).
- Call waiting only vibrates (#578).
- Emergency call with no UI (#446).
- System font (#378).

### 2.3 Yet Another Call Blocker

**Architecture.**
- Java and Views.
- `NumberInfoService` combines four sources: contact, community database, "featured" company name, and blacklist.
- Two blocking engines: a CallScreeningService, and a legacy PHONE_STATE plus `endCall` path.
- **Direct Boot aware**: all data lives in device-protected storage.
- It has INTERNET and carries F-Droid's "Non-Free Network Services" anti-feature label.

**The database lesson.** YACB's offline database is the "Should I Answer?" (SIA) data.
- Deltas and reviews are fetched **by impersonating the proprietary SIA client**: faked Samsung device fields, SIA's version code, and a hard-coded MD5 salt.
- The author writes "I didn't ask for a permission to use it".
- SIA's terms forbid redistribution. EU database rights may apply. The reviews are personal data. The library is AGPL.
- **Parley must not use this data, or even an importer for its format.**
- The engineering is still instructive:
  - prefix-sliced, sorted binary files with binary search;
  - delta updates with atomic merge;
  - filtering by country prefix at unzip time.

**Worth copying:**
- A compact rating row (icon + count × 3).
- **Per-verdict notification channels.**
- **Hit counters on block rules** ("5 calls, last 2 days ago").
- A **confirmation before any action that would leak a contact's number** to a website.
- "Calls from contacts are never blocked" shown inside the rule editor.
- A "Blocked call" notification on by default, with a warning when it is switched off.

**What it lacks** (these are the pain points):
- **No allow list** other than contacts (#11, #26, #66).
- **Blocks on a single negative review.**
- No "not spam" action.
- No overlay or full-screen card (#3, 21 upvotes).
- No per-SIM rules (#45).
- No multi-select block (#57).
- No shareable lists (#60).
- The service broke in Russia when a source died (#62).
- Unmaintained, and Play Protect flagged it (#86, 19 upvotes).

### 2.4 NovaDial

**Architecture.**
- A Fossify Phone fork with a dialog-based contact editor added.
- Views, Kotlin, no tests.
- **Declares RECORD_AUDIO without using it**, and F-Droid shows "Record audio" on its page.

**Worth copying:**
- A **call-waiting banner that slides up while the active call stays visible and dimmed**.
- An **on-hold strip** with inline swap, merge and end.
- An **automatic resume** of the held call when the active one ends.
- A **per-number statistics card**: counts by type and total talk time.
- **Haptics on connect, disconnect, swap and merge.**
- A "(3)" badge for consecutive missed calls.
- **Crash-safe ringtone boost**: the previous volume is saved before boosting and restored even after a crash.
- The instant cached name is replaced by a resolved name via a session token that discards stale lookups.
- A call-log observer with a 200 ms debounce.
- A "Call via system app" fallback when not the default dialer.

**Bugs visible in the code:**
- **The editor loses data.**
  - Phone types other than Mobile, Home, Work and Other become Other, and custom labels are dropped.
  - Existing emails are wiped when one is typed.
  - The name is written into WhatsApp and Signal raw contacts.
  - Changing the account rewrites the raw contact's account, which breaks sync.
- **USSD `*121#` dials `*121`** (#64), because it builds the URI with `Uri.parse("tel:…")`.
- It opens the overlay-permission screen every time you leave a call.
- The notification chronometer resets on every update (no `setWhen`).
- **The full-screen intent is re-posted on every call update**, which causes the Android 15 "frequent full-screen notifications" warning (#63).
- A notification trampoline that Android 12+ blocks.
- **The Recents cache sits in SharedPreferences with `allowBackup=true`**, so call history goes to cloud backup.
- Number normalisation hard-coded for India. Recents dedupe uses the last 9 digits.
- An exported MissedCallReceiver with no permission, so any app can post fake missed-call notifications.

---

## 3. Parley checks triggered by this round

| Check | Result |
|---|---|
| Screening treats "lookup failed" as "stranger" (Fossify tri-state lesson) | **Bug. Fixed in 966f8cd.** `ContactsRepository.isContact()` returns true, false or null, and screening fails open on null. The vault lookup fails open too. |
| `#` survives in call URIs (NovaDial #64) | OK: `CallPlacer` uses `Uri.fromParts("tel", …)` |
| Correct SIM extra key (Koler #290) | OK: `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE` |
| Swap on GSM (Koler) | OK: Swap shows whenever another call is HOLDING |
| Proximity on speaker or Bluetooth (Koler #453) | OK: `ProximityController` acquires only for earpiece calls while the UI is visible |
| In-call activity re-entry (Koler #557) | OK: CallStyle notification plus full-screen intent. The only `noHistory` activity is the shortcut trampoline |
| Full-screen intent re-posted on every update (NovaDial #63) | Parley de-duplicates posts by signature (`CallNotifier`). **Add a test** that the full-screen intent is posted once per ring |
| Call history in cloud backup (NovaDial) | Parley opts out of cloud backup for its own data. **Keep a test** that no call-log data lands in SharedPreferences |
| Unused dangerous permissions (NovaDial, Koler) | Enforced by Parley's build-time permission check |
| **(r3)** T9 covers every Cyrillic alphabet (Emerald) | **Bug**: Ukrainian, Belarusian, Bulgarian, Serbian and Macedonian letters missing → K1 |
| **(r3)** Screening responds before logging (Call Blocker) | **Needs fixing**: the blocked-call log is written before `respondToCall` |
| **(r3)** Call-log retention promise (Logger) | **Verify**: the provider may trim to about 500 rows per account → H1, or softer wording |

---

## 4. Adopt list: ranked, with placement

Effort: S ≈ a day, M ≈ a few days, L ≈ a week or more.

### Tier A: calling (highest user impact)

| # | Feature | Effort | Where in Parley |
|---|---|---|---|
| A1 | **Call-waiting sheet.** While a call is active, an incoming call slides up as a sheet with **Hold & answer · End & answer · Decline · Reply**. The current call stays as a dimmed card at the top. | M | In-call screen (`telecom/ui`). Uses `CallManager.endAndAnswer` |
| A2 | **On-hold strip.** "Ana on hold · 02:10" with Swap / Merge / End inline. Tap the strip to swap. Automatically resume the held call when the active one ends. | S | Top of the in-call screen |
| A3 | **"Return to call" chip.** "● On call with Ana · 03:12 · Return" above the bottom navigation whenever a call exists. | S | Home scaffold (`HomeScreen`) |
| A4 | **Notification health card.** Checks notifications allowed, full-screen intent allowed (Android 14+), dialer role and battery optimisation, each with a one-tap fix. | S | Privacy dashboard, plus a first-run banner when something is off |
| A5 | **Adaptive audio button.** A plain Speaker toggle without accessories. With Bluetooth or wired, a route button that opens a picker of supported routes, naming each Bluetooth device (`CallEndpoint` on Android 14+). | S | In-call grid, Speaker slot. Check Parley's current audio sheet against this |
| A6 | **Connect and disconnect haptics** (respect silent mode) | S | Settings › Calls |
| A7 | ~~Empty call button fills in the last number~~ **(r3) Already built** (`KeypadTab.callNow`); add a test only | — | Keypad tab |
| A8 | **(r3) Merged into B24** (ringtone per reason). **"Ring loud for…"**: boost to maximum only for favourites or repeat callers, crash-safe restore | S | Settings › Calls, plus a per-contact toggle next to Ringtone |
| A9 | **Landscape and foldable in-call layout**: caller card on the left, controls on the right | S–M | In-call screen |
| A10 | **Pending-SIM hint** "Calling via Work SIM…" before the `Call` exists | S | Dialling state of the in-call screen |
| A11 | **Hang-up tile**: a Quick Settings tile that ends the active call, as a safety net | S | Quick Settings |
| A12 | **In-call accessibility pass**: labelled, state-aware buttons ("Mute, off"), TalkBack custom actions for answer and decline | S | In-call screen |
| A13 | **USSD sheet**: detect `*…#`, show the carrier reply in a dialog (`sendUssdRequest`), keep a local history of balance-check replies | M | Keypad |

### Tier B: screening and spam, offline and legal

| # | Feature | Effort | Where |
|---|---|---|---|
| B1 | **Allow rules**: exact, prefix or wildcard, always winning over every block reason. **"Allow for 24 h"** reuses the temporary-contacts expiry | S–M | Blocking screen: an "Always allow" section above the rules. Recents long-press offers "Always allow" / "Allow today" |
| B2 | **Verdict on the incoming screen and in Recents**: "Blocked by rule 'Telemarketing' · 7 calls" or "Reported by FTC list" | S | In-call caller card; Recents secondary line |
| B3 | **Rule hit counters** and **a "why + Not spam" action in the blocked log** | S | Blocking rules list; blocked log |
| B4 | **Spam list packs (`.parleylist`)**. Covered in detail after this table | L | Settings › Blocking › "Spam lists" (hero card: "2 lists · 184,302 numbers · updated 3 days ago") |
| B5 | **Built-in regulator range packs**, starting with France's ARCEP telemarketing ranges; suggested from the SIM country | S | Spam lists, toggle per country |
| B6 | **Per-verdict notification channels**: Likely spam / Reported / Blocked | S | System channels, linked from Settings › Notifications |
| B7 | **Import a user block list** (CSV, NoPhoneSpam, YACB blacklist; *never* the SIA database) and **export or share your rules as a signed pack**, by file, QR or the sync folder (covers YACB #60 without a server) | S | Blocking › Import / Share |
| B8 | **Multi-select Block in Recents**, plus a "Search number on the web" action that hands the number to the browser, with YACB's confirmation when it is a contact | S | Recents selection bar; number history |
| B9 | **(r3) Changed:** a per-rule SIM field (S), evaluated in `CallManager` (screening calls carry no SIM), on one per-SIM page with the plan meter (T8). Was: **Per-SIM screening profiles** ("Work SIM: block non-contacts") | M | Blocking › per-SIM tabs when there are 2+ SIMs |
| B10 | **Wangiri guard**: a missed international or premium call that rang once or less gets a "Don't call back" badge, and calling back asks for confirmation (libphonenumber `getNumberType`) | S | Recents badge; dial confirmation |
| B11 | **Outgoing warning** for premium-rate, shared-cost or listed numbers | S | Dial confirmation sheet |
| B12 | **(r3) Add** a 1-hour regret window (no suggestion if they call back, you dial back, or you save them) and the short-answered-call signal; built on H9 `CallLogIndex`. **Personal reputation**: numbers you keep rejecting, or that hang up within 3 s, get a local "likely spam for you" score and an offer to make it a rule | M | Suggestions in the blocked log |
| B13 | **(r3) Must have no side effects** (SpamBlocker's test sends real SMS and reports); add "test this call" on Recents and coverage badges on past calls. **Screening dry run**: "What this list or rule would have blocked last week", checked against the call log before enabling | S | Spam list and rule editor |

**B4, spam list packs, in detail.**
- **Format:** a zip containing:
  - `manifest.json` (id, publisher, source, licence, version, regions, categories);
  - `numbers.bin` (sorted uint64 E.164 plus category and score bytes, 10 B per entry);
  - `ranges.txt`;
  - an Ed25519 signature.
- **Storage and lookup:** kept in device-protected storage so screening works in Direct Boot. Lookup is a memory-mapped binary search, under 1 ms, done inside `CallScreener`.
- **Getting packs:**
  - (a) import through the file picker;
  - (b) a subscribed folder (Syncthing, Nextcloud, browser downloads);
  - (c) an optional separate **"Parley Lists Updater"** app. It has INTERNET but no contacts or phone permissions, downloads only static files, and exposes them through a signature-permission ContentProvider. Never `sharedUserId`.
- **Policy defaults:**
  - list hits default to **warn**;
  - blocking is opt-in per pack, with a score threshold;
  - contacts, allow rules and repeat callers always win;
  - stale packs are flagged.
- **Sources:**
  - the US FTC Do-Not-Call reported-calls data (public);
  - regulator range lists;
  - community packs under an explicit licence;
  - the user's own lists.

### Tier C: contacts

| # | Feature | Effort | Where |
|---|---|---|---|
| C1 | **"Second line" setting**: Number / Company · Title / Nickname / Account / None under names, plus **automatic company display when two visible names collide** (Fossify #102) | S | Settings › Appearance; contact rows |
| C2 | **Label filters**: an "Unlabelled" chip, AND/OR multi-select, "Merge labels" | S–M | Contacts tab filter chips; label management overflow |
| C3 | **(r3) Label ringtone merged into B24.** **Label page actions**: **ringtone per label** (resolved as contact → label → default by Parley's own ringer), **Message all**, **Email all** | S | Label detail top bar |
| C4 | **Favourites: drag to reorder** (stored by lookup key), sort Custom / A–Z / Most called, **pinch to change grid columns** | S–M | Favorites tab |
| C5 | **Actionable account chips** on the detail page: Edit this copy · Move to… · Unlink | S | Contact detail, "Saved in" section |
| C6 | **Live duplicate warning in the new-contact editor**: "Anna Smith already exists · Open / Add these details to her" | S | Contact editor |
| C7 | **Account diagnostics** in Health check: accounts Android reports vs accounts that own contacts, "Phone account missing → fix", sync disabled for an account (Fossify #83, #186) | S | Health check |
| C8 | **SIM copy**: "Copy to SIM" and "Import from SIM" (`SimPhonebookContract` on 31+, ADN on 29–30), with a warning that only name and one number fit | M | Contact overflow; Import screen |
| C9 | **Date of death** event (stops birthday reminders; shows "would have turned N") and a **"Prefer nickname"** display option | S | Editor events; Settings › Appearance |
| C10 | **Per-account counts** everywhere accounts are listed (filter, export, backup, default-account picker) | S | Those dialogs |
| C11 | **"Why did this change?"**: a provenance line on the contact (which app or sync adapter changed it, from the journal and time-machine diff), plus "Only changed fields were written" on Parley's own saves | M | Contact detail, version history |
| C12 | **(r3) Replaced by H6** (per-contact, all numbers, E.164, weekday × hour). **Per-number statistics card**: counts by type, total talk time, "usually calls weekday evenings" | S | Number history; contact detail "Calls" section |
| C13 | **Pinned shortcut that opens the contact page** (in addition to call and message shortcuts) | S | Contact overflow |
| C14 | **Call-screen background per contact**: copied into app storage, keyed by lookup key, included in backup (not NovaDial's broken URI-by-id) | M | Editor › "Call screen" |
| C15 | **Protected private-name lookup for other apps**: a permission-protected provider that returns only name and photo *for a number query*, never a list, approved per app and logged (the safe version of Fossify's leaky provider) | M | Privacy › "Let apps show private names" |

### Tier D: robustness and trust

- **D1.** **(r3) Extended** with round 3's cases: Cyrillic T9; Mexican `+52 1` forms; literal `_ %` in rules; respond before logging; limits end only their own call; quotas don't reset on reboot; CSV/ICS escaping; import deduplication; billing rounding never changes stored data; free-text number parsing; E.164 with the SIM country; a failed lookup is never cached as "Unknown". Regression tests from this round:
  - `#` in `tel:` URIs;
  - the SIM extra key;
  - SIM pickers keyed by handle, not label;
  - a full-screen intent posted once per ring;
  - notification chronometer `setWhen(connectTime)`;
  - custom phone and event labels survive save and export;
  - plain-text ADR round-trips;
  - one bad vCard doesn't abort the import;
  - private numbers render as "Private number";
  - search with `+` and apostrophes;
  - no call data in SharedPreferences.
- **D2.** **Diagnostics export that masks numbers** by default.
- **D3.** Store listing section: **"Permissions Parley doesn't ask for, and why"**, backed by the build-time permission check. NovaDial's unused RECORD_AUDIO and YACB's Play Protect flag show how much this matters to this audience.

---

## 5. "Who can see your contacts": feasibility of the per-app visibility feature

### 5.1 How contacts access actually works

- **One provider, one permission check.** All contacts live in one system ContentProvider, `ContactsProvider2` (`com.android.contacts`).
  - Its manifest sets `readPermission=READ_CONTACTS`, `writePermission=WRITE_CONTACTS` and `grantUriPermissions=true` ([manifest](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/main/AndroidManifest.xml)).
  - Enforcement is a single permission check for the whole provider ([ContactsProvider2.java](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/main/src/com/android/providers/contacts/ContactsProvider2.java)).
  - **Any app granted Contacts reads every contact from every account.** Nothing filters by caller.
- **Visibility flags are display hints, not access control.** `IN_VISIBLE_GROUP`, `GROUP_VISIBLE` and account visibility only affect what contacts apps choose to show.
- **There is no "default contacts app" role.** The only contacts role in AOSP, `SYSTEM_CONTACTS`, is `systemOnly` ([roles.xml](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)). **ROLE_DIALER** gives Parley its own access (contacts, phone, call log…) and **no power over other apps' access**.
- **Parley cannot change another app's permission.** Per-permission settings intents and app-op usage history are restricted to the system. Parley can only deep-link to an app's info page in Settings.

### 5.2 What the OS itself now offers

- **Android 17 Contact Picker** (API 37, June 2026):
  - apps call `ACTION_PICK_CONTACTS` and receive only the contacts and fields the user selects, without READ_CONTACTS;
  - for apps targeting 37+, the old `ACTION_PICK` is routed to this **system** picker ([developer docs](https://developer.android.com/about/versions/17/features/contact-picker), [AOSP](https://source.android.com/docs/core/permissions/contacts-picker)).
  - READ_CONTACTS itself is still all-or-nothing. There is no iOS-style "limited access" choice, so the user can't force an app into picker mode.
  - **A third-party app can't be that picker.**
- **Google Play policy** (announced April 2026, enforced 27 Jan 2027):
  - apps targeting 37+ may request READ_CONTACTS only when the picker isn't enough, and must file a declaration;
  - contacts, dialer and messaging apps are named as legitimate ([Android Developers Blog](https://android-developers.googleblog.com/2026/04/giving-users-clearer-choice-and-everyone-a-safer-more-trusted-app-ecosystem.html)).
  - **Parley's Play release will need this declaration.**
- **GrapheneOS Contact Scopes:** this is exactly the requested feature. The app thinks it has Contacts access but sees nothing until the user grants specific contacts, numbers, emails or groups. Writes are blocked and accounts hidden ([usage guide](https://grapheneos.org/usage)).
  - It works by redirecting the app's ContentResolver calls to a `ScopedContactsProvider` inside the OS ([source](https://github.com/GrapheneOS/platform_packages_providers_ContactsProvider)). **No app can do this without OS changes.**

### 5.3 What Parley can do (honest effectiveness)

| Approach | Works? | Notes |
|---|---|---|
| **Vault** (keep contacts out of the provider) | **Yes, strongly** | The only complete protection on stock Android. The cost: car Bluetooth (PBAP), watches and other apps see just the number. Parley must never write vault names into the call log's cached name |
| **"Private by default"** (new contacts go to the vault; others are moved there explicitly) | Yes | Same caveats, and it must be opt-in |
| **Parley as the picker** (`ACTION_PICK` / `GET_CONTENT`, already implemented) | Partly, and shrinking | Returns one contact with a temporary grant. It works on Android 10–16 and for apps targeting below 37. On 17+ the system picker takes over for modern apps. Could return only chosen fields via a Parley provider (client compatibility unverified) |
| **Contacts-access audit** | Awareness only | Lists apps holding READ_CONTACTS and links to Settings to revoke. Play build: launcher-visible apps via `<queries>`. F-Droid build: optionally `QUERY_ALL_PACKAGES` (Play restricts it) |
| **Sandbox profile** (Parley as a Shelter-style profile owner) | **Yes, for apps moved into it** | Each profile has its own contacts provider. Parley provisions a work profile offline, fills its provider with only the contacts or fields you choose, and blocks the personal side from reading it. Limits: one work profile per device (conflicts with a corporate profile, Shelter or Insular); Private Space can't be managed by apps; OEM and GMS provisioning quirks; apps must be installed into the profile; it makes Parley a device-policy app (large scope) |
| **Filtered or virtual account inside the provider** | **No** | Every READ_CONTACTS holder sees all accounts |
| **Temporarily deleting and re-adding contacts** | **No: destructive** | Deleting synced contacts deletes them on the server and on every other device, and re-adding changes ids and loses links. Ruled out |
| **Decoy or fake data** | **No** | It would sync to your accounts and corrupt real data. Ruled out |
| **Directory provider keyed on the caller app** | Niche | The contacts provider passes the real caller package to directory providers, so Parley could share vault contacts per app for apps that search directories (mostly email clients). Prototype-grade |

### 5.4 Recommended design

**Settings › Privacy › "Who can see your contacts"**

1. **Header**, telling the truth: *"Android doesn't let any contacts app decide what other apps see. Any app you've allowed 'Contacts' can read every contact on this phone, from every account. Here is what Parley can do."*
2. **Apps with access to your contacts.**
   - Each row shows icon, name, "can read all contacts" and **Change in Settings**.
   - Apps that need contacts for their main job (messengers, backup, email) get a short note.
   - A "not used recently" hint points to Android's auto-revoke setting.
   - *Wording: "These apps can read all your contacts. Parley can't limit them. Tap to change their permission in Android Settings."*
3. **Private by default** (switch, off by default).
   - New contacts are saved to the vault; a "Move to private" bulk action is offered.
   - It lists exactly what stops showing names: car, watch, other apps and the system call log.
   - *"Private contacts are never stored where other apps can read them. Only Parley shows their names."*
4. **Share just one contact** (information row).
   - *"When an app asks you to pick a contact, only that contact is shared."*
   - On Android 17+ it explains the system picker, which does the same.
5. **Sandbox for untrusted apps** (advanced, off by default; offered only when provisioning is allowed and no work profile exists; F-Droid build first).
   - Parley creates a separate space. You install apps you don't trust into it and choose which contacts, and which fields, they may see.
   - *"Apps you install here only get the contacts you choose. Apps outside this space are not affected."*
   - It needs a clear teardown path.
6. **On GrapheneOS:** detect the OS and link to **Contact Scopes**, the real per-app, per-contact control. Parley can explain it and recommend it per app from the audit list.

**Never say** "Parley controls which apps see your contacts" or "hides contacts from apps".

**Phasing.**
- Steps 1–4 and 6 are **S–M** and can ship next.
- Step 5 is **L** and a separate module or flavour. It needs device testing on Pixel, Samsung and Xiaomi, plus a scope review (see §7).

**Still to verify on devices:**
- the exact scope of contacts-provider URI grants from the picker;
- whether client apps accept a non-`com.android.contacts` picker result;
- the Android 17 picker's cross-profile behaviour;
- offline profile provisioning on GMS devices;
- Play acceptance of a dialer that can act as a profile owner.

---

## 6. UI and placement lessons (across all four)

- **Keep:** Parley's bottom navigation with its rail on tablets, detail pages as screens rather than dialogs (NovaDial's dialog editor has no room and no discard guard), and grouped settings (NovaDial has about 35 flat rows, and Fossify restarts the activity on setting changes).
- **Adopt:**
  - Koler's thumb-reachable bottom sheets for *secondary* actions, but never stacked more than one deep;
  - the collapsing large title, as optional polish;
  - Fossify's "why is this empty" states and per-account counts.
- **Avoid:**
  - tapping a Recents row to call (NovaDial and Fossify: accidental calls);
  - hard-coded in-call colours (NovaDial #54);
  - overlay bubbles (use the "Return to call" chip plus the CallStyle notification);
  - toast-only errors (YACB);
  - hiding key switches in overflow menus (YACB);
  - screens reachable only by long-press (YACB's database screen);
  - Save and Delete side by side (Fossify).
- **Messaging:** every one of these apps' top complaints is something Parley already handles. The store listing should say so directly:
  - "never blocks a contact, even if the phone is busy";
  - "edits only what you changed";
  - "no permission it doesn't use";
  - "no internet, so no leaks".

## 7. Proposed sequence

> **(r3) Superseded** by the consolidated roadmap in [COMPETITIVE_ANALYSIS_3.md §8](COMPETITIVE_ANALYSIS_3.md). Kept for history.

1. **Next release (v2.1):**
   - calling: A1–A7, A12;
   - blocking: B1–B3, B6, B8, B10;
   - contacts: C1, C3, C4, C6, C12;
   - trust: D1–D3;
   - "Who can see your contacts" steps 1–4 and 6.
2. **v2.2:**
   - spam list packs (B4, B5, B7, B13);
   - C2, C5, C7–C11, C13;
   - A8–A11, A13;
   - B9, B11, B12.
3. **v3 (a separate decision):**
   - the companion list updater app (B4c);
   - the protected private-name provider (C15);
   - the **sandbox profile** (5.4 step 5). It is significant scope and should be decided on its own merits.
