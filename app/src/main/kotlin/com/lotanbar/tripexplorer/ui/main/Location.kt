package com.lotanbar.tripexplorer.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun isGpsEnabled(context: Context): Boolean =
    runCatching { context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

/**
 * The GPS position for Add POI: the first fix taken after the button was pressed, never an older one
 * (not the recording's last fix, not a cached one). If GPS is off (not recording, or paused), it is
 * on just for this and off again after; while recording, this asks for its own unbatched fix.
 */
@SuppressLint("MissingPermission")
suspend fun currentGpsLocation(context: Context, timeoutMs: Long = 30_000L): Location? {
    val pressedNanos = SystemClock.elapsedRealtimeNanos()
    val lm = context.getSystemService(LocationManager::class.java)
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.elapsedRealtimeNanos < pressedNanos || !cont.isActive) return
                    lm.removeUpdates(this)
                    cont.resume(location)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            val request = LocationRequest.Builder(1_000L).setMinUpdateDistanceMeters(0f).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            runCatching {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, ContextCompat.getMainExecutor(context), listener)
            }.onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }
}
