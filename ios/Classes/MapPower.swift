import CoreLocation
import MapboxMaps
import MapboxNavigation

/// How often the maps draw.
///
/// During guidance a Mapbox map never comes to rest by itself. Every tick
/// starts new camera animations and every finished frame rewrites the
/// location layer, whether or not the driver has moved, so the map draws
/// at whatever rate its display link runs: 60 a second on a charger, up
/// to 120 where nothing sets a rate. Measured on a phone lying still,
/// that drawing was nearly all of the app's processor use, and half of
/// it was for a map nobody could see.
enum MapPower {
    /// While the driver is moving, or something on the map is changing.
    /// This is the SDK's own rate on battery. On a charger the SDK
    /// doubles it, and a phone on a van's charger pays for that in heat
    /// all day.
    static let moving = 30

    /// Stopped, with nothing on the map changing. The picture is the
    /// same at any rate, so this only sets how soon a change shows.
    static let resting = 10

    /// A map with another one over it.
    static let covered = 1

    /// Ticks without movement before the map rests. A van at a stop sign
    /// is moving again before this.
    static let stillTicksBeforeRest = 3

    /// Less than this between two ticks is not movement.
    static let still: CLLocationDistance = 0.5

    /// How long the map keeps the moving rate after something changed on
    /// it with the driver stopped: the sheet moved, the door view came
    /// up, the driver touched it.
    static let awakeFor: TimeInterval = 3
}

extension NavigationFactory {

    /// Sets the navigation map's rate for the tick just handled. Called
    /// last in the tick, after the SDK has set its own.
    func paceMap(_ map: NavigationMapView, at location: CLLocation, phone: CLLocation) {
        dropLocationLayer(of: map)
        // The phone's own speed as well: the navigator can take a tick
        // or two to move off after the phone has.
        let moved = _lastTickLocation.map { location.distance(from: $0) > MapPower.still } ?? true
        let moving = max(location.speed, phone.speed) >= LocationSteadier.movingSpeed
        _lastTickLocation = location
        _stillTicks = moved || moving ? 0 : _stillTicks + 1
        // Part-way between following and overview the camera is moving,
        // whatever the driver is doing.
        let state = map.navigationCamera.state
        let cameraMoving = state == .transitionToFollowing || state == .transitionToOverview
        let resting = _stillTicks >= MapPower.stillTicksBeforeRest && !cameraMoving && Date() >= _mapAwakeUntil
        setRate(resting ? MapPower.resting : MapPower.moving, on: map.mapView)
    }

    /// Something is about to change on the map, perhaps with the driver
    /// stopped: the moving rate from now until it has had time to finish.
    func wakeMap() {
        _mapAwakeUntil = Date().addingTimeInterval(MapPower.awakeFor)
        if let map = _navigationViewController?.navigationMapView?.mapView {
            setRate(MapPower.moving, on: map)
        }
    }

    /// The SDK keeps an invisible location layer on the navigation map
    /// and rewrites it after every frame it draws. That rewrite is what
    /// makes the next frame, so the map never rests, and it costs a
    /// tenth of a processor core to re-encode a layer nobody sees. The
    /// driver's marker is a view of the SDK's own, moved by guidance; it
    /// does not use the layer. Checked every tick because the SDK puts
    /// the layer back whenever it sets the marker up again.
    func dropLocationLayer(of map: NavigationMapView) {
        guard case .courseView? = map.userLocationStyle,
              map.mapView.location.options.puckType != nil else { return }
        map.mapView.location.options.puckType = nil
    }

    func setRate(_ rate: Int, on map: MapView) {
        if map.preferredFramesPerSecond != rate { map.preferredFramesPerSecond = rate }
    }
}

extension FlutterMapboxNavigationView {

    /// The base map lies under the navigation map for the whole trip,
    /// fully covered. Left alone it went on drawing every frame, with its
    /// own location marker and its own location and compass updates.
    func restBaseMap() {
        guard let map = navigationMapView?.mapView, _baseMapLocationProvider == nil,
              let provider = map.location.locationProvider else { return }
        _baseMapLocationProvider = provider
        setRate(MapPower.covered, on: map)
        map.location.overrideLocationProvider(with: NoLocationProvider(inPlaceOf: provider))
        map.location.options.puckType = nil
    }

    /// The navigation map has been taken down and the base map shows
    /// again.
    func wakeBaseMap() {
        guard let map = navigationMapView?.mapView, let provider = _baseMapLocationProvider else { return }
        _baseMapLocationProvider = nil
        setRate(MapPower.moving, on: map)
        map.location.overrideLocationProvider(with: provider)
        // Set to itself: that is what makes the SDK put its marker back.
        let style = navigationMapView.userLocationStyle
        navigationMapView.userLocationStyle = style
    }
}

/// Stands in for a map's location provider while nobody can see that
/// map. It reports nothing and keeps no location manager running.
final class NoLocationProvider: LocationProvider {
    var locationProviderOptions = LocationOptions()
    var headingOrientation: CLDeviceOrientation = .portrait

    // What the map was last told. A change here would have the SDK
    // rebuild the marker this is put in to remove.
    let authorizationStatus: CLAuthorizationStatus
    let accuracyAuthorization: CLAccuracyAuthorization
    var heading: CLHeading? { nil }

    init(inPlaceOf provider: LocationProvider) {
        authorizationStatus = provider.authorizationStatus
        accuracyAuthorization = provider.accuracyAuthorization
    }

    func setDelegate(_ delegate: LocationProviderDelegate) {}
    func requestAlwaysAuthorization() {}
    func requestWhenInUseAuthorization() {}
    func requestTemporaryFullAccuracyAuthorization(withPurposeKey purposeKey: String) {}
    func startUpdatingLocation() {}
    func stopUpdatingLocation() {}
    func startUpdatingHeading() {}
    func stopUpdatingHeading() {}
    func dismissHeadingCalibrationDisplay() {}
}

/// Trace only (`HOST_NAV_TRACE`): how many frames each map drew in the
/// last second, and the rate it is set to.
final class FrameTrace {
    private var counts: [String: Int] = [:]
    private var subscriptions: [String: Cancelable] = [:]
    private var maps: [String: () -> MapView?] = [:]
    private var timer: Timer?

    func watch(_ map: MapView, as name: String) {
        stopWatching(name)
        counts[name] = 0
        maps[name] = { [weak map] in map }
        subscriptions[name] = map.mapboxMap.onEvery(event: .renderFrameFinished) { [weak self] _ in
            self?.counts[name, default: 0] += 1
        }
        if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.report() }
        }
    }

    func stopWatching(_ name: String) {
        subscriptions.removeValue(forKey: name)?.cancel()
        counts[name] = nil
        maps[name] = nil
    }

    private func report() {
        let line = counts.keys.sorted().map { name -> String in
            let rate = maps[name]?()?.preferredFramesPerSecond ?? -1
            return "\(name)=\(counts[name] ?? 0) (rate \(rate))"
        }.joined(separator: " ")
        for name in counts.keys { counts[name] = 0 }
        if !line.isEmpty { navTrace("frames \(line)") }
    }

    deinit { timer?.invalidate() }
}
