import CoreLocation
import MapboxCoreNavigation

/// Set `HOST_NAV_TRACE=1` in the app's environment to log every fix, tick
/// and camera change. Off otherwise; for reading a phone's behaviour over
/// a cable, never for release.
let hostNavTrace = ProcessInfo.processInfo.environment["HOST_NAV_TRACE"] == "1"

func navTrace(_ message: @autoclosure () -> String) {
    if hostNavTrace { NSLog("%@", "NAVTRACE " + message()) }
}

/// The phone's location, steadied while the driver is at rest.
///
/// A phone at rest still reports fixes that wander: a few metres on GPS,
/// tens of metres on Wi-Fi indoors. The navigator reads every wander as
/// driving. It snaps the driver onto whichever street is nearest,
/// sometimes re-routes, and the map swings round to follow.
///
/// So once the driver has come to rest, a fix that lands near where they
/// already are is handed on as "still there, standing still". That is the
/// only thing this ever changes. A fix from a driver who is moving, or
/// who has moved, goes through exactly as the phone reported it: telling
/// the navigator "standing still" about a position that has moved makes
/// it distrust the fix and freeze the route where it started.
final class HostLocationManager: NavigationLocationManager {
    private let relay = LocationRelay()

    override var delegate: CLLocationManagerDelegate? {
        get { relay.target }
        set {
            relay.target = newValue
            super.delegate = newValue == nil ? nil : relay
        }
    }
}

final class LocationRelay: NSObject, CLLocationManagerDelegate {
    weak var target: CLLocationManagerDelegate?
    private var steadier = LocationSteadier()

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        target?.locationManager?(manager, didUpdateLocations: locations.map { fix in
            let out = steadier.steadied(fix)
            navTrace("fix speed=\(String(format: "%.1f", fix.speed)) course=\(Int(fix.course)) acc=\(Int(fix.horizontalAccuracy)) age=\(String(format: "%.1f", Date().timeIntervalSince(fix.timestamp))) lat=\(String(format: "%.5f", fix.coordinate.latitude)) lng=\(String(format: "%.5f", fix.coordinate.longitude)) -> \(out === fix ? "passed" : "held \(Int(out.distance(from: fix))) m back")")
            return out
        })
    }

    // Everything else the SDK listens for goes straight through.
    override func responds(to aSelector: Selector!) -> Bool {
        return super.responds(to: aSelector) || (target?.responds(to: aSelector) ?? false)
    }

    override func forwardingTarget(for aSelector: Selector!) -> Any? {
        return target
    }
}

struct LocationSteadier {
    /// At or above this the phone is telling us the driver is moving.
    /// GPS at rest reads well under it; a van pulling away is over it
    /// within a second.
    static let movingSpeed: CLLocationSpeed = 0.7

    /// A fix this old when it arrives is CoreLocation's cached one, not
    /// where the driver is now.
    static let staleAfter: TimeInterval = 10

    /// At rest, the spot is moved to the newest fix this often, so one
    /// poor fix cannot pin the driver in the wrong place for long.
    static let refreshRestEvery: TimeInterval = 30

    /// How many of the latest fixes are looked at to tell a driver from a
    /// phone that is only wandering.
    static let windowSize = 6

    /// Where the driver is taken to be standing. Nil while they are, or
    /// may be, moving.
    private var held: CLLocation?
    private var heldSince = Date.distantPast

    /// The latest fresh fixes, whatever was done with them.
    private var window: [CLLocation] = []

    mutating func steadied(_ fix: CLLocation, now: Date = Date()) -> CLLocation {
        // Not a usable fix, or a cached one: the SDK has its own handling
        // for those, and neither says where the driver is now.
        guard fix.horizontalAccuracy >= 0,
              now.timeIntervalSince(fix.timestamp) <= Self.staleAfter else { return fix }

        window.append(fix)
        if window.count > Self.windowSize { window.removeFirst() }

        if fix.speed >= Self.movingSpeed {
            held = nil
            return fix
        }

        if let spot = held {
            if now.timeIntervalSince(heldSince) >= Self.refreshRestEvery {
                rest(at: fix, now: now)
                return fix
            }
            // Further than wander explains, or fixes that line up one
            // after another: they have moved.
            if fix.distance(from: spot) > Self.slack(for: fix) || goingSomewhere {
                held = nil
                return fix
            }
            return CLLocation(coordinate: spot.coordinate,
                              altitude: fix.altitude,
                              horizontalAccuracy: fix.horizontalAccuracy,
                              verticalAccuracy: fix.verticalAccuracy,
                              course: spot.course,
                              speed: 0,
                              timestamp: fix.timestamp)
        }

        // Not at rest. Every fix passes; the only question is whether
        // this one shows the driver has stopped.
        if fix.speed >= 0 {
            // The phone measures speed and says "not moving". It is
            // believed unless the fixes themselves are plainly travelling
            // (a speed reading stuck at zero).
            if !goingSomewhere { rest(at: fix, now: now) }
        } else if window.count >= 4, let first = window.first, !goingSomewhere,
                  window.allSatisfy({ $0.distance(from: first) <= Self.slack(for: fix) }) {
            // No speed reading at all: only where the fixes land. Several
            // in a row that stay together and do not line up is a phone
            // that is not going anywhere.
            rest(at: fix, now: now)
        }
        return fix
    }

    /// A driver's fixes line up one after another; a parked phone's
    /// wander doubles back on itself. True when the latest fixes cover
    /// real ground and most of it is in one direction.
    private var goingSomewhere: Bool {
        guard window.count >= 4, let first = window.first, let last = window.last else { return false }
        var path: CLLocationDistance = 0
        for (a, b) in zip(window, window.dropFirst()) { path += b.distance(from: a) }
        guard path >= 15 else { return false }
        return last.distance(from: first) / path >= 0.8
    }

    private mutating func rest(at fix: CLLocation, now: Date) {
        held = fix
        heldSince = now
    }

    /// How far a fix may land from the spot and still count as the same
    /// spot: twice its own error, within bounds. With a speed reading the
    /// bounds are tight, so a driver crawling under walking pace is never
    /// left far behind; without one there is only position to go on.
    static func slack(for fix: CLLocation) -> CLLocationDistance {
        if fix.speed >= 0 {
            return min(max(fix.horizontalAccuracy * 2, 15), 30)
        }
        return min(max(fix.horizontalAccuracy * 2, 10), 40)
    }
}
