package app.parley.ui.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.common.SettingsCategory
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.appVm
import app.parley.ui.backup.BackupScreen
import app.parley.ui.birthdays.BirthdaysScreen
import app.parley.ui.calltime.CallTimeScreen
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
        val category = SettingsCategory.entries.firstOrNull { c -> c.name == a.category } ?: SettingsCategory.APPEARANCE
        SettingsPageScreen(appVm(), category, a.focus, back = back, open = open)
    }
    composable<Routes.Tools> { ToolsScreen(appVm(), back = back, open = open) }
    composable<Routes.Privacy> { PrivacyScreen(appVm(), back = back) }
    composable<Routes.SpeedDial> { SpeedDialScreen(appVm(), back = back) }
    composable<Routes.Temporary> { TemporaryContactsScreen(appVm(), back = back, open = open) }
    composable<Routes.Health> { HealthScreen(appVm(), back = back, open = open) }
    composable<Routes.Birthdays> { BirthdaysScreen(appVm(), back = back, open = open) }
    composable<Routes.Backup> { BackupScreen(appVm(), back = back) }
    composable<Routes.Sync> { FolderSyncScreen(appVm(), back = back, open = open) }
    composable<Routes.CallTime> { CallTimeScreen(appVm(), back = back) }
    composable<CallsRoutes.PhoneMenus> { PhoneMenusScreen(appVm(), back = back) }
    composable<AppLockRoutes.UnlockWith> { UnlockWithScreen(appVm(), back = back) }
    composable<Routes.Journal> { HistoryHubScreen(appVm(), HistoryTab.of(it.toRoute<Routes.Journal>().tab), back = back, open = open) }
}
