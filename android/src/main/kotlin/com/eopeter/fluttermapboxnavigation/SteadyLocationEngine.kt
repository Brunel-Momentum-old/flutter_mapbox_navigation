package com.eopeter.fluttermapboxnavigation

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import android.os.SystemClock
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
 * So once the driver has come to rest, a fix that lands near where they
 * already are is handed on as "still there, standing still". That is the
 * only thing this ever changes. A fix from a driver who is moving, or who
 * has moved, goes through exactly as the phone reported it: telling the
 * navigator "standing still" about a position that has moved makes it
 * distrust the fix and freeze the route where it started.
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
         * At or above this the phone is telling us the driver is moving.
         * GPS at rest reads well under it; a van pulling away is over it
         * within a second.
         */
        const val MOVING_SPEED = 0.7f

        /**
         * A phone that reports no speed gives nothing to go on but where
         * its fixes land. This many in a row landing together is a driver
         * who has stopped.
         */
        const val QUIET_FIXES_TO_REST = 4

        /**
         * At rest, the spot is moved to a newer fix of equal or better
         * accuracy this often, so an early poor fix cannot pin the driver
         * in the wrong place for long.
         */
        const val REFRESH_REST_EVERY_MS = 30_000L
    }

    /** Where the driver is taken to be standing. Null while they are, or may be, moving. */
    private var held: Location? = null
    private var heldSinceMs = 0L

    /** The latest fixes while not at rest, for telling when a driver with no speed reading has stopped. */
    private val recent = ArrayList<Location>()

    @Synchronized
    fun steadied(fix: Location, nowMs: Long = SystemClock.elapsedRealtime()): Location {
        val speedKnown = fix.hasSpeed()
        if (speedKnown && fix.speed >= MOVING_SPEED) {
            moving()
            return fix
        }
        val spot = held
        if (spot != null) {
            // A clearly better fix, or the regular refresh, moves the spot.
            val better = accuracyOf(fix) <= accuracyOf(spot) * 0.67f
            val due = nowMs - heldSinceMs >= REFRESH_REST_EVERY_MS && accuracyOf(fix) <= accuracyOf(spot)
            if (better || due) {
                rest(fix, nowMs)
                return fix
            }
            if (fix.distanceTo(spot) <= slack(fix, speedKnown)) {
                return Location(fix).apply {
                    latitude = spot.latitude
                    longitude = spot.longitude
                    speed = 0f
                    if (spot.hasBearing()) bearing = spot.bearing else removeBearing()
                }
            }
            // Further than wander explains: they have moved.
            moving()
            recent.add(fix)
            return fix
        }
        // Not at rest. Every fix passes; the only question is whether
        // this one shows the driver has stopped.
        if (speedKnown) {
            // The phone measures speed and says "not moving".
            rest(fix, nowMs)
            return fix
        }
        recent.add(fix)
        if (recent.size > QUIET_FIXES_TO_REST) recent.removeAt(0)
        if (recent.size == QUIET_FIXES_TO_REST) {
            val first = recent.first()
            if (recent.all { it.distanceTo(first) <= slack(fix, speedKnown) }) rest(fix, nowMs)
        }
        return fix
    }

    private fun moving() {
        held = null
        recent.clear()
    }

    private fun rest(fix: Location, nowMs: Long) {
        held = fix
        heldSinceMs = nowMs
        recent.clear()
    }

    private fun accuracyOf(fix: Location): Float = if (fix.hasAccuracy()) fix.accuracy else Float.MAX_VALUE

    /**
     * How far a fix may land from the spot and still count as the same
     * spot. A phone that says "not moving" is believed over a wide
     * margin: its speed is measured, its position is the part that
     * wanders. With no speed at all there is less to go on, so less is
     * forgiven.
     */
    private fun slack(fix: Location, speedKnown: Boolean): Float {
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f
        return if (speedKnown) min(max(accuracy * 3, 30f), 75f) else min(max(accuracy * 2, 10f), 40f)
    }
}
