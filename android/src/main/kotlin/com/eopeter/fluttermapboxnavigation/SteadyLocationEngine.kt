package com.eopeter.fluttermapboxnavigation

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import kotlin.math.max
import kotlin.math.min

/**
 * The phone's location, steadied while the driver is not moving.
 *
 * A phone at rest still reports fixes that wander: a few metres on GPS,
 * tens of metres indoors. The navigator reads every wander as driving. It
 * snaps the driver onto whichever street is nearest, sometimes re-routes,
 * and the map swings round to follow. Standing in a loading bay, the
 * driver watches the marker twitch between two streets.
 *
 * So while the phone does not report real movement, a fix that lands near
 * where the driver already is gets handed on as "still there, standing
 * still". The moment the phone reports speed, fixes pass untouched; a fix
 * that lands further off moves the driver there.
 */
class SteadyLocationEngine(private val source: LocationEngine) : LocationEngine {
    private val steadier = LocationSteadier()
    private val relays =
        HashMap<LocationEngineCallback<LocationEngineResult>, LocationEngineCallback<LocationEngineResult>>()

    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        source.getLastLocation(callback)
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?,
    ) {
        val relay = object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult) {
                callback.onSuccess(LocationEngineResult.create(result.locations.map(steadier::steadied)))
            }

            override fun onFailure(exception: Exception) {
                callback.onFailure(exception)
            }
        }
        synchronized(relays) { relays[callback] = relay }
        source.requestLocationUpdates(request, relay, looper)
    }

    override fun requestLocationUpdates(request: LocationEngineRequest, pendingIntent: PendingIntent?) {
        source.requestLocationUpdates(request, pendingIntent)
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        val relay = synchronized(relays) { relays.remove(callback) }
        source.removeLocationUpdates(relay ?: callback)
    }

    override fun removeLocationUpdates(pendingIntent: PendingIntent?) {
        source.removeLocationUpdates(pendingIntent)
    }
}

class LocationSteadier {
    companion object {
        /**
         * Below this the phone is not telling us the driver is moving.
         * GPS at rest reads well under it; a van pulling away is over it
         * within a second.
         */
        const val MOVING_SPEED = 0.7f
    }

    /** Where the driver is taken to be standing. */
    private var held: Location? = null

    @Synchronized
    fun steadied(fix: Location): Location {
        val speedKnown = fix.hasSpeed()
        if (speedKnown && fix.speed >= MOVING_SPEED) {
            held = fix
            return fix
        }
        val spot = held
        if (spot != null && fix.distanceTo(spot) <= slack(fix, speedKnown)) {
            return standing(spot, fix)
        }
        // Somewhere new. Without a speed from the phone it is a place the
        // driver is standing, not one they are passing through.
        held = fix
        return if (speedKnown) fix else standing(fix, fix)
    }

    /**
     * How far a fix may land from the held spot and still count as the
     * same spot. A phone that says "not moving" is believed over a wide
     * margin: its speed is measured, its position is the part that
     * wanders. With no speed at all there is less to go on, so less is
     * forgiven and a driver who really is moving is not pinned for long.
     */
    private fun slack(fix: Location, speedKnown: Boolean): Float {
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f
        return if (speedKnown) min(max(accuracy * 3, 30f), 75f) else min(max(accuracy * 2, 10f), 40f)
    }

    private fun standing(spot: Location, fix: Location): Location = Location(fix).apply {
        latitude = spot.latitude
        longitude = spot.longitude
        speed = 0f
        if (spot.hasBearing()) bearing = spot.bearing else removeBearing()
    }
}
