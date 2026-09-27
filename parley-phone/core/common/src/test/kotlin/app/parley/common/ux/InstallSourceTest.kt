package app.parley.common.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceTest {
    @Test fun stores_are_not_sideloads_unknown_installers_are() {
        assertFalse(InstallSource.isSideloaded("com.android.vending"))
        assertFalse(InstallSource.isSideloaded("org.fdroid.fdroid"))
        assertTrue(InstallSource.isSideloaded(null))
        assertTrue(InstallSource.isSideloaded(""))
        assertTrue(InstallSource.isSideloaded("com.google.android.packageinstaller"))
        assertTrue(InstallSource.isSideloaded("com.example.filemanager"))
    }

    @Test fun restricted_settings_help_only_from_android_13() {
        assertTrue(InstallSource.needsRestrictedSettingsHelp(null, 33))
        assertTrue(InstallSource.needsRestrictedSettingsHelp("com.android.packageinstaller", 36))
        assertFalse(InstallSource.needsRestrictedSettingsHelp(null, 32))
        assertFalse(InstallSource.needsRestrictedSettingsHelp("com.android.vending", 35))
    }
}
