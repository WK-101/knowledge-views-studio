# UI polish for 7.0: transitions, consistency and option placement

This is a read-only audit of `parley-phone` at `61dada60` (Release 6.9.0). It covers `core/ui`, `app/.../ui/**`, `app/.../messaging/**` and `telecom/.../ui/**`, and it is meant to be implemented by several builders working in parallel. Every finding cites `file:line`. Counts come from grep over `src/main`. Items marked **(device)** were reasoned from the code and still need checking on a phone.

Abbreviations used in paths: `app/` is `app/src/main/kotlin/app/parley/`, `ui/` is `app/ui/`, `kit/` is `core/ui/src/main/kotlin/app/parley/ui/`, `tel/` is `telecom/src/main/kotlin/app/parley/telecom/ui/`, and `common/` is `core/common/src/main/kotlin/app/parley/common/`.

## 1. Summary

Since the 3.4 audit (`docs/audit/UI.md`) the design system has caught up:
- Raw `AlertDialog`, `TopAppBar`, `Scaffold`, `ModalBottomSheet`, `ListItem` and `RoundedCornerShape` are all at 0. The app uses ParleyDialog/ConfirmDialog (213 sites), ParleyTopBar (56), ParleyScaffold (54), ParleySheet (37) and ParleyListItem (349) instead.
- `ParleyMotion` reads the theme's expressive `MotionScheme`.
- Endless animations respect "Remove animations".
- Predictive back is enabled, and the editor already uses `PredictiveBackHandler`.
- List-detail on big screens, the rail, PiP, CallStyle, edge-to-edge and dynamic colour are all in place.

What remains falls into four areas:

1. **Transitions.** Movement between screens is the least modern part of the app:
   - The NavHost uses Compose's default specs, not the theme's MotionScheme. Its predictive-back preview is a plain fade and slide, not Material's shrink-and-reveal.
   - Tab switches are an untuned cross-fade that **throws away each tab's scroll position**.
   - 17 transition sites still use default specs.
   - 7 expand/collapse sections are built by hand in 4 visual styles.
   - Shared-element motion exists only from the Contacts list.
2. **Navigation safety.** 16 graph files each repeat `back = { nav.popBackStack() }` and `open = { nav.navigate(r) }` without guards. A quick double tap on Back can pop twice, possibly through Home to a blank host **(device)**. A double tap on a row opens the same page twice.
3. **Repetition.** The following are each built by hand several times:
   - overflow (⋮) buttons: 23, with 4 different labels;
   - drag-to-reorder: 4;
   - press-morph buttons: 4, in one file;
   - activity theme setup: 8;
   - scaffold-padded lists that stop above the navigation bar instead of scrolling under it: 28.
4. **Placement.** Menus are within the 7-item rule and well reasoned (`common/ux/ContactMenu`). The remaining issues are a few Tools groupings that contradict their group's name, destructive menu items that look like any other item, and overflow labels that differ between screens.

## 2. Research: what modern Android and Material 3 offer (October 2026)

Sources:
- Predictive back: <https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture> and <https://developer.android.com/develop/ui/compose/system/predictive-back-setup>
- Navigation releases: <https://developer.android.com/jetpack/androidx/releases/navigation>
- Material 3 releases: <https://developer.android.com/jetpack/androidx/releases/compose-material3>
- Android 16 behaviour changes: <https://developer.android.com/about/versions/16/behavior-changes-16>
- Live Updates: <https://developer.android.com/develop/ui/views/notifications/live-update>

| Topic | Platform / library state | Parley today | Gap |
|---|---|---|---|
| Predictive back (system) | On by default for apps targeting 36 on Android 16. `onBackPressed`/`KEYCODE_BACK` are no longer called. Back-to-home, cross-activity and cross-task animations are system-drawn. | `enableOnBackInvokedCallback="true"` (`app/src/main/AndroidManifest.xml:129`). No `onBackPressed`/`KEYCODE_BACK` anywhere. | None. |
| Predictive back (in-app, NavHost) | navigation-compose 2.8+ drives NavHost pop transitions from the gesture (seekable). 2.10.0 (stable 2.10.2, 23 Sep 2026) adds `predictivePopEnterTransition`/`predictivePopExitTransition(swipeEdge)` and fixes predictive-back races (2.9.8). Material's in-app pattern: the leaving screen scales to about 90 % with rounded corners and shifts toward the swipe edge; the screen below is revealed. | navigation 2.9.6. NavHost transitions at `ui/ParleyRoot.kt:181-184`: slide by 1/6 plus default fade. The gesture previews a slide and fade with no scale or corners. | Material-style pop transitions (UP-01). Upgrading to 2.10.x gives the swipe-edge-aware version (D1). |
| Predictive back (components) | `ModalBottomSheet`, `SearchBar`, navigation drawers animate with the gesture (material3 1.3+). `PredictiveBackHandler` gives progress for custom surfaces. | Sheets get it for free. Custom surfaces use plain `BackHandler` with no preview: Home search (`ui/home/HomeScreen.kt:197`), docked keypad (`:196`), list-detail pane (`ui/home/ListDetail.kt:199`), onboarding steps (`ui/onboarding/OnboardingScreen.kt:108`), Settings search (`ui/settings/SettingsScreen.kt:164`), Simple keypad (`ui/extras/SimpleHome.kt:109`), selection bar (`kit/SelectionTopBar.kt:32`). | UP-12. |
| Shared elements | `SharedTransitionLayout` with `sharedElement`/`sharedBounds`. Works with NavHost predictive back. | Plumbing in `kit/Motion.kt`. Used only from the Contacts list row (`ui/home/ContactsTab.kt:309,315`) to the contact header (`ui/contact/ContactHeader.kt:78,82`). | Recents, Favourites, Circle and search rows don't morph (UP-11). |
| Motion physics | `MaterialExpressiveTheme(motionScheme = MotionScheme.expressive())`; spatial springs for movement, effects springs for colour and alpha. | Theme set (`kit/Theme.kt:175`). `ParleyMotion` wraps it (`kit/ParleyMotion.kt`). 0 raw `tween`/`spring` outside endless animations, except `KeypadPill.kt:235`. | 17 transitions still use Compose defaults (UP-03). Two specs are swapped: spatial used for alpha, effects used for scale (UP-04). |
| Material 3 version | Stable 1.4.0. 1.5.0-beta01 (7 Oct 2026). alpha19–alpha23 made ToggleButton, ButtonGroup, SplitButton, FAB menu, FloatingToolbar, expressive menus (`SelectableDropdownMenuItem`/`CheckableDropdownMenuItem` alpha27, group labels alpha23), flexible top app bars and expressive/segmented list items stable. `LoadingIndicator` and `MaterialShapes` are still experimental. Breaking changes since alpha14: `Slider` needs `onValueChange` plus state overloads (alpha28/29), old `pinnedScrollBehavior` overloads and `LocalMotionScheme` removed (alpha27), `SplitButtonLayout` replaced by `SplitButton` (alpha25). | `material3 = "1.5.0-alpha14"` (`gradle/libs.versions.toml`). Its comment says "newest is 1.5.0-beta01", so the pin is stale. | D1 (owner): a dedicated upgrade package. |
| Expressive components | LoadingIndicator, ButtonGroup, SplitButton, Horizontal/VerticalFloatingToolbar, ShortNavigationBar, WideNavigationRail, Flexible top bars, carousel, expressive menus. | 0 uses of any of these. 40 `CircularProgressIndicator`. `NavigationBar`/`NavigationRail` (`ui/home/HomeScreen.kt:268,286`). Custom press-morph buttons on the call screen. | UP-30–UP-35 (adopt selectively). |
| Edge-to-edge | Enforced for target 36 (the opt-out attribute is ignored). Lists should scroll under transparent system bars, with the last item padded clear. | `enableEdgeToEdge` in every activity, and `SystemBarsFollowTheme` (`kit/Theme.kt:193`). `SettingsScaffold` does it right (`kit/Scaffolds.kt:73-79`). 28 lists pad the whole container instead (UP-08). | UP-08. |
| Adaptive layouts | `currentWindowAdaptiveInfo()`, `ListDetailPaneScaffold` (material3-adaptive), `NavigationSuiteScaffold`. Android 16 ignores orientation and resizability locks on displays of 600 dp and wider. | Own `WindowLayout` (`common/ux/WindowLayout`), tested, using Material breakpoints. Own list-detail (`ui/home/ListDetail.kt`). No orientation locks in the manifest. `rememberWindowLayout` reads `LocalConfiguration` (`ui/home/ListDetail.kt:74`). | Keep the custom one (D7). Add predictive back to the pane (UP-12). Optionally read size from `LocalWindowInfo` (UP-36). |
| Haptics | Compose `HapticFeedbackType` has `Confirm`, `Reject`, `ToggleOn`/`ToggleOff`, `VirtualKey`, `KeyboardTap`, `SegmentTick`, `GestureThresholdActivate`. | 17 sites, a sensible vocabulary. Dialpad uses `TextHandleMove` (`ui/home/KeypadTab.kt:133,140`). Switches give no haptic. 1 of 4 reorder UIs ticks. | UP-27. |
| Dynamic colour / themed icon / splash | Material You on 12+. Monochrome adaptive icon. SplashScreen API. | All present (`kit/Theme.kt:166`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `themes_splash.xml`). | None. |
| Android 16 notifications | `Notification.ProgressStyle` (segmented progress). Live Updates (promoted ongoing) need the **`POST_PROMOTED_NOTIFICATIONS`** permission. | Jobs use `setProgress` (`app/jobs/JobNotices.kt:54`). Calls use `CallStyle` (`telecom/CallNotificationTemplate.kt:64,84`). | ProgressStyle is optional (UP-37). Live Updates need a new permission, so D3 says no. |

## 3. Findings

Severity:
- **High**: wrong behaviour or a visible defect on a main path.
- **Medium**: inconsistent or dated on a common path.
- **Low**: polish.

### 3.1 Transitions and motion

| id | Sev | file:line | Finding | Fix |
|---|---|---|---|---|
| UP-01 | **High** | `ui/ParleyRoot.kt:178-185` | NavHost transitions are inline lambdas with Compose defaults (`fadeIn()`, `slideInHorizontally { w/6 }`), not the theme's MotionScheme. Forward navigation fades the old screen out while the new one slides in, a muddy double fade. The predictive-back preview is only a slide and fade, with no Material shrink, corner rounding or reveal. | Move the specs into `ParleyMotion` (new `ParleyMotion.navEnter/navExit/navPopEnter/navPopExit(rtlSign)`, read **outside** the NavHost lambdas, because they aren't `@Composable`). Forward: shared-axis X, with the new screen sliding about 30 dp from the end side plus `fadeIn(effects)`, and the old screen `fadeOut(fastEffects)` with a −30 dp slide. Pop: the leaving screen `scaleOut(0.9f)` plus `fadeOut(fastEffects)`, and the screen below `fadeIn(effects)` with `scaleIn(0.95f)`. Keep `rtlSign`. Add a Robolectric test that the root reads the shared specs. |
| UP-02 | **High** | `ui/home/HomeScreen.kt:307` | The tab switch is `AnimatedContent(tab) { fadeIn() togetherWith fadeOut() }`. It uses default specs, and the leaving tab's composition is discarded, so `rememberLazyListState()` in Contacts (`ui/home/ContactsTab.kt:135`), Recents (`ui/home/RecentsTab.kt:215`) and Favourites (`ui/home/FavoritesTab.kt:130`) is lost. Contacts → Recents → Contacts lands back at "A". Material expects navigation-bar destinations to keep their state. **(device)** | Wrap each tab in `rememberSaveableStateHolder().SaveableStateProvider(t.name)` and switch the three states to `rememberSaveable(saver = LazyListState.Saver)` (or hoist them). Use Material's fade-through: `fadeIn(effects) + scaleIn(0.92f, spatial) togetherWith fadeOut(fastEffects)`, from a new `ParleyMotion.fadeThrough()`. |
| UP-03 | Medium | Default specs: `ui/settings/SettingsScreen.kt:172`, `ui/home/HomeHeader.kt:76-81`, `ui/contact/ContactDetailScreen.kt:188`, `ui/contact/ContactEditScreen.kt:465` and `:1339-1340` (fades only), `ui/situations/SituationsUi.kt:234`, `ui/calltime/ReturnToCallChip.kt:60`, `ui/calls/VoicemailInbox.kt:275`, `ui/blocking/BlockingUi.kt:80`, `ui/settings/SettingsComponents.kt:89`, `ui/people/LabelScreens.kt:653`, `ui/calls/NextUpStrip.kt:89` and `ui/timemachine/SyncWatchdogScreens.kt:148` (`animateContentSize()`), `tel/CallWaiting.kt:106` | These transitions don't read `ParleyMotion`, so they move differently from their neighbours. They also differ in what they combine (expand only, fade only, both). | Expand/collapse: `ParleyMotion.expandIn()/collapseOut()`. Swaps: new `ParleyMotion.swap()` (`fadeIn(effects) togetherWith fadeOut(fastEffects)`). `animateContentSize(ParleyMotion.spatial())`. Mechanical. |
| UP-04 | Low | `kit/ParleyFabDefaults.kt:104-108` (alpha on a *spatial* spring), `kit/AlphabetIndexRail.kt:355` (`scaleOut` on an *effects* spec) | The spec roles are swapped. Spatial springs may overshoot, which is wrong for alpha. Effects specs on scale lose the expressive bounce. | Alpha uses effects specs and scale uses spatial specs. |
| UP-05 | Medium | `ui/home/HomeHeader.kt:79-80` | The search swap slides `it / 8` from the right in every language. The NavHost was made RTL-aware, but this wasn't. | Use the same `rtlSign`, or `slideIntoContainer(Start)`. Better: UP-12 (search with predictive back). |
| UP-06 | Medium | `ui/onboarding/OnboardingScreen.kt:136` (`verticalScroll(rememberScrollState())` outside the `when (step)`), `:108` | Onboarding steps hard-cut with no transition. One scroll state is shared across steps, so a step opens at the previous step's scroll offset (for example, after scrolling the permissions page). **(device)** | `AnimatedContent(step)` with shared-axis X (forward from the end side, Back from the start side), a scroll state `key`ed per step, and `PredictiveBackHandler` for Back. |
| UP-07 | Medium | `tel/InCallScreen.kt:494-516` | The call screen's bottom half hard-cuts between ringing controls, ongoing grid, SIM picker and ended cards (answering swaps the whole bottom instantly). Only the keypad swap animates (`:552-556`). | Key an `AnimatedContent` on a small `ControlsPhase` enum (ENDED, BLOCKING, RINGING, SELECT_ACCOUNT, ONGOING) with the same spec as the keypad swap (`fadeIn(fast) + scaleIn(spatial, 0.92f) togetherWith fadeOut(fast)`), moved into `ParleyMotion.swapScaled()` so both use it. Keep `contentAlignment = BottomCenter`. |
| UP-08 | Medium | 28 lists in 26 files use `LazyColumn(Modifier.padding(p))`: `ui/calltime/CallTimeScreen.kt`, `ui/extras/TripScreen.kt`, `ui/cases/CaseListScreen.kt`, `ui/cases/CaseScreen.kt`, `ui/history/{ImportCalls,Insights,NumberHistory,SimSettings}Screen.kt`, `ui/export/ExportScreen.kt`, `ui/birthdays/BirthdaysScreen.kt`, `ui/blocking/{DryRun,Templates,Blocking,SpamLists}Screen.kt`, `ui/settings/{SpeedDial,Privacy}Screen.kt`, `ui/timemachine/TimeMachineScreens.kt`, `ui/health/HealthScreen.kt`, `ui/contact/DuplicatesScreen.kt`, `ui/sync/FolderSyncScreen.kt`, `ui/backup/BackupScreen.kt`, `ui/people/{LabelScreens,PrivacyScreens,SimScreens}.kt`, `ui/people/archive/{ArchiveActions,ArchivedContactScreen}.kt` | Padding the whole container by the Scaffold insets stops content at the navigation bar: a dead band above gesture navigation, which edge-to-edge (enforced at target 36) is meant to remove. `SettingsScaffold` already does it right (`kit/Scaffolds.kt:73-79`). | Add a `kit` helper, `Modifier.scaffoldBody(p)` (top/start/end padding) plus `PaddingValues.scaffoldBottom(p, extra)` for `contentPadding`, or `ParleyListScaffold`. Migrate the 28 call sites mechanically. |
| UP-09 | Low | About 40 `ParleyTopBar` without `scrollBehavior`, for example `ui/settings/PrivacyScreen.kt:71`, `ui/history/InsightsScreen.kt:104`, `ui/backup/BackupScreen.kt:93`, `ui/cases/CaseScreen.kt:128` | The bar doesn't tint when content scrolls under it (Material's "on scroll" container colour), unlike the 11 screens that pass `pinnedScrollBehavior`. | Do this together with UP-08: the same helper takes a `pinnedScrollBehavior()` and wires `nestedScroll`. |
| UP-10 | Low | `kit/Motion.kt` | The file named `Motion.kt` holds only the shared-element plumbing, while motion lives in `ParleyMotion.kt`. | Rename it to `SharedElements.kt` (no API change). |
| UP-11 | Medium | Only `ui/home/ContactsTab.kt:309,315` → `ui/contact/ContactHeader.kt:78,82`. `LocalNavAnimScope` is provided at `ui/ParleyRoot.kt:187` and `ui/contact/ContactNavigation.kt:51` only. | Opening a contact from Recents, Favourites, Circle, Contacts search or the Favourites strip in Contacts gets the generic slide; from the A–Z list the avatar flies. | Add `Modifier.shared("avatar-$id")` and `shared("name-$id", bounds = true)` to the avatar and name in the Favourites tile (`ui/home/FavoritesTab.kt`), the Recents row (saved contacts only), the Circle row and search results. Only one row per contact may carry a key on a screen: skip the Contacts favourites strip when the same contact is in A–Z, or key it `"fav-avatar-$id"` with no match. |
| UP-12 | Medium | `BackHandler` sites listed in §2 | In-app back on custom surfaces has no gesture preview. | Search close (`ui/home/HomeScreen.kt:197`): adopt M3 `SearchBar`, which has predictive back built in (D6), or use `PredictiveBackHandler` to scale the field back to the title. List-detail pane (`ui/home/ListDetail.kt:199`): `PredictiveBackHandler` that scales and fades the pane content by progress. Docked keypad (`ui/home/HomeScreen.kt:196`): drive the existing fold value (`ui/home/KeypadPill.kt:284`) from progress, so it already has a seekable state. Onboarding: UP-06. |

### 3.2 Navigation safety and repetition

| id | Sev | file:line | Finding | Fix |
|---|---|---|---|---|
| UP-13 | **High** | `val back = { nav.popBackStack() }` in `ui/situations/SituationNavigation.kt:23`, `ui/drive/DriveNavigation.kt:19`, `ui/extras/ExtrasNavigation.kt:23`, `ui/history/HistoryNavigation.kt:49`, `ui/blocking/BlockingNavigation.kt:38`, `ui/discover/DiscoverNavigation.kt:51`, `ui/settings/SettingsNavigation.kt:27`, `ui/contact/ContactNavigation.kt:48`, `ui/family/FamilyNavigation.kt:21`, `ui/sync/shared/SharedLabelNavigation.kt:48`, `ui/people/PeopleNavigation.kt:59`, `app/messaging/MessagingNavigation.kt:42`; inline in `ui/qr/QrNavigation.kt:19`, `ui/calls/CallRoundScreen.kt:90`, `ui/calls/ToCallScreen.kt:84`, `ui/timemachine/SyncWatchdogScreens.kt:98` | 16 copies, none guarded. A second tap on the top bar's Back while the first pop animates pops the screen below too. From a first-level page that pops `Home`, leaving an empty NavHost (blank screen). **(device)** Nothing uses `dropUnlessResumed` or `launchSingleTop` here. | Add one `NavController.parleyBack(): () -> Unit` (pop only while `currentBackStackEntry` is RESUMED and `previousBackStackEntry != null`) and `parleyOpen(): (Destination) -> Unit`, in `ui/ParleyNavigation.kt`, and use them in every graph. A Robolectric test: two back calls in the same frame pop once, and Home is never popped. |
| UP-14 | Medium | `val open = { r -> nav.navigate(r) }` in 11 graphs (same files) and `ui/ParleyRoot.kt:193` | Double-tapping a row opens two copies of the same contact or page. Only 3 `launchSingleTop` uses exist in the app. | `parleyOpen` navigates only from a RESUMED entry (the same guard as UP-13), which drops the second tap during the transition. |
| UP-15 | Low | `MainActivity.kt:56,84`, `shortcuts/DialWidget.kt:99,116`, `shortcuts/FavouritesDrawing.kt:217,236`, `calls/CallPromiseActivity.kt:65,80`, `calls/RemindMeActivity.kt:50,61`, `messaging/NumberActionActivity.kt:187,198`, `picker/PickerActivity.kt:73,86`, `tel/InCallActivity.kt:72,131` | 8 activities repeat `enableEdgeToEdge()` plus `ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density)`. `ParleyTheme` already re-applies edge-to-edge (`kit/Theme.kt:193-203`). | Add a `ParleyTheme(look: ThemeLook)` overload, or `ComponentActivity.setParleyContent(look) {}`, in `kit`. Keep the early `enableEdgeToEdge()` before `setContent` (it avoids a first-frame flash), once, inside the helper. |

### 3.3 Repeated UI building blocks

| id | Sev | file:line | Finding | Fix |
|---|---|---|---|---|
| UP-16 | Medium | `ui/blocking/BlockingUi.kt:61-81` (`CollapsibleSection`), `ui/people/LabelScreens.kt:642-655` (`LabelSettingsFold`), `ui/settings/SettingsComponents.kt:67-93` (`AdvancedSection`), `ui/contact/ContactPageSections.kt:137-170` (`FoldHeader`), `ui/discover/ComingFromScreen.kt:77-87`, `ui/calls/VoicemailInbox.kt:275`, `tel/ReputationUi.kt:85` | Seven expandable sections. The chevron rotates in 2 of them and swaps icons instantly in 5. The animation uses `ParleyMotion` in 3 and defaults in 4. Semantics differ: some have `stateDescription` plus `heading()`, Settings uses `onClickLabel` only. Feature-agnostic code reuses the Blocking strings `blk_expanded`/`blk_collapsed`. | Add `kit/Expandable.kt` with `ExpandChevron(expanded)` (rotation on `fastSpatial`), `ExpandableHeader(title, summary, expanded, onToggle, icon, style)` with heading, stateDescription and onClickLabel built in, and `ExpandableBody(expanded)` (`expandIn/collapseOut`). Move "Expanded"/"Collapsed" into `KitStrings`. Migrate the 7 sites. |
| UP-17 | Medium | 23 hand-built ⋮ buttons (IconButton MoreVert + DropdownMenu + state). Top-bar labels: "More options" (`ui/home/HomeHeader.kt:97`, `ui/history/NumberHistoryScreen.kt:190`, `ui/journal/HistoryHubScreen.kt:65`, `ui/cases/CaseListScreen.kt:81`, `ui/cases/CaseScreen.kt:135`), "More" (`ui/contact/ContactMenuActions.kt:58`, `ui/people/LabelScreens.kt:153,485`), "More actions" (`ui/home/SelectionBar.kt:197`, `ui/blocking/BlockingHooks.kt:190`, `ui/people/LabelScreens.kt:550`) | The same control has three spoken names, and the code is repeated 23 times. | Add `kit/OverflowMenu.kt`: `OverflowMenu(label = kitStrings().moreOptions) { close -> … }`. Top bars always read "More options". Per-row menus keep "More for %s" (`label_more_for`, which already exists). Delete `main_more_actions` and `label_more` where they become unused. |
| UP-18 | Low | `ui/home/HomeScreen.kt:356` and `tel/PostCallCard.kt:281` | The same private `MenuItem(text, icon, onClick)` is written twice, with the parameters in a different order. | Add a `ParleyMenuItem(text, icon, destructive = false, onClick)` to `kit/OverflowMenu.kt` (also used by UP-19). |
| UP-19 | Medium | `ui/common/MenuSheets.kt:63-72` (`MenuItems`), `ui/history/RecentsMenu.kt:83` (Clear call history) | Destructive entries (Delete, Delete from history, Clear call history) look like every other item, with no separation. Material: put destructive actions last, after a divider, optionally in `error` colour. They are already last in `ContactMenu`/`SelectionMenu`/`RecentMenu` (`common/ux/ContactMenu…`). | `MenuItems` draws a `HorizontalDivider` before a trailing destructive action and tints its text and icon `error`. Add `isDestructive` to `MenuLabel` (`ui/common/MenuSheets.kt:30`), set by each `…Label` mapper. The same applies in `MenuRows` sheets. |
| UP-20 | Medium | `ui/home/FavoritesTab.kt:200-230`, `ui/home/CombinedSurfaces.kt:285-340` (Reorder favourites sheet), `ui/contact/ContactPageSettings.kt:100-170`, `ui/settings/NavTabsSetting.kt` | Four drag-to-reorder implementations, each with its own `detectDragGestures`, zIndex and TalkBack move actions. Only the contact-page one gives haptics (lift and tick). Only the Favourites grid uses `animateItem`. | Add `kit/Reorder.kt` with `rememberReorderState`, `Modifier.reorderable(state, key)` (lift haptic `GestureThresholdActivate`, `SegmentTick` per swap, elevation, `animateItem`) and `reorderActions(moveUp, moveDown)` semantics. Migrate the 4. |
| UP-21 | Low | `tel/CallButtons.kt:120-146, 204-216, 235-241, 288-304` | Four press-morph implementations in one file (corner, scale or width animated from `collectIsPressedAsState`). | Add a private `rememberPressMorph(source, rest, pressed): State<Dp>` in the same file. After D1, consider M3 `IconToggleButton` shapes (UP-33). |
| UP-22 | Low | Hand-built section titles: `ui/history/PeopleCard.kt:108`, `ui/history/RecentsMenu.kt:150`, `ui/history/QualityCard.kt:82`, `ui/home/RecentsLegend.kt:366`, `ui/home/CombinedSurfaces.kt:226`, `ui/contact/ContactPageSections.kt:157`, `ui/family/FamilySafetyScreens.kt:430`, `tel/DriveUi.kt:69`, `tel/ScamCheckUi.kt:121` | 9 `titleSmall` + `primary` texts that re-implement `ListSectionHeader`/`GroupHeader` (`kit/Rows.kt:184`, `kit/Grouped.kt:69`). Some have no `heading()`. | Replace them with `ListSectionHeader(text, inset = …, trailing = …)`. |
| UP-23 | Low | 1,545 raw `n.dp` in app+telecom vs 659 `Spacing.*`. `spacedBy(8.dp)` ×99, `6.dp` ×71 (off-grid), `18.dp` ×67 (mostly icon sizes). Examples: `ui/settings/SettingsComponents.kt:75` (`32.dp`, `12.dp`), `ui/blocking/BlockingUi.kt:88` (`16.dp`, `6.dp`), `kit/Grouped.kt:38` (`20.dp`, `4.dp`, which duplicate `ParleyShapes.card`/`Spacing.xs`) | Spacing tokens exist but less than a third of spacing uses them. | Mechanical, by area: replace paddings and `spacedBy` with `Spacing.*` in files the work packages already touch. Don't do a repo-wide sweep this release (D9). Fix `segmentShape` defaults to use the tokens. |
| UP-24 | Low | `kit/ParleyFabDefaults.kt` vs `app/ui/home/KeypadPill.kt:235` | `foldSpring()` is the only raw spring outside endless animations (damping 0.85, MediumLow). | Use `ParleyMotion.spatial()` if the feel matches on a device. If not, keep it with a `// reason` comment. |
| UP-25 | Low | 16 `Avatar(…, n.dp)` in rows: `ui/home/CombinedSurfaces.kt`, `ui/home/DndStarredRow.kt`, `ui/family/FamilySafetyScreens.kt`, `ui/extras/SimpleSetupScreens.kt` and others | Person rows ignore the List density setting. | Use `PersonRow` or `avatarSize()` in list rows. Keep the fixed sizes for heroes and editors. |

### 3.4 Haptics and feedback

| id | Sev | file:line | Finding | Fix |
|---|---|---|---|---|
| UP-26 | Low | `ui/home/KeypadTab.kt:133,140` | Dialpad key haptic is `TextHandleMove`. | `HapticFeedbackType.VirtualKey` (Compose 1.8+), which matches the system keyboard and dialer. |
| UP-27 | Low | `kit/Rows.kt:66-84` (`SwitchRow`), `kit/SplitSwitchRow.kt` | Switch rows give no haptic. Android 14+ system switches tick on toggle. | `ToggleOn`/`ToggleOff` in `SwitchRow`/`SplitSwitchRow` (one place covers all 65 `SwitchRow` uses). Reorder haptics are in UP-20. |

### 3.5 Option placement

The 7-item rule holds everywhere (`common/ux/ContactMenu.kt` `MENU_LIMIT`, tested). Home ⋮ (`ui/home/HomeScreen.kt:365-413`) keeps the tab's own actions first, then Tools and Settings. Contact page: Star, Edit, ⋮ (`ui/contact/ContactMenuActions.kt:49-58`). Adding people lives on the add button. These are sound. The moves below are the ones worth making:

| id | Sev | Where | Before → After | Rationale |
|---|---|---|---|---|
| UP-28 | Medium | `common/ux/Capabilities.kt` (Tools catalogue) | **Add several numbers** and **Make a contact from pasted text**: "Message without saving" → "Never lose a contact". **Scan a contact's QR code**: "Know who's calling" → "Never lose a contact". | The group names say the opposite of what these rows do: they *save* contacts. Someone looking for "add from QR" scans "Never lose a contact", not "Know who's calling". It keeps MSG to the 4 message-only rows. Update `CapabilityCatalog` tests and the TESTING checks for Tools. |
| UP-29 | Low | Same file | "Calls that work better" holds 10 rows (the largest group): Simple mode, Your car, Rescue call, Speed dial, Phone menus, Plan minutes, Case files, Call quality, Talk-time, Add a helper. | Optional (D8): mark at most 4 `featured` (Speed dial, Simple mode, Your car, Rescue call) so the rest fold under "n more", as the other groups already do. Don't add a new group. |
| UP-17 (placement) | Medium | All top-bar ⋮ | "More options" / "More" / "More actions" → "More options" | One name for one control (GLOSSARY). |
| UP-19 (placement) | Medium | Contact ⋮ Delete, Recents ⋮ Clear call history, call sheet Delete from history, selection ⋮ Delete | Unmarked last item → last, after a divider, `error` tint | Destructive actions guarded by position and colour, as well as by the existing confirm and Undo. |
| UP-02 (placement of state) | High | Home tabs | Scroll position reset on tab switch → kept | Frequency of use: Contacts is visited repeatedly in a session. |

Recommendations to **keep as is** (checked, no change):
- **Contacts ⋮ › Select all** starts a selection. It is also on the selection bar, which is right: an entry point and a control.
- **Recents ⋮ › Blocking & screening**: after a spam call this is the shortest path. Settings keeps its row too (one destination, two entries, and the README rule allows a tab shortcut).
- **Keypad / docked Recents › Speed dial** as a top-bar icon: frequent for keypad users.
- **Contact page Star** in the top bar matches Google Contacts and Samsung. **Share…** stays a ⋮ group (less frequent than call, message or edit).
- **Settings** keeps preferences only, and tools open from Tools (`docs/SETTINGS.md`). The structure is good. The order of categories is D2.

### 3.6 Modern Android adoptions (value vs effort)

| id | Adoption | Effort | Value | Note |
|---|---|---|---|---|
| UP-01/02 | Material shared-axis, fade-through and predictive-pop transitions from `ParleyMotion` | S | High | Biggest visible change for the least code. |
| UP-11 | Shared avatar and name from every person list | S–M | Medium | Plumbing exists. |
| UP-12 | Predictive back on search, pane, docked keypad and onboarding | M | Medium | Android 16 users see the preview everywhere else. |
| UP-30 | `LoadingIndicator` (expressive) for short indeterminate waits: about 40 `CircularProgressIndicator`, for example in importers and lookups | S | Low–Med | Still `@ExperimentalMaterial3ExpressiveApi` in 1.5.0-beta01. Keep `CircularProgressIndicator` for determinate progress. Do it after D1. |
| UP-31 | Expressive menus: `DropdownMenuGroup` labels for the ⋮ "tab items / Tools·Settings" split (`ui/home/HomeScreen.kt:409` uses a bare divider), `SelectableDropdownMenuItem` for choice menus (`MenuRow`) | S | Medium | Needs D1 (alpha23/alpha27). |
| UP-32 | `HorizontalFloatingToolbar` for selection mode (Contacts and Recents) instead of the top-bar action strip | M | Medium | D4: changes where the selection actions are. |
| UP-33 | `IconToggleButton`/`ToggleButton` shapes for the in-call grid, replacing the custom morphs (UP-21) | M | Low | The current custom morph already looks expressive. Only worth it after D1. |
| UP-34 | `ShortNavigationBar` / `WideNavigationRail` for Home | S–M | Low–Med | `WideNavigationRail` can expand to labels on tablets. Check their stability in the version D1 picks. |
| UP-35 | `LargeFlexibleTopAppBar` with subtitle on settings pages (category description) | S | Low | After D1. |
| UP-36 | Window size from `LocalWindowInfo.current.containerSize` instead of `LocalConfiguration.screenWidthDp` (`ui/home/ListDetail.kt:74-77`) | S | Low | Correct in freeform and desktop windows on Android 16. |
| UP-37 | `NotificationCompat.ProgressStyle` for backup, export and import jobs (`app/jobs/JobNotices.kt:54`) on Android 16 | S | Low | No permission needed. Do not request promotion (D3). |
| — | Carousel, SplitButton, ButtonGroup | — | — | No surface needs them. The Recents filter chips and SegmentedButtons (9 files) are the right controls. Not recommended. |

## 4. Fix plan

### (a) Safe mechanical fixes (no behaviour or placement change)
1. `ParleyMotion`: add `navEnter/navExit/navPopEnter/navPopExit(rtl)`, `fadeThrough()`, `swap()` and `swapScaled()`. Use them at `ui/ParleyRoot.kt:181-184`, `ui/home/HomeScreen.kt:307`, `ui/settings/SettingsScreen.kt:172`, `ui/home/HomeHeader.kt:76-81`, `tel/InCallScreen.kt:552-556`, `ui/home/ListDetail.kt:209-211` (UP-01/02/03/05/07).
2. Replace default specs at every UP-03 site, and swap the UP-04 spec roles.
3. Add `NavController.parleyBack()/parleyOpen()` and replace all 16 graphs' local lambdas (UP-13/14).
4. Expandable kit and migration of the 7 sites (UP-16). Overflow kit with one label and migration of the 23 sites (UP-17/18). Destructive menu styling (UP-19).
5. `scaffoldBody`/`scaffoldBottom` helper with pinned scroll, migrating 28 lists and their bars (UP-08/09).
6. Reorder kit and migration of the 4 reorder UIs (UP-20). Press-morph helper (UP-21). Section headers (UP-22). Avatar sizes (UP-25). Haptic types (UP-26/27). Rename `Motion.kt` (UP-10). Activity theme helper (UP-15).

### (b) Placement moves
UP-28 (Tools regrouping, before and after above), UP-17 (one ⋮ name), UP-19 (destructive last, set apart), UP-02 (keep tab state). UP-29 only if the owner says yes (D8).

### (c) Modern-Android adoptions
- Now: UP-01, 02, 11, 12, 26, 27, 36, 37.
- After D1 (library upgrade): UP-30, 31, 35. UP-33 is optional.
- Owner call: UP-32 (D4), UP-34.

### (d) Owner decisions

| id | Question | Recommendation |
|---|---|---|
| D1 | Upgrade `material3` 1.5.0-alpha14 → 1.5.0-beta01, `navigation` 2.9.6 → 2.10.2 and the Compose BOM? Breaking changes: `Slider` signature (alpha28/29), removed `pinnedScrollBehavior` overloads and `LocalMotionScheme` (alpha27), `SplitButtonLayout` → `SplitButton`. | **Yes, as its own last package (WP8)** after the others merge. It unlocks stable expressive menus, toggle buttons, flexible bars and swipe-edge-aware predictive pops. Watch the APK budget (12 MiB). |
| D2 | Settings home order: Appearance and Layout first today (`ui/settings/SettingsScreen.kt:144-148`). A phone app's most changed settings are Calls, Blocking, Contacts. | Move Calls and Blocking first, Appearance and Layout after Notifications. It's a habit change for current users, so it's your call. |
| D3 | Live Updates (promoted call and job notifications) need `POST_PROMOTED_NOTIFICATIONS`. | **No.** The permission set is frozen. CallStyle already gets the status-bar call chip. |
| D4 | Selection actions: top-bar strip (today) → bottom floating toolbar (M3 Expressive, Google Files/Photos style)? | Optional for 7.x. It improves reach on tall phones, but it is a visible relayout of Contacts and Recents selection. |
| D6 | Home search: keep the custom field in the top bar, or adopt M3 `SearchBar` / `AppBarWithSearch`, which has predictive back built in (`AppBarWithSearch` is experimental again since alpha24)? | Keep the custom field and add `PredictiveBackHandler` (UP-12) now. Revisit after D1. |
| D7 | Replace the own list-detail with `ListDetailPaneScaffold` (material3-adaptive)? | **No.** The own one is tested, keeps per-tab stacks and view-model stores, and adds no dependency. Just add predictive back (UP-12). |
| D8 | Fold "Calls that work better" in Tools to 4 featured rows (UP-29)? | Yes. It's low risk. |
| D9 | Repo-wide `Spacing` token sweep? | No. Do it only in files touched by this release (UP-23). |

## 5. Work packages (parallel, by file area)

Each package owns the files listed. Shared `kit` additions go in **new files** named per package, so the packages don't conflict. Only WP1 edits `kit/ParleyMotion.kt`, and the other packages use only the specs that already exist (`expandIn`, `collapseOut`, `spatial`, `effects` and so on) until WP1 merges.

| WP | Scope (items) | Files owned | Tests |
|---|---|---|---|
| **WP1 Motion and navigation** | UP-01, 03 (non-Home sites), 04, 10, 13, 14, 15, 24 | `kit/ParleyMotion.kt`, `kit/Motion.kt`→`kit/SharedElements.kt`, `kit/Theme.kt` (theme helper), `kit/ParleyFabDefaults.kt`, `kit/AlphabetIndexRail.kt`, `ui/ParleyRoot.kt`, `ui/ParleyNavigation.kt`, every `*Navigation.kt` graph listed in UP-13, `ui/calls/{CallRoundScreen,ToCallScreen}.kt` (graph lines only), `ui/timemachine/SyncWatchdogScreens.kt`, the 8 activities in UP-15, `ui/situations/SituationsUi.kt`, `ui/calltime/ReturnToCallChip.kt`, `ui/calls/NextUpStrip.kt`, `ui/contact/ContactDetailScreen.kt:188`, `ui/contact/ContactEditScreen.kt` (UP-03 lines), `ui/settings/SettingsScreen.kt` | A Robolectric test: a double back pops once and never pops Home. A double open navigates once. A unit test that `ParleyMotion` nav specs flip with RTL. |
| **WP2 Home shell** | UP-02, 05, 11 (list side), 12 (search, pane, keypad), 26, 36 | `ui/home/HomeScreen.kt`, `ui/home/HomeHeader.kt`, `ui/home/ListDetail.kt`, `ui/home/ContactsTab.kt`, `ui/home/RecentsTab.kt`, `ui/home/KeypadTab.kt`, `ui/home/KeypadPill.kt`, `ui/circle/CircleTab.kt` (shared keys) | A Compose test: switch tabs and come back, and Contacts keeps its first visible item. Predictive back on the pane pops one entry. |
| **WP3 Expandables, menus and headers** | UP-16, 17, 18, 19, 22 | New `kit/Expandable.kt`, new `kit/OverflowMenu.kt`, `kit/KitStrings.kt`, `ui/common/MenuSheets.kt`, `ui/blocking/BlockingUi.kt`, `ui/blocking/BlockingHooks.kt`, `ui/settings/SettingsComponents.kt`, `ui/people/LabelScreens.kt`, `ui/contact/ContactPageSections.kt`, `ui/contact/ContactMenuActions.kt`, `ui/discover/ComingFromScreen.kt`, `ui/calls/VoicemailInbox.kt`, `ui/history/{NumberHistoryScreen,PeopleCard,RecentsMenu,QualityCard}.kt`, `ui/journal/HistoryHubScreen.kt`, `ui/cases/{CaseListScreen,CaseScreen}.kt`, `ui/home/SelectionBar.kt`, `ui/home/RecentsLegend.kt`, `ui/circle/CircleTimeline.kt`, `ui/health/DeadNumbersSection.kt`, `ui/temporary/TemporaryContacts.kt`, `ui/recall/RecallSection.kt`, `app/messaging/ReachSheet.kt`, `ui/family/FamilySafetyScreens.kt`, `tel/ReputationUi.kt`, `tel/DriveUi.kt`, `tel/ScamCheckUi.kt`, `tel/PostCallCard.kt`, `tel/MenuMemoryUi.kt`, `tel/IncomingControls.kt` (⋮ only) | Kit tests: the header exposes heading and stateDescription. A destructive item renders after a divider. Every top-bar ⋮ reads "More options" (a grep-style unit test over the sources, as other kit rules do). |
| **WP4 Edge-to-edge lists** | UP-08, 09, 23 (in owned files) | New `kit/ScaffoldBody.kt` and the UP-08 screen files **except** `ui/people/LabelScreens.kt`, `ui/blocking/BlockingScreen.kt`, `ui/history/NumberHistoryScreen.kt` and `ui/cases/*` (WP3 owns those and applies the helper there after WP4's kit file lands, or WP4 hands over a one-line patch) | Robolectric: the last row of a migrated list is reachable above a 48 dp navigation bar inset, and the list draws under it. |
| **WP5 Call screen** | UP-07, 21 | `tel/InCallScreen.kt`, `tel/CallButtons.kt`, `tel/CallWaiting.kt` | A Compose test: the RINGING → ONGOING phase change keeps one controls node (no duplicate End button mid-transition, checked once the animation is done). |
| **WP6 Reorder and haptics** | UP-11 (Favourites tile), 20, 25, 27 | New `kit/Reorder.kt`, `kit/Rows.kt`, `kit/SplitSwitchRow.kt`, `ui/home/FavoritesTab.kt`, `ui/home/CombinedSurfaces.kt`, `ui/contact/ContactPageSettings.kt`, `ui/settings/NavTabsSetting.kt`, `ui/home/DndStarredRow.kt`, `ui/extras/SimpleSetupScreens.kt` | Unit test for the reorder index maths. Semantics test for the move actions on each of the 4. |
| **WP7 Placement and onboarding** | UP-06, 28, 29 (if D8), 37, D2 (if yes) | `common/ux/Capabilities.kt` and its tests, `ui/discover/CapabilitiesScreen.kt`, `ui/onboarding/OnboardingScreen.kt`, `app/jobs/JobNotices.kt`, `ui/settings/SettingsScreen.kt:144-148` (only if D2, coordinate with WP1, which owns the file's line 172) | Catalogue test: each row's job. Onboarding test: a new step starts at scroll 0. |
| **WP8 Library upgrade** (D1, last) | D1, then UP-30, 31, 33, 35 | `gradle/libs.versions.toml` and the compile fixes it forces | The full verify plus `checkReleaseApkSize`. |

Order: WP1–WP7 in parallel. WP3 and WP4 depend on nothing from WP1. WP8 starts after the merge.

## 6. Manual checks to add (TESTING.md §54, numbers given by the coordinator)
- Contacts: scroll to "M", switch to Recents and back. The list is still at "M".
- Open a contact from Favourites, Recents and search. The avatar flies into the header. Swipe back slowly: the page shrinks and rounds, and Contacts shows beneath it. Cancel the swipe, and it settles back.
- Tap Back twice quickly on any first-level page. You land on Home, never on a blank screen. Double-tap a contact row: one page opens.
- Arabic: forward pages and search slide in from the left.
- Remove animations on: every transition is instant, and nothing pulses.
- Answer a call: the controls change with a short scale and fade, not a jump.
- Every top bar ⋮ is announced "More options". Delete is last, red, and after a line.
- A long list on a gesture-navigation phone scrolls under the transparent bar, and its last row clears it.
- Onboarding: scroll the permissions page, then Continue. The next step starts at its top.
