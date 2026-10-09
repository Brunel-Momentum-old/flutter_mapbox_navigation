package com.eopeter.fluttermapboxnavigation

import android.app.PendingIntent
import android.location.Location
import android.os.Bundle
import android.os.Handler
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
    companion object {
        /** Marks a fix this engine made up, so the host does not count it as the phone speaking. */
        const val MADE_UP = "hostMadeUpFix"

        /**
         * How long the phone may say nothing before a moving driver is
         * taken to have been lost. Some phones report only every few
         * seconds while all is well; anything much shorter than this
         * stood those drivers still between two ordinary fixes.
         */
        private const val SILENCE_MS = 8000L
    }

    /**
     * Whether the route has the driver in a tunnel. This engine knows
     * nothing of the route, so whoever watches the route's progress keeps
     * it up to date.
     */
    @Volatile
    var inTunnel = false

    private val steadier = LocationSteadier()
    private val relays =
        HashMap<LocationEngineCallback<LocationEngineResult>, LocationEngineCallback<LocationEngineResult>>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastReal: Location? = null
    private val silence = Runnable { onSilence() }

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
                result.lastLocation?.let { lastReal = it }
                handler.removeCallbacks(silence)
                handler.postDelayed(silence, SILENCE_MS)
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
        val relay = synchronized(relays) {
            val removed = relays.remove(callback)
            if (relays.isEmpty()) handler.removeCallbacks(silence)
            removed
        }
        source.removeLocationUpdates(relay ?: callback)
    }

    override fun removeLocationUpdates(pendingIntent: PendingIntent?) {
        source.removeLocationUpdates(pendingIntent)
    }

    /**
     * The phone has stopped reporting while the driver was moving. Left
     * alone the navigator carries them on along the route at their last
     * speed, as far as the stop. It is told once that they are standing
     * where they were last seen, and holds them there until the phone
     * speaks again.
     *
     * Not in a tunnel. No fix arrives in one, and there the navigator
     * carrying the driver along the route is exactly what is wanted:
     * told "standing", it kept them at the tunnel mouth for the whole
     * length of it.
     */
    private fun onSilence() {
        if (inTunnel) return
        val last = lastReal ?: return
        if (!last.hasSpeed() || last.speed < LocationSteadier.MOVING_SPEED) return
        val standing = Location(last).apply {
            speed = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            extras = (extras ?: Bundle()).apply { putBoolean(MADE_UP, true) }
        }
        lastReal = standing
        val listeners = synchronized(relays) { relays.keys.toList() }
        for (listener in listeners) listener.onSuccess(LocationEngineResult.create(standing))
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
         * A fix this old when it arrives is the phone's cached one, not
         * where the driver is now.
         */
        const val STALE_AFTER_MS = 10_000L

        /**
         * How many of the latest fixes are looked at to tell a driver
         * from a phone that is only wandering.
         */
        const val WINDOW_FIXES = 6

        /** Fewer fixes than this are too few to say the driver is going anywhere. */
        const val FIXES_TO_SHOW_TRAVEL = 4

        /**
         * The latest fixes have to cover at least this many metres from
         * one to the next, all told, before they can count as travel.
         */
        const val TRAVEL_PATH_M = 15f

        /**
         * And they have to end at least this share of that distance from
         * where they began. A driver's fixes line up; a parked phone's
         * wander doubles back on itself.
         */
        const val TRAVEL_STRAIGHTNESS = 0.8f

        /**
         * A phone that reports no speed gives nothing to go on but where
         * its fixes land. At least this many of the latest, all landing
         * together, is a driver who has stopped.
         */
        const val QUIET_FIXES_TO_REST = 4

        /**
         * At rest, the spot is moved to the newest fix this often,
         * whatever accuracy either claims: a poor fix that claimed to be
         * a good one could otherwise pin the driver in the wrong place
         * for as long as they stood there.
         */
        const val REFRESH_REST_EVERY_MS = 30_000L
    }

    /** Where the driver is taken to be standing. Null while they are, or may be, moving. */
    private var held: Location? = null
    private var heldSinceMs = 0L

    /**
     * The latest fresh fixes, whatever was done with them. Never cleared,
     * only trimmed: what led up to the driver being let go still counts
     * straight after it.
     */
    private val window = ArrayList<Location>()

    @Synchronized
    fun steadied(fix: Location, nowMs: Long = SystemClock.elapsedRealtime()): Location {
        // A cached fix says where the driver was. It goes through as it
        // is and decides nothing.
        if (nowMs - fix.elapsedRealtimeNanos / 1_000_000L > STALE_AFTER_MS) return fix

        val speedKnown = fix.hasSpeed()
        if (speedKnown && fix.speed >= MOVING_SPEED) {
            held = null
            remember(fix)
            return fix
        }
        remember(fix)
        val spot = held
        if (spot != null) {
            // The regular refresh: the spot moves to where the phone
            // says it is now.
            if (nowMs - heldSinceMs >= REFRESH_REST_EVERY_MS) {
                rest(fix, nowMs)
                return fix
            }
            // Further than wander explains, or the fixes have set off in
            // a line: they have moved.
            if (fix.distanceTo(spot) > slack(fix, speedKnown) || goingSomewhere()) {
                held = null
                return fix
            }
            return Location(fix).apply {
                latitude = spot.latitude
                longitude = spot.longitude
                speed = 0f
                if (spot.hasBearing()) bearing = spot.bearing else removeBearing()
            }
        }
        // Not at rest. Every fix passes; the only question is whether
        // this one shows the driver has stopped. A slow speed reading is
        // not enough while the fixes themselves run on down the road.
        if (goingSomewhere()) return fix
        if (speedKnown) {
            // The phone measures speed and says "not moving".
            rest(fix, nowMs)
        } else if (window.size >= QUIET_FIXES_TO_REST) {
            // No speed reading at all: only where the fixes land. Several
            // in a row that stay together is a phone going nowhere.
            val first = window.first()
            val reach = slack(fix, speedKnown)
            if (window.all { it.distanceTo(first) <= reach }) rest(fix, nowMs)
        }
        return fix
    }

    private fun remember(fix: Location) {
        window.add(fix)
        if (window.size > WINDOW_FIXES) window.removeAt(0)
    }

    private fun rest(fix: Location, nowMs: Long) {
        held = fix
        heldSinceMs = nowMs
    }

    /**
     * Whether the latest fixes are those of a driver on the move: enough
     * ground covered between them, and most of it in one direction.
     * Judged from where the fixes land, so it holds when the phone's
     * speed reads slow, or is missing, while the driver is moving.
     */
    private fun goingSomewhere(): Boolean {
        if (window.size < FIXES_TO_SHOW_TRAVEL) return false
        var path = 0f
        for (index in 1 until window.size) path += window[index - 1].distanceTo(window[index])
        if (path < TRAVEL_PATH_M) return false
        return window.first().distanceTo(window.last()) / path >= TRAVEL_STRAIGHTNESS
    }

    /**
     * How far a fix may land from the spot and still count as the same
     * spot: twice its own error, within bounds. With a speed reading the
     * bounds are tight, so a driver crawling under walking pace is never
     * left far behind; without one there is only position to go on.
     */
    private fun slack(fix: Location, speedKnown: Boolean): Float {
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f
        return if (speedKnown) min(max(accuracy * 2, 15f), 30f) else min(max(accuracy * 2, 10f), 40f)
    }
}
