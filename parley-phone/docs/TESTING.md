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

**Default-dialer rescue (P4)**
- [ ] On a phone where Parley isn't the phone app: Settings (banner) or Settings › Calls › Set default. Press Cancel on Android's dialog: no guide.
- [ ] Decline twice with "Don't ask again" (or use a ROM that refuses sideloaded apps): the next request comes back at once and the guide opens with the steps for this Android version (10/11: Apps & notifications; 12+: Apps; 13+: also "Allow restricted settings"). App info and Default apps open the system screens.
- [ ] Settings › Calls › "Can't make Parley the default phone app?" (only while it isn't) opens the same guide. Settings search "restricted" finds it.

**Clear call history (P5)**
- [ ] Recents ⋮ › Clear call history…: choose "Calls from numbers not in your contacts" (counts shown), Next, Export first? › CSV file: the share sheet offers the CSV; then confirm Delete: only those calls go; the snackbar's Undo brings them back. Contacts' calls and private-contact calls stay.
- [ ] With a Recents filter or search active, "What Recents shows now" is offered first and clears exactly those rows. "Encrypted backup" opens Backup & restore. Settings › Recents & history › Clear call history works the same (without "What Recents shows now").

**Call failure banner (P6)**
- [ ] Airplane mode on, call a number: the call screen keeps "Call didn't go through · Airplane mode is on" with Retry and Dismiss; it doesn't close by itself. The caller's name (a contact) stays on the screen.
- [ ] Dual SIM with "Ask every time": let the SIM picker time out (or have the network refuse): "No SIM was chosen". Cancel on the picker yourself: no banner.
- [ ] A number that fails on the network: the network's own reason, or "The network couldn't connect the call". Retry places the call again on the same SIM; Dismiss closes the screen. A busy line shows "Busy" with Retry. Hanging up yourself, or the other person declining, never shows the banner.
- [ ] During a call, Add call to a number that fails: the banner shows above the call that goes on.

**Haptics (P7)**
- [ ] With Vibrate on call events on: answering buzzes short-then-longer, declining (button, slide, notification, Block & decline) one firm buzz; no extra connect buzz right after answering.
- [ ] An outgoing call buzzes once when it's answered; Settings › Calls › Vibrate when a call connects off: no buzz on connect, answer/decline still buzz. With Vibrate on call events off, nothing buzzes and the connect switch is greyed out. Silent mode: nothing buzzes.

**Call list layout (P8)**
- [ ] Recents ⋮ › Layout: Grouped / Every call / By day. Grouped: calls in a row from one number share a row (as before). Every call: one row each. By day: one row per number per day even when other calls came in between (the count shows). Day headers stay. The choice is also in Settings › Recents & history › Call list layout and survives a restart.

**Regression checks (P9)** — unit tests in `core/common/.../calls/PhoneV32Test.kt`; on the device:
- [ ] Keypad: `*#06#` shows the IMEI; `*100#` sends a USSD request; `**21*+4915112345678#` (paste it) keeps the `+` and `#` and goes to the network as a forwarding code; `#31#0612345678` calls with the number hidden.
- [ ] Type `555` while a contact "+1 555 0100" is the top match: Call dials 555. Typing a name on a hardware keyboard calls the top match.
- [ ] During a call, a second call rings with the call-waiting sheet (ringtone/tone, Answer, Hold & answer, End & answer).

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
- [ ] Private (vault) contacts never get a file. "Keep it up to date" on: the hourly folder-sync run exports again (even with contact sync off). "Stop exporting to this folder" leaves the files where they are.

### 15.7 Review fixes: simple mode, Markdown, Do Not Disturb, handshake, import

- [ ] Simple-mode import (X4): make a setup whose "number" is `**21*+441234567#`, `*100#` or `0123,456` (edit an exported setup on a test build). Importing it leaves those people out and says "N entries weren't plain phone numbers and were left out". Every imported person shows its number on the review screen. A person whose name matches a contact but whose number doesn't shows "Not in your contacts" (no photo, no tick); after "Use this setup" the tile shows initials and calls the imported number. A person whose number is in contacts shows "Found: …" and gets that contact's photo.
- [ ] Markdown (C5): export, then append a line to `Ana.md` in the note app and change Ana's pinned note in Parley. Next export: `Ana.md` keeps your line, Parley writes `Ana (2).md`, and the export row says "1 file you changed was left as it is". Turn on "Only people in your circle": an edited non-Circle file stays; untouched ones are removed. "Stop exporting", then pick the same folder again: no "(2)" copies of untouched files appear (each file carries a `parley_export:` fingerprint).
- [ ] Do Not Disturb (X3): turn it on for "Work" and "Family"; someone in both stays starred when "Work" is turned off and is unstarred when "Family" is too. Remove someone from "Work", then turn it off: they are unstarred too. Delete a label with it on (or merge it into a label without it): its members are unstarred. A contact you starred yourself is never unstarred. Restore a backup: turning it off afterwards still unstars the right people.
- [ ] Handshake (X5): receive a card, tap "Save to phone", and before saving open "Add to contacts" from another app; save that one: no "Met at…" entry on it. Save the first editor: the entry lands on the received contact.
- [ ] Opening a 2,000-card .vcf from a file manager: the account rows show a progress bar and can't be tapped until the cards are counted; then "Back up first?" appears.
