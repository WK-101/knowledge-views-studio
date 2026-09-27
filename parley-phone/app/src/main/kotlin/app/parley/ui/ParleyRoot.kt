package app.parley.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.parley.R
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.UiEvent
import app.parley.common.HomeLayout
import app.parley.common.SettingsCategory
import app.parley.common.StartTab
import app.parley.data.ContactDetails
import app.parley.messaging.ChatThenDecideHost
import app.parley.ui.backup.BackupScreen
import app.parley.ui.birthdays.BirthdaysScreen
import app.parley.ui.blocking.BlockingDialogHost
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.blocking.BlockingScreen
import app.parley.ui.LocalNavAnimScope
import app.parley.ui.LocalSharedScope
import app.parley.ui.LocalAvatarStyle
import app.parley.ui.calltime.CallTimeScreen
import app.parley.ui.calltime.UssdDialog
import app.parley.ui.circle.CircleSnackHost
import app.parley.ui.common.CallDialogs
import app.parley.ui.common.CoachMarks
import app.parley.ui.common.ImportVcfDialog
import app.parley.ui.common.LocalCoachMarks
import app.parley.ui.contact.ContactDetailScreen
import app.parley.ui.contact.ContactEditScreen
import app.parley.ui.contact.ContactPickerScreen
import app.parley.ui.contact.DuplicatesScreen
import app.parley.ui.contact.ReceiveSecureQrDialog
import app.parley.ui.extras.HandshakeInbox
import app.parley.ui.extras.SimpleHome
import app.parley.ui.health.HealthScreen
import app.parley.ui.history.NumberHistoryScreen
import app.parley.ui.history.historyDestinations
import app.parley.ui.home.HomeScreen
import app.parley.ui.home.LocalRecentsStyle
import app.parley.ui.journal.HistoryHubScreen
import app.parley.ui.journal.HistoryTab
import app.parley.ui.onboarding.OnboardingScreen
import app.parley.ui.people.CrashReportHost
import app.parley.ui.people.peopleRoutes
import app.parley.ui.contact.contactPageRoutes
import app.parley.messaging.messagingRoutes
import app.parley.ui.extras.extrasRoutes
import app.parley.ui.qr.qrRoutes
import app.parley.ui.settings.PrivacyScreen
import app.parley.ui.settings.SettingsPageScreen
import app.parley.ui.settings.SettingsScreen
import app.parley.ui.settings.SpeedDialScreen
import app.parley.ui.settings.ToolsScreen
import app.parley.ui.sync.FolderSyncScreen
import app.parley.ui.temporary.TemporaryContactsScreen
import app.parley.ui.timemachine.VersionHistoryScreen
import app.parley.ui.vault.VaultDetailScreen

object Routes {
    const val HOME = "home"
    const val CONTACT = "contact/{id}"
    const val EDIT = "edit?id={id}&name={name}&phone={phone}&email={email}&addPhone={addPhone}&prefill={prefill}&vault={vault}&hs={hs}"
    const val VAULT = "vault/{id}"
    fun vault(id: Long) = "vault/$id"
    const val HISTORY = "history/{number}"
    const val PICK = "pick/{number}"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings/page/{category}?focus={focus}"
    fun settingsPage(category: SettingsCategory, focus: String? = null) =
        "settings/page/${category.name}" + if (focus != null) "?focus=" + Uri.encode(focus) else ""
    const val TEMPORARY = "temporary"
    const val BLOCKING = "blocking"
    const val DUPLICATES = "duplicates"
    const val PRIVACY = "privacy"
    const val SPEED_DIAL = "speeddial"
    const val BIRTHDAYS = "birthdays"
    const val HEALTH = "health"
    /** History & undo; [journal] opens it on one tab. */
    const val JOURNAL = "journal?tab={tab}"
    fun journal(tab: HistoryTab = HistoryTab.CONTACTS) = "journal?tab=" + tab.key
    const val TOOLS = "tools"
    const val BACKUP = "backup"
    const val SYNC = "sync"
    const val CALL_TIME = "calltime"
    const val VERSIONS = "versions/{id}"
    fun versions(id: Long) = "versions/$id"

    fun contact(id: Long) = "contact/$id"
    fun history(number: String) = "history/" + Uri.encode(number)
    fun pick(number: String) = "pick/" + Uri.encode(number)
    /** [handshake]: X5, the id of the received card this editor was opened for (see HandshakeInbox). */
    fun edit(
        id: Long? = null, name: String? = null, phone: String? = null, email: String? = null, addPhone: String? = null, prefill: Boolean = false,
        vault: Long? = null, handshake: String? = null,
    ): String =
        "edit?id=${id ?: -1}&name=${Uri.encode(name.orEmpty())}&phone=${Uri.encode(phone.orEmpty())}" +
            "&email=${Uri.encode(email.orEmpty())}&addPhone=${Uri.encode(addPhone.orEmpty())}&prefill=$prefill&vault=${vault ?: -1}" +
            "&hs=${Uri.encode(handshake.orEmpty())}"

    /** Picker for "add to existing contact"; the number (or "_" = use the pending prefill). */
    const val PREFILL_MARK = "_"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ParleyRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val hasContacts by vm.hasContactsPermission.collectAsStateWithLifecycle()
    var skippedOnboarding by remember { mutableStateOf(false) }
    val loaded by vm.c.settings.loaded.collectAsStateWithLifecycle()
    if (!loaded) {
        Surface(Modifier.fillMaxSize()) {}
        return
    }

    // Once started, onboarding runs to its permissions page even after the phone role grants contacts.
    var onboardingStarted by rememberSaveable { mutableStateOf(false) }
    if (!settings.onboardingDone && !skippedOnboarding && (onboardingStarted || !(isDefault && hasContacts))) {
        SideEffect { onboardingStarted = true }
        OnboardingScreen(vm, onDone = { skippedOnboarding = true })
        return
    }

    // Simple mode replaces the tabs (its own home, calls still go through the usual questions).
    val simple by vm.c.extras.simple.collectAsStateWithLifecycle()
    if (simple.enabled) {
        val marks = remember { CoachMarks(vm.c.ux) }
        CompositionLocalProvider(LocalCoachMarks provides marks) { SimpleHome(vm) }
        CallDialogs(vm)
        return
    }

    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var tabRequest by remember { mutableStateOf<NavEvent.Tab?>(null) }
    var insertOrEdit by remember { mutableStateOf<ContactDetails?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var secureQrUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        vm.navEvents.collect { e ->
            when (e) {
                is NavEvent.Contact -> nav.navigate(Routes.contact(e.id)) { launchSingleTop = true }
                is NavEvent.History -> nav.navigate(Routes.history(e.number)) { launchSingleTop = true }
                is NavEvent.NewContact -> {
                    vm.pendingPrefill = e.prefill
                    nav.navigate(Routes.edit(prefill = true))
                }
                is NavEvent.InsertOrEdit -> insertOrEdit = e.prefill
                is NavEvent.ImportVcf -> importUri = e.uri
                is NavEvent.SecureQr -> secureQrUri = e.uri
                is NavEvent.Vault -> nav.navigate(Routes.vault(e.id)) { launchSingleTop = true }
                is NavEvent.Route -> nav.navigate(e.route) { launchSingleTop = true }
                is NavEvent.Tab -> {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                    tabRequest = e
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.Undo -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.undo(e.journalIds)
                }
                is UiEvent.UndoCalls -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.c.history.undoDelete(e.batchId)
                }
                else -> Unit
            }
        }
    }

    // Avatar style for every list and page.
    val avatarStyle = vm.people.settings.collectAsStateWithLifecycle().value.avatarStyle
    // One-time tips, one at a time.
    val coachMarks = remember { CoachMarks(vm.c.ux) }
    // Rich or Simple call rows everywhere calls are listed.
    val recentsStyle = vm.settings.collectAsStateWithLifecycle().value.recentsStyle
    CompositionLocalProvider(
        LocalAvatarStyle provides avatarStyle, LocalCoachMarks provides coachMarks,
        LocalRecentsStyle provides recentsStyle,
    ) {
    Box(Modifier.fillMaxSize()) {
      SharedTransitionLayout {
       CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(
            nav, startDestination = Routes.HOME,
            enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
        ) {
            composable(Routes.HOME) {
              CompositionLocalProvider(LocalNavAnimScope provides this) {
                HomeScreen(
                    vm = vm,
                    tabRequest = tabRequest,
                    onTabRequestHandled = { tabRequest = null },
                    initialTab = HomeLayout(settings.navTabs, settings.surfaces).startTab(settings.startTab),
                    open = { route -> nav.navigate(route) },
                )
              }
            }
            composable(Routes.CONTACT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
              CompositionLocalProvider(LocalNavAnimScope provides this) {
                ContactDetailScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
              }
            }
            composable(
                Routes.EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("name") { defaultValue = "" },
                    navArgument("phone") { defaultValue = "" },
                    navArgument("email") { defaultValue = "" },
                    navArgument("addPhone") { defaultValue = "" },
                    navArgument("prefill") { type = NavType.BoolType; defaultValue = false },
                    navArgument("vault") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("hs") { defaultValue = "" },
                ),
            ) {
                val a = it.arguments!!
                ContactEditScreen(
                    vm,
                    contactId = a.getLong("id").takeIf { id -> id > 0 },
                    prefillName = a.getString("name").orEmpty(),
                    prefillPhone = a.getString("phone").orEmpty(),
                    prefillEmail = a.getString("email").orEmpty(),
                    addPhone = a.getString("addPhone").orEmpty(),
                    prefill = if (a.getBoolean("prefill")) vm.pendingPrefill.also { vm.pendingPrefill = null } else null,
                    vaultId = a.getLong("vault").takeIf { it >= 0 },
                    done = { savedId ->
                        // A contact received by QR gets its "Met at…" entry once it's saved.
                        HandshakeInbox.onSaved(vm, savedId, a.getString("hs"))
                        nav.popBackStack()
                        val here = nav.currentDestination?.route
                        when {
                            savedId == null -> Unit
                            savedId < 0 -> if (here != Routes.VAULT) nav.navigate(Routes.vault(-savedId)) { launchSingleTop = true }
                            here != Routes.CONTACT -> nav.navigate(Routes.contact(savedId)) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.VAULT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                VaultDetailScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.HISTORY) {
                NumberHistoryScreen(vm, Uri.decode(it.arguments!!.getString("number").orEmpty()), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.PICK) {
                val number = Uri.decode(it.arguments!!.getString("number").orEmpty())
                ContactPickerScreen(vm, back = { nav.popBackStack() }, onPick = { id ->
                    nav.popBackStack()
                    if (number == Routes.PREFILL_MARK) nav.navigate(Routes.edit(id = id, prefill = true))
                    else nav.navigate(Routes.edit(id = id, addPhone = number))
                })
            }
            composable(Routes.SETTINGS) { SettingsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(
                Routes.SETTINGS_PAGE,
                arguments = listOf(navArgument("category") { type = NavType.StringType }, navArgument("focus") { nullable = true; defaultValue = null }),
            ) {
                val a = it.arguments!!
                val category = SettingsCategory.entries.firstOrNull { c -> c.name == a.getString("category") }
                    ?: SettingsCategory.APPEARANCE
                SettingsPageScreen(vm, category, a.getString("focus"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.TEMPORARY) { TemporaryContactsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.BLOCKING) { BlockingScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            BlockingRoutes.register(this, vm) { nav.popBackStack() }
            composable(Routes.DUPLICATES) { DuplicatesScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.SYNC) { FolderSyncScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.VERSIONS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                VersionHistoryScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.BACKUP) { BackupScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.JOURNAL, arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                HistoryHubScreen(
                    vm, HistoryTab.of(it.arguments?.getString("tab")), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) },
                )
            }
            composable(Routes.TOOLS) { ToolsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.HEALTH) { HealthScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.BIRTHDAYS) { BirthdaysScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.SPEED_DIAL) { SpeedDialScreen(vm, back = { nav.popBackStack() }) }
            historyDestinations(vm, nav)
            composable(Routes.CALL_TIME) { CallTimeScreen(vm, back = { nav.popBackStack() }) }
            peopleRoutes(vm, nav)
            // Full timeline, contact page sections.
            contactPageRoutes(vm, nav)
            messagingRoutes(vm, nav)
            extrasRoutes(vm, nav)
            // Scan QR.
            qrRoutes(vm, nav)
        }
       }
      }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
    }
    // A crash report kept from last time is offered once.
    CrashReportHost(vm)
    ChatThenDecideHost(snackbar, openPrivate = { id -> nav.navigate(Routes.vault(id)) { launchSingleTop = true } }) { id -> nav.navigate(Routes.contact(id)) { launchSingleTop = true } }
    // "Log this?" and the Circle's Undo messages.
    CircleSnackHost(vm, snackbar)
    CallDialogs(vm)
    UssdDialog(vm)
    BlockingDialogHost(vm)

    insertOrEdit?.let { p ->
        AlertDialog(
            onDismissRequest = { insertOrEdit = null },
            title = { Text(stringResource(R.string.save_contact_details)) },
            text = {
                Text(
                    listOfNotNull(p.composedName.ifBlank { null }, p.phones.firstOrNull()?.value, p.emails.firstOrNull()?.value).joinToString(" · "),
                )
            },
            confirmButton = {
                TextButton({
                    vm.pendingPrefill = p
                    insertOrEdit = null
                    nav.navigate(Routes.edit(prefill = true))
                }) { Text(stringResource(R.string.keypad_create_contact)) }
            },
            dismissButton = {
                TextButton({
                    vm.pendingPrefill = p
                    insertOrEdit = null
                    nav.navigate(Routes.pick(Routes.PREFILL_MARK))
                }) { Text(stringResource(R.string.keypad_add_to_existing)) }
            },
        )
    }
    importUri?.let { uri -> ImportVcfDialog(vm, uri) { importUri = null } }
    secureQrUri?.let { uri ->
        ReceiveSecureQrDialog(vm, uri, onDone = { secureQrUri = null }) { details, handshake ->
            vm.pendingPrefill = details
            nav.navigate(Routes.edit(prefill = true, handshake = handshake))
        }
    }
}
