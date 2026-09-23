package com.lotanbar.tripexplorer.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.lotanbar.tripexplorer.service.RecordingService
import com.lotanbar.tripexplorer.service.RecordingState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun isGpsEnabled(context: Context): Boolean =
    runCatching { context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

/**
 * The current GPS position for Add POI. While recording (not paused) the newest fix is used;
 * otherwise GPS is turned on for a single fix and off again after.
 */
@SuppressLint("MissingPermission")
suspend fun currentGpsLocation(context: Context, timeoutMs: Long = 60_000L): Location? {
    val active = RecordingService.state.value as? RecordingState.Active
    if (active != null && !active.paused) {
        val fix = RecordingService.lastFix.value
        if (fix != null && System.currentTimeMillis() - fix.time < 15_000L) return fix
    }
    val lm = context.getSystemService(LocationManager::class.java)
    return withTimeoutOrNull(timeoutMs) {
        // getCurrentLocation gives up on its own after ~30 s; ask again until our own timeout.
        var result: Location? = null
        while (result == null) {
            result = suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                runCatching {
                    lm.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, ContextCompat.getMainExecutor(context)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
        result
    }
}
