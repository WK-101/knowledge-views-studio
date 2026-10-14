package app.parley.ui

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.AppViewModel
import app.parley.ParleyApp
import app.parley.RecentFilter
import app.parley.UiEvent
import app.parley.common.StartTab
import app.parley.common.ThemeMode
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.ui.common.CoachMarks
import app.parley.ui.common.LocalCoachMarks
import app.parley.ui.home.HomeScreen
import app.parley.ui.home.RecentsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Deleting a call from a filtered Recents list (here the Missed chip) offers Undo on the snackbar, exactly as the
 * unfiltered list does: the selection bar reads the same filtered list the rows came from, and the delete routes
 * through the view model's Undo path. A phone layout, so there is one list and the home selection bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class, qualifiers = "w411dp-h891dp-port")
class RecentsFilteredUndoTest {
    @get:Rule val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before fun setUp() {
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
            c.contacts.save(
                null,
                ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
                null, null, false,
            )
        }
        val now = System.currentTimeMillis()
        // A missed call and an answered one from Ada, so the Missed chip narrows the list to the missed row.
        listOf(CallLog.Calls.MISSED_TYPE to 0L, CallLog.Calls.INCOMING_TYPE to 60L).forEachIndexed { i, (type, length) ->
            app.contentResolver.insert(
                CallLog.Calls.CONTENT_URI,
                ContentValues().apply {
                    put(CallLog.Calls.NUMBER, "+442079460000")
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

    @Test fun deleting_from_the_missed_filter_offers_undo() {
        val events = mutableListOf<UiEvent>()
        val vm = ViewModelProvider(activity)[AppViewModel::class.java].also { it.start() }
        val recents = ViewModelProvider(activity, ParleyViewModels.Factory)[RecentsViewModel::class.java]
        activity.setContent {
            ParleyTheme(mode = ThemeMode.LIGHT, amoled = false, dynamicColor = false) {
                ProvideSnackbar { snackbar ->
                    // The app's own event-to-snackbar bridge (ParleyRoot), so this test sees the real Undo snackbar.
                    LaunchedEffect(Unit) {
                        vm.uiEvents.collect { e ->
                            events += e
                            // The test only checks that Undo is offered, so it shows the snackbar (never taps it).
                            when (e) {
                                is UiEvent.UndoCalls -> snackbar.state.showSnackbar(e.text, "Undo", duration = SnackbarDuration.Long)
                                is UiEvent.UndoAction -> snackbar.state.showSnackbar(e.text, "Undo", duration = SnackbarDuration.Long)
                                is UiEvent.Message -> snackbar.state.showSnackbar(e.text)
                                else -> Unit
                            }
                        }
                    }
                    CompositionLocalProvider(
                        LocalCoachMarks provides remember { CoachMarks(vm.c.ux) },
                        LocalAppViewModel provides vm,
                    ) {
                        Surface { HomeScreen(vm, tabRequest = null, onTabRequestHandled = {}, initialTab = StartTab.RECENTS, open = {}) }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Narrow to the missed call, then wait for its row to load (it comes off the main thread).
        recents.filter.value = RecentFilter.MISSED
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ada Lovelace", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Ada Lovelace", substring = true)[0].assertExists()
        // Long-press to select the row, then delete it from the selection bar.
        compose.onAllNodesWithText("Ada Lovelace", substring = true)[0].performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Delete the selected calls from history").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete the selected calls from history").performClick()
        // The Undo snackbar appears, as it does in the unfiltered list.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("an Undo event was offered", events.any { it is UiEvent.UndoAction || it is UiEvent.UndoCalls })
    }
}
