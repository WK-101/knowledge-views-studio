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
- [ ] With the tab hidden, Favourites starts with "Your circle" (only once someone is in it; an empty Circle adds nothing to Favourites, not even suggestions). Tap the header: it folds away and stays folded after a restart.
- [ ] Empty Circle with call history: "Suggested for your Circle" lists up to 10 most-called contacts with "N calls this year · Every N days"; Add puts them in the Circle (Undo on the snackbar removes them). Without call history: "Your circle is empty" and how to add someone.
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
- [ ] Contacts search's **Who's in…** chip and Tools › Stay in touch › Who's in… (under "n more") open the screen; it is in no ⋮ menu (see 31.2 step 4). No location permission is asked for, ever (Settings › Apps › Parley › Permissions shows none new).
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
- [ ] "Show Frequent": a "Frequent" row of avatars under the favourites.
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
- [ ] Every tab's ⋮ has at most 7 items and ends with Tools and Settings. Recents: Export…, Call list layout, Clear call history, What the icons mean (+ Speed dial with the keypad docked). Contacts: Select all, Add several numbers…, Find & merge duplicates, (Reorder favourites). Keypad: only Tools and Settings. Circle: Circle settings.
- [ ] ⋮ › Tools and Settings › Tools (top of the list) open the same hub, grouped by job: Birthdays & dates, Temporary contacts, Contact health check, Scan QR code, Import & export contacts, Blocking & screening ("Choose who can ring"), Expecting a call (switch), Messaged numbers, History & undo, Backup & restore, Privacy dashboard, and Lock now with the app lock on, all without opening "n more". Each opens its screen (see 31.1).

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
12. **Formats.** HEIC works (kept as it is, or as asked when it records a location; see 34.4). A photo over 40 MB asks whether to keep the whole file.
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
12. **Recently deleted.** Delete a private contact: the dialog says a sealed copy stays in History & undo. History & undo › Contacts: "Deleted private contacts · 1 kept sealed for 30 days. Unlock to see it". Tap: after the unlock, it is listed with Restore and remove. Restore: it's back with its details, photo, labels, star, private calls, Circle and moments. Remove: gone for good. "Clear history & undo" › Deleted private contacts clears these (Contact changes doesn't). A temporary private contact that expires (or "Delete now") keeps no copy.
13. **Security.** With the vault locked, nothing of the above shows a private contact's name outside Parley: the lock screen, the blocked-calls log and other apps' views of the address book are unchanged. Discreet mode hides private members from label pages and counts.

### 24.7 Private contacts after the review

Design: [CONTACT_MODEL.md](CONTACT_MODEL.md) ("Conversions", "Ringtone and Send to voicemail"). Parley is the phone app; the phone has a screen lock.

1. **Made private before this version.** On a build before 4.3, move a starred device contact with the labels "Family" and "Doctors" and its own ringtone into the private contacts. Update, unlock the vault once: the private contact is in Favourites, in both labels, and its page shows the ringtone. Make visible: the address book has the star, both labels and the ringtone.
2. **Temporary, made visible, never takes another copy.** Keep a Google contact "Ada Lovelace" with the same number as a temporary private "Ada Lovelace" (Delete automatically › 1 day). Make the private one visible: Android may join the two. When the day passes, only the copy Parley restored goes; the Google contact stays (a notice says the rest was kept).
3. **Private calls go back first.** A private contact with calls in its private history: Make visible: the calls are in Recents and the phone's call log. Revoke Parley's call-log permission (or make it not the phone app) and try again with another private contact that has calls: "Their private calls couldn't go back to the call history…", and the contact is still private with its calls.
4. **Nothing forgotten on a slow key.** Make visible on a phone with a large address book: the Circle rhythm, logged moments, the note for calls, the call-screen picture and the original photo are on the visible contact (at once or after the next contacts change).
5. **Clear history & undo.** With one contact change and two deleted private contacts: ⋮ › Clear history & undo shows "Deleted private contacts · 2 contacts, sealed" as its own row. Clear Contact changes: the two private copies stay. Clear Deleted private contacts: it asks with the right count. In discreet mode the row isn't shown.
6. **Delete waits for its copy.** When the copy can't be kept (simulate with a full disk), deleting a private contact asks "Delete without a copy?" and nothing is deleted until you confirm. From multi-select, the snackbar says how many weren't deleted and offers "Delete without a copy".
7. **Relations written by Parley go with the name.** With "Add relations to both contacts" on, save Sam with "Mother: Ana" (picked). Ana shows "Child: Sam". Make Sam private: Ana no longer shows Sam (a row you edited on Ana stays). The same when Sam (a device contact) is deleted.
8. **Keep the call history choice.** Make a private contact temporary from the editor with "Also delete call history" unchecked. Change its time on the page, in the editor and with multi-select › Delete automatically: at expiry its calls stay.
9. **Send to voicemail isn't a block.** A private contact with Send to voicemail calls: the phone doesn't ring, the call goes to voicemail, and the system call log shows it as declined, not blocked. With Parley holding only the call-screening role, it is still sent to voicemail (Android then logs it as blocked).
10. **Details that can't be opened.** For a private contact whose details key was lost ("Some details can't be opened any more"), editing the note for calls, a default number or a date says to choose "Keep what's left" first; Delete automatically and multi-select still change the date without replacing the details.
11. **Editor and list together.** Open a private contact's editor, then (split screen) star it from the Contacts list and add it to a label; save the editor with another change: the star and the label stay.
12. **Original photo.** Open a private contact's photo full screen and pan and zoom for a while: no stutter; closing the viewer frees it. Delete the contact and restore it from History & undo: the photo as picked is back, and a relation on another contact that opened it opens it again.
13. **Keypad search.** Search a private contact's name from the keypad's search: its row shows its photo, with no delay while typing.

## 25. Follow-through (4.4)

### 25.1 Calls follow-up: Remind me and the To call list

Parley is the phone app; the phone has a screen lock. Use two phones (or a friend) to call in.

1. **Decline & remind.** Let a call ring › ⋮ › Decline & remind: three times open under it (In 1 hour, This evening · 18:00 before 17:00 only, Tomorrow morning · 09:00). Pick one: the call is declined and "Declined. Parley will remind you at …" shows. It works from the lock screen without unlocking. Hidden numbers and emergency call-backs don't offer it; simple mode keeps its two big buttons.
2. **Decline & message or call on….** ⋮ › Decline & message or call on…: the ringing stops at once; on the lock screen the phone asks to unlock, then the call is declined and the Message or call on… sheet opens for the number.
3. **Missed-call notification.** Miss a call from a contact: Call back, Message or call on… and Remind me. From an unknown number: Call back, Remind me and Block. From a number the dial guard flags (one-ring scam, premium): no Remind me. Remind me on the lock screen asks to unlock, then a small sheet offers the fixed times; picking one sets it, says so and clears that caller's notification (the missed calls count as seen once none is left).
4. **Post-call card.** After a call with an unknown number, Remind me on the card offers the same times; picking one sets it and closes the call-ended screen.
5. **The strip.** Recents shows "1 to call" above the calls, with the name under it; the first time, a tip explains it. With nothing owed the strip isn't there. Fold it with the arrow: one quiet line, kept after restarting. No badge anywhere (app icon, tab).
6. **The list.** Tap the strip: "To call" lists what is due now (unreturned missed calls from the last week and reminders whose time came), then "Later" by time. Each row says why ("Missed call · 14:05", "Reminder · 18:00", "Follow-up · Mon 09:00") and a contact's first open promise ("☐ send the photos"). Tap a row: the contact (a private one opens its page), or the number's history. Call calls through the usual checks.
7. **Row menu.** ⋮ › Remind me later › In 1 hour: the row moves to Later. Done and Remove take it off with Undo; a missed call marked done doesn't come back, but a newer missed call from them does.
8. **Called back settles it.** Call a person on the list (answered or not), or answer their call: within a few seconds they leave the list, and their reminder doesn't show.
9. **After 6 pm their time.** Miss a call from a number in another time zone (for example +1 212 … while in Europe): the row shows their local time ("14:05 for them") and ⋮ offers "After 6 pm their time". Tick it: the row moves to Later at 18:00 their time; past 21:00 their time it waits for their next evening. Numbers in your own time zone don't offer it.
10. **One quiet reminder.** Set two reminders for the same minute: one notification "2 calls to make" with both names, a single sound, no badge, no repeat. One reminder: "Call Sam" with Call and Not now; Not now brings it back once, an hour later. The notification can come a few minutes late (no exact-alarm permission).
11. **Lock screen and private contacts.** On the lock screen the reminder says only "Reminder". Set a reminder for a private contact: after unlocking the notification and the list show its name (🔒 in the list); in discreet mode only the number shows, in both.
12. **Follow-ups.** With "Anything to remember?" on, after a call with a contact pick "In a week": the contact is under Later as a follow-up for that day's 09:00, and the reminder comes with any other calls due then (no separate notification).
13. **Survives.** Reboot: the reminders still come. Back up and restore on another phone: the list comes back (reminders for private contacts stay out of the backup). Delete all Parley data: the list is empty and no reminder comes.

### 25.2 Call facts and trust on the call screen

Design: [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md) ("4.4: call facts and trust"). Parley is the phone app; the phone has a screen lock.

1. **Call dropped.** In a call over Wi-Fi calling, turn the router off (or walk out of range with no mobile signal). The call-ended screen says "Call dropped" with the reason ("Wi-Fi connection lost", or "Lost signal · Wi-Fi calling") and a big green **Call again**. It stays about 10 s; Dismiss closes it at once; Call again calls the same number on the same SIM. A call you or the other person hang up, a call ended by a time limit and an emergency call never show it. A hidden number shows the reason without Call again.
2. **Quality facts.** After the drop, the number's history (Recents › the number) has "Call details" with "Call dropped", the date, the talk time, "Wi-Fi calling", "HD voice" and the SIM on dual-SIM phones. Plain calls aren't listed. With "Private call history" on, calls with a private contact leave nothing here. Delete the calls from the history: their details go too. Delete all Parley data clears them.
3. **HD and Wi-Fi tags.** Connected over VoLTE/VoWiFi, the caller header shows "HD voice" and "Wi-Fi calling" (only when the network reports them), unchanged since 4.2.
4. **Audio output, press and hold.** In a call with only the earpiece and speaker: tap Speaker toggles it as before; press and hold opens the audio output list (TalkBack offers "Choose audio output"). The first connected call shows a one-time tip about it; "Got it" or using the gesture hides it for good.
5. **Call subject.** From a carrier or second phone that sends a call subject (RCS Call Composer "Call with message", or an `EXTRA_CALL_SUBJECT`), call Parley: the incoming screen shows the subject in quotes under the name, as plain text (links aren't clickable; a very long subject is cut with "…"; right-to-left control characters don't reorder it). An urgent priority shows an "Urgent" tag. The number's history shows the subject afterwards.
6. **Rang through.** Turn on Block non-contacts. (a) Call from an unknown number twice within 3 minutes (at least 20 s apart): the second call rings and says "Rang through: called twice in 3 min" under the status pill. (b) Turn on Expecting a call: an unknown number rings with "Rang through: expecting a call". (c) Allow a number for 24 h: "Rang through: allowed until …". (d) Off hours with only favourites, an allow rule for the label "Family", a Family member calls at night: "Rang through: in Family (allowed during off hours)". With nothing that would have blocked the call, no line shows. The quiet "Allowed by …" tag isn't shown twice.
7. **Check it's really them (in a call).** In a call with a contact, More › Check it's really them: on the lock screen it asks to unlock first. The sheet lists the contact's saved numbers (the one that called first) and "Pick a saved organisation". Tap a number: the call ends and the saved number is dialled on the same SIM. For an unknown caller (a "bank"), Pick a saved organisation lists contacts with a company (by name, then their numbers); tapping one ends the call and dials it. With Parley's app lock locked, only the calling number is listed and the organisations say to open Parley. A private contact caller lists its private numbers, except in discreet mode. Not offered for an emergency call.
8. **Check it's really them (after the call).** After a call from an unknown number, the post-call card has "Call a saved number": after unlocking, the same sheet opens and dials the chosen number.
9. **Hold mode.** In a call, More › I'm on hold: the speaker turns on, the screen dims, and the grid is replaced by "On hold", a running timer, the honest line (Parley can't hear the call), Keypad and **They're back**. Go Home: the picture-in-picture window says "On hold · 12:34" with a "They're back" action. The phone buzzes at 15 and 30 minutes (not in silent mode). They're back (on the screen or in the window) restores the brightness and puts the audio back on the earpiece or headset it was on (unless you changed it meanwhile). Ending the call ends hold mode. Not offered for an emergency call.
10. **Nothing slower while it rings.** With a screening rule set, calls still ring at once; the subject and "rang through" appear with the ringing screen, never delaying it.
11. **Contact photo on the call screen.** Settings › Calls › Show contact photo on the call screen (search "photo" finds it) is on: a contact with a photo and a call-screen picture calls, both show. Turn it off: the incoming and ongoing screen show the initial on the caller's colour, no picture behind, and the picture-in-picture window shows the initial too. On that contact's page › Settings for this contact › Photo on the call screen: "Default (hidden)"; choose Show: the next call shows the photo and picture again. Turn the setting back on and choose Hide on another contact: only that one shows the initial. A private contact (with a photo) follows the same choice; make it visible or private again: the choice stays. The choice is kept on this phone (not in backups).

### 25.3 Small wins: auto-answer, haptic caller ID, Recents Unknown and Contacts, pronouns, emergency information

Parley is the phone app. Try light, dark and black themes, a large font, landscape and Arabic or Urdu (RTL) where a screen is named.

**Auto-answer** (Settings › Calls › Know who's calling › Answer automatically)

1. **Off by default.** A fresh install shows "Off". Calls from contacts ring as before.
2. **Headset.** Turn on "With a headset or Bluetooth", pick 5 seconds. With earbuds or a car kit connected, a contact calls: under the name, "Answering in 5 seconds" counts down with a Cancel button; at 0 the call is answered (the answer buzz). Without a headset nothing happens. TalkBack reads the countdown politely and Cancel as "Don't answer automatically".
3. **Cancel.** Tap Cancel: the countdown goes and the call rings on; it isn't armed again. Pressing volume down (silence), Ignore or Decline also stops it.
4. **Simple mode.** Turn on "In simple mode" and switch simple mode on: a contact's call counts down the same way.
5. **Chosen people and labels.** Turn on "For chosen people and labels". A contact's page › Settings for this contact now has "Answer automatically"; switch it on for Sam. Sam's calls count down; Ana's don't. On the label "Family" switch on "Answer automatically": its members count down too. A private contact (switched on from its page, while the vault is locked or not) counts down as well.
6. **Never.** An unknown number, a hidden number, a call a rule blocks or silences, a "likely spam" call, or a call that arrives while another call is active or on hold never counts down, whatever is on. When a second call arrives during a countdown, the countdown stops.
7. **Delay.** 3, 5, 10 and 15 seconds are offered; the summary reads e.g. "With a headset or Bluetooth · After 10 seconds".

**Haptic caller ID** (contact page › Settings for this contact › Vibration; a label's page › Vibration)

8. **Choose and feel.** Open Vibration: the phone's usual vibration, "A rhythm of their own", Heartbeat, Double tap, One long buzz and "“S” in Morse code" (the first letter of the name). The play button vibrates the pattern once; TalkBack reads "Feel Heartbeat". Pick one: the row shows its name.
9. **Rings with it.** Phone on vibrate: Sam calls and the phone vibrates in Sam's rhythm, a short pause, again. Normal ringer mode with "Vibrate for calls" on: Sam's own ringtone (or the phone's default) plays, with Sam's rhythm. Silent mode, or Do Not Disturb: nothing changes (silent stays silent). "Why did my phone ring?" names the contact's tone or the default.
10. **Labels.** Give "Family" the Heartbeat; Ana (in Family, no pattern of her own) vibrates with it; Sam's own pattern wins over Family's.
11. **Stable rhythm.** "A rhythm of their own" feels the same after renaming the contact, making it private and visible again, and after a backup restore; two different contacts feel different.
12. **Private contacts.** A private contact's pattern is chosen the same way, applies while the phone and the vault are locked, and stays through Make visible / Make private.

**Recents chips** (Recents)

13. **Unknown and Contacts.** New chips after Outgoing: Unknown (a question mark in the Rich style, text in Simple) and Contacts (a person). Unknown lists calls from numbers that aren't contacts or private contacts, and hidden numbers; Contacts the rest. With discreet mode on, private contacts' numbers count as unknown.
14. **Quiet header.** On Unknown, a line under the chips reads "3 unknown callers today" (each number once, each hidden call once) or "No unknown callers today"; it moves on at midnight.
15. **Remembered.** Pick Unknown, close Parley (swipe it away) and open it again: Recents opens on Unknown. Blocked and Voicemail aren't remembered (Recents opens on All). Settings › Recents & history › "Remember the Recents filter" off: Recents always opens on All. A missed-call notification still opens on Missed.

**Pronouns**

16. **Editor.** Open the name chevron: a Pronouns field after Nickname. Type "they/them", save: the contact page shows "they/them" first under the name, and an incoming call from them shows it under the name on the call screen (also for a private contact, and while the phone is locked).
17. **vCard.** Share the contact as a vCard: it has `PRONOUNS:they/them`. Import a card with `PRONOUNS:she/her` from another app: the field is filled. The row isn't listed under "Other fields". Google Contacts keeps the row on the phone (it may not sync it).

**Emergency information** (Contacts › My card)

18. **Emergency information.** My card › In an emergency › Emergency information opens Android's emergency information (medical details, emergency contacts). On a phone without it, a dialog says where it usually is, with Open Settings. The lock screen's Emergency button is unchanged.
19. **ICE label.** Tap "ICE label": the label "ICE" is made in the default account (or opened when it exists); add the people to call in an emergency there.

### 25.4 Quality gate: APK size, benchmarks, main-thread file access

Build: the release APK (`./gradlew :app:assembleRelease`) for 1 to 4; a debug build with logcat open for 6 and 7.

1. **Size.** `./gradlew :app:checkReleaseApkSize` passes and prints about 11.7 MiB (budget 12 MiB).
2. **Languages.** Set the phone to Portuguese (Brazil), then German, Arabic and Hindi: Parley follows each one. Settings › System › Languages › Parley (Android 13+) lists English, Arabic, German, Spanish, French, Hindi, Portuguese (Brazil) and Urdu, no more. Set the phone to Italian: Parley is in English throughout, including a date picker's buttons and a bottom sheet's drag handle label (TalkBack).
3. **Scan from a picture.** Import a contact from a screenshot of a QR code, then of an Aztec code (a boarding pass) and of a PDF417 code: each is read as before.
4. **Start-up and notifications on Android 10 and 11.** Cold start shows Parley's splash (the icon on the window colour), then Recents. An incoming and a missed call show their notifications as before.
5. **Benchmarks build.** `./gradlew :baselineprofile:assembleBenchmarkRelease` passes without a device (CI runs it). On a test device, `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` runs without any tapping: Parley opens on Recents (no onboarding), a contact's page opens and scrolls, the keypad types. Record the results in docs/PERFORMANCE_BENCHMARKS.md › Results.
6. **No main-thread file access when saving photos.** In a debug build, with `adb logcat -s StrictMode` running: edit a contact, remove its photo, save; edit a private contact, remove its photo, save; open a private contact's page with a photo. No `DiskReadViolation` or `DiskWriteViolation` from `OriginalPhotos`, `VaultRepository.removePhoto` or `photoUri`.
7. **Private contacts' photos still work.** After 6: the private contact's page shows no photo; add one back: it shows on the page and in the list; open it full screen.

### 25.5 User corrections

Design: [EDITOR_DESIGN.md](EDITOR_DESIGN.md) ("4.4 corrections"), [CONTACT_PAGE_DESIGN.md](CONTACT_PAGE_DESIGN.md), [CONTACT_MODEL.md](CONTACT_MODEL.md).

1. **My card looks like a contact.** Contacts › My card opens a page like a contact's: a monogram, your name and job, QR code / Share / Edit tiles, then "Contact info" with your numbers, emails, work, websites and address, the private note, and "Your QR code and shared card include: Name, Numbers". Edit (tile or ✎) opens the contact editor with the same fields and components as any contact (no photo, no type selectors, one address), titled "My card", with chips for what the QR code and shared card include. Untick Numbers, tick Email, Save: the QR code and Share now include the name and email, not the numbers. Change your first number: Keypad › "Send my details" sends the new one. Scan theirs (QR) › "Show mine" still shows your code; with an empty card it offers to make one and opens the editor.
2. **Bulk Move to private works.** Select 5 device contacts (and one private one) › ⋮ › Move to private › Move. A dialog shows "Moving to private… 3 of 5" and can't be dismissed; afterwards "Moved 5 to your private contacts" and the selection clears; all five have the lock badge. With the vault locked (after a restart), the unlock prompt appears and the move goes on after it; cancelling the prompt lists the ones not moved. If one can't be moved (delete it from another app while it's selected), the result dialog says "Moved 4 contacts to private" and names the one that couldn't be moved, which stays selected. Leaving the screen during the move doesn't stop it.
3. **Editor alignment.** New contact: a 64 dp neutral circle with a camera on top, centred; "Save to: Device ▾" (no count; "Google · you@gmail.com", "Private", "Temporary" + "7 days ▾" as chosen) starts on the fields' left edge. Name, phone, email and every other row share one left edge after the icon gutter (person icon on the name block, phone icon on the phone). The name chevron sits in the ⊖ column, centred on the name block. Fields are 56 dp, like the contact page's rows. The Add chips wrap onto a second line instead of running off the screen ("Address" fully visible). Existing contact with a photo: the photo with a small edit badge; tap › Change / Remove. Check at font scale 1.3+, landscape (two columns) and RTL.
4. **Number buttons do different things.** A contact whose mobile has WhatsApp: the row shows the apps button and the Message button. Apps button: "Message or call on…" sheet. Message: opens the SMS app on a text to that number (no sheet). Long-press WhatsApp in the sheet to make it the usual app: the Message button shows WhatsApp's icon and opens a WhatsApp chat with that number. A number without apps shows only Message. TalkBack reads "Message or call on…, +44…", "Text message to +44…" or "Message +44… on WhatsApp".
5. **Temporary contacts ask before deleting.** Make a device contact and a private one temporary for 1 day; set the phone's clock 2 days ahead and run the daily upkeep (or wait a day). Nothing is deleted. One notification: "2 temporary contacts are due to be deleted" with Delete / Keep 7 more days / Keep permanently; on the lock screen it shows no name. Temporary contacts shows the same card at the top. Keep 7 more days: both show "7 days left"; Keep permanently: both leave the list and stay contacts; Delete: both go (the device one to History & undo). Ignore the notification: it isn't posted again the next day, but after 3 days it reminds once. Turn off Temporary contacts › "Ask before deleting temporary contacts" (Settings search finds it): expired ones are deleted by the upkeep as before. "Delete now" on one due contact deletes only that one.
6. **Variant chips.** A private temporary contact's page shows two one-line chips under the name: "🔒 Private" and "⏲ Temporary · 5 days left", side by side (a second row only on a narrow screen or large font). Tapping each opens its options as before. TalkBack reads "Private · hidden from other apps" and "Temporary · deletes itself on …".

### 25.6 After the review

Fixes from the 4.4 review. Unit tests: `ToCallRemindersTest` (app), `ToCallStoreTest` and `CallQualityStoreTest` (core:data), `CallAudioOutputsTest`, `SelfSilenceEchoTest` and `CallerChoiceRestoreTest` (core:common), `CallAudioOutputsTypesTest` (telecom).

1. **To call reminder shows once (H1).** Decline a call with Decline & remind › In 1 hour; set the clock forward 61 minutes (or wait). One "Call Sam" notification appears; it doesn't appear again or flicker, and `adb shell dumpsys jobscheduler | grep -i parley` shows no job re-running every few seconds. Not now: it comes back once an hour later. Remind several people for the same time: one notification lists them all.
2. **Auto-answer needs a call headset (M1).** Answer automatically › With a headset or Bluetooth on. Connect only a Bluetooth speaker that plays music (no call profile) and play music: a contact's call rings and is **not** answered on its own. Connect earbuds or a car's hands-free (or a wired headset): it is answered after the countdown.
3. **Countdown on the heads-up (M2).** Same setting, earbuds in, phone unlocked and in use in another app. A contact calls: the heads-up reads "Answering in 5 seconds" counting down, with **Don't auto-answer** next to Decline and Answer. Tap it: the countdown goes, the call keeps ringing, and it isn't answered on its own. Again without tapping: it is answered at 0 and the notification becomes the ongoing call.
4. **Delete on the lock screen needs unlocking (M3).** With a due temporary contact and lock-screen notifications showing all content, lock the phone. On Android 12+, tap Delete on the "due to be deleted" notification: the phone asks to unlock first, and only then deletes. On Android 10–11 Delete opens Temporary contacts after unlocking (and Parley's app lock, if on), where the same choice shows with names. Keep 7 more days and Keep permanently still work from the lock screen.
5. **Unreadable lists are kept (M4, L4, L5).** Hard to cause on purpose; with a debug build, clear the record key's Keystore entry while Parley is stopped, open Recents, then Decline & remind a call: Remind me says it couldn't set the reminder, and after a restart that restores the key the To call list is back as it was. No plain number ever appears in `shared_prefs/to_call.xml` (`adb shell run-as app.parley cat shared_prefs/to_call.xml` shows a sealed value).
6. **Parley's own silence isn't taken for yours (V1).** A contact with a haptic caller ID (Sam), phone on vibrate: Sam calls and the phone vibrates Sam's pattern until answered (it doesn't stop after a moment). In normal mode with Vibrate for calls on: Sam's tone rings with Sam's pattern. With "Answer automatically for chosen people" on for Sam and earbuds in: the countdown runs and the call is answered. Press the volume key while it rings: it goes quiet and auto-answer is cancelled. Repeat with an unknown caller's tone and with a rule's tone.
7. **Haptic caller ID with Vibrate for calls off (L2, L3).** Normal mode, Vibrate for calls off: Sam's call rings with the phone's tone straight away, with no short gap and restart. A rule with its own tone for Sam's label, Vibrate for calls on: the rule's tone plays and the vibration is Sam's pattern from the first buzz.
8. **Check it's really them only after the call ends (L1).** During a call from a "bank" number, Check it's really them › pick the saved bank: the call ends, then the saved number is dialled. If the call can't be ended (rare; some networks), a message says Parley didn't call the saved number, and nothing is dialled.
9. **Caller choices survive a move to a new phone (L6).** Give Sam (a phone-only contact) a vibration and auto-answer; back up; restore on another phone (or after clearing data) with Contacts ticked: Sam's vibration and auto-answer are back. A contact found only by name gets the vibration back but not auto-answer. Restoring a backup made by 4.4 brings back vibrations for contacts with the same lookup key, never auto-answer.
10. **Move to private after a rotation (L7).** Select 30 device contacts › Move to private while the vault is locked partway (lock Parley from the notification during the move). Rotate the phone while the unlock prompt shows: the prompt shows again on the rotated screen and the move goes on after unlocking; cancelling lists the ones not moved. The "Moving…" dialog never stays forever.
11. **Remind me tells the truth (L8).** Remind me on a missed call › In 1 hour: "Parley will remind you at …" appears only once it's saved, and the missed-call notification goes. With the list unreadable (see 5) it says "Couldn't set the reminder. Try again in a moment." instead, and the missed-call notification stays.

## 26. Who is this? (4.5)

### 26.1 Number memory

What Parley remembers about a number that isn't a contact, as one quiet line. Unit tests: `NumberMemoryTest` (core:common: ranking, privacy filter, snapshots, past calls, note excerpts) and `NumberMemoryIndexTest` (core:data, Robolectric: keyed-hash index, nothing stored plain, incremental rebuild, failing stores and keys). Parley is the phone app; the phone has a screen lock. Use a second phone to call in.

1. **Deleted contact.** Save "Plumber Mike" with the second phone's number, then delete him in Parley. Wait about 20 seconds (or run the daily upkeep). Call from the second phone with Parley unlocked: under the number the ringing screen says "You deleted Plumber Mike in <month> with this number". The ringing isn't delayed. Lock the phone and call again: the line says only "Parley knows this number"; unlock while it rings: the full line appears within a second.
2. **Post-call card.** After that call ends, the post-call card shows the same line with **Open history** (it asks to unlock first). It opens the number's history, whose header shows the line with **Restore contact**; tap it: "Restored Plumber Mike", his page opens, and the number is a contact again (no line any more).
3. **Keypad.** Delete Mike again. Type his number on the keypad: no contact matches, and the first result row reads "Not in contacts" with "You deleted Plumber Mike in <month> with this number" and **Restore contact**. The row appears once typing pauses; the keys never move. The first time, a tip explains the line; dismissed, it never shows again.
4. **Note on someone else.** On Ana's page, set the note for calls (or log a moment) to "Dr Lee's office: <a number>". After the daily upkeep (or reopening Parley the next day), type that number on the keypad: "In your note on Ana: “Dr Lee's office”" with **Open note**, which opens Ana's page. A promise ("[ ] call the garage <number>") works the same.
5. **Call note and old calls.** After a call with an unknown number, write a note in its history. Type the number on the keypad: "Your note after a call: “…”" with **Open history**. A number only in the call history shows "<n> calls in your history · last in <month>"; one that was a contact when it called (and isn't now) shows "Showed as <name> in your calls in <month>". The number's own history doesn't repeat calls, call notes or the last chat.
6. **Snapshots.** Delete a contact in another app (not Parley). After the next daily snapshot and upkeep, type their number: "Was saved as <name> until <day>" with **Open snapshot** (History & undo › Snapshots).
7. **To call and chats.** A number on the To call list shows "On your To call list since …" with **Open To call**; a number you messaged from Parley shows "You messaged this number on WhatsApp in <month>". Both appear at once (no upkeep needed).
8. **Private contacts.** Delete a private contact (it goes to "Deleted private contacts"). With the vault unlocked recently (open a private contact's details), type its number: "You deleted <name> in <month>…" with **Restore contact**, which asks for the vault's unlock and restores it as a private contact. Five minutes after the unlock, or with the phone locked, or in discreet mode: no line at all for it. A number in a private contact's note behaves the same. In discreet mode a private contact who calls shows only the number, with no "Parley knows this number".
9. **Nothing kept in clear.** `adb shell run-as app.parley ls no_backup/number_memory` shows `index.bin`; `adb shell run-as app.parley cat no_backup/number_memory/index.bin | strings` shows no number, name or note. It isn't in backups. Delete all Parley data: no line anywhere until new data builds up.
10. **Speed and failure.** With thousands of calls in the history, an unknown caller still rings at once and the line appears within about 2 seconds or not at all. Deleting `index.bin` only makes the line disappear until the next upkeep.

### 26.2 Personal reputation: "Looks like a sales line (your calls)"

Parley is the phone app. Use a second phone (or two) whose numbers are not in your contacts and that you never called. The tags come from the daily maintenance run; to learn at once after setting up the history below ("Learn" in the steps), turn Settings › Blocking & spam › Learn from your calls off and on again.

1. **Defaults.** Settings › Blocking & spam: "Learn from your calls" is on, "Silence numbers that look like sales lines (your calls)" is off (and greyed out while learning is off). Settings search finds both ("sales", "telemarketing", "reputation").
2. **Tag from one number.** From phone B, call and hang up after one ring (under 5 s) three times on one day; don't answer. Learn. The next call from B rings normally and the incoming screen shows, under the status, a quiet "Looks like a sales line (your calls)" with **Why?** (no warning colours). Why? lists "Hung up after ringing for under 5 seconds, 3 times", "None of its 3 calls was answered", "Never left a voicemail" and "Learned only from your own calls on this phone…"; Hide folds it. The first time the tag ever shows, one line explains it; not again.
3. **Declined calls.** From phone C, call twice and decline both times. Learn: C is tagged ("You declined all 2 of its calls", …). Calls you answered and ended within 3 s count as well.
4. **Recents and number history.** B's row in Recents shows "Looks like a sales line (your calls)" as its second line (a screening verdict, when there is one, wins). Long-press › "Why it looks like a sales line" opens the same reasons. B's number history shows the tag with Why?.
5. **False-positive guards.** Save B as a contact (or a private contact): no tag anywhere, at once on the call screen, and after the next run in Recents. Call C back once (or talk to C for over a minute): after the next run C is never tagged again. Emergency numbers, short codes (e.g. 3-5 digits) and hidden numbers are never tagged.
6. **A range.** From three numbers that differ only in the last 2–3 digits (e.g. a SIP/VoIP account with several numbers), call within a week and let them ring out. Learn. A fourth, never-seen number from that range is tagged ("3 different numbers from the same range called within a week"). Save any number of the range as a contact: the range is no longer tagged after the next run.
7. **Silence rule.** Turn on "Silence numbers that look like sales lines (your calls)". B calls: it rings silently (no sound or vibration), the tag still shows, and Blocking & screening › the call's "Why" trace has "Your calls: looks like a sales line: …" with the reasons in words. Repeat callers still ring: B calls again 30 s–3 min later and rings with "Rang through: called twice in 3 min". A contact, an allow rule for B, or Expecting a call all ring normally (and drop the tag). Block rules and spam lists come first. With the setting off, the trace says "looks like a sales line, tag only".
8. **Block this range?** After a call from a tagged number of the range in step 6 ends, the post-call card shows "Block this range?" with "Numbers starting +…… (3 numbers) · would have matched N past calls". Block range: "Range blocked…", with Undo; the rule appears in Blocking & screening as "Range from your calls" (a prefix rule). Undo removes it. With a single tagged number (no other number of its range called), or with someone you know in the range, nothing is offered.
9. **Learn from your calls off.** Turn it off: tags disappear from Recents and number history at once and the incoming screen shows none; the stored index is deleted. Turn it on: it learns again right away.
10. **Privacy.** `adb shell run-as app.parley cat shared_prefs/parley_reputation.xml` shows one sealed value: no numbers, no reasons. Delete all Parley data removes it. It isn't in backups (rebuilt from your calls).
11. **Nothing slower while it rings.** With learning on and a big call history, calls still ring at once: the call path only looks a number up; all the work happens in the daily run.

### 26.3 Family safety: safe word, helpers, expected-call hints

Design: [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md) ("4.5: family safety"), [SETTINGS.md](SETTINGS.md). Unit tests: `FamilySafetyTest`, `ExpectedCallsTest` (core:common). Parley is the default phone app; a label "Family" with Mum in it.

1. **Set the safe word.** Settings › Privacy & security › Family safe word lists the labels ("No safe word"). Tap Family: its page has "Family safe word" with a lock. Tap it: the fingerprint or screen lock is asked first; then "Safe word for Family" with the question "What's our word?" and an empty answer (dots; the eye shows it). Save: "Safe word saved", and the list says "Safe word set". Reopen within a minute: no second prompt, the answer is filled in. Remove: "Safe word removed". With no screen lock on the phone, it opens without asking.
2. **The card after 20 seconds.** Call from a number that isn't saved: answer, wait. At about 20 s a card under the caller: "Claims to be family? Ask: What's our word?" and "Press and hold to see the answer". Hold it: the answer shows in a filled box; let go: it hides. With Parley's app lock on and locked, the first hold asks for the fingerprint; hold again afterwards. With TalkBack, a double tap shows and hides it. Close (×) removes it for this call.
3. **On the lock screen.** Lock the phone during such a call (or answer from the lock screen): the card says "Ask your family question. Unlock to see it." Holding asks to unlock; afterwards the question shows and holding shows the answer.
4. **Says they're family.** A call from a saved contact who isn't in Family: no card. More › "Says they're family" (with its one-line explainer): the card shows at once. Mum (in Family) calling: neither the card nor the More row. An emergency call: never.
5. **Renames and deletes.** Rename Family to Relatives: Relatives' page says the safe word is set. Delete the label: the safe word goes with it. "Delete all Parley data" removes it; a backup never contains it.
6. **Helpers.** Settings › Calls › Family safety › Helpers: "Add a helper" lists contacts and private contacts with a number (several numbers ask which); add Sam, a private contact and one more; a fourth says "You have 3 helpers". Remove one with ⊖. Simple mode's setup shows the same list under "Helpers".
7. **Add my helper.** During a call from an unknown number, More › "Add my helper" (one helper: dials at once; several: a list). The call goes on hold; the card says "Calling Sam to join…" with Cancel. Sam answers: "Sam answered" and a green **Merge now**; tap it: the conference shows both, the card says "Sam joined the call" and goes. Sam declines: "Sam didn't answer", and the first call can be resumed. On a network that can't merge, the card offers Swap instead. Not offered during an emergency call, during a second call, or before the call connects. In discreet mode a private helper shows as a number.
8. **Simple mode.** Simple mode on, one helper: during a connected call a big "Add Sam to the call" button sits above the controls; it does the same as 7.
9. **Expecting a call from your notes: asked once.** Log an interaction with Dentist with the note "Dentist will call Tue" (or a promise "[ ] bank rings tomorrow at 3pm"). Parley asks "Expecting a call?" with the day and hours. Turn on: Settings › Blocking & spam › Expecting a call from your notes says "A note" and lists the window ("Note on Dentist"). No thanks instead: nothing is set and later notes don't ask again; turning the switch on there works.
10. **It rings through.** With "Block calls from numbers not in your contacts" on and the note's window now (write "will call today"), call from an unknown number: it rings, and the incoming screen says "Rang through: expecting a call (note on Dentist)" while unlocked, "(from your notes)" on the lock screen. Outside the window it's blocked as before. "Why did my phone ring?" shows "Expecting a call: from note".
11. **To call and delivery codes.** Decline & remind a call from an unsaved number: the first time, "Expecting a call?" asks about the To call list; once on, only that number rings through until a day after it's due ("on your To call list"). Scan a QR code with a courier's tracking link (e.g. a DHL or Evri link): asked once; once on, unknown numbers ring until 8 pm ("delivery QR code"). A Wi-Fi or contact QR code never asks. Turning a kind off removes its windows.

### 26.4 Sync watchdog and backup setup checker

The watchdog runs in the daily upkeep, after the day's snapshot. To run it at once: `adb shell cmd jobscheduler run -f app.parley <job id>` for the maintenance job (`adb shell dumpsys jobscheduler | grep -B2 parley-maintenance`), or wait a day. It compares the newest snapshot with the one it saw last, so take one snapshot first (open any contact's Version history, which also takes one). Unit tests: `SyncWatchdogTest` and `BackupSetupCheckTest` (core:common).

1. **Explainer.** Tools › Contact health check: a one-time tip above Accounts says what the watchdog does. Got it hides it for good.
2. **Contacts vanish from Google.** With 100+ Google contacts, delete 20 of them in the Google Contacts web app (or Google's Contacts app), let it sync, then run the upkeep. One notification: "20 contacts vanished from Google · you@gmail.com", "Since yesterday, and not deleted in Parley. The daily snapshots still have them." On the lock screen it reads only "Contacts may be missing": no account, no names. Tap it: Contact health check opens with the same card at the top.
3. **Never twice.** Run the upkeep again (and again the next day): no new notification, the card stays until answered.
4. **Restore from snapshot.** On the card, Restore from snapshot: the 20 are listed by name with their numbers and emails, all ticked; Select all toggles them. Untick two, Restore 18 contacts: "18 contacts restored." with Undo, the card is gone from the health check, and the 18 are back in Contacts in the Google account (in Phone if that account is gone). Undo: they go again (History & undo › Contacts lists the undo), the card comes back, and no new notification follows.
5. **It was me.** Delete 15 more on the web, run the upkeep, tap It was me: "Got it. Parley won't mention these again." The card goes and never returns for those contacts, even if an older snapshot is compared later.
6. **Parley's own deletes are not news.** Select 30 contacts in Parley › Delete, run the upkeep: no notification, no card. Same for Move to private, merging duplicates, temporary contacts that expire, and Contact health check › Delete all empty.
7. **Small losses stay quiet.** Delete 5 contacts on the web (or fewer than 5 % of an account of 1,000): nothing.
8. **Whole account gone.** A CardDAV (DAVx⁵) address book with 5 contacts: delete the address book on the server and sync. "All 5 contacts in CardDAV · … are gone", restorable as above.
9. **Account removed.** Settings › Accounts › remove a Google account that holds contacts: "Google · … was removed from this phone", "Its N contacts went with it. The daily snapshots still have them." Restore puts them in Phone.
10. **Sync switched off.** Settings › Accounts › Google › Account sync › turn Contacts off: "Contacts sync is off for Google · …" with Open sync settings (opens Android's account sync screen) and It was me; no Restore button. Turn auto-sync off for every account: "Sync is off for every account". Turning sync back on and off again later is a new event.
11. **Numbers lost.** With a test account, remove the phone numbers from 12 contacts on the web (keep the contacts). "12 contacts lost phone numbers"; Restore lists each contact with the numbers it had; Add numbers back to 12 contacts adds only the numbers (nothing else on the contact changes); Undo removes exactly those numbers again. A number only reformatted (+44 added) doesn't count.
12. **No contacts permission.** Revoke Contacts and run the upkeep: nothing is reported (snapshots can't be trusted without it).
13. **Backup status line.** Tools › Backup & restore, top of the list: one line with one fix. No passphrase: "Backups aren't set up yet" › Set passphrase. No folder: "Choose where your backups go" › Choose folder. Folder in the phone's Documents: the folder row reads "Parley · On this phone only" and the line "Your backup folder is on this phone only; that won't survive a lost phone" › Choose another folder. A memory-card folder: "…on a memory card…". A Nextcloud folder (Nextcloud app › its documents provider in the picker) or Google Drive: "Backed up … and checked · In Nextcloud" with no button. A phone folder named Syncthing: "On this phone, in a folder that looks synced", no warning.
14. **Age and checks first.** Remove the folder in Files: Back up now fails; the line reads "Parley can't reach the backup folder any more" › Choose folder. With the backup reminder at 14 days and the last backup 15 days old (set the clock): "Last good backup 15 days ago" › Back up now runs one. An unchanged backup ("Nothing changed") counts as fresh. No file in the folder is ever opened by the check (it only reads the folder's location).

### 26.5 User corrections

Unit tests: `PrivateOpenCostTest` (core:data: work per open, the split, edits, key upgrades, Recently deleted),
`PrivatePageOpenTest` (app: the page shows the caller-ID copy first, opens the details once), `SocialProfilesTest`
(real profile addresses of every service), `VCardImportTest`, `MeCardsTest`, `ContactSearchTest`.

**A private contact opens as fast as a device contact.** Use a phone with a screen lock (ideally a Pixel or Samsung
with StrongBox). Make a contact with a photo private (Make private), and have a few more private contacts.

1. Unlock the vault (open any private contact's details). Open the contact made private: name, photo, numbers, job
   line and note for calls appear at once, like a device contact; emails, addresses, dates and the rest fill in a
   moment later, without the page jumping or showing "Unlock". Go back and open it again: instant. Open a device
   contact and back: still instant.
2. The first time each contact made private before this version is opened it may take as long as before (it is
   reorganised then); every later open is quick. After the next unlock of the vault the rest are reorganised in the
   background.
3. While a private contact's page is open, change another contact (star it from the list, or edit a device
   contact): the page doesn't flicker or reload.
4. A private contact with a photo picked in Parley (kept whole): the header shows the small photo at once, then the
   whole picture; the viewer still opens the full picture.
5. Nothing lost: Make visible on the contact made private brings back every field and its photo as before; edit a
   private contact, save, Make visible: the edit and the original record are both there. Back up and restore: the
   private contact comes back whole. Delete it and restore it from History & undo › Deleted private contacts: whole.
6. Locked: wait more than 5 minutes after the last unlock, open a private contact: name, photo and numbers, and
   "Unlock to see all details", as before; unlock in place and the rest fills in.

**Profiles (Instagram, LinkedIn…).**

7. Edit a contact › Add › Profile: "Add a profile" lists Instagram, LinkedIn, X (Twitter), Facebook, TikTok, YouTube,
   GitHub, Bluesky, Mastodon, Threads, then Snapchat, Reddit, Pinterest, Twitch, Behance, Dribbble, then Other link.
   Pick Instagram: a "Profiles" row with "Instagram ▾" focuses. Type "@ana.lima": saved; paste
   `https://www.instagram.com/ana.lima/?igsh=abc` instead: the field shows "ana.lima". Paste an X link into an
   Instagram row: the row becomes X. Type "ana" in a Mastodon row: "Add the server too, like name@mastodon.social";
   "ana@mastodon.social" is fine. Other link adds an ordinary website row.
8. Save. The page shows a "Profiles" group in Contact info: "@ana.lima" over "Instagram" with its badge. Tap: the
   Instagram app opens on the profile when installed, else the browser (Parley has no internet permission; the app or
   browser does the fetching). Long-press: Copy (the handle) and Copy link. The same address isn't listed again
   under About.
9. Google Contacts (web or app) shows the profile as a website labelled "Instagram" after sync; a website another app
   saved as https://github.com/ana shows in Parley's Profiles as GitHub.
10. Contacts search "ana.lima" or "@ana.lima": the contact is found, "Matched: profile".
11. A private contact: the same editor rows and page group; the profiles survive Make visible (as labelled websites).
12. Import a vCard from an iPhone with `X-SOCIALPROFILE;type=linkedin:http://www.linkedin.com/in/ana-lima` (and
    `SOCIALPROFILE;SERVICE-TYPE=Mastodon:https://example.town/@ana`): both appear as profiles. Export the contact and
    import it again: no duplicates.
13. My card › Edit: a Profile chip; add LinkedIn. In the share line tick "Profiles": the QR code (scanned with another
    phone's camera) and the shared card include the profile as a labelled link; untick it: they don't. My card's page
    lists the profile and opens it like a contact's.

### 26.6 After the review

Unit tests: `ExpectedCallsTest` and `CallPolicyTest` (core:common: which notes expect a call, and what an expected
call never gets past), `FamilySafetyTest` (helper Cancel), `SyncWatchdogTest` (contacts that came back),
`VaultKeyLifecycleTest` (core:data: the unlock check fails closed), `MeCardDetailsTest` (app).

**Expecting a call: never past your rules.** Turn on "Expecting a call from your notes" (26.3 step 9).

1. Log a note "Dentist will call today". With "Block calls from numbers not in your contacts" on, an unknown number
   rings ("Rang through: expecting a call (note on Dentist)"), as before.
2. In the same window: a number with a block rule of yours, a number covered by a range rule ("Silence this range" or
   "Block range"), a number a spam list blocks, and (with "Silence numbers that look like sales lines" on) a tagged
   sales line are all still blocked or silenced as without the note. "Why did my phone ring?" for those shows the rule
   or list, not "Expecting a call". Turning "Expecting a call" on by hand (the tile) still lets everyone through, as
   before.
3. Notes that are not a call to expect open nothing: "Ring the plumber tomorrow", "Phone bill due today", "Call Ana
   today", "I'll call the bank today". These do: "Garage is calling today", "Asked them to call me back today",
   "Expecting a call from the bank today", "Courier today".
4. Edit the note to "Dentist came by" (or delete the entry, or tick "[ ] dentist will call today" done): Settings ›
   Blocking & spam › Expecting a call from your notes no longer lists its window, and unknown numbers are blocked
   again. Undo of the delete brings the window back ("A note"). Two notes on one contact each keep their own window.
5. After a call with a saved or unsaved number, write the call note "They'll call back today": only that number rings
   through; another unknown number is blocked. Delete the call note in the number's history: the window goes.
6. A To call item for an unsaved number (26.3 step 11): mark it done, or call the number back: its window goes.
7. Discreet mode on, a note "will call today" on a private contact: the incoming screen says "(from your notes)" and
   the windows list says "A note", never the private name. Discreet mode off: "Note on <name>".
8. Windows made by an older version from notes are dropped on update (re-save the note to get one back).
9. Cold start: force-stop Parley, then call from an unknown number inside a window. The call is screened at once (no
   delay); the window applies from the next call at the latest (it is read in the background at start).

**Private data stays locked when the check fails.**

10. With private contacts and a number one of them had in a note: remove the screen lock and set it again (this
    invalidates the vault key), then type that number on the keypad. No private hint (no name, no note excerpt)
    shows. A backup made then still saves what's left of the private contacts (name, numbers), as before.

**Restore from snapshot never duplicates.**

11. Make 20 Google contacts vanish (26.4 step 2), let the card appear, then bring them back (undelete on the web
    within 30 days, or re-sync). Run the upkeep: the card goes by itself. Instead, open Restore from snapshot after
    they came back: "They're back already" and the card goes. With 5 of the 20 back: 15 are listed, "5 contacts
    already came back, so they aren't restored again." Restore writes 15, never 20.

**My card keeps every link.**

12. Import a vCard for yourself with `item1.URL:https://www.linkedin.com/pulse/some-article` and
    `item1.X-ABLabel:LinkedIn`, set it as My card (or give a My card website row that label in Google Contacts), open
    My card › Edit and save without changes: the article link is still there, as a website.

**Locks forget opened private details.**

13. App lock on with "Lock immediately": open a private contact (vault prompt), leave Parley and come back, unlock the
    app: opening that contact again asks for the vault once more if its own unlock has run out. Same with a 1-minute
    timeout after 2 minutes away, and with any timeout after turning the screen off and on.

**Safe word.**

14. Settings › Family safe word › a label: the answer field shows a password keyboard with no suggestions bar, and
    after saving, typing the first letters of the answer in another app suggests nothing from it (Gboard: also check
    Settings › Dictionary that it wasn't learned).
15. During a call, hold the answer: a screenshot or screen recording of that moment shows a black screen (with "Hide
    screen content" off; the flag is put back when the answer hides). With TalkBack on speaker, a double tap says
    "Answer shown" and does not read the answer out; moving to it reads it. Lock Parley (Tools › Lock now) during the
    call after the card showed: the next hold asks for the fingerprint first.

**Add my helper: Cancel right away.**

16. During a call, More › Add my helper, and tap Cancel on the card immediately (before the second call appears):
    the helper's call, once it appears, is ended within a moment; the first call stays on hold and can be resumed.
    Cancel while it rings: ended at once, as before.

**Silence this range.**

17. After a call from a tagged number of a range (26.2 step 8), the post-call card asks "Silence this range?" with
    **Silence range** first and **Block range** beside it. Silence range: "Range silenced. Its calls ring quietly and
    show as missed…" with Undo; the rule in Blocking & screening is "Range from your calls" with Silence. Block range
    instead writes it with Reject ("Range blocked…"). Undo removes the rule ("Done. That range rings as before."). A
    range that already has a rule: "This range already has a rule."

**Downgrades.** Installing a Parley older than 4.5 over this one (`adb install -r -d`) is not supported: clear
Parley's data first, then restore a backup (docs/CONTACT_MODEL.md, "Downgrades").

## 27. Cards that stay current (4.6)

### 27.1 Paste anything → contact

Copy each text below in another app (Notes, Gmail, a web page), then in Parley start a new contact (Contacts › +).

1. **Chip and tip.** A new contact shows a **Paste details** chip at the top (not when editing a contact or My card),
   with the one-time tip under it. Nothing reads the clipboard until the chip is tapped (on Android 12+ the system's
   "Parley pasted from your clipboard" notice appears only then). Empty clipboard: "Nothing to paste…".
2. **British signature.** Copy:
   ```
   Best regards,
   Jane Doe
   Head of Partnerships | Acme Widgets Ltd
   M: +44 7911 123456 | T: +44 20 7946 0958
   jane.doe@acmewidgets.co.uk | www.acmewidgets.co.uk
   12 High Street, London SW1A 1AA, United Kingdom
   ```
   Tap Paste details: the sheet "Details found" lists each value with its type above it and a ticked box: Name,
   Company, Job title, Mobile, Work phone, Work email, Work address, Website. "Best regards" isn't listed. Untick the
   Work phone, tap **Fill in**: the form has Jane / Doe, Acme Widgets Ltd, the title, one Mobile number, the email, the
   address split into street, city, postcode and country, the website. Nothing is saved until Save.
3. **What was typed stays.** New contact, type "Janie" as first name, tap Paste details with the same text, Fill in:
   the first name stays "Janie", the rest is added; paste again: nothing is added twice.
4. **German, French, Dutch signatures.** Copy a signature with "Tel.:", "Fax:", "Mobil:", "Tél. :", "Portable :",
   "T +31…" / "M +31…" lines and a "10115 Berlin" / "75001 Paris" / "1015 CJ Amsterdam" address: the numbers get
   Work phone / Work fax / Mobile, national numbers are read in the SIM's country (a German SIM reads "0151 23456789"
   as +49 151…), prefixes such as "Dr." go into the name's prefix (name chevron), "Marie DUPONT" becomes Marie Dupont.
5. **Extensions.** "Office: (650) 253-0000 ext. 1234" shows as "+1 650-253-0000 ext. 1234"; after Save the number
   dials the extension after a pause.
6. **Profiles and map links.** Text with "Instagram @ana.lima", "Twitter: @analima", "https://github.com/analima"
   and "https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1268141,17z": the preview says "Instagram profile",
   "X (Twitter) profile", "GitHub profile" and "Map link"; after Fill in they appear as Profile rows and the address's
   map link (the address is filled from the link's place when the text had none).
7. **Birthday only when said.** "Birthday: 12 March 1990" fills a birthday; a date without its word does not.
8. **Several people.** A team list (two names, each with a title, email and number, with or without a blank line
   between): "This text has 2 people. Choose one:" with a chip per person; choosing one changes the list below.
9. **Event badge, RTL.** "HELLO my name is / JANE DOE / ACME CORP / Speaker" gives Jane Doe, ACME CORP, Speaker.
   An Arabic name with "مدير المبيعات" and a +971 number: the name and title are right-to-left, the number left to
   right; layout mirrors correctly in Arabic.
10. **Leftovers.** Opening hours or a slogan come as a ticked Note; a long paragraph comes as an unticked Note.
    Confidentiality disclaimers and "Sent from my iPhone" are left out.
11. **Already a contact.** Paste a text whose number or email belongs to an existing contact: the sheet says
    "<name> already has <number>" and offers **Add to <first name>** (primary) and **New contact**. Add to: the
    existing contact's editor opens with the new rows appended (Save enabled); a private contact's editor (after the
    vault's unlock) likewise. With discreet mode ("Hide private contacts") on, private contacts are never named here.
12. **Save destinations.** Paste and fill, then Save to: Private: the contact is private with every pasted field;
    Save to: Temporary (7 days): it is temporary with them.
13. **Share target.** In Chrome, select a "Contact us" block (or share text from another app) › Share › Parley ("Call
    or message a number"): when the text is more than a number or a map link, the sheet offers **Make a contact from
    this text** (also in the number picker for several numbers, and on "No phone number found" for an email-only
    signature). Tapping it opens a new contact with the "Details found" sheet already open. A lone number or a map link
    doesn't offer it. With the app lock on, Parley asks to unlock first.
14. **On-device only.** With the network off (airplane mode) everything above works the same. No new permission is
    asked for.
15. **Look.** Light, dark and AMOLED; font size 200 % (the type above each value wraps, nothing is cut); landscape
    and a tablet (sheet width capped); TalkBack reads each row as "Mobile, +44 7911 123456, checkbox, checked" and
    toggles it with a double tap.

### 27.2 Signed card updates, "Shared with" and "Changed my number"

My card is shared signed, a contact's page offers a newer card from the same person, and My card keeps a private list of who has it. Unit tests: `SignedCardsTest`, `CardTrustTest`, `CardDiffTest`, `ShareLedgerTest` (core:common: canonical form, signature over every field, tamper detection, folded lines, several cards per file, version ordering, key mismatch, the diff that never touches the user's own numbers, the ledger and who has an old number), `CardStoresTest` (core:data, Robolectric: the secret and the ledger sealed at rest, versions that grow only on a change, the identity moving through a backup, links following a re-key), `CardUpdateApplyTest` (app: a new number takes the old one's row). Use two phones with this Parley ("Ana" and "Bo"); a third phone with any camera app or an older Parley for compatibility.

1. **Plain scanners and older apps.** On Ana's phone fill in My card (name, a number). Show its QR code and scan it with a camera app or Google Contacts: the contact reads as before (name, number); nothing odd is shown. Share the card as a file to an older Parley or Google Contacts: it imports as before. The first time My card shows, a tip explains signed cards.
2. **First swap.** On Bo's phone tap **Scan theirs**… or scan Ana's code from Tools › Scan QR code. The result sheet says "Signed card: once you save this contact, Parley will offer their future updates." Tap **Add contact** and save. Open Ana's page once. (If Bo already had a contact with Ana's number, the sheet says "Signed card, linked to Ana" instead.)
3. **Shared with.** On Ana's phone, after showing her code and tapping **Scan theirs** to scan Bo's: My card › **Shared with** lists Bo, "QR swap", today. **Send my details** to a number from Message or call on…, and **Introduce myself** to a contact, add rows ("Send my details", "Introduce myself"); a person reached twice shows "2 times". Remove one (trash icon, confirm): it's gone; **Clear list** empties it. `adb shell run-as app.parley cat shared_prefs/card_sharing.xml` shows no name or number (sealed). The footer shows the card's key fingerprint.
4. **Number change.** On Ana's phone edit My card: replace the number. Back on My card a banner says "You have a new number. 1 person you shared your card with has the old one." with **Tell them**. **Not now** hides it until the numbers change again.
5. **Changed my number wizard.** **Tell them**: pick WhatsApp (or **SMS**). Bo's name and number show with **Open in WhatsApp**: the chat opens with "Hi, it's Ana. My new number is … Please update it in your contacts." filled in; nothing is sent until Ana presses Send. Coming back moves to the next person (or the summary). **Send my card** instead opens the share sheet with the signed card file. Afterwards the banner on My card is gone and Shared with shows "New number" (or "Card file").
6. **Update on the other phone.** Bo opens the card file from step 5 (or scans Ana's new QR code). The import dialog / result sheet says "Ana sent an updated card. You can review it on their page." with **Open**. Ana's page shows "Ana sent an updated card: new number" with **Review** and **Not now**. **Review** lists "Number: +44 7700 900123 → +44 7700 900456" ticked; a changed name is listed unticked. **Apply all** updates the contact (the number keeps its type; a second number Bo added himself stays) and the banner goes. Scanning the same card again says "Bo's contact already has this card"; nothing is offered twice. **Ignore** instead leaves the contact as it is and doesn't offer that version again.
7. **Private contacts.** Make Ana private on Bo's phone (Settings for this contact › Make private) before step 6: the banner shows on her page once the vault is unlocked, **Apply** asks for the unlock if needed, and the private contact is updated (nothing appears in the address book). In discreet mode the scan of her card says nothing about her.
8. **Different signer.** On a third phone, make a card with Ana's name and share it; then edit the file so its `X-PARLEY-CARD` line carries Ana's card id (from Ana's file), keeping that phone's own key and signature. Opening it on Bo's phone: "This card says it's Ana's, but a different key signed it (…). It may not be from them, so nothing was offered." Ana's page offers nothing.
9. **Tampered card.** Change one digit of the number in Ana's card file with a text editor and open it on Bo's phone: "This card's signature doesn't match: it was changed after it was signed." Nothing is offered.
10. **Backups.** Make an encrypted backup on Ana's phone and restore it on a freshly installed Parley: Shared with is back, and the next card she shares has the same key fingerprint, so Bo's Parley still offers it as an update. On Bo's phone, a restore keeps Ana's link (her page still offers her next update). "Delete all Parley data" removes the card key, the list and the links.

### 27.3 Finding your way: Coming from…, What Parley can do, the blocking header, explainers

Unit tests: `CapabilityCatalogTest` (core:common: every settings row names a real setting, unique keys, every job has rows, search, "New in" by release), `ScreeningPresetsTest` (core:common: the setup you're on, the weekly line, contacts read from real traces), `TipsTest` (explainer ids survive dismissal) and `CapabilityRoutesTest` (app, Robolectric: every row and every importer opens a screen registered in the graph, or a tab; every row has its texts).

**Coming from another phone? (P7).**

1. Fresh install (or clear Parley's data) with Parley not yet the phone app: onboarding has a fourth step after permissions, "Coming from another phone?", with Contacts (Google Contacts, iPhone or iCloud, Samsung phone), Call history (from a CSV file) and Block lists (Call Blocker, Yet Another Call Blocker, NoPhoneSpam). Back returns to permissions. **Skip** ends onboarding on the home screen, as before.
2. Tap a source: it unfolds (TalkBack says "Expanded") to where to export it on the old phone, and one button. Google also says that contacts of a Google account signed in on this phone are already there. Only one source is open at a time; tapping it again folds it.
3. **Import contacts** ends onboarding and opens Settings › Contacts with "Import from .vcf or .csv file" highlighted; pick a `.vcf` exported from an iPhone and from Google: both import with the usual report. **Import call history** opens "Import call history from CSV"; **Import a block list** opens Blocking & screening › Import & share. Nothing is downloaded at any point (airplane mode changes nothing).
4. Later: Tools › "Coming from another phone?" opens the same list as a page (intro first); the buttons open the same importers, and Back returns to the list. Settings search "iphone" or "yacb" finds it.

**What Parley can do (P8).**

5. ⋮ › Tools and the **Tools** row at the top of Settings open the hub (it was called "What Parley can do"); Settings search "features" finds it. The page groups one-line rows under Stop spam, Never lose a contact, Stay in touch, Know who's calling, Keep it private, Message without saving and Calls that work better. With a 4.6 build, "New in 4.6" comes first with "Coming from another phone?".
6. Tap rows from each group: each opens its feature (Blocking & screening, Spam lists, History & undo on Contacts or Snapshots, Backup, Birthdays & dates, To call, Labels, Scan QR, My card, the privacy dashboard, Call time, SIMs & plan minutes, Simple mode…). Setting rows open their Settings page with the setting highlighted (e.g. "App lock", "Answer automatically"). "Keep in touch with your Circle" opens the Circle tab even when it's hidden from the bar; "Message a number without saving it" opens the Keypad (docked in Recents with the combined layout). Back returns to the page with the search kept.
7. Search: "whatsapp" shows the message rows; "undo" shows History & undo; "zzz" shows "Nothing here matches “zzz”" with **Clear search**. With a large font (200 %) and in landscape the rows wrap and nothing is cut off; RTL (Arabic) mirrors the page.
8. After updating from 4.5 (not a fresh install), the What's new card on home has one link, **See what's new**, plus **Got it**; neither changes tabs, and the card doesn't come back. A fresh install shows no card.

**Blocking & screening header (P9).**

9. A fresh install: Blocking & screening starts with a card "You're on: Let everyone ring", "Last 7 days: no calls stopped" and the four setups as chips (the current one selected). Tap "Only people I know": its explanation asks first (**Use this**); afterwards the card says "You're on: Only people I know". Add "Quiet nights": "Only people I know · Quiet nights". Turn on only "Silence or block hidden numbers" by hand (after "Let everyone ring"): "You're on: your own mix".
10. Below the card: the screening status, Expecting a call, the two main switches, Spam lists, then an **Advanced** header over Always let through, Your rules, Off hours, More checks, Sounds, Emergency numbers, Tools and the system block list; the log of recent calls stays last.
11. Weekly line: with "Only people I know" on, call once each from two unknown numbers, and once from a contact (not a favourite) while Off hours let only Favourites ring and silence the rest: the card says "Last 7 days: 3 calls silenced · 1 contact affected"; the same contact calling again still counts as 1 contact. A rule set to Reject adds "…, 1 declined". Calls older than 7 days drop out.

**One-line explainers (P18).**

12. The first time each concept shows, one tip card explains it, with **Got it**: a private contact's page (under the "Private" chip), a temporary contact's page or Temporary contacts, the Circle tab with people in it, Labels (with labels), Favourites (with favourites), History & undo. To call keeps its own tip. Only one tip shows at a time; dismissed tips never come back (also after restarting Parley) until Settings › Appearance › Reset tips. The wording matches docs/GLOSSARY.md.

### 27.4 After the review

Fixes to signed cards, Shared with, Changed my number, the blocking setups and paste. Unit tests: `CardTrustTest` (core:common: every way to take over a contact's updates — a fresh card id with the contact's number, the contact's card id signed by another key, a held card replaced by another key, a held card linked on a page open — changes no link the user didn't choose; first link and "Trust the new card" are explicit; wider shares), `SignedCardsTest` (strict reading: a name put before the signed one, extra numbers, `X-ANDROID-CUSTOM`, notes, padding past the line cap, a card inside the card, middle names or city parts, other parameters, unescaped separators are all "changed after signing"; an honest folded card still verifies; the importer saves what the signature covers; signed parts; profile label/link can't shift; versions never repeat), `CardDiffTest` (the user's own address, company or title is never replaced or ticked; removals never ticked; a part left out isn't a removal), `ScreeningPresetsTest` (the schedule "Only people I know" would remove, keeping it; one person from two numbers; contacts stopped by the system list), `CardStoresTest` (core:data, Robolectric: showing a card doesn't mark the key used; a restore on a phone whose card was shared asks; versions after a restore; private contacts' receipts and links sealed with the vault key and kept out of the general backup; a restored link never replaces one; the reseal sweep), `CardUpdateApplyTest`, `PasteFillTest` ("Add to" skips what the contact has), `PasteInboxTest` and `NavigationRoutesTest` (pasted text bound to its editor, with an expiry). Phones "Ana", "Bo" and a third one ("Mallory") as in 27.2.

1. **Nothing links by itself.** On Bo's phone, with a contact "Ana" that has Ana's number and no card linked, scan Ana's QR code: the result sheet asks "This card says it's Ana (key …). Link it to Ana so their future updates show here?" with **Link**. Close the sheet without tapping it: Ana's page asks the same ("A signed card you received says it's Ana…") with **Link** and **Don't link**. **Link** links it (later updates are offered as in 27.2 step 6); **Don't link** forgets that card.
2. **A new contact from a card.** Scan a card for someone Bo doesn't have: "Signed card. Once you save this contact, their page asks whether to link it…". Save the contact and open its page: it asks; nothing is linked until **Link**.
3. **Takeover attempt, new card id.** With Ana linked, Mallory shares a card named "Ana" with Ana's number (her own key, her own card id). On Bo's phone: "This card says it's Ana's, but a different key signed it (…). It may not be from them, so nothing was offered." with **Trust the new card**; Ana's page shows the warning too ("A different card says it's Ana…", **Trust the new card**, **Ignore**). Ana's link is unchanged: her next real card still arrives as an update, and Mallory's later cards offer nothing.
4. **Takeover attempt, Ana's card id.** Mallory's card carrying Ana's card id with Mallory's key: the same warning; Ana's held or linked card isn't replaced.
5. **A real key change.** Ana reinstalls without a backup and shares her card again: Bo sees the different-signer warning. **Trust the new card** shows "Trust the new card for Ana?" with both keys; confirming links the new card, and her next updates are offered from it.
6. **Tampered card files.** Edit Ana's card file in a text editor: add `N:Evil;Mallory;;;` before her name, or a second `TEL:`, a `NOTE:`, an `X-ANDROID-CUSTOM:` line, 400 lines of padding, or change `ADR:;;` to `ADR:;;street;City;;;`. Each one opening on Bo's phone says "This card's signature doesn't match…"; none says verified. A file with Ana's signed card plus an unsigned look-alike says "1 other card here isn't signed…".
7. **Fewer parts.** Ana shares the full card (all parts ticked), then a QR with only Name and Phones after changing her number. Bo's review lists only the number change: nothing about her email, website or address. A later share of the same card with more parts arrives as an update with the extra fields.
8. **Ticks.** In Review, removals ("Remove …") and the name are unticked; a company or title Bo typed himself shows unticked; a new number or added email is ticked. On a contact with two addresses (one Bo typed, one from Ana's card), Ana's new address replaces only hers; Bo's stays.
9. **Versions.** Open My card's QR code several times without changing anything, and open the QR swap: Bo receives no update. Changed my number › **Send my card** sends the same version as the QR code with the profile's email.
10. **Restore and the card key.** On a new phone, open My card's QR code (don't share) and then restore Ana's backup: the next card has her old key, no question asked. On a phone that already shared its own card (file or swap), restoring asks on My card: "Your backup has your earlier card key…" with **Use earlier key** and **Keep this one**. After a restore, Ana's next changed card is offered on Bo's phone as an update (versions never go back).
11. **Private contacts in Shared with.** Introduce yourself to a private contact (or Send my details to a private contact's number): Shared with shows their name while discreet mode is off and hides them when it's on; `run-as … cat shared_prefs/card_sharing.xml` has a separate `ledger_private` entry. The general backup's people section has no receipt naming them; restoring the private contacts brings the receipt back.
12. **Changed my number keeps its list.** Start the wizard, open a chat, rotate the phone (or switch dark mode) while in the chat, come back: the same person is shown with **Next**; nobody is skipped, and the summary counts are right. A person counts as told (Shared with) only after **Next**.
13. **Only people I know.** Set "Block non-contacts" with a weekday schedule, tap the **Only people I know** chip: the dialog says "This replaces your schedule for unknown callers (Mon–Fri …)" with **Keep my schedule**. Ticked: the schedule stays after **Use this**; unticked: it is removed, as the dialog said.
14. **Weekly line.** A contact who calls from two numbers counts as one contact affected; a contact on the system's blocked numbers list counts as a contact; a busy week with more than 500 stopped calls is counted in full.
15. **Share sheet and paste.** Share a long text to Parley: the number sheet opens at once and **Make a contact from this text** appears a moment later. Make a contact from shared text, cancel the app lock, then open a new contact from Contacts: the editor doesn't fill in the old text. "Add to Ana" with Ana's own signature adds nothing twice (website, address, birthday) and no empty number row.
16. **Language reset.** After the first start of this version, later starts don't touch the per-app language again (no extra disk read on the main thread under StrictMode).

## 28. Everyone can call (4.7)

### 28.1 RTT and the call quality diary

Unit tests: `RttTextTest` (core:common: streaming into one message, backspace and DEL, an emoji deleted whole, a backspace after a line break reopening the message, both sides typing at once, LF / CR LF / U+2028, keep-alives and control characters dropped, the 20,000-character cap, the typing diff for typed, deleted, autocorrected and pasted text), `CallQualityDiaryTest` (rates per SIM and network, "Calls with Mum on SIM 2 in the evening" with Try calling on SIM 1, a part that narrows nothing left out, Wi-Fi calling and mobile advice, even drops giving no pattern, hidden callers never named, parts of the day, the number line) and `CallQualityStoreTest` (core:data: every row by line key). RTT needs a SIM whose carrier supports it (a US carrier such as Verizon, AT&T or T-Mobile, on 4G or Wi-Fi calling) and a second phone that supports RTT (Google Phone or an iPhone with RTT on).

**RTT.**

1. On the RTT phone, Android's Settings › Accessibility › RTT (or Parley's Settings › Calls › Accessibility › **TTY and RTT settings**, which opens it): set RTT to visible / always available. Parley's **Answer with RTT** row has no "None of your SIMs offers RTT" line once the carrier offers it. On a SIM without RTT (most non-US carriers), the line shows and no RTT row appears in calls.
2. Call the second phone, answer there. Parley › More: **Switch to RTT** with "Type and read during the call (real-time text), where your carrier supports it". Tap it: "Asking to switch to RTT…" under the caller; when the other phone accepts, the **RTT conversation** sheet opens by itself, and More now says "RTT conversation".
3. Type "Helo", backspace twice, "llo": the other phone shows "Hello" letter by letter, the corrections too. Tap **Send**: your bubble closes on the end side. On the other phone type "Hi there": it appears on the start side as it's typed, with "typing…", and closes when they send. Type at the same time on both: two open bubbles, nothing mixed up. An emoji and its deletion arrive as one character.
4. Autocorrect a word (Gboard): the other phone sees the backspaces and the corrected word. Rotate the phone and switch dark mode with the sheet open: the text and what you were typing stay.
5. TalkBack: a finished message from them is read once ("Sam: Hi there"); letters being typed aren't spelled out; Send, Turn off RTT and Save have labels.
6. Audio chips: **Listen and type** and **Talk and read** change the audio as Android does (HCO / VCO); **Talk and type** returns to both ways.
7. Request from the other side: from the second phone, start RTT during a voice call. Parley shows "Sam wants to switch to RTT" under the caller with **Switch to RTT** / **Not now**. **Not now** stays a voice call; asking again and **Switch to RTT** opens the conversation.
8. A request that fails (call a number whose network doesn't do RTT, then Switch to RTT if offered): "Couldn't switch to RTT. The network or the other phone didn't accept it." with a close button; the call goes on.
9. **Turn off RTT**: the sheet says "RTT is off. The call goes on as a voice call." and More offers Switch to RTT again.
10. **Save to the call's note**: the button turns into "Saved to the call's note". After the call, the number history (and the contact's timeline) has a call note "Sam: … / You: …". Without Save, nothing is kept: after the call the sheet says "The call has ended. The conversation isn't kept unless you save it."; Save still works there; after closing the call screen it's gone.
11. Lock screen: with the phone locked, the call screen shows the conversation and typing works; the call notification and the lock-screen notification never contain any RTT text; the picture-in-picture window shows none either.
12. **Answer with RTT** on: an incoming voice call answered in Parley (slider, tap, notification, auto-answer) asks to switch once connected and opens the conversation. An incoming RTT call opens the conversation whatever the setting. Off (the default): nothing switches by itself.
13. Private contact: an RTT call with a private contact shows their name in "wants to switch to RTT" and the saved note goes with their calls; in discreet mode only the number shows, as everywhere on the call screen.

**Call quality diary.**

14. Make or receive about ten calls over a few days, on two SIMs if you have them, some over Wi-Fi calling; let two or three drop (walk out of coverage, or turn on airplane mode during a call on the mobile network). Recents › Call insights: under People, **Call quality** with its explainer; "N of M calls dropped" and the percentage; with two SIMs a row per SIM; once Wi-Fi calling carried a call, "Wi-Fi calling" and "Mobile network" rows. Without drops it says "No dropped calls" with the count.
15. Patterns: let three calls with one person drop on one SIM in the evening while other calls (that person by day, others in the evening, the other SIM) don't: **Patterns** shows "Calls with Sam often drop on Work in the evening" with "3 of 4 calls dropped · Try calling on Personal" (or "Try Wi-Fi calling" with one SIM). Drops over Wi-Fi calling only: "… over Wi-Fi calling · Try the mobile network instead of Wi-Fi calling". Drops spread across everyone: no pattern.
16. **Recent dropped calls**: newest first with time, talk time and SIM; **Call again** calls the same number on the same SIM; tapping the row opens the contact (or the number's history). Hidden numbers are counted but never listed.
17. Private contacts: their dropped calls are named with discreet mode off (and Call again works); with discreet mode on they count in the rates but aren't named or listed.
18. Number history of someone with two or more connected calls: "Call details" starts with "7 calls in 60 days, 2 dropped, all on Work · 3 over Wi-Fi calling · 1 in HD voice" (the SIM only on dual-SIM phones).
19. Deleting a call or a number's history (History & undo) removes its quality facts: the card's counts drop accordingly.

### 28.2 Drive profile and calling abroad

Needs a car (or any Bluetooth hands-free kit or headset standing in for it), a second phone, and for the abroad steps a dual-SIM phone abroad (or a SIM roaming in another country). Have a favourite, a contact chosen for auto-answer, an ordinary contact, a private contact, and an unknown number ready.

1. **Marking the car.** Settings › Calls › On the road › Drive profile. Android 12+: without "Nearby devices" the row **Show paired devices** asks for it; after allowing, the paired devices are listed by name. Android 10–11: connect to the car first; it is listed with "Connected now". Tick the car: the Calls page says "On for <car name>". The switches below are greyed out until a car is ticked. Untick it: the profile is off. Settings search "car", "driving", "roaming" finds Drive profile and the two abroad switches (which open SIMs & plan minutes).
2. **Say who's calling.** Car connected, ringer on: the favourite calls; "<name> is calling" is said once, through the car's speakers (or the headset), while it rings. It stops as soon as you answer or decline. Not said: for an unknown number, for a private contact with discreet mode on (it rings like an unknown number), with the phone on vibrate, silent or Do Not Disturb, after pressing a volume key, or for a call waiting during another call. A private contact with discreet mode off is announced by name.
3. **Auto-answer in the car.** Turn on Answer favourites automatically, 3 seconds. The favourite calls: "Answering in 3" with Cancel, then the call is answered and goes to the car. Cancel works. An ordinary contact isn't answered; with Answer people chosen for auto-answer on, a contact chosen on their page is. Disconnect the car (Bluetooth off) and call again: not answered, no "Drive profile on". Never during another call, never an unknown or hidden number.
4. **Status and replies.** With the car connected, the incoming and in-call screens show "Drive profile on" with a car icon under the caller; the first time a one-line explainer sits under it. Reply on an incoming call: "While driving" with three driving replies comes first; tapping one declines and sends it. Without the car, the reply sheet is as before.
5. **Silence unknown callers.** Turn it on. An unknown number calls with the car connected: no ringing, the status says "Silenced while driving", and it's a missed call afterwards. A contact, a private contact (also in discreet mode) and a repeat caller let through by "Repeat callers ring through" still ring. An emergency call-back (call 112 test line where allowed, or within the hour after an emergency call) always rings.
6. **Abroad: assisted dialling.** With the call's SIM abroad (e.g. a UK SIM in France), type a home-format number (07400 123456) and call: "Call +44 7400 123456?" with "You're abroad, so Parley added the country code for United Kingdom". **Call** dials +44…; **Dial as typed** dials the number unchanged; tapping outside cancels. With confirm before calling on, no second question follows. Nothing is asked for 112, 999, *#06#, a number starting with + or 00, a short code (118 118) or a freephone number, nor at home. Turn Settings › Calls › SIMs & plan minutes › Abroad › Assisted dialling abroad off: nothing is asked.
7. **Abroad: local SIM.** Dual-SIM with SIM 1 roaming and SIM 2 local: call on SIM 1: "Use SIM 2?" — **Use SIM 2** places the call on SIM 2, **Keep SIM 1** on SIM 1. The next call on the same trip doesn't ask again; after SIM 1 has been home and travels again, it asks once more. With "Ask every time" for the SIM, choose SIM 1 in the SIM dialog: the questions follow it.

### 28.3 Hear who's calling: ringtones made from a name, and the accessibility sweep

Unit tests: `CallerTuneTest` (core:common: the same name and variant give the same notes and samples; every tune lasts 3–5 s; the peak is exactly 85 % of full scale, never clipped, silent at both ends; notes stay in the pentatonic scale and end on the home note; variants and people sound different; the WAV header; file names never carry the name), `CallAnnouncementsTest` (what TalkBack says on each change), `ThemeContrastTest` (dynamic colour by tone, for any wallpaper) and `ThemeContrastTokensTest` (core:ui: the brand light, dark and AMOLED schemes, AMOLED under dynamic colour, the call colours). See [ACCESSIBILITY.md](ACCESSIBILITY.md) for the full checklist.

**Ringtone made from a name (I17).**

1. Open a device contact "Ana" › Settings: **Make a ringtone for Ana** (with a one-time tip under it the first time). Tap it: "Ringtone for Ana", **Play** plays a 3–5 s tune at ring volume and turns into **Stop** while playing (and back to Play when it ends). **Try another** plays the next one at once and the line above says "Tune 2", "Tune 3"… (TalkBack reads it). Close and open again: Tune 1 is the same tune as before; so is Tune 2.
2. **Use this tune**: "Ringtone set for Ana"; the Ringtone row says "Tune made for Ana". Call the phone from Ana's number with Parley as the default phone app, and again with another phone app as the default: the tune rings both times, and loops with a short pause. Reboot the phone and call again: it still rings (Telecom reads Parley's file as the system; System UI's grant is renewed when Parley starts).
3. Open the contact in Google Contacts (or the system Contacts app): its ringtone shows a file name like `parley-tune-1a2b3c4d.wav`, never Ana's name.
4. A **private contact**: the same row and dialog; the call rings with the tune through Parley's own ringer, also while the phone is locked.
5. A **label** page (e.g. "Family"): **Make a ringtone for Family** under the ringtone row; after **Use this tune** the ringtone row says "Tune made for Family" and a member without a ringtone of their own rings with it. **Reset** goes back to the default.
6. A name with no Latin letters ("李小龙", "محمد"), digits only ("0800 123") and an empty name still give a tune. Rotate the phone with the dialog open: the same tune number stays.
7. No new permission: Settings › Apps › Parley › Permissions lists the same ones as before.

**Accessibility sweep (P16).** TalkBack, Switch Access and Voice Access on; Settings › Display › Font size and Display size at the largest (200 %).

8. **Incoming call, slide to answer:** with Voice Access, say "tap Answer": the call is answered; "tap Decline" declines. Switch Access scanning reaches Decline, the track and Answer, each with a name. With TalkBack, the green and red ends are "Answer" and "Decline" buttons, and the track still has the Answer and Decline actions. Touching an end with a finger does nothing (only a slide does).
9. **Incoming call, tap to answer** and simple mode: "tap Answer" / "tap Decline" work the same.
10. **Call announcements:** with TalkBack on and nothing focused on the status, answer a call: TalkBack says "Call connected"; Hold: "On hold"; Resume: "Call resumed"; the other side hangs up: "Call ended" (or the reason, e.g. "Call dropped"). Placing a call: "Call connected" when they answer. The timer is never read out by itself.
11. **Headings:** TalkBack's heading navigation (swipe up then down, or the reading control set to Headings) jumps between the sections of Blocking & screening, Settings, a contact's page, Recents' days and Contacts' letters.
12. **200 % font and largest display size:** Recents, Contacts, Favourites, Keypad, a contact's page, Settings, Blocking & screening, the call screen (incoming and in a call) and the "Return to call" bar: nothing is cut off in the middle of a word without a way to read it (lists and pages scroll; the "Return to call" bar wraps to two lines; the caller scrolls on the call screen while the controls stay put).
13. **Contrast:** in light, dark and dark with Pure black, with dynamic colour on and off, and with a few very different wallpapers (bright yellow, deep blue, grey, a photo), secondary text, text buttons and error text stay readable on every card and sheet.

### 28.4 Menu memory and call reasons

Needs a number with a phone menu ("press 1 for…"), such as a bank, an airline or your carrier's service line, saved as a contact or not.

1. **Remembered.** Call the menu, open the keypad once connected and choose 2, wait for the next prompt, then 1 and 4. Hang up and call again: the keypad's top row says "Last time: 2 › 1 › 4" with **Replay**, and the first time the tip "Parley remembers the keys you press in phone menus…" above it (closing it keeps it closed).
2. **Replay.** Tap Replay right after the call connects: the row says "Sending 2 › 1 › 4" with a progress bar and **Stop**; the first digit waits as long as last time after the connect, the others as long as last time between them (at least 0.4 s); the digits appear in the keypad's line of tones sent. Stop (or any key pressed by hand) stops it at once. Hold the call during a replay: no further digit is sent. TalkBack reads "Send 2 › 1 › 4 again" for Replay and announces the row's changes.
3. **PINs and card numbers are never kept.** Choose 3, #, then type a 4-digit PIN: next call shows only "Last time: 3 › #". Nothing from a run of 4 or more digits on is kept, however slowly it is typed, and at most 6 keys are kept (see 28.5).
4. **Only calls you placed.** An incoming call where you press digits leaves nothing; a conference, a hidden number, a service code (`*100#`) and an emergency number never show the row or keep anything.
5. **Don't remember.** ⋮ › "Don't remember digits for this number" › Don't remember: the row goes; on the next call to that number the row never comes back, whatever you press. A shortcut already saved for it stays.
6. **Save as shortcut.** ⋮ › "Save as shortcut…": the dialog suggests "Bank › 2 › 1 › 4" (the contact's name, or the number), says what it dials; Save: the row says "Saved as …". The same number and digits saved again keep one shortcut (renamed).
7. **Contact page and number history.** The contact page shows **Shortcuts** under the header with the shortcut ("+44 20 …, then 2 › 1 › 4"); a number that isn't a contact shows it in its number history (Recents › tap the number). Tap it: the usual call path (dial guard, SIM question, "Confirm before calling") runs, the call goes to `number,,2,1,4` and Android sends the digits after the pauses once connected. ⋮ › Rename… changes the name (and a pinned home-screen copy); ⋮ › Add to home screen pins it (the launcher asks); ⋮ › Delete asks first, then the home-screen copy stops working ("Shortcut deleted").
8. **Private contacts.** With a private contact's number: the row and shortcuts work the same; the shortcut shows on the private contact's page once unlocked, and nothing about it is in a backup (Backup & restore › Back up now, restore on a test phone: shortcuts for normal contacts come back, private ones don't).
9. **Backup.** Back up, clear Parley's data, restore with Settings: shortcuts and "Don't remember" choices come back; remembered digits stay on the phone they were typed on (not in backups).
10. **Call with a reason, network that supports it.** On a SIM whose network carries call subjects (some carriers with RCS Call Composer or IMS subjects): type a number on the keypad, press and hold the green Call pill (or a SIM's segment): "Call with a reason" opens; type "Your parcel", tap **Call**: the call goes on the chosen SIM, and the other phone shows the subject while it rings (Parley on the other side shows it in quotes under the name). The keypad's one-time tip "Press and hold Call to say why you're calling" goes once the long-press is found.
11. **Text first.** On a SIM without call subjects (most): the sheet says the network can't send a reason; **Text first** opens the messaging app with "Calling you about Your parcel" for that number; send it (or not), come back to Parley: "Call Ana now?" with **Call** (no second "Confirm before calling") and **Not now**. There, **Call** calls without a reason. With both SIMs and no remembered SIM, the subject is only offered when both SIMs carry it.
12. **Contact page.** Press and hold a contact's **Call** tile: the same sheet, for their primary number (not when Call is set to another app). TalkBack offers "Call with a reason…" as the tile's long-press action. Emergency numbers (112, 911) skip the sheet and call at once.

### 28.5 After the review

Each step names the finding it checks.

1. **H1 PINs never kept, at any speed.** Call a menu, choose 2, then type a 4-digit PIN slowly (several seconds between digits, or with TalkBack: explore, then double-tap) and without #: next call the row says "Last time: 2", nothing more. Type `1#`, `2#`, `5#`, `7#` (a passcode's digits, one per prompt): nothing is kept. Choose 1 and 2 and then a passcode's 3 digits: nothing is kept (5 digits in a row). At most 6 keys are ever kept.
2. **H1 lock screen.** With a remembered path, lock the phone and call the number from the lock screen (or let the call screen show over the lock screen): the keypad's row says "Menu keys remembered" with Replay, no digits; TalkBack reads "Send the remembered menu keys again"; ⋮ has no "Save as shortcut…". Unlock: "Last time: 2 › 1" again.
3. **H1 switch.** Settings › Calls › During calls › **Phone menus** (says "Remembering menu keys") › turn off "Remember menu keys": remembered paths are forgotten at once, the row never shows and nothing new is kept; saved shortcuts still work. Search Settings for "menu keys" or "ivr": it finds both. The tip now says no run of 4 or more digits is kept and where to turn it off.
4. **H1 backup and upgrade.** Back up and restore: shortcuts and "Don't remember" choices come back, remembered paths don't (they stay on the phone they were typed on). A path kept by 4.7 that holds 4 or more digits in a row is gone after updating (the row doesn't show it).
5. **M1 RTT mode change.** In an RTT call, switch to HCO and back (and have the other side change mode): every character the other side types still appears, none twice.
6. **M2 media-only car.** Mark the car, turn off "Phone calls" for it in Android's Bluetooth settings (media stays on), turn on "Answer favourites": a favourite's call rings and is announced but is not answered automatically. Turn "Phone calls" back on: it is answered after the countdown.
7. **M3 work profile.** On a phone with a work profile: a starred personal contact calls while the car is connected with "Answer favourites" on: answered after the countdown.
8. **M4 emergency.** Type 112 (or 911) and press and hold Call: nothing special happens (no sheet, no delay); a contact whose number is 112 likewise. A normal number still opens "Call with a reason".
9. **L1 car without Nearby devices.** Mark the car, then deny "Nearby devices" in App info: the car still counts as connected (Android 14+ shows only the last two bytes of its address, plus its name). A car named "Car Multimedia" or "MY CAR" shows "Nearby devices needed" on the drive profile while the permission is off.
10. **L2 Priority.** Do Not Disturb on, Priority with starred contacts allowed, car connected: a favourite's call rings and its name is said; someone else's isn't (and doesn't ring).
11. **L3 merge during replay.** Start Replay, then merge the call into a conference (or answer a waiting call): the replay stops at once and no further key is sent.
12. **L4 numbers saved with the country code but no "+".** Abroad with a US SIM, call a contact saved as `1 201 555 0123`: "Call +1 201-555-0123?" is offered. With a Russian SIM, `7 912 345 67 89` and `8 912 345 67 89` are offered as +7 912 ….
13. **L5 warnings for the converted number.** Abroad, call a number whose international form is a premium or listed number: after **Call** in the abroad question, the dial guard's warning for that number shows (not the typed number's).
14. **L6 private names.** Save a menu shortcut during a private contact's call: the suggested name starts with the number, not the name. A private contact's number history shows no Shortcuts block (they are on its own page).
15. **L7 rename keeps the photo.** Pin a contact's menu shortcut to the home screen, then rename it on the contact page: the home-screen icon keeps the contact's photo.
16. **L8 tunes.** Make a ringtone from a name for a contact, then pick another ringtone (or another tune): Parley's tune file for the old one is deleted once nothing uses it (Files by Google can't see it; check with `adb shell run-as` on a debug build: `files/tunes` holds only tunes in use), and the old file loses System UI's read grant.
17. **L9 diary.** Call insights › Quality opens without a wait on a phone with a long, busy call history.

## 29. Household (5.0)

### 29.1 Shared family phonebook

Two phones (A: Ana, B: Sam), each with Parley as the phone app, and a folder app (Syncthing, or Nextcloud with
folder sync) sharing one empty folder `Family` between them. Neither phone needs "Sync between your phones".

1. **Share.** On A: Contacts › Labels › make a label "Family" with three contacts (Ada, Grace, Dr Lee) and one private
   contact. Open it › ⋮ › **Share this label…**: the screen says one private contact stays on this phone. Choose the
   `Family` folder, type a passphrase (the meter must reach Strong; "Share" stays off until both fields match), your
   name "Ana", **Share**: "Family is shared. Invite people next." opens *Sharing Family*. The folder holds
   `.parley-label`, three `c-….plabel` files and one `j-….plabel`; none of them shows a name or number in a text editor.
2. **Same folder as the phones' sync refused.** With "Sync between your phones" set to a folder, share another label
   into that same folder: "That's the folder of Sync between your phones…". Share into a folder that already holds a
   label: "That folder already holds a shared label…".
3. **Invite by QR code.** On A: *Sharing Family* › **Invite with a QR code**: a code and an 8-character code under it.
   On B, take a photo or screenshot of it, Tools › Scan QR code › that picture: "Shared label invitation" › Open in
   Parley. Type the code: "Ana invited you to Family", Ana's key (compare with A's My card › Shared with: same
   fingerprint) and "Their folder: Family". A wrong code says "That doesn't open this invitation".
4. **Join.** On B: **Choose the folder**. Pick a folder that isn't it: "That folder doesn't hold this label…". Pick
   `Family`: Members lists Ana with her fingerprint and "Shared the label". Type "Sam" › **Join**: "You joined Family";
   B has a "Family" label (made in the default account) with Ada, Grace and Dr Lee; the private contact isn't there.
   A contact B already had with Dr Lee's number joins the label instead of being added twice.
5. **Members.** On A, open the label: the **Shared** part (with its one-time tip) lists "Ana, Sam"; Members &
   invitations shows Sam, "Invited by You", with Sam's key.
6. **Edits both ways.** On B change Ada's number; on A, add a note to Ada. Wait for the sync app (or tap **Sync now**
   on both label pages, B first): both phones show the new number and the note. A's label page says "Sam changed
   Ada's number · …", B's says "Ana changed Ada's notes · …". The chips filter the list by member.
7. **Changed on two phones.** Both change Dr Lee's number to different values, sync A then B: B's label page shows
   "1 contact changed on two phones" › Choose: "Number: Ana's / Yours" with both values. Until you choose, B keeps its
   value and A keeps Ana's. Choose Ana's › Keep these: B shows Ana's number; History & undo on B has the previous
   version. Sync A: unchanged.
8. **Delete.** On A delete Grace. Sync A, then B: Grace is gone from B, and B's History & undo › Contacts lists her
   (restore works; she is then shared again as a new contact). A contact B had before joining is only taken out of
   the label, never deleted.
9. **Many at once.** On A delete 4 of 5 contacts in the label: A's label page says "Waiting: 4 contacts would be
   deleted at once" › Apply. Same on B.
10. **A vanished file.** Delete one `c-….plabel` file from the folder by hand: the next sync writes it again; no
    contact disappears anywhere.
11. **Private contacts refused.** On A, select the private contact in Contacts › Add to label › Family: "1 private
    contact wasn't added: private contacts aren't shared…".
12. **Invite by file.** *Sharing Family* › **Invite with a file**: a wrong passphrase says "That isn't this label's
    passphrase"; the right one saves `Family.parleyinvite`. On a third phone (or B after leaving), Settings › search
    "join" › **Join a shared label** › Join from an invitation file: the label's passphrase opens it.
13. **Remove a member.** On A: Members › Sam › Remove: the dialog says Sam keeps what they have; type a new strong
    passphrase twice › Remove: "Sam was removed. Invite the others again." On B, Sync now: "This label's key was
    changed. Ask a member for a new invitation. Your contacts stay." Edits on A no longer reach B.
14. **Coming back.** Remove someone else instead (a third member) while Sam stays: Sam's row says "Needs a new
    invitation from you"; a new QR invitation brings B back with its contacts and pending edits, nothing duplicated.
15. **Leave.** On B: *Sharing Family* › Leave this label: the contacts stay in B's label; A lists Sam no more.
16. **Rename and delete the label.** Rename "Family" on A: it stays shared (B keeps "Family"). Delete the label on
    A: its page is gone and Settings › Shared labels shows "This label isn't on this phone any more"; nothing is
    deleted on B.
17. **Background.** Leave both phones alone for an hour with the sync app running: changes arrive without opening
    Parley. Settings › search "family phonebook" opens Shared labels.

### 29.2 Duress unlock

Unit tests: `AppPinTest`, `DuressTest` (core:common: PIN rules, hashes that tell the two PINs apart in one derivation, the stored record, the backoff and its restart rule, the session state machine, what is hidden, the in-memory settings overlay, search); `DuressUnlockTest` (core:data, Robolectric: sealed hashes, waits, a duress unlock hiding notes, promises, safe words and private details with real stores, a session's changes forgotten at the lock, hiding across a restart, everything back after the real PIN with nothing lost). Threat model: [SECURITY_MODEL.md](SECURITY_MODEL.md#duress-unlock).

Set up: the app lock on, a few private contacts (one with its own ringtone, one temporary), a private call, a Circle note with a promise (`[ ] …`), a note for calls on a device contact, a call note, a family safe word on a label, and someone in My card › Shared with.

1. **Parley PIN.** Settings › Privacy & security › App lock › Unlock with: turn on **Parley PIN** (the fingerprint or screen lock asks first), type a PIN twice; 3 digits or two different PINs are refused. Lock now: the lock screen shows a PIN field (no system prompt), the Emergency call button and **Use fingerprint or screen lock**. Both unlock. TalkBack reads "Parley PIN" and the error after a wrong PIN.
2. **Waits.** Type five wrong PINs: the fifth says "Too many tries. Try again in 0:30" and the field is disabled; the countdown runs; a sixth wrong one waits 1:00. Change the phone's clock forward an hour: the wait doesn't shorten. Reboot during a wait: it starts over in full.
3. **Duress PIN.** With the Parley PIN on, **Duress PIN** shows the explanation (what it hides, that only a PIN unlocks while it is set, that a forgotten Parley PIN can't be recovered, what it can't do) before asking. Its own Parley PIN is refused as the duress PIN, and the other way round in Change Parley PIN. The lock screen then has no **Use fingerprint or screen lock**, and the Quick Settings "Private contacts" tile or a number shared from another app can't open Parley with the fingerprint (the sheet closes).
4. **A duress unlock looks normal.** Lock, then type the duress PIN: Parley opens exactly as with the real one (same wait, no banner). Check that none of these show anything: private contacts in Contacts, Favourites, the keypad's results and Circle; the Private chip; private calls in Recents; Temporary contacts' private one; History & undo › Contacts' deleted private contacts; Circle notes and promises (moments show without notes); a device contact's note for calls and its call notes; the label's safe word (Settings › Privacy & security › Family safe word lists none, and setting one fails); My card › Shared with ("No one yet"); the Privacy dashboard's counts (0); Settings search for "duress" (nothing). A private contact's old notification or link opens "contact not found".
5. **Settings stay plausible.** Settings › Privacy & security: Hide private contacts and Private call history show what you had them at. Unlock with shows Parley PIN on, Change Parley PIN, and no duress rows. Turn off the app lock and Hide private contacts, turn off the Parley PIN, change the theme: the screens show it, private contacts stay hidden. Lock now (turn the app lock back on first if you turned it off) and unlock with the duress PIN again: the app lock and switches are back as stored, the theme change stayed.
6. **A "new PIN" in a session.** In a duress session, Change Parley PIN to a new one. Lock: the new PIN opens a duress session, the old duress PIN no longer works, the real Parley PIN still opens everything.
7. **Calls during the hiding.** While hidden (also after locking Parley and after a reboot), call from a private contact: the call screen and the missed-call notification show only the number, with the default ringtone (not its own); a private contact set to "Send to voicemail" still goes to voicemail. Call an emergency number from the lock screen's Emergency call button: it goes through.
8. **Back to normal.** Unlock with the real Parley PIN: everything is back, with every note, promise, safe word and Shared with entry as before (also after editing a contact's settings or a logged moment during the duress session).
9. **Backups wait.** While hidden, a scheduled backup doesn't run (no new file in the backup folder) and "Back up now" makes one without private contacts and without saying so. After the real PIN, the next backup has everything.
10. **Keep private details locked.** With it on, after the duress PIN, private details don't open even if you use the phone's fingerprint (no private page is reachable anyway; check with an old shortcut or adb `am start` link). With it off, the hiding is the same.
11. **Turning it off.** Unlock with the real PIN, turn off the duress PIN: the fingerprint or screen lock is offered again. Turn off the Parley PIN: the app lock asks for the fingerprint or screen lock as before.

### 29.3 After the review

The 5.0 security review of shared labels and the duress unlock (3 High, 9 Medium, 8 Low). Unit tests: `SharedLabelRulesTest` (an unreadable file's grace period, versions far ahead, saturating versions), `SharedLabelMembershipTest` (invitations expire and let in one member, a 78-bit QR code under scrypt, the signed key change, joined labels' names), `NumberMemoryTest` (note hints dropped while hiding), `DuressTest` (only a PIN with a Parley PIN set, search the same in a session, PIN changes counted), `RetentionDeciderTest` (no rotation while hiding); Robolectric: `SharedLabelSyncTest` (a phone-only card matching a contact outside the label, a key change with files planted during and after it, a leaver's files on a provider without stamps, a swapped and a deleted header, junk and a maximal version, a file arriving after the listing, joining next to a label of the same name) and `DuressUnlockTest` ("Change PIN" in a session, the session's Settings, a wrong-try count that can't be stored, an unreadable PIN record, a hiding that can't be stored, notes and safe words written while hiding, notifications cleared, number-memory note hints, the stored discreet switch and the private-name providers). This changes 29.2 steps 1, 3, 4, 5 and 11: with a Parley PIN only a PIN opens Parley, and a session's Settings show the duress PIN off.

Set up: two phones (A, B) sharing "Family" through a Syncthing folder as in 29.1, and on A a Parley PIN, a duress PIN and the notes from 29.2.

1. **A card never pulls in an outside contact.** On A, keep a contact "Shelter" (+1 555 0177, a note) outside Family. On B, add a contact to Family with only the number +1 555 0177 and no name. After both sync: on A, Shelter is not in Family; Family has a new contact with just that number; on B nothing of Shelter (name, note) arrives.
2. **Joining never merges by name.** On a third phone with its own "Family" label (a few contacts), join A's invitation: the screen says "It joins as a new label: Family (shared)"; after joining, your own Family is untouched and not shared (B never sees its contacts). Join again with another invitation, tap **Use a label you already have**, pick a label: the confirmation says how many of its contacts will be shared; Cancel keeps "new label". Try renaming "Family (shared)" to "Family": refused with "There's already a label called Family…".
3. **Invitations.** A QR invitation's code has 16 letters (four groups). An invitation opened more than 7 days after it was made says it doesn't open (ask for a new one). Opening the same invitation on two phones: only the first is listed as a member on A (after both sync, neither when both arrive at once).
4. **Removing a member while files are planted.** On A, remove B. While A's removal runs (or right after, with B's sync app still running), edit a contact on B and delete another: after A syncs again, A's contacts are unchanged and A's members list shows only the members who stay. B says "This label's key was changed".
5. **A swapped header.** With a file manager, replace `.parley-label` in the folder with a copy of another label's header: A keeps syncing, and the label page shows "The folder's label file was changed without a member's signature…" quietly under the status; the next sync puts the right file back. A real key change (step 4) still shows "This label's key was changed" on the removed phone.
6. **Overwritten or junk files.** Overwrite one `c-….plabel` file with random bytes: A shows "Some files in the folder couldn't be read…", and within about an hour writes its copy back (the contact syncs again, edits included). Add a hundred junk `c-….plabel` files: syncs stay quick.
7. **A member who leaves.** B edits a contact, then leaves the label: on A the contact keeps B's edit and later edits from A still reach a new member who joins afterwards (also with a folder app that shows no file dates).
8. **The lock screen with a Parley PIN.** Turn on the Parley PIN without a duress PIN: the lock screen shows only the PIN field and Emergency call (no "Use fingerprint or screen lock"); the same with a duress PIN. Choosing a PIN says that only the PIN opens Parley.
9. **A duress session's Settings.** Unlock with the duress PIN. Settings › Privacy & security › App lock › Unlock with shows the "If someone makes you unlock" group with Duress PIN "Off", as on a phone without one; Settings search for "duress" finds it. Set a duress PIN there: it shows "On" until the next lock, and doesn't open Parley afterwards.
10. **"Change PIN" in a session.** In a duress session, Change Parley PIN to your real PIN: it says nothing different (the dialog closes as for any PIN); lock: the real PIN still opens everything, the duress PIN still opens a session. Change the PIN five more times: the next try says "The PIN was changed a few times just now. Try again in …", for any PIN typed; a duress unlock doesn't clear that, the real PIN does.
11. **The Quick Settings tile.** With a Parley PIN set and discreet mode on, lock Parley and tap the "Private contacts" tile: Parley opens on its PIN screen instead of asking for the screen lock, and discreet mode stays on until you turn it off inside Parley. After a duress unlock, the tile (or the switch in a session) never changes the stored switch: after the real PIN, discreet mode is as you left it.
12. **Writing during a session.** In a duress session add a note for calls on a contact without one, a call note, a Circle moment with a note, and a safe word on a label without one: each shows as saved, also after locking and unlocking with the duress PIN again. Type a note for calls on a contact whose note is hidden: it shows. After the real PIN, the new notes and safe word are there and the hidden note is as it was.
13. **Notifications and number memory.** Before a duress unlock, have a missed call from a private contact and a To call reminder in the shade: after the duress unlock they are gone (a call in progress stays). On the keypad, type a number mentioned in a hidden note: no "Your note: …" line while hidden; it is back after the real PIN.
14. **Backups and failures.** In a duress session, tap Back up now several times: older backups in the folder stay. Approved name-lookup apps (an SMS app) asking for a private number while Parley's process was stopped get no name during the hiding or with discreet mode on.

## 30. Correctness (5.0.1)

### 30.1 Contacts

1. **Android 16 with a cloud default account.** On an Android 16 phone signed in to Google, set the phone's default account for new contacts to the Google account (Settings › Google › Contacts, or Contacts › Settings › Default account). In Parley: Settings › Contacts › "Save new contacts to" lists the Google account first, has no "Phone only" entry and says "Android puts new contacts in Google · …". New contact: the Save-to menu has no Device entry and explains why under the accounts; Save works, and the contact appears in Google Contacts.
2. **Every way in.** With that default still set, each of these saves without an error and lands in the Google account: import a `.vcf` (Settings › Contacts › Import), import from the SIM, restore a backup, restore a contact from History & undo and from Snapshots, Make visible on a private contact (the message names the account), make a temporary contact that isn't private, Add several numbers, Paste details, accept a card from a QR handshake, and a contact pulled in by a shared label.
3. **Phone as default.** Set the phone's default back to "Phone" (or "Don't set"): Device is offered again, new contacts go to the phone, and nothing mentions the default account. On Android 15 and earlier nothing changes either.
4. **Department kept.** In Google Contacts (web or app), give a contact a department and nothing in company or title; in Outlook or another app, give one an office location. In Parley: the contact page shows the department under the name and "Office" under Other fields. Edit the contact's nickname and save: Google Contacts still shows the department and office.
5. **Department edited.** In Parley's editor, the Work group shows Company, Title and Department. Change the department and save: Google Contacts shows the new one. Clear company and title on a contact with a department and save: the department stays. Make that contact private and visible again: the department and office come back.
6. **Edit from another app.** From an app that offers "Edit contact" (a launcher's contact widget, a messaging app, Google's contact card with Parley as the only contacts app, or `adb shell am start -a android.intent.action.EDIT -d content://com.android.contacts/contacts/<id>`), Parley opens its editor on that contact. A `raw_contacts/<id>` link opens the editor of that copy. With the app lock on, the lock screen comes first and the editor opens after unlocking. A link to a deleted contact says "This contact isn't on the phone any more".

### 30.2 Calls, Recents and privacy

1. **The Recents legend.** Recents ⋮ › What do the colours mean? has three groups: **Calls** (every call badge, with its shape in words), **Filters** (All, Missed, Incoming, Outgoing, Unknown, Contacts, Blocked and Voicemail with the same icons as the Rich chips, then Filter and Saved filter) and **On a row** (count, dots, bar, Call back, video call, private contact, the screening line). The footer points to Settings › Recents & history › Recents style. TalkBack reads each group name as a heading. Large font and landscape: the dialog scrolls.
2. **Video calls answered as voice.** From another phone, place a video call (ViLTE, or a carrier's video calling) to this one. While it rings the call screen shows "Video call · you'll answer with voice"; once answered, "Video call · answered as voice". TalkBack reads "Video call. Parley answers with voice only, and your camera stays off." No camera permission is asked for. A voice call shows neither.
3. **The video badge.** A call Android logged as video (place one with the stock phone app if needed) shows a small camera next to its badge in Recents (Rich and Simple), and "Video call" in the number's call history. TalkBack reads "Video call".
4. **Caller on the lock screen.** Settings › Privacy & security › Lock screen › Caller on the lock screen: *Name* (default) behaves as before. *Initials*: lock the phone and call it from a saved contact "Ada Lovelace": the full-screen call screen and the notification show "AL", with no photo, number, label or notes; unlock: the full name comes back on the call screen and, within a moment, in the notification. A number that isn't saved still shows its number. *Just "Incoming call"*: the call screen and the notification say only "Incoming call" ("Ongoing call" after answering); turn the screen off during the call and wake it: the lock screen's notification still hides the caller. An emergency call shows in full. A private contact with discreet mode on still shows no more than before, under every choice.
5. **Send my details uses My card.** On an upgraded phone that had "My details" filled in, open Contacts › My card: the name and number are there. In Message or call on…, tap **Send my details**: My card's name and first number fill in the message. **Edit name and number** changes the name and first number of My card (check Contacts › My card), and Introduce myself… uses them too. Settings › Messaging › My card shows the same.
6. **Names.** Settings › Backup & sync shows "Daily snapshots" (searching "time machine" still finds it). A shared label's change list says "email", not "e-mail".
7. **Crash reports keep no messages.** With Settings › About › Keep crash reports on, make a debug build crash (for example through a test hook): the report Parley offers next time lists exception class names and code lines, and no exception message. A report kept from before the update loses its messages when shared too.
8. **No logs in release.** On a release build, `adb logcat --pid=$(adb shell pidof app.parley)` while placing calls, editing contacts and running a backup shows no lines from Parley's own tags.

## 31. Simpler (5.1)

### 30.3 Review fixes

1. **The lock-screen notification hides the number.** Caller on the lock screen *Initials*, phone locked, lock screen set to show all notification content: a call from the saved contact "Ada Lovelace" shows "AL" in the notification with only the SIM under it, never the number. *Just "Incoming call"*: neither name nor number, also for an unknown number. Answer the call: the ongoing notification stays the same. Unlock: name and number come back.
2. **Nothing else names the caller.** With *Just "Incoming call"* and the phone locked, call from a contact in a label that rings through during off hours, from a number with an "Allowed by" rule, from abroad, and from a contact with a call-time limit: the call screen shows no "Rang through" line, no "Allowed by …" tag, no local time and no "Limit for …" (the limit's time left still shows); a "Likely spam" warning still shows. Block & decline a contact's call: the card says "Calls from this number…" without the number. Speak caller's name stays quiet. Unlock: everything is back.
3. **Conference calls.** Merge two calls with saved contacts into a conference, set *Initials* and lock the phone: Manage conference lists "AL" and "GH" without numbers or photos; with *Just "Incoming call"* every participant reads "Ongoing call".
4. **A network caller name.** *Initials*, phone locked: an unsaved number that arrives with a caller name from the network shows that name and its number (nothing to shorten).
5. **Edit links name the right person.** Find a contact with two copies (for example Google and the phone) and note a raw contact id that differs from its contact id (`adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id:contact_id`). `adb shell am start -a android.intent.action.EDIT -d content://com.android.contacts/raw_contacts/<raw id>` opens the editor on that contact's copy, never on the contact whose id happens to equal the raw id. A link to a deleted raw contact says "This contact isn't on the phone any more".
6. **Make visible says where it goes.** Android 16 with a Google default: a private contact's Make visible question adds "On this phone, Android puts new contacts in ana@…. Any that were only on the phone go there and are synced to the cloud." (also in Contacts › select several › Make visible). A contact made private from the phone ends with "Visible to other apps now, in ana@…"; one made private from a work account goes back there and ends with the plain message. With the phone as default, neither sentence appears.
7. **Redirects are said everywhere.** Same Android 16 setup: import a `.vcf` into "Phone": the report says it went into the Google account. Restore a contact from History & undo or a backup that was on the phone, or reopen a half-finished new contact the editor kept: a snackbar says "Saved in ana@…. On this phone, Android puts new contacts there." once, not per contact.
8. **The legend in both styles.** Settings › Recents & history › Recents style › *Simple*, then Recents ⋮ › What do the colours mean?: Calls lists the arrow icons (in, out, missed, declined, blocked, voicemail, unknown), Filters shows text chips, and On a row explains "Name in red", "Number in brackets", the video mark, the lock and the screening line; no shapes, dots, bars or Call back. Switch to *Rich*: the shapes come back, with "Coloured edge" and "Bold name" among the row marks.
9. **Private contacts.** Open a private contact that has an office or a job description (for example one saved privately from a QR vCard with a ROLE): the page shows them under Other fields. Make it visible: the job description is in the address book. A video call with a private contact, or one only in Parley's full-history archive, keeps the camera mark in Recents.
10. **Send my details.** In Message or call on…, tap **Edit name and number** and clear the number: Send my details then sends the name only, even when the phone's own profile has a number.
11. **Diagnostics.** Settings › About › Export diagnostics after an error (for example a failed SIM write): "Recent errors" lists where and the kind of error, never its message.
12. **README.** The README says "email" and "Daily snapshots" throughout.

### 31.1 One hub and reminders

1. **One Tools.** Every tab's ⋮ has one **Tools** item, and Settings starts with one **Tools** row (no separate "What Parley can do"). Both open the same page, titled Tools, with a search box and seven jobs. Each job shows a few rows and "n more"; tap it: the rest slide in and it reads "Show fewer". Rotate: what you opened stays open. With TalkBack, each job name is read as a heading and "4 more" as a button.
2. **What Tools used to hold.** Without opening any "more": Choose who can ring (Blocking & screening), Expecting a call, Backup & restore, Undo a delete, edit or merge (History & undo), Import & export contacts (opens Settings › Contacts at Import), Coming from another phone?, Contact health check, All your reminders, Birthdays & dates, To call, Scan a contact's QR code, Temporary contacts, Privacy dashboard, Messaged numbers. Each opens its screen.
3. **Actions in place.** Turn on **Expecting a call**: the usual choice of how long appears; pick one and the row says it's on. Turn it off: unknown callers are screened again. With the app lock on, **Lock Parley now** (Keep it private) locks at once; with the app lock off, that row isn't there.
4. **Search.** Type "lock": Lock Parley now and App lock show (with the app lock on). Type "zzz": the empty state offers Clear search. While searching, every match shows, none behind "more".
5. **Privacy dashboard.** Settings › Privacy & security › Your data › Privacy dashboard opens it; so does Tools › Keep it private › Privacy dashboard. Settings search "privacy dashboard" finds it under Privacy & security.
6. **Old ways in.** Settings search "what parley can do" and "tools" find Tools. The What's new card's "See what's new" opens it with "New in …" on top.
7. **One Reminders page.** Settings search "reminders" opens Settings › Reminders: a short intro, then Missed calls (Remind me of missed calls), To call and follow-ups (To call, Anything to remember? after calls), Keep in touch (nudges, then how they arrive and, for one at a time, at most per week), Birthdays and dates (Birthday reminders with its time and how early), Backups (Remind me to back up) and Temporary contacts (Ask before deleting). Change each and leave: the old behaviour follows (for example a missed call re-alerts at the new interval; birthday reminders arrive at the new hour).
8. **Found by old words.** Search "re-alert", "birthday reminders", "reminder time", "keep in touch", "digest", "remind me to back up", "remind me" and "ask before deleting": each opens Reminders scrolled to its row and highlights it. Search "reminder time" with birthday reminders off: the page says it shows once Birthday reminders is on and highlights that switch.
9. **Links where you'd look.** Settings › Calls (Missed calls and voicemail), Contacts (Birthdays and reminders), Recents & history (Recents) and Backup & sync (Backups) each have a **Reminders** row that opens the page; none of them repeats the reminder switches any more. Contacts still has Log messages you start. Circle ⋮ › Circle settings opens Settings › Contacts at the Circle group with Log messages you start highlighted; its **Keep-in-touch reminders** row opens Reminders at "How keep-in-touch reminders arrive".
10. **One channel group.** Android Settings › Apps › Parley › Notifications: a **Reminders** group holds "Birthdays, keep in touch & follow-ups", "To call" and "Backup reminders". On a phone updated from 5.0 where you had changed one of them (say, silenced To call), it keeps that choice after the update and the first start. Missed calls, Contacts housekeeping and **Backups** (failed scheduled backups) stay outside the group. Turning the whole group off silences those reminders and nothing else: a scheduled backup that fails still notifies (see 31.4). Reminders › Reminder notifications opens this screen.

### 31.2 Settings and names
1. **Calls is a short list.** Settings › Calls shows the default phone app, then Answering, During calls, SIMs & carrier and Situations, then Missed calls and voicemail (a **Reminders** row, where Remind me of missed calls now lives, and Voicemail) and Before you call (Confirm before calling, Ask before pocket calls). Open each page: Answering has the answer gesture, the unknown-caller ringtone (pick one: its name shows), call screen background, contact photo, Answer automatically, Vibration for callers and Answer with RTT with Android's TTY and RTT link; During calls has the four vibration and screen rows and Remember what matters; SIMs & carrier opens SIMs & plan minutes, SIM & calling accounts and the carrier's call settings; Situations has Helpers, Drive profile, Phone menus and Reminders & limits, each opening its screen. Every switch keeps the value it had before the update.
2. **Search finds the old words.** In Settings search try "power button", "proximity", "auto answer", "RTT", "unknown ringtone", "wifi calling", "call forwarding", "memory", "helper", "drive", "phone menu" and "plan minutes": each result opens the page it is on, scrolled to the row with it highlighted (screens of their own, such as Helpers or Phone menus, open directly). Tools rows for these settings open the same places.
3. **Sort by and Show names as.** Settings › Appearance › Names has **Sort by** (First name / Last name) and **Show names as** (First name first / Last name first). On a phone updated from 5.0 with "Sort and show names by: Last name", both read Last name; with First name, both read First name. Set Sort by: Last name and Show names as: First name first: Contacts lists "Bob Adams" under **A**, before "Ann Young" under **Y**; the letter headers and the A–Z rail show A, …, Y; a private "Dan Brown" is listed under **B** (not D), between Adams and Young. Then Sort by: First name and Show names as: Last name first: the list keeps first-name order and reads "Young, Ann", "Adams, Bob"; the headers follow the first names. Recents, keypad search results, Favourites (A–Z) and an incoming call from a saved contact show "Adams, Bob" too. With Prefer nicknames on, a nickname is shown and sorted by itself. Searching "sort and show names by" in Settings finds both settings.
4. **Who's in… is a city chip.** The ⋮ menus of Contacts, Circle and Favourites no longer have "Who's in…" (Tools › Stay in touch has it, see 31.4). Above Contacts, the chip row ends with **Who's in…**: it opens the city screen on the last city. Type "Lyon" in the Contacts search: a chip **People in Lyon** appears after "All" and opens the city screen on Lyon. Typing only digits shows no city chip. An old link to the city screen still opens it.

### 31.3 People

1. **Frequent lives in Favourites.** Favourites tab: favourites first, then a "Frequent" heading with the people you call most who aren't starred. Settings › Appearance › Layout › Favourites in Contacts › Section at top (or Avatar strip): the switch now reads "Show Frequent"; turn it on and the Frequent row appears under the favourites at the top of Contacts. "Frequent" appears nowhere else (no menu, tab or setting of its own). The Favourites explainer, shown once the first time there are favourites, mentions Frequent under them.
2. **To call lives in Recents.** Choose Remind me on a missed call (notification), on the call screen's Decline & remind and on the post-call card: each confirmation says the reminder is "under To call in Recents". Recents shows the To call strip at the top; tapping it opens the To call list. The reminder notification opens the same list. There is no To call item in any ⋮ menu; Tools' To call row opens the same list.
3. **An unused Circle stays out of the way.** On a fresh install (Circle tab hidden, nobody in the Circle) with plenty of call history: the Favourites tab and Favourites in Contacts show no Circle section and no "Suggested for your Circle" list, only favourites and Frequent. Add someone to the Circle from their contact page (Add to your Circle): the "Your Circle" section appears at the top of Favourites, with "Suggested for your Circle" under it until you tap Hide suggestions. Show the Circle tab: with an empty Circle it still offers its suggestions there, and its how-to line names "Add to your Circle".
4. **One sentence each.** The explainers read: Favourites (starred, other apps see the star; Frequent under them), Circle (keep in touch; unlike Favourites it doesn't star anyone, and it stays empty until you add someone), Labels (groups you make yourself) and To call (calls you said you'd make and missed calls you haven't returned). TalkBack reads "Frequent" and "Your Circle" as headings.



### 31.4 Review fixes

1. **Failed backups aren't reminders.** Android Settings › Apps › Parley › Notifications: turn the **Reminders** group off. Make a scheduled backup fail (Backup & restore › Schedule: Daily, then delete or rename the backup folder and wait for the next run): the "Parley backup" notice still arrives, from the **Backups** channel outside the group. An overdue backup's monthly reminder comes from **Backup reminders** inside the group and stays silent while the group is off. On a phone updated from 5.0 where Backups was turned off, Backup reminders starts off too.
2. **Private contacts follow both name settings.** Make a private contact "Dan Brown" and one "Zoe Mary Zabel". Sort by: Last name, Show names as: First name first: "Dan Brown" is under **B** (after Bob Adams), "Zoe Mary Zabel" under **Z**, and the A–Z rail has B and Z. Show names as: Last name first: they read "Brown, Dan" and "Zabel, Zoe Mary" in Contacts, Favourites, Contacts search and keypad search, and an incoming call from Dan's number shows "Brown, Dan" (also on the lock screen with discreet mode off; with discreet mode on, the number only, as before). A private contact saved with name parts (Given "Ludwig", Family "van Beethoven") reads "van Beethoven, Ludwig".
3. **Restoring a 5.0 backup keeps how names read.** On 5.0 set "Sort and show names by: Last name" and make a backup. On a fresh 5.1 install, set Show names as: First name first, then restore that backup (Backup › Move to a new phone or Restore): Sort by reads Last name and Show names as Last name first, and the list reads "Young, Ann". A backup made on 5.1 after choosing Show names as brings that choice back as it was.
4. **Who's in… has a way in without Contacts.** Hide the Contacts tab (Settings › Layout & gestures › Navigation bar). ⋮ › Tools › Stay in touch › "n more" › **Who's in…** opens the city screen. Tools search "trip" or "travel" shows that row; Settings search "who's in" or "trip" finds **Tools**.
5. **"n more" with TalkBack.** On Tools, focus a job's "4 more": TalkBack reads "4 more, collapsed, double-tap to show 4 more in Stop spam" (with that job's name). Open it: "Show fewer, expanded", and the action names the job. The search box reads "Search Tools".
6. **Old Calls links land on the row.** Settings search "proximity" opens Calls › During calls with "Turn the screen off at your ear" highlighted. A link to Settings › Calls with a moved row (a back stack Android restores after Parley was stopped in the background, from before the update) opens the page that holds the row now, with it highlighted, not the short Calls page.
7. **Circle settings.** Circle ⋮ › Circle settings opens Settings › Contacts at the **Circle: keeping in touch** group with Log messages you start highlighted; its Keep-in-touch reminders row opens Reminders at "How keep-in-touch reminders arrive".
8. **Wording.** Settings › Reminders' intro says most reminders share the group Reminders and that missed calls, backup problems and temporary contacts keep their own channels. The Reminders link rows on Contacts, Recents & history, Calls and Backup & sync read "Every reminder in one place".

## 32. Polish (5.2)

### 32.1 Call screen
1. **Ringing frame.** Call the phone from a saved contact with a photo: a scalloped frame in the theme's colour shows around the photo and turns slowly (one turn in about 24 s). Answer: the frame fades away and nothing in the header moves. Repeat with a contact without a photo (the monogram) and an unknown number. Settings › Accessibility › Remove animations on: the frame shows but stands still. Landscape and an unfolded foldable (two panes): the frame shows around the photo on the start side. Light, dark and AMOLED: the frame is visible but quiet.
2. **Weights and digits.** On the call screen the caller's name reads a little heavier than before at the same size; with the keypad open the compact name too, and the call-waiting sheet's name. Watch the timer in the status pill go from 0:09 to 0:10 and 0:19 to 0:20: the pill doesn't change width. The same for "12:31 left" under it (set a call limit), the auto-answer countdown, the "On hold · 02:10" strip, hold mode's big time and the picture-in-picture time. Type "1111" then "8888" on the main keypad and in a call: the digits take the same width. The home titles (Contacts, Recents, Favourites) read slightly heavier; other screens' top bars are unchanged.
3. **Poster.** Settings › Calls › Answering › Call screen background › **Poster**. Give a contact a call-screen picture and have them call: the picture fills the screen, clear at the top, with the name large above the answer controls; the name, number line, tags and status stay readable (4.5:1) over a white and a black picture in light and dark themes. Tap the name: the contact opens after the call (as the photo did). Answer: the layout stays a poster above the grid. Open the keypad: the picture dims to the classic scrim and the compact header shows; close it: the poster returns.
4. **Poster falls back to classic.** With Poster on: a caller with no call-screen picture shows the classic screen with the caller's colour; rotate to landscape (or use a tablet): the classic two-pane layout over the picture; a second call waiting: the call-waiting layout; a likely-spam call: the red warning, no poster; the picture-in-picture window: no picture. Turn "Show contact photo on the call screen" off (or set the contact's own Photo on the call screen to Hide): classic, no picture. Settings › Privacy & security › Caller on the lock screen › Initials, lock the phone and call: classic and masked, no picture. A private contact (and discreet mode): classic, no picture.
5. **Settings search.** Search "poster": it opens Answering scrolled to Call screen background. Switch back to Caller's colour and Plain: both look as before.

### 32.2 Photos and widget

1. **Take photo.** New contact › tap the photo circle: a menu offers **Choose photo** and **Take photo**. Take photo opens the phone's camera app (Parley asks for no camera permission; Android's App info › Permissions still lists none). Take a picture and accept it: **Frame photo** opens. Cancel in the camera app: you're back in the editor, nothing changed. On a phone without a camera app: "No camera app on this phone can take a photo."
2. **Frame photo.** After choosing or taking a photo, Frame photo shows the whole picture behind a circle. Drag: the picture follows the finger and stops at its edges. Pinch: it zooms around the fingers, up to 8×, and back out to the largest square. With a face off centre (a portrait with the face near the top), the circle starts on the face. **Done**: the editor's header shows the framed circle. **Use whole photo**: the header shows the photo as before. **✕** or Back: the picked photo is dropped and the editor is as it was.
3. **Buttons and TalkBack.** With TalkBack, the picture area reads "Photo framing…" and its zoom ("Zoom 1.5×"); the buttons read Zoom out, Zoom in, Move photo left / up / down / right, Centre on face (only when a face was found) and Reset, each 48 dp. In Arabic or Urdu the arrows still move the photo in the screen direction they show. Large fonts: the hint wraps and the buttons wrap onto a second line; nothing is cut off. Landscape: the circle fits the shorter side.
4. **What other apps see.** Save a contact framed off centre. Contacts list, Favourites, Recents and the incoming call screen show the framed circle; Google Contacts or another contacts app shows the same square. The contact's page header and the photo viewer still show the whole picture (tap it: zoom into parts that are outside the circle).
5. **Adjust framing later.** Edit the contact › photo › **Adjust framing**: Frame photo opens on the whole picture with the circle where you left it. Move it and save: the avatar changes everywhere; the page header still shows the whole photo. Edit again: the circle is where you left it the second time. For a contact whose photo another app set, Adjust framing frames Android's copy.
6. **Private and temporary contacts.** Repeat 1–5 for a new private contact, an existing private contact and a new temporary contact (Save to: Temporary, private or visible). Private: the lists and the call screen show the framed circle with the lock badge; the page shows the whole photo after unlock. Make the framed contact visible and private again: Adjust framing still starts where you left it.
7. **My card.** My card has no photo (it isn't shared), so the photo menu doesn't appear there.
8. **Interruptions.** Take a photo, then rotate while Frame photo is open: it stays open on the same picture. With "Don't keep activities" on, take a photo and come back: Frame photo opens on it. After saving, Parley's cache holds no camera photo (Settings › Apps › Parley › Storage: cache doesn't grow with each photo); see 32.4 for the other ways out of the editor.
9. **Favourites widget: place it.** Long-press the home screen › Widgets › Parley › **Favourites**. On Android 10 and 11 a settings screen asks "When you tap someone": Call or Open their page; skip it (Back) and the widget still appears and calls. On Android 12 and later the launcher usually places it without asking; the same screen opens from the widget's long-press menu (Settings or the pencil). The grid shows favourites in the Favourites tab's order (Custom, A–Z or Most called), each with photo or monogram and name.
10. **Tap.** Tap someone: Parley calls their default number (or the first). With Settings › Calls › Confirm before calling on, the same question as in Parley appears first. With the pocket guard on and the proximity sensor covered, it asks before calling. A favourite without a number opens their page. Long-press the widget › its settings › Open their page › Done: a tap now opens the contact.
11. **Resize and theme.** Resize it from 2×1 to 5×4: the grid gains columns and rows; favourites that don't fit are left out. Switch the phone between light and dark: the widget follows. Each name has its own TalkBack label ("Call Ana" or "Open Ana").
12. **Changes.** Star or unstar someone, rename a favourite, or reorder favourites in Parley: within a few seconds the widget follows. No favourites: "No favourites yet. Star people in Parley to see them here."
13. **Locked and private.** The widget is a home-screen widget, so it can't be seen while the phone is locked; check what it shows right after unlocking. Turn on the app lock and close Parley (swipe it away from Recents is fine, the widget keeps its last drawing). Lock the phone, then unlock it and look at the home screen before opening Parley: if Parley's process was still running, the names are already back (it redraws when the phone is unlocked); otherwise it shows only "5 favourites" and "Tap to show names", no names or photos. Tap it, or open Parley: names return. Star a private contact in Parley: it never appears in the widget, its count included, in discreet mode or not.

### 32.3 Recents cards
1. **Off until chosen.** On a fresh install and on a phone updated from 5.1, Settings › Recents & history › Recents style reads **Rich** and Recents looks as before (flat rows, coloured edge). The style menu offers Rich, Simple and **Cards**; searching Settings for "cards" or "rounded" finds Recents style.
2. **Each day is one card.** Choose Cards. Under each day header ("Today", "Yesterday", a date) that day's calls sit in one rounded card, 16 dp in from both edges: large corners at the top of the first row and the bottom of the last, small corners and a thin gap between rows. A day with one call is one fully rounded card. The headers keep their place and still open the day summary. The rows keep the Rich badges, count chip, sequence dots, duration bar, Call back pill and the tint of a missed call not returned yet, but no coloured edge. Recents ⋮ › What do the colours mean? lists the Rich lines without "Coloured edge".
3. **Everything still works in a card.** With swipe actions on, swipe a row each way: the action's colour stays inside the card's corners and the row springs back. Long-press a row: Select; selected rows turn the selected colour inside the card; Back clears them. Tap a contact row, an unknown number and a private contact's row: each opens its page. The To call strip, the filter chips, saved filters and the search behave as with Rich. Change Call list layout (Grouped, every call, by day): the cards follow the rows.
4. **Combined keypad.** Settings › Appearance › Layout › Calls layout: Combined. The docked keypad sits over the carded list; fold it: the last card clears the keypad button.
5. **Looks.** Light, dark and dark with black (AMOLED) backgrounds: the cards are a slightly lighter panel than the page in each, never grey on black blocks. List density Compact: the rows shrink inside the cards. Largest font and display size: names wrap or ellipsize inside the card. Arabic (or force right-to-left in developer options): the cards mirror, corners included. Rotate to landscape and on a tablet: the cards span the list's width.
6. **Smooth.** With a few thousand calls, fling Recents top to bottom in Cards: no dropped frames compared with Rich (Profile GPU rendering bars stay as low), and rows don't jump while new calls arrive.
7. **Private and discreet.** A private contact's calls show in their day's card with the lock. Turn on discreet mode: they disappear and the neighbouring rows' cards close up around the gap (first and last corners redrawn).

## 33. Features (5.3)

### 32.4 Review fixes
1. **Each widget calls its own person.** Place a Favourites widget and a Circle widget (and a Direct dial widget), on a phone that has had widgets placed and removed many times (widget ids in the hundreds), or add and remove a few widgets first. Star someone else in Parley so the Favourites widget redraws, then tap the Circle widget's Call on a person: it calls that person. Tap each Favourites tile and the Direct dial widget: each calls its own person; the "open" rows of the Circle widget open their own contact.
2. **Confirm before calling doesn't clear missed calls.** Settings › Calls › Confirm before calling on. Have someone call you twice and don't answer: two missed-call notifications. Tap someone on the Favourites widget: the question shows their name and number ("Call Ana?"); press Cancel: both missed-call notifications are still there, the missed calls are still unread in Recents and the reminder still repeats if it is on. Repeat with a pinned shortcut, the Direct dial widget, the Circle widget's Call and a reminder notification's Call: the same. Then tap **Call back** on the missed-call notification and confirm: the call is placed and only now the missed-call notifications go. Tap Call back again on another missed call and Cancel at the question: the notification stays.
3. **Camera photos never wait in the cache.** In the editor, take a photo and accept it, then take another one (or Choose photo, or Remove photo): Settings › Apps › Parley › Storage › cache doesn't keep the first one. Take a photo for a new private contact, then leave the editor without saving (Back › Discard): the cache doesn't grow. Take a photo, Frame photo open, then go Back out of the editor: the same. With "Don't keep activities" on, take a photo and come back: Frame photo still opens on it; force-stop Parley a day later and start it: the left-over shot is gone.
4. **Widget follows numbers and sizes.** Change a favourite's default number in Parley (or give a favourite without a number their first one): within a few seconds the widget's tap calls the new number (a favourite that had none now calls instead of opening their page). With Favourites sorted by Most called, make a few calls: the widget's order follows. Place a 4×2 widget on a phone in portrait: four columns with readable names (not six squeezed ones). Rotate the home screen (or use a tablet or unfolded foldable): landscape shows as many columns and rows as fit there, names and photos not cut off.
5. **Poster with a picture that can't be shown.** Settings › Calls › Answering › Call screen background › Poster. Give a contact a call-screen picture, then delete or move that picture file with a file manager (or restore a backup without it), and have them call: the classic screen shows, with their photo, the turning ringing frame and, with a call limit set, the remaining-time ring; no large name over an empty background. A readable picture still shows the poster after a moment.
6. **Back from the widget settings keeps the widget.** On Android 10 or 11, place a Favourites widget and press Back (or ✕) on its settings screen: the widget is placed and calls. Open its settings later and press Back: the tap choice is unchanged.
7. **Adjust framing without a change.** Edit a contact whose photo was set before 5.2 (or by another app) › photo › Adjust framing › Done without moving anything: Save stays as it was (nothing to save), and Google Contacts doesn't show the photo as changed. Move the circle and Done: the avatar changes as before.
8. **Ringing frame.** Answer a call while the frame turns: it fades out smoothly and nothing in the header jumps (with "Profile GPU rendering" on, no extra bars during the fade).

### 33.1 Calls
1. **Start calls on speaker: off.** On a fresh install and after the update, Settings › Calls › During calls › Start calls on speaker reads **Never**, and calls start on the earpiece as before.
2. **Always.** Choose Always. Call someone: the speaker comes on while it dials (the ringback is on the speaker). Tap Speaker: the earpiece, and it stays there when they answer. Have someone call you: it rings as usual and the speaker comes on once you answer. Connect Bluetooth earbuds (or a car) and call: the call stays on the earbuds. Plug in a wired headset: the same.
3. **Numbers not in your contacts.** Choose it. Call a saved contact and a private contact: earpiece. Call (and be called by) a number that isn't saved and a hidden number: speaker. Discreet mode on, call a private contact: earpiece (it is still saved).
4. **Never switched.** With Always: call the emergency test number your carrier gives, or an emergency number on a phone without a SIM where that's allowed: the audio is left where Android puts it. During a call, answer a second call (or add a call): the audio stays where you had it. Start **I'm on hold** on a speaker call, then **They're back**: the speaker stays on (hold mode only turns it off when it turned it on).
5. **Screen at your ear.** Settings › Calls › During calls › Turn the screen off at your ear offers **Off**, **During calls** (the default, as before) and **Once answered**. With Once answered, call someone and hold the phone to your ear while it rings out: the screen stays on; once they answer, it turns off at your ear. With During calls it turns off while dialling too. Incoming calls never turn the screen off while ringing, with either choice. Off: the screen stays on. Search "after answering" finds the row.
6. **Flip to silence.** Settings › Calls › Answering › Flip to silence is off by default; turn it on. Have someone call while the phone lies face up: turn it face down and leave it: within a second it stops ringing and vibrating, the call screen shows "Silenced" and the caller still hears it ring; it is never declined. Turn it face up while it rings, then quickly face down and up again: nothing (it has to stay down about half a second). Shake it hard while face down: nothing. A phone already lying face down when the call comes in keeps ringing; pick it up and put it down again: silenced. With another call going on, a waiting call isn't affected. Off: turning the phone over does nothing. Parley's permissions are unchanged (Settings › Apps › Parley › Permissions).
7. **Send to another number.** On a carrier that supports call deflection, have someone call: the incoming screen's ⋮ offers **Send to another number** (it asks to unlock first on the lock screen). Type a name: matching contacts and private contacts (not in discreet mode) show with their numbers; type a whole number: "Use …". Pick one: the call goes on to that number unanswered and the call-ended screen says "Sent to another number" (never "Call dropped"). Where the carrier doesn't support it, the item isn't there. Typing an emergency number or "12": "Calls can't be sent to that number." If the network ignores the request, after about 10 seconds the call still rings and "The call couldn't be sent on. It's still ringing." With Parley's app lock locked: "Unlock Parley to pick from your contacts, or type the number." Simple mode doesn't offer it.
8. **Transfer isn't offered.** During a connected call, More has no Transfer: Android keeps call transfer for system phone apps.
9. **Is this a scam?** Answer a call from a number that isn't saved (or a hidden number). More › **Is this a scam?**: "Parley can't hear the call…", six warning signs with icons (rushing you, gift cards or crypto, a code or PIN, installing an app, a "safe account", a family member in trouble), then **Ask your family safe word** (only when a label has a safe word: it closes the sheet and shows the safe-word card, question hidden on the lock screen), **Check it's really them** (the saved-number sheet, after unlocking), **Hang up and call the official number** (ends the call and opens the keypad after unlocking) and **Hang up** ("Then block or report the number on the next screen"; the post-call card follows). Not offered for a contact, a private contact, an emergency call or a conference. Over the lock screen the sheet shows no name or number. TalkBack reads "Warning signs" and "Safe ways to check" as headings.
10. **After the call.** End a call with an unknown number: the post-call card has **Was it a scam?**, which opens the same signs with **Call a saved number**, **Block this number** and **Report this number** (each after unlocking). The card stays up while the sheet is open.
11. **Text me your name.** Settings › Messaging › Quick reply messages: under the four replies, a field "For numbers not in your contacts" reads "Sorry, I don't answer unknown numbers. Please text me your name and why you're calling." Have an unknown number call, tap Reply: that message is first, with "For numbers not in your contacts" under it; tapping it declines and sends it (carrier reply-with-message) or opens the messaging app with it filled in. A contact calling: it isn't listed. After a call with an unknown number, the post-call card's **Ask their name** opens the messaging app with the text for you to send (nothing is sent by Parley). Empty the field and save: it's gone from both places. **Reset** brings it back.
12. **Looks.** Light, dark and AMOLED; the largest font (the sheets scroll, rows wrap); Arabic or forced right-to-left (rows mirror, numbers stay left to right); landscape and a tablet; Remove animations on (the sheets still open, nothing moves on its own).

### 33.2 Contact fields
1. **Custom fields.** Edit a contact in a Google account › Add a field › **Custom field**: a block with Label and Value appears and takes the focus. Type "Shoe size" / "38", add a second one, save. The page shows a **More** section with "38 · Shoe size". In Google Contacts (on the web, after a sync) the contact has the same custom fields. Edit one there and sync: Parley shows the change. Remove one in Parley: it's gone there too.
2. **Custom fields elsewhere.** Repeat on a phone-only contact, a CardDAV contact, a new private contact and a temporary contact: the fields save, show under More, and come back in the editor. Make the phone-only contact private, then visible again: the fields are still there. Search the Contacts tab for "38": the contact is found with "Matched: custom field" (device contacts).
3. **Name details.** Open the name chevron: **Phonetic middle name** sits between the phonetic first and last names. Fill it, save, reopen: it's kept, and a vCard share has `X-PHONETIC-MIDDLE-NAME`. Import a card with `N:García;Ana;;;;López;Jr.`: the editor's name block shows **Second surname** "López" and **Generation** "Jr." (only for contacts that have them), the page lists them under More, and sharing the contact writes the same `N` line back.
4. **Language.** Add a field › **Language**, type "Spanish": the field says "Saved as Spanish" and saving stores `es`; the page shows "Spanish" under More. Type "pt-BR": "Portuguese (Brazil)". Share the contact: the card has `LANG:es`. Import a card with `LANGUAGE:de`: it shows as German and goes out again as `LANGUAGE:de`.
5. **Address parts.** Import a card whose address has RFC 9554 parts, for example `ADR;TYPE=work:;;Rua A 1;Porto;;4000-001;Portugal;12;;2;1;Rua A;Torre B;;;Bonfim;;N`. The import report lists nothing as not mapped. The editor shows "Also: Room: 12 · Floor: 2 · …" under the address (not editable); the page lists them under More. Change the street and save: the parts are still there, and a vCard export writes them back in the same places.
6. **More phone types.** Tap a number's type: Mobile, Home, Work, Main, Work fax, Other, then **More types…** and Custom…. More types lists Work mobile, Pager, Work pager, Assistant, Company main, Callback, Car, Home fax, Other fax, ISDN, Radio, Telex, TTY/TDD and MMS, the current one ticked. Pick Assistant: the field shows "Assistant"; Google Contacts and the call screen show the same type. TalkBack reads each type; at the largest font the list scrolls.
7. **Lunar, Hebrew and Hijri dates.** Add a birthday with the year, then in the date picker set **Calendar** to Chinese lunar (Hebrew, Hijri likewise). Turn "Include year" off: the calendar choice is replaced by "To follow another calendar, include the year." With the year, save: the date row says "Chinese lunar calendar" under it; the page shows the date as born and "Chinese lunar · next …" with this year's (or next year's) Gregorian day. Example: born 27 January 1990 (Chinese New Year) shows next 17 February 2026 when checked after 29 January 2025. Birthdays (Settings › Contacts › Birthdays) sorts it by that day. Set the phone's date to the day before (or use a 1-day lead): the birthday reminder arrives that day, not on 27 January.
8. **Calendars round-trip.** Share the contact as a vCard: the birthday has `X-PARLEY-CALENDAR=chinese` (or `hebrew`, `islamic-umalqura`) on its Gregorian date, and no `CALSCALE`. Import it on another phone with Parley: the calendar is kept. A private contact keeps the calendar too (its page shows the next day after unlock), and Make visible keeps it.
9. **Social profiles in vCards.** Share a contact with an Instagram and a LinkedIn profile: the card has `SOCIALPROFILE;SERVICE-TYPE=Instagram;USERNAME=…:https://…` lines and no `X-ABLabel` for them. Import it back: the profiles are the same. Import an older Parley export (labelled URLs) and an iPhone card (`X-SOCIALPROFILE`): both still give the profiles.
10. **CSV formats.** Settings › Contacts › Export all to .csv file: a sheet offers **Parley CSV**, **Google Contacts CSV** and **Outlook CSV**. Choose Google: the file is named contacts-google.csv; import it at Google Contacts › Import: names, phones with their labels, e-mails, addresses, company, title, **department**, birthday, notes, labels and custom fields arrive without any column mapping. Choose Outlook: contacts-outlook.csv imports into Outlook (outlook.com › People › Import contacts) with its own columns (Business Phone, Mobile Phone, Department…). Parley CSV now has a **Department** column; importing it back keeps the department, and the column-mapping screen offers Department for a Google or Outlook file's department column.

### 33.3 Work profile
Needs a phone with a work profile (Settings › Passwords & accounts › Work, or Google's Test DPC app from the Play Store set up as a work profile) and a few contacts saved in the work profile's Contacts app.
1. **Contacts search.** In Contacts, search for part of a work contact's name: below the personal matches a **Work** header lists them, each with a small briefcase on the photo and its number (or "From your work profile"). A personal contact with the same name still appears above, once. Search for a work contact's number: the same row appears. TalkBack reads the briefcase as "Work contact" and the row's action as "open in your work apps".
2. **Keypad search.** On the Keypad, open the header search and type the same name: the Work section appears after the personal and private results. A work-only match no longer shows "No matches"; "Create contact" appears only when nothing matches anywhere.
3. **Open and call.** Tap a work row: the work profile's own contact card opens (with the work badge, in the work apps). Tap the call button on the row: Parley calls the number as for any contact. With the work profile paused (Quick Settings › Work apps off), search again: no Work section, and nothing else changes.
4. **The admin's say.** In Test DPC, turn off "Cross-profile contacts search" (and caller ID): the Work section no longer appears; personal search is unchanged. Turn it back on: it returns.
5. **Never kept.** Work contacts never appear in the Contacts list without a search, in Favourites, in widgets, in backups, exports or History & undo. Remove the work profile: no trace is left in Parley.
6. **Filters.** With a label, account or the Private filter chosen in Contacts, the Work section stays hidden (it belongs to a plain search). On a phone without a work profile, nothing about searches changes.


### 33.4 Review fixes
1. **Speaker after speaker.** Settings › Calls › During calls › Start calls on speaker › Always. Make a call: it starts on the speaker. Hang up while still on the speaker, then make another call: it starts on the speaker again (it used to stay on the earpiece every other call).
2. **Earbuds since the last call.** With Always on, make a call on the earpiece and hang up. Connect Bluetooth earbuds (or the car), then make a call: it stays on the earbuds and the speaker never comes on, not even for a moment.
3. **Your own choice while it looks the number up.** With "Numbers not in your contacts", call an unsaved number and tap Speaker on and off at once while it dials: the speaker stays off when the lookup finishes.
4. **Merge.** With Always on, have two calls on the earpiece (switch each back to the earpiece) and tap Merge: the conference stays on the earpiece.
5. **Emergency call-back window.** In Blocking & screening, add a number of your own (a second phone) to the numbers that start the one-hour emergency window, and call it: Blocking shows the "Emergency call-back window" countdown. Have someone call within the hour: the incoming screen's ⋮ has no **Send to another number**, and turning the phone face down with Flip to silence on leaves it ringing. After the window ends (or after Reset), both work again.
6. **A failed hand-off.** On a carrier that refuses the deflect, send a call to another number: after about ten seconds the message says the call is still ringing silently and to answer or decline it.
7. **Hijri birthday twice in a year.** Give a contact a birthday kept by Hijri whose day falls in early January this year (5 Rajab 1446 is 5 January 2025; 5 Rajab 1447 is 25 December 2025). Born 5 Rajab 1420 (October 1999): the January reminder says "turns 26", the December one "turns 27", and both arrive (marking the first as wished doesn't silence the second).
8. **Lunar New Year edge.** A Chinese lunar or Hebrew birthday whose day moves between December and January: "turns N" counts the years of that calendar, not the Gregorian year.
9. **Calendars in vCards.** Share a contact with a Chinese lunar birthday: the card has `BDAY;X-PARLEY-CALENDAR=chinese:…` with the Gregorian day and no `CALSCALE`. Import a card with `BDAY;CALSCALE=islamic-umalqura:14461001`: the birthday is 30 March 2025 and follows Hijri. Import one with `BDAY;CALSCALE=hebrew:57500315`: there is no birthday; a custom field "Birthday (hebrew calendar)" holds 5750-03-15, and the report lists `BDAY;CALSCALE=hebrew (kept as a custom field)`.
10. **Another app's calendar value.** On a contact whose birthday another app marked (DATA14, for example `persian` or `islamic-civil`), open the date, change only the day and tap OK: the mark is still there (check with a vCard share: `X-PARLEY-DATA14=persian`). Tapping OK without changing anything writes nothing.
11. **Half a custom field in Google.** In a Google account, add a custom field with a value and no label: it saves and shows under More, and Google Contacts never shows a broken field after sync (Parley keeps it as its own field). Give it a label: it becomes Google's field and syncs.
12. **Imported preference and profiles.** Import a card with `TEL;PREF=1E:111` and `TEL;PREF=2:222`: 222 is the default number. Import `SOCIALPROFILE;SERVICE-TYPE=Instagram;VALUE=text:ana.lima`: the contact gets the Instagram profile, not a website "ana.lima".
13. **CSV note.** Settings › Contacts › Export all to .csv file: under the three formats, a note says that values starting with =, +, - or @ get an apostrophe and that Google Contacts and Outlook keep it.
14. **Small things.** Work results in search use the same separator as the rest of the app. Settings › Messaging › Quick reply messages: edit the name reply and rotate the phone: the edit is kept. On a contact page, tap a row under More: the value is copied (TalkBack says "Copy"), long-press still copies. In the editor, More types… lists the types with the app's own list rows.

## 34. Corrections (5.3.1)

### 34.1 Save and share images
1. **The original, saved.** Give a phone contact a photo picked from the gallery (a JPEG straight from the camera). Open the contact › tap the photo: the viewer shows **Save** and **Share** at the bottom, with a line saying it is the photo as picked, in its own format, and that its location was removed when Parley kept it. Tap Save: Android's "Save to" screen opens with the name "<their name>.jpg"; save it to Downloads. In a file manager the file has the same size and resolution as the gallery photo (or a few bytes less when it had a location) and opens in the gallery with the camera and date details. Repeat with a PNG screenshot and a WebP image: "<name>.png" and "<name>.webp", byte for byte the same as the picked file. A HEIC photo: "<name>.heic" when it has no location, otherwise as answered when it was picked (34.4).
2. **Share.** In the same viewer tap Share: the share screen opens with the picture's preview; send it to yourself in a messaging or email app: the attachment is the full-size photo with its name, not the small circle. Settings › Apps › Parley › Permissions: no storage or media permission was added, and none was asked.
3. **Without an original.** Set a contact's photo in Google Contacts (or use one from before Parley 4.3), open it in Parley and tap the photo: the line says Parley doesn't have the original, so you get the largest copy Android keeps; Save gives a JPEG of up to 720 px (Android's display photo, not the 96 px thumbnail).
4. **Private contacts.** Open a private contact with a photo › tap it › Save: Parley asks for the private contacts' unlock first (fingerprint or screen lock); cancel it: nothing is saved. Unlock: the "Save to" screen opens, and the file is the original as picked (not the sealed file). Share: after the unlock, share to an app, then come back: the app can still read the picture (the decrypted copy stays until Parley locks or 10 minutes pass, 34.4). Force-stop Parley while the share screen is open and start it again: the copy is gone. A private contact without an original: the line says it is the copy kept for caller ID.
5. **Discreet mode.** Turn on discreet mode (Hide private contacts): private contacts can't be opened, and nowhere offers Save or Share for their photo, call-screen picture or QR codes. After a duress unlock: the same.
6. **Temporary contacts.** A temporary contact's photo (device or private): the viewer offers Save and Share like any other, with the same unlock for a private one.
7. **Call-screen picture.** On a contact page with a call-screen picture, Settings for this contact › tap the small picture (TalkBack: "View call screen picture"): it opens full screen with Save and Share ("<name> call screen.jpg"); tapping the rest of the row still picks a new picture. For a private contact, the unlock comes first. The Poster background shows the same picture, so it saves the same file.
8. **QR codes.** Each of these shows Save and Share under the code, and the saved file is a sharp PNG that the Scan QR screen (Pick an image) reads back: a contact's QR code ("<name> QR code.png"), its secure QR code, My card's QR code (also when swapping cards after scanning someone's), a shared label's invitation, a rule template's QR code and simple mode's setup QR. Secure QR, invitations and simple mode say under the buttons that the passcode should go separately. A private contact's plain or secure QR asks for the unlock first.
9. **No picture on the call screen.** During a call (and on the lock screen with a masked caller) tapping the caller's photo opens nothing to save or share; the call screen is unchanged.
10. **Look.** In the photo viewer the buttons sit above the navigation bar in light, dark and AMOLED themes, in landscape and at 200 % font size (the line wraps; both buttons stay 48 dp tall); with a right-to-left system language the order mirrors. Tapping the picture (not the buttons) still closes the viewer; pinch and double-tap zoom still work.

### 34.2 Editor and contact page
1. **Move a number up.** Edit a contact with three numbers. Press and hold the third number's ⊖ (a tap removes it, 34.4) › **Move up** twice: it is first, and the keyboard focus (if it was in that field) stays with it. Move up is greyed on the first row, Move down on the last. Save: the contact page lists the numbers in that order; open Google Contacts (or Android's contacts app): same order there. Edit again: the order is kept.
2. **Every group.** Repeat with two emails, two addresses (each with a map link: the link stays with its address), two websites, two profiles (profiles and websites move only among themselves), two messenger handles (a SIP address among them), two relations, two dates and two custom fields. Each saves and reads back in the chosen order.
3. **Nothing else changes.** Make the second of two numbers the default (contact page › long-press › Default), then in the editor move it up and save: it is still the default ("Default" on its row). An email Outlook gave a display name, or an address another app gave a floor ("Also: Floor: 2"), keeps it after a move. Share the contact as a vCard: the numbers are in the new order.
4. **Read-only rows.** On a contact whose copy has a locked row (lock icon, from an Exchange or company account), that group shows no ⋮ and keeps the order the account gives it.
5. **Private, temporary, drafts.** Do 1 on a private contact and on a new temporary one: the order is kept. Move rows, then rotate the phone and leave the app until it is stopped (Developer options › Don't keep activities): the draft comes back in the moved order.
6. **TalkBack.** With TalkBack on, focus a number's field or its ⋮ button: the actions menu offers **Move up** and **Move down**; using one moves the row.
7. **Copy the name.** Open a contact with a nickname, pronouns and a job. Press and hold the name: "Copied" (a system preview on Android 13+), and pasting gives only the name. A tap on the name does nothing. Tap the pronouns, the nickname and the job line one at a time: each copies just its own text. TalkBack reads "Copy" as the action on each. Same on a private contact.
8. **Other rows copy.** Long-press a number, email, address, date, website, profile, handle, relation, the contact's note, a custom field and the note for calls: each copies its value (the note for calls still opens its editor on a tap).
9. **Private contacts in the relation picker.** Edit a device contact › Relation › choose a contact: private contacts are listed with their lock badge. Pick one and save. On the device contact's page, tapping that relation opens the private contact. Turn on discreet mode (Hide private contacts): the picker no longer lists them, and the relation's tap says there is no contact with that name.
10. **Two-way with a private contact.** With Settings › Contacts › "Add relations to both contacts" on: on private Ana, add "Mother: Sam" (Sam a device contact, picked from the list) and save. Sam's page shows Ana under About with "Child · From their contact", and a tap opens Ana; Google Contacts and other apps show no new relation on Sam. Turn on discreet mode, or lock Parley and open Sam again: the row is gone. The other way: on device Sam, add "Spouse: Ana" (picked); Ana's page shows Sam with "Spouse · From their contact". Turn the setting off: neither page shows these rows.
11. **Relationship status.** Give a contact the relation "Spouse: Sam" (picked from your contacts): under the name, "Married to Sam" joins the line; a tap opens Sam, a long press copies the line. Add "Partner: Alex": "Married to Sam · Partner of Alex". Change Sam to "Ex-spouse": the header line goes, and Sam's row under About says "Formerly married to". A relation "Partner: Ana" naming a private contact opens Ana; in discreet mode the tap says there is no contact with that name. A contact whose spouse is only shown from a private contact's relation (10) gets the line too, outside discreet mode.

### 34.3 Search and filters
Automated: `ContactSearchTest`, `ContactFiltersTest` and `ContactSearchSpeedTest` (core:common), `PeopleIndexSearchTest` (core:data, Robolectric, 5,000 contacts).
1. **Every field.** Give a contact a nickname, a phonetic name, a second number, an email, an address with a city, postcode and country (and a floor or building from an imported card), a company with title and department, a website, an Instagram profile, a Signal handle, a SIP address, a relation ("Sister: Bruno"), a birthday in 1990, a note, a custom field, pronouns, a language and a label. In Contacts, search each in turn ("bruno", "sister", "1990", "may", "she/her", "portuguese", the label's name, the account's email, the postcode…): the contact is listed, and under it "Matched: relation", "Matched: date", "Matched: pronouns" and so on. A name or number match shows the usual second line.
2. **Words in any field.** Search "ana lisbon" (her name and her city): Ana is found, "Matched: address". "ana madrid" when she has nothing in Madrid: not found. Accents and case don't matter: "jose", "JOSÉ", "zurich" for Zürich, "lodz" for Łódź, "strasse" for Straße.
3. **Numbers in any form.** A number saved as "07700 900123" is found by "+44 7700 900123", "447700900123" and "7700 900"; one saved as "+44 7700 900123" by "07700900123". The keypad's header search (Keypad tab › search) finds them the same ways, and T9 is unchanged.
4. **Filters.** Open the Contacts search: a **Filters** chip appears after All (it isn't there while the search is closed and nothing is filtered). Tap it: a sheet with Private and temporary, Labels, Account, Country, City or region, Company, Birthday (Has a birthday and the months used), Relation, Language, Custom field, Has (an email, an address, a photo) and Missing info (No number, No name). Only values your contacts have are offered, each with its count; "PT", "portugal" and "Portugal" are one **Portugal**. A long list shows twelve and **Show all**.
5. **Combining.** Choose Portugal and Spain: people in either. Add **Has an email**: only those of them with an email. Close the sheet: each choice is a chip above the list with a ✕ (TalkBack: "Remove filter: Portugal"); tap one to remove it. The footer says "n contacts match the filter". Type a word: the filters stay and the search runs within them. Close the search: the filters and their chips stay; **All** clears everything. The sheet's button reads "Show n contacts" with the live count.
6. **Missing info.** **No number** lists contacts with only an email or address; **No name** lists contacts shown by their number or email (a company counts as a name).
7. **Private contacts.** With private contacts, unlock them (open one and unlock), then search a word from a private contact's note or address: it is found with its lock badge and "Matched: note". Wait more than five minutes after the last unlock (or restart the phone), then search the same word: a card says "Private contacts are searched by name and number until you unlock them." with **Unlock**, and the contact is found only by its name or number; tap Unlock and confirm: it appears. With the app lock on and the search open, turn the screen off and on again: the private details are forgotten and the card shows until you tap Unlock. Filters (a country, a birthday month) include private contacts only while unlocked the same way. Turn on discreet mode: no private contact is found by any field and the card isn't shown. Nothing about private contacts is written to disk for search (no new files in the app's storage after searching).
8. **Follows changes.** Leave Contacts open with a filter on; in another contacts app, add a contact in that country: it appears within a few seconds. Change a city there: the City filter offers the new one.
9. **Speed.** With thousands of contacts, typing in the search stays smooth (no visible lag per letter); opening Contacts right after start doesn't stutter while the index builds.
10. **Looks.** Light, dark and AMOLED; the largest font (the sheet scrolls, chips wrap); right-to-left (chips mirror); landscape and a tablet; TalkBack reads each group's heading, each chip with its count and selected state; Remove animations on.

### 34.4 Review fixes
Automated: `ContactSearchTest`, `TextSearchIndexTest`, `OriginalPhotoTest`, `ParleyRelationsTest`, `ContactFiltersTest`, `ContactSearchSpeedTest` (core:common); `ParleyRelationRowsTest`, `RecordSealingTest`, `ContactRowOrderWriteTest`, `AppDatabaseMigrationTest` (core:data); `ImageExportTest` (app).
1. **A name and a number.** Contacts "Ana" (+351 912 345 678) and "Zed" (+351 961 000 000). In Contacts search "ana 912": only Ana. "zed 912": nobody. "912 345": Ana. Same in the keypad's header search (Keypad › search).
2. **Private relation on a synced contact.** Edit a Google contact Bob › Relation › choose a contact › pick private Ana: a dialog says Ana is a private contact and that saving stores "Ana" on Bob in the phone's contacts, where other apps can read it, and it syncs to Bob's account. **Cancel**: nothing changes. **Save on this contact**: as before (Google Contacts shows the relation). Again with **Keep it in Parley only**: the relation moves to a group "Kept in Parley only" (lock icon) with its type pill and remove; save. Google Contacts shows no relation on Bob. Bob's page shows "Ana" with "Sister · Kept in Parley only"; a tap opens Ana; "Married to Ana" appears for a Spouse relation. Ana's page shows Bob "From their contact" (with "Add relations to both contacts" on). Discreet mode: the row is gone from Bob's page and his editor; edit and save Bob, turn discreet mode off: the relation is still there. Back up and restore: it comes back. On a phone-only contact the dialog doesn't mention syncing. On a private contact the picker asks nothing.
3. **Share stays readable.** Share a private contact's photo to Drive (Save to Drive) or a messaging app that opens a chat picker first: the upload/attachment works. Lock Parley (app lock on): the decrypted copy is deleted; with the app lock off it is deleted 10 minutes later, or at the next start.
4. **Save survives rotation.** Open a contact's photo › Save › on the "Save to" screen rotate the phone (or switch dark mode), then save: "Saved", and the file has the picture. With Developer options › Don't keep activities, do the same: the file is written once Parley shows the viewer again. Make a save fail (choose a place that refuses writes, or a private picture after the vault locked): no empty "<name>.jpg" is left there.
5. **Private search forgets.** App lock off. Search a word from a private contact's note: found. Turn the screen off and on: the card asks to unlock; the details aren't searched until Unlock. Leave the search open untouched for over a minute, then type: the details are opened again (unlock asked when the vault needs it).
6. **Photos kept as picked.** Pick for a contact: a HEIC without location (a screenshot converted, or location off in the camera) — no question; the viewer says "whole and in its own format", Save gives "<name>.heic" byte for byte. A HEIC with location: Parley asks "Keep where it was taken?" — **Keep with location**: Save gives the .heic with its location, the viewer says "location included"; **JPEG without location** (or tapping outside): a .jpg, the viewer says "A full-size, high-quality JPEG…". A JPEG with location: no question, kept as .jpg without location ("its location was removed"). A file over 40 MB: "Keep the whole file?" — either answer works. A photo kept before this version says "The photo at full size, as Parley kept it." Rotate while a question shows: it is asked again, not lost.
7. **Custom field order.** In a Google contact with custom fields "Shoe size: 38" then "Hat: M", clear the first one's label (it becomes Parley's kind) and save: it is still listed first.
8. **Read-only website.** On a contact whose websites include a locked one, neither profiles nor websites offer moves.
9. **Editor remove and move.** In a group of three numbers, ⊖ removes a row in one tap; press and hold ⊖ for Move up / Move down. With TalkBack, the number's field and its ⊖ both offer Move up and Move down (no need to focus the field first).
10. **Photo viewer bar.** Open a contact photo without a kept original (or the call-screen picture): tapping the line above Save/Share or the dark bar around them doesn't close the viewer; tapping the picture does.
11. **Filters keep their names.** Filter by a country only private contacts have; lock Parley (or turn the screen off) and come back: the chip still reads "Portugal" (not "portugal").
12. **Other languages.** Set the phone to Portuguese: searching "maio" finds May birthdays (and "may" still does). A contact with country "Deutschland" and one with "DE" are one **Germany** filter, and "germany" finds both.
