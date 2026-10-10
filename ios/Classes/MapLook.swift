import Flutter
import UIKit
import CoreLocation
import MapboxMaps
import MapboxDirections
import MapboxCoreNavigation
import MapboxNavigation
import Turf

/// Colours of the host app's map: route line, puck and the destination
/// building. Kept here so the day and night styles agree.
enum HostMapColor {
    static let route = UIColor(red: 0x24 / 255.0, green: 0x63 / 255.0, blue: 0xEB / 255.0, alpha: 1)
    static let trafficModerate = UIColor(red: 0xF0 / 255.0, green: 0xA9 / 255.0, blue: 0x1B / 255.0, alpha: 1)
    static let trafficHeavy = UIColor(red: 0xC8 / 255.0, green: 0x40 / 255.0, blue: 0x2F / 255.0, alpha: 1)
    static let trafficSevere = UIColor(red: 0x9E / 255.0, green: 0x33 / 255.0, blue: 0x25 / 255.0, alpha: 1)
    static let travelled = UIColor(red: 0xB9 / 255.0, green: 0xB6 / 255.0, blue: 0xAE / 255.0, alpha: 1)
    static let green = UIColor(red: 0x2B / 255.0, green: 0x73 / 255.0, blue: 0x48 / 255.0, alpha: 1)
    static let buildingFill = UIColor(red: 0xBF / 255.0, green: 0xDC / 255.0, blue: 0xCB / 255.0, alpha: 1)
    static let ink = UIColor(red: 0x2B / 255.0, green: 0x2B / 255.0, blue: 0x2B / 255.0, alpha: 1)
    static let leftRoute = UIColor(red: 0x8E / 255.0, green: 0x8B / 255.0, blue: 0x83 / 255.0, alpha: 1)
    static let tip = UIColor(red: 0x1E / 255.0, green: 0x1E / 255.0, blue: 0x1C / 255.0, alpha: 1)

    /// As close as the following camera comes at a turn. The camera is
    /// tilted, so it sees a long way ahead for its zoom: at the SDK's own
    /// limit (16.35) a junction 30 m off was a few points across.
    static let closestZoom = 18.75
    static let tipGreen = UIColor(red: 0x1F / 255.0, green: 0x5A / 255.0, blue: 0x37 / 255.0, alpha: 1)

    /// Route line, casing, traffic and puck colours, applied through the
    /// appearance proxy by the day and night styles. Set per idiom, the
    /// way the SDK's own styles do it: a trait-specific proxy outranks
    /// the global one, so a global value would lose to the SDK default.
    static func applyRouteLine() {
        let traitCollections = [UITraitCollection(userInterfaceIdiom: .phone),
                                UITraitCollection(userInterfaceIdiom: .pad)]
        for traits in traitCollections {
            style(NavigationMapView.appearance(for: traits))
            style(UserPuckCourseView.appearance(for: traits))
        }
    }

    static func style(_ map: NavigationMapView) {
        map.trafficUnknownColor = route
        map.trafficLowColor = route
        map.trafficModerateColor = trafficModerate
        map.trafficHeavyColor = trafficHeavy
        map.trafficSevereColor = trafficSevere
        map.routeCasingColor = .white
        map.traversedRouteColor = travelled
        map.maneuverArrowColor = .white
        map.maneuverArrowStrokeColor = route
        map.buildingHighlightColor = buildingFill
    }

    static func style(_ puck: UserPuckCourseView) {
        puck.fillColor = green
        puck.puckColor = .white
        puck.shadowColor = green.withAlphaComponent(0.22)
    }
}

/// The last stretch to the stop: a close, low camera pushed toward the
/// stop's side of the street, with the destination building marked.
struct DoorView {
    let side: String?
    let coordinate: CLLocationCoordinate2D?
    let label: String?
    var footprintFound = false
    var highlightAttempts = 0
}

extension NavigationFactory {

    /// Map look that needs the live view, not just the appearance proxy.
    func applyMapLook(_ navigationViewController: NavigationViewController) {
        // The travelled part of the line greys out behind the driver.
        navigationViewController.routeLineTracksTraversal = true
        // The host decides day or night (`setNightMode`) so its own
        // chrome and the map never disagree.
        navigationViewController.automaticallyAdjustsStyleForTimeOfDay = false
        navigationViewController.usesNightStyleWhileInTunnel = false
        if _nightMode {
            navigationViewController.styleManager?.applyStyle(type: .night)
        }
        if let map = navigationViewController.navigationMapView {
            // Heading up, always, with the driver's marker in one place.
            // By default the camera looks towards the next turn, up to
            // 45 degrees off the way the driver is going, so on a curving
            // road the marker's arrow swung round while the map stayed
            // put; and near a turn it flattened and slid over to frame
            // the turn, taking the marker off its spot.
            if let viewport = map.navigationCamera.viewportDataSource as? NavigationViewportDataSource {
                viewport.options.followingCameraOptions.bearingSmoothing.enabled = false
                viewport.options.followingCameraOptions.pitchNearManeuver.enabled = false
                // The camera closes in as a turn comes up, but the SDK
                // stops it at a zoom where the screen still shows 600 m:
                // 30 m from a junction the lanes and the turn itself were
                // a few points across. Let it come in to where the
                // junction fills the screen.
                let zoom = viewport.options.followingCameraOptions.zoomRange
                viewport.options.followingCameraOptions.zoomRange = zoom.lowerBound...HostMapColor.closestZoom
            }
            // The host offers a faster route in its own card; the SDK's
            // "2 min slower" bubbles on every alternative are noise.
            map.showsRelativeDurationOnContinuousAlternativeRoutes = false
            // And nothing switches route without that card: left alone,
            // a tap anywhere near an alternative's line took it.
            map.tapGestureDistanceThreshold = 0
            HostMapColor.style(map)
            // The SDK's own marker view, in a kind that animates at the
            // map's rate (see PacedPuckView).
            if case let .courseView(view)? = map.userLocationStyle, !(view is PacedPuckView) {
                map.userLocationStyle = .courseView(PacedPuckView(frame: CGRect(origin: .zero, size: view.bounds.size)))
            }
            if case let .courseView(view)? = map.userLocationStyle,
               let puck = view as? UserPuckCourseView {
                HostMapColor.style(puck)
            }
        }
    }

    func setNightMode(_ night: Bool) {
        guard night != _nightMode else { return }
        _nightMode = night
        wakeMap()
        _navigationViewController?.styleManager?.applyStyle(type: night ? .night : .day)
        // The new style drops the layers drawn here. Forgetting what is
        // on the map makes the next tick draw it again.
        _fasterRouteOnMap = nil
        _shownTags = ""
        _shownDestinationPin = nil
        _trafficLightsDrawnAt = nil
    }

    // MARK: Tags on the map: the next turn, and a faster route

    /// The street the next turn goes onto, tagged at the turn.
    func tagNextTurn(_ progress: RouteProgress) {
        let step = progress.currentLegProgress.upcomingStep
        let street = progress.currentLegProgress.currentStepProgress.currentVisualInstruction?.primaryInstruction.text
            ?? step?.names?.first
        // Arriving is not a turn: the door view marks the stop itself.
        if let step = step, step.maneuverType != .arrive, let street = street, !street.isEmpty {
            _turnTag = (step.maneuverLocation, street)
        } else {
            _turnTag = nil
        }
        refreshTags()
    }

    // MARK: Traffic lights

    /// Puts our traffic light in place of the SDK's. Its housing is
    /// pale, and a light on the route line all but vanished into it
    /// (founder, on a drive: "the lights are blending into the
    /// polyline"). Ours is the same three lights in a black housing with
    /// a white edge.
    ///
    /// The SDK puts its own picture back whenever it restyles the map,
    /// without saying so, so ours is set again every quarter of a minute
    /// as well as after a change of day and night.
    func drawTrafficLights() {
        if let at = _trafficLightsDrawnAt, Date().timeIntervalSince(at) < 15 { return }
        guard let style = _navigationViewController?.navigationMapView?.mapView.mapboxMap.style else { return }
        // The SDK's name for it; the layer it draws the lights with
        // looks the picture up by this.
        let image = Self.trafficLightImage()
        if (try? style.addImage(image, id: "traffic_signal")) == nil {
            // A picture of another size will not be swapped in place.
            try? style.removeImage(withId: "traffic_signal")
            guard (try? style.addImage(image, id: "traffic_signal")) != nil else { return }
        }
        _trafficLightsDrawnAt = Date()
    }

    static func trafficLightImage() -> UIImage {
        let size = CGSize(width: 22, height: 52)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let housing = UIBezierPath(roundedRect: CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1),
                                       cornerRadius: 7)
            cg.setFillColor(HostMapColor.tip.cgColor)
            housing.fill()
            cg.setStrokeColor(UIColor.white.cgColor)
            housing.lineWidth = 1.5
            housing.stroke()
            let lights: [UIColor] = [
                UIColor(red: 0xE5 / 255.0, green: 0x48 / 255.0, blue: 0x4D / 255.0, alpha: 1),
                UIColor(red: 0xF5 / 255.0, green: 0xB3 / 255.0, blue: 0x01 / 255.0, alpha: 1),
                UIColor(red: 0x2F / 255.0, green: 0xA3 / 255.0, blue: 0x6B / 255.0, alpha: 1),
            ]
            for (index, light) in lights.enumerated() {
                cg.setFillColor(light.cgColor)
                cg.fillEllipse(in: CGRect(x: 5, y: 6 + CGFloat(index) * 14, width: 12, height: 12))
            }
        }
    }

    // MARK: Heavy traffic ahead

    /// Tags the first stretch of heavy traffic ahead with what it costs:
    /// "+3 min · Heavy traffic". The red on the line says where; this
    /// says how much, which is what makes a driver decide.
    ///
    /// The delay is the leg's expected time over its typical time, so it
    /// is only shown where the directions give both, and only from two
    /// minutes up: less than that is not worth a driver's glance. Worked
    /// out again when the route or the leg changes and every half
    /// minute, not on every tick: the line is walked from the driver to
    /// the stop each time.
    func tagTraffic(_ progress: RouteProgress) {
        let key = "\(ObjectIdentifier(progress.route).hashValue),\(progress.legIndex)"
        if key == _trafficCheckedFor, let at = _trafficCheckedAt, Date().timeIntervalSince(at) < 30 { return }
        _trafficCheckedFor = key
        _trafficCheckedAt = Date()
        _trafficTag = Self.heavyTraffic(ahead: progress)
        refreshTags()
    }

    static func heavyTraffic(ahead progress: RouteProgress) -> (CLLocationCoordinate2D, String)? {
        let leg = progress.currentLeg
        let line = leg.shape.coordinates
        // One level for each piece of the line, or the two cannot be
        // matched up and nothing is said.
        guard let typical = leg.typicalTravelTime,
              let levels = leg.segmentCongestionLevels,
              line.count == levels.count + 1 else { return nil }
        let minutes = Int(((leg.expectedTravelTime - typical) / 60).rounded())
        guard minutes >= 2 else { return nil }
        var start: Int?
        var length: CLLocationDistance = 0
        let from = min(max(0, progress.currentLegProgress.shapeIndex), levels.count)
        // One past the end, so a run that reaches the stop is closed too.
        for index in from...levels.count {
            let heavy = index < levels.count && (levels[index] == .heavy || levels[index] == .severe)
            if heavy {
                if start == nil { start = index; length = 0 }
                length += line[index].distance(to: line[index + 1])
                continue
            }
            if let first = start {
                if length >= 100 {
                    let run = LineString(Array(line[first...index]))
                    guard let middle = run.coordinateFromStart(distance: length / 2) else { return nil }
                    return (middle, "+\(minutes) min · Heavy traffic")
                }
                start = nil
            }
        }
        return nil
    }

    // MARK: Closing in on a turn

    /// Holds the following camera close as a turn comes up.
    ///
    /// The SDK works the zoom out from how much road is left before the
    /// turn, and it does come in, but not reliably: a short street, or a
    /// run of junctions, and it was still showing several blocks with the
    /// turn 30 m away. So the least zoom it may use is raised as the
    /// turn nears, from the SDK's own closest at 200 m to nearly the
    /// closest allowed (`HostMapColor.closestZoom`) inside 60 m. On a
    /// fast road the turn arrives sooner, so both distances are at least
    /// a few seconds of driving: at 100 km/h it starts 330 m out and is
    /// fully in by 110 m. Arriving is not a turn; the door view frames
    /// that.
    func closeInOnTurn(_ progress: RouteProgress) {
        guard let viewport = _navigationViewController?.navigationMapView?.navigationCamera
            .viewportDataSource as? NavigationViewportDataSource else { return }
        let far = 10.5, sdkClosest = 16.35, near = HostMapColor.closestZoom - 0.25
        var least = far
        if let step = progress.currentLegProgress.upcomingStep, step.maneuverType != .arrive {
            let distance = progress.currentLegProgress.currentStepProgress.distanceRemaining
            let speed = max(0, _lastKnownLocation?.speed ?? 0)
            let begin = max(200, speed * 12)
            let closest = max(60, speed * 4)
            if distance <= closest {
                least = near
            } else if distance <= begin {
                least = sdkClosest + (begin - distance) / (begin - closest) * (near - sdkClosest)
            }
        }
        let range = viewport.options.followingCameraOptions.zoomRange
        guard abs(range.lowerBound - least) > 0.05 || range.upperBound != HostMapColor.closestZoom else { return }
        viewport.options.followingCameraOptions.zoomRange = least...HostMapColor.closestZoom
    }

    // MARK: Where the stop is, at the end of the route

    /// Marks the end of the route with a pin whose arrow points at the
    /// stop. The route ends on the road; the stop is a house or a door
    /// to one side of it, and which side is the first thing the driver
    /// needs to know as they pull up.
    ///
    /// Shown on the last stretch and in the door view, not in the
    /// overview, which has the numbered pins.
    func markDestination(_ progress: RouteProgress) {
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        let leg = progress.currentLeg
        let arriving = progress.currentLegProgress.upcomingStep?.maneuverType == .arrive || _doorView != nil
        var pin: (CLLocationCoordinate2D, CLLocationDirection?)?
        if arriving, _cameraState != "overview", let last = leg.steps.last {
            let road = last.maneuverLocation
            pin = (road, Self.direction(toStopFrom: road, step: last,
                                        stop: _doorView?.coordinate ?? leg.destination?.coordinate))
        }
        let key = pin.map { "\($0.0.latitude),\($0.0.longitude),\($0.1.map { String(Int($0.rounded())) } ?? "-")" } ?? ""
        guard key != _shownDestinationPin else { return }
        _shownDestinationPin = key
        if _destinationPinManager == nil {
            let manager = mapView.annotations.makePointAnnotationManager(id: "host-destination-pin")
            // The arrow is a direction on the ground: it turns with the
            // map, and nothing may hide it.
            manager.iconRotationAlignment = .map
            manager.iconAllowOverlap = true
            manager.iconIgnorePlacement = true
            _destinationPinManager = manager
        }
        guard let (road, direction) = pin else {
            _destinationPinManager?.annotations = []
            return
        }
        var annotation = PointAnnotation(coordinate: road)
        annotation.image = .init(image: Self.destinationPinImage(pointing: direction != nil),
                                 name: direction != nil ? "host-destination-arrow" : "host-destination-dot")
        annotation.iconRotate = direction ?? 0
        _destinationPinManager?.annotations = [annotation]
    }

    /// Which way the stop lies from the end of the route, as a compass
    /// bearing. From the two points when they are far enough apart to
    /// tell; otherwise from the side the directions give for the arrival.
    /// Nil when neither says.
    static func direction(toStopFrom road: CLLocationCoordinate2D,
                          step: RouteStep,
                          stop: CLLocationCoordinate2D?) -> CLLocationDirection? {
        if let stop = stop,
           CLLocation(latitude: road.latitude, longitude: road.longitude)
            .distance(from: CLLocation(latitude: stop.latitude, longitude: stop.longitude)) >= 4 {
            return road.direction(to: stop)
        }
        guard let heading = step.initialHeading ?? step.finalHeading else { return nil }
        switch step.maneuverDirection {
        case .left?, .slightLeft?, .sharpLeft?: return (heading + 270).truncatingRemainder(dividingBy: 360)
        case .right?, .slightRight?, .sharpRight?: return (heading + 90).truncatingRemainder(dividingBy: 360)
        default: return nil
        }
    }

    /// A dark disc with a white ring; `pointing` adds a white arrow that
    /// points up, to be turned towards the stop.
    static func destinationPinImage(pointing: Bool) -> UIImage {
        let diameter: CGFloat = 40
        let size = CGSize(width: diameter + 4, height: diameter + 4)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let disc = CGRect(x: center.x - diameter / 2, y: center.y - diameter / 2, width: diameter, height: diameter)
            cg.setFillColor(HostMapColor.tip.cgColor)
            cg.fillEllipse(in: disc)
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(3)
            cg.strokeEllipse(in: disc.insetBy(dx: 1.5, dy: 1.5))
            cg.setFillColor(UIColor.white.cgColor)
            guard pointing else {
                cg.fillEllipse(in: CGRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10))
                return
            }
            // A head and a shaft, tip at the top.
            let arrow = UIBezierPath()
            arrow.move(to: CGPoint(x: center.x, y: center.y - 13))
            arrow.addLine(to: CGPoint(x: center.x + 10, y: center.y - 1))
            arrow.addLine(to: CGPoint(x: center.x + 3.5, y: center.y - 1))
            arrow.addLine(to: CGPoint(x: center.x + 3.5, y: center.y + 12))
            arrow.addLine(to: CGPoint(x: center.x - 3.5, y: center.y + 12))
            arrow.addLine(to: CGPoint(x: center.x - 3.5, y: center.y - 1))
            arrow.addLine(to: CGPoint(x: center.x - 10, y: center.y - 1))
            arrow.close()
            arrow.fill()
        }
    }

    /// Draws the faster route on offer, or takes it off when the offer
    /// has gone: the part of it that leaves the route in use, in green,
    /// with "N min faster" on it and "Yours" on the route in use.
    func showFasterRouteOnMap(_ faster: AlternativeRoute?) {
        guard faster?.id != _fasterRouteOnMap else { return }
        _fasterRouteOnMap = faster?.id
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        let style = mapView.mapboxMap.style
        let id = "host-faster-route"
        for layer in [id, id + "-casing"] where style.layerExists(withId: layer) {
            try? style.removeLayer(withId: layer)
        }
        if style.sourceExists(withId: id) { try? style.removeSource(withId: id) }
        _fasterTips = []
        defer { refreshTags() }

        guard let faster = faster,
              let whole = faster.indexedRouteResponse.currentRoute?.shape,
              let end = whole.coordinates.last,
              // Only where it leaves the route in use: the shared stretch
              // stays the colour of the route the driver is on.
              let branch = whole.sliced(from: faster.alternativeRouteIntersection.location, to: end) else { return }
        var source = GeoJSONSource()
        source.data = .geometry(.lineString(branch))
        try? style.addSource(source, id: id)
        var casing = LineLayer(id: id + "-casing")
        casing.source = id
        casing.lineColor = .constant(StyleColor(.white))
        casing.lineWidth = .constant(12)
        casing.lineCap = .constant(.round)
        casing.lineJoin = .constant(.round)
        var line = LineLayer(id: id)
        line.source = id
        line.lineColor = .constant(StyleColor(HostMapColor.green))
        line.lineWidth = .constant(7)
        line.lineCap = .constant(.round)
        line.lineJoin = .constant(.round)
        let main = style.allLayerIdentifiers.map { $0.id }.last { $0.hasSuffix(".main.route_line") }
        if let main = main {
            try? style.addLayer(casing, layerPosition: .above(main))
        } else {
            try? style.addLayer(casing)
        }
        try? style.addLayer(line, layerPosition: .above(id + "-casing"))

        let minutes = max(1, Int((-faster.expectedTravelTimeDelta / 60).rounded()))
        if let length = branch.distance(), let at = branch.coordinateFromStart(distance: length * 0.4) {
            _fasterTips.append((at, "\(minutes) min faster"))
        }
        if let mine = _lastProgress?.route.shape, let mineEnd = mine.coordinates.last,
           let rest = mine.sliced(from: faster.mainRouteIntersection.location, to: mineEnd),
           let length = rest.distance(), let at = rest.coordinateFromStart(distance: length * 0.4) {
            _fasterTips.append((at, "Yours"))
        }
    }

    /// One manager for both kinds of tag.
    func refreshTags() {
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        // The door view has the stop's own marking, and the overview is
        // for the shape of the trip. The faster route's tags stay in any
        // view: the host switches to the overview when it offers one.
        let overview = _cameraState == "overview"
        let turn = (_doorView == nil && !overview && _fasterTips.isEmpty) ? _turnTag : nil
        // Traffic is a thing of the road, not of the door; and a faster
        // route on offer says the same with a way out attached.
        let traffic = (_doorView == nil && _fasterTips.isEmpty) ? _trafficTag : nil
        let key = [turn.map { "\($0.0.latitude),\($0.0.longitude),\($0.1)" } ?? "",
                   traffic.map { "\($0.0.latitude),\($0.0.longitude),\($0.1)" } ?? ""]
            + _fasterTips.map { "\($0.0.latitude),\($0.0.longitude),\($0.1)" }
        let joined = key.joined(separator: ";") + (_nightMode ? "n" : "d")
        guard joined != _shownTags else { return }
        _shownTags = joined
        if _tagManager == nil {
            _tagManager = mapView.annotations.makePointAnnotationManager(id: "host-tags")
        }
        var tags: [PointAnnotation] = []
        if let turn = turn {
            var tag = PointAnnotation(coordinate: turn.0)
            tag.image = .init(image: Self.tagImage(turn.1, fill: HostMapColor.tip, pointer: true), name: "host-turn-\(turn.1)")
            tag.iconAnchor = .bottom
            tag.iconOffset = [0, -8]
            tags.append(tag)
        }
        if let traffic = traffic {
            var tag = PointAnnotation(coordinate: traffic.0)
            tag.image = .init(image: Self.tagImage(traffic.1, fill: HostMapColor.trafficHeavy, pointer: false),
                              name: "host-traffic-\(traffic.1)")
            tags.append(tag)
        }
        for (at, text) in _fasterTips {
            var tag = PointAnnotation(coordinate: at)
            let yours = text == "Yours"
            tag.image = .init(image: Self.tagImage(text, fill: yours ? HostMapColor.tip : HostMapColor.tipGreen, pointer: false),
                              name: "host-tip-\(text)")
            tag.symbolSortKey = yours ? 0 : 1
            tags.append(tag)
        }
        _tagManager?.annotations = tags
    }

    /// A dark or green rounded tag with white text; `pointer` adds a tail
    /// pointing down.
    static func tagImage(_ text: String, fill: UIColor, pointer: Bool) -> UIImage {
        let font = UIFont.systemFont(ofSize: pointer ? 14 : 16, weight: .bold)
        let label = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: UIColor.white])
        let textSize = label.size()
        let bubble = CGSize(width: textSize.width + 24, height: pointer ? 31 : 36)
        let tail: CGFloat = pointer ? 9 : 0
        let size = CGSize(width: bubble.width + 4, height: bubble.height + tail + 4)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let rect = CGRect(x: 2, y: 2, width: bubble.width, height: bubble.height)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: pointer ? 9 : 12)
            if pointer {
                path.move(to: CGPoint(x: rect.midX - 8, y: rect.maxY - 1))
                path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY + tail))
                path.addLine(to: CGPoint(x: rect.midX + 8, y: rect.maxY - 1))
                path.close()
            }
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            fill.setFill()
            path.fill()
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            label.draw(at: CGPoint(x: rect.midX - textSize.width / 2, y: rect.midY - textSize.height / 2))
        }
    }

    // MARK: Route look

    /// "normal", "faded" or "left" (see `NavRouteLook` on the Dart side).
    func setRouteLook(_ look: String) {
        guard look != _routeLook else { return }
        _routeLook = look
        applyRouteLook()
    }

    /// Runs again on every tick while the look is not the normal one:
    /// the SDK rebuilds its route layers whenever it redraws the route.
    func applyRouteLook() {
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        let style = mapView.mapboxMap.style
        // The SDK names its layers after the route object; the suffixes
        // are what is stable.
        // The third is the travelled-route layer, which runs the whole
        // length of the route under the other two.
        let mainLine = style.allLayerIdentifiers.map { $0.id }.filter {
            $0.hasSuffix(".main.route_line") || $0.hasSuffix(".main.route_line_casing")
                || $0.hasSuffix(".traversed_route")
        }
        let opacity: Double
        switch _routeLook {
        case "faded": opacity = 0.55
        case "left": opacity = 0
        default: opacity = 1
        }
        for id in mainLine {
            try? style.setLayerProperty(for: id, property: "line-opacity", value: opacity)
        }

        let leftId = "host-left-route"
        guard _routeLook == "left" else {
            if style.layerExists(withId: leftId) { try? style.removeLayer(withId: leftId) }
            if style.sourceExists(withId: leftId) { try? style.removeSource(withId: leftId) }
            return
        }
        // Drawn once, when the driver leaves: the part of the old route
        // that was still ahead of them.
        guard !style.layerExists(withId: leftId),
              let progress = _lastProgress,
              let shape = progress.route.shape,
              let ahead = shape.trimmed(from: progress.distanceTraveled, to: shape.distance() ?? 0) else { return }
        var source = GeoJSONSource()
        source.data = .geometry(.lineString(ahead))
        try? style.addSource(source, id: leftId)
        var layer = LineLayer(id: leftId)
        layer.source = leftId
        layer.lineColor = .constant(StyleColor(HostMapColor.leftRoute))
        layer.lineWidth = .constant(8)
        layer.lineCap = .constant(.round)
        layer.lineDasharray = .constant([0.5, 1.5])
        if let above = mainLine.last {
            try? style.addLayer(layer, layerPosition: .above(above))
        } else {
            try? style.addLayer(layer)
        }
    }

    // MARK: Mapbox wordmark and attribution

    /// Both have to stay in view. The SDK parks them in the bottom
    /// corners, behind the host's speed sign and tool buttons; they go
    /// just above the host's sheet, left of the tool column.
    func placeOrnaments() {
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        let bottom = max(0, _cameraPaddingBottom - mapView.safeAreaInsets.bottom) + 6
        var options = mapView.ornaments.options
        let logo = CGPoint(x: 96, y: bottom)
        let attribution = CGPoint(x: 96, y: bottom + 26)
        // Setting them lays every ornament out again, and this runs on
        // every tick.
        if options.logo.position == .bottomRight, options.logo.margins == logo,
           options.attributionButton.position == .bottomRight, options.attributionButton.margins == attribution {
            return
        }
        options.logo.position = .bottomRight
        options.logo.margins = logo
        options.attributionButton.position = .bottomRight
        options.attributionButton.margins = attribution
        mapView.ornaments.options = options
    }

    // MARK: Stop pins (overview)

    func setStopPins(_ pins: [[String: Any]]) {
        _stopPins = pins
        refreshStopPins()
    }

    /// Later stops show as numbered pins while the whole route is on
    /// screen, and stay out of the way while following.
    func refreshStopPins() {
        // Whatever changed the pins (the camera, the door view) changes
        // which tags belong on the map too, and the pin at the stop.
        refreshTags()
        if let progress = _lastProgress { markDestination(progress) }
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        if _stopPinManager == nil {
            _stopPinManager = mapView.annotations.makePointAnnotationManager(id: "host-stop-pins")
        }
        guard let manager = _stopPinManager else { return }
        let overview = _cameraState == "overview"
        manager.annotations = _stopPins.compactMap { pin in
            guard let lat = pin["latitude"] as? Double, let lng = pin["longitude"] as? Double else { return nil }
            let label = pin["label"] as? String ?? ""
            let current = pin["current"] as? Bool ?? false
            let partner = (pin["kind"] as? String) == "partner"
            // Later stops belong to the overview. The stop being driven
            // to stays marked until the door view takes over, and the
            // other driver's position is worth seeing all the way in.
            guard partner || overview || (current && _doorView == nil) else { return nil }
            var annotation = PointAnnotation(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng))
            // Stops can share a spot (pickup and sort at the depot): the
            // one being driven to goes on top, the other driver above all.
            annotation.symbolSortKey = partner ? 2 : (current ? 1 : 0)
            if partner {
                annotation.image = .init(image: Self.partnerPinImage(tag: label), name: "host-partner-pin-\(label)")
                annotation.iconAnchor = .top
                annotation.iconOffset = [0, -18]
            } else {
                annotation.image = .init(image: Self.stopPinImage(label: label, current: current),
                                         name: "host-stop-pin-\(label)-\(current)")
            }
            return annotation
        }
    }

    /// The other driver: a blue disc with a person on it, and a white tag
    /// underneath saying who it is.
    static func partnerPinImage(tag: String) -> UIImage {
        let disc: CGFloat = 32
        let font = UIFont.systemFont(ofSize: 14, weight: .bold)
        let text = NSAttributedString(string: tag, attributes: [.font: font, .foregroundColor: HostMapColor.ink])
        let textSize = tag.isEmpty ? .zero : text.size()
        let tagSize = tag.isEmpty ? CGSize.zero : CGSize(width: textSize.width + 16, height: 26)
        let size = CGSize(width: max(disc + 4, tagSize.width + 4), height: disc + 4 + (tag.isEmpty ? 0 : tagSize.height + 6))
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let center = CGPoint(x: size.width / 2, y: disc / 2 + 2)
            let rect = CGRect(x: center.x - disc / 2, y: 2, width: disc, height: disc)
            cg.setShadow(offset: CGSize(width: 0, height: 2), blur: 4, color: UIColor.black.withAlphaComponent(0.35).cgColor)
            cg.setFillColor(HostMapColor.route.cgColor)
            cg.fillEllipse(in: rect)
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(2.5)
            cg.strokeEllipse(in: rect.insetBy(dx: 1.25, dy: 1.25))
            // Head and shoulders.
            cg.setFillColor(UIColor.white.cgColor)
            cg.fillEllipse(in: CGRect(x: center.x - 4.5, y: center.y - 9, width: 9, height: 9))
            let body = UIBezierPath(roundedRect: CGRect(x: center.x - 8, y: center.y + 1.5, width: 16, height: 8.5),
                                    byRoundingCorners: [.topLeft, .topRight],
                                    cornerRadii: CGSize(width: 7, height: 7))
            body.fill()
            guard !tag.isEmpty else { return }
            let tagRect = CGRect(x: (size.width - tagSize.width) / 2, y: disc + 8, width: tagSize.width, height: tagSize.height)
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.25).cgColor)
            UIColor.white.setFill()
            UIBezierPath(roundedRect: tagRect, cornerRadius: 8).fill()
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            text.draw(at: CGPoint(x: tagRect.midX - textSize.width / 2, y: tagRect.midY - textSize.height / 2))
        }
    }

    static func stopPinImage(label: String, current: Bool) -> UIImage {
        let diameter: CGFloat = current ? 44 : 32
        let halo: CGFloat = current ? 6 : 0
        let size = CGSize(width: diameter + halo * 2 + 4, height: diameter + halo * 2 + 4)
        return UIGraphicsImageRenderer(size: size).image { context in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let cg = context.cgContext
            if current {
                cg.setFillColor(HostMapColor.green.withAlphaComponent(0.45).cgColor)
                cg.fillEllipse(in: CGRect(x: center.x - diameter / 2 - halo, y: center.y - diameter / 2 - halo,
                                          width: diameter + halo * 2, height: diameter + halo * 2))
            }
            let disc = CGRect(x: center.x - diameter / 2, y: center.y - diameter / 2, width: diameter, height: diameter)
            cg.setFillColor(HostMapColor.ink.cgColor)
            cg.fillEllipse(in: disc)
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(2.5)
            cg.strokeEllipse(in: disc.insetBy(dx: 1.25, dy: 1.25))
            let font = UIFont.systemFont(ofSize: current ? 19 : 15, weight: .bold)
            let text = NSAttributedString(string: label, attributes: [.font: font, .foregroundColor: UIColor.white])
            let bounds = text.size()
            text.draw(at: CGPoint(x: center.x - bounds.width / 2, y: center.y - bounds.height / 2))
        }
    }

    // MARK: Door view

    func setDoorView(arguments: NSDictionary?) {
        let enabled = arguments?["enabled"] as? Bool ?? false
        guard let navigationViewController = _navigationViewController,
              let map = navigationViewController.navigationMapView else { return }
        wakeMap()
        guard enabled else {
            leaveDoorView()
            return
        }
        var coordinate: CLLocationCoordinate2D?
        if let lat = arguments?["latitude"] as? Double, let lng = arguments?["longitude"] as? Double {
            coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lng)
        }
        coordinate = coordinate ?? navigationViewController.navigationService.routeProgress.currentLeg.destination?.coordinate
        let entering = _doorView == nil
        _doorView = DoorView(side: arguments?["side"] as? String,
                             coordinate: coordinate,
                             label: arguments?["label"] as? String)
        if entering {
            map.navigationCamera.stop()
            refreshStopPins()
        }
        updateDoorView(duration: 1.0)
    }

    func leaveDoorView() {
        guard _doorView != nil else { return }
        _doorView = nil
        let map = _navigationViewController?.navigationMapView
        map?.unhighlightBuildings()
        _doorPointManager?.annotations = []
        _doorPolygonManager?.annotations = []
        refreshStopPins()
        if _overviewRequested {
            map?.navigationCamera.moveToOverview()
        } else {
            map?.navigationCamera.follow()
        }
    }

    /// Runs on every progress tick while the door view is on: keeps the
    /// camera on the driver, and keeps trying to mark the building until
    /// it has scrolled into view and can be found.
    func updateDoorView(duration: TimeInterval = 1.0) {
        // The driver asked for the whole trip: the door camera waits
        // until they re-centre, or the two fight over the map each tick.
        guard !_overviewRequested else { return }
        guard var door = _doorView,
              let map = _navigationViewController?.navigationMapView,
              let location = _lastKnownLocation ?? map.mapView.location.latestLocation?.location else { return }
        let bounds = map.mapView.bounds
        let clearHeight = max(120, bounds.height - _cameraPaddingTop - _cameraPaddingBottom)
        // Every tick, because switching day and night drops the layer.
        addHouseNumbers(to: map.mapView)

        // Close enough to read house numbers, far enough that the stop is
        // on screen: fit the remaining distance into the clear area ahead
        // of the driver.
        var zoom: CGFloat = 18.6
        if let destination = door.coordinate {
            let remaining = location.distance(from: CLLocation(latitude: destination.latitude, longitude: destination.longitude))
            let metresPerPoint = (remaining + 20) / Double(clearHeight * 0.62)
            let fitted = log2(78271.484 * cos(location.coordinate.latitude * .pi / 180) / max(metresPerPoint, 0.01))
            zoom = CGFloat(min(18.8, max(16.6, fitted)))
        }

        // The stop's side of the street gets most of the width: the
        // driver sits toward the other edge.
        let shift = bounds.width * 0.3
        var padding = UIEdgeInsets(top: _cameraPaddingTop + clearHeight * 0.38,
                                   left: 0,
                                   bottom: _cameraPaddingBottom,
                                   right: 0)
        if door.side == "left" { padding.left = shift }
        if door.side == "right" { padding.right = shift }

        let camera = CameraOptions(center: location.coordinate,
                                   padding: padding,
                                   zoom: zoom,
                                   bearing: location.course >= 0 ? location.course : nil,
                                   pitch: 20)
        // Stopped at the door with the camera already there, there is
        // nothing to move; an animation to the same place still makes the
        // map draw every frame of it.
        if !cameraIs(at: camera, on: map.mapView) {
            map.mapView.camera.ease(to: camera, duration: duration, curve: .linear, completion: nil)
        }

        if let destination = door.coordinate, !door.footprintFound, door.highlightAttempts < 12 {
            door.highlightAttempts += 1
            let attempt = door.highlightAttempts
            _doorView = door
            map.highlightBuildings(at: [destination], in3D: false) { [weak self] found in
                guard let self = self, var current = self._doorView else { return }
                if found { current.footprintFound = true }
                self._doorView = current
                // No footprint under the pin after it has had time to
                // come on screen: mark the spot instead of the building.
                // Once the building itself is lit up, nothing is drawn
                // over it: a number tag on top hid the very house the
                // driver is looking for. The tag marks the spot only
                // while there is no building to light.
                self.showDoorMarker(at: destination, label: current.label,
                                    ring: !found && attempt >= 4, tag: !found)
            }
        }
    }

    /// True when the map already shows what `camera` asks for.
    private func cameraIs(at camera: CameraOptions, on mapView: MapView) -> Bool {
        let now = mapView.cameraState
        if let center = camera.center,
           CLLocation(latitude: center.latitude, longitude: center.longitude)
            .distance(from: CLLocation(latitude: now.center.latitude, longitude: now.center.longitude)) > 0.3 {
            return false
        }
        if let zoom = camera.zoom, abs(zoom - now.zoom) > 0.01 { return false }
        if let pitch = camera.pitch, abs(pitch - now.pitch) > 0.5 { return false }
        if let bearing = camera.bearing {
            let turn = abs((bearing - now.bearing).truncatingRemainder(dividingBy: 360))
            if min(turn, 360 - turn) > 0.5 { return false }
        }
        if let padding = camera.padding {
            let was = now.padding
            if abs(padding.top - was.top) > 0.5 || abs(padding.bottom - was.bottom) > 0.5
                || abs(padding.left - was.left) > 0.5 || abs(padding.right - was.right) > 0.5 {
                return false
            }
        }
        return true
    }

    private func showDoorMarker(at coordinate: CLLocationCoordinate2D, label: String?, ring: Bool, tag: Bool) {
        guard let mapView = _navigationViewController?.navigationMapView?.mapView else { return }
        if _doorPolygonManager == nil {
            _doorPolygonManager = mapView.annotations.makePolygonAnnotationManager(id: "host-door-ring")
        }
        if _doorPointManager == nil {
            _doorPointManager = mapView.annotations.makePointAnnotationManager(id: "host-door-marker")
        }
        if ring {
            var polygon = PolygonAnnotation(polygon: Polygon([Self.circle(around: coordinate, radius: 15)]))
            polygon.fillColor = StyleColor(HostMapColor.buildingFill)
            polygon.fillOpacity = 0.55
            polygon.fillOutlineColor = StyleColor(HostMapColor.green)
            _doorPolygonManager?.annotations = [polygon]
        } else {
            _doorPolygonManager?.annotations = []
        }
        guard tag else {
            _doorPointManager?.annotations = []
            return
        }
        var marker = PointAnnotation(coordinate: coordinate)
        let text = (label ?? "").trimmingCharacters(in: .whitespaces)
        marker.image = .init(image: Self.doorMarkerImage(label: text), name: "host-door-\(text)")
        marker.iconAnchor = .bottom
        _doorPointManager?.annotations = [marker]
    }

    static func circle(around center: CLLocationCoordinate2D, radius: CLLocationDistance) -> [CLLocationCoordinate2D] {
        let steps = 48
        let latitudeMetres = 111_320.0
        let longitudeMetres = latitudeMetres * cos(center.latitude * .pi / 180)
        return (0...steps).map { step in
            let angle = Double(step) / Double(steps) * 2 * .pi
            return CLLocationCoordinate2D(latitude: center.latitude + radius * cos(angle) / latitudeMetres,
                                          longitude: center.longitude + radius * sin(angle) / longitudeMetres)
        }
    }

    /// A green callout with the house number, pointing down at the stop.
    static func doorMarkerImage(label: String) -> UIImage {
        let font = UIFont.systemFont(ofSize: 20, weight: .bold)
        let text = NSAttributedString(string: label.isEmpty ? "Stop" : label,
                                      attributes: [.font: font, .foregroundColor: UIColor.white])
        let textSize = text.size()
        let bubble = CGSize(width: max(52, textSize.width + 24), height: textSize.height + 14)
        let pointer: CGFloat = 9
        let size = CGSize(width: bubble.width + 4, height: bubble.height + pointer + 4)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let rect = CGRect(x: 2, y: 2, width: bubble.width, height: bubble.height)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 12)
            path.move(to: CGPoint(x: rect.midX - pointer, y: rect.maxY - 1))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY + pointer))
            path.addLine(to: CGPoint(x: rect.midX + pointer, y: rect.maxY - 1))
            path.close()
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            HostMapColor.green.setFill()
            path.fill()
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            text.draw(at: CGPoint(x: rect.midX - textSize.width / 2, y: rect.midY - textSize.height / 2))
        }
    }

    /// House numbers on every building, from the street data the base
    /// style already carries but does not draw.
    private func addHouseNumbers(to mapView: MapView) {
        let style = mapView.mapboxMap.style
        let layerId = "host-house-numbers"
        guard !style.layerExists(withId: layerId), style.sourceExists(withId: "composite") else { return }
        var layer = SymbolLayer(id: layerId)
        layer.source = "composite"
        layer.sourceLayer = "housenum_label"
        layer.minZoom = 16.5
        layer.textField = .expression(Exp(.get) { "house_num" })
        layer.textSize = .constant(15)
        layer.textColor = .constant(StyleColor(_nightMode ? .white : HostMapColor.ink))
        layer.textHaloColor = .constant(StyleColor(_nightMode ? HostMapColor.ink : .white))
        layer.textHaloWidth = .constant(1.5)
        layer.textFont = .constant(["DIN Pro Medium", "Arial Unicode MS Regular"])
        try? style.addLayer(layer)
    }
}
