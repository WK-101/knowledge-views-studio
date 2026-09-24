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
