# Parley 5.0 vs. the best phone, call screen and contacts apps (round 7)

*3 Oct 2026. Round 6 (`COMPETITIVE_ANALYSIS_6.md`) set the 4.2–5.0 roadmap, and all of it that needs no new permission has shipped. This round starts again from scratch, in four parts:*

1. *A full inventory of Parley 5.0.0, read from the code.*
2. *The phone and call-screen market: Phone by Google, Samsung Phone (One UI 8–9), iOS 26/27, Truecaller, Hiya, Fossify, Koler and OEM dialers.*
3. *The contacts market and how contact data is now modelled: Google Contacts, iOS, Samsung, Outlook People, Cardhop, Dex, Mesh, Monica, Fossify, RFC 9554 and Android 16/17.*
4. *A privacy review against Privacy Guides, F-Droid, OWASP MASVS v2, Exodus, GrapheneOS, Android's data-safety guidance and EFF SSD.*

*Every claim that Parley has or lacks something was checked against the 5.0.0 code (`34a4131`). File paths are relative to `parley-phone/`. Claims about other apps carry a dated source in §10. **(?)** marks something we could not confirm.*

## 0. Summary

- **Two probable bugs come first.**
  - **Android 16 may refuse new contacts saved to the phone.** Android 16 rejects a contact written to the phone's local storage when the user's default contacts account is a cloud account (Google or Samsung). Parley never reads `RawContacts.DefaultAccount` and always writes new contacts to `localAccount()` (`core/data/.../ContactsRepository.kt:730`, `DeviceAccounts.kt:30`). On most Pixel and Samsung phones, that could break saving new contacts, Make visible, imports, restores and Add several numbers.
  - **Parley can delete a work entry it never shows.** A work row with a department or office but no company and no title is deleted on any save (`ContactsRepository.kt:794`). Parley neither shows nor edits the department.

  Both break Parley's basic promise that saving works and never loses data, so they go into a 5.0.1 before anything else.
- **On features, Parley is already complete as a phone, call screen and contacts app.**
  - Of the basics a dedicated app is expected to have, only six are missing: opening the editor from another app's "Edit contact", video calls, call transfer, flip to silence, a separate name-display order, and photo crop.
  - It leads every app in the set on offline screening you can understand, on layout control, on recovering lost data, on contact organisation, on family safety and on privacy engineering.
  - It matches the 2026 trend of showing context on the call screen (Apple *Call Context*, Samsung *Call Brief*, Google *Magic Cue*), and does it from the user's own notes rather than by reading their mail.
- **What the big apps have that Parley lacks is mostly impossible for a third-party, offline app**, so Parley should not chase it:
  - AI call screening, scam and deepfake detection, live translation, call notes, Hold for Me and recording all need call audio. Android gives call audio only to system dialers.
  - Crowd-sourced spam data needs a network.
  - Parley's answer is offline substitutes and saying plainly what it cannot do.
- **Parley's weakest points now are trust, simplicity and polish, not features.**
  - **Trust:** there is no public release, licence or disclosure policy, and no independent audit.
  - **Simplicity:** there are 159 settings, four overlapping hubs, seven reminder systems and five "important people" concepts.
  - **Polish:** Parley's call screen and Recents look plainer than Google's 2026 Material 3 Expressive style.
  - **Test depth:** the app and telecom layers have 98 tests across 66k lines of code, and there are no device tests.
- **Privacy verdict: top-tier engineering, but not yet rateable as "top".**
  - On the code, Parley beats every dialer and contacts app reviewed, Fossify included. It has no INTERNET permission, enforced at build time, uses hardware-keyed encryption and signed backups, and has duress unlock.
  - A Privacy Guides or F-Droid reviewer cannot rate it yet, because they cannot install it from a public release, check the binary against the source, find a licence that agrees with itself, or report a vulnerability.
  - The fixes are six small, mostly non-code steps (§8, Phase B). They need the owner's decision because they publish things.
- **The plan (§8) has six phases:**
  1. **A. Correctness:** bugs first.
  2. **B. Trust and release.**
  3. **C. Simplify.**
  4. **D. Polish.**
  5. **E. Close the remaining feature gaps.**
  6. **F. Quality.**

  Items that need a new permission or the owner's decision are listed separately (§8.7).

---

## 1. Scorecard

1 = weak or missing, 5 = best in class. These are judgement calls, based on each app's 2025–26 releases and reviews (§10) and on Parley's code.

| Area | **Parley 5.0** | Google Phone/Contacts | Samsung Phone/Contacts | iOS 26/27 | Truecaller | Fossify |
|---|---|---|---|---|---|---|
| Incoming and in-call | **4.5**: 3×2 morphing pills, answer slider, caller card, RTT, hold mode, menu memory, call reasons | **5**: AI screening, Call Notes, Scam Detection, Calling Cards | **5**: AI screening, Live Translate, Call Brief, backgrounds | **5**: Screening, Hold Assist, Call Context, posters | 4 | 2.5 |
| Dialer and keypad | **5**: T9 in 11 scripts and CJK, call pill per SIM, USSD, abroad assist | 3.5 | 4 | 3 | 4 | 3.5 |
| Recents and history | **5**: archive beyond Android's limit, why-it-rang, number memory, To call | 4: card style, Contacts tab returning | 4.5: VoIP calls in log (One UI 9) | 4 | 3.5 | 2.5 |
| Contacts list and page | **4.5**: chips, AND/Unlabelled filters, compact page, variants | 4.5: Expressive cards, Recent activity, Your info | 4 | 4 | 3 | 3.5 |
| Editor and fields | **4.5**: ~70 relations, 16 profiles, 12 messengers, pronouns. Missing: department, custom fields, photo crop | 4.5: Add-fields sheet, department, custom field | 4 | 4.5: pronunciation, maiden name, other calendars | 2.5 | 3 |
| Blocking and spam | **4**: explainable offline engine. No crowd data | 4.5 | 4.5 | 4 | **5** | 2 |
| Sharing and exchange | **4**: QR, encrypted QR, signed self-updating card, shared labels. No tap exchange | 4.5: Tap to Share, Your info | 4.5: profile card, NFC | **5**: NameDrop | 3 | 2 |
| Data safety and recovery | **5**: 30-day trash, undo, 6-month snapshots, health check, sync watchdog | 4 (undo only on the web) | 4 (30-day bin) | 3 (no trash on the phone) | 2 | 2 |
| Privacy engineering | **5** | 2.5 | 3 | 4 | 1 | 4 |
| **Verifiable trust** (release, licence, audit, disclosure) | **2** | n/a | n/a | n/a | n/a | **4**: on F-Droid, built by F-Droid |
| Visual polish | **3.5**: calm and dense, but plainer type, no expressive avatar, flat Recents | **5** (though criticised as "oversized") | 4.5 | **5** | 3.5 | 3 |
| Simplicity | **3**: 159 settings, overlapping hubs | 4 | 3.5 | **4.5** | 3 | **4.5** |
| Test and release maturity | **3**: strong core tests, thin UI/telecom tests, no device tests | 5 | 5 | 5 | 4 | 3.5 |

**The pattern:** Parley wins on what it does and loses on proving it, keeping it simple and presenting it. The plan in §8 is built around closing those three gaps.

---

## 2. The apps: features, how they work, UI and limits

### 2.1 Phone by Google (Pixel, Nothing, Motorola, OnePlus in some regions)
- **Features**
  - **Call screening and messages:** Call Screen (manual or automatic) and Take a Message (a live transcript of missed calls).
  - **AI calling help:** Call Notes (Gemini Nano), Hold for Me, Direct My Call, Voice Translate and Clear Calling.
  - **Scam protection:** Scam Detection (on-device; Galaxy S26 since Feb 2026) and caller-ID verification, where the real contact's phone confirms the call.
  - **Calls with context:** "Urgent" call reason and Calling Cards.
  - **Agentic calling:** Call for Me (Pixel 11, paid, US).
  - **Other:** recording with an announcement, and spam filtering.
- **How it works.**
  - On-device: transcription, notes and scam models.
  - Need a Google account: spam reputation, call reasons, verification and Calling Cards.
  - It is a system app, so it gets call audio, which no third-party dialer can.
- **UI.**
  - Material 3 Expressive (Aug 2025).
  - Home tab: a favourites carousel and Recents as rounded cards.
  - Keypad and Voicemail tabs.
  - Pill buttons that morph into rounded rectangles.
  - A scalloped avatar that rotates while ringing.
  - Answering by horizontal swipe or a single tap.
  - Filter chips.
- **Limits and complaints.**
  - Users called the redesign "oversized, clunky, ugly", and 27 % disliked it in a poll.
  - The Contacts tab was removed for 13 months and is now coming back (beta, 1 Oct 2026).
  - T9 search misses some contacts.
  - Most AI features are Pixel-only and vary by region.

### 2.2 Samsung Phone and Contacts (One UI 8 → 9)
- **Features.**
  - **Screening and translation:** Bixby Text Call, automatic AI screening (8.5) and Live Translate.
  - **Context and history:** Call Brief (events, messages and the caller's local time) and VoIP calls in the call log (One UI 9).
  - **Personalisation:** per-contact call backgrounds (picture or video) and a profile card that others see when you call.
  - **Contact management:** a 30-day recycle bin and view-by-storage.
  - **Other:** call recording and Hiya spam ID.
- **How it works.** Language packs run on the phone. Spam lookups go to Hiya, and the AI features need a Samsung account.
- **UI.**
  - Dense lists under a large title.
  - Green and red circles dragged outward to answer or decline.
  - Labelled round buttons.
  - Call status in the Now Bar.
- **Limits.**
  - Callers are matched on the last 5–8 digits, so the wrong contact can show.
  - Users report dropped calls since One UI 8.
  - Custom labels revert.
  - Features vary by region and need an account.

### 2.3 iOS 26/27 Phone and Contacts
- **Features.**
  - **Screening:** Call Screening ("ask the reason for calling").
  - **During calls:** Hold Assist, Live Translation and recording with a transcript.
  - **Context:** Call Context (iOS 27: codes found in Mail).
  - **Handoff:** move a call between iPhones.
  - **Contacts and sharing:** Contact Posters, NameDrop, pronouns, per-app limited contact access, and alternate-calendar birthdays **(?)**.
- **How it works.** Mostly on-device Apple Intelligence, plus carrier spam labels.
- **UI.**
  - Unified or Classic layout.
  - Liquid Glass styling.
  - A 3×2 grid of translucent round buttons.
  - Full-screen posters.
- **Limits.**
  - Screening silences doctors, couriers and recruiters (PIRG).
  - Posters are a niche: over half of TidBITS readers made none, they are hard to remove, and storage grows.
  - There is no Recently Deleted on the phone.

### 2.4 Truecaller and Hiya
- **Features.**
  - **Caller ID and spam:** crowd-sourced, the largest database of its kind.
  - **AI answering (Truecaller):** an AI assistant answers calls.
  - **Synthetic voices:** AI Call Scanner (Truecaller) and real-time deepfake-voice detection (Hiya AI Phone).
  - **Family Protection (Truecaller):** an admin can end a family member's call remotely.
- **How it works.** Cloud lookups. Truecaller uploads contacts, hashed.
- **Limits.**
  - Ads and upsell.
  - Blocked numbers still ring.
  - Names are wrong or out of date.
  - Contact uploads are a privacy problem (Swedish IMY probe, Feb 2025).
  - Weakest privacy in the set.

### 2.5 Fossify Phone and Contacts, Koler, OEM dialers
- **Fossify.** It has been stalled since Phone 1.11.1 (3 Feb 2026). Its UI is AOSP-style.
  - **Most-requested Phone issues:** recording, visual voicemail, global search, speaker on by default, proximity only after answering, ViLTE, auto-SMS for missed calls.
  - **Most-requested Contacts issues:** all Nextcloud fields, a relation field, merging duplicates, choosing what to share, QR.
  - Parley already covers almost all of these (§6.1).
- **Koler** has not been updated since June 2023 and is effectively dead.
- **OxygenOS 16** disables the OnePlus dialer outside some regions.
- **Nothing OS** users ask for a native dialer.

The FOSS end of the market has no actively developed, full-featured option, and that gap is Parley's opening.

### 2.6 Contacts-first and relationship apps
| App | What it adds | Limit |
|---|---|---|
| **Outlook People** | Contact lists, colour categories, de-duplication across Outlook and Teams, table view; Exchange fields (department, office, manager, assistant) | Needs an account; a desktop-first design |
| **Cardhop** | A "parse anything" input line, Shortcuts actions, a birthday widget | Only an iOS/macOS front end to the system address book |
| **Dex, Mesh (formerly Clay)** | LinkedIn and email enrichment, keep-in-touch cadences, timelines | Cloud only, paid, data mining |
| **Monica / Chandler** | User-defined field types, custom genders, pets, food preferences, work history | Self-hosted web app, not a phone |

Parley's Circle (rhythms, promises, timeline), Paste details and social-profile groups cover the useful part of these apps offline.

---

## 3. How contacts changed in 2024–2026

| When | Change | Effect on Parley |
|---|---|---|
| Sep 2024 | Google "Create contact" redesign: a short form plus an **Add fields** sheet (middle, phonetic, file as, department, related, custom field) | Parley's compact editor (4.3–4.4) follows the same idea. **Missing:** department, custom field and phonetic middle name |
| May 2024 | **RFC 9554** vCard extensions: PRONOUNS, SOCIALPROFILE, GRAMGENDER, LANGUAGE, CREATED; name gains SECONDARY-SURNAME and GENERATION; addresses gain ROOM, FLOOR, BUILDING, STREETNUMBER and others | Parley writes PRONOUNS and reads SOCIALPROFILE. It still writes profiles as URL + X-ABLabel and drops the rest, listing them in the import report |
| Jul 2025 | Google **Recent activity** card | Parley has had an at-a-glance line and timeline since 3.x |
| Aug 2025 | Google Contacts in **Material 3 Expressive**: lists in rounded cards, pill actions | Parley uses M3E controls but flat list rows |
| Oct 2025 | Google **Calling Cards** (a picture and styled name you set for any contact) | Parley's per-contact call-screen picture (4.1) is the same idea, with no Play Services needed |
| Nov 2025 – Jan 2026 | Share and import **review screens** with field checkboxes | Parley has had share-with-field-choice and a restore preview for a while |
| 2025–26 | **Android 16 default account**: inserts to the local account are refused when the default is cloud | **Bug B1** |
| Jun 2026 | Gemini edits contacts | Parley's Paste details is the offline counterpart |
| Jul 2026 | Google **Your info** card | Parley's My card, plus signed self-updating cards (4.6) |
| Aug 2026 | Google **Tap to Share** (like NameDrop; Pixel 6+, 2026 Samsung foldables) | Parley has QR, encrypted QR and Swap, but **no tap exchange** (needs NFC) |
| 2026 | **Android 17 contact picker** `ACTION_PICK_CONTACTS` (per-session, per-field); Play limits on `READ_CONTACTS` for apps targeting API 37 | Parley answers `ACTION_PICK` but not the new action. It needs a decision (§8.7) |
| 2025–26 | Android **Key Verifier** (QR check of E2EE messaging keys) in Google Contacts | Needs Play Services. Optional, read-only status at most |

### 3.1 The modern field and category set, and Parley's coverage

| Category | Fields that are now standard | Parley 5.0 |
|---|---|---|
| **Name** | prefix, first, middle, last, suffix; phonetic first/middle/last; nickname; file as | ✅ except **phonetic middle name** (kept, not editable) and **file as** (read-only) |
| **Identity** | pronouns; *(iOS)* pronunciation, maiden name; *(RFC 9554)* grammatical gender, language | ✅ pronouns. ❌ the rest (low value on Android, except language) |
| **Work** | company, job title, **department**; *(Outlook)* office, manager, assistant | ◐ company and title only. **Department is invisible and can be deleted (B2)** |
| **Reach** | phones with types and custom labels, default number, email, SIP, messenger handles, social profiles, websites | ✅ ahead of everyone: 12 messengers and 16 social profiles. ◐ phone types: 6 plus custom in the picker (Android has 20) |
| **Places** | structured address; *(RFC 9554)* floor, building, etc.; map links | ✅ address with map links and coordinates. ❌ the RFC 9554 parts |
| **Dates** | birthday with optional year, anniversary, custom labels; *(iOS/Samsung)* lunar or other calendars | ✅ plus date of death. ❌ alternate calendars |
| **Relationships** | related people with types | ✅ about 70 types, linked to contacts, shown both ways |
| **Notes and custom** | notes; *(Google)* **custom label:value fields** | ✅ notes with links. ◐ Google's custom fields are read-only |
| **Media** | photo with crop and reposition; full-screen caller picture | ◐ the whole photo is kept, but it can't be cropped or repositioned, and the editor can't take a photo with the camera. ✅ call-screen picture |
| **Alerts** | ringtone, vibration, emergency bypass | ✅ per contact and per label |
| **Organisation** | labels or groups, favourites, accounts or storage, hidden contacts | ✅ plus merging labels, AND/Unlabelled filters, private and temporary variants, shared family labels |
| **Management** | duplicates, link/unlink, trash, undo, fix suggestions, import/export | ✅ ahead (30-day trash, undo and snapshots on the phone; a health check). ◐ no Google or Outlook CSV export formats |
| **Self and sharing** | own card, QR, tap exchange, a card that updates itself | ✅ except **tap exchange** |
| **Security** | per-app access limits; key verification | ✅ private contacts per contact and an access audit. ◐ no Key Verifier |

---

## 4. Is Parley a complete phone, call screen and contacts app?

Legend: ✅ present · ◐ partial · ❌ missing · ⛔ impossible for a third-party or offline app.

### 4.1 Phone and dialer
| Expected | | Note |
|---|---|---|
| Default dialer role with a rescue guide | ✅ | `DialerRoleRescue.kt` |
| Call-screening role | ✅ | |
| T9 search in non-Latin scripts | ✅ | 11 scripts plus CJK |
| Speed dial, voicemail on long-press 1, `+` on long-press 0 | ✅ | |
| Dual SIM: per call, per contact, per label | ✅ | |
| Emergency calls over the lock, never screened or limited | ✅ | `EmergencyPolicy` |
| USSD, MMI codes, IMEI, secret codes | ✅ | |
| Pause and wait characters | ✅ | |
| Assisted dialling abroad | ✅ | |
| Recents: grouping, filters, search, saved filters | ✅ | 8 chips |
| Call log export and import; history beyond Android's limit | ✅ | |
| Missed-call notification with actions | ✅ | |
| Block from Recents or a notification; system block list | ✅ | |
| Visual voicemail | ◐ / ⛔ | Shows what the carrier app stored. It cannot sync without internet |
| Call forwarding, call waiting, carrier settings | ◐ | Opens Android's own screens, which is acceptable |
| TTY | ◐ | Opens Android's own setting |
| **Video-call badge in Recents** | ❌ | `CallLog.Calls.FEATURES_VIDEO` is never read. Easy |
| VoIP calls (WhatsApp, Meet) in Recents | ❌ | One UI 9 has it. Needs research (§8.7) |
| Favourites widget | ❌ | Only a direct-dial widget and the Circle widget |
| Android Auto and Wear apps | ❌ | Auto needs Play allow-listing. The watch already mirrors the CallStyle notification |

### 4.2 Call screen
| Expected | | Note |
|---|---|---|
| Full-screen incoming over the lock screen; slide or tap to answer | ✅ | |
| Decline, silence (also with the volume keys), reply with SMS | ✅ | |
| Mute, keypad (DTMF), speaker and route list, hold, add call | ✅ | |
| Merge, swap, manage conference (separate or end one person) | ✅ | |
| Call waiting choices | ✅ | |
| Proximity screen-off | ✅ | |
| STIR/SHAKEN, HD voice and Wi-Fi tags, why the call dropped | ✅ | |
| RTT | ✅ | |
| Call subject or reason | ✅ | |
| Picture-in-picture; CallStyle notification and status-bar chip | ✅ | |
| Notes during the call; card after the call | ✅ | |
| Auto-answer | ✅ | |
| TalkBack actions and spoken caller name | ✅ | |
| **Speaker on by default** | ❌ | Fossify's open request #200. Easy |
| **Proximity only after answering** | ❌ | Fossify #166. Easy |
| **Flip to silence** | ❌ | Sensor only, no permission needed. Easy |
| **Call transfer or deflect** | ❌ | `Call.transfer` / `Call.deflect` (API 29+), where the carrier supports it |
| Video calls (ViLTE) | ❌ | Always answered audio-only (`CallManager.kt:958`). Needs CAMERA (§8.7). At minimum, say "answered as voice" honestly |
| Recording, AI screening, live captions, translation | ⛔ | Need call audio, which only system dialers get |

### 4.3 Contacts
| Expected | | Note |
|---|---|---|
| Create, edit, delete; choose account; move between accounts | ✅ | **B1 on Android 16** |
| Labels, favourites, link and unlink, duplicates and merge | ✅ | |
| vCard 2.1/3/4 and CSV import and export; SIM read and write | ✅ | |
| Search across all fields; phonetic names | ✅ | |
| Ringtone and vibration per contact; send to voicemail | ✅ | |
| Share as file, text, QR or encrypted QR; field choice | ✅ | |
| `ACTION_PICK`, `GET_CONTENT`, `INSERT`, `INSERT_OR_EDIT`, `QUICK_CONTACT`, `SHOW_OR_CREATE` | ✅ | |
| **`ACTION_EDIT` from other apps** | ❌ | No intent filter. Another app's "Edit contact" won't open Parley. Easy |
| **Separate sort order and name display order** | ◐ | One combined setting (`SettingsPages.kt:212`). AOSP keeps them separate |
| **Photo crop and reposition; take a photo** | ◐ | Whole photo kept, no framing. The camera can be opened by intent with no permission, as QR scan already does |
| **Department, office** | ❌ | B2 |
| Custom fields | ◐ | Read-only |
| Work-profile contacts in the list and search | ◐ | Caller ID only |
| Trash, undo, version history | ✅ | Ahead of everyone |
| Tap or NFC exchange | ❌ | Needs NFC (§8.7) |

**Verdict: complete, with six small holes.** The six are `ACTION_EDIT`, display order, photo framing, department, speaker on by default and proximity only after answering. Three more are worth adding: flip to silence, transfer/deflect and the video badge. None needs a new permission.

---

## 5. Privacy: can Parley be called the top?

### 5.1 Against the guides
Of 31 applicable criteria from Privacy Guides, F-Droid, OWASP MASVS v2, Exodus, GrapheneOS, Android data-safety guidance and EFF SSD: **18 met, 9 partial, 4 failed.**

| Met (best in class) | Partial | Failed |
|---|---|---|
| No INTERNET; build-enforced allow-list run in CI | Permissions wider than a bare dialer needs, for good reasons that aren't documented (GET_ACCOUNTS, READ_SYNC_SETTINGS, `<queries>`) | **No public release.** Not on `main`, no `v*` tags, F-Droid recipe stale at 3.2.0 |
| 0 trackers, no Play Services | Ed25519 below API 33 is home-grown and not constant-time | **No `SECURITY.md` or disclosure channel** |
| `allowBackup=false`; extraction rules exclude everything | Lock-screen incoming-call notification shows the name | **No independent audit** |
| Keystore and StrongBox sealing, signed E2E backups | 76 `Log.w/e/i` calls kept in release; crash-report masking misses names | No bug bounty (low priority) |
| Duress unlock, with no visible sign | `verify-signatures=false`; Material 3 alpha shipped | |
| Exported components protected and tested; immutable PendingIntents | Reproducible build last proven for 3.1.0 code, not in CI; `version-control-info` in the APK | |
| Clipboard marked sensitive; overlay guard; lock-aware recents | Licence ambiguous: repo root MIT, `parley-phone/` GPL-3.0, README silent | |
| Local, opt-in crash reports; detailed threat model | No privacy policy document | |
| | Signing key on an ephemeral path with plaintext passwords next to it | |

### 5.2 Compared with the others
| | Parley | Fossify | GrapheneOS Dialer | Google | Samsung | iOS | Truecaller |
|---|---|---|---|---|---|---|---|
| Network | none (enforced) | none | none **(?)** | yes | yes | yes | yes, central database |
| Encryption beyond file-based | **vault, records, archive, backups** | no | no | partly | partly | yes | server side |
| Hidden contacts, duress | **yes** | no | OS-level only | no | Secure Folder | no | no |
| Release you can verify | **no** | **F-Droid-built** | OS-signed | n/a | n/a | n/a | n/a |
| Audit | no | no | no **(?)** | internal | internal | internal | no |

**Verdict.**
- Technically, Parley is ahead of every app here, Fossify included.
- On trust signals anyone can check, it is behind Fossify, because Fossify is published, licensed and built by F-Droid.
- Six steps (Phase B) bring Parley level with or ahead of Fossify on every verifiable signal.
- An external audit would then make Parley a credible "top privacy dialer" recommendation.

---

## 6. Where Parley stands

### 6.1 Ahead (by user impact)
1. **Screening you can understand, without a cloud.** Rules have an order, every decision has a "why" trace, and calls can be tested and replayed. "Why it rang" and expected-call hints fix the top complaint about iOS and Samsung screening: it silences the doctor or the courier.
2. **Data you can't lose.** A 30-day trash, undo and 6-month snapshots on the phone, a health check and a sync watchdog. Google's undo works only on the web, and iOS has no trash on the phone.
3. **Context from your own data.** The caller card shows your note, the last call, open promises, the caller's local time and "Who is this?". It is the offline version of Call Context, Call Brief and Magic Cue.
4. **Layout control.** The reorderable navigation bar and combined or separate layouts. Google's 13-month Contacts-tab saga shows why this matters.
5. **Contact organisation.** Label merge, AND and Unlabelled filters, shared family labels, private and temporary variants of one contact, and two-way relations.
6. **Family and personal safety.**
   - **Family:** safe word, helpers, expected calls, call-time limits and supervised mode.
   - **Personal:** duress unlock.
7. **Covers everything Fossify users ask for** except recording, ViLTE, auto-SMS and speaker on by default, while Fossify itself has stalled.
8. **Privacy engineering** (§5).

### 6.2 At parity
- **Call screen:** the in-call grid and controls, answering, call waiting, conferences, routing, RTT, dual-SIM, STIR, HD and Wi-Fi tags, call reason, PiP, CallStyle notification, emergency handling.
- **Contacts:** field coverage at iOS level or above.

### 6.3 Behind, and buildable
- **Polish:** the expressive ringing avatar, emphasised type and an optional card style for Recents.
- **Call screen:** speaker on by default, proximity only after answering, flip to silence, transfer and deflect, and the video badge.
- **Contacts:** `ACTION_EDIT`, separate display order, photo framing and camera, department, custom fields, phonetic middle name and RFC 9554 fields, Google and Outlook CSV export, alternate-calendar birthdays, more phone types and a favourites widget.
- **Scam help and lock screen:** an offline "Is this a scam?" sheet, a "text me your name" reply, and a choice of how much the lock screen shows.

### 6.4 Behind, and impossible (do not chase)
| Capability | Why | Parley's honest substitute |
|---|---|---|
| AI screening, Take a Message, Call Notes, Hold for Me, Direct My Call, translation, recording, deepfake detection | Only system apps get call audio, and Parley has no microphone permission | Notes and promises, "I'm on hold" mode, menu memory, Who is this?, safe word, Check it's really them, the scam sheet |
| Crowd-sourced spam data | No network | Signed list packs through the optional Parley Lists companion; personal reputation |
| Visual voicemail sync | No network | Show what the carrier app already stored |
| Caller verification, remote family hang-up, Handoff | Need a network service | Call subject through the carrier, Text first, helper calls |
| Lock-screen emergency dialler | System UI | `EmergencyPolicy` |

---

## 7. What else the inventory found

- **Size.**
  - About 125k lines of main Kotlin, 65 destinations, about 100 sheets and dialogs.
  - 159 searchable settings, 42 "What Parley can do" rows, 5 tiles and 2 widgets.
  - 16 notification channels, 8 workers and 83 registered stores.
- **Too many ways in.**
  - **Hubs:** four overlap (⋮ › Tools, Settings › Tools, What Parley can do, Privacy dashboard).
  - **Reminders:** seven separate mechanisms (missed re-alert, Remind me / To call, follow-ups, nudges and digest, birthdays, backup reminder, temporary due).
  - **People who matter:** five concepts (Favourites, Frequent, Circle, Labels, To call).
  - **Who's in…** appears in three menus.
  - **Calls settings:** 31 entries.
- **Hard to find.** Family safety, Drive profile, Phone menus, Call time, Diagnostics, SIM import, Import calls and Introduce myself are each reachable from only one or two places.
- **Docs and names that drift.**
  - The README says Scan QR is in the Contacts and Keypad ⋮ menus, and Messaged numbers in the Recents ⋮ menu. They are not there.
  - `MyDetailsDialog` still exists, though the glossary says it should be "My card".
  - "Daily snapshots (time machine)" breaks the glossary.
  - One string says "e-mail".
- **Code health.**
  - The detekt baseline suppresses 1,781 issues, 215 of them overly complex methods.
  - `CallManager.kt` is 1,501 lines. `ContactsRepository`, the editor and the contact page are about 1.25k each.
  - **Tests:** core/common has 1,577; app has 86 for 55k lines; telecom has 12; lists-updater has 0. There are no device tests, and the benchmarks have never run on a device.

---

## 8. The plan

**Owner decisions (3 Oct 2026).** Tap/NFC exchange and video calls are skipped. Phase B (trust and release) waits until the app is polished. The build order is A → C → D → E, with F alongside each phase.

Each phase is a release. The order runs from what can lose data or break, through what blocks trust and what makes Parley hard to use, to how it looks, then new features, with quality work running alongside. Effort: S ≈ a day, M ≈ a few days, L ≈ more.

### 8.1 Phase A: Correctness (5.0.1, hotfix)
| # | Item | Effort |
|---|---|---|
| A1 | **Android 16 default account (B1).** Read `RawContacts.DefaultAccount.getDefaultAccountForNewContacts()` on API 36+. When the default is a cloud account, save new contacts there, or show a clear choice instead of failing. Cover every insert path: new contacts, Make visible, imports, restores, visible temporaries, SIM import, Add several numbers and shared labels. Add a provider-fake test and a check on an Android 16 device | S–M |
| A2 | **Department and office (B2).** Read and edit department in the Work group, and show office and job description as read-only. Never delete an Organization row that still holds other columns. Add a write test | S |
| A3 | **`ACTION_EDIT`** intent filter and route to the editor, with tests in `NavigationRoutesTest` | S |
| A4 | **Honest video calls.** When a video call is answered as voice, say so on the call screen, and show a video badge in Recents (`FEATURES_VIDEO`) | S |
| A5 | **Docs and names.** Fix the README menu locations. Fold `MyDetailsDialog` into My card. Rename "time machine". Change "e-mail" to "email" | S |
| A6 | **Logs and crash reports.** Strip `Log.w/i/e` in release, or route them through a safe logger that keeps only class names. Remove exception messages from shared crash reports | S |
| A7 | **Lock-screen caller name.** A choice between name, initials and "Incoming call" | S |

### 8.2 Phase B: Trust and release (needs the owner's go-ahead: it publishes things)
| # | Item | Effort |
|---|---|---|
| B1 | **Secure the signing key now.** Move `parley-release.jks` to offline, encrypted storage with fresh passwords, and delete `parley-signing.txt`. Losing the key means no update can ever install over an existing install | S (owner) |
| B2 | **Settle the licence.** Either add a README line saying "`parley-phone/` is GPL-3.0-only; the rest of this repository is MIT", or move Parley to its own repository. Add SPDX headers, and decide between GPL-3.0-only and -or-later | S |
| B3 | **`SECURITY.md`** (private reporting, supported versions, response times) and **`PRIVACY.md`**. PRIVACY.md says Parley collects and sends nothing, covers the system call log, the Lists companion's network use, and that crash reports are local and opt-in. Add **`PERMISSIONS.md`** with one line of justification per permission | S |
| B4 | **Reproducible build proof for the release.** Run `tools/repro-check.sh --signed`, record the result, add a CI job that builds twice and compares, set `vcsInfo.include = false`, and correct the "no native code" line | S–M |
| B5 | **Publish.** Merge to `main` (or the new repository), tag `v5.0.1`, and attach the APK, its SHA-256 and the certificate fingerprint to a GitHub Release. This enables Obtainium, Privacy Guides' first-choice install path | S |
| B6 | **IzzyOnDroid, then F-Droid.** Refresh the recipe (commit, `Binaries`, `AllowedAPKSigningKeys`) and state the Lists companion's network use | M |
| B7 | **Exodus report and GrapheneOS check.** Link the Exodus report. Test Contact Scopes, the Network toggle and private-name lookups on GrapheneOS | S |
| B8 | **Supply chain.** Turn on PGP dependency verification where artifacts are signed, add OSV-Scanner or Dependabot, move to a stable Material 3, and use a vetted Ed25519 library below API 33 | S–M |
| B9 | **Independent audit** of the vault, backup, duress, card signatures and IPC (NLnet NGI with Radically Open Security, or Cure53 or 7ASecurity), and publish the report | L (funding) |

### 8.3 Phase C: Simplify (5.1)
| # | Item | Effort |
|---|---|---|
| C1 | **One hub.** Fold Tools into What Parley can do, grouped by job. Keep one ⋮ › Tools entry. Move the Privacy dashboard under Settings › Privacy | M |
| C2 | **Reminders page.** One page and one notification-channel group for all seven reminder kinds, each with its own switch and time | M |
| C3 | **Split Calls settings** (31) into Answering, During calls and SIMs & carrier. Put Family safety, Drive, Phone menus and Call time on a "Situations" page | S–M |
| C4 | **People priority.** Make Frequent and To call sections of Favourites and Recents rather than separate concepts, and keep Circle as the only opt-in layer | M |
| C5 | **Who's in…** becomes a "city" search scope instead of an item in three menus | S |
| C6 | **Separate sort order and name display order**, matching AOSP | S |
| C7 | **Settings budget.** No net growth: each new setting must replace one or move one onto a sub-page. `SettingsSearchTest` enforces the limits | — |

### 8.4 Phase D: Polish (5.2)
| # | Item | Effort |
|---|---|---|
| D1 | **Expressive ringing avatar.** A slowly rotating scalloped `MaterialShapes` frame, which stays still when animations are off | S |
| D2 | **Emphasised type.** A heavier caller name, tabular digits for the timer and keypad, and checked weights across headers. No bundled font unless the APK budget allows it | S |
| D3 | **Photo framing.** A Compose crop-and-reposition overlay for the square avatar, keeping the original (4.3 rule). Add Take photo through `ACTION_IMAGE_CAPTURE`, which needs no permission (QR scan already works this way) | M |
| D4 | **Optional card style for Recents** (rounded groups by day), off by default under the layout promise | M |
| D5 | **Poster-style call picture.** A name-over-picture layout option for the per-contact call-screen picture | S–M |
| D6 | **Favourites widget** (grid, private contacts hidden while locked) | M |

### 8.5 Phase E: Close the remaining feature gaps (5.3)
| # | Item | Effort |
|---|---|---|
| E1 | **Speaker on by default** (always, unknown callers only, or when no headset is connected) and **proximity only after answering** | S |
| E2 | **Flip to silence** (accelerometer, no permission) | S |
| E3 | **Call transfer and deflect** (`Call.transfer` / `Call.deflect`) under More, where the carrier supports them | S–M |
| E4 | **"Is this a scam?" sheet** in More for unknown callers: offline warning signs, a safe-word reminder and Check it's really them | S–M |
| E5 | **"Text me your name" reply** for unknown callers. It opens the SMS app, so no permission is needed | S |
| E6 | **Custom fields** (label: value). They map to Google's user-defined field on Google accounts, and to Parley rows plus `X-` properties in vCard | M |
| E7 | **More name fields.** The phonetic middle name can be edited. RFC 9554 LANGUAGE, SECONDARY-SURNAME, GENERATION and the new address parts are kept as Parley rows with vCard in and out. Social profiles are written as SOCIALPROFILE | S–M |
| E8 | **Google CSV and Outlook CSV export** formats | S |
| E9 | **Alternate-calendar birthdays** (Chinese lunar, Hebrew, Hijri) using ICU's calendars in Android, with reminders | M |
| E10 | **All 20 Android phone types** in the type picker, behind "More types" | S |
| E11 | **Work-profile contacts in search** (read-only, through the enterprise URIs already used for caller ID) | M |

### 8.6 Phase F: Quality (alongside every phase)
| # | Item | Effort |
|---|---|---|
| F1 | **Telecom tests.** Robolectric tests for the call state machine, answer and decline, waiting, conference, routes and the emergency bypass | M |
| F2 | **App tests.** ViewModel tests for the editor, contact page, Recents and keypad. Compose UI smoke tests for the five tabs and the call screen | M |
| F3 | **Device runs.** Run the three macrobenchmarks and a small instrumented smoke suite on a real device or emulator in CI, and record the results in `PERFORMANCE_BENCHMARKS.md` | M |
| F4 | **Split the large files.** Break up `CallManager`, `ContactsRepository`, the editor and the contact page. Shrink the detekt baseline by at least 20 % per release | M, ongoing |
| F5 | **Fix the flaky test** `CardStoresTest.the_ledger_is_sealed_and_survives_a_restart` (shared crypto state between tests) | S |
| F6 | **Fuzz the parsers.** Jazzer on vCard, QR and list-pack parsing, keeping the corpus | M |

### 8.7 Needs a new permission or a decision (to discuss with the owner)
| Item | Needs | Recommendation |
|---|---|---|
| **Tap / NFC exchange** of My card (and the signed card) | `NFC` (no network) | Worth it: it is the 2026 way to exchange contacts and fits the privacy model. Use HCE or NDEF with Parley's own card format |
| **ViLTE video calls** | `CAMERA` | Not now. Answering as voice with an honest label (A4) is enough for a privacy app. Revisit if users ask |
| **VoIP calls in Recents** | Research: calling-account columns in Android 16.1 | Investigate first; no permission expected |
| **Android 17 `ACTION_PICK_CONTACTS`** | Platform decision: whether a third-party app may serve it | Investigate. If only the system may, document that private contacts stay out of other apps' pickers, which is a privacy plus |
| **Key Verifier status** | Google Play Services | Optional and read-only, shown only when present. Low priority |
| **Android Auto app** | Play allow-listing | Only with a Play build. The drive profile covers the need |
| **Wear app** | A companion module | Not now. CallStyle mirroring works |
| **Translations** | Owner decision (English-only since 4.6) | Keep English-only until Phase C reduces the string count, then add the top 5 languages |
| **Separate repository and public release** | Owner decision | Recommended (Phase B) |

### 8.8 Things not to do
- **Do not chase the AI features that need call audio**, or ship anything that pretends to replace them. Say plainly what Parley cannot do, as the voicemail and TTY screens already do.
- **Do not add an INTERNET permission to the app itself.** Network features belong only in the optional Lists companion.
- **Do not add features before Phases A–C.** The research is consistent that Parley's next gains come from trust, simplicity and polish, not from more features.

---

## 9. Counter-arguments

- **"Without AI screening Parley can't be one of the best."** For mainstream users on a Pixel or Galaxy, that's true: screening by voice is the headline feature of 2025–26. But those features are tied to Pixel or Galaxy hardware, a region or an account, and users complain about them (doctors silenced, prompts that sound robotic). Parley competes for the users who want an app that works on any phone, without an account, and explains its decisions. In that segment no app comes close, and Fossify has stalled.
- **"159 settings is a strength for power users."** Partly. But the inventory found duplicated concepts, not only depth. Phase C removes duplicates, not capability.
- **"B1 may not happen on real devices."** The evidence is one Expo report on Samsung Android 16 phones plus Android's own documentation. It hasn't been reproduced on a Pixel. The fix is small and safe either way, so it goes first.
- **"Privacy is already top; the trust steps are paperwork."** Privacy guides rate what they can check. Without a release, a licence and a reproducible build, they cannot check anything, so the paperwork *is* the rating.

---

## 10. Sources

The working notes for this round have the full per-claim sources. The main ones, by topic:

- **Google.**
  - [M3E Phone redesign (9to5Google, 19 Jun 2025)](https://9to5google.com/2025/06/19/google-phone-material-3-expressive/)
  - [Contacts tab returns (9to5Google, 1 Oct 2026)](https://9to5google.com/2026/10/01/google-phone-contacts-tab/)
  - [Redesign reaction (Business Standard)](https://www.business-standard.com/technology/tech-news/google-phone-app-gets-makeover-not-everyone-is-happy-what-changed-material-3-expressive-design-125082500299_1.html)
  - [Pixel 10 calling features](https://blog.google/products-and-platforms/devices/pixel/calling-updates-pixel-10/)
  - [Scam Detection help](https://support.google.com/phoneapp/answer/15654065?hl=en)
  - [Call Reason (Android Authority, Dec 2025)](https://www.androidauthority.com/google-phone-app-call-reason-3621179/)
  - [Call for Me (9to5Google, Sep 2026)](https://9to5google.com/2026/09/24/pixel-11-call-for-me/)
  - [Contacts create redesign (Sep 2024)](https://9to5google.com/2024/09/13/google-contacts-create-redesign/)
  - [Recent activity (Jul 2025)](https://www.androidpolice.com/google-contacts-recent-activity-feature-rolling-out/)
  - [Contacts M3E (Aug 2025)](https://9to5google.com/2025/08/14/google-contacts-material-3-expressive/)
  - [Calling Cards](https://support.google.com/phoneapp/answer/16539485?hl=en)
  - [Your info (Jul 2026)](https://www.androidauthority.com/google-contacts-rolls-out-your-info-3688359/)
  - [Tap to Share (Aug 2026)](https://propakistani.pk/2026/08/13/android-phones-officially-get-iphones-namedrop-like-tap-to-share/)
  - [Key Verifier](https://support.google.com/android/answer/15669061?hl=en)
- **Samsung.**
  - [One UI 8.5 automatic screening](https://www.androidpolice.com/samsung-galaxy-one-ui-8-5-automatic-call-screening/)
  - [One UI 9 Call Brief (Aug 2026)](https://www.androidauthority.com/one-ui-9-call-brief-international-calling-3708416/)
  - [One UI 7 contacts management](https://sammyguru.com/samsung-revamps-contacts-management-in-one-ui-7-update/)
  - [Profile card](https://www.samsung.com/in/support/mobile-devices/how-to-create-and-share-the-profile-card-in-galaxy-devices/)
  - [Recycle bin](https://ushl.samsung.com/au/support/mobile-devices/restore-from-recycling-bin)
- **Apple.**
  - [iOS 26 Phone (MacRumors)](https://www.macrumors.com/guide/ios-26-phone-app/)
  - [iOS 27 Phone (MacRumors)](https://www.macrumors.com/guide/ios-27-phone-facetime/)
  - [Call Context (9to5Mac, Sep 2026)](https://9to5mac.com/2026/09/15/ios-27-adds-new-phone-app-feature-thats-an-instant-favorite/)
  - [Posters remain niche (TidBITS, Nov 2025)](https://tidbits.com/2025/11/24/do-you-use-it-contact-posters-remain-a-niche-feature/)
  - [PIRG on iOS 26 screening](https://pirg.org/edfund/articles/call-screening-in-iphone-ios26-nice-effort-to-combat-robocalls-but/)
- **Truecaller and Hiya.**
  - [Family Protection](https://www.techcrunch.com/2025/12/09/truecaller-now-lets-users-protect-households-from-scam-calls/)
  - [Remote hang-up](https://techcrunch.com/2026/03/12/truecallers-now-lets-you-hang-up-on-scammers-on-behalf-of-your-family/)
  - [Hiya deepfake assistant](https://www.hiya.com/newsroom/press-releases/hiya-launches-first-ai-call-assistant-that-stops-live-and-deepfake-scams-in-real-time)
- **FOSS.**
  - [Fossify Phone releases](https://github.com/FossifyOrg/Phone/releases)
  - [Fossify Phone issues by votes](https://github.com/FossifyOrg/Phone/issues?q=is%3Aissue+is%3Aopen+sort%3Areactions-%2B1-desc)
  - [Fossify Contacts issues](https://github.com/FossifyOrg/Contacts/issues?q=is%3Aissue+is%3Aopen+sort%3Areactions-%2B1-desc)
  - [Koler on IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.chooloo.www.koler?repo=main)
- **Standards and platform.**
  - [RFC 9554](https://www.rfc-editor.org/rfc/rfc9554.html)
  - [Android contacts storage locations](https://developer.android.com/identity/providers/contacts-provider/contacts-storage-locations)
  - [expo#44626](https://github.com/expo/expo/issues/44626)
  - [Android 17 contact picker](https://www.androidauthority.com/android-17-contact-picker-explained-3652035/)
- **Privacy.**
  - [Privacy Guides criteria](https://www.privacyguides.org/en/about/criteria/)
  - [Obtaining apps](https://www.privacyguides.org/en/android/obtaining-apps/)
  - [F-Droid Anti-Features](https://f-droid.org/en/docs/Anti-Features/)
  - [F-Droid in 2025](https://f-droid.org/en/2026/01/23/fdroid-in-2025-strengthening-our-foundations-in-a-changing-mobile-landscape.html)
  - [OWASP MASVS](https://mas.owasp.org/MASVS/)
  - [Exodus Privacy](https://reports.exodus-privacy.eu.org/)
  - [GrapheneOS features](https://grapheneos.org/features)
  - [EFF SSD](https://ssd.eff.org/)

**Confidence.**
- **High** for the Parley inventory, which was read from the code.
- **High** for competitor features with dated sources.
- **Medium** for B1, which was confirmed in Parley's code but not reproduced on a device.
- Items marked **(?)** are unconfirmed.

---

## 11. Build status (5.3.0, 3 Oct 2026)

| Phase | Release | Status |
|---|---|---|
| A. Correctness | 5.0.1 | Done: Android 16 default account, department kept and editable, `ACTION_EDIT`, video calls answered as voice, logs and crash reports scrubbed, Caller on the lock screen, full Recents legend (owner correction) |
| B. Trust and release | — | Deferred by the owner until the app is polished |
| C. Simplify | 5.1.0 | Done: one Tools hub, one Reminders page, Calls settings split, Sort by / Show names as, Who's in… chip, people concepts, settings budget |
| D. Polish | 5.2.0 | Done: ringing frame, emphasised type, Poster background, Frame photo + Take photo, Favourites widget, Recents Cards |
| E. Features | 5.3.0 | Done except call transfer (not available to third-party apps in Android 16) and the two skipped items (NFC exchange, video calls) |
| F. Quality | 5.1–5.3 | Done: telecom and app tests, Compose smoke tests, parser fuzzing, file splits, detekt baseline 1,781 → about 975, instrumented smoke tests (need a device) |

Every phase had an independent review; all findings were fixed before release (5.0.1: 18, 5.1: 13, 5.2: 12, 5.3: 18). None of the device steps in TESTING.md §30–33 has been run on a real phone yet.
