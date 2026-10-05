package app.parley.ui

import android.Manifest
import android.content.ContentValues
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import android.graphics.Bitmap
import android.net.Uri
import app.parley.common.ux.CallScreenBackground
import java.io.File
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import app.parley.AppViewModel
import app.parley.ParleyApp
import app.parley.common.AnswerGesture
import app.parley.common.ThemeMode
import app.parley.common.Verification
import app.parley.common.ux.RecentsStyle
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.telecom.AudioUi
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.ui.InCallScreen
import app.parley.ui.circle.CircleTab
import app.parley.ui.common.CoachMarks
import app.parley.ui.common.LocalCoachMarks
import app.parley.ui.home.ContactsTab
import app.parley.ui.home.FavoritesTab
import app.parley.ui.home.KeypadDock
import app.parley.ui.home.KeypadTab
import app.parley.ui.home.LocalRecentsStyle
import app.parley.ui.home.RecentsTab
import app.parley.ui.home.HomeScreen
import app.parley.ui.home.Centred
import app.parley.ui.home.rememberWindowLayout
import app.parley.ui.contact.ContactDetailScreen
import app.parley.common.StartTab
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Each home tab and the call screen draw without crashing in light, dark (black for OLED) and with the largest
 * font in a right-to-left layout, over a few contacts and calls, with the real app (fake providers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class UiSmokeTest {
    // Composition coroutines resume on the test thread, as they would on the main thread in the app. With the rule's
    // default (unconfined) dispatcher, an effect waiting on background work (a load on IO, a state flow set from the
    // app's Default scope) carries on in that background thread, and can drive a frame there and touch the views.
    @get:Rule val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private var adaId = 0L

    /** How a screen is drawn: theme, black surfaces, font scale and direction. */
    private enum class Look(val mode: ThemeMode, val amoled: Boolean = false, val fontScale: Float = 1f, val rtl: Boolean = false) {
        LIGHT(ThemeMode.LIGHT),
        DARK(ThemeMode.DARK, amoled = true),
        LARGE_FONT_RTL(ThemeMode.LIGHT, fontScale = 2f, rtl = true),
    }

    @Before fun setUp() {
        // The empty activity the screens are drawn in isn't in the app's manifest: register it for this test.
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        // The app schedules its upkeep workers after start-up.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
        )
        val c = app.container
        runBlocking {
            val details = ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE)))
            val ada = c.contacts.save(null, details, null, null, false)!!
            adaId = ada.contactId
            c.contacts.setStarred(ada.contactId, true)
            c.vault.save(null, ContactDetails(given = "Grace", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))))
        }
        val now = System.currentTimeMillis()
        listOf(
            Triple("+442079460000", CallLog.Calls.MISSED_TYPE, 0L),
            Triple("+44 20 7946 0123", CallLog.Calls.INCOMING_TYPE, 95L),
            Triple("+44 20 7946 0123", CallLog.Calls.OUTGOING_TYPE, 30L),
        ).forEachIndexed { i, (n, type, length) ->
            app.contentResolver.insert(
                CallLog.Calls.CONTENT_URI,
                ContentValues().apply {
                    put(CallLog.Calls.NUMBER, n)
                    put(CallLog.Calls.DATE, now - (i + 1) * 60_000L * 90)
                    put(CallLog.Calls.TYPE, type)
                    put(CallLog.Calls.DURATION, length)
                },
            )
        }
        c.startFull()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After fun tearDown() {
        scenario.close()
        app.container.scope.cancel()
    }

    /** Draws [content] as Parley's root does (theme, avatar, tips, Recents style, the app's view model) in [look]. */
    private fun show(look: Look, style: RecentsStyle = RecentsStyle.RICH, content: @Composable (AppViewModel) -> Unit) {
        val vm = ViewModelProvider(activity)[AppViewModel::class.java]
        activity.setContent {
            ParleyTheme(mode = look.mode, amoled = look.amoled, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, look.fontScale),
                    LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalCoachMarks provides remember { CoachMarks(vm.c.ux) },
                    LocalRecentsStyle provides style,
                    LocalAppViewModel provides vm,
                ) {
                    Surface { content(vm) }
                }
            }
        }
        compose.waitForIdle()
        // Home may also show a tip in a popup of its own: a second root.
        assertTrue(compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty())
    }

    /** Waits until [text] is on screen (the lists load off the main thread). */
    private fun shows(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text, substring = true)[0].assertExists()
    }

    private val noRoute: (Destination) -> Unit = {}

    // ---------------------------------------------------------------- Recents

    @Test fun recents_light() {
        show(Look.LIGHT) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_dark() {
        show(Look.DARK) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_simple_style() {
        show(Look.LIGHT, RecentsStyle.SIMPLE) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_cards_light() {
        show(Look.LIGHT, RecentsStyle.CARDS) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_cards_dark() {
        show(Look.DARK, RecentsStyle.CARDS) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun recents_cards_large_font_rtl() {
        show(Look.LARGE_FONT_RTL, RecentsStyle.CARDS) { RecentsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    // ---------------------------------------------------------------- Contacts

    @Test fun contacts_light() {
        show(Look.LIGHT) { ContactsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun contacts_dark() {
        show(Look.DARK) { ContactsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun contacts_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { ContactsTab(it, noRoute) }
        shows("Ada Lovelace")
    }

    // ---------------------------------------------------------------- Favourites

    @Test fun favourites_light() {
        show(Look.LIGHT) { FavoritesTab(it, noRoute) }
        shows("Ada")
    }

    @Test fun favourites_dark() {
        show(Look.DARK) { FavoritesTab(it, noRoute) }
        shows("Ada")
    }

    @Test fun favourites_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { FavoritesTab(it, noRoute) }
        shows("Ada")
    }

    // ---------------------------------------------------------------- Keypad

    @Test fun keypad_light() {
        show(Look.LIGHT) { KeypadTab(it, noRoute) }
    }

    @Test fun keypad_dark() {
        show(Look.DARK) { KeypadTab(it, noRoute) }
    }

    @Test fun keypad_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { KeypadTab(it, noRoute) }
    }

    @Test fun keypad_with_recents_docked() {
        show(Look.LIGHT, RecentsStyle.CARDS) { vm ->
            KeypadTab(vm, noRoute, dock = KeypadDock(expanded = false, onExpandedChange = {}) { RecentsTab(vm, noRoute) })
        }
        shows("Ada Lovelace")
    }

    // ---------------------------------------------------------------- Contact page

    @Test fun contact_page_light() {
        show(Look.LIGHT) { ContactDetailScreen(it, adaId, back = {}, open = noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun contact_page_dark() {
        show(Look.DARK) { ContactDetailScreen(it, adaId, back = {}, open = noRoute) }
        shows("Ada Lovelace")
    }

    @Test fun contact_page_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { ContactDetailScreen(it, adaId, back = {}, open = noRoute) }
        shows("Ada Lovelace")
    }

    // One dialog at a time: ⋮ › Delete asks, and Cancel closes the question without deleting.
    @Test fun contact_page_menu_opens_its_dialog_and_cancel_closes_it() {
        show(Look.LIGHT) { ContactDetailScreen(it, adaId, back = {}, open = noRoute) }
        shows("Ada Lovelace")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onAllNodesWithText("Delete")[0].performClick()
        shows("Delete Ada Lovelace?")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Delete Ada Lovelace?").fetchSemanticsNodes().isEmpty())
        shows("Ada Lovelace")
    }

    // ---------------------------------------------------------------- big screens

    private fun home(look: Look, tab: StartTab, opened: MutableList<Destination>) {
        show(look) { vm -> HomeScreen(vm, tabRequest = null, onTabRequestHandled = {}, initialTab = tab, open = { opened += it }) }
    }

    private fun hasText(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Config(qualifiers = TABLET)
    @Test
    fun tablet_contacts_open_the_page_beside_the_list() {
        val opened = mutableListOf<Destination>()
        home(Look.LIGHT, StartTab.CONTACTS, opened)
        shows("Choose someone to see their page here")
        compose.onAllNodesWithText("Ada Lovelace")[0].performClick()
        // The page opens in the pane (its Edit button is there), not as a page over Home; the list stays.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(opened.isEmpty())
        assertTrue(!hasText("Choose someone to see their page here"))
        // The open contact is marked in the list.
        compose.waitUntil(10_000) { compose.onAllNodes(isSelected()).fetchSemanticsNodes().isNotEmpty() }
        // Back closes the page and leaves the list.
        activity.onBackPressedDispatcher.onBackPressed()
        shows("Choose someone to see their page here")
    }

    @Config(qualifiers = TABLET)
    @Test
    fun tablet_contacts_dark_large_font_rtl() {
        home(Look.LARGE_FONT_RTL, StartTab.CONTACTS, mutableListOf())
        shows("Ada Lovelace")
        shows("Choose someone to see their page here")
    }

    @Config(qualifiers = TABLET)
    @Test
    fun tablet_recents_have_a_detail_pane() {
        home(Look.DARK, StartTab.RECENTS, mutableListOf())
        shows("Ada Lovelace")
        shows("Choose a call to see more about it here")
    }

    @Config(qualifiers = TABLET)
    @Test
    fun tablet_keypad_and_favourites_draw() {
        home(Look.LIGHT, StartTab.KEYPAD, mutableListOf())
        // Favourites as Home draws them on a wide screen (centred, at most their width).
        show(Look.DARK) { vm -> Centred(rememberWindowLayout().contentMaxDp) { FavoritesTab(vm, noRoute) } }
        shows("Ada")
    }

    // Phones are unchanged: a contact opens as its own page.
    @Config(qualifiers = PHONE)
    @Test
    fun phone_contacts_open_the_page_on_its_own() {
        val opened = mutableListOf<Destination>()
        home(Look.LIGHT, StartTab.CONTACTS, opened)
        shows("Ada Lovelace")
        assertTrue(!hasText("Choose someone to see their page here"))
        compose.onAllNodesWithText("Ada Lovelace")[0].performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(Routes.Contact(adaId), opened.first())
    }

    // ---------------------------------------------------------------- Circle

    @Test fun circle_light() {
        show(Look.LIGHT) { CircleTab(it, noRoute, query = "") }
    }

    @Test fun circle_dark() {
        show(Look.DARK) { CircleTab(it, noRoute, query = "") }
    }

    @Test fun circle_large_font_rtl() {
        show(Look.LARGE_FONT_RTL) { CircleTab(it, noRoute, query = "") }
    }

    // ---------------------------------------------------------------- call screen

    private fun call(state: CallState, incoming: Boolean) = CallUi(
        id = "call-1", state = state, number = "+44 20 7946 0000", hidden = false, name = "Ada Lovelace", label = "Mobile", photoUri = null,
        contactId = 1L, incoming = incoming, connectTimeMillis = if (state == CallState.ACTIVE) System.currentTimeMillis() - 65_000 else 0L,
        isConference = false, children = emptyList(), canHold = true, canMerge = false, canSwap = false, canMute = true, canSeparate = false,
        canDisconnectChild = false, canRespondViaText = incoming, accountLabel = null, verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = false,
    )

    private fun callScreen(look: Look, state: CallState, incoming: Boolean) {
        show(look) {
            InCallScreen(
                calls = listOf(call(state, incoming)), audio = AudioUi(), ended = null, answerGesture = AnswerGesture.SWIPE,
                quickReplies = listOf("Can't talk now"), keypadOpen = false, onKeypad = {}, onAddCall = {}, onOpenContact = {},
            )
        }
        shows("Ada Lovelace")
    }

    @Test fun call_screen_ringing_light() = callScreen(Look.LIGHT, CallState.RINGING, incoming = true)

    @Test fun call_screen_ringing_dark() = callScreen(Look.DARK, CallState.RINGING, incoming = true)

    @Test fun call_screen_ringing_large_font_rtl() = callScreen(Look.LARGE_FONT_RTL, CallState.RINGING, incoming = true)

    @Test fun call_screen_in_call_light() = callScreen(Look.LIGHT, CallState.ACTIVE, incoming = false)

    @Test fun call_screen_in_call_dark() = callScreen(Look.DARK, CallState.ACTIVE, incoming = false)

    @Test fun call_screen_in_call_large_font_rtl() = callScreen(Look.LARGE_FONT_RTL, CallState.ACTIVE, incoming = false)

    // ---------------------------------------------------------------- poster

    /** The ringing screen in the Poster style, for a caller whose call-screen picture is [backgroundUri]. */
    private fun poster(look: Look, backgroundUri: String) {
        show(look) {
            InCallScreen(
                calls = listOf(call(CallState.RINGING, incoming = true).copy(backgroundUri = backgroundUri)), audio = AudioUi(), ended = null,
                answerGesture = AnswerGesture.SWIPE, quickReplies = emptyList(), keypadOpen = false, onKeypad = {}, onAddCall = {},
                onOpenContact = {}, background = CallScreenBackground.POSTER,
            )
        }
        shows("Ada Lovelace")
    }

    private fun photoShown() = compose.onAllNodesWithTag(CALLER_PHOTO, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pictureFile(): File {
        val f = File(app.cacheDir, "poster.png")
        val bmp = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 90, 40)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    // A phone in portrait: the poster needs the one-column layout with room above the controls.
    @Config(qualifiers = PHONE)
    @Test
    fun call_screen_poster_light() {
        poster(Look.LIGHT, Uri.fromFile(pictureFile()).toString())
        // The picture is the caller: once it has decoded, the photo gives way to the large name over it.
        compose.waitUntil(10_000) { !photoShown() }
    }

    // A phone in portrait: the poster needs the one-column layout with room above the controls.
    @Config(qualifiers = PHONE)
    @Test
    fun call_screen_poster_dark_large_font_rtl() {
        poster(Look.LARGE_FONT_RTL, Uri.fromFile(pictureFile()).toString())
        compose.waitUntil(10_000) { !photoShown() }
    }

    // A phone in portrait: the poster needs the one-column layout with room above the controls.
    @Config(qualifiers = PHONE)
    @Test
    fun call_screen_poster_with_a_picture_that_cannot_be_read_keeps_the_classic_layout() {
        poster(Look.DARK, Uri.fromFile(File(app.cacheDir, "deleted.png")).toString())
        // Give the decode time to fail: the photo, its ringing frame and time ring stay.
        repeat(20) {
            compose.waitForIdle()
            Thread.sleep(20)
        }
        compose.onNodeWithTag(CALLER_PHOTO, useUnmergedTree = true).assertExists()
    }

    private companion object {
        /** The caller's photo on the call screen (CallerHeader's tag). */
        const val CALLER_PHOTO = "caller-photo"

        const val PHONE = "w411dp-h891dp-port"

        // A 10-inch tablet held sideways: list and detail side by side.
        const val TABLET = "w1280dp-h800dp-land"
    }
}
