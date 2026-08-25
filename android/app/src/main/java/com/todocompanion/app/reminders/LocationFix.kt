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
        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> return onResult(lastKnown(context))
        }
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        fun finish(fix: Pair<Double, Double>?) { if (done.compareAndSet(false, true)) main.post { onResult(fix ?: lastKnown(context)) } }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { loc: Location? ->
                    finish(loc?.let { it.latitude to it.longitude })
                }
                main.postDelayed({ if (!done.get()) { runCatching { cancel.cancel() }; finish(null) } }, timeoutMs)
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) { finish(loc.latitude to loc.longitude) }
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                main.postDelayed({ if (!done.get()) { runCatching { lm.removeUpdates(listener) }; finish(null) } }, timeoutMs)
            }
        }.onFailure { finish(null) }
    }
}
