# Parley 4.1 vs. the top phone and contacts apps (round 6)

*29 Sep 2026. A market round rather than a code round. Rounds 1–5 compared Parley with open-source apps (Fossify, Koler, NovaDial, Call Blocker, SpamBlocker, Bondwidth…). This round compares it with the apps most people actually use: **Phone by Google / Google Contacts**, **Samsung Phone / Contacts (One UI 8–8.5)**, **iOS 26 Phone / Contacts**, **Truecaller** and **Hiya**, with **Fossify** as the privacy baseline and **Dex / Clay / Monica / Cardhop** for relationship features. Every "Parley lacks X" claim was checked against the 4.1 code (`app`, `telecom`, `core/*`) with grep; file names are given where it matters. Sources are in §8.*

## 0. Summary

- **Parley's core is already feature-complete.** On contacts, history, blocking, privacy, relationships and data safety it matches or beats every app in the set. The one place it clearly loses is the **"AI answers the phone for you" layer**: Call Screen, Hold for Me, Direct My Call, Call Notes, Take a Message, Live Translate, Bixby text call, iOS Call Screening and Hold Assist. Android gives none of those to a third-party dialer, because only system apps can get call audio. That gap is structural, and Parley should say so plainly rather than chase it.
- **What Parley lacks that it *can* build** is mostly small telephony plumbing: remind me later / a list of calls to make, Wi-Fi calling and HD indicators, call subject, auto-answer, RTT, carrier video calls, assisted dialling abroad, per-contact vibration, an "Unknown" filter in Recents, pronouns, and contacts made from text or cards.
- **Most of the remaining weakness is quality, not features.** None of the performance targets have been measured on a device. The APK is 14 MB against a 12 MB budget. Localisation is paused, so new screens are English-only. There are more than 100 features, and people can't find them.
- **Parley's big opportunity is an offline answer to "who is this, and can I trust them?"** It fits the privacy promise and none of the big apps does it without a cloud. The ideas are: *number memory* across deleted contacts, snapshots and notes; a *personal reputation* score learned from your own calls; *Verify & call back* on the saved number; a *family safe word*; and *bring in my helper*. §6 has 22 ideas in all.

---

## 1. Scorecard vs. the top apps

1 = weak or missing, 5 = best in class. The scores are judgement calls, based on the features listed in each app's 2025–26 release notes and reviews (§8) and on Parley's code.

| Area | **Parley 4.1** | Google Phone/Contacts | Samsung Phone/Contacts | iOS 26 Phone/Contacts | Truecaller | Fossify Phone/Contacts |
|---|---|---|---|---|---|---|
| Incoming & in-call | **4**: 4.1 redesign, caller card, notes, PiP, call time, "Why did it ring" | **5**: Call Screen, Hold for Me, Direct My Call, Call Notes, Calling Cards, scam detection | **5**: Bixby text call, Live Translate, call backgrounds, AI screening (8.5) | **5**: Call Screening, Hold Assist, Live Translation, posters | 4: AI assistant (Premium), big caller ID | 2.5: basic grid, no notes |
| Dialer / keypad | **5**: T9 in 11 scripts plus CJK, speed dial, USSD dialog, call pill per SIM | 3.5: T9, keypad now its own tab | 4: T9, good dual-SIM | 3: keypad search since iOS 18, single SIM focus | 4 | 3.5 |
| Recents / history | **5**: encrypted archive beyond Android's ~500 rows, Call insights, "why" trace, voicemail inbox, exports | 3.5: merged "Home", grouping backlash | 4 | 4: unified or classic view, Unknown Callers list, longer history | 3.5 | 2.5: history refresh bugs fixed only in 2025–26 |
| Contacts list | **4.5**: labels, multi-select, fast scroll, second line, health check | 4: Expressive containers, Contacts hidden in the Phone app | 4 | 3.5 | 3 | 3.5 |
| Contact page | **4.5**: foldable sections, timeline, Reach via apps, map links | 4: "Recent activity" card, Your info card | 4 | 4: Contact Posters | 3 | 3 |
| Editor | **4.5**: lossless, "Changed elsewhere", year-optional dates, 70 relations | 4 | 4 | 4 | 2.5 | 3 |
| Blocking & spam | **4**: offline rules, precedence, trace, lists, templates. **But** no crowd-sourced database, so new spam campaigns get through | 4.5: carrier and Google spam, scam detection | 4: Smart Call (Hiya) | 4: carrier spam, Silence Unknown | **5**: detection, family protection | 2 |
| Privacy & security | **5**: no INTERNET (build-enforced), vault, app lock, sealed notes, no accounts | 2.5 | 3 | 4 | 1: address-book upload | 4 |
| Backup / sync / migration | **4**: encrypted, verified, snapshots, serverless sync. Manual folder setup is friction | 4.5: automatic, but call-log restore is flaky | 4.5: Smart Switch | **5**: iCloud | 3 | 2.5 |
| Messaging integration | **4.5**: message or call any number on about 20 apps without saving it | 4: RCS; VoIP calling accounts (16.1) | 4 | 4.5: iMessage/FaceTime | 3.5 | 2 |
| Relationship features | **5**: Circle, rhythms, promises, good time to call, People card | 3: Recent activity, highlights | 2 | 2.5 | 1 | 1 |
| Accessibility | **3.5**: TalkBack labels, simple mode, spoken caller name, 48dp targets. No RTT, no captions | **5**: RTT, Live Caption, Call Screen | 4.5: Bixby text call, Live caption | **5**: RTT/TTY, Live Captions | 2.5 | 3 |
| Polish / performance | **3.5**: expressive UI, but unmeasured cold start and ring-to-UI, 14 MB APK, a very dense feature set | 4.5 | 4 | 4.5 | 3: ads, heavy | 4: light |
| **Overall (unweighted)** | **≈ 4.4** | ≈ 4.0 | ≈ 3.9 | ≈ 4.1 | ≈ 3.1 | ≈ 2.8 |

**What the table says:** Parley wins on breadth, control and trust. It loses on things that need call audio or a cloud, and on accessibility for Deaf and hard-of-hearing users.

---

## 2. Where Parley is ahead (with evidence)

| # | Advantage | Evidence in Parley | The competitor weakness it answers |
|---|---|---|---|
| A1 | **Nothing leaves the phone, and that's enforced** | `checkReleasePermissions` fails the build on INTERNET, mic, camera, location, SMS or storage; `ACCESS_NETWORK_STATE` is stripped in the manifest | Truecaller's address-book upload; Google and Samsung AI features that need an account and region |
| A2 | **Call history that outlives Android's cap** | `CallHistory` encrypted archive, "keep forever" per person, 30-day undo | Android keeps about 500 call-log rows ([techmesto](https://www.techmesto.com/cla-unlimited-call-log-android/)); Smart Switch call-log restores that silently fail ([Android Central forum](https://forums.androidcentral.com/threads/call-log-refuses-to-restore-despite-restored-confirmation.1057652/)) |
| A3 | **You can't really lose a contact** | History & undo (30 d), daily snapshots (6 months), verified encrypted backups, "Changed elsewhere" merge | Google Contacts' "Undo changes" is web-only; forum threads about "contacts disappeared" are endless ([XDA](https://www.xda-developers.com/google-contacts-disappear-android-reversible/), [Google community](https://support.google.com/accounts/thread/168701833/contacts-disappeared-cannot-restore?hl=en)) |
| A4 | **Your layout stays yours** | Customisable navigation bar, separate or combined layouts (both off by default), the "layout promise" | Google's 2025 redesign merged Favourites and Recents, dropped the Contacts tab and offered no way back ([Android Police](https://www.androidpolice.com/google-phones-redesign-ditches-multiple-tabs-in-the-name-of-material-3-expressive/), [Business Standard](https://www.business-standard.com/technology/tech-news/google-phone-app-gets-makeover-not-everyone-is-happy-what-changed-material-3-expressive-design-125082500299_1.html)). iOS 26 had to keep "Classic" beside "Unified" |
| A5 | **Explainable screening** | Fixed precedence, a stored "why" trace, "Why didn't it ring?", test a call, replay last week | Every big app's spam decisions are black boxes; iOS screening misses some spam ([MacRumors](https://www.macrumors.com/guide/ios-26-phone-app/)) |
| A6 | **Correct caller matching** | One `PhoneIdentity` key (E.164) | One UI 8/8.5 matches callers on the last 5–8 digits and shows the wrong contact ([Samsung Members](https://r2.community.samsung.com/t5/Galaxy-S/One-UI-8-5-Inherents-Bugs-from-One-UI-8-0/m-p/22080004)) |
| A7 | **Message any number without saving it** | Message or call on… with about 20 apps, opened directly in the app | Google and Samsung need a saved contact or a browser hop |
| A8 | **A personal CRM that doesn't upload your social graph** | Circle, natural rhythm, promises, good time to call, People card, Markdown export | Dex and Clay need LinkedIn, Gmail or cloud sync; Monica needs a server |
| A9 | **T9 for the world** | Cyrillic, Greek, Hebrew, Arabic, CJK syllables | The Pixel T9 "not showing all contacts" thread ([Pixel community](https://support.google.com/pixelphone/thread/88363850/phone-app-t9-dialer-not-showing-all-contacts?hl=en)) |
| A10 | **Private contacts that still ring by name** | Vault plus the opt-in directory provider | Nobody else offers this except GrapheneOS Contact Scopes, which works per app and is read-only |
| A11 | **Call time, allowances and plan minutes** | `CallLimits`, `CallUsageLedger`, supervised mode | Not in any of the top apps |
| A12 | **Fossify's top requests are all done** | Missed-call notification, different ringtone for unknown callers, choosing a number, more fields on the call screen, merging duplicates, choosing what to share, QR share, relations, default storage | Fossify Phone #83, #31, #102, #477 and Contacts #37, #82, #13, #90, #9 are still open ([Phone issues](https://github.com/FossifyOrg/Phone/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc), [Contacts issues](https://github.com/FossifyOrg/Contacts/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc)) |

---

## 3. What Parley lacks (verified against the code), ranked by user value

"Verified" means a grep of `app/src/main`, `telecom/src/main` and `core/*/src/main` found no implementation (search terms in brackets).

| Rank | Missing | Who has it | Verified | Can Parley build it? | Effort |
|---|---|---|---|---|---|
| L1 | **"Remind me" on decline or missed call, and a list of calls to make** | iOS (Remind Me on decline), Samsung (call reminders) | No reminder action in `MissedCallNotifier`, `IncomingControls` or the post-call card; only the re-alert (`remind.*later`, `callLater`) | Yes. Inexact alarm, no new permission | S–M |
| L2 | **Call quality facts**: Wi-Fi calling, HD voice, why a call dropped, reconnect | Samsung and Pixel show VoWiFi/HD icons | No `PROPERTY_WIFI` or `PROPERTY_HIGH_DEF_AUDIO` usage | Yes. `Call.Details` properties and `DisconnectCause` | S |
| L3 | **RTT (real-time text)** for Deaf and hard-of-hearing users | Google Phone, iOS | No `RttCall` | Yes where the carrier supports it. `Call.RttCall` is public to an InCallService ([AOSP](https://source.android.com/docs/core/connect/rtt)) | M–L |
| L4 | **Auto-answer** (headset, simple mode, a chosen person after N seconds) | Samsung (accessibility), many dialers | None (`autoAnswer`) | Yes. `ANSWER_PHONE_CALLS` is already held | S |
| L5 | **Making a contact from text or a business card** (Cardhop-style "paste anything") | Cardhop, Samsung (card scan), Google (Lens) | None (`TextClassifier`, `ocr`). `BulkAdd` only extracts numbers | Text: yes (system `TextClassifier` plus own parser). Card photos: yes through the photo picker, but OCR adds size (§6) | M |
| L6 | **Assisted dialling abroad** (fix local-format numbers while roaming, warn about the roaming SIM) | Google Phone | None (`assistedDial`, `roaming` only in key code) | Yes. `READ_PHONE_STATE` is already held | M |
| L7 | **"Unknown" and "Contacts" filters in Recents** | iOS 26 Unknown Callers list | Chips are All / Missed / Incoming / Outgoing / Blocked / Voicemail (`RecentsTab.kt:420-425`) | Yes | S |
| L8 | **Per-contact or per-label vibration pattern** | Samsung, iOS (custom vibrations) | Ringtones per contact and label only | Yes. `VibrationEffect`, `VIBRATE` already held | S |
| L9 | **Carrier video calls (ViLTE)** | Google, Samsung | Always `answer(VideoProfile.STATE_AUDIO_ONLY)` (`CallManager.kt:608`) | Yes, but it needs a camera preview surface. Parley has no CAMERA permission, so it can only accept audio-only or hand off. Flag it: honest audio-only, with "Answer as audio" text | L, or skip |
| L10 | **Call subject / call reason** (RCS Call Composer, `EXTRA_CALL_SUBJECT`) | Google (Call Composer), Samsung (call with message) | None (`CALL_SUBJECT`) | Show on incoming: yes. Send: only on `PhoneAccount.CAPABILITY_CALL_SUBJECT` carriers | S (show), M (send) |
| L11 | **Pronouns** field | iOS, Google Contacts | None (`pronoun`) | Yes. vCard `PRONOUNS` (RFC 9554); stored as a Parley custom row | S |
| L12 | **Emergency info / ICE** entry point | iOS Medical ID, Android Emergency info | None | Link to Android's emergency info from My card and the lock screen emergency button; an ICE label | S |
| L13 | **NFC tap-to-exchange** (NameDrop-like) | iOS NameDrop, Samsung Quick Share | None (`Nfc`) | Yes, but it adds `android.permission.NFC` (a normal install-time permission, not on the forbidden list; update the allow-list) | M |
| L14 | **Third-party VoIP calls in Recents** | Google Phone "Calling accounts" (Android 16.1, Jetpack Telecom 1.1) | None | Investigate. Dialers show them through package allowlists ([9to5Google](https://9to5google.com/2026/05/14/google-phone-call-logs/)) | M (research) |
| L15 | **Android Auto phone app, Wear OS app** | Google, Samsung | None (`androidx.car`, `wear`) | Auto: needs the Play allowlist ([Android Authority](https://www.androidauthority.com/android-auto-dialer-app-support-3488295/)). Wear: the CallStyle notification already mirrors, and a full app would need Play Services | L, gated |
| L16 | **Translations of the newest screens** | Everyone | 8 locales exist, but work is paused (AUDIT §6) | Yes | M (ongoing) |
| — | **AI screening, Hold Assist, Direct My Call, transcription, live translation, call recording** | Google, Samsung, iOS, Truecaller, Hiya | By design | **No.** Android gives call audio only to system apps, and Parley forbids the microphone. §6 has offline substitutes (I1, I6, I12) and one opt-in companion (I20) | — |

---

## 4. Polish and improve what exists

| # | Where | Now | Improve |
|---|---|---|---|
| P1 | **Incoming screen**, under the status pill | Shows *why it's silent* (`CallerHeader.kt:259`) | Also show **why it rang through** from the trace: "Rang through: called twice in 3 min", "Expecting a call", "Allowed for 24 h". This builds trust in the blocking engine |
| P2 | **Incoming ⋮ and the decline slider** | Reply, Silence, Block & decline | Add **Decline & remind** (1 h / tonight / when I leave… no location, so fixed times only) and **Decline & message on…**. iOS has had this for years |
| P3 | **Missed-call notification** | Call back, Message or call on…, Block | Add **Remind me** (1 h, this evening). It lands in the new "To call" list (I9) |
| P4 | **Recents chips** | 6 chips | Add **Unknown** and **Contacts**, and save the last chip per launch. Show "3 unknown callers today" as a quiet header, like iOS's filter |
| P5 | **Call ended screen** | Post-call card for unknown numbers; memory prompt opt-in | Show quality facts ("Dropped · Wi-Fi calling") with a big **Call again** when `DisconnectCause` is ERROR or lost signal |
| P6 | **In-call grid** | 6 fixed buttons | Show small **HD** and **Wi-Fi** tags next to the timer; long-press Speaker opens the route list directly |
| P7 | **Onboarding** | 3 steps: promise, default app, permissions | Add a 4th optional step, **"Coming from…"**: Google Contacts export, iPhone `.vcf`, call log CSV, a Call Blocker or YACB list, each opening the existing importer. Switchers are the growth funnel |
| P8 | **Discoverability** (Tools has 12 destinations; 100+ features) | Tips, coach marks, searchable settings | A **"What Parley can do"** page grouped by job to be done ("Stop spam", "Never lose a contact", "Stay in touch"), each row opening the feature. One card per release in What's new, never a forced screen |
| P9 | **Blocking & screening** | Presets exist ("Situations, not mechanisms") | Put the preset picker at the **top** of the screen with the current choice named ("You're on: Known callers ring"); move rule lists under "Advanced". Show a weekly count ("12 calls silenced, 0 contacts affected") |
| P10 | **Contact page** | Sections, timeline, reach apps | "At a glance" line under the name: last talked, next date, open promise. Google's *Recent activity* and *highlights* do this; Parley has the data but spreads it across sections |
| P11 | **Editor** | "Add more info" sheet | "Paste details" chip at the top of a *new* contact: parses a pasted signature (L5/I15) into fields with a preview |
| P12 | **Keypad** | Call pill, docked keypad | When a typed number matches nothing, show "Not in contacts · Leeds · you called this 2× in 2024 (archive)", which reuses number memory (I1) |
| P13 | **Backup** | Folder picker, Syncthing/Nextcloud guidance | A **setup checker**: "Your backup folder is on this phone only; that won't survive a lost phone" (checks whether the tree URI's authority is local external storage or a cloud/SAF provider) |
| P14 | **Performance** | Macrobenchmarks written, never run | Run them on 2 mid-range devices, publish in PERFORMANCE_BENCHMARKS.md, gate ring-to-UI < 300 ms in CI with a device farm or manual release step |
| P15 | **APK size** | 14 MB, budget 12 MB | Trim the libphonenumber geocoder to the languages you ship (`GeoLanguages`) and ship ZXing core only |
| P16 | **Accessibility** | Labels, 48dp, TalkBack actions | Test at 200 % font and display size, Switch Access and Voice Access ("tap Answer"), and check contrast with 10 dynamic-colour wallpapers. Add live-region announcements for "Call connected" and "On hold" |
| P17 | **Localisation** | Paused | Before any store push, restore key parity for the 8 locales on the call screen, notifications and onboarding (the most-seen strings) |
| P18 | **Wording** | "Private contacts", "Circle", "Labels", "Favourites", "Temporary" | Keep the glossary; add a one-line explainer at each concept's first appearance (the audit's UX coherence is still 7.5) |

---

## 5. Top user pain points and recurring requests

"How common" is judged from how many sources and threads raise it, and how loudly.

| # | Pain point / request | How common | Sources | Parley today | Could do |
|---|---|---|---|---|---|
| U1 | **Spam and scam calls** (impostors, AI voices, "1 in 4 calls reviewed contain AI audio") | Very common, #1 reason people install Truecaller or Hiya | [Hiya](https://blog.hiya.com/1-in-4-calls-reviewed-by-hiya-contain-ai-generated-audio), [TechCrunch on Truecaller family](https://techcrunch.com/2026/03/12/truecallers-now-lets-you-hang-up-on-scammers-on-behalf-of-your-family/), [ConsumerAffairs](https://www.consumeraffairs.com/news/call-screening-apps-promise-relief-from-scam-calls-032526.html) | Rules, lists, STIR/SHAKEN, wangiri warnings | I1–I5: number memory, personal reputation, Verify & call back, safe word, bring in my helper |
| U2 | **Screening that blocks real callers** (doctor, pharmacy, courier) | Common complaint about automatic screening | [ConsumerAffairs](https://www.consumeraffairs.com/news/call-screening-apps-promise-relief-from-scam-calls-032526.html), [MacRumors](https://www.macrumors.com/guide/ios-26-phone-app/) | Expecting a call, repeat callers, allow 24 h, "why" trace | P1 (show why it rang), I7 (expected-call hints from your notes) |
| U3 | **Redesigns forced on users** (Google 2025: merged tabs, no Contacts tab, "blocky, oversized") | Very loud; 27 % disliked it in the Android Authority poll | [Android Authority](https://www.androidauthority.com/i-love-new-google-phone-app-redesign-survey-results-3592446/), [Android Police](https://www.androidpolice.com/google-phones-redesign-ditches-multiple-tabs-in-the-name-of-material-3-expressive/) | Layout promise, optional combined layouts | Keep it. Add "Classic" and "Unified" presets like iOS 26 in one tap |
| U4 | **Contacts disappearing or duplicating after sync** | Very common, perennial | [XDA](https://www.xda-developers.com/google-contacts-disappear-android-reversible/), [Android Central](https://forums.androidcentral.com/threads/google-contact-missing-repeatedly-even-when-manually-synched.1070880/), Fossify Contacts #9 and #186 | Snapshots, undo, health check, account diagnostics | P13; I13 "sync watchdog" |
| U5 | **Call history lost** on a phone change or when trimmed at about 500 rows | Common | [Android Central](https://forums.androidcentral.com/threads/call-log-refuses-to-restore-despite-restored-confirmation.1057652/), [Google community](https://support.google.com/android/thread/251031031/retrieve-call-history-from-old-phone-backup?hl=en), [techmesto](https://www.techmesto.com/cla-unlimited-call-log-android/) | Archive, Move to a new phone, CSV import | Make it a headline: "Your call history survives phone changes" |
| U6 | **Call recording** | #1 Fossify Phone request (#17, 55+ 👍); OnePlus users root phones for it | [Fossify #17](https://github.com/FossifyOrg/Phone/issues/17), [XDA](https://xdaforums.com/t/magisk-module-for-oneplus-dialer-messages-optional-and-enable-call-recorder-options-for-the-oxygenos-16-15-global-eu-in-and-na-root-required.4727965/) | Not possible, stated honestly | In-call notes, promises, I6 menu memory; keep the honest note |
| U7 | **Dropped calls and bad audio after updates** (One UI 8) | Common on Samsung | [Android Central forum](https://forums.androidcentral.com/threads/dropped-calls-insane-amount-since-one-ui-8-0-android-16-software-update-in-2025.1088872/) | Failure banner, Retry | L2, P5, I8 (call quality diary) |
| U8 | **Waiting on hold and phone menus** | Common; it's why Hold for Me, Direct My Call, Hold Assist and Call for Me exist | [Android Police](https://www.androidpolice.com/how-to-use-hold-for-me-direct-my-call-google-pixel-10/), [9to5Google](https://9to5google.com/2026/09/24/pixel-11-call-for-me/) | Pause/wait characters, keypad | I6 (menu memory), I10 (hold mode) |
| U9 | **Car and Bluetooth**: contacts not in the car, third-party dialers missing in Android Auto, audio routing | Common | [Android Authority](https://www.androidauthority.com/android-auto-dialer-app-support-3488295/), [Google AA community](https://support.google.com/androidauto/thread/385783962/inability-to-route-audio-to-a-third-party-bluetooth-device-while-android-auto-is-active?hl=en) | Adaptive audio button, BT routes | I11 (drive profile); an Auto app for the Play release |
| U10 | **Missed-call follow-up** (Fossify #83, #4 auto-SMS) | Common | [Fossify issues](https://github.com/FossifyOrg/Phone/issues) | Rich notification, re-alert | L1, P3, I9 (To-call list). Auto-SMS stays out (needs SEND_SMS) |
| U11 | **Search is tab-specific** (Fossify #150) | Common | [Fossify #150](https://github.com/FossifyOrg/Phone/issues) | Header search per tab; `BroadSearch` covers notes and addresses | One search across contacts, calls, notes and handles from any tab, with sections |
| U12 | **Dual-SIM friction** | Common in India, EU, LATAM | Rounds 1–5 | Call pill per SIM, per-contact and per-label SIM | L6 (roaming aware), I8 (per-SIM quality) |
| U13 | **Google account dependency; degoogled phones lack a good dialer** | Very common in r/degoogle and r/fossdroid | [Unstore](https://unstore.io/discover/best-google-contacts-alternatives/), [AlternativeTo](https://alternativeto.net/software/phone) | Core promise | P7 onboarding "Coming from…"; F-Droid listing polish |
| U14 | **Accessibility**: hearing loss, older relatives, one-handed use | Common, underserved outside Google and Apple | [Google RTT help](https://support.google.com/accessibility/android/answer/9042284?hl=en) | Simple mode, TTS caller name, bottom-half controls | L3 RTT, L4 auto-answer, I16 haptic caller ID, I17 sonic caller ID |
| U15 | **Contact fields and fidelity** (NextCloud attributes, structured addresses, display name) | Fossify Contacts #85, #30, #129 | [Fossify Contacts issues](https://github.com/FossifyOrg/Contacts/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc) | Lossless vCard, other fields | L11 pronouns; check #129 (an editable display name that isn't auto-generated) |

---

## 6. Ideas beyond the obvious (22)

Constraint: no INTERNET, microphone, camera or location. Items that need anything else are flagged ⚑.

### "Who is this, and can I trust them?" (offline trust layer)

| # | Idea | Value | Effort | UI placement |
|---|---|---|---|---|
| I1 | **Number memory.** When an unknown number calls, Parley searches everything it knows *offline*: deleted contacts (History & undo), daily snapshots, archived calls, notes, messaged numbers, QR scans and bulk-add batches. "You deleted *Plumber Mike* in March with this number" or "In your note on Ana: 'Dr Lee's office'". It uses a keyed-hash index built in the maintenance worker, so no plaintext list is kept | Answers "who is this?" better than a cloud database for the numbers that matter to *you* | M | Incoming caller card; the keypad no-match line (P12); number history |
| I2 | **Personal reputation.** A local score learned from *your* history with a number or prefix: rings under 5 s, never answered, never left voicemail, calls at odd hours, many different numbers in one range. It shows as "Looks like a sales line (your calls)", never as a block unless a rule says so | Catches new spam campaigns that the lists haven't seen yet, with nothing uploaded | M | Incoming tag; post-call card "Block this range?" |
| I3 | **Verify & call back.** On any call, More › "Check it's really them" ends the call and dials the *saved* number for that person or organisation. With an organisation contact ("My bank · official"), an unknown caller claiming to be the bank gets one tap: "Hang up and call the bank's saved number" | The one defence that always works against spoofing and impostors | S | In-call More sheet; post-call card |
| I4 | **Family safe word.** Store a private question or word per label ("Family"). When someone calls from an unknown number and the call lasts more than 20 s, a discreet card appears: "Claims to be family? Ask: *what's our word?*". The word is sealed and never shown on the lock screen | Beats AI voice-clone "grandparent" scams without any AI | S | Label page; in-call card; simple mode |
| I5 | **Bring in my helper.** During a suspicious call, one button adds a trusted person (Add call, then Merge), so a relative can hear and help. It's Truecaller's "family hangs up for you", done with the phone network instead of a server | Protects older users | M | In-call More; simple mode big button |

### Calls that work better

| # | Idea | Value | Effort | UI placement |
|---|---|---|---|---|
| I6 | **Menu memory (an offline "Direct My Call").** Parley sends every DTMF digit itself, so it can remember the digits and timing per number: "Last time: 2 › 1 › 4". Next call, one tap replays the path, or saves it as a **menu shortcut** ("Bank › lost card") that dials `number,,2,1,4` | Skips phone trees; nobody offline does this | M | In-call keypad top row; contact page "Shortcuts" |
| I7 | **Expected-call hints.** Notes and promises with a date ("dentist will call Tue") or a delivery QR scan turn on "Expecting a call" for that window automatically, after asking once | Fewer blocked legitimate callers (U2) | S | Note editor chip; the Expecting a call tile |
| I8 | **Call quality diary.** Per call: SIM, Wi-Fi calling, HD, disconnect cause, duration. It shows patterns ("Calls with Mum drop on SIM 2 at home: try Wi-Fi calling") and a big **Call again** after a drop | Turns Samsung-style dropped-call misery into advice | M | Call-ended screen; Call insights "Quality" card |
| I9 | **"To call" list.** One list of calls you owe, fed by Remind me (L1), unreturned missed calls, promises and follow-ups, each with an optional "after 6 pm their time" window. It's one quiet notification at the chosen time, never a badge | An inbox for calls, the most requested follow-up feature | M | Top of Recents, a collapsible strip; Circle; widget |
| I10 | **Hold mode.** A manual "I'm on hold" button: speaker on, screen dims, PiP with a hold timer, the app free to use, and a vibration reminder at 15 and 30 min. It's honest that it can't hear the agent | 80 % of Hold Assist's comfort with 0 % of the audio access | S | In-call More; PiP action |
| I11 | **Drive profile.** When a Bluetooth device you mark as your car connects (`BLUETOOTH_CONNECT` is already held): announce the caller, auto-answer favourites after N s, silence unknown callers, and show "Driving" reply templates for when you stop | Safer driving without Android Auto | M | Settings › Calls › Drive profile; the audio button |
| I12 | **Call-subject bridge.** Show `EXTRA_CALL_SUBJECT` / RCS Call Composer subjects when the carrier sends them. When calling, if the SIM supports it, "Add a reason"; otherwise **Text first** opens SMS with "Calling you about …" for you to send | Callers who say why get answered (Google's "call reason" idea, without a server) | S–M | Keypad call pill long-press; contact page Call long-press |

### Contacts that fill themselves in, with no cloud

| # | Idea | Value | Effort | UI placement |
|---|---|---|---|---|
| I13 | **Sync watchdog.** The daily snapshot diff notices "142 contacts vanished from Google since yesterday" or "Samsung account sync is off" and says so once, with **Restore from snapshot** | Stops the #4 pain point before it hurts | S | Notification; Contact health check |
| I14 | **Signed card updates.** My card carries a stable ID and an Ed25519 signature (the signing code already exists). When a newer card from the same key arrives by QR, file, share or sync folder, the contact shows "Ana updated her card: new number, apply?". A "Changed my number" wizard then sends your new card to the people you shared it with, one prefilled chat at a time (as "Introduce myself" does) | The offline answer to Google's *Your info* and iOS Contact Posters: contacts that stay current | M | My card; contact page banner |
| I15 | **Paste anything → contact.** An email signature, a WhatsApp business profile, a web "Contact us" block or an event badge text is parsed by the system `TextClassifier` (on-device) plus Parley's rules into name, organisation, title, phones (country-aware), email, address and a map link, with a field-by-field preview | Cardhop's best trick, offline | M | Contacts: new-contact "Paste details"; the "Save all…" share target |
| I15b | ⚑ **Business-card photos** through the photo picker or camera app (no camera permission, as QR does), OCR on the device | Same, from paper | L. ⚑ Needs a bundled OCR model (+4–10 MB); ML Kit's thin model needs Play Services. Better as an optional **Parley Scan** module |
| I16 | **Haptic caller ID.** A vibration pattern per person or label, generated (each contact gets a unique 1 s rhythm) or chosen (heartbeat, Morse initial) | Know who's calling with the phone in your pocket or silent; useful for Deaf users too | S | Contact page › Settings section; label page |
| I17 | **Sonic caller ID.** Generate a short, unique, pleasant ringtone from a contact's name (a deterministic melody, rendered offline to an OGG in app storage) | Blind users and busy kitchens know who's calling without looking | M | Contact page ringtone picker "Make one for Ana" |
| I18 | **NFC tap-to-swap.** Two Parley phones back to back exchange encrypted cards (reuses the QR handshake and "Met at…"). Writing your card to a blank NFC tag or sticker works as an offline business card | NameDrop for Android, without Google | M. ⚑ Adds `android.permission.NFC` (normal; update the allow-list) | My card › "Tap to swap" |

### Privacy and household

| # | Idea | Value | Effort | UI placement |
|---|---|---|---|---|
| I19 | **Shared family phonebook.** One label (for example "Family" or "Doctors & school") syncs between several people's phones through the existing encrypted folder sync, with its own passphrase and per-member edit history | Couples and families keep one list; nobody else does this serverless | L | Label page › "Share this label" |
| I20 | ⚑ **"Parley Listen" companion (opt-in).** A separate app, like Parley Lists, with microphone but no INTERNET, no contacts and no call log. It transcribes **voicemail files** Parley hands it (content URI, one at a time) with Android's on-device speech recogniser, and returns the text | Voicemail transcription for carriers that don't provide it, with Parley itself staying mic-free | M. ⚑ The microphone lives in the companion only; the same signature-permission pattern as Parley Lists |
| I21 | **Duress unlock.** A second app-lock PIN opens Parley with private contacts, the private call history and Circle notes hidden, and discreet mode forced | For people at risk (coercive partners, border checks) | M | Settings › Privacy › App lock |
| I22 | **Sharing receipts.** "Who has my number": a private ledger of everyone you gave My card to (QR swaps, Send my details, Introduce myself). It feeds I14's number-change wizard and lets you see what you shared with whom | Control over your own data, which privacy users ask for | S | My card › "Shared with" |

---

## 7. Roadmap v4.2 → v5.0

Principles: quick wins first; each phase ends with a release plus device checks (TESTING.md); work packages (WP) touch separate modules and files so parallel builders don't collide.

### v4.2 "Follow-through" (quick wins, about 2 weeks)

| WP | Items | Main files / modules |
|---|---|---|
| WP-1 Calls follow-up | L1 Remind me (decline, missed, post-call), P2, P3, I9 To-call list v1 (reminders and unreturned calls) | `app/calls/MissedCallNotifier`, `telecom/ui/IncomingControls`, new `core/common/calls/ToCall.kt`, `RecentsTab` strip |
| WP-2 Call facts | L2 Wi-Fi/HD tags, P5 Call again after a drop, P6, L10 show call subject | `telecom/CallSession`, `CallerHeader`, `InCallScreen`, `RingFacts` |
| WP-3 Small wins | L4 auto-answer (simple mode, headset, a chosen person), L8 / I16 haptic caller ID, L7 / P4 Recents chips, L11 pronouns, L12 ICE link | `CallRinger`, `SimpleMode`, `RecentsTab`, `VCardMapper`, `EditorForm` |
| WP-4 Trust | P1 "why it rang through", I3 Verify & call back, I10 Hold mode | `CallerHeader`, `CallTimeUi` (More sheet), `PipCallCard` |
| WP-5 Quality gate | P14 run benchmarks, P15 APK ≤ 12 MB, P17 key parity for the call screen, notifications and onboarding | `baselineprofile`, `GeoLanguages`, `res/values-*` |

### v4.3 "Who is this?" (about 3 weeks)

| WP | Items |
|---|---|
| WP-6 Number memory | I1 (keyed index over journal, snapshots, archive, notes, messaged, QR), P12 keypad line |
| WP-7 Personal reputation | I2 (pure scorer in `core/common/spam`, unit-tested; tag only), post-call "Block this range?" |
| WP-8 Family safety | I4 safe word, I5 bring in my helper, I7 expected-call hints |
| WP-9 Sync watchdog | I13, P13 backup-folder checker |

### v4.4 "Cards that stay current" (about 3 weeks)

| WP | Items |
|---|---|
| WP-10 Paste anything | I15, P11 (parser in `core/common`, heavy tests on 200 real-world signatures) |
| WP-11 Signed cards | I14 signed card updates and the number-change wizard, I22 sharing receipts |
| WP-12 NFC | I18 tap-to-swap and tag writing (permission allow-list update, device matrix) |
| WP-13 Onboarding and discoverability | P7 "Coming from…", P8 "What Parley can do", P9 blocking preset header, P10 at-a-glance line |

### v4.5 "Everyone can call" (accessibility and car, about 3–4 weeks)

| WP | Items |
|---|---|
| WP-14 RTT | L3: RTT toggle, transcript view, on supported carriers; TESTING on a US carrier |
| WP-15 Drive and roaming | I11 drive profile, L6 assisted dialling and roaming SIM warnings |
| WP-16 Hear who's calling | I17 sonic caller ID, P16 accessibility sweep (200 % font, Switch and Voice Access) |
| WP-17 Menus | I6 menu memory and shortcuts, I12 send call subject / Text first |
| WP-18 Call quality | I8 diary and the Call insights "Quality" card |

### v5.0 "Household & ecosystem" (about 5–6 weeks, separate decisions)

| WP | Items | Decision needed |
|---|---|---|
| WP-19 Shared family phonebook | I19 | Conflict model per label (reuse `ThreeWayMerge`) |
| WP-20 Duress unlock | I21 | Threat-model review |
| WP-21 Companions | I20 Parley Listen; I15b Parley Scan (OCR) | Whether to ship more companion apps |
| WP-22 Platform | L14 VoIP calling accounts (Android 16.1); L15 Android Auto with the Play release | Play distribution and allowlists |
| WP-23 Translations | Resume the 8 locales to 100 % parity | Un-pause localisation |

**Build order rationale:** v4.2 is small, independent and visible every day (reminders, tags, chips). v4.3 has the highest differentiation and needs only existing data. v4.4 builds on the signing and handshake code. v4.5 needs carrier and device testing, so it comes after the quality gate. v5.0 items each need a product decision first.

**Store lines this round earns** (use each only once its feature ships): "Knows who's calling from your own history, not from a cloud." · "Your call history survives the phone change." · "Updates never rearrange your phone app." · "Hang up and call the real number in one tap."

---

## 8. Sources

- Google: [9to5Google M3E Phone](https://9to5google.com/2025/06/19/google-phone-material-3-expressive/) · [Pixel 10 calling features](https://blog.google/products-and-platforms/devices/pixel/calling-updates-pixel-10/) · [Google Phone guide (2026 headlines)](https://9to5google.com/guides/google-phone/) · [Call for Me](https://9to5google.com/2026/09/24/pixel-11-call-for-me/) · [VoIP call logs in system dialers](https://9to5google.com/2026/05/14/google-phone-call-logs/) · [GSMArena calling accounts](https://www.gsmarena.com/google_is_bringing_a_longawaited_dialer_update_to_android-news-72835.php) · [Google Contacts guide](https://9to5google.com/guides/google-contacts/) · [Contacts M3E](https://www.androidcentral.com/apps-software/google-contacts-material-3-expressive-redesign-rolls-out) · [Hold for Me / Direct My Call](https://www.androidpolice.com/how-to-use-hold-for-me-direct-my-call-google-pixel-10/)
- Redesign reaction: [Android Authority poll](https://www.androidauthority.com/i-love-new-google-phone-app-redesign-survey-results-3592446/) · [Android Police](https://www.androidpolice.com/google-phones-redesign-ditches-multiple-tabs-in-the-name-of-material-3-expressive/) · [Business Standard](https://www.business-standard.com/technology/tech-news/google-phone-app-gets-makeover-not-everyone-is-happy-what-changed-material-3-expressive-design-125082500299_1.html)
- Apple: [MacRumors iOS 26 Phone](https://www.macrumors.com/guide/ios-26-phone-app/) · [Cult of Mac](https://www.cultofmac.com/news/ios-26-reimagines-phone-app-call-screening-hold-assist) · [TidBITS classic vs unified](https://tidbits.com/2025/11/10/comparing-the-classic-and-unified-views-in-ios-26s-phone-app/)
- Samsung: [Live Translate](https://www.samsung.com/ae/support/mobile-devices/how-to-use-phone-app-live-translate-during-call/) · [One UI 8.5 call screening](https://www.androidcentral.com/phones/samsung-galaxy/one-ui-8-5-automatic-call-screen-samsung-phones) · [Call backgrounds](https://www.xda-developers.com/how-set-custom-call-background-contacts-samsung-device/) · [One UI 8/8.5 caller-ID bug](https://r2.community.samsung.com/t5/Galaxy-S/One-UI-8-5-Inherents-Bugs-from-One-UI-8-0/m-p/22080004) · [Dropped calls](https://forums.androidcentral.com/threads/dropped-calls-insane-amount-since-one-ui-8-0-android-16-software-update-in-2025.1088872/)
- Truecaller and Hiya: [Truecaller family protection](https://techcrunch.com/2026/03/12/truecallers-now-lets-you-hang-up-on-scammers-on-behalf-of-your-family/) · [TRAI rules](https://www.medianama.com/2026/09/223-trai-truecaller-140-1600-calls-spam-rules/) · [Hiya AI audio stat](https://blog.hiya.com/1-in-4-calls-reviewed-by-hiya-contain-ai-generated-audio) · [Hiya deepfake detection](https://www.hiya.com/newsroom/press-releases/hiya-launches-first-ai-call-assistant-that-stops-live-and-deepfake-scams-in-real-time)
- Open source and privacy: [Fossify Phone issues](https://github.com/FossifyOrg/Phone/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc) · [Fossify Phone releases](https://github.com/FossifyOrg/Phone/releases) · [Fossify Contacts issues](https://github.com/FossifyOrg/Contacts/issues?q=is%3Aissue+sort%3Areactions-%2B1-desc) · [GrapheneOS features](https://grapheneos.org/features) · [Unstore alternatives](https://unstore.io/discover/best-google-contacts-alternatives/)
- Personal CRM: [Dex guide](https://getdex.com/guides/finding-the-right-personal-crm/) · [Storyflow 2026 roundup](https://storyflow.so/blog/best-personal-crm-tools-2026)
- Platform: [AOSP RTT](https://source.android.com/docs/core/connect/rtt) · [Android RTT help](https://support.google.com/accessibility/android/answer/9042284?hl=en) · [Android Auto dialer allowlist](https://www.androidauthority.com/android-auto-dialer-app-support-3488295/) · [Call log 500 limit](https://www.techmesto.com/cla-unlimited-call-log-android/) · [Call-log restore failure](https://forums.androidcentral.com/threads/call-log-refuses-to-restore-despite-restored-confirmation.1057652/) · [Contacts disappearing (XDA)](https://www.xda-developers.com/google-contacts-disappear-android-reversible/)


## 8. Build status (5.0.0)

The roadmap above shipped as 4.4–5.0 (version numbers shifted by two, since 4.2 and 4.3 were correction releases):

| Release | Phase | Shipped | Left out (needs a decision or a permission) |
|---|---|---|---|
| 4.4 | Follow-through | WP-1 to WP-5 | Translations (Parley is English-only since 4.6) |
| 4.5 | Who is this? | WP-6 to WP-9 | — |
| 4.6 | Cards that stay current | WP-10, WP-11, WP-13 | WP-12 NFC (needs `android.permission.NFC`) |
| 4.7 | Everyone can call | WP-14 to WP-18 | — |
| 5.0 | Household | WP-19, WP-20 | WP-21 companion apps (microphone, OCR), WP-22 VoIP calling accounts and Android Auto (Play distribution), WP-23 translations |

Each release was reviewed independently before shipping; device checks are in TESTING.md §25–§29.
