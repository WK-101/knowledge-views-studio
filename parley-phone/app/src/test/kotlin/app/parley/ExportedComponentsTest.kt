package app.parley

import android.app.Application
import android.content.ComponentName
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.parley.security.LockedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every component another app (or the system) can reach, read from the merged manifest. Each must be listed here with
 * its protection: an exported activity must extend [LockedActivity] (app lock, window protection, locale), and an
 * exported service, receiver or provider must require the permission listed. A new exported component fails this test
 * until someone decides how it is protected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExportedComponentsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private companion object {
        const val BIND_TILE = "android.permission.BIND_QUICK_SETTINGS_TILE"

        /** Exported activities: all of them are entry points behind the app lock. */
        val ACTIVITIES = setOf(
            "app.parley.MainActivity",
            "app.parley.picker.PickerActivity",
            "app.parley.messaging.NumberActionActivity",
            "app.parley.shortcuts.DialWidgetConfigActivity",
            "app.parley.shortcuts.FavoritesWidgetConfigActivity",
        )

        /** Exported services, receivers and providers, with the permission a caller must hold. */
        val PROTECTED = mapOf(
            // Quick Settings tiles: only the system UI can bind.
            "app.parley.security.VaultTileService" to BIND_TILE,
            "app.parley.messaging.MessageNumberTileService" to BIND_TILE,
            "app.parley.ui.qr.QrScanTileService" to BIND_TILE,
            "app.parley.blocking.ExpectingCallTileService" to BIND_TILE,
            "app.parley.telecom.HangUpTileService" to BIND_TILE,
            // Telecom binds these as the default phone app and call screener.
            "app.parley.telecom.ParleyInCallService" to "android.permission.BIND_INCALL_SERVICE",
            "app.parley.telecom.ParleyCallScreeningService" to "android.permission.BIND_SCREENING_SERVICE",
            // The system's "missed call" broadcast to the default dialer.
            "app.parley.MissedCallReceiver" to "android.permission.MODIFY_PHONE_STATE",
            // Approved apps only (signature-pinned approvals inside); the directory is reached through the Contacts Provider.
            "app.parley.privatenames.PrivateNameProvider" to "app.parley.permission.LOOKUP_PRIVATE_NAME_DEBUG",
            "app.parley.privatenames.PrivateDirectoryProvider" to "android.permission.READ_CONTACTS",
            // Libraries: the job scheduler and the profile installer (adb / the system only).
            "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE",
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
        )
    }

    private fun packageInfo() = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS,
    )

    private fun exported(): List<ComponentInfo> = with(packageInfo()) {
        listOfNotNull(activities, services, receivers, providers).flatMap { it.toList() }.filter { it.exported }
    }

    @Test fun every_exported_activity_is_a_locked_activity() {
        val activities = packageInfo().activities.orEmpty().filter { it.exported }
        assertEquals(ACTIVITIES, activities.map { it.name }.toSet())
        for (a in activities) {
            val cls = Class.forName(a.name)
            assertTrue("${a.name} must extend LockedActivity", LockedActivity::class.java.isAssignableFrom(cls))
        }
    }

    @Test fun every_other_exported_component_requires_its_permission() {
        val others = exported().filter { it.name !in ACTIVITIES }
        val names = others.map { it.name }.toSet()
        val unexpected = names - PROTECTED.keys
        assertTrue("Exported without a decided protection: $unexpected", unexpected.isEmpty())
        for (c in others) {
            val required = PROTECTED.getValue(c.name)
            val actual = when (c) {
                is android.content.pm.ServiceInfo -> c.permission
                is android.content.pm.ActivityInfo -> c.permission // receivers are ActivityInfo too
                is android.content.pm.ProviderInfo -> c.readPermission
                else -> null
            }
            // Debug builds use their own names for Parley's permissions, so both builds can be installed.
            assertTrue("${c.name} requires $actual, not $required", actual == required || actual == required.removeSuffix("_DEBUG"))
        }
    }

    /**
     * The missed-call "Call back" alias opens the number sheet from Parley's own notification only: it stays unexported,
     * and the activity behind it is a [LockedActivity] like every entry point.
     */
    @Test fun the_missed_call_back_alias_is_private_and_locked() {
        val alias = packageInfo().activities.orEmpty().firstOrNull { it.name == "app.parley.messaging.MissedCallBack" }
            ?: context.packageManager.getActivityInfo(ComponentName(context, "app.parley.messaging.MissedCallBack"), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertTrue("MissedCallBack must not be exported", !alias.exported)
        val target = alias.targetActivity ?: alias.name
        assertTrue(LockedActivity::class.java.isAssignableFrom(Class.forName(target)))
    }

    @Test fun parleys_own_providers_are_not_readable_by_other_apps() {
        for (name in listOf("app.parley.privatenames.VaultPhotoProvider", "androidx.core.content.FileProvider")) {
            val info = packageInfo().providers.orEmpty().firstOrNull { it.name == name } ?: continue
            assertTrue("$name must not be exported", !info.exported)
        }
        // And a component that isn't in the manifest at all is simply absent.
        assertTrue(runCatching { context.packageManager.getActivityInfo(ComponentName(context, "app.parley.Nope"), 0) }.isFailure)
    }
}
