package app.parley

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.SettingsCategory
import app.parley.common.StartTab
import app.parley.data.ContactDetails
import app.parley.messaging.MessagingRoutes
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.contact.ContactPageRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.journal.HistoryTab
import app.parley.ui.parleyGraph
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.qr.QrRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every way into the app still arrives where it did before the routes became typed: `parley://` links, `tel:` links,
 * launcher shortcuts, the Quick Settings tile, notification actions and other apps' intents resolve to the same
 * places, and every destination is registered in the real graph with its arguments intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NavigationRoutesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        nav = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
        }
        nav.graph = nav.createGraph(startDestination = Routes.Home) {
            composable<Routes.Home> {}
            parleyGraph(nav)
        }
    }

    private fun resolve(action: String, data: Uri? = null, type: String? = null, extras: Intent.() -> Unit = {}): IntentTarget? {
        val i = Intent(action)
        // setData() and setType() each clear the other.
        when {
            data != null && type != null -> i.setDataAndType(data, type)
            data != null -> i.data = data
            type != null -> i.type = type
        }
        i.extras()
        return IntentRoutes.resolve(i) { null }
    }

    private fun routeOf(t: IntentTarget?): Destination = (t?.event as? NavEvent.Route)?.route ?: error("no route in $t")

    /** Navigates to [d] in the real graph and checks it arrived there. */
    private fun opens(d: Destination) {
        nav.navigate(d)
        val here = nav.currentBackStackEntry?.destination
        assertNotNull(here)
        assertTrue("$d is registered", here!!.hasRoute(d::class))
    }

    @Test fun parleyLinksKeepWorking() {
        val qr = Uri.parse("parley://qr?v=1&d=abc")
        assertEquals(NavEvent.SecureQr(qr), resolve(Intent.ACTION_VIEW, qr)?.event)

        val simple = Uri.parse("parley://simple?v=1&d=abc")
        val s = resolve(Intent.ACTION_VIEW, simple)
        assertEquals(simple, s?.simpleSetup)
        assertEquals(ExtrasRoutes.SimpleImport, routeOf(s))
        opens(routeOf(s))

        val template = Uri.parse("parley://template?d=abc")
        val t = resolve(Intent.ACTION_VIEW, template)
        assertEquals(template, t?.template)
        assertEquals(BlockingRoutes.Templates, routeOf(t))
        opens(routeOf(t))
    }

    @Test fun dialLinksOpenTheKeypadOrRecents() {
        assertEquals(NavEvent.Tab(StartTab.KEYPAD, dial = "+15551234567"), resolve(Intent.ACTION_DIAL, Uri.parse("tel:+15551234567"))?.event)
        assertEquals(NavEvent.Tab(StartTab.KEYPAD, dial = ""), resolve(Intent.ACTION_DIAL)?.event)
        assertEquals(NavEvent.Tab(StartTab.RECENTS), resolve(Intent.ACTION_VIEW, type = "vnd.android.cursor.dir/calls")?.event)
        assertEquals(NavEvent.Tab(StartTab.RECENTS), resolve(Intent.ACTION_CALL_BUTTON)?.event)
        assertEquals(NavEvent.Tab(StartTab.KEYPAD, dial = ""), resolve(IntentRoutes.ACTION_ADD_CALL)?.event)
    }

    @Test fun shortcutsTilesAndSettingsLinksOpenTheirScreens() {
        val expected = mapOf(
            Intent.ACTION_APPLICATION_PREFERENCES to Routes.Settings,
            IntentRoutes.ACTION_OPEN_BACKUP to Routes.Backup,
            IntentRoutes.ACTION_OPEN_BLOCKING to Routes.Blocking,
            IntentRoutes.ACTION_BULK_ADD to MessagingRoutes.BulkAdd,
            IntentRoutes.ACTION_SCAN_QR to QrRoutes.Scan,
        )
        for ((action, dest) in expected) {
            assertEquals(action, dest, routeOf(resolve(action)))
            opens(dest)
        }
        // The same actions as MainActivity's constants, which notifications and shortcuts use.
        assertEquals(MainActivity.ACTION_SCAN_QR, IntentRoutes.ACTION_SCAN_QR)
        assertEquals(MainActivity.ACTION_SHOW_CALLER, IntentRoutes.ACTION_SHOW_CALLER)
    }

    @Test fun notificationActionsKeepTheirExtras() {
        assertEquals(NavEvent.Tab(StartTab.CIRCLE), resolve(IntentRoutes.ACTION_SHOW_CIRCLE)?.event)
        val missed = resolve(IntentRoutes.ACTION_SHOW_MISSED)
        assertEquals(NavEvent.Tab(StartTab.RECENTS, missedOnly = true), missed?.event)
        assertTrue(missed!!.missedSeen)

        val byId = resolve(IntentRoutes.ACTION_SHOW_CALLER) { putExtra(IntentRoutes.EXTRA_CONTACT_ID, 42L) }
        assertEquals(NavEvent.Contact(42), byId?.event)
        val contact = Routes.forEvent(byId!!.event!!)!!
        opens(contact)
        assertEquals(42L, nav.currentBackStackEntry!!.toRoute<Routes.Contact>().id)

        val byNumber = resolve(IntentRoutes.ACTION_SHOW_CALLER) { putExtra(IntentRoutes.EXTRA_NUMBER, "+44 20 7946 0000") }
        assertEquals(NavEvent.History("+44 20 7946 0000"), byNumber?.event)
        assertNull(resolve(IntentRoutes.ACTION_SHOW_CALLER))

        val block = resolve(IntentRoutes.ACTION_POST_CALL) {
            putExtra(IntentRoutes.EXTRA_NUMBER, "+1 555 0100")
            putExtra(IntentRoutes.EXTRA_POST_CALL_ACTION, "BLOCK")
        }
        val rule = routeOf(block)
        assertEquals(BlockingRoutes.rule(0, RuleKind.BLOCK, RuleType.EXACT, "+1 555 0100"), rule)
        opens(rule)
        val opened = nav.currentBackStackEntry!!.toRoute<BlockingRoutes.Rule>()
        assertEquals("+1 555 0100", opened.pattern)
        assertEquals(RuleType.EXACT.name, opened.type)

        val report = resolve(IntentRoutes.ACTION_POST_CALL) {
            putExtra(IntentRoutes.EXTRA_NUMBER, "+1 555 0100")
            putExtra(IntentRoutes.EXTRA_POST_CALL_ACTION, "REPORT")
        }
        assertEquals("+1 555 0100", report?.report)
    }

    @Test fun otherAppsIntentsStillArrive() {
        val lookup = Uri.parse("content://com.android.contacts/contacts/lookup/abc/7")
        assertEquals(lookup, resolve(IntentRoutes.QUICK_CONTACT, lookup)?.resolveContact)
        assertEquals(lookup, resolve(IntentRoutes.QUICK_CONTACT_LEGACY, lookup)?.resolveContact)
        val tel = Uri.parse("tel:+15550100")
        assertEquals(tel, resolve(IntentRoutes.SHOW_OR_CREATE, tel)?.showOrCreate)
        val vcf = Uri.parse("content://files/card.vcf")
        assertEquals(NavEvent.ImportVcf(vcf), resolve(Intent.ACTION_VIEW, vcf, "text/x-vcard")?.event)
        assertTrue(resolve(Intent.ACTION_INSERT)?.event is NavEvent.NewContact)
        assertTrue(resolve(Intent.ACTION_INSERT_OR_EDIT)?.event is NavEvent.InsertOrEdit)
        opens(Routes.forEvent(NavEvent.NewContact(ContactDetails()))!!)
    }

    @Test fun argumentsSurviveWithoutHandEncoding() {
        // Characters a hand-built query string used to mangle (and a second decode used to break).
        val number = "+44 (20) 7946/0000 #1&x=%41?"
        opens(Routes.history(number))
        assertEquals(number, nav.currentBackStackEntry!!.toRoute<Routes.History>().number)

        opens(Routes.edit(name = "Zoë & Co / 100%", phone = "+1 555?", prefill = true, handshake = "h/1"))
        val edit = nav.currentBackStackEntry!!.toRoute<Routes.Edit>()
        assertEquals("Zoë & Co / 100%", edit.name)
        assertEquals("+1 555?", edit.phone)
        assertTrue(edit.prefill)
        assertEquals(-1L, edit.id)

        opens(Routes.settingsPage(SettingsCategory.HISTORY, "archive"))
        assertEquals("archive", nav.currentBackStackEntry!!.toRoute<Routes.SettingsPage>().focus)
        opens(PeopleRoutes.label("Family / close"))
        assertEquals("Family / close", nav.currentBackStackEntry!!.toRoute<PeopleRoutes.Label>().title)
    }

    @Test fun everyDestinationIsInTheGraph() {
        val all: List<Destination> = listOf(
            Routes.Contact(1), Routes.edit(), Routes.Vault(2), Routes.history("123"), Routes.pick(Routes.PREFILL_MARK), Routes.Settings,
            Routes.settingsPage(SettingsCategory.APPEARANCE), Routes.Temporary, Routes.Blocking, Routes.Duplicates, Routes.Privacy,
            Routes.SpeedDial, Routes.Birthdays, Routes.Health, Routes.journal(HistoryTab.CALLS), Routes.Tools, Routes.Backup, Routes.Sync,
            Routes.CallTime, Routes.versions(3),
            ContactPageRoutes.timeline(4), ContactPageRoutes.Sections,
            HistoryRoutes.Insights, HistoryRoutes.Settings, HistoryRoutes.Import, HistoryRoutes.Sims, HistoryRoutes.sim("sim/1"),
            BlockingRoutes.Lists, BlockingRoutes.Transfer, BlockingRoutes.DryRun, BlockingRoutes.Templates, BlockingRoutes.rule(5),
            PeopleRoutes.Labels, PeopleRoutes.label("Work"), PeopleRoutes.editRaw(6, 7), PeopleRoutes.SimImport, PeopleRoutes.WhoCanSee,
            PeopleRoutes.PrivateNames, PeopleRoutes.Diagnostics, PeopleRoutes.Me,
            MessagingRoutes.Messaged, MessagingRoutes.BulkAdd, MessagingRoutes.Introduce, MessagingRoutes.CsvMapping,
            ExtrasRoutes.Trip, ExtrasRoutes.SimpleSetup, ExtrasRoutes.SimpleImport, QrRoutes.Scan,
        )
        all.forEach(::opens)
        nav.navigate(Routes.Home)
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute(Routes.Home::class))
    }
}
