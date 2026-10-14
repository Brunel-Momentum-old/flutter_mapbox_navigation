import Foundation

/// The bird's-eye view at a turn: when the following camera goes
/// straight down, and how close it comes.
///
/// A tilted camera sees a long way ahead for its zoom, and a junction
/// seen at a slant is hard to place: which of two side streets, how far
/// off. So a few seconds before a turn, a fork, a ramp or a roundabout
/// the camera eases to straight down, heading still up, with the driver
/// and the turn both on screen; it stays there through the turn and
/// eases back once past it.
///
/// Nothing in here touches a map, so it can be checked on its own
/// (`tool/check_turn_view.sh`). Android has the same numbers and the
/// same rules in `TurnView.kt`; change them together.
enum TurnView {
    /// The view comes up this many seconds of driving before the turn...
    static let leadSeconds = 15.0
    /// ...but never later than this many metres out (crawling in traffic),
    static let minTriggerMetres = 120.0
    /// and never earlier than this (a motorway at speed).
    static let maxTriggerMetres = 600.0
    /// Once up for a turn it stays up for that turn whatever the speed
    /// does, unless the turn is this much further off than when it came
    /// up (a new route).
    static let releaseSlackMetres = 60.0
    /// How far past the turn the view is held before easing back.
    static let holdAfterMetres = 40.0
    /// How much of the road after the turn is kept on screen.
    static let frameAfterMetres = 60.0
    /// How long the tilt takes to go flat, and to come back.
    static let easeSeconds = 1.8
    /// The most the zoom may change in a second.
    static let zoomPerSecond = 0.6
    /// As close as the bird's-eye view comes: about 120 m of road ahead
    /// on a phone. Closer, and a van crawling up to a junction sees only
    /// the junction.
    static let maxZoom = 17.5
    /// As far out as it goes, for a turn 600 m off.
    static let minZoom = 14.5
    /// The share of the clear map the driver and the turn are fitted to.
    static let frameFill = 0.8
    /// Where the tilted camera's zoom is handed back to the SDK from:
    /// the closest the SDK's own following camera comes.
    static let tiltedClosestZoom = 16.35

    struct State: Equatable {
        var topDown = false
        /// The step the view came up on.
        var stepKey = -1
        /// How far off the turn was when it came up.
        var latchMetres = 0.0
    }

    /// True for what the driver has to pick a way at: a turn, a fork, a
    /// ramp, a roundabout. False for carrying straight on, a change of
    /// street name, setting off and arriving. `type` and `modifier` are
    /// the directions API's own words.
    static func needsChoice(type: String?, modifier: String?) -> Bool {
        let turning = ["left", "right", "sharp left", "sharp right", "uturn"].contains(modifier ?? "")
        switch type ?? "" {
        case "turn", "end of road":
            return modifier != "straight"
        case "fork", "off ramp", "on ramp", "roundabout", "rotary", "roundabout turn",
             "exit roundabout", "exit rotary":
            return true
        case "depart", "arrive", "notification", "":
            return false
        default:
            // "continue", "new name", "merge", "use lane": only where
            // the road itself turns.
            return turning
        }
    }

    /// How far before a turn the view comes up, at `speed` metres a second.
    static func triggerMetres(speed: Double) -> Double {
        let pace = speed.isFinite ? max(0, speed) : 0
        return min(maxTriggerMetres, max(minTriggerMetres, pace * leadSeconds))
    }

    /// The view for this tick, from the view of the last one.
    ///
    /// `stepKey` names the step being driven; `distanceToManeuver` is
    /// what is left of it and `distanceIntoStep` what has been driven of
    /// it, in metres. `needsChoice` is false with no route to trust (off
    /// route, re-routing) as well as for a step that ends in no turn.
    static func next(_ state: State,
                     stepKey: Int,
                     needsChoice: Bool,
                     distanceToManeuver: Double?,
                     distanceIntoStep: Double,
                     speed: Double) -> State {
        if needsChoice, let distance = distanceToManeuver, distance.isFinite {
            // Up for this turn already: braking for it must not take the
            // view away, and nor must a fix that jumps back a few metres.
            if state.topDown, state.stepKey == stepKey, distance <= state.latchMetres + releaseSlackMetres {
                return state
            }
            if distance <= triggerMetres(speed: speed) {
                return State(topDown: true, stepKey: stepKey, latchMetres: distance)
            }
        }
        // Just through a turn: held until the van is round it. A turn
        // that follows at once is caught above, so the view never bobs
        // up and down between two turns.
        if state.topDown, distanceIntoStep.isFinite, distanceIntoStep < holdAfterMetres {
            return state
        }
        return State(topDown: false, stepKey: stepKey, latchMetres: 0)
    }

    /// The zoom at which everything in `points` is on screen, looking
    /// straight down with the driver at the bottom of the clear map.
    ///
    /// Each point is how far ahead of the driver and how far to one side
    /// it lies, in metres. `aheadPoints` is the height of clear map above
    /// the driver's marker and `halfWidthPoints` the width to either
    /// side, in screen points.
    static func fitZoom(points: [(ahead: Double, side: Double)],
                        aheadPoints: Double,
                        halfWidthPoints: Double,
                        latitude: Double) -> Double {
        var metresPerPoint = 0.0
        for point in points {
            if aheadPoints > 0 { metresPerPoint = max(metresPerPoint, point.ahead / (aheadPoints * frameFill)) }
            if halfWidthPoints > 0 { metresPerPoint = max(metresPerPoint, abs(point.side) / (halfWidthPoints * frameFill)) }
        }
        guard metresPerPoint > 0 else { return maxZoom }
        // Metres to a point at zoom 0 on the equator, for 512-point tiles.
        let zoom = log2(78271.517 * cos(latitude * .pi / 180) / metresPerPoint)
        return min(maxZoom, max(minZoom, zoom))
    }

    /// `from` moved towards `to` by no more than `most`.
    static func step(from: Double, to: Double, most: Double) -> Double {
        if abs(to - from) <= most { return to }
        return from + (to > from ? most : -most)
    }
}
