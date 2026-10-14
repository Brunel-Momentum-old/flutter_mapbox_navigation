package com.eopeter.fluttermapboxnavigation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The bird's-eye view at a turn: when the following camera goes straight
 * down, and how close it comes.
 *
 * A tilted camera sees a long way ahead for its zoom, and a junction
 * seen at a slant is hard to place: which of two side streets, how far
 * off. So a few seconds before a turn, a fork, a ramp or a roundabout
 * the camera eases to straight down, heading still up, with the driver
 * and the turn both on screen; it stays there through the turn and eases
 * back once past it.
 *
 * Nothing in here touches a map. iOS has the same numbers and the same
 * rules in `TurnView.swift`, where they are checked
 * (`tool/check_turn_view.sh`); change them together.
 */
object TurnView {
    /** The view comes up this many seconds of driving before the turn... */
    const val LEAD_SECONDS = 15.0

    /** ...but never later than this many metres out (crawling in traffic), */
    const val MIN_TRIGGER_METRES = 120.0

    /** and never earlier than this (a motorway at speed). */
    const val MAX_TRIGGER_METRES = 600.0

    /**
     * Once up for a turn it stays up for that turn whatever the speed
     * does, unless the turn is this much further off than when it came
     * up (a new route).
     */
    const val RELEASE_SLACK_METRES = 60.0

    /** How far past the turn the view is held before easing back. */
    const val HOLD_AFTER_METRES = 40.0

    /** How much of the road after the turn is kept on screen. */
    const val FRAME_AFTER_METRES = 60.0

    /** How long the tilt takes to go flat, and to come back. */
    const val EASE_SECONDS = 1.8

    /** The most the zoom may change in a second. */
    const val ZOOM_PER_SECOND = 0.6

    /**
     * As close as the bird's-eye view comes: about 120 m of road ahead
     * on a phone. Closer, and a van crawling up to a junction sees only
     * the junction.
     */
    const val MAX_ZOOM = 17.5

    /** As far out as it goes, for a turn 600 m off. */
    const val MIN_ZOOM = 14.5

    /** The share of the clear map the driver and the turn are fitted to. */
    const val FRAME_FILL = 0.8

    /**
     * Where the tilted camera's zoom is handed back to the SDK from: the
     * closest the SDK's own following camera comes.
     */
    const val TILTED_CLOSEST_ZOOM = 16.35

    /**
     * [stepKey] is the step the view came up on, [latchMetres] how far
     * off the turn was when it did.
     */
    data class State(
        val topDown: Boolean = false,
        val stepKey: Int = -1,
        val latchMetres: Double = 0.0,
    )

    /** How far ahead of the driver a point lies, and how far to one side, in metres. */
    data class Offset(val ahead: Double, val side: Double)

    private val TURNING = setOf("left", "right", "sharp left", "sharp right", "uturn")

    /**
     * True for what the driver has to pick a way at: a turn, a fork, a
     * ramp, a roundabout. False for carrying straight on, a change of
     * street name, setting off and arriving. [type] and [modifier] are
     * the directions API's own words.
     */
    fun needsChoice(type: String?, modifier: String?): Boolean = when (type ?: "") {
        "turn", "end of road" -> modifier != "straight"
        "fork", "off ramp", "on ramp", "roundabout", "rotary", "roundabout turn",
        "exit roundabout", "exit rotary" -> true
        "depart", "arrive", "notification", "" -> false
        // "continue", "new name", "merge", "use lane": only where the
        // road itself turns.
        else -> (modifier ?: "") in TURNING
    }

    /** How far before a turn the view comes up, at [speed] metres a second. */
    fun triggerMetres(speed: Double): Double {
        val pace = if (speed.isFinite()) max(0.0, speed) else 0.0
        return min(MAX_TRIGGER_METRES, max(MIN_TRIGGER_METRES, pace * LEAD_SECONDS))
    }

    /**
     * The view for this tick, from the view of the last one.
     *
     * [stepKey] names the step being driven; [distanceToManeuver] is
     * what is left of it and [distanceIntoStep] what has been driven of
     * it, in metres. [needsChoice] is false with no route to trust (off
     * route, re-routing) as well as for a step that ends in no turn.
     */
    fun next(
        state: State,
        stepKey: Int,
        needsChoice: Boolean,
        distanceToManeuver: Double?,
        distanceIntoStep: Double,
        speed: Double,
    ): State {
        if (needsChoice && distanceToManeuver != null && distanceToManeuver.isFinite()) {
            // Up for this turn already: braking for it must not take the
            // view away, and nor must a fix that jumps back a few metres.
            if (state.topDown && state.stepKey == stepKey &&
                distanceToManeuver <= state.latchMetres + RELEASE_SLACK_METRES
            ) {
                return state
            }
            if (distanceToManeuver <= triggerMetres(speed)) {
                return State(topDown = true, stepKey = stepKey, latchMetres = distanceToManeuver)
            }
        }
        // Just through a turn: held until the van is round it. A turn
        // that follows at once is caught above, so the view never bobs
        // up and down between two turns.
        if (state.topDown && distanceIntoStep.isFinite() && distanceIntoStep < HOLD_AFTER_METRES) {
            return state
        }
        return State(topDown = false, stepKey = stepKey, latchMetres = 0.0)
    }

    /**
     * The zoom at which everything in [points] is on screen, looking
     * straight down with the driver at the bottom of the clear map.
     * [aheadPoints] is the height of clear map above the driver's marker
     * and [halfWidthPoints] the width to either side, in density-
     * independent pixels.
     */
    fun fitZoom(points: List<Offset>, aheadPoints: Double, halfWidthPoints: Double, latitude: Double): Double {
        var metresPerPoint = 0.0
        for (point in points) {
            if (aheadPoints > 0) metresPerPoint = max(metresPerPoint, point.ahead / (aheadPoints * FRAME_FILL))
            if (halfWidthPoints > 0) {
                metresPerPoint = max(metresPerPoint, abs(point.side) / (halfWidthPoints * FRAME_FILL))
            }
        }
        if (metresPerPoint <= 0) return MAX_ZOOM
        // Metres to a point at zoom 0 on the equator, for 512-point tiles.
        val zoom = ln(78271.517 * cos(latitude * PI / 180) / metresPerPoint) / ln(2.0)
        return min(MAX_ZOOM, max(MIN_ZOOM, zoom))
    }

    /** [from] moved towards [to] by no more than [most]. */
    fun step(from: Double, to: Double, most: Double): Double {
        if (abs(to - from) <= most) return to
        return from + if (to > from) most else -most
    }
}
