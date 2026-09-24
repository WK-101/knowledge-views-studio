package app.parley.ui

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
import app.parley.ui.common.CallDialogs
import app.parley.ui.contact.ContactDetailScreen
import app.parley.ui.contact.ContactEditScreen
import app.parley.ui.contact.ContactPickerScreen
import app.parley.ui.contact.DuplicatesScreen
import app.parley.ui.history.NumberHistoryScreen
import app.parley.ui.home.HomeScreen
import app.parley.ui.onboarding.OnboardingScreen
import app.parley.ui.settings.PrivacyScreen
import app.parley.ui.settings.SettingsScreen
import app.parley.ui.settings.SpeedDialScreen

object Routes {
    const val HOME = "home"
    const val CONTACT = "contact/{id}"
    const val EDIT = "edit?id={id}&name={name}&phone={phone}&email={email}&addPhone={addPhone}"
    const val HISTORY = "history/{number}"
    const val PICK = "pick/{number}"
    const val SETTINGS = "settings"
    const val BLOCKING = "blocking"
    const val DUPLICATES = "duplicates"
    const val PRIVACY = "privacy"
    const val SPEED_DIAL = "speeddial"

    fun contact(id: Long) = "contact/$id"
    fun history(number: String) = "history/" + Uri.encode(number)
    fun pick(number: String) = "pick/" + Uri.encode(number)
    fun edit(id: Long? = null, name: String? = null, phone: String? = null, email: String? = null, addPhone: String? = null): String =
        "edit?id=${id ?: -1}&name=${Uri.encode(name.orEmpty())}&phone=${Uri.encode(phone.orEmpty())}" +
            "&email=${Uri.encode(email.orEmpty())}&addPhone=${Uri.encode(addPhone.orEmpty())}"
}

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

    if (!settings.onboardingDone && !skippedOnboarding && !(isDefault && hasContacts)) {
        OnboardingScreen(vm, onDone = { skippedOnboarding = true })
        return
    }

    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var tabRequest by remember { mutableStateOf<NavEvent.Tab?>(null) }

    LaunchedEffect(Unit) {
        vm.navEvents.collect { e ->
            when (e) {
                is NavEvent.Contact -> nav.navigate(Routes.contact(e.id)) { launchSingleTop = true }
                is NavEvent.History -> nav.navigate(Routes.history(e.number)) { launchSingleTop = true }
                is NavEvent.NewContact -> nav.navigate(Routes.edit(name = e.name, phone = e.phone, email = e.email))
                is NavEvent.Tab -> {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                    tabRequest = e
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e -> if (e is UiEvent.Message) snackbar.showSnackbar(e.text) }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav, startDestination = Routes.HOME,
            enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    tabRequest = tabRequest,
                    onTabRequestHandled = { tabRequest = null },
                    initialTab = settings.startTab,
                    open = { route -> nav.navigate(route) },
                )
            }
            composable(Routes.CONTACT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ContactDetailScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(
                Routes.EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("name") { defaultValue = "" },
                    navArgument("phone") { defaultValue = "" },
                    navArgument("email") { defaultValue = "" },
                    navArgument("addPhone") { defaultValue = "" },
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
                    done = { savedId ->
                        nav.popBackStack()
                        if (savedId != null && nav.currentDestination?.route != Routes.CONTACT) nav.navigate(Routes.contact(savedId)) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.HISTORY) {
                NumberHistoryScreen(vm, Uri.decode(it.arguments!!.getString("number").orEmpty()), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
            }
            composable(Routes.PICK) {
                val number = Uri.decode(it.arguments!!.getString("number").orEmpty())
                ContactPickerScreen(vm, back = { nav.popBackStack() }, onPick = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.edit(id = id, addPhone = number))
                })
            }
            composable(Routes.SETTINGS) { SettingsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
            composable(Routes.BLOCKING) { BlockingScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.DUPLICATES) { DuplicatesScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.SPEED_DIAL) { SpeedDialScreen(vm, back = { nav.popBackStack() }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
    CallDialogs(vm)
}
