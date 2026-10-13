package app.parley.ui

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.AppViewModel
import app.parley.ParleyApp
import app.parley.common.ThemeMode
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.ui.common.CoachMarks
import app.parley.ui.common.LocalCoachMarks
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * The real app (fake providers) with a few contacts and calls, and a screen drawn as Parley's root draws it, for UI
 * tests that need the app's view model. Haptics go to [haptics] instead of the vibrator.
 */
abstract class AppComposeHarness {
    // Composition coroutines resume on the test thread, as they would on the main thread in the app (see UiSmokeTest).
    @get:Rule val compose = createEmptyComposeRule(StandardTestDispatcher())

    protected val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    protected lateinit var activity: ComponentActivity
    protected var adaId = 0L

    /** Contacts added besides Ada (starred) and Grace (private): "Mila Test01"… */
    protected open val extraContacts = 0

    /** The haptic feedback the screen asked for, in order. */
    protected val haptics = mutableListOf<HapticFeedbackType>()

    @Before fun setUpApp() {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
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
            val ada = c.contacts.save(null, person("Ada", "Lovelace", "+44 20 7946 0000"), null, null, false)!!
            adaId = ada.contactId
            c.contacts.setStarred(ada.contactId, true)
            val bob = c.contacts.save(null, person("Bob", "Babbage", "+44 20 7946 0001"), null, null, false)!!
            c.contacts.setStarred(bob.contactId, true)
            c.vault.save(null, ContactDetails(given = "Grace", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))))
            repeat(extraContacts) { i ->
                val n = "%02d".format(i + 1)
                c.contacts.save(null, person("Mila", "Test$n", "+44 20 7946 1$n"), null, null, false)
            }
        }
        app.contentResolver.insert(
            CallLog.Calls.CONTENT_URI,
            ContentValues().apply {
                put(CallLog.Calls.NUMBER, "+442079460000")
                put(CallLog.Calls.DATE, System.currentTimeMillis() - 60_000L * 90)
                put(CallLog.Calls.TYPE, CallLog.Calls.INCOMING_TYPE)
                put(CallLog.Calls.DURATION, 60L)
            },
        )
        c.startFull()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity = it }
    }

    private fun person(given: String, family: String, number: String) =
        ContactDetails(given = given, family = family, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE)))

    @After fun tearDownApp() {
        scenario.close()
        app.container.scope.cancel()
    }

    /** Draws [content] as Parley's root does (theme, tips, the app's view model), with haptics recorded. */
    protected lateinit var vm: AppViewModel

    protected fun show(content: @Composable (AppViewModel) -> Unit) {
        val vm = ViewModelProvider(activity)[AppViewModel::class.java].also { it.start() }
        this.vm = vm
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }
        activity.setContent {
            ParleyTheme(mode = ThemeMode.LIGHT, amoled = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalCoachMarks provides remember { CoachMarks(vm.c.ux) },
                    LocalAppViewModel provides vm,
                    LocalHapticFeedback provides recorder,
                ) {
                    Surface { content(vm) }
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun textShown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    protected fun waitForText(text: String) {
        compose.waitUntil(10_000) { textShown(text) }
        compose.waitForIdle()
    }

    protected companion object {
        const val PHONE = "w411dp-h891dp-port"

        // A 10-inch tablet held sideways: list and detail side by side.
        const val TABLET = "w1280dp-h800dp-land"
    }
}
