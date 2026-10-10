package app.parley.shortcuts

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming a pinned menu shortcut rebuilds it with the contact's photo, as it was pinned. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MenuShortcutIconTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun rename_keeps_the_photo() {
        val asked = ArrayList<Pair<String, String?>>()
        val icon: (android.content.Context, String, String?) -> IconCompat = { _, name, photo ->
            asked += name to photo
            IconCompat.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        Shortcuts.renameMenu(context, "a", "Bank › cards", "0800123456,,2", "Bank", "content://photos/7", icon)
        assertEquals(listOf("Bank" to "content://photos/7"), asked)
        val info = Shortcuts.menuInfo(context, "a", "Bank › cards", "0800123456,,2", "Bank", "content://photos/7", icon)
        assertEquals("menu-a", info.id)
        assertEquals("Bank › cards", info.longLabel.toString())
    }
}
