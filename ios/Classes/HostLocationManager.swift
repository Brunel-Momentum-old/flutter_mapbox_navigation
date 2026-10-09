import CoreLocation
import MapboxCoreNavigation

/// The phone's location, steadied while the driver is not moving.
///
/// A phone at rest still reports fixes that wander: a few metres on GPS,
/// tens of metres on Wi-Fi indoors, where CoreLocation also stops
/// reporting a speed. The navigator reads every wander as driving. It
/// works out a speed from the jump, snaps the driver onto whichever
/// street is nearest, sometimes re-routes, and the map swings round to
/// follow. Standing in a loading bay, the driver watches the marker
/// twitch between two streets.
///
/// So while the receiver does not report real movement, a fix that lands
/// within its own error of where the driver already is gets handed on as
/// "still there, standing still". The moment the receiver reports speed,
/// fixes pass untouched; a fix that lands further off moves the driver
/// there.
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
    /// Below this the receiver is not telling us the driver is moving.
    /// GPS at rest reads well under it; a van pulling away is over it
    /// within a second.
    static let movingSpeed: CLLocationSpeed = 0.7

    /// How far a fix may land from the held spot and still count as the
    /// same spot: twice the fix's own accuracy (two fixes each off by
    /// their error can sit that far apart), kept between these bounds so
    /// a very confident fix still forgives ordinary wander and a very
    /// poor one cannot pin a moving driver for long.
    static let minSlack: CLLocationDistance = 10
    static let maxSlack: CLLocationDistance = 40

    weak var target: CLLocationManagerDelegate?

    /// Where the driver is taken to be standing.
    private var held: CLLocation?

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        target?.locationManager?(manager, didUpdateLocations: locations.map(steadied))
    }

    func steadied(_ fix: CLLocation) -> CLLocation {
        // Not a usable fix: the SDK has its own handling for those.
        guard fix.horizontalAccuracy >= 0 else { return fix }
        if fix.speed >= Self.movingSpeed {
            held = fix
            return fix
        }
        let slack = min(max(fix.horizontalAccuracy * 2, Self.minSlack), Self.maxSlack)
        if let held = held, fix.distance(from: held) <= slack {
            return standing(at: held, reportedBy: fix)
        }
        // Somewhere new. Without a speed from the receiver it is a place
        // the driver is standing, not one they are passing through: given
        // "speed unknown" the navigator carries them on along the route
        // until the next fix arrives.
        held = fix
        return fix.speed < 0 ? standing(at: fix, reportedBy: fix) : fix
    }

    private func standing(at spot: CLLocation, reportedBy fix: CLLocation) -> CLLocation {
        return CLLocation(coordinate: spot.coordinate,
                          altitude: fix.altitude,
                          horizontalAccuracy: fix.horizontalAccuracy,
                          verticalAccuracy: fix.verticalAccuracy,
                          course: spot.course,
                          speed: 0,
                          timestamp: fix.timestamp)
    }

    // Everything else the SDK listens for goes straight through.
    override func responds(to aSelector: Selector!) -> Bool {
        return super.responds(to: aSelector) || (target?.responds(to: aSelector) ?? false)
    }

    override func forwardingTarget(for aSelector: Selector!) -> Any? {
        return target
    }
}
