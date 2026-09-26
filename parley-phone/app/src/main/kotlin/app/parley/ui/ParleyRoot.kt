package app.parley.ui

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
import app.parley.common.StartTab
import app.parley.ui.blocking.BlockingScreen
import app.parley.ui.LocalNavAnimScope
import app.parley.ui.LocalSharedScope
import app.parley.ui.LocalAvatarStyle
import app.parley.ui.common.CallDialogs
import app.parley.ui.contact.ContactDetailScreen
import app.parley.ui.contact.ContactEditScreen
import app.parley.ui.contact.ContactPickerScreen
import app.parley.ui.contact.DuplicatesScreen
import app.parley.ui.history.NumberHistoryScreen
import app.parley.ui.history.historyDestinations
import app.parley.ui.home.HomeScreen
import app.parley.ui.onboarding.OnboardingScreen
import app.parley.ui.people.peopleRoutes
import app.parley.messaging.messagingRoutes
import app.parley.ui.extras.extrasRoutes
import app.parley.ui.settings.PrivacyScreen
import app.parley.ui.settings.SettingsScreen
import app.parley.ui.settings.SpeedDialScreen

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
    fun settingsPage(category: app.parley.common.SettingsCategory, focus: String? = null) =
        "settings/page/${category.name}" + if (focus != null) "?focus=" + Uri.encode(focus) else ""
    const val TEMPORARY = "temporary"
    const val BLOCKING = "blocking"
    const val DUPLICATES = "duplicates"
    const val PRIVACY = "privacy"
    const val SPEED_DIAL = "speeddial"
    const val BIRTHDAYS = "birthdays"
    const val HEALTH = "health"
    const val JOURNAL = "journal"
    const val BACKUP = "backup"
    const val CHANGES = "changes"
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

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ParleyRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val hasContacts by vm.hasContactsPermission.collectAsStateWithLifecycle()
    var skippedOnboarding by remember { mutableStateOf(false) }
    val loaded by vm.c.settings.loaded.collectAsStateWithLifecycle()
    if (!loaded) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
        return
    }

    // U1: once started, onboarding runs to its permissions page even after the phone role grants contacts.
    var onboardingStarted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (!settings.onboardingDone && !skippedOnboarding && (onboardingStarted || !(isDefault && hasContacts))) {
        androidx.compose.runtime.SideEffect { onboardingStarted = true }
        OnboardingScreen(vm, onDone = { skippedOnboarding = true })
        return
    }

    // X4: simple mode replaces the tabs (its own home, calls still go through the usual questions).
    val simple by vm.c.extras.simple.collectAsStateWithLifecycle()
    if (simple.enabled) {
        val marks = remember { app.parley.ui.common.CoachMarks(vm.c.ux) }
        androidx.compose.runtime.CompositionLocalProvider(app.parley.ui.common.LocalCoachMarks provides marks) { app.parley.ui.extras.SimpleHome(vm) }
        CallDialogs(vm)
        return
    }

    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var tabRequest by remember { mutableStateOf<NavEvent.Tab?>(null) }
    var insertOrEdit by remember { mutableStateOf<app.parley.data.ContactDetails?>(null) }
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
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undo(e.journalIds)
                }
                is UiEvent.UndoCalls -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.c.history.undoDelete(e.batchId)
                }
                else -> Unit
            }
        }
    }

    // U6: avatar style for every list and page.
    val avatarStyle = vm.people.settings.collectAsStateWithLifecycle().value.avatarStyle
    // U2: one-time tips, one at a time.
    val coachMarks = remember { app.parley.ui.common.CoachMarks(vm.c.ux) }
    // R4 (v3.3): Rich or Simple call rows everywhere calls are listed.
    val recentsStyle = vm.settings.collectAsStateWithLifecycle().value.recentsStyle
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAvatarStyle provides avatarStyle, app.parley.ui.common.LocalCoachMarks provides coachMarks,
        app.parley.ui.home.LocalRecentsStyle provides recentsStyle,
    ) {
    Box(Modifier.fillMaxSize()) {
      androidx.compose.animation.SharedTransitionLayout {
       androidx.compose.runtime.CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(
            nav, startDestination = Routes.HOME,
            enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
        ) {
            composable(Routes.HOME) {
              androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimScope provides this) {
                HomeScreen(
                    vm = vm,
                    tabRequest = tabRequest,
                    onTabRequestHandled = { tabRequest = null },
                    initialTab = settings.navTabs.startTab(settings.startTab),
                    open = { route -> nav.navigate(route) },
                )
              }
            }
            composable(Routes.CONTACT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
              androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimScope provides this) {
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
                        // X5: a contact received by QR gets its "Met at…" entry once it's saved.
                        app.parley.ui.extras.HandshakeInbox.onSaved(vm, savedId, a.getString("hs"))
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
                app.parley.ui.vault.VaultDetailScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
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
                val category = app.parley.common.SettingsCategory.entries.firstOrNull { c -> c.name == a.getString("category") }
                    ?: app.parley.common.SettingsCategory.APPEARANCE
                app.parley.ui.settings.SettingsPageScreen(vm, category, a.getString("focus"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.TEMPORARY) { app.parley.ui.temporary.TemporaryContactsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.BLOCKING) { BlockingScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            app.parley.ui.blocking.BlockingRoutes.register(this, vm) { nav.popBackStack() }
            composable(Routes.DUPLICATES) { DuplicatesScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.SYNC) { app.parley.ui.sync.FolderSyncScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.CHANGES) { app.parley.ui.timemachine.ChangesScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.VERSIONS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                app.parley.ui.timemachine.VersionHistoryScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.BACKUP) { app.parley.ui.backup.BackupScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.JOURNAL) { app.parley.ui.journal.JournalScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.HEALTH) { app.parley.ui.health.HealthScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.BIRTHDAYS) { app.parley.ui.birthdays.BirthdaysScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.SPEED_DIAL) { SpeedDialScreen(vm, back = { nav.popBackStack() }) }
            historyDestinations(vm, nav)
            composable(Routes.CALL_TIME) { app.parley.ui.calltime.CallTimeScreen(vm, back = { nav.popBackStack() }) }
            peopleRoutes(vm, nav)
            messagingRoutes(vm, nav)
            // X2, X4
            extrasRoutes(vm, nav)
        }
       }
      }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
    }
    // U10: a crash report kept from last time is offered once.
    app.parley.ui.people.CrashReportHost(vm)
    app.parley.messaging.ChatThenDecideHost(snackbar, openPrivate = { id -> nav.navigate(Routes.vault(id)) { launchSingleTop = true } }) { id -> nav.navigate(Routes.contact(id)) { launchSingleTop = true } }
    // R3 "Log this?" and the Circle's Undo messages.
    app.parley.ui.circle.CircleSnackHost(vm, snackbar)
    CallDialogs(vm)
    app.parley.ui.calltime.UssdDialog(vm)
    app.parley.ui.blocking.BlockingDialogHost(vm)

    insertOrEdit?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { insertOrEdit = null },
            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.save_contact_details)) },
            text = {
                androidx.compose.material3.Text(
                    listOfNotNull(p.composedName.ifBlank { null }, p.phones.firstOrNull()?.value, p.emails.firstOrNull()?.value).joinToString(" · "),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    vm.pendingPrefill = p
                    insertOrEdit = null
                    nav.navigate(Routes.edit(prefill = true))
                }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.keypad_create_contact)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton({
                    vm.pendingPrefill = p
                    insertOrEdit = null
                    nav.navigate(Routes.pick(Routes.PREFILL_MARK))
                }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.keypad_add_to_existing)) }
            },
        )
    }
    importUri?.let { uri -> app.parley.ui.common.ImportVcfDialog(vm, uri) { importUri = null } }
    secureQrUri?.let { uri ->
        app.parley.ui.contact.ReceiveSecureQrDialog(vm, uri, onDone = { secureQrUri = null }) { details, handshake ->
            vm.pendingPrefill = details
            nav.navigate(Routes.edit(prefill = true, handshake = handshake))
        }
    }
}
