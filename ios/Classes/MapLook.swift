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
            // The host offers a faster route in its own card; the SDK's
            // "2 min slower" bubbles on every alternative are noise.
            map.showsRelativeDurationOnContinuousAlternativeRoutes = false
            HostMapColor.style(map)
            if case let .courseView(view)? = map.userLocationStyle,
               let puck = view as? UserPuckCourseView {
                HostMapColor.style(puck)
            }
        }
    }

    func setNightMode(_ night: Bool) {
        guard night != _nightMode else { return }
        _nightMode = night
        _navigationViewController?.styleManager?.applyStyle(type: night ? .night : .day)
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
        let mainLine = style.allLayerIdentifiers.map { $0.id }.filter {
            $0.hasSuffix(".main.route_line") || $0.hasSuffix(".main.route_line_casing")
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
        options.logo.position = .bottomRight
        options.logo.margins = CGPoint(x: 96, y: bottom)
        options.attributionButton.position = .bottomRight
        options.attributionButton.margins = CGPoint(x: 96, y: bottom + 26)
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
        map.mapView.camera.ease(to: camera, duration: duration, curve: .linear, completion: nil)

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
