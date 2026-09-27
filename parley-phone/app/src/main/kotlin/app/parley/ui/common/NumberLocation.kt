package app.parley.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import app.parley.data.NumberInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Where is this number from", worked out off the main thread: the geocoder loads large data files the first time a
 * country is asked for, which would stall a scrolling list. An answer already known shows in the first frame.
 */
@Composable
fun rememberNumberLocation(number: String?, countryIso: String, enabled: Boolean = true): String? {
    val known = if (enabled) NumberInfo.cachedLocation(number, countryIso) else null
    return produceState(known, number, countryIso, enabled) {
        value = if (!enabled || number.isNullOrBlank()) null else known ?: withContext(Dispatchers.IO) { NumberInfo.location(number, countryIso) }
    }.value
}
