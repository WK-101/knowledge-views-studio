package app.parley.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.UiEvent
import app.parley.common.HomeLayout
import app.parley.data.ContactDetails
import app.parley.messaging.ChatThenDecideHost
import app.parley.ui.blocking.BlockingDialogHost
import app.parley.ui.LocalNavAnimScope
import app.parley.ui.LocalSharedScope
import app.parley.ui.LocalAvatarStyle
import app.parley.ui.calltime.UssdDialog
import app.parley.ui.circle.CircleSnackHost
import app.parley.ui.family.ExpectedCallOfferHost
import app.parley.ui.common.CallDialogs
import app.parley.ui.common.CoachMarks
import app.parley.ui.common.ImportVcfDialog
import app.parley.ui.common.LocalCoachMarks
import app.parley.ui.contact.ReceiveSecureQrDialog
import app.parley.ui.extras.SimpleHome
import app.parley.ui.home.HomeScreen
import app.parley.ui.home.LocalRecentsStyle
import app.parley.ui.onboarding.OnboardingScreen
import app.parley.ui.people.CrashReportHost
import app.parley.ui.common.ProvideAppKit
import app.parley.ui.common.JobResultsHost
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * The app's root: the shared components' words and the one snackbar every screen shows (see [ParleySnackbar]),
 * then onboarding, Simple mode or the navigation host.
 */
@Composable
fun ParleyRoot(vm: AppViewModel) {
    ProvideAppKit {
        ProvideSnackbar { snackbar ->
            JobResultsHost(vm)
            ParleyRootContent(vm, snackbar)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ParleyRootContent(vm: AppViewModel, appSnackbar: ParleySnackbar) {
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
    val snackbar = appSnackbar.state
    val undoLabel = stringResource(R.string.dc_undo)
    var tabRequest by remember { mutableStateOf<NavEvent.Tab?>(null) }
    var insertOrEdit by remember { mutableStateOf<ContactDetails?>(null) }
    var importAsk by remember { mutableStateOf<NavEvent.ImportVcf?>(null) }
    var secureQrUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        vm.navEvents.collect { e ->
            when (e) {
                is NavEvent.NewContact -> {
                    vm.pendingPrefill = e.prefill
                    nav.navigate(Routes.edit(prefill = true))
                }
                is NavEvent.InsertOrEdit -> insertOrEdit = e.prefill
                is NavEvent.ImportVcf -> importAsk = e
                is NavEvent.SecureQr -> secureQrUri = e.uri
                is NavEvent.Tab -> {
                    nav.popBackStack(Routes.Home, inclusive = false)
                    tabRequest = e
                }
                else -> Routes.forEvent(e)?.let { d -> nav.navigate(d) { launchSingleTop = true } }
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.Undo -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.undo(e.journalIds)
                }
                is UiEvent.UndoCalls -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.c.history.undoDelete(e.batchId)
                }
                is UiEvent.UndoAction -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.runUndo(e.undo)
                }
                is UiEvent.Offer -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = e.actionLabel, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) vm.runUndo(e.action)
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
        LocalRecentsStyle provides recentsStyle, LocalAppViewModel provides vm,
    ) {
    val rtlSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    Box(Modifier.fillMaxSize()) {
      SharedTransitionLayout {
       CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(
            nav, startDestination = Routes.Home,
            // Forward screens come in from the end side: the right, or the left in Arabic and Urdu.
            enterTransition = { slideInHorizontally { w -> w / 6 * rtlSign } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { w -> w / 6 * rtlSign } + fadeOut() },
        ) {
            composable<Routes.Home> {
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
            parleyGraph(nav)
        }
       }
      }
        // Screens show the snackbar inside their own Scaffold (above their navigation bar and floating button);
        // only while none does (a screen without one) does the root show it.
        if (appSnackbar.hosts == 0) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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
    // "Expecting a call?" the first time a note, To call item or delivery QR code could turn it on.
    ExpectedCallOfferHost(vm)

    insertOrEdit?.let { p ->
        ParleyDialog(
            onDismissRequest = { insertOrEdit = null },
            title = { Text(stringResource(R.string.save_contact_details)) },
            text = {
                Text(
                    listOfNotNull(
                        p.composedName.ifBlank { null }, p.phones.firstOrNull()?.value, p.emails.firstOrNull()?.value,
                        p.addresses.firstOrNull()?.formatted?.ifBlank { null },
                    ).joinToString(" · "),
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
    importAsk?.let { ask -> ImportVcfDialog(vm, ask.uri, ask.keep) { importAsk = null } }
    secureQrUri?.let { uri ->
        ReceiveSecureQrDialog(vm, uri, onDone = { secureQrUri = null }) { details, handshake ->
            vm.pendingPrefill = details
            nav.navigate(Routes.edit(prefill = true, handshake = handshake))
        }
    }
}
