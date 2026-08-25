package com.todocompanion.app.reminders

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot location capture via the framework [LocationManager] — no Google Play Services, so it
 * works on any device (that was the point of the offline design). [lastKnown] is instant but often
 * null; [requestFix] actively asks a provider for a single fresh fix and always calls back once,
 * within [timeoutMs]. Callers must already hold a fine/coarse location permission.
 */
object LocationFix {
    fun lastKnown(context: Context): Pair<Double, Double>? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching {
            lm.getProviders(true)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
        return loc?.let { it.latitude to it.longitude }
    }

    fun requestFix(context: Context, timeoutMs: Long = 12_000, onResult: (Pair<Double, Double>?) -> Unit) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
        // Ask EVERY enabled provider at once and take whichever answers first. GPS alone starves
        // indoors — exactly where these reminders get set up — so the network provider must run too.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return onResult(lastKnown(context))
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        val cancels = mutableListOf<CancellationSignal>()
        val listeners = mutableListOf<LocationListener>()
        fun finish(fix: Pair<Double, Double>?) {
            if (done.compareAndSet(false, true)) {
                cancels.forEach { runCatching { it.cancel() } }
                listeners.forEach { l -> runCatching { lm.removeUpdates(l) } }
                main.post { onResult(fix ?: lastKnown(context)) }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                providers.forEach { p ->
                    val cancel = CancellationSignal(); cancels += cancel
                    lm.getCurrentLocation(p, cancel, ContextCompat.getMainExecutor(context)) { loc: Location? ->
                        if (loc != null) finish(loc.latitude to loc.longitude)   // null = this provider gave up; let others / timeout decide
                    }
                }
            } else {
                providers.forEach { p ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) { finish(loc.latitude to loc.longitude) }
                        override fun onStatusChanged(pr: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(pr: String) {}
                        override fun onProviderDisabled(pr: String) {}
                    }
                    listeners += listener
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                }
            }
            main.postDelayed({ if (!done.get()) finish(null) }, timeoutMs)
        }.onFailure { finish(null) }
    }
}
