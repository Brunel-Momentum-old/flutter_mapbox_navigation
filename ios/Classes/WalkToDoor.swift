import Flutter
import CoreLocation
import MapboxDirections
import MapboxCoreNavigation
import MapboxNavigation

/// On foot for the last stretch (founder, 2026-10-19): once the driver
/// has parked near the stop and is walking, the host asks for walking
/// directions to the door, and for driving ones again if they drive off.
///
/// The new route takes the place of the old one in the trip already
/// running (`router.updateRoute`), so nothing is torn down: the door view
/// — its camera, the house numbers and the lit building — and the voice
/// carry on without a flicker. The walking options go to the router too,
/// so a re-route on foot stays on foot.
///
/// Kept in a file of its own on purpose: the app's
/// `scripts/patch_mapbox_avoid_tolls.sh` adds toll avoidance to route
/// options in the plugin's other files, and the walking profile refuses
/// any road class to avoid.
extension NavigationFactory {
    func setTravelMode(walking: Bool, result: @escaping FlutterResult) {
        guard let router = _navigationViewController?.navigationService.router,
              let here = router.location?.coordinate,
              let destination = router.routeProgress.currentLeg.destination
        else {
            result(false)
            return
        }
        let start = Waypoint(coordinate: here)
        let travel = NavigationRouteOptions(
            waypoints: [start, destination],
            profileIdentifier: walking ? .walking : .automobileAvoidingTraffic
        )
        if !walking { travel.roadClassesToAvoid = .toll }
        travel.distanceMeasurementSystem = _voiceUnits == "imperial" ? .imperial : .metric
        travel.locale = Locale(identifier: _language)
        // On foot there is one way to the door worth showing.
        travel.includesAlternativeRoutes = walking ? false : _alternatives
        _ = Directions.shared.calculate(travel) { [weak self] (_, response) in
            guard case let .success(answer) = response, !(answer.routes ?? []).isEmpty else {
                result(false)
                return
            }
            var answered = false
            router.updateRoute(
                with: IndexedRouteResponse(routeResponse: answer, routeIndex: 0),
                routeOptions: travel
            ) { success in
                answered = true
                if success { self?.emitNavState() }
                result(success)
            }
            // The router calls nobody back once the trip is over.
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                if !answered { result(false) }
            }
        }
    }
}
