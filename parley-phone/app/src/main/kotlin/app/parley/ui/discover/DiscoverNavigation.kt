// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.discover

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.NavEvent
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory
import app.parley.common.StartTab
import app.parley.common.ux.AppScreen
import app.parley.common.ux.CapabilityTarget
import app.parley.common.ux.ComingFrom
import app.parley.common.ux.HelpTopic
import app.parley.messaging.MessagingRoutes
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.appVm
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.calls.ToCallRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.family.FamilyRoutes
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.journal.HistoryTab
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.qr.QrRoutes
import app.parley.ui.settings.settingRoute
import app.parley.ui.settings.CallsRoutes
import app.parley.ui.drive.DriveRoutes
import app.parley.ui.sync.shared.SharedLabelRoutes
import app.parley.ui.situations.SituationRoutes
import kotlinx.serialization.Serializable

/** Tools, "Coming from another phone?" and Help & troubleshooting. */
object DiscoverRoutes {
    @Serializable data object Capabilities : Destination

    @Serializable data object ComingFrom : Destination

    /** Help & troubleshooting: the task pages ([HelpTopic.troubleshooting]). */
    @Serializable data object Help : Destination

    /** One help page, by [HelpTopic.key]. */
    @Serializable data class HelpPage(val topic: String) : Destination
}

fun NavGraphBuilder.discoverGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<DiscoverRoutes.Capabilities> { CapabilitiesScreen(appVm(), back) }
    composable<DiscoverRoutes.ComingFrom> { ComingFromScreen(appVm(), back) }
    val openTopic: (HelpTopic) -> Unit = { nav.navigate(DiscoverRoutes.HelpPage(it.key)) }
    composable<DiscoverRoutes.Help> { HelpScreen(back, openTopic) }
    composable<DiscoverRoutes.HelpPage> { HelpPageScreen(appVm(), HelpTopic.of(it.toRoute<DiscoverRoutes.HelpPage>().topic), back, openTopic) }
}

/**
 * Where a row of "What Parley can do" goes. Screens map one to one (the `when` is exhaustive, so a new [AppScreen]
 * can't be forgotten); settings open where Settings search opens them; tabs open even when hidden from the bar.
 */
fun capabilityEvent(target: CapabilityTarget): NavEvent = when (target) {
    is CapabilityTarget.Setting -> NavEvent.Route(settingRoute(SettingsCatalog[target.key]))
    is CapabilityTarget.Help -> NavEvent.Route(DiscoverRoutes.HelpPage(target.topic.key))
    is CapabilityTarget.Screen -> when (target.screen) {
        AppScreen.CIRCLE -> NavEvent.Tab(StartTab.CIRCLE)
        AppScreen.KEYPAD -> NavEvent.Tab(StartTab.KEYPAD)
        // Recall in Recents' search, where "who called in March?" starts.
        AppScreen.SEARCH_EVERYTHING -> NavEvent.Tab(StartTab.RECENTS, everything = true)
        else -> NavEvent.Route(screenRoute(target.screen))
    }
}

@Suppress("CyclomaticComplexMethod") // One route per screen.
private fun screenRoute(s: AppScreen): Destination = when (s) {
    AppScreen.BLOCKING -> Routes.Blocking
    AppScreen.SPAM_LISTS -> BlockingRoutes.Lists
    AppScreen.TEST_A_CALL -> BlockingRoutes.DryRun
    AppScreen.RULE_TEMPLATES -> BlockingRoutes.Templates
    AppScreen.BLOCK_LIST_IMPORT -> BlockingRoutes.Transfer
    AppScreen.HISTORY_UNDO -> Routes.journal(HistoryTab.CONTACTS)
    AppScreen.SNAPSHOTS -> Routes.journal(HistoryTab.SNAPSHOTS)
    AppScreen.BACKUP -> Routes.Backup
    AppScreen.SYNC -> Routes.Sync
    AppScreen.HEALTH_CHECK -> Routes.Health
    AppScreen.DUPLICATES -> Routes.Duplicates
    AppScreen.COMING_FROM -> DiscoverRoutes.ComingFrom
    AppScreen.BIRTHDAYS -> Routes.Birthdays
    AppScreen.TO_CALL -> ToCallRoutes.List
    AppScreen.CALL_INSIGHTS -> HistoryRoutes.Insights()
    AppScreen.CALL_QUALITY -> HistoryRoutes.Insights(quality = true)
    AppScreen.TRIP -> ExtrasRoutes.Trip
    AppScreen.LABELS -> PeopleRoutes.Labels
    AppScreen.SCAN_QR -> QrRoutes.Scan
    AppScreen.MY_CARD -> PeopleRoutes.Me
    AppScreen.HELPERS -> FamilyRoutes.Helpers
    AppScreen.PRIVACY_DASHBOARD -> Routes.Privacy
    AppScreen.WHO_CAN_SEE -> PeopleRoutes.WhoCanSee
    AppScreen.TEMPORARY -> Routes.Temporary
    AppScreen.MESSAGED_NUMBERS -> MessagingRoutes.Messaged
    AppScreen.BULK_ADD -> MessagingRoutes.BulkAdd
    AppScreen.NEW_CONTACT -> Routes.edit()
    AppScreen.CALL_TIME -> Routes.CallTime
    AppScreen.SIMS -> HistoryRoutes.Sims(plans = true)
    AppScreen.SIMPLE_MODE -> ExtrasRoutes.SimpleSetup
    AppScreen.INTRODUCE -> MessagingRoutes.Introduce
    AppScreen.DRIVE_PROFILE -> DriveRoutes.Profile
    AppScreen.PHONE_MENUS -> CallsRoutes.PhoneMenus
    AppScreen.SPEED_DIAL -> Routes.SpeedDial
    AppScreen.SHARED_LABELS -> SharedLabelRoutes.All
    AppScreen.PRIVATE_NAMES -> PeopleRoutes.PrivateNames
    AppScreen.RESCUE_CALL -> SituationRoutes.RescueCall
    AppScreen.CASE_FILES -> HistoryRoutes.Cases
    AppScreen.ARCHIVED -> PeopleRoutes.Archived
    AppScreen.HELP -> DiscoverRoutes.Help
    // Tabs, handled by capabilityEvent; Home is where they live.
    AppScreen.CIRCLE, AppScreen.KEYPAD, AppScreen.SEARCH_EVERYTHING -> Routes.Home
}

/** The existing importer each "Coming from…" source opens. */
fun importerRoute(importer: ComingFrom.Importer): Destination = when (importer) {
    ComingFrom.Importer.PARLEY_BACKUP -> Routes.Backup
    ComingFrom.Importer.CONTACTS_FILE -> Routes.settingsPage(SettingsCategory.CONTACTS, "import_file")
    ComingFrom.Importer.CALL_HISTORY_CSV -> HistoryRoutes.Import
    ComingFrom.Importer.BLOCK_LIST -> BlockingRoutes.Transfer
}
