package app.parley.data.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.DataContainer
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every screen following the contacts' notes shares one opened copy, which follows changes. */
@RunWith(RobolectricTestRunner::class)
class SharedMetaTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun one_shared_flow_opened_once_per_change() = runBlocking {
        assertSame(c.meta.allMeta(), c.meta.allMeta())
        c.meta.setMeta(ContactMetaEntity("lk-ana", pinnedNote = "Owes me the ladder", contactId = 1))
        val rows = withTimeout(5_000) { c.meta.allMeta().first { list -> list.any { it.pinnedNote == "Owes me the ladder" } } }
        assertEquals("lk-ana", rows.single().lookupKey)
    }
}
