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
