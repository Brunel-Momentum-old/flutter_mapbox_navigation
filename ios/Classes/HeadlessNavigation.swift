import Flutter
import UIKit
import CoreLocation
import MapboxMaps
import MapboxDirections
import MapboxCoreNavigation
import MapboxNavigation

/// An invisible banner whose only job is to reserve height.
///
/// The embedded view draws none of Mapbox's own controls; the host app
/// draws its banner and sheet over the map. Mapbox still has to know how
/// much of the map they cover, and it works that out from the height of
/// its top and bottom banners. So the host's `setCameraPadding` sizes two
/// of these, and the SDK's own viewport logic keeps the puck clear.
final class SpacerBannerViewController: ContainerViewController {
    private var heightConstraint: NSLayoutConstraint?

    var height: CGFloat = 0 {
        didSet {
            guard oldValue != height else { return }
            heightConstraint?.constant = height
            view.superview?.setNeedsLayout()
        }
    }

    override func loadView() {
        let spacer = UIView()
        spacer.backgroundColor = .clear
        spacer.isUserInteractionEnabled = false
        view = spacer
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let constraint = view.heightAnchor.constraint(equalToConstant: height)
        constraint.priority = UILayoutPriority(999)
        constraint.isActive = true
        heightConstraint = constraint
    }
}

/// How much faster an alternative has to be before it is offered.
let fasterRouteMinimumSaving: TimeInterval = 120

extension NavigationFactory {

    /// Strips every stock control off an embedded
    /// `NavigationViewController`, leaving the map, route line, puck and
    /// voice.
    func makeHeadless(_ navigationViewController: NavigationViewController) {
        navigationViewController.showsReportFeedback = false
        navigationViewController.showsEndOfRouteFeedback = false
        navigationViewController.showsSpeedLimits = false
        navigationViewController.floatingButtons = []
        navigationViewController.showsContinuousAlternatives = true

        let navigationView = navigationViewController.navigationView
        for container in [navigationView.topBannerContainerView, navigationView.bottomBannerContainerView] {
            container.backgroundColor = .clear
            container.alpha = 0
            container.isUserInteractionEnabled = false
        }
        // The SDK toggles `isHidden` on these as the camera changes mode,
        // so hide them with alpha, which it never touches.
        navigationView.wayNameView.alpha = 0
        navigationView.wayNameView.isUserInteractionEnabled = false
        for subview in navigationView.subviews where subview is ResumeButton {
            subview.alpha = 0
            subview.isUserInteractionEnabled = false
        }

        // A faster route is offered to the driver, never taken for them.
        navigationViewController.navigationService.router.reroutesProactively = false

        applyMapLook(navigationViewController)

        NotificationCenter.default.removeObserver(self, name: .navigationCameraStateDidChange, object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(headlessCameraStateDidChange(_:)),
                                               name: .navigationCameraStateDidChange,
                                               object: navigationViewController.navigationMapView?.navigationCamera)

        for recognizer in navigationViewController.navigationMapView?.mapView.gestureRecognizers ?? []
        where recognizer is UIPanGestureRecognizer
            || recognizer is UIPinchGestureRecognizer
            || recognizer is UIRotationGestureRecognizer {
            recognizer.addTarget(self, action: #selector(headlessUserGesture(_:)))
        }
    }

    @objc func headlessCameraStateDidChange(_ notification: Notification) {
        guard let state = notification.userInfo?[NavigationCamera.NotificationUserInfoKey.state] as? NavigationCameraState else { return }
        switch state {
        case .following, .transitionToFollowing:
            reportCameraState("following")
        case .overview, .transitionToOverview:
            reportCameraState("overview")
        case .idle:
            // The SDK also drops to idle with nobody touching the map
            // (seen mid-drive on a simulated route). Only a real pan,
            // pinch or rotate may leave the driver without a following
            // camera, so wait a beat for the gesture to register, and
            // otherwise put the camera back where the host asked for it.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
                // The door view drives the camera by hand; idle is its
                // normal state, not something to undo or report.
                guard let self = self, self._doorView == nil,
                      let camera = self._navigationViewController?.navigationMapView?.navigationCamera,
                      camera.state == .idle else { return }
                if self.userIsMovingMap() {
                    self.reportCameraState("free")
                } else if self._overviewRequested {
                    camera.moveToOverview()
                } else {
                    camera.follow()
                }
            }
        }
    }

    func reportCameraState(_ value: String) {
        guard value != _cameraState else { return }
        _cameraState = value
        sendEvent(eventType: MapBoxEventType.camera_state, data: value)
        refreshStopPins()
    }

    /// True while, or just after, the driver's fingers moved the map.
    private func userIsMovingMap() -> Bool {
        if let last = _lastUserGestureAt, Date().timeIntervalSince(last) < 1.0 { return true }
        let recognizers = _navigationViewController?.navigationMapView?.mapView.gestureRecognizers ?? []
        return recognizers.contains {
            ($0 is UIPanGestureRecognizer || $0 is UIPinchGestureRecognizer || $0 is UIRotationGestureRecognizer)
                && ($0.state == .began || $0.state == .changed)
        }
    }

    @objc func headlessUserGesture(_ recognizer: UIGestureRecognizer) {
        if recognizer.state == .began || recognizer.state == .changed {
            _lastUserGestureAt = Date()
        }
    }

    /// Applies the host's covered insets, given in points from the edges
    /// of the map view.
    func applyCameraPadding() {
        guard let navigationViewController = _navigationViewController else { return }
        let safeArea = navigationViewController.view.safeAreaInsets
        _topSpacer?.height = max(0, _cameraPaddingTop - safeArea.top)
        _bottomSpacer?.height = max(0, _cameraPaddingBottom - safeArea.bottom)
        navigationViewController.view.setNeedsLayout()
        // After the SDK has laid its own ornaments out for the new height.
        DispatchQueue.main.async { [weak self] in self?.placeOrnaments() }
    }

    func setMuted(_ muted: Bool) {
        NavigationSettings.shared.voiceMuted = muted
    }

    func showOverview() {
        _overviewRequested = true
        _navigationViewController?.navigationMapView?.navigationCamera.moveToOverview()
    }

    func recenter() {
        _overviewRequested = false
        // In the door view the camera is driven by hand on every tick.
        guard _doorView == nil else {
            reportCameraState("following")
            updateDoorView(duration: 0.6)
            return
        }
        _navigationViewController?.navigationMapView?.navigationCamera.follow()
    }

    // MARK: Faster route

    /// The alternative worth offering right now, if any.
    func fasterAlternative() -> AlternativeRoute? {
        guard let router = _navigationViewController?.navigationService.router else { return nil }
        return router.continuousAlternatives
            .filter { !_declinedAlternatives.contains($0.id) }
            .filter { $0.expectedTravelTimeDelta <= -fasterRouteMinimumSaving }
            .min { $0.expectedTravelTimeDelta < $1.expectedTravelTimeDelta }
    }

    func acceptFasterRoute(result: @escaping FlutterResult) {
        guard let alternative = fasterAlternative(),
              let router = _navigationViewController?.navigationService.router else {
            result(false)
            return
        }
        router.updateRoute(with: alternative.indexedRouteResponse, routeOptions: nil) { [weak self] success in
            if success {
                self?.sendEvent(eventType: MapBoxEventType.reroute_along)
            }
            result(success)
        }
    }

    func declineFasterRoute() {
        guard let alternative = fasterAlternative() else { return }
        _declinedAlternatives.insert(alternative.id)
        emitNavState()
    }

    // MARK: nav_state

    func resetHeadlessState() {
        _offRoute = false
        _rerouting = false
        _declinedAlternatives.removeAll()
        _lastProgress = nil
        _cameraState = nil
        _overviewRequested = false
        _lastUserGestureAt = nil
        _doorView = nil
        _stopPinManager = nil
        _doorPointManager = nil
        _doorPolygonManager = nil
    }

    /// Sends the latest guidance state to the host. Safe to call at any
    /// time; does nothing until the first progress update.
    func emitNavState() {
        guard isEmbeddedNavigation, _eventSink != nil, let progress = _lastProgress else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: navState(for: progress), options: []),
              let json = String(data: data, encoding: .utf8) else { return }
        sendEvent(eventType: MapBoxEventType.nav_state, data: json)
    }

    func navState(for progress: RouteProgress) -> [String: Any] {
        var state: [String: Any] = [
            "distanceRemaining": progress.distanceRemaining,
            "durationRemaining": progress.durationRemaining,
            "etaEpochMs": (Date().timeIntervalSince1970 + progress.durationRemaining) * 1000,
            "offRoute": _offRoute,
            "rerouting": _rerouting,
            "arrived": progress.isFinalLeg && progress.currentLegProgress.userHasArrivedAtWaypoint,
        ]

        let legProgress = progress.currentLegProgress
        let stepProgress = legProgress.currentStepProgress
        state["distanceToManeuver"] = stepProgress.distanceRemaining

        if let banner = stepProgress.currentVisualInstruction {
            var maneuver = maneuverJson(banner.primaryInstruction)
            if let secondary = banner.secondaryInstruction?.text, !secondary.isEmpty {
                maneuver["secondaryText"] = secondary
            }
            state["maneuver"] = maneuver

            if let tertiary = banner.tertiaryInstruction {
                let lanes = lanesJson(tertiary)
                if lanes.isEmpty {
                    state["then"] = maneuverJson(tertiary)
                } else {
                    state["lanes"] = lanes
                }
            }
        } else if let upcoming = legProgress.upcomingStep {
            var maneuver: [String: Any] = ["type": upcoming.maneuverType.rawValue]
            if let direction = upcoming.maneuverDirection {
                maneuver["modifier"] = direction.rawValue
            }
            if let name = upcoming.names?.first {
                maneuver["text"] = name
            }
            state["maneuver"] = maneuver
        }

        if let limit = legProgress.currentSpeedLimit {
            let metric = _voiceUnits != "imperial"
            let unit: UnitSpeed = metric ? .kilometersPerHour : .milesPerHour
            let value = limit.converted(to: unit).value
            if value.isFinite, value > 0 {
                state["speedLimit"] = Int(value.rounded())
                state["speedLimitUnit"] = metric ? "km/h" : "mph"
            }
        }

        if let location = _lastKnownLocation {
            state["latitude"] = location.coordinate.latitude
            state["longitude"] = location.coordinate.longitude
            if location.course >= 0 { state["bearing"] = location.course }
            if location.speed >= 0 { state["speed"] = location.speed }
        }

        if !_offRoute, !_rerouting, let alternative = fasterAlternative() {
            state["fasterRoute"] = ["savingSeconds": -alternative.expectedTravelTimeDelta]
        }
        return state
    }

    private func maneuverJson(_ instruction: VisualInstruction) -> [String: Any] {
        var json: [String: Any] = [:]
        if let type = instruction.maneuverType { json["type"] = type.rawValue }
        if let direction = instruction.maneuverDirection { json["modifier"] = direction.rawValue }
        if let text = instruction.text, !text.isEmpty { json["text"] = text }
        return json
    }

    private func lanesJson(_ instruction: VisualInstruction) -> [[String: Any]] {
        var lanes: [[String: Any]] = []
        for component in instruction.components {
            guard case let .lane(indications, isUsable, preferredDirection) = component else { continue }
            var lane: [String: Any] = [
                "indications": indications.descriptions,
                "valid": isUsable,
            ]
            if let preferred = preferredDirection {
                lane["active"] = preferred.rawValue
            }
            lanes.append(lane)
        }
        return lanes
    }
}
