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
