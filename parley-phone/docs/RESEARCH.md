# Parley Phone: Research Brief

*Working name. Researched on 24 Sep 2026. This project is separate from the rest of this repository.*

## TL;DR

There is a clear gap in this market. No current Android app is modern, full-featured and private all at once.

- **The polished apps collect data or push ads.** This covers Google Phone, Samsung, Truecaller, Eyecon, CallApp and drupe. Google Phone collects data, and Samsung's spam protection runs through Hiya, an ad-tech partner. The others are free apps with ads that share device IDs for advertising, and some upload your phonebook.
- **The private apps look dated and lack features.** This covers Fossify, AOSP/GrapheneOS, OpenContacts and the dormant Koler. They use a Material 2 or older AOSP look, have no spam protection, and miss features users ask for.

Four things matter most to privacy-minded users:

1. **Core calling that is completely reliable.** This means answering from the lock screen, Bluetooth routing, call waiting and swapping, merging calls, and emergency calls. These are the bugs that drive people back to Google Phone.
2. **Spam protection that never uploads the user's contacts.**
3. **A modern UI that the user controls.** Layout, answer gesture and density should be settings, not forced redesigns.
4. **Trust that can be checked.** No INTERNET permission, open source, and reproducible builds.

All of this can be built as a native Kotlin app with **no INTERNET permission**. The Android dialer role grants almost everything else automatically.

There are two hard limits:

- **Call recording is not possible** without root or system privileges.
- **Visual voicemail syncing needs internet.**

The plan treats both honestly rather than faking them.

---

## 1. Competitive landscape

| App | Contacts / Dialer / In-call | Scale (Play) | Model | Privacy posture | UI |
|---|---|---|---|---|---|
| **Phone by Google + Contacts** | separate apps / ✔ / ✔ | 4.4★, 5B+ | Platform | Caller ID & spam **on by default**, sends non-contact numbers to Google; Data-safety lists location, audio, device IDs; "data can't be deleted" | M3 Expressive (Aug 2025 redesign) |
| **Samsung Phone + Contacts** | ✔ / ✔ / ✔ | preinstalled | Platform + Hiya | Spam ID requires accepting Hiya's terms (partnership through 2028) | One UI |
| **Truecaller** | partial / ✔ / ✔ (+SMS) | 4.4★, 1B+, ~400M MAU | Ads + subscription | Crowd-sources other people's numbers from users' phonebooks; location used for ads; ad pop-ups after calls | Own |
| **Hiya** | caller ID only | 3.9★, 10M+ | Subscription + B2B | Shares IDs and phone number | Own |
| **Eyecon / CallApp** | ✔ / ✔ / ✔ | 4.3–4.4★, 100M+ | Ads + IAP | Installed-apps list used for ads; CallApp enriches contacts from social networks | Own |
| **drupe / Contacts+ / Showcaller / True Phone** | ✔ / ✔ / ✔ | 3.9–4.4★, 10M+ | Ads + IAP | Device IDs shared for ads; ad after every call (drupe) | Own |
| **Simple Dialer (now ZipoApps)** | – / ✔ / ✔ | 3.7★, 1M+ | Ads + IAP | Was open source; after the Dec 2023 sale it gained ads and trackers | Material 2 |
| **Fossify Phone / Contacts** | ✔ / ✔ / ✔ (2 apps) | 4.2–4.3★, 100K+ | Donations | No data collected, GPL-3 | Material 2-ish |
| **Right Dialer / Contacts (Goodwy)** | ✔ / ✔ / ✔ (2 apps) | 3.9★, 10M+ | Free | Fossify fork | iOS-like |
| **AOSP / GrapheneOS / LineageOS** | ✔ / ✔ / ✔ | OS | – | Clean; GrapheneOS adds OS-level *Contact Scopes*; LineageOS has local recording | Old AOSP ("amateurish") |
| **OpenContacts** | private DB / ✔ / basic | F-Droid | Free | Private contact store, but declares INTERNET, overlay and query-all-packages | Dated |
| **Koler** | – / ✔ / ✔ | F-Droid | Free | Clean | Dormant since 2023 |
| **Rivo** (GitHub) | ✔ / ✔ / ✔ | ~500 installs | Free | Compose + M3 Expressive, encrypted vault, T9 | Closest in spirit, very early |

*Figures come from Play listings and Data-safety pages on the research date. See sources at the end.*

### What this landscape tells us

- **"All-in-one" is already table stakes among commercial apps.** Truecaller, Eyecon, CallApp, drupe and Contacts+ all combine contacts, dialer and in-call. Every one of them pays for that with ads or data. What would be new is being **all-in-one and private together**. Among open-source options only Rivo and the tiny Cinnamon project try, and both are very early.
- **The open-source ecosystem has a sell-out problem.** Simple Mobile Tools was sold to ZipoApps. Afterwards users saw ads, trackers and a $14.99/week subscription on the flashlight app. That episode trained privacy users to distrust any single-owner "simple" app. Trust now comes from things users can verify:
  - a manifest with no INTERNET permission;
  - reproducible builds;
  - F-Droid distribution;
  - a copyleft license.

  Promises don't count.
- **Google's Aug 2025 redesign backlash was about being forced, not about the design.** An Android Authority poll with over 10k votes found about 65% at least somewhat positive. The anger came from muscle memory being broken with no opt-out. The design implication is to ship the modern look, but make tab layout, density and answer gesture settings.

---

## 2. What users actually ask for

This is ranked by how often each item appears across GitHub issue reactions (Fossify, Koler), the GrapheneOS forum, Lemmy mirrors of r/fossdroid and r/degoogle, and tech press. Reddit and Exodus Privacy blocked automated access, so they are not primary sources here.

| # | Request | Feasible offline & without root? | Evidence |
|---|---|---|---|
| 1 | Call recording | **No** (see §4) | Fossify Phone #17 (top issue); 15+ GrapheneOS threads |
| 2 | Reliable **merge / conference / call-waiting swap** | Yes | GrapheneOS d/22756, d/6203 |
| 3 | Blocking by **pattern / prefix / wildcard**, choice of reject / silence / hang up | Yes | Google support thread 182141306; Fossify #825 |
| 4 | App-owned **missed-call notifications** | Yes | Fossify #83 |
| 5 | Visual voicemail | Partly (play what the system already synced) | Fossify #48 |
| 6 | **Global, fuzzy, number-normalised search** ("stone" finds "Kingstone"; +33… equals 0…) | Yes | Fossify #150, #254; GrapheneOS d/12208 |
| 7 | **T9 smart dialpad** | Yes | Koler #214; headline feature in "best dialer" lists |
| 8 | Call-history **filters, per-number history, auto-prune** | Yes | Google added filter chips Feb 2025; Fossify #220 |
| 9 | Choice of **answer gesture** (tap / swipe) | Yes | Google made it a setting in 2025; Fossify #175 |
| 10 | **Confirm before dialing**, safe tap targets in search | Yes | GrapheneOS d/22756 |
| 11 | **Default SIM per contact**, carrier labels everywhere | Yes | /e/OS forum, LineageOS #5640, Fossify #162 |
| 12 | Caller ID / spam **without uploading contacts** | Yes (offline rules; optional lookups would need network) | Fossify #590; YACB popularity |
| 13 | Richer incoming-call screen; picker when a contact has several numbers | Yes | Fossify #477, #102 |
| 14 | Merge duplicate contacts | Yes (`AggregationExceptions`) | Fossify Contacts #37 |
| 15 | Full **vCard / CardDAV fidelity** (DAVx⁵ / Nextcloud) | Yes | Fossify Contacts #85 (top issue) |
| 16 | **Private / hidden contacts** and a private call history | Yes | Fossify Contacts #9, Phone #782; OpenContacts |
| 17 | Per-contact ringtone, ringtone for unknown callers | Yes | Fossify #31 |
| 18 | Quick SMS replies / auto-reply to missed calls | Quick reply yes; auto-SMS needs `SEND_SMS` | Fossify #4 |
| 19 | Speaker and mute buttons in the call notification | Yes | Fossify #173 |
| 20 | QR contact sharing with a field picker | Yes (generate offline) | Fossify Contacts #13, #82 |
| 21 | Swipe-to-call / swipe-to-message in lists | Yes | Fossify #77 |
| 22 | Compact density option | Yes | Fossify #147 |
| 23 | Birthdays, tags and groups, hiding rarely used contacts | Yes | Fossify Contacts #128, #32 |
| 24 | RTT, airplane-mode warning when dialing | Yes | Fossify #402, #34 |
| 25 | Modern Material You design in open-source apps | Yes | GrapheneOS d/8283 |

### Pain points to design against

1. **Lock-screen answer failures**, and ongoing calls you can't get back to (Fossify #165, #676, #903). These are deal-breakers.
2. **Bluetooth route switching** takes two tries (Fossify #272).
3. **Emergency calls** where the in-call UI doesn't appear and nothing is logged (Fossify #250). There are also accidental pocket-dials of emergency numbers.
4. **Slow launch and slow history load** (Fossify #385; GrapheneOS "Contacts loading veeery slowly").
5. **Forced redesigns and changing answer gestures**, and gesture conflicts where the swipe to switch apps answers the call.
6. **Accidental calls** when tapping a search result that was meant to open the contact.
7. **Number normalisation bugs** that break both matching and blocking.
8. **Dual-SIM regressions** after OS updates.
9. **Ads after every call** and nagging (Truecaller, drupe).
10. **Default-on data flows** (Google caller ID) and **phonebook harvesting** (Truecaller).

---

## 3. The feature set, grouped

**T** = table stakes, **D** = differentiator, **★** = a gap in the market that we can fill.

- **Dialer:**
  - T: T9 with accents and non-Latin scripts, speed dial, paste a number or USSD code, SIM choice.
  - D: per-contact default SIM, confirm-before-dial, pause/wait characters.
  - ★: T9 that also matches initials and substrings of numbers, instantly (<16 ms per keypress).
- **In-call:**
  - T: full-screen incoming call over the lock screen, proximity sensor, mute, keypad, speaker, Bluetooth routing, hold, add, merge, swap, split, reply with SMS, CallStyle notification.
  - D: configurable answer gesture, notes during a call, flip to silence.
  - ★: a "reliability first" in-call engine. Its buttons are driven strictly by the call's capabilities, with explicit handling for multiple calls, Bluetooth and emergency calls.
- **Contacts:**
  - T: search across all fields, favourites, groups, account picker, vCard, merge duplicates, photos, sort order, birthdays, QR share.
  - D: per-contact ringtone and vibration, notes, last-contacted timeline, links to Signal, WhatsApp or Telegram.
  - ★: an **encrypted private vault** that other apps can't read but that still shows caller ID and can be dialed.
- **Call log:**
  - T: grouping, filters, per-contact history, delete.
  - D: search, statistics, call tags and notes, auto-prune.
  - ★: private history for vault contacts.
- **Blocking and spam:**
  - T: block a number, block hidden numbers, use the system block list.
  - D: block non-contacts, prefix and wildcard rules, quiet hours.
  - ★: **offline spam scoring**: STIR/SHAKEN, neighbour-spoofing detection, user rules and importable blocklists. No phonebook upload.
- **Backup:**
  - T: vCard export.
  - D: CardDAV via DAVx⁵ with no special handling needed.
  - ★: **one encrypted archive** (contacts, call log, rules, notes, settings), scheduled to a folder the user picks.
- **Customisation:**
  - T: dark/AMOLED, dynamic colour.
  - D: M3 Expressive, tab order, density, answer gesture.
- **Accessibility:**
  - T: TalkBack, font scaling, contrast.
  - D: big-button "simple mode", haptic call-state cues, tap-to-answer, one-handed layout. Senior users are currently served by launchers, not dialers.
- **Trust:**
  - ★: an in-app **Privacy dashboard**. It shows each permission, why it is needed, and proof of "no INTERNET". Plus reproducible builds and F-Droid-ready metadata.

---

## 4. Implementation reality: platform facts that shape the design

### The dialer role does most of the work

When the user sets the app as the default phone app (`RoleManager.ROLE_DIALER`), Android automatically grants every permission the app declares from these groups:

- `CALL_PHONE`
- `READ_PHONE_STATE`
- `ANSWER_PHONE_CALLS`
- `READ_CALL_LOG` / `WRITE_CALL_LOG`
- `READ_CONTACTS` / `WRITE_CONTACTS` / `GET_ACCOUNTS`
- `READ_VOICEMAIL` / `WRITE_VOICEMAIL` / `ADD_VOICEMAIL`
- `POST_NOTIFICATIONS`

It also exempts the app from background restrictions (`RUN_ANY_IN_BACKGROUND`). In practice, onboarding is one system dialog, not a wall of permission prompts.

The role also unlocks:

- `BlockedNumberContract`, which only the default phone or SMS app, the system and carrier apps can use;
- MMI/USSD codes through `TelecomManager.handleMmi`;
- the ability to silence the ringer;
- SIM (ADN) contacts;
- cancelling the system's missed-call notification.

**Manifest requirements** come from AOSP `roles.xml`:

- An activity for `ACTION_DIAL`, and one for `ACTION_DIAL` with `tel:`.
- An exported `InCallService`, protected by `BIND_INCALL_SERVICE`, with `IN_CALL_SERVICE_UI=true`.
- We also declare `IN_CALL_SERVICE_RINGING=true`. We then own the ringtone and vibration, which gives us per-contact ringtones and do-not-disturb handling.
- **Never** declare `CAR_MODE_UI`, because the role prohibits it.

### INTERNET permission: not needed

Nothing in Telecom, Telephony, ContactsProvider, CallLog or the blocked-numbers stack needs network access. Omitting INTERNET has three costs:

- **Visual voicemail:** we can't run our own OMTP/IMAP sync. We *can* play voicemails already synced by the system or carrier app, and offer "call voicemail".
- **Spam lookups:** no online lookup, so spam handling is offline-only (see below).
- **Crash reports:** none sent automatically. We can offer a local log file the user exports.

**Build-time guardrail:** some libraries quietly merge `INTERNET` into the manifest. CI must fail if the merged manifest contains it.

### Incoming calls: the one path that must never fail

The official pattern:

1. In `onCallAdded`, post a CallStyle notification with `IMPORTANCE_MAX` and a full-screen intent, **before any disk or contacts I/O**.
2. Fill in the caller's name and photo asynchronously.

Other requirements:

- The activity uses `showWhenLocked` and `turnScreenOn`, and a single-instance launch mode.
- Telecom binds the service at foreground and top-app priority, so no separate foreground service is needed.
- `USE_FULL_SCREEN_INTENT` stays granted for side-loaded and F-Droid installs, but the user can revoke it. Check `canUseFullScreenIntent()` and deep-link to the setting.

**OEM traps:**

- **Xiaomi/HyperOS:** "Show on lock screen" and "pop-up windows while running in background" fail *silently*.
- **Samsung:** "sleeping apps".
- **Huawei:** launch manager.

Onboarding must detect these and guide the user through them.

### Emergency calls

Emergency calls always go through the preloaded system dialer. We must place every call with `TelecomManager.placeCall`, never with our own `ACTION_CALL`, so that the platform keeps emergency routing correct. Emergency numbers are also never blocked by the platform. We still need our UI to show and behave correctly if Telecom hands an emergency call to our InCallService afterwards; that is the Fossify #250 failure.

### Call screening limits

`CallScreeningService` (the separate `ROLE_CALL_SCREENING`) has these limits:

- It must answer within **5 seconds**; the phone does not ring until it does.
- It only sees the direction, the number handle and the STIR/SHAKEN status.
- It **never receives private, unknown or payphone calls**. "Block hidden numbers" must therefore be done in the InCallService, by rejecting in `onCallAdded`.

The response can reject, silence, skip the call log, or skip the notification.

### Call recording: out of scope, stated honestly

Recording the other party needs `CAPTURE_AUDIO_OUTPUT`, which is `signature|privileged` and not user-grantable. Android 10+ also silences the mic for other apps during calls. The Accessibility-service workaround was banned from Play in 2022 and only captured echo anyway. Google Pixel, Samsung and LineageOS can record because they are *system* apps.

The Shizuku approach (Rivo) would need ADB-level privileges and more study. We list it as a possible later spike, not a promise.

### Dual SIM

- Each SIM is a `PhoneAccountHandle`. Pass the chosen one in `placeCall` extras.
- If none is chosen, the call arrives in `STATE_SELECT_PHONE_ACCOUNT`; we show our SIM picker, then call `phoneAccountSelected`.
- `READ_PHONE_NUMBERS`, needed to show the SIM's own number, is *not* granted by the role. It is optional, and requested only for neighbour-spoofing detection.

### Contacts

- `ContactsContract` has three layers: aggregate Contacts, RawContacts per account, and Data rows.
- Merging and unlinking use `AggregationExceptions` (KEEP_TOGETHER / KEEP_SEPARATE).
- Local-only contacts use null account columns; API 35+ has `getLocalAccountName`.
- Google and CardDAV contacts are just raw contacts that another app syncs. We can edit them without needing internet ourselves.
- vCard: there is no public platform parser, so we use `ez-vcard` or our own. Contacts and backup files go through the Storage Access Framework, so no storage permission is needed.
- Photos: read and write through `DisplayPhoto`, picked with the Photo Picker.

### Private vault: a non-obvious consequence

Vault contacts live in our own encrypted database, not in ContactsProvider, so other apps (WhatsApp, keyboards) can't read them. However, **Telecom always writes the system call log**. For a truly private history we must copy the entry into our encrypted store and delete it from `CallLog` (using `WRITE_CALL_LOG`) after the call ends. This is an opt-in setting, and we explain the trade-off to the user.

### Offline spam scoring: what is actually possible

Signals available with no network:

1. **STIR/SHAKEN** verification status. This is strong in the US and mostly absent elsewhere.
2. **User rules:** prefix and wildcard lists, not-in-contacts, hidden numbers, quiet hours.
3. **Neighbour spoofing:** the caller shares the first N digits of the user's own number but is not a contact.
4. **Importable blocklists** (CSV/JSON) that the user downloads themselves, for example community lists, since the app can't fetch them.
5. **Local reputation:** numbers the user rejected or blocked before, and very short repeated calls.

None of these requires uploading anything.

---

## 5. Architecture decision

**Choice: native Android, Kotlin, Jetpack Compose with Material 3, built with Gradle into a signed APK.**

| Option | Verdict | Reason |
|---|---|---|
| **Kotlin + Compose (native)** | ✅ **Chosen** | `InCallService`, `CallScreeningService`, `RoleManager`, ContactsProvider and the full-screen-intent path are all native Android APIs. Native is the fastest, smallest and most reliable path for the ringing moment. Compose + M3 gives dynamic colour and the Expressive look. |
| Flutter / React Native | ❌ | The in-call path would still be native, so we'd write two stacks. The engine cold-start sits in the incoming-call path. Bigger APK (native .so files, 16 KB page alignment). No benefit for an Android-only app. |
| Kotlin + XML Views | ❌ | Works, but slower to build a modern M3 Expressive UI, and no access to current Compose components. |

**Buildable here.** This environment has JDK 21 and Gradle 8.14, and Google's SDK and Maven repositories are reachable. So the Android SDK command-line tools can be installed, and a signed release APK can be built and delivered from this session.

**Limitation:** there is no KVM, so an emulator can't run here. Verification will be:

- unit tests plus Robolectric,
- lint,
- a check of the manifest and permissions,
- **you testing on a real phone**.

Real telephony such as dual SIM and Bluetooth can only be tested on a real device anyway.

Full stack and module layout are in [PLAN.md](PLAN.md).

---

## 6. Adjacent opportunities (beyond the ask)

| Opportunity | Why it matters | Signal |
|---|---|---|
| **SMS in the same app** (Truecaller, Contacts+, Cinnamon do it) | "Reply with SMS", auto-reply to missed calls and a messaging-first contact screen become first-class. It needs the default SMS role, a much bigger surface area, and SMS permissions. | Medium. Best as a later, separate phase |
| **On-device call assistant** (screening, captions, scam cues) | Pixel and Samsung only today. Users accept it *if local and opt-in*. It is blocked by call-audio capture, the same wall as recording, so we can't do it without system privileges. Worth monitoring, not building. | Low for us, high demand |
| **Senior / simple mode** | Seniors are served by launchers (BIG Phone, BaldPhone), not dialers. A big-button tap-to-answer mode inside a real dialer is almost unserved. | High, and cheap to build |
| **GrapheneOS / CalyxOS / /e/OS audience** | They are the most vocal about the AOSP dialer's dated UI and would adopt a well-built open-source replacement. They care about working with Contact Scopes and DAVx⁵. | High |
| **Work profile / two phonebooks** | Android work profiles already separate contacts. The vault could act as a lightweight "second phonebook" for users without MDM. | Medium |

## 7. Horizon: worth knowing about

- **Android developer verification (from 30 Sep 2026, going global in 2027).** Certified devices in Brazil, Indonesia, Singapore and Thailand will block apps from unregistered developers, except through an "advanced flow" with a 24-hour wait. ADB still works. F-Droid is openly opposed. **This will affect side-loading our APK in those regions.** Decide whether to register as a developer before a public release.
- **Google Play policy.** Call-log and SMS permissions need the default-handler exception and a Permissions Declaration Form. That is feasible for a real dialer but has review risk. F-Droid and IzzyOnDroid are the natural first channels.
- **RCS / Jetpack Telecom VoIP visibility (2026 alpha).** A future path to show Signal or WhatsApp calls in a unified call log.
- **Truecaller legal pressure.** A Nigerian court reportedly ruled in Sep 2026 that one user's consent does not cover the contacts in their phonebook. This supports "we never upload your contacts" as a positioning line.
- **The recording announcement design.** Google is testing a beep instead of a voice announcement. This only matters if we ever get system-level recording, for example as a LineageOS or GrapheneOS contribution.

## 8. Red team: why this plan could be wrong

- **"Privacy users will stay on the AOSP/Fossify apps."** Maybe. But the evidence shows them putting up with Fossify despite deal-breaker bugs, and side-loading Google's apps with network access cut off just to get the design. Polish plus reliability is exactly what they lack. **This holds.**
- **"Without spam lookup, spam protection is weak."** This is true for unknown scam numbers in markets without STIR/SHAKEN. Offline rules catch neighbour spoofing and user-known patterns only. Mitigation: be honest in the UI, support importable blocklists, and keep a possible *separate, optional* companion for lookups (k-anonymity hashed prefix) out of the core APK so the core keeps no INTERNET permission.
- **"One app is a bigger target and more complex than three."** True. An in-call crash is far worse than a contacts crash. Mitigation: keep the in-call engine isolated in its own module with the fewest dependencies and the most tests, and put all enrichment behind a timeout.
- **"The top-requested feature (recording) is impossible."** It is the #1 ask and we can't ship it without root. Some users will leave over it. We say so openly rather than ship a broken speakerphone hack.
- **OEM fragmentation.** Every open-source dialer's worst bugs are OEM-specific: Xiaomi lock screen, Samsung sleeping apps. We can't test on those devices here. A beta period on real devices is required before any "stable" claim.

## Sources and confidence

Primary sources:

- AOSP source (`roles.xml`, `InCallService.java`, `TelecomServiceImpl.java`, `InCallController.java`, framework `AndroidManifest.xml`)
- developer.android.com behaviour-change pages for Android 14, 15 and 16
- source.android.com FSI limits
- Play Data-safety pages
- GitHub issue trackers (FossifyOrg/Phone, FossifyOrg/Contacts, chooloo/koler)
- GrapheneOS forum
- 9to5Google, Android Police, Android Authority, gHacks, TechCrunch, Reuters

Selected links:

- Dialer role definition: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml
- InCallService: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telecomm/java/android/telecom/InCallService.java
- FSI limits: https://source.android.com/docs/core/permissions/fsi-limits
- Android 16 changes: https://developer.android.com/about/versions/16/behavior-changes-16
- Google Phone redesign: https://9to5google.com/2025/08/21/google-phone-material-3-expressive-redesign/ · poll: https://www.androidauthority.com/i-love-new-google-phone-app-redesign-survey-results-3592446/
- Simple Mobile Tools sale: https://www.ghacks.net/2023/12/05/warning-simple-mobile-tools-sold-to-controversial-zipoapps-publisher/
- Fossify Phone issues: https://github.com/FossifyOrg/Phone/issues (e.g. #17, #83, #250, #272, #676, #825)
- Fossify Contacts #85: https://github.com/FossifyOrg/Contacts/issues/85
- GrapheneOS threads: https://discuss.grapheneos.org/d/22756 · https://discuss.grapheneos.org/d/8283
- Recording ban on Play: https://9to5google.com/2022/04/21/google-will-block-all-third-party-call-recording-apps-on-play-store-from-may-11/
- Play call-log/SMS policy: https://support.google.com/googleplay/android-developer/answer/10208820
- Developer verification: https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html
- Reproducible builds: https://f-droid.org/docs/Reproducible_Builds/
- Samsung + Hiya: https://www.businesswire.com/news/home/20250402239592/en/Hiya-and-Samsung-Extend-Strategic-Partnership-Through-2028
- Truecaller Nigeria ruling: https://techcabal.com/2026/09/23/nigerian-court-says-truecallers-consent-doesnt-cover-contacts/

**Confidence:**

| Area | Confidence | Reasoning |
|---|---|---|
| Platform and technical facts | HIGH | Read directly from AOSP source |
| Feature demand ranking | MEDIUM–HIGH | Reddit and Play reviews couldn't be fetched directly. GitHub reactions and forums skew towards privacy-minded power users, which is our target audience anyway. |
| Store ratings and installs | MEDIUM | Point-in-time snapshots |
| Very recent news (Sep 2026 court ruling, developer-verification dates) | MEDIUM | Single sources; re-check before relying on them publicly |
