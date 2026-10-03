package app.parley.data.calls

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import app.parley.data.CallLogRepository
import app.parley.data.testing.FakeCallLogProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Recents marks a call Android logged as video (`FEATURES_VIDEO`), whatever other feature bits come with it. */
@RunWith(RobolectricTestRunner::class)
class CallLogVideoTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun video_calls_are_read_from_the_features_column() {
        val provider = FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        fun call(date: Long, features: Int) = ContentValues().apply {
            put(Calls.NUMBER, "+442079460000")
            put(Calls.DATE, date)
            put(Calls.TYPE, Calls.INCOMING_TYPE)
            put(Calls.FEATURES, features)
        }
        provider.insert(Calls.CONTENT_URI, call(1_000L, 0))
        provider.insert(Calls.CONTENT_URI, call(2_000L, Calls.FEATURES_VIDEO or Calls.FEATURES_HD_CALL))
        provider.insert(Calls.CONTENT_URI, call(3_000L, Calls.FEATURES_WIFI))
        val repo = CallLogRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SharingStarted.Lazily)
        val byDate = repo.queryForNumber("+442079460000").associate { it.date to it.video }
        assertEquals(mapOf(1_000L to false, 2_000L to true, 3_000L to false), byDate)
    }
}
