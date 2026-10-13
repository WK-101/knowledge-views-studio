package app.parley.data.people

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** "Who can see your contacts" lists, per permission, the home-screen apps that hold it, and never Parley itself. */
@RunWith(RobolectricTestRunner::class)
class ContactsAuditTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pm get() = shadowOf(app.packageManager)

    private fun launcherApp(pkg: String, label: String, vararg granted: String) {
        val ai = ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label }
        pm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ai
                requestedPermissions = arrayOf(*granted)
                requestedPermissionsFlags = IntArray(granted.size) { PackageInfo.REQUESTED_PERMISSION_GRANTED }
            },
        )
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.addResolveInfoForIntent(launcher, ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ai } })
    }

    @Before fun apps() {
        launcherApp("org.example.logger", "Logger", Manifest.permission.READ_CALL_LOG)
        launcherApp("org.example.chat", "Chat", Manifest.permission.READ_CONTACTS)
        launcherApp("com.google.android.dialer", "Phone", Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
        launcherApp("org.example.torch", "Torch")
        launcherApp(app.packageName, "Parley", Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
    }

    @Test fun contacts_by_default() {
        val apps = runBlocking { ContactsAudit(app).appsWithAccess() }
        assertEquals(listOf("org.example.chat", "com.google.android.dialer"), apps.map { it.packageName })
    }

    @Test fun call_history_lists_the_apps_that_can_read_the_call_log() {
        val apps = runBlocking { ContactsAudit(app).appsWithAccess(Manifest.permission.READ_CALL_LOG) }
        assertEquals(listOf("org.example.logger", "com.google.android.dialer"), apps.map { it.packageName })
        // A phone app is told apart, as on the contacts list.
        assertEquals(null, apps[0].note)
        assertEquals(true, apps[1].note != null)
    }
}
