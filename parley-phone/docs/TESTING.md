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
