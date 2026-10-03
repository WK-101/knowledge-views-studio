package app.parley.ui.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.common.SettingPlace
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.appVm
import app.parley.ui.backup.BackupScreen
import app.parley.ui.birthdays.BirthdaysScreen
import app.parley.ui.calltime.CallTimeScreen
import app.parley.ui.discover.CapabilitiesScreen
import app.parley.ui.health.HealthScreen
import app.parley.ui.journal.HistoryHubScreen
import app.parley.ui.journal.HistoryTab
import app.parley.ui.sync.FolderSyncScreen
import app.parley.ui.temporary.TemporaryContactsScreen

/** Settings and the app-wide tools reached from it: backup, sync, History & undo, call time, speed dial… */
fun NavGraphBuilder.settingsGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<Routes.Settings> { SettingsScreen(appVm(), back = back, open = open) }
    composable<Routes.SettingsPage> {
        val a = it.toRoute<Routes.SettingsPage>()
        when (val t = settingsPageTarget(a.category, a.focus)) {
            is SettingsPageTarget.Category -> SettingsPageScreen(appVm(), t.category, t.focus, back = back, open = open)
            is SettingsPageTarget.Reminders -> RemindersScreen(appVm(), t.focus, back = back, open = open)
            is SettingsPageTarget.Calls -> CallsSubPageScreen(appVm(), t.page, t.focus, back = back, open = open)
        }
    }
    composable<RemindersRoutes.Page> { RemindersScreen(appVm(), it.toRoute<RemindersRoutes.Page>().focus, back = back, open = open) }
    // Tools was a page of its own; old links (a restored back stack) open the one hub that replaced it.
    composable<Routes.Tools> { CapabilitiesScreen(appVm(), back) }
    composable<Routes.Privacy> { PrivacyScreen(appVm(), back = back) }
    composable<Routes.SpeedDial> { SpeedDialScreen(appVm(), back = back) }
    composable<Routes.Temporary> { TemporaryContactsScreen(appVm(), back = back, open = open) }
    composable<Routes.Health> { HealthScreen(appVm(), back = back, open = open) }
    composable<Routes.Birthdays> { BirthdaysScreen(appVm(), back = back, open = open) }
    composable<Routes.Backup> { BackupScreen(appVm(), back = back) }
    composable<Routes.Sync> { FolderSyncScreen(appVm(), back = back, open = open) }
    composable<Routes.CallTime> { CallTimeScreen(appVm(), back = back) }
    composable<CallsRoutes.PhoneMenus> { PhoneMenusScreen(appVm(), back = back) }
    composable<CallsRoutes.Page> {
        val a = it.toRoute<CallsRoutes.Page>()
        CallsSubPageScreen(appVm(), CallsSubPage.of(a.page), a.focus, back = back, open = open)
    }
    composable<AppLockRoutes.UnlockWith> { UnlockWithScreen(appVm(), back = back) }
    composable<Routes.Journal> { HistoryHubScreen(appVm(), HistoryTab.of(it.toRoute<Routes.Journal>().tab), back = back, open = open) }
}

/** What a [Routes.SettingsPage] link opens. */
internal sealed interface SettingsPageTarget {
    data class Category(val category: SettingsCategory, val focus: String?) : SettingsPageTarget

    data class Reminders(val focus: String) : SettingsPageTarget

    data class Calls(val page: CallsSubPage, val focus: String) : SettingsPageTarget
}

/**
 * Where a link to [categoryName]'s page with [focus] lands. A setting that moved off its category page hands over to
 * where it lives now, scrolled to and highlighting its row: a reminder to Reminders, and a Calls setting to the Calls
 * page that holds it. Old links (a restored back stack, a menu, a notification) then still find the row. Another
 * page that keeps a row with the same key (Call time's "SIMs & plan minutes") keeps it.
 */
internal fun settingsPageTarget(categoryName: String, focus: String?): SettingsPageTarget {
    val category = SettingsCategory.entries.firstOrNull { it.name == categoryName } ?: SettingsCategory.APPEARANCE
    val place = focus?.let { f -> SettingsCatalog.entries.firstOrNull { it.key == f }?.place }
    if (focus == null || place == null) return SettingsPageTarget.Category(category, focus)
    if (place == SettingPlace.REMINDERS) return SettingsPageTarget.Reminders(focus)
    val calls = CallsSubPage.at(place)?.takeIf { category == SettingsCategory.CALLS }
    return if (calls != null) SettingsPageTarget.Calls(calls, focus) else SettingsPageTarget.Category(category, focus)
}
