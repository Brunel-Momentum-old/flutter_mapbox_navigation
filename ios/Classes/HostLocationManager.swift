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

    /// A phone that reports no speed gives nothing to go on but where its
    /// fixes land. This many in a row landing together is a driver who
    /// has stopped.
    static let quietFixesToRest = 4

    /// A fix this old when it arrives is CoreLocation's cached one, not
    /// where the driver is now.
    static let staleAfter: TimeInterval = 10

    /// At rest, the spot is moved to a newer fix of equal or better
    /// accuracy this often, so an early poor fix cannot pin the driver in
    /// the wrong place for long.
    static let refreshRestEvery: TimeInterval = 30

    /// Where the driver is taken to be standing. Nil while they are, or
    /// may be, moving.
    private var held: CLLocation?
    private var heldSince = Date.distantPast

    /// The latest fixes while not at rest, for telling when a driver
    /// with no speed reading has stopped.
    private var recent: [CLLocation] = []

    mutating func steadied(_ fix: CLLocation, now: Date = Date()) -> CLLocation {
        // Not a usable fix, or a cached one: the SDK has its own handling
        // for those, and neither says where the driver is at rest.
        guard fix.horizontalAccuracy >= 0,
              now.timeIntervalSince(fix.timestamp) <= Self.staleAfter else { return fix }

        if fix.speed >= Self.movingSpeed {
            moving()
            return fix
        }

        if let spot = held {
            // A clearly better fix, or the regular refresh, moves the spot.
            let better = fix.horizontalAccuracy <= spot.horizontalAccuracy * 0.67
            let due = now.timeIntervalSince(heldSince) >= Self.refreshRestEvery
                && fix.horizontalAccuracy <= spot.horizontalAccuracy
            if better || due {
                rest(at: fix, now: now)
                return fix
            }
            if fix.distance(from: spot) <= Self.slack(for: fix) {
                return CLLocation(coordinate: spot.coordinate,
                                  altitude: fix.altitude,
                                  horizontalAccuracy: fix.horizontalAccuracy,
                                  verticalAccuracy: fix.verticalAccuracy,
                                  course: spot.course,
                                  speed: 0,
                                  timestamp: fix.timestamp)
            }
            // Further than wander explains: they have moved.
            moving()
            recent = [fix]
            return fix
        }

        // Not at rest. Every fix passes; the only question is whether
        // this one shows the driver has stopped.
        if fix.speed >= 0 {
            // The phone measures speed and says "not moving".
            rest(at: fix, now: now)
            return fix
        }
        recent.append(fix)
        if recent.count > Self.quietFixesToRest { recent.removeFirst() }
        if recent.count == Self.quietFixesToRest,
           let first = recent.first,
           recent.allSatisfy({ $0.distance(from: first) <= Self.slack(for: fix) }) {
            rest(at: fix, now: now)
        }
        return fix
    }

    private mutating func moving() {
        held = nil
        recent.removeAll()
    }

    private mutating func rest(at fix: CLLocation, now: Date) {
        held = fix
        heldSince = now
        recent.removeAll()
    }

    /// How far a fix may land from the spot and still count as the same
    /// spot. A phone that says "not moving" is believed over a wide
    /// margin: its speed is measured, its position is the part that
    /// wanders. With no speed at all there is less to go on, so less is
    /// forgiven.
    static func slack(for fix: CLLocation) -> CLLocationDistance {
        if fix.speed >= 0 {
            return min(max(fix.horizontalAccuracy * 3, 30), 75)
        }
        return min(max(fix.horizontalAccuracy * 2, 10), 40)
    }
}
