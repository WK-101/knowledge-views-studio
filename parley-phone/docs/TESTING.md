# Device test checklist

Real telephony can't be emulated in the build environment. Please run this checklist on each phone and report the results:

- make, model and Android version
- which checks pass (✅) and which fail (❌), with a short note or screenshot for failures

## 0. Install and set up

- [ ] Install `parley-<version>.apk` (allow "install unknown apps" for your file manager or browser).
- [ ] Open Parley → **Set as default phone app** → confirm in the system dialog.
- [ ] Settings → **Privacy dashboard** says "No internet access".
- [ ] Settings → no warning about full-screen calls. If there is one, tap it and allow.
- [ ] Xiaomi/Redmi/POCO: follow the Xiaomi row in Settings (lock screen and pop-up permissions).

## 1. Incoming calls (most important)

Call the phone from another phone in each state below. For each one, check that it rings, that you can answer, and that audio works in both directions.

- [ ] Phone unlocked, Parley open
- [ ] Phone unlocked, another app open → heads-up notification with Answer and Decline
- [ ] Screen off and locked → full-screen call screen over the lock screen
- [ ] Slide right to answer, slide left to decline (Settings → Calls → Answer by: **Swipe**)
- [ ] Tap Answer and Decline buttons (Settings → Answer by: **Tap**)
- [ ] Reply with message → caller receives the SMS
- [ ] Missed call → Parley's "Missed call" notification → **Call back** works; the Recents badge clears after opening Recents
- [ ] Contact with a custom ringtone rings with that ringtone
- [ ] Do Not Disturb on → behaves like your system settings

## 2. During a call

- [ ] Mute and unmute (the other side confirms)
- [ ] Keypad tones work (e.g. call a voicemail or IVR menu and press digits)
- [ ] Speaker on and off
- [ ] Bluetooth headset: audio goes to the headset, and you can switch to the phone and back in the Audio sheet. Note if it needs two taps.
- [ ] Wired headset, if you have one
- [ ] Screen turns off when the phone is at your ear, and back on when you move it away
- [ ] Hold and resume
- [ ] Notification shows the timer, Mute, Speaker and Hang up, and all of them work
- [ ] Leave the call screen (Home), then return through the notification and through the green banner in Parley

## 3. Several calls

- [ ] Call waiting: receive a second call during a call → Answer (first call goes on hold) → Swap → end both
- [ ] "End current call and answer"
- [ ] Add call → dial a second number → **Merge** → Manage conference → end one participant, or make it private if the carrier allows
- [ ] **Three calls, no conference**: one active, one held, a third one waiting → decline the waiting call; the active and held calls stay as they were, and the held one can still be resumed
- [ ] **Swap across two SIMs**: a call on SIM 1, then a call on SIM 2 (answer the second, or add a call and pick the other SIM) → Swap both ways; each call keeps its own SIM label, and ending one resumes the other

## 4. Outgoing and dialer

- [ ] Dial from the keypad, from Recents, from a contact and from Favorites
- [ ] T9: type the digits for part of a name (e.g. `5646` for "John") → the contact appears with the match highlighted
- [ ] Long-press 0 gives `+`; long-press 1 calls voicemail; long-press 2 prompts to set up speed dial
- [ ] USSD code (e.g. your carrier's balance code such as `*100#`) → result shown
- [ ] `*#*#4636#*#*` opens the testing menu, on phones that have it
- [ ] Dual SIM: two call buttons appear; each uses the right SIM; the "Always SIM 2" setting on a contact's number is respected
- [ ] Tapping a `tel:` link in a browser or another app opens the Parley keypad with the number filled in
- [ ] **Emergency:** do NOT place a real emergency call. Only check that typing `112` or `911` shows no block and no warning. Parley always hands emergency calls to the system.

## 5. Contacts

- [ ] Contact list loads quickly (note the rough number of contacts and how long it takes)
- [ ] Create a contact in each account (device, Google) with a photo, two numbers, an e-mail, an address and a birthday → it shows up in Google Contacts or on the web after sync
- [ ] Edit, then delete a field and save → the change is correct
- [ ] Star or unstar → Favorites updates
- [ ] Export all to .vcf → import that file into a device account → contacts appear
- [ ] Find & merge duplicates → Merge → a single contact remains; menu → "Separate linked contacts" undoes it
- [ ] QR code scans with another phone's camera
- [ ] Share contact as a file

## 6. Blocking

- [ ] Block a number from Recents → call from it → rejected, and it appears in "Recently blocked"
- [ ] Prefix rule (e.g. the first digits of a test phone) → blocked
- [ ] Block private numbers → call with the caller ID hidden (usually `#31#` before the number on most networks) → blocked
- [ ] Silence action → the phone doesn't ring, and a quiet notification lets you answer
- [ ] Contacts are never blocked by "Block numbers not in contacts"

## 7. Look and feel

- [ ] Light, dark and pure-black themes; wallpaper colours on and off
- [ ] Large font (system font size at maximum) → nothing is cut off on the call screen or keypad
- [ ] TalkBack: the incoming call can be answered using TalkBack actions ("Answer" and "Decline")

## 8. v2.0 features

**Other apps**
- [ ] From WhatsApp, Signal or a browser: share a contact / "add to contacts" / pick a contact → Parley opens.
- [ ] Open a `.vcf` file from a file manager → Parley asks which account to import into and shows a report.

**Never lose a contact**
- [ ] Delete a contact → tap **Undo** in the snackbar → it's back. Delete another, then restore it from ⋮ → Recently deleted.
- [ ] Backup: Settings → Backup & restore → set a passphrase (write down the recovery key) → choose a folder → Back up now. Check that a `.parley` file appears in the folder.
- [ ] Restore that file with the passphrase and again with the recovery key (Merge mode). Nothing should be duplicated.
- [ ] Move to a new phone: send the file to a second phone, install Parley there, Backup → Restore from a file.
- [ ] Time machine: edit a contact, then on the next day open ⋮ → Version history and restore the earlier version. Settings → What changed.
- [ ] Folder sync with Syncthing between two phones: edit a contact on phone A → it updates on phone B; delete on B → gone on A (and in Recently deleted).

**Privacy**
- [ ] App lock on, then leave the app and come back → locked. Incoming call while locked → the call screen still shows the name.
- [ ] Move a contact to the private vault → it disappears from other apps (e.g. WhatsApp's contact list). Call from it → Parley shows the name. The call then disappears from the system call log and appears in Parley with a 🔒.
- [ ] Quick Settings tile "Private contacts" hides and shows private contacts.
- [ ] Encrypted QR: share a contact privately and scan it with another phone's camera → it opens Parley and asks for the passcode.

**Calls**
- [ ] Incoming call from a contact with a pinned note → the note and "last call …" show on the call screen.
- [ ] Unknown caller → "Not in your contacts · <city/country>"; with an unknown-caller ringtone set, that ringtone plays.
- [ ] Ignore → ringing stops, the call keeps waiting silently, and you can still answer it from the notification.
- [ ] With "Block numbers not in contacts" on, call twice within 3 minutes from an unknown number → the second call rings.

**Other**
- [ ] Birthdays screen, and a birthday notification at the chosen hour.
- [ ] Add to home screen (call, message, open) and the direct-dial widget.
- [ ] "Call with Signal/WhatsApp" rows on a contact that uses those apps; long-press to make one the default Call action.
- [ ] Contact health check → Fix all (country codes).
- [ ] Tablet/foldable or landscape: navigation rail on the side.

## 9. v3.0 features

**Calls**
- [ ] Call waiting on a real network: Hold & answer, End & answer, Decline; the held call resumes when the other ends (and doesn't resume twice).
- [ ] Tap the on-hold strip only swaps when the active call can be held.
- [ ] Leave the call screen → "Return to call" chip on Home; the notification countdown (with a limit set) runs without flicker and its "+5 min" / "Don't end" work.
- [ ] Call-length limit with the screen off: warning beep in the earpiece, then only that call ends — even with a second call ringing or held. Call an emergency number and let it call back: never limited.
- [ ] Daily allowance used up → outgoing asks "Call anyway?" once (single dialog, also with dial warnings); reboot doesn't reset it.
- [ ] USSD `*100#`-style code on each SIM → reply dialog; failure → "Dial as a call".
- [ ] Quick Settings "End call" tile ends the active call from the lock screen.
- [ ] Notification health card on Android 14+ (full-screen permission) and its fix buttons. TalkBack on incoming, in-call and keypad.

**Blocking**
- [ ] Blocking screen status card in phone-app vs screening-only mode.
- [ ] Two SIMs: an allow rule for one SIM lets its callers through even with "block non-contacts" on.
- [ ] Label rules ("only Family rings at night") after a reboot, and after restoring a backup on another phone.
- [ ] "Ring loud" for a favourite, switch to vibrate mid-ring → volume back to normal on the next call.
- [ ] One-ring foreign missed call → "Don't call back" badge; calling back asks first.
- [ ] Spam lists: import ARCEP, subscribe a folder, then install Parley Lists and subscribe to the FTC list; a list signed by another key asks "Replace?".
- [ ] Templates: dry run, install, uninstall restores earlier settings; share by QR to another phone.
- [ ] "Expecting a call" tile asks to unlock first.

**Keypad & messaging**
- [ ] Keypad letters for your alphabet; font size 200 % still fits; Chinese/Korean/kana names found.
- [ ] Physical keyboard / flip phone: digits, Call, D-pad through results.
- [ ] "Message on…" opens WhatsApp, Signal, Telegram, Viber directly for a local-format unknown number (right country); select a number in any app → "Call / Message with Parley".
- [ ] Paste chip shows no "pasted from clipboard" toast.

**History**
- [ ] Archive keeps calls older than what the system log shows; "Keep forever"; delete a date range and undo.
- [ ] Export CSV/ICS/PDF and open them in a spreadsheet, calendar and PDF viewer; print.
- [ ] Import a Logger CSV (dry run, no duplicates, no missed-call badge).
- [ ] Plan meter warning at 80 % on the right SIM.

**Contacts & privacy**
- [ ] SIM import/copy on Android 10–11 and 12+.
- [ ] Move a contact between Google and another account (photo, labels, undo).
- [ ] Favourites drag and pinch; label page actions and label ringtone.
- [ ] Who can see your contacts list (and GrapheneOS pointer on GrapheneOS).
- [ ] Private-name lookup from a test app: permission, approval notification, access log.
- [ ] Diagnostics export contains no numbers.

## 10. Round-4 safety fixes (v3.1)

**Notifications**
- [ ] **Android 17 on a Pixel**: incoming (heads-up and full-screen over the lock screen), ongoing (chronometer, Mute/Speaker/Hang up) and missed-call notifications all appear and their buttons work (Fossify #854)
- [ ] Swipe away the ongoing-call notification (Android 14+) → it comes straight back while the call lasts; after hanging up nothing comes back (F6)
- [ ] Lock screen with “Hide sensitive content”: a missed call from a private contact shows only “Missed call”; with “Hide private contacts” on, the private name never appears, even unlocked (F14)
- [ ] Incoming call from a private contact: the notification never shows “Private”, on or off the lock screen (F14)

**Screening and ringing**
- [ ] **OEM screening on one SIM**: with the carrier's or OEM's screening service rejecting every call on one SIM (Fossify #456), calls on the other SIM still ring normally, and the rejected ones don't leave Parley's call screen or notifications behind
- [ ] Unknown-caller ringtone: Telecom's ringtone never plays at the same time; the phone vibrates when “Vibrate for calls” is on, and not when it's off (F20)
- [ ] Keypad tones: silent in silent and vibrate mode; turning Android's “Dial pad tones” off takes effect without restarting Parley (F22)

**Messaging and privacy**
- [ ] “Chat, then decide” and “Save as a temporary contact” save privately by default (not visible in Google Contacts or WhatsApp); “Save visible to other apps” saves a phone-only contact; both delete themselves after 7 days (F5)
- [ ] “Message on…” from Recents for a call on a foreign SIM uses that SIM's country; the country chip changes it; a too-short number shows a disabled row with a reason (F19)
- [ ] After the first WhatsApp chat: the one-time “WhatsApp may ask to sync your contacts” note (F30)
- [ ] Privacy dashboard: “Keep a record of numbers you message” shows the count; delete one number, Clear all, turn it off (F13)
- [ ] Private contact with a French number; a call from a Spanish number with the same last 9 digits is **not** shown with the private name (F7)
- [ ] Recents update live after granting call-log access later from Android Settings (F29)

## 11. UI refresh (header, Settings, navigation bar)

**Status bar and header**
- [ ] Every home tab (Favorites, Recents, Contacts, Keypad) starts below the status bar and the camera cutout, in portrait and landscape, with gesture and 3-button navigation.
- [ ] Status-bar and navigation-bar icons are dark in Parley's light theme and light in its dark theme, also when Parley's theme differs from the phone's (Settings › Appearance › Theme).
- [ ] Tablet / unfolded / landscape: the navigation rail starts below the header and nothing hides behind the system bars.
- [ ] Each tab shows its title, the search icon, its own actions and "More options"; the bar tints when a list scrolls under it.
- [ ] Search icon → the field gets the keyboard; typing filters (Favorites: favourites and frequent; Recents; Contacts; Keypad: all contacts); ✕ clears, ✕ on an empty field or Back closes; switching tabs closes it; rotation keeps it.
- [ ] The Contacts multi-select bar also sits below the status bar.
- [ ] With app lock on: "Lock now" (Contacts header and every overflow menu) shows the lock screen without an automatic fingerprint prompt; Unlock works.

**Settings**
- [ ] Settings shows categories with icons and summaries; each opens its page with grouped cards; the large title collapses on scroll.
- [ ] Search "dark", "vibration", "spam", "voicemail", "backup", "tabs": results show their category; tapping one opens the page, scrolls to the setting and briefly highlights it.
- [ ] Android's App info › Parley › gear (App settings) opens Parley's Settings.
- [ ] Every 3.0 setting is still reachable (README › "Where things are").

**Navigation bar**
- [ ] Settings › Appearance › Navigation bar: hide tabs (the last one can't be hidden), drag to reorder, TalkBack "Move up" / "Move down"; the bottom bar and the rail follow; "Open on" lists only visible tabs.
- [ ] Hide Keypad, then open a tel: link or press Call on a headset: the Keypad opens and shows in the bar until you switch tabs. Hide Recents, tap a missed-call notification: Recents opens the same way.

**Temporary contacts**
- [ ] Type an unknown number on the keypad: chips Message / Add to contacts / Save temporary contact / Add to existing contact; a saved number only shows Message.
- [ ] Save temporary contact: name, 1 / 7 / 30 days or custom, "Also delete its call history", private by default ("Save visible to other apps" makes a phone contact); it appears under the Contacts chip "Temporary (n)", in the overflow menu and in Settings › Contacts › Temporary contacts with the time left (private ones with a lock, opening their private page); Extend, Keep permanently and Delete now work (Delete now can be undone from Recently deleted).

## 12. Messaging round (v3.1: M8, M10–M13)

**Message a number (M8)**
- [ ] Add the "Message a number" Quick Settings tile. Unlocked: tap it; the sheet opens with an empty field and the keyboard, a Paste chip and the SIM's country. Locked: tapping asks to unlock first; nothing (numbers, history, names) shows before that.
- [ ] Long-press Parley's launcher icon: "Message a number" is there next to New contact and favourites; it opens the same sheet.
- [ ] Nothing reads the clipboard until Paste is tapped (Android 12+ shows its "pasted from your clipboard" toast only then). One number: it fills the field. Several: the pick list with "Save all N numbers…".
- [ ] Type a national number: the messenger rows appear once it's complete; change the country chip and the international form follows.

**Messaged numbers (M10)**
- [ ] Privacy dashboard › Messaged numbers, Settings › Messaging › Messaged numbers and Recents ⋮ › Messaged numbers open the same list: delete one, Clear all (with confirmation), tap one for its history.
- [ ] "Keep a record" off clears the list and nothing new is added. "Forget messaged numbers after 7 days" removes older entries at once; with call-history retention set, the shorter one wins.

**Add several numbers (M11)**
- [ ] Contacts ⋮ › Add several numbers…: paste a text with numbers (one a contact, one private, one repeated, one broken). Each shows its status; only new ones are ticked; repeats can't be ticked.
- [ ] Naming: "Contact 01…", "Prefix · +92 300…", custom "{prefix} {n} {number}"; the first name is previewed.
- [ ] Save to a new label in a Google account: the label appears with the contacts (in that account only). Save privately: they are private contacts. Save as temporary for 1 day: they are in Temporary contacts.
- [ ] The snackbar "Undo this batch" removes exactly those contacts (not in Recently deleted). "Delete this batch" later from Recent batches removes them (restorable from Recently deleted, except private ones).
- [ ] Select text with several numbers in another app › Call / Message with Parley › "Save all…": Parley opens Add several numbers with the text.
- [ ] 200+ numbers save with a progress bar and without "transaction too large" errors.

**CSV with column mapping (M12)**
- [ ] Settings › Contacts › Import: a Google Contacts CSV, an Outlook CSV, "Name;Phone" with semicolons, a tab-separated file and a single column of numbers each open "Choose columns" with sensible guesses and a preview; changing a column updates the preview; Import shows the usual report.
- [ ] Parley's own CSV export still imports directly, without the mapping screen.

**Telegram profile and introductions (M13)**
- [ ] Message on… › Telegram row: long-press or ⋮ › Open profile opens the person's profile in Telegram (an old Telegram opens the chat instead).
- [ ] After a bulk add, "Introduce myself…" (and Contacts multi-select ⋮ › Introduce myself…): choose WhatsApp; each chat opens with "Hi, this is …" filled in; press Send, come back, and the next person is shown ("2 of 5"). Skip, Next and Stop work; Signal/Viber copy the text for pasting. Nothing is ever sent without you pressing Send.

## 13. Calls, voicemail and notifications (v3.1, COMPETITIVE_ANALYSIS_4 §4.1)

**Voicemail inbox (V1)** (needs a carrier with visual voicemail, or Android's built-in one, and Parley as the default phone app)
- [ ] Recents shows a **Voicemail** chip with a badge of unheard messages; it's hidden when Parley isn't the default phone app.
- [ ] The chip lists voicemails already downloaded by the carrier's voicemail app or Android, newest first, with name or number, time, length, SIM (two SIMs) and a dot for new ones; the note says Parley can't sync without internet.
- [ ] Tap a row: play/pause, seek with the slider, Speaker ↔ Earpiece while playing (earpiece plays at the ear, speaker out loud); playing marks it heard; "Mark as new" undoes it.
- [ ] A transcription (if the carrier provides one) shows under the row and in full when opened.
- [ ] Share → the chooser offers the audio file (e.g. `voicemail-2026-09-24-1432.amr`); it plays in the receiving app.
- [ ] Delete → confirm → the row goes; after the voicemail app syncs, it's gone from the carrier mailbox too (if the carrier supports it).
- [ ] A voicemail whose audio isn't downloaded yet shows "not downloaded" and a Download chip (asks the voicemail app; nothing happens without mobile data).
- [ ] "Call voicemail" dials voicemail; "Voicemail settings" opens Android's voicemail settings; "Carrier voicemail app" appears when the carrier app publishes a settings page.
- [ ] During a call, playing a voicemail says "Can't play during a call"; after playing on the earpiece, the next call's audio routing is normal.
- [ ] Long-press 1 on the keypad still calls voicemail.

**Missed-call notification (V2)**
- [ ] One missed call from a contact: contact photo, name, "Missed call · 14:32", the SIM label on dual-SIM phones; Call back and Message on… (no Block for contacts).
- [ ] Two calls from the same number: one notification, "2 missed calls · last 14:35".
- [ ] Calls from three people: a group ("5 missed calls from 3 callers") with one notification per caller, each with its own actions; only the newest makes a sound. Swiping all of them away clears the missed calls.
- [ ] A call silenced by an off-hours or silence rule says why ("Silenced: …"). A call while the phone was on silent or vibrate or in Do Not Disturb: "Didn't ring: phone on silent" / "Vibrate only: phone on vibrate" / "Didn't ring: Do Not Disturb".
- [ ] Unknown number: Block asks to unlock first (Android 12+) and then blocks; on Android 10–11 it opens the rule editor.
- [ ] Lock screen with hidden sensitive content: only "Missed call" / "n missed calls"; with "Hide private contacts" on, a private contact's name never shows (F14).

**Re-alert (V3)**
- [ ] Settings › Calls › Remind me of missed calls is Off by default. Set 5 minutes, miss a call, leave the phone: it alerts again about every 5 minutes (inexact: it may drift a minute or two), for up to 3 hours.
- [ ] With Do Not Disturb on there's no re-alert; after turning it off, the next interval alerts again.
- [ ] Opening Recents, tapping the notification or dismissing it stops the re-alerts; so does turning the setting Off.

**Post-call card (V4)**
- [ ] After a call with a number that isn't in contacts (either direction), the call-ended screen shows "Not in your contacts" with Block, Save privately, Message on…, Report and Done. It stays up about 8 s, and for good once touched.
- [ ] Block → unlock → the rule editor opens with the number filled in. Save privately → name → "Saved privately. Deletes itself in 7 days." and it's under Temporary contacts with a lock. Message on… opens the chat-app sheet. Report opens the report dialog.
- [ ] No card after a call with a contact, a private contact, a hidden number or an emergency number.

**SIM on the answer control (V5)**
- [ ] Dual SIM: the answer slider says "on Work · …4567" (the SIM's number only when Android knows it); with tap-to-answer, a SIM tag shows under Answer; TalkBack reads "Answer on …". Single SIM: nothing extra.

**Proximity sensor switch (V6)**
- [ ] Settings › Calls › Turn the screen off at your ear: Off → the screen stays on when covered during an earpiece call; On → it turns off again, also mid-call.

**Keypad touch (V7)**
- [ ] The digit and its tone come on touch, not on release; a very short tap still gives a short tone; holding a key holds the tone.
- [ ] Long-press 0 gives "+", 1 calls voicemail, 2–9 speed dial, * a pause, # a wait (the digit typed on touch is replaced). Sliding the finger off the key before the long-press time cancels it.
- [ ] Press a second key before lifting the first (roll-over): both digits are typed, in order.
- [ ] In a call, the in-call keypad's DTMF tone lasts as long as the key is held (try a phone menu that wants a long press).
- [ ] Silent or vibrate mode: no keypad tones (F22 still holds).

**Pocket-dial guard (V8)**
- [ ] Cover the proximity sensor and tap a favourite: a "Phone covered" warning before calling. Uncovered: it calls straight away (unless something else asks).
- [ ] Same with the direct-dial widget and a contact shortcut (a dialog over the home screen). With "Ask before pocket calls" off, nothing is asked.

**Why did my phone ring, or not? (V9)**
- [ ] After a few incoming calls (normal, silent mode, Do Not Disturb, answered on a Bluetooth car kit, answered on another device), the number's history shows "Why it rang, or didn't"; each row opens to Do Not Disturb, ringer and volume, vibrate, the ringtone (default, the contact's own, a label or rule tone, the unknown-caller tone) and where it was answered.
- [ ] The same facts show under the trace in "Why did this ring?" and in the blocked log's detail for silenced calls.

**Power button ends call (V10)**
- [ ] Settings › Calls › Power button ends call opens Android's Accessibility settings; the row shows On or Off when the phone lets Parley read it.

**Recents (V11)**
- [ ] With a missed call, open Parley on Recents: the status-bar missed-call icon goes away; coming back to Recents later clears new ones too.
- [ ] With a large call log (thousands of calls), Recents shows the newest calls almost at once and the rest (with the archive) a moment later.

## 14. People (v3.1: M6, M7, I1–I9, U1–U11)

**Messaging a saved or private contact (M6, M7)**
- [ ] Contact page › Message tile with no choice yet: the "Message … on…" sheet lists installed messengers and SMS; with WhatsApp installed but not linked to the person, its row says "WhatsApp can't see your contacts; you can still message them" and opens the chat by number, in WhatsApp directly (no browser).
- [ ] Keep "Always use this" ticked: the tile now shows that app's name and one tap opens it; long-press the tile (or a phone row › long-press › Message on…) to choose again. The chat icon on each phone row messages that number the same way.
- [ ] Contacts list with "Call & message buttons" on: the message button uses the remembered choice, or asks once.
- [ ] Private contact page: the Message tile and the chat icon on numbers open the same sheet (no messenger rows: no other app can see the contact); the choice is remembered once the contact is unlocked.

**Contact data (I1–I6, I8, I9)**
- [ ] Editor › More fields › Messenger handle: Matrix `@name:matrix.org`, Threema ID, Telegram username, Signal username, XMPP and SIP; a malformed value shows a hint. Save and reopen: the handles are listed under Messengers; phones, e-mails, the rows WhatsApp/Signal added and any read-only rows are unchanged (compare with another contacts app).
- [ ] Tap a Telegram handle: Telegram opens the profile; Threema opens its compose screen; a Matrix handle with Element installed opens Element, without it Parley asks before opening a browser.
- [ ] A contact with two numbers: long-press one › Set as default: a star appears and the Call tile uses it; Remove default clears it. Same for two e-mail addresses.
- [ ] A Google contact with "File as" or a custom field: the page shows it under "Other fields" (read-only).
- [ ] Editor › Relations: the relation button offers the vCard 4.0 list (search "grand", "co-worker"), the person icon picks a contact; tapping the relation on the page opens that contact, also after renaming it.
- [ ] Private contact: the editor has a photo (system photo picker, no permission prompt), "Who is this?" and "Note for calls". A call from that number shows the photo, job/company and the "who is this" line on the call screen, also while the phone is locked; a missed call shows the line in the notification, but not with "Hide private contacts" on, and never in the lock screen's public version.
- [ ] Contacts search "paris" (an address), a word from a note, a company, a website or a handle: the contact is listed with "Matched: address" (etc.) instead of its second line. The keypad search is unchanged.
- [ ] With a work profile whose policy allows caller ID: a call from a work contact shows the name with "Work profile"; without a work profile nothing changes.

**My card (I2)**
- [ ] Contacts starts with "My card"; the old "My details" name and number are already in it. Add numbers, e-mail, company; Save. Settings › Messaging › My card opens the same screen; "Send my details" in Message on… uses the card's name and first number.
- [ ] QR code: choose parts, scan with another phone's camera: the contact has only those parts. Share: a .vcf goes to the chosen app. The private note is never included.
- [ ] If the phone has a profile ("Me" in another contacts app), its details fill what's empty and the screen says so; Parley doesn't change the profile.

**Private names in other phone apps (I7)**
- [ ] Settings › Privacy › Private names in other phone apps: off by default. Turn it on: Android lists "Parley private contacts" among contact directories (`adb shell content query --uri content://com.android.contacts/directories`).
- [ ] From a test app (or a phone app that queries directories), look up a private number with `PhoneLookup.CONTENT_FILTER_URI` and `?directory=<id>`: the first time, a notification asks to allow that app; after Allow the name comes back; a prefix, a partial number or any list query returns nothing; the access log marks each request "(directory)". Discreet mode: nothing. Turn it off: the directory disappears.

**Look and feel (U1–U7, U10, U11)**
- [ ] Contact page: scrolling docks the photo and name into the top bar with "Last talked…"; the avatar still animates in from the list; sections are grouped cards; tiles read Call / Message / Video (when a messenger offers video; long-press to choose) / Email.
- [ ] Editor: grouped cards, a red "−" to remove, a green "+ Add …" at the end of each group, "More fields" chips reveal a section in place.
- [ ] Settings › Appearance › Swipe actions: off by default; turn on, choose actions, try the preview row. On Contacts and Recents a right swipe calls and a left swipe messages; Delete shows Undo (Recents: the calls come back; Contacts: the contact comes back).
- [ ] Settings › Appearance › Avatars: grey monogram; a contact named "🐶 Rex" shows the dog; a company-only contact shows a building in the list too.
- [ ] Contacts fast-scroll letters are larger when there are few; Blocking, Birthdays, Recently deleted, Health, Duplicates and Private names tint their top bar on scroll; Blocking sections are grouped cards.
- [ ] Settings › About › Keep crash reports on; force a crash (debug build); the next start offers the report with numbers masked; Send… opens e-mail or any app; Delete report removes it. Diagnostics › "Include the contacts tables (masked)" adds raw rows whose values show only their shape.
- [ ] Contacts › select several › ⋮ › Copy as text: paste elsewhere; on Android 13+ the clipboard preview hides the content.

## 15. v3.2 (COMPETITIVE_ANALYSIS_5)

### 15.1 Phone (P1–P9, G3)

**Picture-in-picture (P1)**
- [ ] Android 12+: during a call (or while dialling) press Home: the call shrinks to a PiP window with the caller's photo and name, the timer and, while muted, a red "Muted" tag. Its actions Mute/Unmute and Hang up work; the Mute icon follows the state (also after muting from the notification).
- [ ] Android 10/11: the same happens when you press Home or open Recents (manual entry).
- [ ] No PiP while a call is ringing (incoming or call waiting), while the SIM picker shows, or while the screen is off at your ear (proximity).
- [ ] Tap the PiP window: the full call screen comes back. Swipe it away: the call goes on; the notification and the "Return to call" chip in Parley lead back.
- [ ] During a call tap Add call: Parley's keypad opens, no PiP window, and opening Parley never bounces back to the call screen. Dial a second number: the call screen shows both calls. Same for tapping the caller's photo (contact page).
- [ ] The call ends while in PiP: "Call ended" shows briefly, then the window closes.

**Hide screen content on the call screen (G3/P3)**
- [ ] Settings › Privacy › Hide screen content on. During a call take a screenshot: Android refuses; the recent-apps thumbnail of the call is blank; screen casting shows black. Turn it off: screenshots work again (the next time the call screen resumes).

**Block & decline (P2)**
- [ ] Incoming call from a number: ⋮ next to "Ignore" › Block & decline. The ringing stops at once, the call is declined, and the call-ended screen says "Blocked and declined" with Undo. Blocking & spam lists a rule for the number with the note "Blocked during a call"; a new call from it is declined.
- [ ] Tap Undo: the card says "Unblocked…", the rule is gone, a new call rings.
- [ ] A number that was already blocked with a silence rule: the card says it was already blocked, no Undo. Private numbers and emergency call-backs never offer ⋮. TalkBack: the caller's name offers "Block & decline" as an action.
- [ ] Right after tapping Block & decline the answer controls are replaced by "Blocking this number…" and the notification goes; answering from the notification or the screen isn't possible. On a slow first write (just after boot) the card may say "Blocking this number…" and then "Blocked and declined" with Undo; it never says "couldn't be blocked" for a number that ends up blocked, and the next call from it is declined.
- [ ] While dialling a second call, Block & decline the incoming one: the "Blocked · Undo" card shows above the call that goes on; Done hides it, Undo works.
- [ ] Answer with a headset button during the write: the call connects, and the card later says the number is blocked and the call was answered (not "declined").

**Default-dialer rescue (P4)**
- [ ] On a phone where Parley isn't the phone app: Settings (banner) or Settings › Calls › Set default. Press Cancel on Android's dialog: no guide.
- [ ] Decline twice with "Don't ask again" (or use a ROM that refuses sideloaded apps): the next request comes back at once and the guide opens with the steps for this Android version (10/11: Apps & notifications; 12+: Apps; 13+: also "Allow restricted settings"). App info and Default apps open the system screens.
- [ ] Settings › Calls › "Can't make Parley the default phone app?" (only while it isn't) opens the same guide. Settings search "restricted" finds it.

**Clear call history (P5)**
- [ ] Recents ⋮ › Clear call history…: choose "Calls from numbers not in your contacts" (counts shown), Next, Export first? › CSV file: the share sheet offers the CSV; then confirm Delete: only those calls go; the snackbar's Undo brings them back. Contacts' calls and private-contact calls stay.
- [ ] Deny Contacts (or open Settings › Clear call history right after a cold start): "Calls from numbers not in your contacts" is greyed out with "Needs access to your contacts…"; nothing from contacts can be cleared that way.
- [ ] Call a private contact, then at once Clear › All (or Missed): that call isn't cleared and later appears in the private contact's history, never in the ordinary Recently deleted.
- [ ] With a Recents filter or search active, "What Recents shows now" is offered first and clears exactly those rows. "Encrypted backup" opens Backup & restore. Settings › Recents & history › Clear call history works the same (without "What Recents shows now").

**Call failure banner (P6)**
- [ ] Airplane mode on, call a number: the call screen keeps "Call didn't go through · Airplane mode is on" with Retry and Dismiss; it doesn't close by itself. The caller's name (a contact) stays on the screen.
- [ ] Dual SIM with "Ask every time": when the network refuses a call that was still at the SIM picker: "No SIM was chosen". Cancel on the picker yourself (or with the power button): no banner.
- [ ] A number that fails on the network: the network's own reason, or "The network couldn't connect the call". Retry places the call again on the same SIM; Dismiss closes the screen. A busy line shows "Busy" with Retry. Hanging up yourself, or the other person declining, never shows the banner.
- [ ] During a call, Add call to a number that fails: the banner shows above the call that goes on.
- [ ] Dial and end the call with the power button ("Power button ends call"), a Bluetooth headset, a car kit or a watch before it's answered: no banner, the screen closes as usual.
- [ ] After a failure, Dismiss (or leave with Home). Later a call comes in, or dial someone else: the ringing/dialling screen never shows the old banner or its Retry.

**Haptics (P7)**
- [ ] With Vibrate on call events on: answering buzzes short-then-longer, declining (button, slide, notification, Block & decline) one firm buzz; no extra connect buzz right after answering.
- [ ] An outgoing call buzzes once when it's answered; Settings › Calls › Vibrate when a call connects off: no buzz on connect, answer/decline still buzz. With Vibrate on call events off, nothing buzzes and the connect switch is greyed out. Silent mode: nothing buzzes.

**Call list layout (P8)**
- [ ] Recents ⋮ › Layout: Grouped / Every call / By day. Grouped: calls in a row from one number share a row (as before). Every call: one row each. By day: one row per number per day even when other calls came in between (the count shows). Day headers stay. The choice is also in Settings › Recents & history › Call list layout and survives a restart.

**Regression checks (P9)** — unit tests in `core/common/.../calls/DialTargetTest.kt`; on the device:
- [ ] Keypad: `*#06#` shows the IMEI; `*100#` sends a USSD request; `**21*+4915112345678#` (paste it) keeps the `+` and `#` and goes to the network as a forwarding code; `#31#0612345678` calls with the number hidden.
- [ ] Type `555` while a contact "+1 555 0100" is the top match: Call dials 555. Typing a name on a hardware keyboard calls the top match.
- [ ] During a call, a second call rings with the call-waiting sheet (ringtone/tone, Answer, Hold & answer, End & answer).
- [ ] Dial from the keypad and from Recents with a label SIM (X3) on a large contacts list: no stutter at the moment of dialling; StrictMode (developer build) reports no disk/content read on the main thread there; the call goes out on the label's SIM.

**Simple mode checks (X4)**
- [ ] "Speak caller name" on: a contact calls, the name is spoken; press volume-down or power: the ringer and the voice both stop.
- [ ] During a call, a contact calls in (call waiting): the name is never spoken.
- [ ] "Confirm before declining" on: Decline on the call-waiting sheet asks "Decline this call?" first. With the phone unlocked, tap Decline on the heads-up notification: the call screen opens with the same question (Keep ringing leaves the call ringing).

### 15.2 Distribution (D1–D4)
- [ ] `tools/fdroid-strip-check.sh --static` passes. The full run builds `app-release-unsigned.apk` and `lists-updater-release-unsigned.apk` with the signing config stripped as F-Droid does.
- [ ] With no keystore.properties and no PARLEY_KEYSTORE, `./gradlew :app:assembleRelease` finishes with an unsigned APK (no "SigningConfig not found").
- [ ] `tools/repro-check.sh` reports both APKs byte-identical. Record the result in docs/RELEASING.md §4.
- [ ] Signed release (keystore.properties present): `apksigner verify --print-certs` shows SHA-256 `f5349c31…104284a` for both APKs, and `tools/repro-check.sh --app phone --signed dist/Parley-3.2.0.apk` matches.
- [ ] Install signed Parley 3.2.0 over 3.1.0: it updates in place and keeps its data; Android's App info shows 3.2.0.
- [ ] Install signed Parley Lists 1.1.1 over 1.1.0: it updates in place, and Parley › Blocking › Spam lists still shows its packs (the signature permission is granted).
- [ ] If Parley Lists is signed with a different key (for example a debug build next to a release Parley), Parley shows none of its packs and doesn't crash.

### 15.3 Circle: interactions, "Log this?", kind reminders, dates (R1–R5, U4, G4–G6)

**Circle tab and Favourites (R1, U6)**
- [ ] Update from 3.1 with a customised bar (e.g. Keypad first, Favourites hidden): the bar is unchanged and the new Circle tab is *not* in it. Settings › Appearance › Navigation bar lists Circle, hidden. A fresh install also has it hidden.
- [ ] With the tab hidden, Favourites starts with "Your circle" (only once someone is in it, or suggestions exist). Tap the header: it folds away and stays folded after a restart.
- [ ] Empty Circle with call history: "Suggested from your calls" lists up to 10 most-called contacts with "N calls this year · Every N days"; Add puts them in the Circle (Undo on the snackbar removes them). Without call history: "Your circle is empty" and how to add someone.
- [ ] Show the Circle tab: people are sorted Due, Soon, Fine, each with "Last in touch 12 days ago · call" (or met / message / video call), Call and Message buttons. Search "zz": "No one in your circle matches “zz”" (never "Your circle is empty").
- [ ] Private (vault) contacts never appear in the Circle, the suggestions or the reminders, also with discreet mode off. Move a Circle contact into the vault: it leaves the Circle and its logged entries are gone.

**Contact page and timeline (U4, R2)**
- [ ] Order: header and action tiles, Stay in touch, Dates, numbers (e-mail, addresses, messengers, about), Timeline, call insights, pinned note, settings.
- [ ] Not in the Circle: Stay in touch says "Add to your circle"; ⋮ has "Log interaction"; no FAB. In the Circle: the card shows the rhythm, last in touch and the next date within 60 days with a Due/Soon/Fine chip, and an extended FAB "Log interaction".
- [ ] Log a meeting with a note; it appears in the Timeline under this month, with calls, call notes and birthdays of earlier months. Edit it (change only the note): its time stays. Change its day: the time of day stays. Delete: "Entry deleted" with Undo brings it back with the same time.
- [ ] Merge the contact with a duplicate (Find & merge): the logged entries and the rhythm follow the merged contact.
- [ ] Encrypted backup, restore on another phone (or after clearing data): Circle members, rhythms and entries come back, matched by number or name.

**"Log this?" (R3)**
- [ ] For a Circle contact, Message → WhatsApp (or Signal, Telegram, SMS, a video tile): nothing shows while you're in the other app; back in Parley a snackbar asks "Log as a message with Sam?" (video: "…a video call…"). Log → "Logged time with Sam" with Undo. Launching twice within 10 minutes records one entry.
- [ ] A contact outside the Circle: no question. A normal phone call: never a question (calls come from the call log).
- [ ] Settings › Contacts › Log messages you start: set WhatsApp to Always: back in Parley "Logged a message with Sam" with Undo; Never: nothing. Other channels keep asking.

**Kind reminders (R4, G4, G5, G6)**
- [ ] Default delivery is the weekly digest: set the device date to a Sunday (or wait) at the reminder hour: one notification "People you might like to hear from" with at most 3 lines (one due, one upcoming date, one quiet); tapping it opens the Circle. It never comes twice in a week; the next week's "quiet" person is someone else.
- [ ] Settings › Contacts › How keep-in-touch reminders arrive › As they come due, At most per week 2: with 3 people due, 2 notifications this week ("Sam might enjoy hearing from you", last in touch line), no counters. "Not now" removes it and Sam isn't due again until a full gap later.
- [ ] Log a video call (or answer a call) with someone due: they turn Fine and aren't in the next reminder (G6).
- [ ] Rhythm › Natural rhythm on someone with 5+ days of calls: the card shows "Natural rhythm · about every N days" (at least 7); with little history "still learning".
- [ ] Lock screen: every reminder shows only "Reminder" until unlocked (G4); nothing appears on a paired watch.

**Dates (R5, G5)**
- [ ] Settings › Contacts › Remind me before dates › Also 3 days before: a birthday 3 days away notifies "Sam has a birthday in 3 days" once (run the worker twice: still once), nothing the next two days, then "Sam has a birthday today".
- [ ] "Mark as wished" on the lead notification: it disappears, a "message" entry appears in Sam's timeline and nothing fires on the day.
- [ ] A contact with a birthday and an anniversary on the same day gets two notifications; contact 5's nudge and contact 10 005's birthday don't replace each other.

### 15.4 Layout, onboarding and backups (C1–C4, U1–U3, U5, U6)

**Contact photos (C1, G2)**
- [ ] Edit a contact › photo: pick a 50 MP camera photo, and a portrait one taken with the phone upright: no crash, the photo is upright and square, and the contact page shows it sharp (720 px display photo).
- [ ] Pick a HEIC photo (Pixel/Samsung "High efficiency"): it is saved like a JPEG.
- [ ] Import a .vcf that carries a large rotated photo: the contact gets an upright square photo. A private contact's photo (editor with "Private") is upright too.

**Back up first? (C2)**
- [ ] With no backup, or the last one older than 7 days: Settings › Contacts › Import a file with 20+ contacts, Contacts › select 5+ › Delete, select 2+ › Merge, Find & merge duplicates › Merge (asked once per visit), Tidy up › Delete all (5+ empty contacts) and opening a shared .vcf with 20+ cards each ask "Back up first?".
- [ ] Backups set up (passphrase and folder): "Back up now" makes a backup (toast), then the change goes ahead. Not set up: "Set up backup" opens Backup and nothing changes. "Continue without" goes ahead. Dismissing (outside tap or back) changes nothing.
- [ ] A backup made today: none of the above asks. A 3-contact import or a 4-contact delete never asks.

**Backup reminder (C3)**
- [ ] Settings › Backup & sync › Remind me to back up: 30 days (default) or 14 days; the same choice is in Backup. Settings search "backup reminder" finds it.
- [ ] With the last backup older than the threshold (or none, and installed longer ago than that): a card shows at the top of Settings, Settings › Backup & sync and Backup. "Not now" hides it for 7 days (set the clock forward a week: it's back). "Back up now" backs up and the card goes.
- [ ] Daily housekeeping run (or `adb shell cmd jobscheduler run -f app.parley <job id>`): one "Time for a backup?" notification, shown as "Backup" only on the lock screen and not mirrored to a watch; no second one within 30 days. Tapping it opens Backup. With notifications off nothing is sent (and the cards still show).

**Birthday slots (C4)**
- [ ] A contact without a birthday or anniversary shows "Add birthday?" / "Add anniversary?" chips above its About card. Pick a date (with or without year): the page shows the date, Google Contacts (or another contacts app) shows it, and Recently deleted has the edit to undo.
- [ ] A contact that has both shows no chips.

**Onboarding (U1)**
- [ ] Fresh install (clear data): Welcome › Get started › "Make Parley your phone app" › "What Parley can use" with Contacts, Call history, Phone, Notifications (13+) and Nearby devices (12+): each row says why, and what still works without it.
- [ ] "Allow all" asks for everything not yet granted in one go; a row's switch asks for that row only; a refused row then reads "Android won't ask again…" and its switch opens App info; a granted row's switch opens App info. Coming back from App info updates the switches.
- [ ] Setting Parley as the phone app still ends on the permissions page.
- [ ] Sideloaded (`adb install`, or opened from a file manager) on Android 13, 14, 15 and 16: the phone-app step shows "Installed from a file?" with the ⋮ › Allow restricted settings steps and "Open App info" before asking; if Android refuses, the card turns red. Installed from Play or F-Droid: no card.

**Tips (U2)**
- [ ] After onboarding: a bubble under the header search icon; "Got it" hides it for good. Keypad with nothing typed: a speed-dial tip with "Set up". Recents with calls: a long-press tip ("Turn on" opens Appearance › Swipe actions), or a swipe tip when swipe actions are on. Only one tip shows at a time.
- [ ] Settings › Appearance › Reset tips: they all show again.

**Call colours (U3)**
- [ ] Recents, a number's history, a contact's recent calls, the contact's call insights and a private contact's calls show the call icon on a tinted circle: incoming green, outgoing blue, missed and declined red, blocked amber. Change the wallpaper (Wallpaper colours on): the call colours stay the same. Dark theme: lighter hues, still readable. The insights chart uses the same blue and green.

**Empty states (U5)**
- [ ] Contacts, private contacts, Favorites, Recents, Voicemail, Keypad search, Settings search and the contact picker: a search with no result says "No … match “xyz”" with "Clear search" (Keypad: "Create new contact"); with nothing there yet it says so, with one action (Create contact, Choose favorites, Open keypad, Call voicemail…). Recents › Missed with none: "Show all calls". Contacts with a label filter and no match: "Clear filter".
- [ ] Birthdays, Recently deleted, Temporary contacts, Labels, Introduce, Duplicates, Tidy up, a contact's version history and the CSV mapping each show one button when empty.

**What's new (U6)**
- [ ] Update over an older build (`adb install -r` with a higher versionCode): home shows a "What's new in Parley …" card once; tabs, start tab and Recents look exactly as before. "Try it" opens Appearance › Navigation bar; "Got it" dismisses it. It doesn't come back until the next version. A fresh install never shows it.

### 15.5 Circle, part 2 (R6–R10, X1, X6)

**People card in Insights (R6)**
- [ ] Recents › Insights: a "People" section under the totals. With people in your Circle: "You were in touch with n of m in your circle this month" and an arrow against the 30 days before (worked out from calls and logged interactions, no snapshot). Log a meeting with a Circle member you hadn't reached this month: the count goes up on the next visit.
- [ ] Open loops: miss a call from a contact → "Their call · …" with a Call button. Call back (even unanswered) or log an interaction: it's gone. Call a contact who doesn't answer → "Your call, not answered yet"; when they call back it's gone. Loops older than 30 days never show.
- [ ] "Who usually reaches out first" per Circle member with at least 4 conversations; "Only you see this". ⋮ › Hide who reaches out first hides it; Settings › Recents & history › Who usually reaches out first brings it back.
- [ ] "Your year" appears only with at least 20 calls or interactions in the last year: most in touch, longest gap with a Circle member, occasions acknowledged ("Mark as wished").
- [ ] ⋮ › Hide this card: gone. Settings › Recents & history › People card in Insights turns it back on. Settings search "open loops" and "reaches out" find both rows. Private contacts never appear.

**Circle widget (R7)**
- [ ] Long-press home › Widgets › Parley › Circle: the picker shows a preview and the description. Place it: up to 3 people from the digest (tap a name opens the contact; the phone button calls through the pocket guard) and up to 3 dates in the next 14 days. Resize it smaller: fewer rows; larger: more.
- [ ] Add someone to the Circle, log an interaction or make a call: the widget updates within a few seconds while Parley runs, and at least daily otherwise.
- [ ] Turn on the app lock, lock the phone (screen off) and look at the widget right after unlocking with a PIN on a launcher that shows widgets before unlock, or reboot: only "n people to reach out to / n dates…", never names. After unlocking, names return. With the app lock off, names always show.
- [ ] Move a Circle contact to private contacts: it disappears from the widget.

**Remember what matters (R8)**
- [ ] Settings › Calls › Remember what matters › "Anything to remember?" after calls (off by default). Turn it on, call a contact and hang up after it connects: the call-ended screen shows "Anything to remember?" with a note field, Their news, I promised… (adds "[ ] "), Follow up in 1 week / 1 month. Unknown numbers still get the V4 card instead; unanswered calls get nothing.
- [ ] Save a note: toast "Saved to their timeline"; the note shows on the contact's timeline as a call note. Not now closes the screen; touching the card keeps it up.
- [ ] Follow up in 1 week (wait a week, or move the phone date on and let WorkManager run): a "Follow up with …" notification, private on the lock screen (only a neutral line there), with Call; it lists open promises.
- [ ] Next incoming call from that contact, phone unlocked: under the pinned note the call screen shows "Last note: …" and "☐ …" promises. Phone locked: not shown; unlock while ringing: it appears within a second. Settings › Notes on the lock screen on: shown while locked too. Private contacts never show it.
- [ ] Contact page › Call (or tap a number) with a note, promise or good-time line: the pre-call peek sheet opens with them and a Call button; Cancel dials nothing. "Don't show again" turns it off (Settings › Calls › Peek before calling) and calls. Without anything to show, Call dials at once.

**Promises (R9)**
- [ ] The pinned note editor, the Log interaction note and the post-call note have a checkbox button and a one-line hint. Lines starting with "[ ] " become promises: a "Promises" group appears under Stay in touch; tick one off: it's gone, with "Ticked off: …" and Undo; the note line now reads "[x] …" in the timeline.
- [ ] Promises tick off in the pre-call peek too.

**Life events remembered yearly (R10)**
- [ ] A contact with a custom date (e.g. label "New job", with a year) shows a repeat icon on the date row; tap it: "Remembered yearly" is added under the label. Birthdays and anniversaries have no icon.
- [ ] Set the date to a few days after next Sunday's digest a year ago (or change the phone's date): the Sunday digest has "1 year since Ana's New job". Without a year: "Around now: Ana's New job".
- [ ] Link that contact with another one (or rename a phone-only contact): the flag stays. Backup and restore on another phone: the flag comes back.

**Good time to call (X1)**
- [ ] A contact with at least 8 answered calls, mostly in the evening: the Stay in touch card and the peek show "Usually free 6 PM–9 PM". With a foreign number (e.g. +81 …): "· 7:40 AM there" in their time. Fewer than 8 answered calls or calls all over the day: no line. US/Canada numbers from area codes with several time zones may show no local time.

**Digest serendipity (X6)**
- [ ] With the weekly digest: the third line is someone you were in touch with more than a year ago ("It's been over a year since you and … were in touch"), Circle or not, and never the same person two Sundays in a row. Nobody quiet for a year: no such line.

**Review fixes (Circle)**
- [ ] Log a few interactions (one with a `[ ]` promise note) for a contact, then ⋮ › Move to private: the interactions don't appear anywhere (Circle, People card, widget). Move them back out: the timeline, notes and promise are back.
- [ ] Mark a non-Circle contact's birthday as wished, then rename that phone-only contact: the entry stays on their timeline.
- [ ] Restore a backup with Circle members and interactions onto a phone without those contacts yet: members and entries come back; the result mentions any entries with no matching contact.
- [ ] Ask mode: open WhatsApp with a Circle contact, tap Log, open it again within 10 minutes, tap Log: no Undo is offered the second time, and the entry stays.
- [ ] Two custom dates of one contact on the same day ("New job", "Moved"): two separate reminders; "Mark as wished" closes only the one tapped.
- [ ] Weekly digest with nobody in the Circle but a date flagged "remembered yearly": the Sunday digest still arrives with that line.
- [ ] App lock on, Circle widget on the home screen: lock and unlock the phone after Parley was closed from Recents: the counts show "Tap to show names"; a tap (or opening Parley) brings the names back.
- [ ] Insights › People › Year in review in Arabic or Urdu: names are joined with the Arabic comma; a contact with a family-name-first or CJK name shows its given name (or the full name), never a split fragment.

### 15.6 Extras (X2–X5, C5)

**Trip mode, "Who's in…" (X2)**
- [ ] Contacts ⋮ › Who's in… and Circle ⋮ › Who's in… open the screen; with the Circle tab hidden, Favourites ⋮ has it too. No location permission is asked for, ever (Settings › Apps › Parley › Permissions shows none new).
- [ ] Chips list the cities from your contacts' addresses (most common first). Type "lisboa", "LISBOA" or "Lisbóa": the same people show. "Rome" doesn't list someone in "Romeoville".
- [ ] A contact with the city in an address shows "Address"; one whose note says "moved to Porto" shows "Mentioned in a note"; one with a Lisbon landline (+351 21…) shows "Number from there" for "Lisbon". Summary line: "You're in Lisbon: Ana, Marco" (and "… and 2 more").
- [ ] Call and Message on a row work (the call goes through the usual confirm/SIM questions). Leave and come back: the last city is filled in and first among the chips.

**Label policies (X3)**
- [ ] Contacts › Labels › a label: under the ringtone, "For everyone in this label". With two SIMs: "SIM for calls" › pick SIM 2. Call a member with no remembered SIM from Contacts, Recents and the keypad: no SIM question, it goes out on SIM 2. A member with their own SIM (contact page › number › SIM) keeps theirs. Remove SIM 2: calls ask again (or use the default).
- [ ] "Keep in touch when they join your circle" › Every 2 weeks. Open a member who isn't in the Circle › Stay in touch: the first row is "Like your “Family” label: Every 14 days". The label page offers "Add N members to your circle"; they appear in the Circle.
- [ ] "Allow through Do Not Disturb": the explanation names the trade-off (starred shows in Favourites and other apps; every starred contact rings). Confirm: members are starred and Android's Do Not Disturb people page opens (or the Do Not Disturb/sound page on phones without it). A member added later shows "Star 1 new member". Turning it off unstars only the ones Parley starred.
- [ ] Rename the label: its SIM, rhythm and Do Not Disturb choice follow. Delete it: they go. Back up and restore (Settings › Backup): the policies come back.

**Simple mode (X4)**
- [ ] Settings › Appearance › Simple mode (search "simple", "senior" or "elderly" finds it). Add 1, 4, then 9 people (a contact with several numbers asks which); remove one. Options: Keypad button, Ask before declining, Say who is calling.
- [ ] "Turn on simple mode": the home becomes big photo tiles (1 = one tile, 4 = 2×2, 9 = 3×3) with names, and a large Keypad button. A tile asks "Call Ana?" with a big green Call. Keypad: big keys, hold 0 for +, delete, Call. A tel: link or the headset button opens the big keypad with the number.
- [ ] Tapping "Leave" only says to press and hold (a one-time tip explains it too). Press and hold › confirm: with the app lock on, the fingerprint/PIN prompt shows first; cancelling keeps simple mode. App lock and "Hide screen content" apply to the simple home (screenshot blocked, recents thumbnail blank).
- [ ] Incoming call in simple mode: very large Answer (top) and Decline buttons, no slider and no "Block & decline". With "Ask before declining": Decline asks "Decline this call?" ("Keep ringing" goes back). With "Say who is calling" and the ringer on: "Ana is calling" is spoken up to three times; not for unknown numbers, not in silent/vibrate or Do Not Disturb, and it stops when the call is answered or "Stop ringing" is tapped. No microphone permission appears.
- [ ] Save the setup as a file (passphrase twice, 8+ characters) on phone A; on phone B Settings › Appearance › Simple mode › Import a setup file › passphrase: people are listed "Found: …" (matched by number, else by name) or "Not in your contacts" with Create (opens the editor with name and number). Wrong passphrase: "That didn't open it". "Use this setup and turn on simple mode" switches B to the simple home.
- [ ] Show the setup as a QR code on A; scan it on B with any camera/QR app: Parley opens "Import simple mode" and asks for the passcode. In simple mode on B, the link just says to leave simple mode first.
- [ ] Encrypted backup and restore keeps the simple-mode setup (people by name and number).

**Handshake (X5)**
- [ ] Phone A: a contact › Share › encrypted QR. Phone B scans it and types the passcode: under the contact, "Where did you meet?" (type "Café Lua"), "Also add it to their note" and "Swap: always show my card…". "Show my card" shows B's own card QR (or offers to fill it in when empty).
- [ ] Save to phone contacts: the editor opens (note filled with "Met at Café Lua on 26 Sep 2026" when ticked); after saving, the contact's timeline shows a Met entry with that note. Cancelling the editor logs nothing. Saving the same contact twice from one scan logs one entry.
- [ ] Save privately: the private contact's note gets the line when ticked (no Circle entry: private contacts aren't in the Circle).
- [ ] With Swap ticked, the next received contact shows B's card QR straight away; A scans it with its camera to get B's card.

**Markdown notes export (C5)**
- [ ] Settings › Backup & sync › Export notes as Markdown (or Sync between your phones, below the sync card): pick a folder (e.g. an Obsidian vault). One `Name.md` per contact appears: YAML front-matter (name, company, phones with labels, emails, dates, labels, keep_in_touch_days, exported), then Pinned note, Note, Circle and Timeline (calls with minutes, Met/Message entries with notes, call notes), newest first. Obsidian shows the properties.
- [ ] Names with `/ : ? #` make safe file names; two "Ana Silva" become "Ana Silva.md" and "Ana Silva (2).md". A note of your own already named "Ana Silva.md" in the folder is never overwritten (Parley's file gets "(2)").
- [ ] "Only people in your circle" on: files of people outside the Circle that Parley wrote are removed; your own files stay. Export again without changes: "0 files updated". Change a note: only that file is rewritten.
- [ ] Private (vault) contacts never get a file. "Keep it up to date" on: the folder-sync run (a minute after contacts change, and daily) exports again (even with contact sync off). "Stop exporting to this folder" leaves the files where they are.

### 15.7 Review fixes: simple mode, Markdown, Do Not Disturb, handshake, import

- [ ] Simple-mode import (X4): make a setup whose "number" is `**21*+441234567#`, `*100#` or `0123,456` (edit an exported setup on a test build). Importing it leaves those people out and says "N entries weren't plain phone numbers and were left out". Every imported person shows its number on the review screen. A person whose name matches a contact but whose number doesn't shows "Not in your contacts" (no photo, no tick); after "Use this setup" the tile shows initials and calls the imported number. A person whose number is in contacts shows "Found: …" and gets that contact's photo.
- [ ] Markdown (C5): export, then append a line to `Ana.md` in the note app and change Ana's pinned note in Parley. Next export: `Ana.md` keeps your line, Parley writes `Ana (2).md`, and the export row says "1 file you changed was left as it is". Turn on "Only people in your circle": an edited non-Circle file stays; untouched ones are removed. "Stop exporting", then pick the same folder again: no "(2)" copies of untouched files appear (each file carries a `parley_export:` fingerprint).
- [ ] Do Not Disturb (X3): turn it on for "Work" and "Family"; someone in both stays starred when "Work" is turned off and is unstarred when "Family" is too. Remove someone from "Work", then turn it off: they are unstarred too. Delete a label with it on (or merge it into a label without it): its members are unstarred. A contact you starred yourself is never unstarred. Restore a backup: turning it off afterwards still unstars the right people.
- [ ] Handshake (X5): receive a card, tap "Save to phone", and before saving open "Add to contacts" from another app; save that one: no "Met at…" entry on it. Save the first editor: the entry lands on the received contact.
- [ ] Opening a 2,000-card .vcf from a file manager: the account rows show a progress bar and can't be tapped until the cards are counted; then "Back up first?" appears.

## 16. v3.3

### 16.1 Corrections

**Keypad keys never move while typing (C1)**
- [ ] Portrait, on-screen keypad: put a finger over the 5 key and type a number digit by digit (up to 15 digits, with and without a match in contacts). The Message / Add contact / Temporary / Add to existing chips appear at the foot of the results list, above the keypad panel; the digit keys, the number field, the backspace and the Call button stay exactly where they were (compare a screenshot before the first digit and after the tenth).
- [ ] Type a service code (`*#06#`, `*100#`) and letters from a hardware keyboard: the chips hide and nothing in the panel moves either. Long numbers switch the number to the smaller size without changing the field's height.
- [ ] Two SIMs: the single Call button becomes two SIM buttons on the first digit; the row keeps its height (no jump). A SIM near its plan limit (badge dot) doesn't change it either.
- [ ] Landscape and a large font size (200 %): keys stay put while typing; the chips shrink with the results area and never push the keypad. With a hardware keypad or keyboard (keypad hidden), typing doesn't move the number field or the Call button.
- [ ] The chips scroll sideways when they don't fit; the list's last result can still be scrolled above them.

**Call wherever Parley offers "Message on…" (C2)**
- [ ] Keypad: type an unsaved number › Message (chip or the "Message on…" result): a green **Call** button is first in the sheet. Tap it: the sheet closes and the call goes through the usual checks (dial guard warnings for a premium number, "Confirm before calling", the SIM question with two SIMs and no default).
- [ ] The same Call button is first in: Recents long-press › Message on…; number history › Message on…; a contact's (and a private contact's) "Message on…" sheet (calls the number chosen in the chips; a contact with pre-call peek on shows the peek first); the Recents swipe "Message on…" for an unsaved number.
- [ ] Missed-call notification › Message on… (Parley in the background or closed): the sheet over the current app has Call first; it places the call without opening Parley. Post-call screen › Message on…: Call is there once the call has ended; opened from the in-call caller card during a call, Call is left out.
- [ ] Select a number in any app › "Call / Message with Parley" (and Share text with a number to Parley): Call is the large green button above "Message on…"; after "Message on…", the messenger sheet has Call first too.
- [ ] Quick Settings tile / launcher shortcut "Message a number": type a number; Call appears with the messengers (and on its own for a short or service number messengers can't open). It works with Parley not in the foreground. Without the phone permission it opens Parley's keypad with the number.
- [ ] Recents ⋮ › Messaged numbers: each number has a green call button beside delete. Add several numbers › review: each found number has a call button (the checkbox still toggles on a row tap).

**My card opens its QR code (Q3)**
- [ ] Contacts › My card (filled in): one tap shows the QR code with the part checkboxes, **Edit**, **Share file** and Done. Edit opens the editor. The row's pencil button and a long-press on the row open the editor directly. TalkBack says "Show my QR code" for the tap and "Edit my card" for the long-press.
- [ ] With an empty card, a tap opens the editor (there's nothing to show yet).

**Recents at a glance (R4)**
- [ ] Settings › Recents & history › Recents style: Rich (default) and Simple. Settings search finds it by "recents style", "legend", "colour blind". Simple brings back the previous rows everywhere.
- [ ] Rich: each call class has its own badge shape: missed = solid circle, declined = solid square, incoming = soft circle, answered on another device = dashed circle with a devices icon, voicemail = soft square, outgoing = outlined circle, outgoing nobody answered = dashed circle with the "missed outgoing" arrow and "No answer" in the row, blocked = crossed square outline. Check in grey scale (Developer options › Simulate colour space › Monochromacy) that every class is still told apart, in light and dark theme and with a dynamic colour wallpaper.
- [ ] A thin bar in the call's colour runs along the row's leading edge (on the right in Arabic/Urdu).
- [ ] A missed call you haven't returned: row lightly tinted, name in bold, and a **Call back** pill instead of the call icon. Call that number (answered or not), or take a call from it: the tint and the pill go on every older row of that number. A missed call from a private number never gets the pill. Declined calls don't ask to be returned.
- [ ] The Missed chip shows how many people are still to call back (TalkBack: "Missed, 3 people to call back"); no number when there are none.
- [ ] A grouped row with several calls: a "3×" chip in the colour of the latest call, and up to 4 small marks in order, oldest first (squares for declined/blocked, rings for calls you made). TalkBack reads "3 calls, latest: missed call, missed call, outgoing call".
- [ ] Talked calls show a short bar sized by length (log scale: 20 s is a sliver, 5 min about half, an hour full); TalkBack reads the length.
- [ ] Recents ⋮ › "What do the colours mean?": the legend lists each badge with a line of explanation.
- [ ] Number history, the contact's Circle timeline, a private contact's calls and Call insights use the same badges (and the length bar / "No answer" in history and timeline rows).
- [ ] TalkBack on a Recents row reads the call type in words ("Missed call · not called back yet"), then the rest of the row.

### 16.2 QR scan

Parley declares no CAMERA permission: check App info › Permissions never lists Camera. The build's permission check fails if one appears.

**Getting a picture in (Q1)**
- [ ] Contacts header › QR icon (first time: the tip "New: scan a QR code…" under it). "Take a photo" opens the phone's camera app; photograph a vCard QR: the result sheet opens. `adb shell ls /data/data/app.parley.phone/cache/qr` is empty afterwards (also after cancelling the camera, and after the camera app is killed mid-shot and Parley reopened).
- [ ] "Pick an image" opens the system photo picker (no storage permission asked); a screenshot of a QR code reads. A 50 MP photo with a small code reads without the app stalling (decoding happens off the main thread; "Looking for a code…" shows meanwhile).
- [ ] Share a picture from Gallery/Photos › Share › "Scan QR code" (Parley): the scan screen opens and reads it. With the app lock on, Parley asks to unlock first.
- [ ] Copy `https://wa.me/491511234567` elsewhere, then "Paste a link or text": the WhatsApp sheet opens. The clipboard is only read on that tap (Android 12+ shows its "Parley pasted from your clipboard" toast only then).
- [ ] A picture without a code: "No code found in this picture" with four tips and "Take another photo". A corrupt file: "This picture couldn't be opened".
- [ ] Light-on-dark (inverted) code, a code photographed at an angle, a Data Matrix or Aztec code: read. A picture with two QR codes: "Found 2 codes. Pick one" lists both with their type.

**Entry points (Q2)**
- [ ] Contacts ⋮ › Scan QR code; Keypad ⋮ › Scan QR code; Contacts › My card › QR code › "Scan theirs"; Settings search "qr" or "scan" › Scan QR code (Settings › Contacts › Import & export); long-press the launcher icon › "Scan QR"; Quick Settings › edit › add "Scan QR" tile (collapses the shade and opens the scan screen, after unlocking).

**Contacts (Q3, Q4)**
- [ ] vCard 3.0, vCard 4.0 (`TEL;VALUE=uri:tel:+…`), vCard 2.1 with `CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE` (umlauts show right), MECARD (`MECARD:N:Owen,Sean;TEL:…;;`) and BIZCARD codes: the sheet shows a card with name, work, numbers, emails, address, website, dates, note.
- [ ] "Add to contacts" opens Parley's editor filled in; a number you already have shows the duplicate warning. "Add privately" saves to private contacts (asks to unlock the vault if needed). "Add as temporary" opens the temporary-contact dialog with the name and first number. "Add to an existing contact" opens the picker, then the chosen contact's editor with the new details appended.
- [ ] A card with a photo or labels: the note "This card has details the editor doesn't show" and "Import with every detail" (the import dialog, duplicates skipped).
- [ ] A code with two vCards: the list; tap one to see its card ("Back to the list"), or "Import all 2 contacts".
- [ ] Parley's own encrypted contact QR (`parley://qr`) and simple-mode QR (`parley://simple`): "Open in Parley" leads to the passcode dialogs as before. A Parley plain "My card" QR reads as a normal contact.
- [ ] Pixel/Samsung camera or Lens scanning a vCard QR › "Add contact" offers Parley (it handles `text/vcard`, `text/x-vcard` and INSERT).

**Numbers, messages, places, Wi-Fi, events (Q3, Q4, Q5)**
- [ ] `tel:+12125551212`: number in full; Call goes through the dial guard and confirm-before-calling (a premium or spam-listed number warns); Message on…; Add to contacts; Put on the keypad.
- [ ] `tel:*21*123%23` and `tel:%2A%2306%23`: the sheet says "Phone code", shows `*21*123#` / `*#06#` in full with the warning, and offers only "Put on the keypad" (never dials or runs it).
- [ ] `SMSTO:+18005551212:Hello` and `sms:+18005551212?body=Hi`: "Write the message" opens the SMS app with the body; nothing is sent by Parley.
- [ ] `mailto:a@example.com?subject=Hi&body=Text` and `MATMSG:TO:a@example.com;SUB:Hi;BODY:Text;;`: "Write the email" fills the email app.
- [ ] `geo:40.71872,-73.98905`: "Open in maps"; "Copy coordinates".
- [ ] `WIFI:T:WPA;S:Home;P:secret123;;`: name, WPA/WPA2, masked password with the eye button. Android 11+: "Save this network" shows Android's own "Save network?" dialog (no permission prompt from Parley). Android 10, WEP, enterprise or a password under 8 characters: only "Copy password" (kept out of clipboard previews on Android 13+) and "Open Wi-Fi settings". Backslash-escaped (`S:My\;Net`) and percent-encoded (`S:caf%C3%A9`) names both read right.
- [ ] `BEGIN:VEVENT…DTSTART:20261001T090000Z…END:VEVENT`: "When" shows the local time; "Add to calendar" opens the calendar app's new-event screen filled in (no calendar permission).

**Messenger links (Q3, Q4)**
- [ ] With WhatsApp installed: `https://wa.me/491511234567` › "Open in WhatsApp" opens the chat in the app (not the browser). Also "Save as contact" and "Call". `https://chat.whatsapp.com/…` says "WhatsApp group invite".
- [ ] Signal `https://signal.me/#p/+…` and `sgnl://…`, Telegram `https://t.me/name` and `tg://resolve?domain=…`, Threema `3mid:ECHOECHO,…`, Matrix `https://matrix.to/#/@a:matrix.org`, SimpleX `simplex:/contact#…`: each opens its app when installed.
- [ ] Uninstall the app (or use one you don't have, e.g. LINE `https://line.me/R/ti/p/~x`): "LINE isn't installed…", then Copy link, "Open in browser" (with the note that the browser leaves Parley and uses the internet) and "LINE in your app store". Nothing opens until tapped.
- [ ] A Session ID (66 hex characters starting 05): "Open in Session" copies it and opens Session. WeChat and KakaoTalk profile codes: the note about scanning inside the app. `skype:name?chat`: the note that Skype has closed.

**Web addresses (Q4, Q5)**
- [ ] `https://www.example.co.uk/path`: "Goes to example.co.uk" in large type, the full address below, "Open in browser" (the default browser, even if an app claims the link) and "Copy link".
- [ ] `https://paypal.com.secure-login.io`, `https://paypa1.com`, `https://аpple.com` (Cyrillic а), `https://bit.ly/x`, `https://www.bank.com@evil.example`, `http://192.168.0.1`: each shows its red warning, and "Open in browser" isn't the highlighted button. `https://xn--mnchen-3ya.de` shows "münchen.de" and "Spelled for the internet as xn--mnchen-3ya.de".
- [ ] Text containing a right-to-left override (e.g. `abc‮txt.exe`): the note "hidden formatting characters… left out" and the text shown without them. `javascript:…`, `intent:…`, `file:…` codes show as plain text with only Copy and Share.
- [ ] Every sheet: Copy and Share work.

### 16.3 Combined surfaces

Settings › Appearance › Layout (search "combine", "keypad", "favourites", "merge tabs"). Everything here is optional; Separate / Off is the default for new and existing users.

**Update keeps the layout (migration)**
- [ ] Install the previous release, reorder or hide tabs, pick an "Open on" tab, update to this build: the bar, its order, the start tab and the Recents row tap are exactly as before; Layout shows Calls layout "Separate" and Favourites in Contacts "Off".
- [ ] After that update the "What's new" card mentions the layout options once, with "Layout options" (opens Settings on Calls layout, highlighted). Nothing changes until you pick something; after "Got it" the offer doesn't come back on later updates.
- [ ] Backup, switch options, restore: the layout from the backup comes back.

**Keypad + Recents (Calls layout: Combined)**
- [ ] With the Keypad tab shown in the bar, tap "Combined": a dialog asks "Keep the Keypad tab too?". Back/outside changes nothing; "Hide the tab" docks the keypad and removes the tab from the bar; "Keep the tab" keeps both. Settings › Navigation bar lists Keypad with "Shown in Recents" and its switch unchanged.
- [ ] Recents shows the calls with the keypad docked at the bottom. Folded: a round keypad button (bottom end). Unfold by tapping it or swiping it up; fold by tapping/swiping down the handle on top of the keypad, by scrolling the list, or with Back.
- [ ] Type digits: the calls are replaced in place by the T9 matches, the Message / Add contact / Temporary chips appear above the keypad (outside it), and the digit keys, number field and Call button never move (screenshots before and after 10 digits). Delete the digits: the calls come back.
- [ ] Fold while digits are typed: the button shows the number ("Show keypad, 0612… typed" in TalkBack); unfold restores it.
- [ ] Switch to another tab and back, rotate: the keypad stays folded/unfolded as you left it (for this session).
- [ ] `tel:` link, ACTION_DIAL (another app's dial button → Parley), "Add call" during a call, a shared number's "Edit before calling", Recents long-press "Edit number before calling", a contact's "Edit before call": Recents opens with the keypad unfolded and the number filled in. With "Also keep a separate Keypad tab" on, they open the Keypad tab instead.
- [ ] Hardware keypad/keyboard (or `adb shell input keyevent KEYCODE_5`): typing on Recents unfolds the docked keypad and shows the number.
- [ ] Header search on Recents searches calls (the keypad hides while searching).
- [ ] Recents ⋮ adds Speed dial, SIMs & plan minutes and Keypad settings while the keypad is docked.
- [ ] Large text (Settings › Display › Font size largest, or 200 %): the docked keypad takes at most ~60 % of the height and scrolls inside itself; the list above stays visible.
- [ ] Landscape on a tablet/foldable (navigation rail): the keypad sits beside the calls list, not under it; folding it gives the list the full width.
- [ ] RTL (Arabic/Urdu): digits stay 1 2 3 left to right, the folded keypad button sits at the bottom left, the previews in Settings are mirrored.
- [ ] TalkBack: the keypad handle reads "Hide keypad" (button); the keypad panel has a "Hide keypad" action; the folded button reads "Show keypad".
- [ ] Hide Recents in Settings › Navigation bar while Combined: the Keypad tab comes back in the bar (the keypad is never unreachable).

**Tapping a call in Recents (every layout)**
- [ ] Default "Open details": unchanged. "Call": a tap on a row calls back (through confirm-before-calling and the dial guard if on); the trailing button becomes "Details for …" and opens the contact / number history. Long-press still opens the actions sheet; while selecting, a tap selects.

**Favourites + Contacts**
- [ ] With the Favourites tab shown, pick "Section at top" or "Avatar strip": a dialog asks whether to keep the Favourites tab. Contacts shows, under "My card", a "Favourites (n)" header with Reorder and a fold chevron; the section uses the Favourites grid's columns (pinch there to change), the strip is one scrolling row of avatars.
- [ ] Tap a favourite: calls (as on the Favourites tab); long-press: opens the contact.
- [ ] Fold the header, leave, kill and reopen Parley: it stays folded. TalkBack reads it as a heading with "Folded/Unfolded".
- [ ] "Show frequent contacts": a "Frequent" row of avatars under the favourites.
- [ ] Reorder (header button or Contacts ⋮ › "Reorder favourites"): a sheet with drag handles and TalkBack "Move earlier/later"; the order matches the Favourites tab (custom order).
- [ ] Circle tab hidden: the Circle section appears in Contacts under the favourites (and Settings says so); with "Also keep a Favourites tab" on it stays at the top of Favourites as before. Showing the Circle tab removes the section.
- [ ] Searching or filtering Contacts hides the favourites and Circle sections; the A–Z fast scroller still jumps to the right letter with the sections shown.
- [ ] Hide Contacts in Navigation bar while favourites are in Contacts: the Favourites tab comes back.

**Tabs and start tab**
- [ ] "Open on: Keypad" with Combined: Parley opens on Recents with the keypad unfolded; "Open on: Favorites" with favourites in Contacts: opens on Contacts. Switch back to Separate: they open on Keypad / Favorites again (the saved choice was kept).
- [ ] "Open on" lists only the tabs in the bar.
- [ ] Show only Recents and Keypad in the bar, then Combined: only one tab is left and the bottom bar (or rail) disappears; a missed-call notification or a Circle digest still opens its tab, with the bar back while it's open.
- [ ] "Back to separate tabs" (shown while anything is combined): one tap restores the bar exactly as it was before combining; the Recents tap choice stays.

### 16.4 Review fixes (QR, docked keypad, unreturned missed calls)
- [ ] Paste `https://evil.com\@paypal.com/login`: the sheet says **evil.com**, the full address reads `https://evil.com/@paypal.com/login`, and "Open in browser" opens evil.com. `https://paypal.com@evil.com/` shows evil.com with the "hides its real destination" warning and opens `https://evil.com/` (no user name). `https://paypal.com%2F.evil.com/` shows as plain text.
- [ ] Paste `https://evil.com\@t.me/joinchat/x`: a web address to evil.com, not a Telegram group.
- [ ] Paste a VEVENT with `DTSTART:20260231`: shown as text, no crash.
- [ ] Take a photo of a QR code with the camera app, and have Developer options › "Don't keep activities" on: the result still appears after coming back.
- [ ] A scanned vCard with `X-PARLEY-STARRED:1`, `X-PARLEY-SEND-TO-VOICEMAIL:1` and `CATEGORIES:Family`: the sheet shows "This card also asks to:" with three unticked boxes. "Add contact" (or "Import all" with two cards): the contact isn't a favourite, isn't sent to voicemail and has no label. Tick "favourite" first: it is starred.
- [ ] Combined calls layout, Recents search open: tap a `tel:` link: the search closes and the keypad unfolds with the number.
- [ ] Wi-Fi code with a password, "Copy text" on Android 13+: the clipboard preview hides the text.
- [ ] `mailto:a@example.com?bcc=b@example.com`: the sheet shows "Hidden copy to (Bcc)" and a warning.
- [ ] Hardware keyboard, Combined layout, nothing typed: D-pad to a Recents row and press Enter: that row opens (the last number isn't recalled). With the keypad unfolded and focus on it, Enter still recalls the last number; digits always go to the keypad.
- [ ] Missed chip: missed calls older than 7 days, from blocked or spam-marked numbers, or withheld numbers don't count as "to call back".

## 17. v3.4

### 17.1 Keypad

Call pill (K1)
- [ ] One SIM: the bottom row shows the keypad button (left), one compact green Call pill (centre) and backspace (right). The number row above the keys shows only the number.
- [ ] Two SIMs: one green pill split in two ("📞 SIM 1 | 📞 SIM 2", or the carrier / your SIM labels, shortened with "…"), with a thin divider. Each half calls with its SIM, straight away (no SIM question). Three SIMs: three segments.
- [ ] Two SIMs from the same carrier: the segments read "SIM 1" / "SIM 2"; TalkBack still says "Call with SIM 1 (Carrier)".
- [ ] TalkBack on a segment: "Call with SIM 2 (Orange)"; the SIM a plain Call would use (remembered for the typed number, else the default SIM) is shown in bold and read as "Usual SIM for this call".
- [ ] A SIM near its plan minutes shows a small dot on its segment's phone icon (not a big badge).
- [ ] Nothing typed: a segment (or the single pill) puts the last dialled number back. Letters typed on a hardware keyboard: a segment calls the top match with that SIM.
- [ ] Hardware Call key and Enter still follow the remembered / default SIM rules (and ask when there is neither).
- [ ] Backspace: tap deletes before the cursor; long-press clears the number (with a haptic tick). It's dimmed with nothing typed, and nothing in the bottom row moves or appears while typing.

Keypad look (K2)
- [ ] Large light digits with letters beneath; voicemail icon under 1, "," under *, "+" under 0, ";" under #. No key backgrounds; a soft round ripple on press. Light and dark themes; 200 % font size (digits capped, letters readable).
- [ ] Type a long number fast: the keys never move (number actions stay above the panel). Long-presses (0 → +, 1 → voicemail, 2–9 speed dial, * → pause, # → wait), local letter rows, tones/haptics and hardware keys work as before.
- [ ] Keypad tab: the keypad button hides the keys to see more matches, and brings them back.

Docked keypad folding (K3, Calls layout: Combined)
- [ ] Drag the handle or anywhere on the panel (keys included) down: the keypad follows the finger and slides down behind the edge; release past about a third, or flick down, and it folds with a spring; a short slow drag springs back open. A swipe that starts on a key never types that digit.
- [ ] Scroll the calls list: the keypad folds away first, following the finger, then the list scrolls; flinging keeps its speed for the list. Scrolling back up never pulls the keypad open.
- [ ] Folded: a keypad FAB springs in at the bottom end, with the typed number's last digits as a badge ("…5678"). Tap it, or drag it up (the keypad follows), to unfold. No scrim, no jump of the list.
- [ ] Still as in v3.3: tel: links / ACTION_DIAL unfold the keypad with the number; Back folds it; TalkBack "Hide on-screen keypad" action on the panel; large fonts scroll inside the panel (max ~62 % of the screen); landscape side by side, where the panel slides aside as it folds; typing on a hardware keypad unfolds it.

### 17.2 Message and call on

Needs a phone with some of WhatsApp, WhatsApp Business, Signal (or Molly), Telegram, Viber, Threema installed and set up, and contacts who use them.

- [ ] Signal contact: open a contact who is on Signal. "Reach via apps" lists Signal with Message, Voice and Video buttons; each opens Signal (chat, voice call, video call) for that person. The Video tile at the top also offers Signal.
- [ ] Signal detection regression: take a contact Signal linked, move it to another account (or edit it so its raw contact is replaced), wait for Signal to sync: Signal still shows under "Reach via apps" (read from Signal's own contact on the same number).
- [ ] WhatsApp and WhatsApp Business both linked: two separate rows; WhatsApp's Voice opens WhatsApp, Business's opens Business.
- [ ] Contact with two numbers where Signal knows only one: each app row shows the number it is for.
- [ ] Viber contact: Viber shows Message and Voice; Viber Out (paid) is never offered as a call.
- [ ] Phone in French or German: Signal's voice call row still shows as Voice (not as a chat).
- [ ] Long-press Voice on Signal: the button fills and the row says "Usual for calls"; the Call tile at the top now reads "Signal" and starts a Signal call. Long-press again: back to normal calls. A one-time tip explains the long-press.
- [ ] Contact page Message long-press / "Message or call on…" in a number's menu: the sheet is titled "Message or call <name>", has "Message on" (apps, SMS) and "Call on". Apps that added call rows show Voice/Video buttons; installed chat apps that didn't say "Open chat on WhatsApp to call" and, when tapped, open the chat with a toast pointing at the call button. With "Always use this" ticked, a call choice is remembered as the call (or video) app and doesn't change the message choice.
- [ ] Keypad: type an unsaved number; the chip reads "Message or call"; the sheet shows "Call on" with each installed chat app as "Open chat on … to call" (never a pretend direct call), and "Save for 7 days so apps can offer calls". Save it (visible to other apps ticked by default), wait a few minutes, reopen the sheet: apps that synced now offer direct Voice/Video buttons.
- [ ] Keypad: type the number of a saved contact who is on Signal: "Call on" offers Signal's Voice and Video directly.
- [ ] Recents row actions, missed-call notification action, post-call card and number history all say "Message or call on…" and open the same sheet.
- [ ] TalkBack: each round button reads "Voice call on Signal" etc.; long-press is announced as "Use by default"/"Stop using by default". Buttons are 48 dp; layout works in RTL, dark theme, largest font and landscape.
- [ ] With Signal and Molly both installed and linked, Molly's rows open Molly and Signal's open Signal (no chooser).

### 17.3 Contact page and list

**Contact page (P1)**
- [ ] Open a contact with a long history: under the header, every section (Stay in touch, Dates, Phone, Email, Address, Messengers, About, Other fields, Timeline, Call insights, Note for calls, Settings for this contact) has a header row with a chevron. Tap it: the section folds or unfolds with a spring; folded headers show a summary ("Timeline · 124 entries", "Dates · Birthday in 12 days", "Phone · 3 numbers").
- [ ] Fold Timeline on one contact, open another: it's folded there too (folds are remembered for every contact). Kill and reopen Parley: still folded.
- [ ] Defaults on a fresh install: the details are open; Other fields, Call insights and Settings for this contact start folded.
- [ ] Timeline shows only the latest 5 entries; "Show all (n)" opens the full timeline with a search box and Calls / Missed / Logged / Notes / Dates chips. Search "missed", a note word (accents ignored) or 3+ digits of a number; combine with chips; "Clear search and filters" when nothing matches. Month headings stay pinned while scrolling. Edit a logged entry there (tap it) and delete one (⋮ › Delete, then Undo on the snackbar).
- [ ] Scroll the page down: once the big Call / Message / Video / Email tiles have gone, a compact bar with the same buttons (and ⋮ "More") stays under the top bar; each button works and TalkBack reads "Call <name>", "Message <name>"… Scroll back to the top: the bar goes away.
- [ ] With 4 or more sections shown, the pinned bar has "jump to" chips; tapping one unfolds that section if needed and scrolls it just under the bar. Settings › Contacts › Contact page sections › "Jump to a section" off: no chips.
- [ ] TalkBack on a section header: reads the title, "heading", "Folded"/"Open" and the action "Unfold"/"Fold".
- [ ] Landscape, largest font, dark theme and Arabic (RTL): headers, chevrons, the pinned bar and chips lay out correctly.

**Contact page sections (P1)**
- [ ] Settings › Contacts › "Contact page sections" (also found by searching "fold", "sections" or "reorder"): drag a section by its handle (a tick on each step); the new order shows on every contact page.
- [ ] Tap a section: choose Open / Folded / Hidden. Hidden sections disappear from contact pages but nothing is deleted: show it again and it's back in the same place with its data. Choosing a start mode forgets the fold remembered for that section.
- [ ] TalkBack: each row offers "Move up" / "Move down" and reads its start mode.
- [ ] "Reset" (enabled only after a change): default order, modes and folds.
- [ ] Backup and restore: the order and modes come back.

**My card (M1)**
- [ ] Contacts › My card row has no pencil. Tapping the row opens the editor; tapping the QR button on its end shows the QR code (Share, Edit, Scan theirs inside). An empty card has no QR button and opens the editor.
- [ ] TalkBack: the row reads "Edit my card" as its action; the QR button reads "Show my QR code".

**Swipe actions (S1)** (Settings › Appearance › Swipe actions on)
- [ ] In Contacts and Recents, scroll up and down quickly, including slightly diagonal flicks: rows never start sliding. A clearly sideways drag (at least twice as sideways as vertical) slides the row.
- [ ] Touch the list while it's still flinging and drag sideways: the fling stops, the row doesn't slide. Drag again: now it slides.
- [ ] Drag slowly: the background is grey with a small icon; at about a third of the row it turns the action's colour, the icon pops and you feel a tick; drag back under it: grey again with a lighter tick. Let go below the threshold: the row springs back, nothing happens.
- [ ] A short, quick flick (a quarter of the threshold) runs the action; flicking back towards the start cancels.
- [ ] Delete: the row slides out and the snackbar offers Undo. Block: the snackbar says "Blocked <number>" with Undo, which unblocks it.
- [ ] A side without an action only gives a little rubber band.
- [ ] Arabic (RTL): "Swipe right" is still a rightwards swipe and the icon shows on the side being uncovered.
- [ ] TalkBack: the actions are in the row's custom actions; the preview row in Settings still works.

**A–Z fast scroll (A4)** (more than 30 contacts)
- [ ] Drag along the A–Z rail: a large rounded bubble with a pointed corner towards the finger follows it, showing the current letter (also "#", other scripts and "★" when favourites are at the top of Contacts); the rail gets a light background and the letter under the finger is highlighted; each new letter gives a light tick and the list jumps to it. Let go: the bubble shrinks away.
- [ ] Without dragging, the rail highlights the letter of the section at the top of the list while you scroll.
- [ ] Tap a single letter: the list jumps there (bubble shows briefly).
- [ ] RTL: the rail is on the left and the bubble appears to its right, pointing at the finger.
- [ ] TalkBack: the rail is one adjustable control ("Alphabet index", current letter); swipe up/down (or volume keys) moves letter by letter and the list follows.

### 17.4 Contact editor
The editor was redesigned after comparing Google Contacts, Samsung One UI Contacts, iOS Contacts, Fossify/Goodwy and Outlook (notes in `docs/EDITOR_DESIGN.md`): big photo with an edit badge, a "Save to" chip, one Name card that expands, a rounded card per kind of field with inline type chips, "+ Add …" rows and red "−", and "Add more info" for the rest. Nothing about what's saved changed.

**Look and layout**
- [ ] New contact: top bar with ✕ and a Save button that stays disabled until something is typed; from Recents › "Create contact" (number prefilled) Save is ready at once.
- [ ] Edit an existing contact: Save is disabled until something changes; add an empty phone row and remove it again: still disabled, and Back leaves without asking.
- [ ] "Add to existing contact" from a number: the number is appended and Save is ready without typing.
- [ ] 128dp round photo with a badge (camera without a photo, pencil with one); tapping it or "Add photo" opens the system photo picker; "Remove photo" appears only with a photo. The name typed so far shows under the photo.
- [ ] Name card: First and Last name; the chevron (TalkBack: "Show more name fields") slides in Prefix, Middle, Suffix, Phonetic first/last and Nickname with a spring. With any of them filled the card stays open and the chevron is gone.
- [ ] Work card: Company and Title.
- [ ] Phone, Email: each row has a type chip under it ("Mobile ▾"); its menu ticks the current type and ends with "Custom…". Phone numbers show the country flag after 6 characters.
- [ ] "+ Add phone" adds a row that slides in, and the keyboard opens in it (phone keyboard; email keyboard for email, URL keyboard for website). The red "−" removes a row, and the rows below slide up.
- [ ] Keyboard "Next" walks First → Last → Company → Title → first phone …
- [ ] Type "call me" in a phone or "ana@example" in an email, then leave the field: a gentle hint under it ("doesn't look like … yet"); it never blocks Save and disappears while you type.
- [ ] "Add more info" opens a sheet listing only what isn't on screen: Name details, Important dates, Address, Website, Messenger handles, Relations, Notes (Notes are already shown for existing contacts). Picking one adds its card, scrolls to it and focuses it; picking "Important dates" opens the date picker right away. When everything is shown the button is gone.
- [ ] Dates: the picker still allows a date without a year; type chip offers Birthday, Anniversary, Other, Death, Custom….
- [ ] Address: type chip and "−" on top, then Street, (PO box, Neighbourhood if the address has them), Postcode + City, Region + Country.
- [ ] Handles: service chip (Signal, Matrix, …, Other → "Service name"), per-service hint and warning under the handle.
- [ ] Relations: person icon picks a contact; the type chip opens the searchable relation list.
- [ ] Labels (for the chosen account) as filter chips with a tick; Notes; Call screen background (existing contacts); private contacts show "When they call".

**Safety and flows (unchanged behaviour)**
- [ ] New contact "Save to" chip: Private (only in Parley) or each account with its count, current one ticked; existing contacts show "Saved in …"; private contacts show "Private · only in Parley".
- [ ] Type a name or number of an existing contact in a new contact: the "already exists" card appears (Open / Add these details / Someone else).
- [ ] A contact with read-only rows (e.g. from an Exchange or messenger account): those rows show a lock, no "−" and a plain type label; saving keeps them. "Edit this copy" edits one raw contact only.
- [ ] Google-only fields (File as, custom fields), PO box and neighbourhood survive an edit (compare before/after in another contacts app).
- [ ] Temporary contact: edit and save → "Keep this contact?" as before. QR-received contact (encrypted QR › Add): after saving, "Met at…" appears (the `&hs=` route still works).
- [ ] Private contact: photo is encrypted (note under the photo); editing needs the vault unlocked.
- [ ] Undo/"What changed" lists the edit as before.

**Back, discard, accessibility**
- [ ] With changes, swipe back slowly (Android 14+): the editor shrinks with the gesture; release → "Discard changes?" with "Your changes … haven't been saved." Cancel the swipe: it springs back. ✕ asks the same. Without changes, Back and ✕ just leave.
- [ ] Largest font and display size: fields wrap, chips and "−" stay tappable (48dp), nothing overlaps.
- [ ] Arabic/Urdu (RTL): cards, chips, "−" and the chevron mirror correctly; numbers stay left-to-right.
- [ ] Landscape phone or tablet/foldable: two columns: photo, account, duplicate card, name and work on the left, the field cards on the right, each scrolling on its own. Portrait tablet: one column at most ~640dp wide, centred.
- [ ] TalkBack: group titles are headings; type chips read "Type: Mobile", action "Change type"; "−" reads "Remove number/email/…"; the Save spinner reads "Saving".
- [ ] Light and dark theme, and pure black: cards are visible against the background.

## 18. Safety hotfix (3.4.1)

### 18.1 Calls and screening

Emergency calls (use your country's test procedure or a number your carrier documents for testing; never place a real emergency call just to test — the rows below can also be checked with a number added under Blocking › Emergency where noted, or by stopping at the point a question would appear):
- [ ] Settings › Calls › "Confirm before calling" on, dual-SIM phone with no default SIM: typing 112 (or your local emergency number) and pressing Call shows **no** confirmation, **no** SIM question and **no** dial-guard warning; the platform picks the network.
- [ ] "Call with SIM 2" on an emergency number places it on SIM 2 without questions.
- [ ] Pocket guard on, a favourite/shortcut set to an emergency number, proximity sensor covered: no pocket question.
- [ ] A call-time limit or allowance on "everyone": an emergency call is never timed, warned or ended, and has no "End in 1 min".
- [ ] Emergency window: it starts when the emergency call starts. During the call, an incoming call from a hidden or blocked number is **not** screened; Blocking shows the "within an hour of an emergency call" countdown right away. After the call ends the countdown restarts at 60 min.
- [ ] Change the phone's date/time by a day during the window: the window keeps running (not ended early). Reboot during the window: the countdown survives (wall-clock fallback).
- [ ] A call arriving while the phone is in emergency callback mode (carrier-dependent) rings without screening and without "Block & decline".
- [ ] App lock on: the lock screen shows "Emergency call". Tap it: a keypad opens; "Call" stays disabled for ordinary numbers and enables for your emergency number; Cancel closes it. Also check the lock screens of the direct-dial widget setup and the contact picker.

Wildcard rules:
- [ ] Add a wildcard block rule `+33 6*` (or your country) and test a matching and a non-matching number with "Test a call": results as before.
- [ ] Import or type a pattern of 199 `*` followed by `9`: saving is instant, "Test a call" answers at once, and a real incoming call is screened without delay. A pattern longer than 200 characters is refused as "Not a valid pattern".

Work profile:
- [ ] Phone with a work profile whose policy allows cross-profile caller ID, "Block non-contacts" on: a call from a number saved only as a **work** contact rings (not blocked) and shows "Work profile" on the call screen. With the work profile paused or the policy off, behaviour is as before.

Incoming-call notification:
- [ ] Do Not Disturb set to "Starred contacts only" (calls): a call from a starred contact shows the full-screen answer UI / heads-up while the phone rings; a call from a non-starred number stays silent as DND says.
- [ ] System settings › Notifications › Parley › "Incoming calls" set to Silent (or off): the next incoming call opens the call screen directly; Settings › Privacy dashboard (notification health) shows "Incoming-call alerts" with a Fix button that opens that channel's settings; the Home banner appears.
- [ ] Android 14+: revoke "Full-screen notifications" for Parley: the health check flags it (as before) and incoming calls still open the call screen.

Caller location off the main thread:
- [ ] Cold start the app by an incoming call from an unknown number in another region: the call screen appears without a stall; the "where from" line appears a moment later.
- [ ] Recents with many unknown numbers from different countries: first fling is smooth; locations fill in without jank. Number history and the number-action sheet still show the location.

### 18.2 Data and privacy

Notifications (id and channel registry)
- [ ] Force a scheduled backup to fail (pick a backup folder, then remove it or revoke access; run the backup worker): the "Parley backup" notification shows. Get a missed call: both notifications stay. Open Recents: the missed call goes, the backup alert stays.
- [ ] With the backup alert showing and two missed calls from different people: swipe both missed-call children away: the calls are marked seen (the badge clears) and "Remind me of missed calls" stops re-alerting.
- [ ] Incoming, ongoing, missed, blocked / likely spam, busy auto-reply, plan warning, reminders, follow-up, temporary contacts and private-name requests still post and keep their system channel settings (no duplicate channels in App info › Notifications).

Delete history for a number
- [ ] Needs a number with calls older than the newest 3000 in the system call log (import a call-history CSV with old calls for one number, or use a long-lived phone), archive on. Recents › number › Delete history › All: the dialog counts every call, old ones too. Delete, then Settings › History › sync now (or wait 6 h): none of them come back in the number's history.
- [ ] Undo right after deleting: every call returns, in the system log and in Parley. Range "Last month" deletes only the last month's calls.

Clipboard
- [ ] Android 13+: copy a number (contact long-press, Recents › Copy number, number history › Copy) and the backup recovery key: the clipboard preview shows dots, not the text; paste still works. Gboard's clipboard history doesn't keep them.
- [ ] Android 12 and older: copying shows the "Copied" toast.

Private-name requests
- [ ] A third-party dialer asks for private names: the notification on the lock screen shows only "Private name requests". Tap Allow from the lock screen: the phone asks to unlock first; only then is the app approved. Same for Don't allow, and for the Directory variant.
- [ ] Android 10–11: Allow from the lock screen opens the unlock screen first, then records the answer (no Parley window shows).

App lock
- [ ] App lock on, "Lock after" immediately: open Parley, press Home, open Recents: Parley's thumbnail is blank (Android 13+: no screenshot at all). Return: the lock screen shows at once, with no flash of contacts.
- [ ] "Lock after" 1 minute: switch away and back within a minute: still unlocked; after a minute: locked, with no flash of content.
- [ ] Phone with a screen lock where the fingerprint sensor is unavailable (e.g. no fingerprints enrolled with biometrics-only prompts, or sensor busy): Unlock asks for the PIN / pattern instead of letting you in. Phone with no screen lock at all: the app lock lets you in (it can't work without one).
- [ ] Parley locked: select a phone number in another app › "Call / Message with Parley": Parley asks to unlock first; cancel closes the sheet. After unlocking, contact names and "My details" show as usual. App lock off: the sheet opens straight away.
- [ ] Discreet mode on, app lock on: tap the "Private hidden" Quick Settings tile on an unlocked phone: Parley asks to unlock; cancel leaves private contacts hidden; unlock shows them and the tile flips. Hiding again needs no unlock. App lock off: the tile toggles straight away (after the phone unlock if on the lock screen).

Companion app
- [ ] Parley Lists from the same build/source: its packs list and install as before. Install a Parley Lists signed with a different key (e.g. a debug build next to a release Parley): Blocking › Lists shows "signed by a different developer" and nothing is read from it.

Save and backup errors
- [ ] Edit a contact, tap Save and immediately press Back: no "Save failed" message appears.
- [ ] A backup where one feature section fails (e.g. debugger-injected exception in Circle export): the backup completes and its message names the part left out ("Some parts couldn't be included this time (circle)…"); scheduled backups post the notification.

## 19. Consolidation (3.5)

### 19.1 Data model

Phone-number identity and the key migration
- [ ] Before updating (on the previous version): add a call note to a contact whose number is saved in national form ("06 12 34 56 78"), pick a SIM for that number (contact › number › SIM), and let that number ring once and hang up. Update, keep Parley open for a minute (the migration waits about 30 s after start): the note still shows on the contact page, in the timeline, in the number's history and on the incoming-call screen; the SIM choice is still used when calling; "Why it rang" still lists the ring.
- [ ] Two contacts in different countries whose numbers end in the same 9 digits (for example +33 6 12 34 56 78 and +34 6 12 34 56 78): a call from one is named correctly in Recents, the keypad, the missed-call notification and the call screen; a note taken during the call shows only on that contact. Notes from before the update that the migration couldn't attribute keep showing on both (the old key can't tell them apart); new notes never do.
- [ ] Settings › Messaging › Messaged numbers: entries from before F7 (no number shown) now show under the right number when that number is a contact or in the call history.

One source for calls
- [ ] With "Keep full call history" on, clear the system call log from another dialer: Recents, the Favourites tab's frequent row, the keypad's "last called" ranking, the missed-call badge, the caller's "Last call …" line on the call screen, Contact health's "not called in 2 years", "Most called" favourites sort and the blocking "Test a call" dry run all still see the archived calls.
- [ ] Call a private (vault) contact: the private call shows in Recents (unless discreet mode) and counts towards a call-time allowance.

Allowances and supervision
- [ ] Call time › add a daily allowance for a contact (e.g. 2 min), supervised mode on. Talk for 3 minutes, then clear the call log from another dialer: calling that contact again still says the allowance is used up (the call-usage ledger counts it). Calls from before the rule existed still count from the call history, and a call is never counted twice.
- [ ] Emergency calls are never counted and never held back by an allowance.

Backup and restore (new parts)
- [ ] Set up on phone A: a pinned note, a preferred messenger, a relation link ("Spouse: Anna" pointing to Anna), a call note, a temporary contact (7 days), a call-time rule with an allowance and "Never limit" for a favourite, the proximity / pocket-guard / re-alert switches, a saved Recents filter and plan minutes for a SIM, a spam list installed from a .parleylist file, a few blocked calls, and a private contact with calls (vault unlocked when backing up). Back up, restore on phone B (all options on, settings too): every item above is back, the temporary contact still expires on its date, the relation link opens Anna, the blocked-call log shows the old entries, the private contact shows its calls.
- [ ] Restore the same backup a second time: nothing is duplicated (notes, blocked-log entries, private calls, spam list).
- [ ] On phone B turn supervised call time on, then restore: the report says the backup's limits weren't applied and offers "Apply the backup's limits"; it asks for the app lock and only then replaces them. Closing the dialog leaves the current limits untouched.
- [ ] A scheduled backup run right after a reboot (before opening Parley) contains the block rules (restore it with "Blocking" only: the rules come back) and warns about private contacts left out when the vault is locked.

Delete all Parley data
- [ ] Settings › Privacy & security › Delete all Parley data (also found by searching "wipe" or "reset"): the button stays disabled until DELETE is typed; with the app lock on it asks to unlock.
- [ ] With backups set up and "Make a backup first" ticked: a backup is written first; with the backup folder removed, the backup fails and nothing is deleted.
- [ ] Without the extra ticks: Parley restarts as new (onboarding, no private contacts, no notes, no rules, no Circle, default settings); Android's call log and contacts are unchanged in another app.
- [ ] Tick "Also delete Android's call history": the system call log is empty afterwards. Tick "Also delete the contacts stored on this phone": phone-only contacts are gone, Google contacts stay (check on contacts.google.com that nothing was deleted there).
- [ ] During a call the wipe refuses ("Finish the call first").

Safety of multi-step changes
- [ ] Move a contact to private while its full record can't be read (e.g. revoke contacts permission right before, from Settings): the move is refused and the phone contact stays.
- [ ] "Recently deleted & changed" › Restore: tap Restore twice quickly: one contact comes back, not two.
- [ ] With "Private call history" on, move a contact with calls from months ago into the vault: all their calls leave the system call log and appear as private calls.

### 19.2 Concepts, menus and settings

Messenger catalog
- [ ] `./gradlew :core:common:test`: `MessengerCatalogTest` passes. Remove one messenger `<package>` from the manifest's `<queries>` (or add a package to `MessengerCatalog` only): the test fails and names the package.
- [ ] With Molly's UnifiedPush build (`im.molly.app.unifiedpush`) or Telegram Plus/Beta installed and nothing else of theirs: they show under "Message or call on…" › Message on, and a scanned Signal or Telegram link opens in them.

One "Message or call on…" sheet
- [ ] Open it from Recents (a call's ⋮ or swipe), the keypad (type a number › Message), a missed-call notification, a scanned QR number, the "Message a number" tile, a saved contact's long-pressed Message tile, a private contact's Message, and the list row's message button with no usual app: every time the same layout, titled "Message or call on…": the name (for a person) and number, green **Call**, then **Message on** (apps with their icons, SMS), then **Call on**.
- [ ] Saved contact with several numbers: the number chips switch Message on and Call on to that number. With "Always use this" ticked, picking an app marks it "Usual" next time and the Message tile shows its name.
- [ ] Long-press the contact page's Video tile (or tap it with two video apps and no usual one): the same sheet opens, not a dialog; a video button there starts the call and becomes the usual video app.
- [ ] Unsaved number: the country chip, "Optional message" and "Send my details" are there; Telegram's ⋮ still offers Open chat / Open profile; WhatsApp and WhatsApp Business are two rows (no "which one?" dialog). "Save for 7 days so apps can offer calls" shows only for a number that isn't saved.
- [ ] Contact page: the section is called "Message or call on…" and shows the same app icons and round Message / Voice / Video buttons as the sheet.

Names
- [ ] English: the tab bar and empty states say "Favourites"; the Recents header action and the Insights screen say "Call insights"; the ⋮ › Tools, the Settings row and the screen all say "Blocking & screening"; "email" is never hyphenated; "your Circle" is capitalised. See docs/GLOSSARY.md.

Allow through Do Not Disturb (label page)
- [ ] A label with 3 members, 1 already starred: turn on "Allow through Do Not Disturb": the dialog lists the 2 people who will be starred "and appear in Favourites", and the button says "Star 2 and open settings". Cancel: nothing is starred and the switch stays off. Confirm: both appear in Favourites and Android's Do Not Disturb page opens.
- [ ] Add a new member to the label: "Star 1 new member" opens the same dialog with that one name; nothing changes until you confirm.
- [ ] Turn it off: the toast counts the contacts unstarred; the one starred before stays starred.

Menus and Tools
- [ ] Every tab's ⋮ has at most 7 items and ends with Tools and Settings. Recents: Export…, Call list layout, Clear call history, What the icons mean (+ Speed dial with the keypad docked). Contacts: Select all, Add several numbers…, Find & merge duplicates, (Reorder favourites), Who's in…. Keypad: only Tools and Settings. Circle: Who's in…, Circle settings.
- [ ] ⋮ › Tools and Settings › Tools (top of the list) open the same page: Birthdays & dates, Temporary contacts (with the count), Contact health check, Scan QR code, Import & export contacts, Blocking & screening, Expecting a call (switch), Messaged numbers, History & undo, Backup & restore, Privacy dashboard, and Lock now with the app lock on. Each opens its screen.

History & undo
- [ ] ⋮ › Tools › History & undo: tabs Contacts, Calls, Snapshots. Delete a contact: it's under Contacts with Restore. Delete a call in Recents: it's under Calls with Restore. Snapshots shows the "since yesterday / week / month / 6 months" chips.
- [ ] Settings › Backup & sync › Daily snapshots opens History & undo on Snapshots; Settings › Recents & history › Deleted calls opens it on Calls. The contact empty state's action switches to Snapshots.

Settings
- [ ] Settings lists "Layout & gestures" under Appearance: Navigation bar, Open on, the combined layouts, Tapping a call in Recents, Call & message buttons, Swipe actions, Simple mode. Appearance keeps theme, language, lists, names and tips.
- [ ] Recents & history: "Keep full call history" appears once, with the number of calls kept; with it on, "Numbers kept forever" lists them with a remove button; Excel-friendly CSV and Import call history are in "Export & import"; Show SIM in call history is under Advanced. A notice that used to open "Call history" (archive off / Android dropped calls) now opens this page.
- [ ] Calls, Contacts, Recents & history and Privacy end with a folded "Advanced" group. Search "proximity": the result opens Calls with Advanced unfolded and the row highlighted.
- [ ] Search "withheld", "bedtime", "parental", "rotation", "billing" and "recently deleted": each finds a setting; tapping it opens Blocking & screening, Call time, Backup & restore, the SIM list or History & undo › Calls. Search "plan minutes": it opens Calls on "SIMs & plan minutes". Search "scan qr": it opens the scan screen.
- [ ] Every setting you had before keeps its value after updating (theme, layouts, swipe actions, archive, retention, app lock…).

### 19.3 Screens keep their state

Contact editor (use Developer options › "Don't keep activities" for the process-death cases, or `adb shell am kill app.parley` while Parley is in the background)
- [ ] Edit a contact: change the first name, add a phone row, pick a photo, turn on a label, add a relation with the contact picker. Rotate the phone: every change is still there, the Save button is still ready, and Save writes all of it (the relation opens the picked person).
- [ ] Same edit, then switch dark mode, change the font size and change the app language (Settings › Appearance › Language) one after the other: the edit is still there each time.
- [ ] New contact: type a name and number, tap the photo and, in the photo picker, press Home; kill Parley; come back to it: the editor shows the name and number, and choosing a photo sets it. The "Save to" choice (Private, or an account) is kept too.
- [ ] Edit a private (vault) contact: change "Who is this?" and rotate: the text stays.
- [ ] Edit an existing contact, add a blank phone row only, rotate: Save stays disabled (a blank new row isn't a change). Cancel (×) closes without asking.
- [ ] Save a big contact and press Back straight away: the contact is saved completely (open it: every field, the call-screen background and relation links are there), and no "Save failed" message appears.
- [ ] Edit a temporary contact and save: "Keep this contact?" appears; rotate while it's open: it's still there; each answer closes the editor and opens the contact.
- [ ] Save failures still say so (for example make the contact's account read-only or remove the contacts permission while editing): "Save failed: …" and the edit stays.

Contact page
- [ ] Open a contact with calls, a pinned note, a messenger and a Circle rhythm: every section shows as before. Edit the pinned note: it shows at once. Set a Circle rhythm from its dialog, then change the pinned note: both stay (neither overwrites the other).
- [ ] Choose "Message on…" › Always: the choice is kept after leaving and reopening the page.
- [ ] Set a default number, send to voicemail, star/unstar, set the ringtone, change "Delete after…": each takes effect and shows its message; rotate: the page keeps its data without a blank flash.
- [ ] Log an interaction (FAB or ⋮): it shows in the timeline; a relation opens that person (or asks which one when several share the name).
- [ ] Rotate while scrolled half way: the page stays where it was.

Recents
- [ ] 1000+ calls: fling Recents top to bottom: smooth; day headers ("Today", "Yesterday", weekday dates) are right, and tapping one opens the day summary.
- [ ] Leave Parley open on Recents over midnight (or change the phone's date): "Today" becomes "Yesterday" without reopening.
- [ ] Filters (Missed with its count, Incoming, Voicemail), the search, multi-select and "Block N" work as before, also in the docked keypad's list (combined layout).
- [ ] Settings › Calls › Voicemail still opens Recents on the Voicemail chip; a missed-call notification's "Call back list" opens Recents on Missed.
- [ ] Unknown numbers show their place ("Paris, France") under the name without the rows jumping while scrolling.

Keypad
- [ ] Type 3–4 digits quickly with 3000 contacts: results follow every key without lag; keys never swap order when rolling between them (docked and on the Keypad tab), and the number field never loses a digit.
- [ ] Header search on the Keypad tab: "jose" finds "José"; a number fragment finds its contact; a private contact appears (not when private contacts are hidden); no match offers "Create contact".
- [ ] Nothing typed, Call: the last number you called comes back. Long-press 2–9: speed dial (or the "not set up" dialog). Two SIMs: the SIM pill shows the SIM a Call would use for the typed number.
- [ ] Save for a while from the chips: saved, the field clears and the message says for how many days.
- [ ] Open a tel: link or a missed-call "Call back" with the app closed: the number is on the keypad.

Contacts list
- [ ] 1000+ contacts: letter headers are right (accents under their base letter, digits under #), the fast-scroll rail jumps to each letter (and ★ to the favourites), select, swipe and search work as before.

## 20. Quality (3.6)

### 20.1 Tests and CI

Automated (no phone needed; `.github/workflows/parley.yml` runs all of it on every push and pull request that touches `parley-phone/`)
- [ ] `./gradlew :core:common:test testDebugUnitTest` passes. The Robolectric tests cover contact saves (only changed rows are written, read-only rows are never touched, an emptied copy is removed, no delete without an undo copy, work-profile lookup), call screening (hidden, unknown, emergency, fail-open, work profile, rules and their log), the outgoing-call gate (emergency, confirmation, SIM question), an encrypted backup → wipe → restore round trip with every registry section, the vault / call-history / interaction crypto, and every Room migration from version 1 to 8. The first run downloads Robolectric's Android runtime (about 150 MB).
- [ ] `./gradlew detekt` passes (new findings fail; existing ones are listed in `config/detekt/baseline.xml`; after a large refactor run `./gradlew detektBaseline` and review the diff).
- [ ] `./gradlew checkHardcodedText -PfailOnHardcodedText=true lintDebug` passes (lint errors fail every module's build; warnings don't).
- [ ] Add a permission to `app/src/main/AndroidManifest.xml` (e.g. `android.permission.READ_CALENDAR`) and run `./gradlew :app:assembleDebug`: the build fails with "Permissions not on the allow-list" (the check runs with every build, `bundleRelease` included). Revert.
- [ ] `./gradlew :app:checkReleaseApkSize` builds the unsigned release APK and prints its size; it fails above 16 MiB.

On a phone (debug build only)
- [ ] Install the debug build and use it for a few minutes (open Recents, Contacts, a contact, Settings, take a call): `adb logcat -s StrictMode` lists main-thread disk access and leaked resources as log lines only; the app never crashes because of them. The release build logs none of them.

### 20.2 Design system and accessibility

Automated
- [ ] `./gradlew detekt` passes with the `parley` rules on: add `AlertDialog(`, `TopAppBar(`, `ModalBottomSheet(`, `RoundedCornerShape(8.dp)` or `Toast.makeText(…)` to any screen in `app/` and run it: `DesignSystemComponent` / `SystemToast` fail the build and name the core/ui replacement. Revert.
- [ ] `./gradlew checkHardcodedText -PfailOnHardcodedText=true` also catches `actionLabel = "…"` and `showSnackbar("…")`.
- [ ] `./gradlew :core:common:test` includes `ContrastTest` (every avatar colour reaches 4.5:1 with its initials' ink).

Top bars, dialogs, sheets (walk through Settings, Blocking & screening, Call history, SIMs, Speed dial, Backup, Privacy dashboard, Labels, Temporary contacts, History & undo, Birthdays, Health check, Scan QR, a contact, the editor, the picker)
- [ ] Every screen's top bar has the same Back arrow on the start side (on the right in Arabic/Urdu) and TalkBack reads "Back" in the app's language on each; the title is one line with "…" when long.
- [ ] Settings screens keep their large title that collapses on scroll; screens with a scroll tint (Birthdays, Health, Labels, Temporary contacts) still tint.
- [ ] Delete a call from its history, a label, a voicemail, a temporary contact, a private contact, selected contacts, all "Messaged numbers", a blocking rule; discard an edit: each confirmation shows the action in red with Cancel before it. Other questions (Move, Open, Save, Import) keep the normal colour.
- [ ] Dialogs and sheets have the same rounded corners (28 dp); sheet titles are read as headings by TalkBack.

Snackbar
- [ ] Contacts tab: delete a contact: the "Deleted … Undo" snackbar sits above the navigation bar and the "Create contact" button moves up for it (it never covers the button). Undo brings the contact back. Same on a contact's page with the "Log interaction" button, and on Recents after deleting a call.
- [ ] Switch the app language to German or Arabic, delete a contact: the snackbar action is "Rückgängig" / Arabic, not "Undo".
- [ ] Copy a number on Android 12 or older, or tap "Open in…" for an app that isn't installed: the message is a snackbar inside Parley, not a system toast. From the "Message a number" sheet over another app (Quick Settings tile), "Paste" with nothing on the clipboard still shows a system toast (no Parley screen is behind it).
- [ ] Number history › delete a call; Add several numbers › save: their Undo snackbars still work and appear inside the screen.

Accessibility
- [ ] TalkBack: Contacts A–Z letters, Recents day headers, "Frequent", the contact page's section titles, the People card's sub-titles and the call-history filter sheet's titles are announced as headings; swipe up/down with "Headings" navigation jumps between them.
- [ ] TalkBack: every on/off row (Blocking & screening, Call time, Settings, Call history's "Keep full history", Folder sync, Markdown export, the contact page's "Send to voicemail", the editor's "Include year") is one focus stop read as "<title>, switch, on/off"; double-tap toggles it. The switch on a blocking rule and on a spam list says which rule or list it is.
- [ ] Call-history filter sheet: the × on a saved filter is a full-size touch target; deleting shows "Filter … deleted" with Undo, which brings it back.
- [ ] Simple mode: TalkBack on "Leave" says the tap explains and the long press ("Leave simple mode") leaves.
- [ ] Settings › Accessibility › Remove animations on, then an incoming call: the answer slider's thumb doesn't pulse.
- [ ] Font size at the largest setting and display size largest: avatar initials (two letters) stay inside their circle in Contacts, Recents and on the contact page.
- [ ] Dark theme, incoming call while Parley is open on another screen: the ringing "Return to call" chip's text is dark on the light teal (readable); during the call it is white on a darker green. Accept/call buttons and the call swipe action are the darker green with white text.
- [ ] Avatars without a photo: lime, orange, blue and green circles have dark initials; indigo, purple, pink, brown, deep purple and teal ones white.

Theme and density
- [ ] Settings › Appearance › Dynamic colour off (or an Android 10–11 phone): dialogs, sheets, chips, outlines and the search field are tinted blue like the cards, with no purple anywhere, in light and dark. AMOLED black on: sheets, menus and dialogs are near-black too.
- [ ] Settings › Appearance › List density › Compact: rows in Contacts, Recents, Keypad results, Circle, the picker, Insights, Temporary contacts, Birthdays, Duplicates and History & undo get visibly shorter (avatars 36 dp); Comfortable restores them. Trailing call/message buttons still fit and work.
- [ ] Arabic or Urdu: opening a screen slides it in from the left, Back slides it out to the left.
- [ ] Contact and label merges show a merge icon (not the call-merge one), "Separate" and "Unlink" an unlink icon; the default number's menu star is filled while it is the default.

### 20.3 Performance

Start-up and the call path (a phone with a few hundred contacts and calls; Perfetto or `adb shell atrace` for the trace sections)
- [ ] With Parley closed (swipe it from Recents, or `adb shell am kill app.parley.phone`), ring the phone. The call screen or heads-up shows once screening decides (at most about 1.5 s; nothing is shown before that). A call a block rule or a spam list rejects never shows any notification, name, number or Answer button, not even briefly.
- [ ] In a trace of that call: `Parley.screenToRespond` (screening service), `Parley.screenCall` and `Parley.addToNotification` (in-call service) are there; no contacts or call-log scan runs in the process while the phone rings (no `ContactsRepository` / `CallLogRepository` loads; the "Last call …" line still shows for someone you called before).
- [ ] About ten seconds after that call ends, the process loads the rest (a trace shows the contacts and call-log reads then). Opening Parley right away works as before.
- [ ] Open Parley from the launcher: the system splash (the icon on Parley's window colour, dark in dark mode) stays until the app is ready; there is no blank white or dark frame before Recents. With the app lock on, the lock screen follows the splash directly and no contact flashes.
- [ ] With a Circle widget placed, it still updates while Parley runs (log an interaction, change the app lock). Remove every Circle widget: nothing about it keeps running (no widget refresh in logcat).

Data flow
- [ ] Add, edit or sync many contacts at once (e.g. a Google account sync, or import a large vCard): Contacts updates once the burst settles (about a second), not once per change.
- [ ] Link two contacts in the system Contacts app: a pinned note and the Circle rhythm follow within about 15 seconds of Parley running; nothing moves when nothing changed.
- [ ] Call archive with more than 5000 calls (import a large CSV): Recents shows the newest; "Delete calls with this number" (all time) removes old archived calls too; a backup includes all of them; making a number private removes all of its archived calls.
- [ ] Blocked calls: more than a year old are gone after the daily maintenance run; the list still shows recent ones.

Background work
- [ ] `adb shell dumpsys jobscheduler | grep -A3 app.parley.phone`: one daily maintenance job (battery not low), the reminders job at the chosen hour, and no separate daily housekeeping, history or screening jobs.
- [ ] Temporary contact expiry, call-log retention, the backup reminder, plan warnings and spam-list refreshes still happen (run the maintenance job with `adb shell cmd jobscheduler run -f app.parley.phone <id>`).
- [ ] Folder sync on with "Keep up to date": edit a contact; the folder is updated within a few minutes. Change a contact on the other phone: this phone picks it up within the hour, and about 30 s after opening Parley. Turning folder sync and the Markdown export off cancels all its jobs.
- [ ] Make one maintenance step fail (e.g. a vault entry that can't be read): logcat shows `ParleyMaintenance` "Maintenance step failed", and the other steps (archive retention, journal, snapshot) still run.
- [ ] Circle widget after a reboot (Parley not opened): someone in your Circle you called yesterday is not listed as due.

Missed calls and the archive
- [ ] Missed-call "Call back" with "Confirm before calling" on, or with two SIMs and no default: a small window asks first, as in Parley. Without questions the call starts at once. With the app lock on, the unlock is asked only when there is a question.
- [ ] With Parley closed, let a shared-cost or premium number from abroad ring once: its missed-call notification has no "Call back", and opening it from Parley shows the one-ring warning.
- [ ] Delete calls with a number, make that number private, then Undo: its calls don't come back into the archive (Recents and the number's history). Restoring a backup likewise leaves private numbers' calls out.
- [ ] Settings, Call history, turn off the full history: "Turn off and delete" is drawn in the error colour.

App lock and size
- [ ] App lock on Android 11+: unlock with a fingerprint, and with the PIN from the same prompt; on Android 10: fingerprint through the system prompt, and with no fingerprint enrolled the screen-lock confirmation. Cancelling keeps Parley locked. The private vault still asks and unlocks as before.
- [ ] Caller location ("Where is this number from") in English, German, Spanish, French, Portuguese and Arabic; with the phone in another language (e.g. Italian) it shows in English rather than not at all.
- [ ] `./gradlew :app:checkReleaseApkSize`: about 12.8 MiB (was 13.9 MiB), under the 16 MiB budget. `unzip -l` of the release APK has no `androidx/appcompat` resources and only the six geocoder languages.

Baseline profile
- [ ] The release APK has `assets/dexopt/baseline.prof`, and `app/build/intermediates/merged_art_profile/release/` lists `Lapp/parley/...` rules. After `adb install` of a release build, `adb shell cmd package compile --reset app.parley.phone` followed by `adb shell dumpsys package dexopt | grep -A2 app.parley.phone` shows `speed-profile` once the profile installer ran (or after the next start).
- [ ] With a device connected, `./gradlew :app:generateBaselineProfile` and the macrobenchmarks run as described in `docs/PERFORMANCE_BENCHMARKS.md`.

## 21. Hardening (4.0)

### 21.1 Security

What each item protects is explained in [SECURITY_MODEL.md](SECURITY_MODEL.md).

Private contacts: key lifecycle
- [ ] Fresh install on a phone **with** a screen lock: add a private contact, then `adb shell dumpsys keystore2` (or the "vault key" line in diagnostics, if shown) lists `parley_vault_detail_g1`. Opening its details asks for the fingerprint or PIN after 5 minutes; with the phone locked (another app in front, screen off), the details can't be opened by the app.
- [ ] Phone **without** a screen lock: add a private contact, then set a PIN. Open Parley, unlock it (or open a private contact and confirm): within a few seconds the vault moves to a new key (`parley_vault_detail_g2`), every private contact still opens, and the old key is gone.
- [ ] Update from 3.x with private contacts: after the first unlock, all of them still open and the old `parley_vault_detail_v1` key is replaced.
- [ ] Remove the screen lock (Settings › Security › None), then open a private contact: name, numbers, job title and the note for calls show, with "Some details can't be opened any more". Nothing is rewritten until "Keep what's left"; after it, the contact opens normally and a file sits in `no_backup/vault-unreadable/`.

Small records sealed at rest
- [ ] Add a pinned note, a call note and let a named caller be screened; `adb shell run-as app.parley.phone sqlite3 databases/parley.db "select pinnedNote from contact_meta; select text from call_notes; select callerName from blocked_calls"` shows only `\u0001rs1:` values. The app shows them normally, and a backup restores them readable.
- [ ] Update from 3.x with existing notes: about 30 seconds after opening Parley the same query shows them sealed; nothing was lost. The undo journal ("History & undo") and time-machine snapshots still restore.

Backups
- [ ] New backup passphrase: "password12" and "qwertyuiop12" show Weak with a hint and Save stays disabled; "correct horse battery staple" shows Strong or Very strong and saves.
- [ ] Restore a backup made on this phone: the options show "Made on this phone". Restore it on a second phone (same passphrase): "Made on another phone of yours".
- [ ] Restore a backup made by 3.x: "This backup isn't signed…" warning in red; restoring still works.
- [ ] Existing 3.x users: Backup shows "Mark this phone's backups as yours"; enter the passphrase once and the row disappears.
- [ ] Restore with "Settings" ticked from a backup that had the app lock and discreet mode on, onto a phone where they are off: the summary says safety settings weren't changed; "Apply the backup's safety settings" asks for the app lock and then turns them on. Closing the dialog leaves them off. Same for supervised call time and apps allowed to show private names.
- [ ] A `parley://qr` or `parley://simple` link whose header asks for 10,000,000 KDF rounds is refused at once ("damaged"), without a long wait.

Folder sync
- [ ] Choose a new sync folder: Parley asks how to store the files; "Encrypted (recommended)" asks for a new sync passphrase (Strong required). The folder then holds `.parley-sync` and `*.parleycard` files, none readable as text.
- [ ] On the second phone choose the same folder, "Encrypted": it asks for the passphrase chosen on the first phone; a wrong one says so; the right one syncs both ways.
- [ ] "Plain vCard files" needs the "I understand these files are readable by anyone with the folder" tick; the screen then says the files are readable, with "Switch to encrypted files". Switching removes the `.vcf` files this phone wrote.
- [ ] A folder set up with 3.x shows the choice and doesn't sync until one is made. A 10 MB `.vcf` in a plain folder is skipped (left alone), not loaded.

Bounded readers
- [ ] Import a vCard with a 20 MB PHOTO line, a CSV with a 5 MB quoted cell, and a zip bomb renamed `.parleylist`: each fails with a short "too large" message, and Parley stays responsive.

Overlays, shares and links
- [ ] Android 12+: with a floating overlay app (a screen dimmer or chat bubble) on, open the app lock, a private contact, Delete all data, a backup restore, Private names, or the "Message a number" sheet: the overlay disappears while the screen shows and comes back afterwards.
- [ ] Share a `file:///sdcard/...vcf` to Parley with `adb shell am start -a android.intent.action.SEND -t text/x-vcard --eu android.intent.extra.STREAM file:///sdcard/Download/a.vcf app.parley.phone/app.parley.MainActivity`: nothing is imported. The same file shared from a file manager (a `content://` URI) imports.
- [ ] A note containing `https://paypa1.com/login`: tapping it opens the web-address sheet with the domain large and the look-alike warning, not the browser.
- [ ] `adb shell am start -a com.android.contacts.action.JOIN_CONTACT --el com.android.contacts.action.CONTACT_ID <id> app.parley.phone/app.parley.picker.PickerActivity`: the picker title names the contact; picking another asks "Link these contacts?" naming the app and both contacts.

Entry points and build
- [ ] With the app lock on (lock at once): the contact picker (from another app), the "Message a number" sheet and the dial-widget setup all show the lock; with "Hide screen content" on, their screenshots are black and Recents shows them blank.
- [ ] `./gradlew :app:testDebugUnitTest --tests '*ExportedComponents*'` passes; add `android:exported="true"` to any activity or service without a permission and it fails naming it.
- [ ] Change one byte of a cached dependency jar under `~/.gradle/caches/modules-2` (or edit a checksum in `gradle/verification-metadata.xml`): the build fails with a verification error.
- [ ] Spam lists: install a list from Parley Lists, then a list with the same id signed by another key: refused. Removing every Parley Lists list unpins its key.

### 21.2 Robustness

Contact saves changed elsewhere
- [ ] Open a synced (Google or CardDAV) contact in Parley's editor and change the note. Meanwhile, on the web or in another contacts app, change the same contact's phone number and let it sync. Save in Parley: "Changed elsewhere" opens and nothing is written yet.
- [ ] "Merge field by field" with those two changes (different fields): both are kept without a question; the editor says "Merged. Check the result, then save." and saving keeps their number and your note.
- [ ] Change the same field on both sides (the phone number): "Merge field by field" lists "Phone numbers" with Theirs / Mine and both values; the pick is what gets saved. Rotate the phone while the list shows: the picks stay.
- [ ] "Show their version" shows the contact as it is now, with your edits set aside; "Keep mine" saves what the editor shows over their change.
- [ ] Delete the contact in another app while editing it, then save: the sheet says it was removed and "Keep mine" saves your edits as a new contact.
- [ ] Put Parley in the background while editing (with "Don't keep activities" on in Developer options), change the contact elsewhere, come back and save: the change is still caught.
- [ ] With "Don't keep activities" on, edit a phone-only contact, leave Parley, and meanwhile link it to another contact (or let an account's copy join it) in another app. Back in Parley, save: the edit lands on the phone-only copy and the other copy is untouched. If that copy left the contact, "Changed elsewhere" opens first and the other copy's numbers are never deleted.
- [ ] An ordinary edit (nothing changed elsewhere) saves straight away as before.

Folder sync and indexing at scale
- [ ] Folder sync with a large address book (a few thousand contacts, some with photos): the first sync writes every file without running out of memory; the second run with nothing changed finishes in seconds and the folder's files keep their modified times.
- [ ] Edit one contact: the next sync rewrites only its file. Edit one file on another device: only that contact changes here.
- [ ] Move a contact to another account (or let a first Google sync give it a new key): its file stays and no deletion spreads to other devices.
- [ ] Delete a file (on another device) for a contact you edited here in the last three days: the sync pauses and asks; confirming deletes it (journaled). An older contact is deleted without the question (unless many go at once).
- [ ] After updating from 3.x with folder sync on, the first sync reads each contact once and writes nothing it didn't need to; the one after reads nothing.
- [ ] After updating from 3.x with folder sync on, choose "Encrypted": the folder then holds only `.parleycard` files (plus any `.vcf` that isn't Parley's); the next sync imports and duplicates nothing, contacts with only a name included. If some `.vcf` can't be removed, the Sync screen says how many are still readable there. Choosing "Plain vCard files" instead keeps the sync state: nothing is imported again.
- [ ] Delete a file on another device for a contact this phone got from the folder yesterday: it is deleted here without a question. For a contact you edited here, only that deletion waits; the rest of the run syncs, and a notification (no name on the lock screen) opens the Sync screen to confirm.
- [ ] Rename a label: the next sync rewrites its members' files with the new name.
- [ ] Encrypted folder: copy a contact's file aside, edit the contact, sync, then put the old copy back: the next sync keeps the edit and writes the file again. Put back the file of a contact deleted here: it is not imported again.
- [ ] A cloud folder that is still loading its listing (a provider that shows a spinner in the Files app): the sync says the folder is still loading and deletes nothing.
- [ ] With a long call history, a new call shows in Recents and Insights at once; the insights numbers equal those after reopening the app.
- [ ] While a Google account syncs thousands of contacts, the Contacts list stays responsive and settles once the sync ends.

Calls (no visible change expected)
- [ ] Incoming call from an unknown number: the unknown-caller tone plays (if set), "Ignore" silences it, "Block & decline" blocks and declines with Undo; a rule's own ringtone and "Ring loud" still apply; a call over its allowance rings silently; a limit still ends an outgoing call; nothing of the above ever touches an emergency call.
- [ ] Two calls: hold, swap, merge; the held call resumes when the other ends. Post-dial "Send 1234?" still asks. "Why didn't it ring?" on a missed call shows the same reasons as before.
- [ ] Swap a SIM between calls on a dual-SIM phone: the call screen shows the new SIM's own number.

Links and navigation
- [ ] Each of these still opens the same place: a `parley://qr`, `parley://simple` and `parley://template` QR code scanned with another app; a `tel:` link; the launcher shortcuts (Scan QR, Message a number); the Quick Settings tiles; a missed-call notification (Recents, missed only), "Call back" and a caller's notification (their contact or number history); the post-call card's Block (the rule editor with the number) and Report; Settings' search results; another app's "Add to contacts" and Quick Contact.
- [ ] Numbers, names and labels with `+`, `/`, `&`, `%`, `#` or spaces open correctly (number history, a label's page, the editor prefilled from another app).
- [ ] Rotate on every screen reached from Tools and Settings, then press Back: the back stack is the same.

Forms keep their input
- [ ] Rotate, or switch dark mode, while typing in: the blocking rule editor (pattern, note, schedule, SIM), the new-label and rename-label dialogs, a label page's SIM and rhythm choices, Simple mode setup (the person picker's search), "Add several numbers" (text, ticks after "Review", naming, account), "Choose columns" for a CSV (changed columns and the header switch), the call-time rule editor, speed dial's search, the date dialog, the blocking screen's test number and "Add number", saved filters' name. Everything stays.
- [ ] With "Don't keep activities" on, leave and return to those screens: the same holds.
- [ ] Passphrases and passcodes (backup, Simple mode file and QR, shared list import) are empty again after the activity is recreated: they are never kept in saved state.

### 21.3 Keys and sealed records after the review

1. **Transient Keystore error keeps notes.** With call notes and pinned notes present, force a Keystore failure (reboot and open Parley before the first unlock, or use a debug build with the key alias revoked). Parley shows a notice card instead of empty notes. After unlocking, the notes are all back, not replaced by blanks.
2. **Interrupted vault upgrade.** Start the vault key upgrade (unlock the vault after updating from 3.6), then force-stop Parley mid-way. On the next start all private contacts open, and the upgrade finishes on its own.
3. **Passphrase strength.** Type `password123`, `Summer2026!` and a common word repeated: each is rated weak. Four random words are rated strong.
4. **Backup origin.** Restore a backup made on this phone: it is shown as from this phone. Restore one made on another phone, or with the signature stripped: it is shown as unconfirmed, and the restore still works after you confirm.
5. **Overlay protection.** With a screen-overlay app running (for example a floating-bubble app), open the backup passphrase and "Delete all Parley data" dialogs. Taps through the overlay are ignored.

## 22. Corrections (4.1)

### 22.1 Keypad: one place per number action, one keypad panel

Number actions (Keypad tab, and Recents with the keypad docked):
1. Type a number that matches no contact, e.g. `0000`. The results show, once each and in this order: **Message or call on…** (with "WhatsApp, Signal, Telegram, Viber or SMS" under it), **Create new contact**, **Add to a contact**, **Save temporary contact**. No chip row shows above the keypad.
2. Tap each row: the apps sheet opens; the contact editor opens with the number; the contact picker opens; the temporary contact dialog opens.
3. Type `06` (two digits, no contact match): only **Message or call on…** shows, as a row.
4. Type digits that match contacts (e.g. the first digits of a saved number). The contacts fill the list and the chip row above the keypad offers **Message or call on…**, **Create new contact**, **Add to a contact**, **Save temporary contact**. None of these appear as rows in the list.
5. Type a saved contact's full number: the chip row offers only **Message or call on…**.
6. Type `*#06#` or a USSD code such as `*100#`: no rows and no chips.
7. Type letters on a hardware keyboard (name search): no rows and no chips.
8. In Recents with the keypad docked, fold the keypad (keypad button left of the Call pill) with `0000` typed: the rows stay in the list and the keypad button sits in the corner; with contacts matching, it sits above the chip row.

One keypad panel:
1. Switch **Calls layout** between separate Keypad tab and Combined. Type `0000` in each: the panel's top edge, the number and every key sit at the same height in both (no grab handle above the docked number).
2. Docked: swipe down on the number or on the keys to fold the keypad; scroll Recents to fold it; tap the keypad button left of the Call pill to fold it; tap the floating keypad button to bring it back.
3. TalkBack, docked: the panel offers the "Hide on-screen keypad" action; the keypad button beside the Call pill folds it.
4. Large font (200 %) and landscape: the docked panel still scrolls inside its height and never covers the whole list; side by side on a wide screen it folds aside.

### 22.2 Contact page and contact list

Map links for addresses
- [ ] Edit a contact › Add more info › Address › "Add from map link". Paste each and check the line under the field before tapping Add: a Google Maps place link (`google.com/maps/place/NAME/@lat,lon…`, shows the name and the spot), `geo:48.8584,2.2945?q=48.8584,2.2945(Eiffel Tower)`, an OpenStreetMap link and an `osm.org/go/…` short link, an Organic Maps or CoMaps share (`omaps.app/…`, `comaps.at/…`), an OsmAnd link, a Magic Earth link, an Apple Maps link, a full Plus Code (`849VCWC8+R9`) and plain coordinates. Each shows "Found the exact spot: …". Nothing asks for internet access, and Airplane mode changes nothing.
- [ ] Paste a `maps.app.goo.gl/…` short link: the dialog says it will be saved as a link and why. Paste "hello": "No map link, Plus Code or coordinates found", and Add stays off. Paste works only when tapped.
- [ ] With the address empty, Add fills the street with the place's name (or the coordinates); with an address typed, the text stays. The row then says "Exact spot saved from a map link" (or "Map link saved"), with Change and Remove. The Websites group shows the link, labelled "Map (Home)".
- [ ] Save, then open the contact: the address has a pin and "Home · exact spot". Tapping it asks which map app to use and opens that exact spot, named after the address (Google Maps, Organic Maps, OsmAnd, CoMaps, Magic Earth). Long-press: Copy, "Open the exact spot", "Search the address in maps" (the old behaviour) and "Open the saved map link". The link isn't listed again under About.
- [ ] An address with only a short link: tapping opens that link. An address without a link: tapping searches the address as before.
- [ ] Two addresses (Home, Work), a link on each: each opens its own spot. Remove the Work address in the editor: its link goes too.
- [ ] Open the contact in Google Contacts (after sync) and export it as a .vcf from Parley: the link is there as a website labelled "Map (Home)" (`X-ABLabel:Map (Home)`), and importing the file into Parley brings back the pinned address.
- [ ] From Organic Maps or Google Maps, share a place and pick Parley's "Call or message a number": the sheet shows "A place" with its name or coordinates and "Add to a contact…", which opens "Save contact details" (create, or add to an existing contact) with the address and its map link filled in.

Call screen picture
- [ ] Contact page › Settings › "Default call screen": tapping it opens the photo picker; after choosing, "Call screen picture set" appears, the row shows the picture and "Custom call screen picture", and a call from that contact shows it. The ✕ removes it ("Call screen picture removed").
- [ ] Choose a second, different picture: the row and the call screen show the new one, not the old.
- [ ] In the editor, the "Call screen" section at the bottom still works; with "Don't keep activities" on, choose a picture there (and a contact photo), come back and Save: both are kept.
- [ ] Rename a phone-only (device) contact and choose a call screen picture in the same edit: after saving, the page shows "Custom call screen picture" and calls show it.
- [ ] Choose a picture from a cloud album that fails to download: a message says Parley couldn't open it, instead of nothing happening.

Contact list buttons
- [ ] Settings › Contacts › Contact list › "Call and message buttons in the list": off (the default) shows names only; on shows Message and Call on each row; off again hides them. Search Settings for "call button", "hide buttons" and "clean list": each finds it. It is no longer under Layout & gestures.

Contact page photo
- [ ] A contact with a photo shows it large (168 dp) at the top; one without shows a large monogram (136 dp). Scrolling up shrinks it towards the top bar while the small avatar and name fade in there; scrolling back reverses it smoothly. Tapping the photo opens the photo viewer.
- [ ] Landscape on a phone: the photo is smaller (104 dp) and the name and action tiles still fit. On a tablet it is larger. With the largest font size, nothing overlaps.

### 22.3 Call screen redesign

Design and spec: [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md). Check each step in light, dark and black (AMOLED) themes; with dynamic colour on and off.

1. **Ongoing call.** Call a contact. The photo, name, "Mobile · number", the SIM and (on a verified number) "Verified number" tags, then the running time in a pill; the caller card (pinned note, last call) below. At the bottom, two rows of three buttons: Mute, Keypad, Speaker / Hold, Add call, More; then the red End call pill, centred.
2. **Toggles.** Tap Mute: the button fills with the primary colour, its corners square off with a small spring, the icon becomes a crossed microphone and the label "Muted". Same for Speaker and Hold (label "Resume", play icon). TalkBack reads "Mute, switch, on/off".
3. **Disabled buttons.** On a call that can't be held (some VoLTE/VoIP calls), Hold stays in its place, greyed out; nothing shifts.
4. **Audio route.** Connect a Bluetooth headset: the third button names the device and opens "Audio output" with each route; without a headset it's a Speaker toggle.
5. **Keypad.** Tap Keypad: the grid fades and springs into the keypad, the caller shrinks to name and time, and a "Hide keypad" button appears beside End call. Tones play while a key is held. Hide it again.
6. **More.** Tap More: the sheet lists Add a note, Open contact (not for a hidden number) and the Call time card (+2 / +5 min, End in 1 min, Don't end, as before). With two calls that can be merged and swapped, Merge is in the grid and Swap, Add call (and Manage conference) are at the top of More.
7. **Two calls.** Add a second call: the first shows as the on-hold strip with Swap, Merge and End. Merge: the fifth button becomes Manage (or Merge/Swap first when available); Manage lists each person with Private and End.
8. **Incoming, slide.** With "Answer incoming calls by: Swipe", ring the phone: "Incoming call" pill, the photo with a slow halo, Reply · Silence · ⋮ pills, then the slider (right answers, left declines). Silence stops the ringer and the pill disappears; ⋮ › Block & decline declines with Undo on the next screen.
9. **Incoming, tap.** With "Tap", Decline (red) and Answer (green, with a halo) circles; on a dual-SIM phone the SIM tag sits under Answer. A spam verdict shows as a red tag and tints the top of the screen red.
10. **Call waiting.** During a call, receive a second: the current call dims at the top, the waiting call rises as a sheet with Hold & answer / End & answer / Decline / Reply round buttons and a Silence pill.
11. **Background.** Set a call-screen picture on a contact (contact editor), then have them call: the picture fills the screen under a scrim; every line of text stays readable over a white and over a black picture. Without a picture the top is tinted with the contact's avatar colour; an unknown number uses the theme colour.
12. **Ended.** Hang up: the status pill says "Call ended" (or the reason); an unknown number gets the post-call card, a contact with "Anything to remember?" on gets the memory card; a failed call shows the reason and Retry.
13. **Layouts.** Rotate a phone during a call (caller left, controls right), and try a tablet or unfolded foldable. Set the largest font and display size: labels wrap, the caller scrolls, End call stays reachable. Switch to Arabic or Urdu: the grid mirrors, the keypad and the slider stay left to right.
14. **Reduced motion.** Settings › Accessibility › Remove animations: halos and the slider pulse stand still; buttons still change state.
15. **Picture-in-picture.** Press Home during a call: the small window carries the same caller tint, name, time and Muted tag; Mute and Hang up still work.
16. **Ring latency.** Cold-start the phone app and ring it (or run the IncomingCallBenchmark): the incoming screen appears as fast as before; a large call-screen picture fades in after the screen is up.
17. **Emergency.** (Test number where available.) An emergency call shows the "Emergency call" tag; nothing about limits, screening or prompts changed.

## 23. Corrections (4.2)

### 23.1 Call screen: slide control, quiet actions, background setting, in-call polish

Design: [CALL_SCREEN_DESIGN.md › 4.2 revisions](CALL_SCREEN_DESIGN.md#42-revisions). Check in light, dark and black (AMOLED) themes.

1. **Slide control at rest.** Settings › Calls › Answer incoming calls by: Swipe. Ring the phone. The track has a red Decline circle at the left end, a green Answer circle at the right and the round knob in the middle; no text is inside the track. "Slide right to answer, left to decline" sits under the track and is fully readable. Faint chevrons shimmer outwards and the knob nudges towards Answer every few seconds.
2. **Dragging.** Drag the knob right slowly: the hint fades out, the knob turns green and the Answer circle fills; at about 60% the phone ticks and the hint reads "Release to answer" in green. Drag back a little: a lighter tick, the hint fades again. Let go before 60%: the knob springs back to the middle. Let go past it: the call is answered. Repeat to the left: the handset tips over, "Release to decline" in red, declined.
3. **Pocket safety.** Touch the track away from the knob and drag: nothing moves. Brush the knob quickly a short way: it springs back. A quick flick from about a third of the way does answer.
4. **Dual SIM.** On a dual-SIM phone "Incoming on <SIM> · …1234" shows as a small label above the track (and above the tap buttons), never inside it.
5. **Quiet actions.** Reply · Silence · More are round buttons with the label under them, lined up over the track's two ends and its middle, and tinted with the background (not dark pills). Tap Silence: the ringer stops and the middle button reads "Silenced". For a hidden number the Reply column stays empty and nothing shifts. More › Block & decline works as before.
6. **Tap variant.** Switch to Tap: Decline and Answer circles sit in the same columns as the track's ends; Answer has its halo.
7. **TalkBack.** The track reads "Incoming call. Slide right to answer, left to decline." (with the SIM on dual-SIM phones) and offers Answer and Decline actions; the hint under it is not read again. The quiet buttons read "Reply with a message", "Stop ringing", "More options for this call".
8. **Reduced motion.** Remove animations: no shimmer, no nudge, no halos; dragging and the ticks still work.
9. **Arabic / Urdu.** The slide control and the tap buttons stay Decline-left, Answer-right; the quiet row mirrors.
10. **Background setting.** Settings › Calls › Call screen background: Plain. Ring the phone from a contact without a picture: the screen is the plain theme background (also in the picture-in-picture window). A contact with a call-screen picture still shows it. A likely-spam call still gets the red wash. Search Settings for "tint": the setting is found. Back to Caller's colour: the tint returns.
11. **HD voice and Wi-Fi calling.** On a VoLTE call that the network marks HD, an "HD voice" tag shows next to the SIM once connected; on Wi-Fi calling, a "Wi-Fi calling" tag.
12. **Local time.** Call (or be called by) a number in another time zone (e.g. a +1 number from Europe): a "9:40 pm there" tag shows a moment after the screen is up. A number in your own zone shows none.
13. **Hold.** Put a call on hold: the status pill turns to the tertiary colour with a pause icon, "On hold", and the photo dims; resume restores both. Press Home: the PiP window shows the pause icon.
14. **Copy number.** More › Copy number (the number under it): the number is on the clipboard; on Android 12 and older a "Number copied" message shows. Not offered for a hidden number.
15. **Audio output.** With a Bluetooth headset connected, the audio button opens "Audio output" listing the headset by name, Phone and Speaker; the current one is ticked.
16. **Keypad.** Open the keypad: keys are larger; type more than 20 digits: the line keeps the latest digits with "…" at the start.
17. **Ring latency.** Cold-start and ring (or run IncomingCallBenchmark): the incoming screen appears as fast as before; the local-time tag, if any, arrives after the screen is up.

### 23.2 Recents icon filter chips; contact page scrolled bar without repeats

**Recents filter row (Recents style: search Settings for "Recents style")**

1. **Rich style.** With Recents style set to Rich, the chips above the calls are icons: All (history clock), Missed, Incoming, Outgoing and Blocked with the same shape-coded badges as the rows (solid red circle, tonal green circle, outlined blue circle, outlined square), Voicemail (tonal square, as default phone app), one round letter per saved filter, and the Filter (tune) chip. No names on unselected chips.
2. **Selected chip.** The selected chip is filled (tonal) and, when the row has room, grows with a spring to show its name ("Missed"); picking another chip moves the name across. On a phone around 392 dp wide with Voicemail and a saved filter showing, the row stays icon-only rather than scrolling; on a wide phone, a tablet or in landscape the name shows. On a very narrow screen or with many saved filters the row still scrolls sideways.
3. **Counts.** A missed call not returned yet puts a count badge on the Missed icon; unheard voicemail puts one on the Voicemail icon.
4. **Names.** Long-press any chip: a tooltip names it ("Incoming", the saved filter's name, "Filter", or the active filter's description). With TalkBack, each chip reads its name, the selected state, and for Missed and Voicemail the count ("Missed, 2 to call back").
5. **Saved filters.** Save a filter named "Work": its chip shows "W"; tap it to apply (it fills and shows "Work" when it fits), tap again to clear. A filter set but not saved fills the Filter chip. A name with no letters shows a bookmark icon.
6. **Simple style.** Switch Recents style to Simple: the row is the text chips as before, with the Voicemail count.
7. **Themes and RTL.** Light, dark and AMOLED: the badges keep their call colours; outlines stay visible. In Arabic or Urdu the row runs right to left.

**Contact page, scrolled**

8. **Top of the page.** Open a contact: the top bar has Back, the star, Edit and ⋮; the header shows the photo, name and the Call / Message / Video / Email tiles.
9. **Scrolled.** Scroll down: the name and small photo dock into the top bar (with star, Edit and ⋮ still there), and the pinned strip under it shows only Call, Message, Video (when there's a video app) and Email (when there's an address). There is no second ⋮ / "More" button in the strip. Long-press a strip button: a tooltip names it. The jump chips still appear on long pages.
10. **Scroll back up.** The strip slides away with the same spring and the big tiles return; the top bar's ⋮ menu opens the same items in both states.
11. **Layouts.** Landscape, large font, TalkBack (each strip button reads "Call Sam", "Message Sam"…), and Arabic: the strip mirrors and stays within the width.

### 23.3 Contact editor redesign

Design: [EDITOR_DESIGN.md](EDITOR_DESIGN.md) ("4.2 redesign"). Check each step in light, dark and black (AMOLED) themes.

1. **New contact.** Contacts › + : the photo with "Add photo", the "Save to" pill, then the name block with no card around it. The keyboard opens on First name. Each group shows its icon once in the start gutter (person, work, phone, email); the fields are soft tonal blocks with thin 2 dp joins, no outlines. Save in the top bar is greyed until you type something, then fills in.
2. **Focus.** Tap a field: it lifts a shade and gets a 2 dp ring in the primary colour; the label moves up inside the field. IME "Next" walks First name › Last name › Company › Job title › Phone › Email.
3. **Phone formatting.** Type 2025550134 with a US SIM (or 07700900123 with a UK one): it shows as "(202) 555-0134" / "07700 900123" while typing; the flag appears after six digits. Type your own spaces or dashes: the number shows exactly as typed. Save, reopen: the saved number is what was typed.
4. **Type pills.** "Mobile ▾" sits at the end of each phone and email. Tap it: the menu ticks the current type and offers "Custom…". With the largest font, or on a very narrow screen, the pill moves under its field so the number keeps its room.
5. **Rows.** "+ Add phone" adds a row that animates in and takes the focus; the ⊖ at the end removes one. An account-locked row shows a lock instead of ⊖, and its type as text under it.
6. **Name details.** The chevron at the end of First name opens prefix, middle name, suffix, phonetic names and nickname around it as one block; it can't close while any of them holds text.
7. **Add more info.** The button opens the sheet with only kinds not on screen. Dates: tap the date field and the year-optional picker opens; the type pill offers Birthday, Anniversary, Other, Death, Custom…. Address: one block per address (street with type pill, postcode + city, region + country) and "Add from map link" under it. Relations: the person-search icon picks a contact, the pill opens the searchable relation types. Handles: service pill; "Other" adds a service-name line in the same block.
8. **Everything else.** Labels (chips), Notes, the call-screen picture (existing contacts), private contacts' "When they call" fields, the duplicate warning ("already exists") while typing a known name or number, the discard guard (Back or ✕ after a change, predictive back scales the page) and the changed-elsewhere sheet all work as before.
9. **Layouts.** Landscape or a tablet: photo, account, name and work on the left, the other groups on the right. Arabic or Urdu: the gutter icons and ⊖ mirror; phone numbers, emails and websites stay left to right. TalkBack: each group's icon is read as a heading ("Phone"), pills as "Type: Mobile, double-tap to change type", ⊖ as "Remove number".

## 24. Corrections (4.3)

### 24.1 Clearing History & undo; Recents icon chips across the whole row

**History & undo › ⋮ › Clear history & undo…**

1. **Storage sheet.** Delete a contact, delete a few calls from Recents, and let a daily snapshot exist (or open a contact's version history once). Open History & undo › ⋮ › **Clear history & undo…**: a sheet lists Contact changes (count · size), Deleted calls (count · size) and Daily snapshots (count · since date · size). A store with nothing in it reads "Nothing kept" and its Clear button is disabled.
2. **Contact changes.** Tap Clear: the dialog says how many saved copies go and that those changes can't be undone or the contacts restored. With the app lock on, the lock prompt follows; cancelling it clears nothing. Confirm: a snackbar says "Cleared N contact changes", the sheet's row reads "Nothing kept" and the Contacts tab shows its empty state. Your contacts themselves are unchanged.
3. **Deleted calls.** Clear: the dialog says the calls deleted in Parley will be gone for good and that the call log stays as it is. After confirming, the Calls tab is empty and Recents is unchanged (no call comes back, none else disappears).
4. **Snapshots.** Clear: the dialog offers "Older than 30 days", "All but the latest" and "All snapshots", each with how many it clears (an option that clears nothing is disabled). "All snapshots" empties the Snapshots tab to "No snapshots yet" (not an endless spinner); the next daily run takes a new one. "All but the latest" keeps "What changed" working from the newest snapshot.
5. **Per item.** On the Contacts and Calls tabs each row has a delete icon ("Remove from history" for TalkBack) next to Restore. It asks first, then removes just that row with "Removed from history".
6. **After "Delete all Parley data" and on a fresh install** the sheet shows every store as "Nothing kept"; the Snapshots tab shows "No snapshots yet" until the first daily snapshot.
7. **Layouts.** Landscape and large font: the sheet scrolls. Arabic or Urdu: the rows and dialogs mirror; sizes and dates read correctly.

**Recents icon chips (Recents style: Rich)**

8. **Whole row.** On a phone where all the icon chips fit, the first chip's pill starts at the same inset as the call rows below and the last ends at the same inset on the other side, with the rest evenly spaced between; no empty stretch on either side. In Arabic or Urdu the same, mirrored.
9. **Selected name.** Pick Missed: its name grows in and the other chips close up evenly (the gaps shrink, nothing jumps to one side). Pick All, then a saved filter: the same.
10. **Too many chips.** On a narrow screen, or with several saved filters, the row falls back to scrolling sideways with the chips packed as before.
11. **Tablet and landscape.** The chips spread over the full width of the Recents column.

### 24.2 Compact contact editor; Save to: Temporary

Design: [EDITOR_DESIGN.md](EDITOR_DESIGN.md) ("4.3 compact editor"). Check light, dark and black (AMOLED) themes.

1. **New contact, short page.** Contacts › + : a "Save to: …" chip at the top left, then an 80 dp photo beside First name / Last name, one Phone field, and a line of "Add" chips (Email, Work, Date, Address, Notes, …). No Work, Email, Notes or Labels groups, no "+ Add phone" rows, no "Add more info" button. Fields are 48 dp; groups sit 8 dp apart. The keyboard opens on First name.
2. **Height.** On a ~360 × 800 dp phone, type a name, a number and (after tapping the Email chip) an email: everything, including the chips, fits on one screen with the keyboard closed.
3. **Add chips.** Tap Email: an email row appears, scrolls into view and takes the focus. While the phone row is empty there's no Phone chip; once a number is typed a Phone chip leads the line and adds a second number. Work shows company + job title and focuses company. Date adds a birthday and opens the picker. Chips for kinds already shown and single (Work, Notes) disappear. The line scrolls sideways; at font size 130 % or more it wraps.
4. **Type selector.** "Mobile ▾" is quiet text inside the field's end; tap it: the menu ticks the current type and offers "Custom…". At font size 130 % or more, or on a very narrow screen, it moves under the field. TalkBack reads "Type: Mobile", action "Change type".
5. **Photo.** Tap the photo: the photo picker opens. With a photo, tap it: "Change photo" and a red "Remove photo". On a narrow screen or at 130 % font the photo sits above the name (72 dp). The chevron at the end of the name opens prefix, middle name, suffix, phonetic names and nickname; it can't close while any holds text.
6. **Save to: Temporary.** Open the Save-to chip: the accounts, "Private (only in Parley)" and "Temporary · Deletes itself after a time you choose". Pick Temporary: the chip reads "Save to: Temporary · private" and a second chip "After 7 days ▾" appears, with a line of small print. Its menu: 1 day, 7 days, 30 days, Custom… (a days field, 1–3650), "Save visible to other apps", "Also delete its call history". Save: a "Deletes itself in 7 days" message; the contact is in Tools › Temporary contacts with a lock (private) and the right time left, with every field typed (open it: email, address…). With "Save visible to other apps" on, it's a phone-only contact that other apps see, also listed there; its photo is kept.
7. **Temporary expiry.** Set the phone's clock past the time (or use "Delete now" in Temporary contacts): it goes as the keypad's temporary contacts do, with its call history when that was ticked.
8. **Existing contact.** Edit a saved contact: "Saved in …" and a "Make temporary ▾" chip. Pick 30 days: the chip reads "After 30 days" and Save turns on; save: the contact page and Temporary contacts show 30 days left, and no "Keep this contact?" prompt appears. Edit a temporary contact: the chip shows its time left ("3 days left"); "Keep permanently" then Save removes it from Temporary contacts. Private contacts (Private contacts › edit) have the same chip. Editing one copy of a linked contact ("Edit this copy") shows no chip. There is no "Make private" in the editor (use the contact page's move).
9. **Private and prompts unchanged.** "Private" still saves to the vault with the "When they call" chip/fields and encrypted photo note; editing a temporary contact without touching its chip still asks "Keep this contact?" after saving.
10. **Draft restore.** With "Don't keep activities" on, pick Temporary / 30 days, switch away and back: the choice and the draft are still there. The discard guard, changed-elsewhere sheet, duplicate warning, map links, relations picker and the call-screen picture (Call screen chip on existing contacts, shown directly when one is set) work as before.
11. **Layouts.** Landscape or a tablet: Save to, photo and name on the left, groups and chips on the right. Arabic: gutter icons, ⊖ and chips mirror; numbers stay left to right. Largest font: fields grow, nothing is clipped.

### 24.3 Contact page: calmer and more compact

Design: [CONTACT_PAGE_DESIGN.md](CONTACT_PAGE_DESIGN.md). Check in light, dark and black (AMOLED) themes.

1. **First screen.** Open a contact with a photo, two numbers, an email and a birthday. The photo is smaller than before, and the name, a one-line summary ("Last talked 3 days ago · Birthday in 6 days"), the Call / Message / Video / Email tiles and the start of "Contact info" with the numbers all fit on the first screen of an upright phone. A monogram is smaller still.
2. **At a glance.** A contact never called reads "No calls yet". A birthday more than 30 days away isn't in the line; one within 30 days is ("Birthday tomorrow", "… today"). Write a note with "[ ] call back about the flat" on a call: the line adds "1 open promise".
3. **Contact info.** Numbers, emails and addresses are in one group under "Contact info", each a two-line row: the value, then "Mobile · Default · WhatsApp, Signal" (type, default when there are several, remembered SIM, the apps that reach that number). The phone icon shows only on the first number, the email icon on the first email.
4. **Number actions.** Tap a number: it calls (through the pre-call peek when on). The chat icon messages it. The apps icon (only when an app reaches that number) opens "Message or call on…" for that number. Long-press: Copy, Set as default, Message or call on…, Edit before calling, Choose SIM (dual SIM).
5. **Apps without a saved number.** A contact whose Signal (or Telegram) account is on a number not saved in the contact: that app gets its own row with Message / Voice / Video buttons; long-press a button makes it the usual way. Typed-in handles (Matrix…) show as rows in the same group.
6. **Stay in touch.** For someone outside the Circle with no promises and few calls there is no Stay in touch group; "Add to your Circle" is the first row of "Settings for this contact". Add them: a Stay in touch group appears at the top with the rhythm and the "Due" chip; the next date isn't repeated there.
7. **About.** Dates, the "Add birthday" / "Add anniversary" chips, websites, relations, the contact's note and "Note for calls" are one group titled "About <first name>". The yearly-reminder button on a life event still works.
8. **Timeline.** The latest three entries show in one group with their dates (no month headings); "Show all (N)" opens the full timeline.
9. **Settings for this contact.** Folded by default at the bottom. Unfold it: Add to your Circle (outside the Circle), Send to voicemail, talk-time reminder, call time limit, ringtone, call-screen picture, "Saved in" chips (tap one: Edit this copy, Move to…, Unlink), who changed it last, "Deletes itself on…" for a temporary contact (tap: change it), and "Contact page sections", which opens Settings › Contacts › Contact page sections.
10. **Folding and order.** Fold "Contact info": the whole group folds and its header shows "2 numbers · 1 address…"; reopen the app and another contact: still folded. In Settings › Contact page sections move Email below Timeline: Email is a group of its own there and Contact info keeps the rest. Hide "Message or call on…": its rows go. Reset: back to the joined groups.
11. **Upgrading.** On a phone where 4.2 had a section folded (with the old order), the page opens in the new order with that fold kept. A custom order set in 4.2 is kept as it was.
12. **Scrolled.** Scroll: the name and photo dock into the top bar, the pinned strip shows Call / Message / Video / Email, and on a long page the jump chips name the groups ("Contact info", "About Sam", "Timeline"…); a chip unfolds and scrolls to its group.
13. **Layouts.** Landscape (small photo, tiles in view), a tablet, the largest font (rows grow, nothing clipped), TalkBack (each row reads its value and label as one item; group headers are headings with Folded / Open), and Arabic or Urdu (the rows mirror; numbers stay left to right).

### 24.4 One contact, with private and temporary as variants

Design: [CONTACT_MODEL.md](CONTACT_MODEL.md). Use a phone with a screen lock and the app lock off (so the vault asks for its own unlock), and check light, dark and black themes.

1. **One page.** Open a private contact from Contacts. It opens the same page as any contact: photo, name, the at-a-glance line, Call / Message / Email tiles, Contact info, About, Timeline, Call insights (after a few calls) and "Settings for this contact". The only difference in the header is the chip "Private · hidden from other apps". A temporary contact (private or not) also has "Temporary · deletes itself on …"; tap it: the "Delete automatically after" choice opens.
2. **Locked.** Lock the phone, unlock it, wait past the vault's unlock window and open a private contact: the name, photo and numbers show, with "Unlock to see all details" under the header and no sections. Tap it and authenticate: the full page appears in place. Calls from that contact while the phone is locked still show the name and the note for calls, as before.
3. **Everything works.** On a private contact: star it (it appears in Favourites with a small lock on the photo), add it to your Circle (it is in the Circle tab, with the lock), log a moment, write a note for calls, set a default number, choose a call-screen picture (it shows when they call), tap a relation, open the full timeline, and use Show QR code (it asks first: whoever scans it gets an ordinary contact). Copy to SIM, the home-screen shortcut, version history and "Share" as a file are not offered: they live in Android's address book (labels, ringtone and Send to voicemail: see 24.6).
4. **Editor.** Edit the private contact: every field is there, including dates, relations, a map link on the address, the photo, "When they call" and the call-screen picture. Save: the page shows the change.
5. **Make private.** On a phone contact with a label, a note for calls, a Circle rhythm, a logged moment and a call-screen picture: Settings for this contact › Make private. The dialog says what other apps won't see and what stays. Confirm: the same page opens with the Private chip, and the note, Circle, moment and picture are all there. Another contacts app (or WhatsApp after its next sync) no longer shows them.
6. **Make visible.** On that contact: Make visible to other apps. The dialog says what other apps will see. Confirm: the page opens as a phone contact with the label back, and the note, Circle, moment and picture still there. Calls made while it was private are in Recents and in the phone's call history.
7. **Temporary both ways.** On a phone contact and on a private contact: Settings for this contact › Delete automatically… › 1 week: the Temporary chip shows the date. "Keep permanently": the chip goes. Make a temporary contact private and back: it keeps its date.
8. **Lists.** Contacts shows private contacts in the one list, in name order, with a small lock on the photo (TalkBack: "Private contact"); there is no separate private section. The "Private" chip filters the list to them; with none, it offers "Add private contact". Search, keypad search and T9 find them with the same rows; a tap opens their page. Long-press selects them like anyone (see 24.6); swipe to delete works.
9. **Discreet mode.** Settings › Privacy › Hide private contacts: they leave Contacts, Favourites, the Circle and keypad results, and calls from them show only the number, as before.
10. **Old links.** A missed-call notification, "Chat, then decide", Recents or Temporary contacts that open a private contact all land on the same page.
11. **Backup.** With the vault unlocked, back up and restore on another phone (or after "Delete all Parley data"): the private contact comes back with its Circle rhythm, moments and call-screen picture. A backup made while the vault was locked holds none of them.

### 24.5 Two-way relations, whole contact photos, contact count

Relations:

1. **Mirror.** Edit Sam, add a relation, pick Ana with the contact picker and choose "Mother". Save: a snackbar "Also added to Ana Lee: Mother → Child" with Undo. Open Ana: About lists "Child: Sam …"; tap it: Sam's page opens (and on Sam's page, tapping "Mother: Ana" opens Ana).
2. **Types.** Spouse → Spouse, Partner → Partner, Sister/Brother → Sibling, Friend → Friend, Manager → Assistant (and back), Father/Parent → Child, Child/Son → Parent, Aunt → Relative. A custom label ("Fishing buddy") is mirrored as written. "Doctor" or "Met" add nothing on the other side.
3. **Undo.** Tap Undo on the snackbar: Ana's "Child" row goes. History & undo › Contacts lists Ana as edited.
4. **Retype and remove.** Change Sam's relation to "Friend": Ana's row becomes "Friend" ("Also changed on Ana Lee: Friend → Friend"). Remove it: Ana's row goes ("Also removed from Ana Lee").
5. **User rows win.** On Ana, change the added row to "Son" by hand; then remove Sam's relation: Ana keeps "Son". If Ana already calls Sam something ("Brother: Sam Lee") before you add the relation on Sam, no second row is added.
6. **Read-only.** Pick a contact whose only copy is in a read-only account (a messenger's): the snackbar says it's in a read-only account and the relation is saved only on Sam. Nothing is written to that contact.
7. **Setting.** Settings › Contacts › Organise › "Add relations to both contacts" (on by default; search "reciprocal" finds it). Turn it off: relations are saved only on the contact you edit.
8. **Private contacts.** Relations on private contacts are never mirrored to phone contacts (their names would leave Parley).

Photos:

9. **Whole photo.** Edit a contact, add a landscape 4:3 (or portrait) photo, save. The contact page shows it uncropped as a rounded rectangle (wider or taller than the old circle); a square photo is still a circle. Scroll: it shrinks and docks into the top bar as before. The Contacts list keeps round avatars.
10. **Full resolution.** Tap the photo: the viewer shows it whole; pinch or double-tap and zoom in on fine detail: after a moment it sharpens (only the part in view is decoded). A 50 MP photo opens without stutter or a crash.
11. **Orientation.** A phone photo stored rotated (EXIF orientation 6 or 8, most portraits) shows upright on the page and in the viewer, also when zoomed in.
12. **Formats.** HEIC works (kept as a high-quality JPEG). A photo over 20 MB works too.
13. **Other apps.** The photo menu in the editor says "Parley keeps the whole photo. Other apps see Android's smaller copy." Google Contacts shows the same picture, uncropped but at Android's size (720 px at most).
14. **Replaced elsewhere.** Change the photo in another contacts app: Parley's page shows the new photo (its own copy of the old one is dropped). Remove the photo in Parley: the page shows the monogram.
15. **Private contact.** Add a photo to a private contact: its page shows it whole and the viewer opens it; the file on disk is sealed. Make it visible again (and private again): the whole photo comes along each time. Delete the private contact: its original goes too.
16. **Backup.** Back up and restore on a fresh install: originals up to 4 MB in total come back; the others show Android's copy.

Count:

17. **Footer.** Scroll to the end of Contacts: "120 contacts" in small grey text. Pick one label: "12 contacts in Family"; one account: "30 contacts in Google · …"; Unlabelled: "5 unlabelled contacts"; two labels: "7 contacts match the filter". Search: "4 results". With private contacts in the list: "120 contacts · 3 private". The Private chip: "3 private contacts". One contact reads "1 contact". TalkBack reads the line.

### 24.6 Private contacts: labels, ringtone, voicemail, dates, call time, multi-select, Recently deleted

Design: [CONTACT_MODEL.md](CONTACT_MODEL.md) ("Labels of a private contact", "Ringtone and Send to voicemail"). Parley is the phone app; the phone has a screen lock and the app lock is off (so the vault asks for its own unlock).

1. **Labels in the editor.** Edit a private contact: a Labels chip row lists every label once. Tick "Family", save. Open Contacts › label chip "Family": the private contact is listed with its lock badge; with "Match all" and two labels, and with "Unlabelled", it is filtered like everyone. Settings › Labels shows the right counts. Another contacts app shows no change to "Family" (no new member).
2. **Label page.** Open "Family": the private member is in the list. Message all includes their number. Email all asks for the vault's unlock first (if locked), then includes their email. Long-press the member › Remove from Family: gone from the page and the filter.
3. **Add to label.** Select a device contact and a private contact (long-press both) › ⋮ › Add to label › Work: both are in Work; the address book shows only the device contact in it.
4. **Rename, merge, delete.** Rename "Work" to "Office": the private member follows. Merge "Office" into "Family": they're in Family. Delete "Family": they're in no label. Delete a label in another app: it disappears from the private contact; make it again with the same name: the private contact is back in it.
5. **Label ringtone and policies.** Give "Family" a ringtone and a SIM; put the private contact in it. Call from its number: the Family tone plays (Parley's ringer). Call it from Parley without a remembered SIM: the Family SIM is used. Add it to your Circle: "Family"'s rhythm is offered. A label block rule on "Family" declines it. Turn on "Allow through Do Not Disturb": the switch's summary says the private member can't ring through, and it isn't starred.
6. **Own ringtone.** Private contact › Settings for this contact › Ringtone › pick one. Call from it (screen locked too): that tone plays with the usual vibration; "Why did my phone ring?" names the contact's tone. In silent or vibrate mode, or Do Not Disturb, Parley plays nothing, as for every tone.
7. **Send to voicemail.** Turn it on: a call from it doesn't ring and goes to voicemail; no missed-call notification, and nothing appears in Blocking & screening's log. Turn it off: it rings again.
8. **Dates.** A private contact without a birthday: "Add birthday" on its page saves the date (vault unlocked); another contacts app sees nothing.
9. **Call time.** Settings for this contact › Call time limit › 10 minutes a day. Talk 10 minutes: the next call rings silently (with "silence over allowance" on) and the in-call screen names the limit. Settings › Call time lists the limit with the contact's name (hidden in discreet mode: "this contact"). A backup's Call time section has no entry for it; the private section does.
10. **Multi-select.** Select two device contacts and one private contact. Star: all three are Favourites. Delete automatically… › 1 week: all three are in Temporary contacts. Share, Export to .vcf, Copy as text and Merge act on the two device contacts and say "1 private contact left out"; with only private contacts selected, Copy as text, Export and Merge aren't offered. Move to private moves only the device ones; Make visible to other apps asks for the unlock, then makes the private one visible with its star and labels. Delete removes all three.
11. **Make visible keeps it all.** A private contact with a label, a ringtone and Send to voicemail: Make visible: the address book has the label, the ringtone and Send to voicemail. Make private again: all three are kept.
12. **Recently deleted.** Delete a private contact: the dialog says a sealed copy stays in History & undo. History & undo › Contacts: "Deleted private contacts · 1 kept sealed for 30 days. Unlock to see it". Tap: after the unlock, it is listed with Restore and remove. Restore: it's back with its details, photo, labels, star, private calls, Circle and moments. Remove: gone for good. "Clear history & undo" › Contact changes clears these too. A temporary private contact that expires (or "Delete now") keeps no copy.
13. **Security.** With the vault locked, nothing of the above shows a private contact's name outside Parley: the lock screen, the blocked-calls log and other apps' views of the address book are unchanged. Discreet mode hides private members from label pages and counts.
