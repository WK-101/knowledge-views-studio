# Call screen design (4.1)

How the leading phone apps lay out their incoming and in-call screens (checked September 2026), and the spec Parley's call screen follows. Code: `telecom/.../ui/InCallScreen.kt` (layout), `CallerHeader.kt`, `CallButtons.kt`, `CallBackground.kt`, `IncomingControls.kt`, `CallWaiting.kt`, `CallTimeUi.kt` (More sheet); pure rules in `core/common`: `calls/CallControls.kt` (grid vs. More) and `ux/CallBackdrop.kt` (background contrast).

## What the top apps do

| App | Notable patterns |
|---|---|
| Phone by Google (Material 3 Expressive, rolled out mid-2025) | Bigger caller photo and name; the photo sits in an animated scalloped shape while it rings. Answer is a choice in Settings: **Horizontal swipe** (a pill slider with the phone button in the middle, answer right, decline left, chosen because vertical swipes caused pocket answers) or **Single tap** (two buttons). In-call controls became **pill-shaped buttons that morph into rounded rectangles when switched on**, with more space between them and a **wider End call** button. Call Assist / Audio Emoji / Call Notes appear as large pills above the grid; the rest live behind a simplified **More** pop-up. |
| iOS 26 Phone (Liquid Glass) | Contact Poster fills the screen behind the name while it rings; in a call, a **3 × 2 grid of round translucent buttons** with labels (Speaker/Audio, FaceTime, Mute, More, End, Keypad), the red End button inside the grid's reach. Secondary features (Hold Assist, call recording, Add call) moved into **More**. Toggled buttons fill white with a dark glyph. |
| Samsung One UI 7/8 | Call background (the contact's picture or a video) under a dark scrim; name, number and SIM at the top; the in-call buttons were regrouped into **neatly separated rounded buttons** with labels (Record, Video, Bluetooth, Speaker, Mute, Keypad), End call as a big red circle at the bottom; call info also in the Now Bar pill on the lock screen. |
| Pixel / AOSP Dialer, Fossify Phone | Contact photo or large initial circle, name, number and a status/timer line at the top; a 3 × 2 grid of round icon buttons with labels; red round End call at the bottom; the keypad slides over the grid with "Hide" beside End call; conference "Manage" list with Private/End per person. |
| OxygenOS 15, Nothing OS 3 | Same hierarchy; OxygenOS uses a tonal gradient from the wallpaper colour, Nothing a monochrome dot-matrix style with one red accent (End). Both keep the controls in the bottom half for one-handed use. |
| Material 3 Expressive guidance | **Shape morphing** (round at rest, squarer when pressed; toggle buttons change their resting shape when selected), **button groups** with even spacing, **expressive spring motion** from the motion scheme, **larger targets** for primary actions, colour as a secondary signal (icon and label change too). |

Common ground worth copying:
1. **Hierarchy**: who is calling first (big photo, big name), one calm secondary line, then the status or timer; everything else below or tucked away.
2. **Controls in the bottom half**, a regular grid with labels under each button, **End call centred at the bottom**, the largest target on screen.
3. **Toggled state that doesn't rely on colour alone**: filled container, a different shape and a different icon/label (Mute → Muted with a crossed mic).
4. **Secondary features behind More** rather than a crowded grid; the grid keeps the same buttons in the same places.
5. **Answer gestures that resist pocket answers** (horizontal slide) with a tap alternative.
6. **Background with personality but guaranteed legibility**: contact picture under a scrim, or a tonal gradient from the contact's colour.

## Parley's spec

### Layout (portrait)

Top to bottom, inside a 24dp side gutter (`Spacing.xl`):

1. Other calls (on-hold strip with Swap / Merge / End, "Connecting…" banner), a failed second call (Retry), "Blocked · Undo".
2. **Caller** (scrolls on small screens and at large font sizes; the controls never move):
   - photo 128dp (88dp when the screen is shorter than 480dp), inside the remaining-time ring when a limit applies, with a slow breathing halo in the primary colour while it rings (still when animations are off);
   - name, `headlineLarge`, up to 2 lines;
   - one calm line in `onSurfaceVariant`: label · number (or "Not in your contacts · Leeds" for an unknown number while it rings); job/company under it;
   - **tags**: SIM and "Verified number" as quiet icon + text, and warnings (Possibly spoofed, Emergency call, a spam verdict) as `errorContainer` pills;
   - **status pill** (`surfaceContainerHighest`, `titleMedium`): "Incoming call", "Calling via Work…", "On hold", or the running time in tabular digits; the "12:31 left · Limit for Ana" line under it;
   - **caller card** (`surfaceContainerHigh`, `ParleyShapes.card`, max 480dp): who is this, the pinned note, the last note and open promises, the last call, each with a small leading icon.
3. **Controls** (bottom): the incoming controls, or the 3 × 2 grid, then End call.

Keypad open: the caller collapses to name, secondary line and status; the keypad replaces the grid (fade + spring scale from 92%); "Hide keypad" sits to the end side of End call.

Landscape phones, unfolded foldables and tablets (width ≥ 560dp and wider than tall): two panes, caller on the start side, controls on the end side, each centred and scrollable.

### Ongoing call: the grid

Decided by `CallControls.layout` (core:common, unit-tested):

| | column 1 | column 2 | column 3 |
|---|---|---|---|
| row 1 | Mute | Keypad | Speaker / audio route |
| row 2 | Hold | Merge → Swap → Manage → Add call (first the call allows) | More |

- The grid always has six buttons in the same places. A button the call doesn't allow stays, disabled (38% content), instead of leaving a hole.
- The multi-call candidates that don't win the fifth place go to the top of **More**, with the same icons and names. More then has Add a note, Open contact, and the **Call time** card (+2 / +5 min, End in 1 min, Don't end; "can only be shortened" in supervised mode).
- Add call is off while a second call can be neither merged nor swapped (Telecom would refuse a third).

Button spec (`CallButtons.kt`, `CallButtonSize`):

| Token | Value |
|---|---|
| Control container | 64dp tall, cell width up to 96dp, 12dp between cells, 16dp between rows, grid max 420dp wide |
| Resting shape | pill (32dp corners) |
| Pressed | 14dp corners (spring, `ParleyMotion.fastSpatial`) |
| On (Mute, Speaker, Hold) | 20dp corners, `primary` container, `onPrimary` icon, icon swaps (Mic → MicOff, Pause → PlayArrow), label swaps (Mute → Muted, Hold → Resume); colours animate with `ParleyMotion.effects` |
| Off | `surfaceContainerHighest`, `onSurface` icon 28dp |
| Label | `labelLarge`, `onSurface`, up to 2 lines, 8dp under the container |
| End call | 136 × 72dp pill, `CallColors.Decline`, white 32dp icon; widens by 12dp and squares to 22dp corners while pressed |
| Answer / Decline (tap) | 80dp circles, `CallColors.Accept` / `Decline`, squaring to 30% corners and 94% scale while pressed; Answer has a breathing halo |
| Call-waiting actions | 64dp circles, same behaviour |
| Secondary actions | 48dp-tall tonal pills (`surfaceContainerHighest`), 18dp icon + `labelLarge` |

TalkBack: toggles are `Role.Switch` with a stable name and a state ("Mute, on"); the audio button says "Audio output, Bluetooth headset"; every icon-only button has a label; the ringing caller offers Answer, Decline, Reply, Stop ringing and Block & decline as custom actions; the slider has Answer and Decline actions.

### Incoming call

- Status pill says "Incoming call" (or why it's silent: "Silenced by your blocking rules", an allowance used up).
- **Secondary row** (quiet pills, centred, wraps at large font sizes): **Reply** (Reply with a message; only for a visible number), **Silence** (stop ringing; the call keeps ringing silently), and **⋮** with **Block & decline** (two deliberate taps; Undo on the next screen).
- **Answer** follows Settings › Calls › "Answer incoming calls by": **slide** (80dp pill track, 64dp thumb, past 55% to answer right or decline left, always left-to-right, haptic tick) or **tap** (Decline and Answer circles, the SIM tag under Answer on dual-SIM phones).
- "End current call and answer" while another call is going. Simple mode keeps its two very large buttons and "Decline this call?".

### Call waiting, hold, conference

- The call you're on stays at the top as a dimmed card ("Answering puts this call on hold"); the waiting call rises as a sheet with Hold & answer / End & answer / Decline / Reply as 64dp round actions and a Silence pill. It can't be swiped away.
- Held calls: a `secondaryContainer` strip, "On hold · 02:10", with Swap, Merge and End.
- Conference: More › Manage conference (or the grid's fifth button) opens a sheet with each person, Private and End.

### Background

`CallBackground.kt` with `CallBackdrop` (core:common):
- **Tonal gradient**: the theme surface tinted at the top with the caller's colour (the same hue as their avatar; the theme's or dynamic `primary` for unknown numbers; `error` for a likely spam call), fading to the plain surface at 60% height. The tint is at most 32%, and weaker when needed so that `onSurface` and `onSurfaceVariant` text keep **4.5:1** (`CallBackdrop.tintStrength`). AMOLED keeps pure black at the bottom.
- **Call-screen picture** (set per contact): full bleed under a surface scrim whose strength is the weakest that keeps both text colours at 4.5:1 over *any* pixel (`CallBackdrop.scrimAlpha` checks the black and white extremes and that both stay on the surface's side of each ink): about 73% in light and dark, 68% on black; fully covered (≥ 96%) behind the controls.
- The picture is decoded on the IO dispatcher, subsampled to about 1080px, and fades in over the gradient: nothing heavy runs on the main thread while the phone rings, and the gradient needs no image at all.
- The picture-in-picture window uses the same tint (not the picture).

### Motion and accessibility

- All motion comes from the theme's expressive motion scheme (`ParleyMotion`): springs for shape and size, effects specs for colour and fades. Endless animations (the avatar halo, the Answer halo, the slider pulse) stand still when Android's animations are off.
- Targets: every control ≥ 48dp (grid 64dp, End call 72dp). Labels are text, never only colour; warnings carry an icon and a filled container.
- RTL: the grid, rows and pills mirror; the keypad and the answer slider stay left-to-right; numbers are wrapped in `Bidi.ltr`.
- Large fonts: labels wrap to two lines, the caller scrolls, the secondary row wraps.

### Unchanged underneath

CallSession and its collaborators, Telecom calls (`CallManager`), EmergencyPolicy, StartGate and the ring path, PiP rules and actions, proximity, the notification, post-call and memory cards (restyled only by their surroundings), "Hide screen content" and the lock-screen rules for notes.

## 4.2 revisions

What changed after 4.1 was tried on a phone, and why. Where this section and the 4.1 spec above disagree, this section wins.

### What the top apps do with the answer control (checked again, September 2026)

| App | Answer control |
|---|---|
| Phone by Google (2025 redesign) | **Horizontal swipe**: a pill with the phone button in the middle, decline at the left end, answer at the right. Nothing is written under the button: "Swipe to answer" style hints sit outside the pill. The older vertical version was a bouncing answer button with the hint above it. |
| Samsung One UI | A green circle on the left and a red one on the right; you drag either one outwards. The hint arrows sit beside the circles, not under them. |
| iOS | "slide to answer": the knob starts at the **left end** and the text sits to its right with a shimmer that runs towards the end; the text fades as you drag. There's only one action on the track, so it can start at one end. |

What they share: the moving part never covers the words; the ends are clear, coloured targets; a gentle motion (shimmer, bounce) says "this moves"; the phone ticks when letting go would act.

### Incoming screen

Top to bottom, one column no wider than the grid (420dp), inside the 24dp gutter:

1. **Caller**, placed a little above the middle of the free space while it rings (it glides to the top once answered), so the screen reads as one composition instead of a header with a gap under it.
2. **Quiet actions**: Reply · Silence · More, as 56dp round buttons with the label under them (like Phone by Google's Message / Remind me and iOS's Remind Me / Message). The circle is a 10% veil of the text colour, so it takes on the caller's tint, a picture's scrim or the plain background instead of floating over it as a dark pill. The three sit in fixed 88dp columns: **the outer two are right above the answer control's two ends**, and a column whose action doesn't apply stays empty so nothing shifts. After Silence the middle one reads "Silenced" in the secondary container (not clickable). More still holds Block & decline (two deliberate taps).
3. 32dp, then **the line** the call came in on, on dual-SIM phones: "Incoming on Work · …4567" as a small label above the control (TalkBack hears it with the control).
4. **Answer control**:
   - **Slide** (default): an 80dp track (a 8% veil of the text colour) with a red Decline target at the left end and a green Answer target at the right (56dp circles, tinted at rest, filling with their colour and growing 10% as the knob comes near). The 64dp knob rests in the middle; **nothing is written inside the track**. Faint chevrons between the knob and each end shimmer outwards, and every 2.6s the knob gives a small nudge towards Answer; both stand still when animations are off. The knob turns green or red as it moves, and its handset tips over to the hang-up angle towards Decline. Only a drag that starts on the knob moves it.
   - **Rules** (`AnswerSlide`, core:common, unit-tested): letting go past 60% of the travel answers or declines; a fast flick (≥ 1200dp/s) counts from 30%, only when it keeps going the same way; anything else springs back. A threshold haptic when crossing 60% outwards, a lighter tick when backing off, Confirm on answer and Reject on decline.
   - **Hint under the track**: "Slide right to answer, left to decline", fading out over the first 30% of the drag; once letting go would act it reads "Release to answer" (green) or "Release to decline" (red).
   - **Tap**: Decline and Answer circles in the same two columns as the slide control's ends; Answer keeps its halo. Both variants stay left to right in every language (Decline left, Answer right).
   - TalkBack: the track (or the name) still offers Answer and Decline as actions, and the hint and line label are not read twice.
5. "End current call and answer" under the control while another call is going.

Simple mode keeps its two very large buttons; the line label moved above them.

### Call screen background (setting)

Settings › Calls › **Call screen background**: *Caller's colour* (default) or *Plain*. Decided by `CallBackdrop.plan` (core:common, unit-tested):
- *Plain* draws the theme's own surface, no tint. The picture-in-picture window follows the same choice.
- A **likely spam** call keeps its red wash with either choice: it's a warning, not decoration (the red "Likely spam" tag shows as well).
- A contact's **call-screen picture** still shows with *Plain*. It's a per-contact choice the user made on purpose, so a global "no tint" shouldn't silently undo it; it's removed from the contact editor. A second switch just to hide pictures would add a setting for a case nobody asked for.

### Polish and new functions in the call

| What | Why |
|---|---|
| **HD voice** and **Wi-Fi calling** tags once connected (`Call.Details.PROPERTY_HIGH_DEF_AUDIO`, `PROPERTY_WIFI`) | Samsung and Pixel show both; they explain call quality and why a call works without signal. Shown only when the network reports them. |
| **Caller's local time** ("9:40 pm there") for callers in another time zone | Worked out offline from the number (libphonenumber's time-zone map, the same one Circle uses) after the screen is up, off the main thread and never on the ring path; only shown when their offset differs from yours. Useful before answering a 3 am call too. |
| **Copy number** in More (with the number under it) | Common ask ("read me that number"); copied as sensitive so it stays out of the clipboard preview and keyboard history. Not offered for hidden numbers. |
| **On hold is unmistakable**: the status pill turns tertiary with a pause icon, and the photo dims to 55% | Before, only the word changed. The PiP window shows the pause icon too. |
| **Audio output sheet** in the kit's sheet and row style, the current route tinted and ticked | The sheet already listed each Bluetooth device by name; it now matches the other sheets. |
| **Keypad**: keys 88 × 64dp with larger digits; the tones sent so far keep their whole history in one line, trimmed at the start with "…" | Easier to hit held away from the face; checking a long account number no longer loses its beginning. |

Considered and left out: *long-press End call for "End & send message"*: a hidden gesture on the most important button risks an accidental end and isn't discoverable; Reply with a message stays on the incoming screen. *Call recording and anything needing the microphone*: no such permission. *Bigger layout changes to the grid*: its positions are muscle memory and stay.

### Tokens added

| Token | Value |
|---|---|
| Incoming / grid column width | `CallButtonSize.panelMaxWidth` 420dp |
| Incoming column (quiet action, slide end, tap button) | `CallButtonSize.slot` 88dp |
| Quiet action | 56dp circle, 10% `onSurface` veil, 24dp icon, `labelLarge` label in `onSurfaceVariant`; 14dp corners while pressed |
| Slide track / knob / ends | 80dp pill (8% `onSurface` veil) / 64dp circle / 56dp circles, 18% of their colour at rest |

## 4.4: call facts and trust

What the call screen says about the call itself, and three ways to trust it more (COMPETITIVE_ANALYSIS_6 WP-2 and WP-4). Pure rules in `core/common`: `calls/CallDrop.kt`, `calls/CallSubject.kt`, `calls/CallQuality.kt`, `calls/HoldMode.kt`, `calls/VerifyCallBack.kt` and `RangThrough` in `CallPolicy.kt`, all unit-tested. UI: `CallFactsUi.kt` (drop card, hold mode panel, saved-number sheet, the call tip).

| What | How it works | Why |
|---|---|---|
| **Call dropped** (L2, P5) | A call that was connected and ended with Telecom's ERROR (or OTHER/UNKNOWN naming telephony's drop cause: `LOST_SIGNAL`, `CDMA_DROP`, `WIFI_LOST`, `OUT_OF_SERVICE`) and that nobody hung up. The status pill says "Call dropped"; a card says why in plain words ("Lost signal · Wi-Fi calling · Work") with a 64dp green **Call again** (same number, same SIM). It stays 10 s or until dismissed. Never for emergency calls, calls ended by a limit, or hidden numbers (no Call again). | Samsung and Pixel users blame the phone app for drops; saying what happened and offering the redial is the fix people ask for. |
| **Quality facts** (L2, for WP-18) | Every call's SIM, Wi-Fi calling and HD voice (as seen while connected), disconnect code and cause, talk time and subject go into `CallQualityStore` (`parley_call_quality`, device-local, sealed and keyed like ring facts, 60 days, forgotten with the call). The number history shows the calls with something to say ("Call details"). | The raw material for a quality diary and "Calls with Mum drop on SIM 2" advice later. |
| **HD / Wi-Fi tags** (P6) | Unchanged since 4.2: next to the SIM tag once connected. | Already built; reused, not duplicated. |
| **Press and hold Speaker** (P6) | Opens the audio output list directly, headset or not; tap keeps toggling the speaker. TalkBack names the long-press action. A one-time tip (`Tips.CALL_AUDIO_ROUTES`) explains it. | Picking the earpiece while a headset is connected took two steps; the grid stays the same six buttons. |
| **Call subject** (L10) | `TelecomManager.EXTRA_CALL_SUBJECT` from the call's extras (or intent extras), and Call Composer's urgent priority as an "Urgent" tag. Cleaned by `CallSubject` (no control or bidi characters, one line, 80 characters), shown in quotes under the name as plain text, and kept in the call's facts for the history. | Callers who say why get answered; the text comes from strangers, so it's never trusted as markup. |
| **Rang through** (P1) | `CallPolicy.decide` screens an allowed call again without its exceptions (allow rules, label allow rules, Expecting a call, numbers you called or talked to, repeat callers). Only when that would have blocked it, the incoming screen says why under the status pill: "Rang through: called twice in 3 min", "expecting a call", "allowed until 18:40", "in Family (allowed during off hours)". It replaces the quiet "Allowed by …" tag; warnings stay. | Shows the blocking engine at work, so people trust it with stricter settings (U2). |
| **Check it's really them** (I3) | More › Check it's really them (and "Call a saved number" on the post-call card), after unlocking: the caller's saved numbers (a contact's, or a private contact's outside discreet mode), the calling line first, and "Pick a saved organisation" (contacts with a company). A tap ends the call and dials the saved number on the same SIM (`CallManager.hangUpAndCall`, which waits for the call to go). With Parley's app lock locked, only the number already on screen is listed. Never for emergency calls. | Caller ID can be faked; the saved number reaches the real bank. |
| **Hold mode** (I10) | More › I'm on hold: the speaker comes on (the previous route is restored on exit unless changed), the window's brightness drops to 5%, the grid becomes a panel with the waiting time, an honest line ("Parley can't hear the call"), Keypad and **They're back**. The PiP window shows "On hold · 12:34" with a They're back action. The phone buzzes twice (not in silent mode) at 15 and 30 minutes. | Most of Hold Assist's comfort without microphone access. |
| **Contact photo on the call screen** | Settings › Calls › "Show contact photo on the call screen" (on by default), overridden per contact in Settings for this contact › Photo on the call screen (*Default* / *Show* / *Hide*; `CallerPhoto.shows`). Hidden, the caller lookup hands the screen no photo and no call-screen picture, so the header, the backdrop and the PiP window show the initial on the caller's colour. The choice is kept beside the call-screen picture under the Parley key (a private contact's too), so it follows links, key changes and conversions. | Some people don't want faces on the lock screen, or a photo only for some callers. |

Left out: sending a call subject (needs `PhoneAccount.CAPABILITY_CALL_SUBJECT` carriers; WP-3), guessing when an agent is back (needs the microphone), and "Call again" for emergency calls (the system handles those; EmergencyPolicy is unchanged).

## 4.5: family safety

Three ways to protect people from impostor calls without a server or any new permission (COMPETITIVE_ANALYSIS_6 WP-8: I4, I5, I7). Pure rules in `core/common`: `calls/FamilySafety.kt` (`SafeWords`, `Helpers`, `HelperJoin`) and `calls/ExpectedCalls.kt`, unit-tested; the windows are read by `CallPolicy` like "Expecting a call". UI: `FamilySafetyUi.kt` (the safe-word card, the helper card and sheet, simple mode's button) and the More sheet (`CallTimeUi.kt`). Everything is kept in one sealed store on the phone (`FamilySafetyStore`, the vault's caller-ID key, never backed up).

| What | How it works | Why |
|---|---|---|
| **Family safe word** (I4) | A label's page has "Family safe word": a question ("What's our word?") and an answer, shown and changed only after the fingerprint or screen lock. During a connected call from a number that's neither a contact nor a private contact (or a hidden one), after 20 s, a quiet card under the caller says "Claims to be family? Ask: What's our word?". More › **Says they're family** shows it at once, for any caller. The answer shows only while the user presses and holds it (TalkBack: a double tap shows and hides it); with the phone locked the question is hidden ("Unlock to see it") and holding asks to unlock, and with Parley's app lock locked it asks for the fingerprint or screen lock first. Never for an emergency call or a conference, never for a saved member of that label, and the card can be closed. Rules: `SafeWords.cardShows`, `claimOffered`, `offeredFor`. | Voice-clone "grandparent" scams; a question only family can answer beats any voice. |
| **Bring in my helper** (I5) | Settings › Calls › Helpers (and simple mode's setup): up to 3 trusted people, private contacts too (shown as their number in discreet mode). More › **Add my helper** (simple mode: a big button above the grid) places a call to the helper on the same SIM; Telecom holds the call that goes on. A card says "Calling Sam to join…" (Cancel), then "Sam answered" with a green 64dp **Merge now** (Telecom's merge, the same as the grid's), or Swap where the network can't merge; then "Sam joined the call" or "Sam didn't answer", which go after 4 s. Offered only on a single connected call that isn't an emergency call, and never for the caller themselves (`Helpers.offered`, `forCall`; stages in `HelperJoin.stage`). | Truecaller's "family can help" without a server: the phone network's own conference. |
| **Expected-call hints** (I7) | A note or promise saved with a day and a word for calling ("Dentist will call Tue", "bank rings tomorrow at 3pm") gives a window that day (8:00–20:00, a part of the day, or 30 min before to 2 h after a time); a To call item for a number nobody saved lets that number ring until a day after it's due (at most a week); a QR code that looks like a parcel lets unknown numbers ring until 8 pm. Each kind asks once ("Expecting a call?"), is off until accepted, and has its switch in Settings › Blocking & spam › Expecting a call from your notes. In the window, screening treats the call as "Expecting a call" (`ExpectedCalls.covering`, number windows first), and the incoming screen says "Rang through: expecting a call (note on Dentist)", with the name only while unlocked ("(from your notes)" on the lock screen), "(on your To call list)" or "(delivery QR code)". | Fewer blocked doctors, couriers and call-backs (U2). |

Left out: reading the caller's words (needs the microphone), a safe word on the lock screen or in notifications, a helper during an emergency call, and guessing dates in languages other than English (Parley is English-only for now).


## 4.7: menus and call reasons

COMPETITIVE_ANALYSIS_6 WP-17 (I6, I12). Pure rules in `core/common`: `calls/MenuMemory.kt` and `calls/CallReason.kt`, unit-tested. UI: `MenuMemoryUi.kt` (the keypad's top row) and, in the app, `ui/menus/` (Shortcuts, "Call with a reason…").

| What | How it works | Why |
|---|---|---|
| **Menu memory** (I6) | Parley sends every DTMF tone itself (`CallManager.startDtmf`), so in a connected call the user placed it notes each digit and its time since the connect. When the call ends, `MenuMemory.record` keeps up to 12 digits for the number (`menu_memory`, sealed with the small-records key, keyed by line key, in the encrypted backup without private contacts' numbers). Everything from a quick run of 6+ digits, or 4+ digits ended with #, on is dropped (a card number or PIN); "Don't remember digits for this number" forgets and stops for good. Never incoming calls, conferences, hidden numbers, service codes or emergency numbers. Next call, the keypad's top row says "Last time: 2 › 1 › 4" with **Replay** (each digit after its recorded pause; Stop, or any key, stops it) and ⋮ (Save as shortcut…, Don't remember…). | Skips phone trees offline; the honest substitute for Direct My Call. |
| **Menu shortcuts** (I6) | "Save as shortcut…" keeps a name, the number and the digits. The contact page's **Shortcuts** group (and a number history, for numbers that aren't contacts) calls `number,,2,1,4` (one `,` per 3 s of recorded wait, 1–10 each) through the usual call path; ⋮ renames, pins to the home screen (the Call trampoline, with the pocket guard) or deletes (the pinned copy is disabled). | One tap to "Bank › lost card". |
| **Call with a reason** (I12) | Press and hold the keypad's Call pill (or a SIM's segment) or a contact's Call. When the SIM's phone account has `CAPABILITY_CALL_SUBJECT` (every SIM, while one is still to be chosen), **Call** places the call with `EXTRA_CALL_SUBJECT` (cut to `EXTRA_CALL_SUBJECT_MAX_LENGTH`). Otherwise, or as the second choice, **Text first** opens the messaging app with "Calling you about …" for the user to send; back in Parley, "Call Ana now?". Emergency numbers skip the sheet. | Callers who say why get answered; nothing is sent without the user. |

Left out: hearing the menu's prompts (needs the microphone), so timing is the only clue for secrets; replaying in incoming calls; Capabilities ("What Parley can do") entries, which need a screen or setting to open.
