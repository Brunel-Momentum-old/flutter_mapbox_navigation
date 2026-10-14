import CoreLocation
import Flutter
import MapboxMaps
import MapboxNavigation
import UIKit

/// The driver's marker on the map: the SDK's own pin, or a 3D model of
/// the host's van (`setMarker`). Android has the same in `TruckPuck.kt`.
///
/// The model is a glTF file in the host's Flutter assets, metres, +Y up,
/// its front towards +Z, origin on the ground under its middle. The host
/// sends how big to draw it at each zoom (`scale`, a style expression):
/// the size of the pin when zoomed out, a believable van close in.
struct DriverMarker: Equatable {
    var truck = false
    var modelURL: URL?
    var scale: MapboxMaps.Expression?
    /// The scale as the host sent it, for comparing two markers.
    var scaleJSON: String?
    /// Euler angles, degrees, applied to the model before the heading.
    var rotation: [Double] = [0, 0, 0]

    var showsTruck: Bool { truck && modelURL != nil }

    static func == (a: DriverMarker, b: DriverMarker) -> Bool {
        a.truck == b.truck && a.modelURL == b.modelURL && a.scaleJSON == b.scaleJSON && a.rotation == b.rotation
    }

    init() {}

    /// From `setMarker`'s arguments. A model that cannot be found leaves
    /// the pin.
    init(arguments: NSDictionary?) {
        truck = (arguments?["marker"] as? String) == "truck"
        if let asset = arguments?["modelAsset"] as? String {
            let key = FlutterDartProject.lookupKey(forAsset: asset)
            if let path = Bundle.main.path(forResource: key, ofType: nil),
               Self.isGlb(atPath: path) {
                modelURL = URL(fileURLWithPath: path)
            } else {
                NSLog("flutter_mapbox_navigation: marker model missing or not a glTF binary (\(asset)); using the pin")
            }
        }
        if let json = arguments?["scale"] as? String, let data = json.data(using: .utf8) {
            scale = try? JSONDecoder().decode(MapboxMaps.Expression.self, from: data)
            scaleJSON = scale == nil ? nil : json
        }
        if let r = arguments?["rotation"] as? [Double], r.count == 3 { rotation = r }
    }

    /// A readable glTF binary, version 2, no longer than its file: the
    /// SDK given anything else draws no marker at all.
    static func isGlb(atPath path: String) -> Bool {
        guard let handle = FileHandle(forReadingAtPath: path) else { return false }
        defer { try? handle.close() }
        let header = handle.readData(ofLength: 12)
        guard header.count == 12 else { return false }
        let word = { (i: Int) -> UInt32 in
            header.subdata(in: i..<(i + 4)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.uint32Value ?? 0
        return word(0) == 0x4654_6C67 && word(4) == 2 && word(8) <= size && word(8) > 12
    }

    /// The SDK's 3D puck for this marker.
    var style: UserLocationStyle? {
        guard showsTruck, let url = modelURL else { return nil }
        let model = Model(uri: url, orientation: [0, 0, 0])
        return .puck3D(configuration: Puck3DConfiguration(
            model: model,
            modelScale: scale.map { Value<[Double]>.expression($0) },
            modelRotation: .constant(rotation)))
    }
}

/// Where the truck is: the location guidance settles on each tick,
/// snapped to the road, and nothing else.
///
/// The SDK's 3D puck reads the map's location provider. Left as it was,
/// that is the phone's raw position (off the road, and wandering at rest)
/// and the map's own location manager running beside guidance's. Fed
/// from the tick instead, the truck sits where the pin would, and the
/// map's location work stays as quiet as it is with the pin: one update a
/// second while moving, none at rest.
final class TruckLocationFeed: LocationProvider {
    var locationProviderOptions = LocationOptions()
    var headingOrientation: CLDeviceOrientation = .portrait
    let authorizationStatus: CLAuthorizationStatus = .authorizedWhenInUse
    let accuracyAuthorization: CLAccuracyAuthorization = .fullAccuracy
    var heading: CLHeading? { nil }

    private weak var delegate: LocationProviderDelegate?
    private(set) var last: CLLocation?

    func setDelegate(_ delegate: LocationProviderDelegate) {
        self.delegate = delegate
        if let last = last { delegate.locationProvider(self, didUpdateLocations: [last]) }
    }

    /// Hands on a tick's location, unless it is where the truck already
    /// is, facing the same way: a puck given the same place again still
    /// animates to it, and the map draws a frame for each step.
    func push(_ location: CLLocation) {
        if let last = last,
           location.distance(from: last) < MapPower.still,
           abs(angleBetween(location.course, last.course)) < 1 {
            return
        }
        last = location
        delegate?.locationProvider(self, didUpdateLocations: [location])
    }

    /// Hands on the last location again, turned a hair. After a change
    /// of style the puck's model source is gone until the puck next
    /// moves, and a van parked at the time would have no truck.
    func refresh() {
        guard let last = last else { return }
        let course = last.course >= 0 ? (last.course + 0.01).truncatingRemainder(dividingBy: 360) : 0
        let again = CLLocation(coordinate: last.coordinate, altitude: last.altitude,
                               horizontalAccuracy: last.horizontalAccuracy, verticalAccuracy: last.verticalAccuracy,
                               course: course, speed: last.speed, timestamp: Date())
        self.last = again
        delegate?.locationProvider(self, didUpdateLocations: [again])
    }

    private func angleBetween(_ a: CLLocationDirection, _ b: CLLocationDirection) -> Double {
        guard a >= 0, b >= 0 else { return a == b ? 0 : 180 }
        let d = (a - b).truncatingRemainder(dividingBy: 360)
        return d > 180 ? d - 360 : (d < -180 ? d + 360 : d)
    }

    func requestAlwaysAuthorization() {}
    func requestWhenInUseAuthorization() {}
    func requestTemporaryFullAccuracyAuthorization(withPurposeKey purposeKey: String) {}
    func startUpdatingLocation() {}
    func stopUpdatingLocation() {}
    func startUpdatingHeading() {}
    func stopUpdatingHeading() {}
    func dismissHeadingCalibrationDisplay() {}
}

extension NavigationFactory {

    /// The id Mapbox gives the 3D puck's layer.
    static let truckLayerId = "puck-model-layer"

    /// The host chose a marker. Applied to the trip's map at once, and to
    /// every map set up after.
    func setMarker(_ marker: DriverMarker) {
        guard marker != _marker else { return }
        _marker = marker
        wakeMap()
        if let map = _navigationViewController?.navigationMapView {
            applyMarker(to: map)
        }
    }

    /// Puts the chosen marker on the navigation map.
    func applyMarker(to map: NavigationMapView) {
        if let truck = _marker.style {
            let feed = _truckFeed ?? TruckLocationFeed()
            _truckFeed = feed
            if let location = _lastKnownLocation { feed.push(location) }
            if !(map.mapView.location.locationProvider is TruckLocationFeed) {
                map.mapView.location.overrideLocationProvider(with: feed)
            }
            map.userLocationStyle = truck
            _truckLayerCount = 0
            _truckStyleWatch?.cancel()
            _truckStyleWatch = map.mapView.mapboxMap.onEvery(event: .styleLoaded) { [weak self] _ in
                self?._truckFeed?.refresh()
            }
            _truckErrorWatch?.cancel()
            _truckErrorWatch = map.mapView.mapboxMap.onEvery(event: .mapLoadingError) { [weak self, weak map] event in
                // The model would not load: the pin, never no marker.
                let message = "\(event.payload.error)"
                guard let self = self, let map = map, self._marker.truck,
                      event.payload.sourceId == "puck-model-source"
                        || message.lowercased().contains("gltf")
                        || message.lowercased().contains("model") else { return }
                NSLog("flutter_mapbox_navigation: truck marker failed (\(message)); using the pin")
                self._marker.truck = false
                self.applyMarker(to: map)
            }
        } else {
            _truckStyleWatch?.cancel()
            _truckStyleWatch = nil
            _truckErrorWatch?.cancel()
            _truckErrorWatch = nil
            if case .courseView? = map.userLocationStyle { return }
            let view = PacedPuckView(frame: CGRect(origin: .zero, size: CGSize(width: 75, height: 75)))
            HostMapColor.style(view)
            map.userLocationStyle = .courseView(view)
            if let location = _lastKnownLocation { map.moveUserLocation(to: location) }
        }
    }

    /// Each tick: the truck to where guidance puts the driver, and its
    /// layer back on top of anything drawn since (pins, tags, the door
    /// view), which would otherwise cover it.
    func moveTruck(on map: NavigationMapView, to location: CLLocation) {
        guard _marker.showsTruck, let feed = _truckFeed else { return }
        if !(map.mapView.location.locationProvider is TruckLocationFeed) {
            map.mapView.location.overrideLocationProvider(with: feed)
        }
        feed.push(location)
        // Only when layers came or went: the SDK's annotation layers
        // report themselves after the puck whatever their order, so
        // checking the order alone moved the layer on every tick.
        let style = map.mapView.mapboxMap.style
        let layers = style.allLayerIdentifiers
        guard layers.count != _truckLayerCount else { return }
        _truckLayerCount = layers.count
        if layers.last?.id != Self.truckLayerId, style.layerExists(withId: Self.truckLayerId) {
            do {
                try style.moveLayer(withId: Self.truckLayerId, to: .default)
            } catch {
                NSLog("flutter_mapbox_navigation: truck layer could not be moved: \(error)")
            }
        }
    }
}
