import Flutter
import UIKit
import MapboxMaps
import MapboxDirections
import MapboxCoreNavigation
import MapboxNavigation

public class NavigationFactory : NSObject, FlutterStreamHandler
{
    /// Resolve the Flutter root VC without force-casting.
    ///
    /// Under the UIScene lifecycle `AppDelegate.window` can be nil, and the
    /// key window's root is not always the FlutterViewController (a system
    /// alert, a permission prompt or another window can be key). Try the
    /// legacy lookup, then every window of every connected scene, key window
    /// first. Returns nil instead of crashing when nothing matches.
    func flutterRootViewController() -> FlutterViewController? {
        if let legacy = UIApplication.shared.delegate?.window??.rootViewController as? FlutterViewController {
            return legacy
        }
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .sorted { $0.isKeyWindow && !$1.isKeyWindow }
        for window in windows {
            if let flutter = window.rootViewController as? FlutterViewController {
                return flutter
            }
            var top = window.rootViewController
            while let presented = top?.presentedViewController {
                if let flutter = presented as? FlutterViewController {
                    return flutter
                }
                top = presented
            }
        }
        return nil
    }

    var _navigationViewController: NavigationViewController? = nil
    var _eventSink: FlutterEventSink? = nil
    
    let ALLOW_ROUTE_SELECTION = false
    let IsMultipleUniqueRoutes = false
    var isEmbeddedNavigation = false
    
    var _distanceRemaining: Double?
    var _durationRemaining: Double?
    var _navigationMode: String?
    var _routes: [Route]?
    var _wayPointOrder = [Int:Waypoint]()
    var _wayPoints = [Waypoint]()
    var _lastKnownLocation: CLLocation?
    
    var _options: NavigationRouteOptions?
    var _simulateRoute = false
    var _allowsUTurnAtWayPoints: Bool?
    var _isOptimized = false
    var _language = "en"
    var _voiceUnits = "metric"
    var _mapStyleUrlDay: String?
    var _mapStyleUrlNight: String?
    var _zoom: Double = 13.0
    var _tilt: Double = 0.0
    var _bearing: Double = 0.0
    var _animateBuildRoute = true
    var _longPressDestinationEnabled = true
    var _alternatives = true
    var _shouldReRoute = true
    var _showReportFeedbackButton = true
    var _showEndOfRouteFeedback = true
    var _enableOnMapTapCallback = false
    var navigationDirections: Directions?

    // Headless embedded navigation — see HeadlessNavigation.swift.
    var _topSpacer: SpacerBannerViewController?
    var _bottomSpacer: SpacerBannerViewController?
    var _cameraPaddingTop: CGFloat = 0
    var _cameraPaddingBottom: CGFloat = 0
    var _cameraState: String?
    var _overviewRequested = false
    var _lastUserGestureAt: Date?

    // How often the maps draw — see MapPower.swift.
    var _stillTicks = 0
    var _lastTickLocation: CLLocation?
    var _mapAwakeUntil = Date.distantPast
    let _frameTrace: FrameTrace? = hostNavTrace ? FrameTrace() : nil

    // Map look, stop pins and door view — see MapLook.swift.
    var _nightMode = false
    var _stopPins: [[String: Any]] = []
    var _stopPinManager: PointAnnotationManager?
    var _doorView: DoorView?
    var _doorPointManager: PointAnnotationManager?
    var _doorPolygonManager: PolygonAnnotationManager?
    var _tagManager: PointAnnotationManager?
    var _turnTag: (CLLocationCoordinate2D, String)?
    var _fasterTips: [(CLLocationCoordinate2D, String)] = []
    var _shownTags = ""
    var _fasterRouteOnMap: AlternativeRoute.ID?

    /// How much faster an alternative has to be before it is offered.
    /// The host may change it (`setFasterRouteMinimumSaving`).
    var _fasterRouteMinimumSaving: TimeInterval = defaultFasterRouteMinimumSaving
    var _offRoute = false
    var _lastRawFixAt: Date?
    var _guidanceStartedAt: Date?

    /// This trip began with the navigator still standing where the last
    /// one ended, and it has not yet taken in where the driver is.
    var _navigatorBehind = false
    /// The trip's route is to be set again once the navigator has the
    /// phone's position. Outlives `_navigatorBehind`: the host stops being
    /// kept waiting after a few seconds, the route still gets set.
    var _routeNeedsSettingAgain = false
    var _noRerouteUntil: Date?
    /// When re-routes were first held back because the navigator and the
    /// phone disagree about where the driver is.
    var _rerouteHeldSince: Date?
    var _routeLook = "normal"
    var _rerouting = false
    var _declinedAlternatives = Set<AlternativeRoute.ID>()
    var _lastProgress: RouteProgress?
    
    func addWayPoints(arguments: NSDictionary?, result: @escaping FlutterResult)
    {

        guard var locations = getLocationsFromFlutterArgument(arguments: arguments) else { return }

        var nextIndex = 1
        for loc in locations
        {
            let wayPoint = Waypoint(coordinate: CLLocationCoordinate2D(latitude: loc.latitude!, longitude: loc.longitude!), name: loc.name)
            wayPoint.separatesLegs = !loc.isSilent
            if (_wayPoints.count >= nextIndex) {
                _wayPoints.insert(wayPoint, at: nextIndex)
            }
            else {
                _wayPoints.append(wayPoint)
            }
            nextIndex += 1
        }
        
        startNavigationWithWayPoints(wayPoints: _wayPoints, flutterResult: result, isUpdatingWaypoints: true)
    }
    
    func startFreeDrive(arguments: NSDictionary?, result: @escaping FlutterResult)
    {
        let freeDriveViewController = FreeDriveViewController()
        guard let flutterViewController = flutterRootViewController() else {
            result(FlutterError(code: "NO_FLUTTER_VC", message: "Could not find FlutterViewController to present navigation", details: nil))
            return
        }
        flutterViewController.present(freeDriveViewController, animated: true, completion: nil)
    }
    
    func startNavigation(arguments: NSDictionary?, result: @escaping FlutterResult)
    {
        _wayPoints.removeAll()
        _wayPointOrder.removeAll()
        
        guard var locations = getLocationsFromFlutterArgument(arguments: arguments) else { return }
        
        for loc in locations
        {
            let location = Waypoint(coordinate: CLLocationCoordinate2D(latitude: loc.latitude!, longitude: loc.longitude!), name: loc.name)
            
            location.separatesLegs = !loc.isSilent
            
            _wayPoints.append(location)
            _wayPointOrder[loc.order!] = location
        }
        
        parseFlutterArguments(arguments: arguments)
        
        _options?.includesAlternativeRoutes = _alternatives
        
        if(_wayPoints.count > 3 && arguments?["mode"] == nil)
        {
            _navigationMode = "driving"
        }
        
        if(_wayPoints.count > 0)
        {
            if(IsMultipleUniqueRoutes)
            {
                startNavigationWithWayPoints(wayPoints: [_wayPoints.remove(at: 0), _wayPoints.remove(at: 0)], flutterResult: result, isUpdatingWaypoints: false)
            }
            else
            {
                startNavigationWithWayPoints(wayPoints: _wayPoints, flutterResult: result, isUpdatingWaypoints: false)
            }
            
        }
    }
    
    
    func startNavigationWithWayPoints(wayPoints: [Waypoint], flutterResult: @escaping FlutterResult, isUpdatingWaypoints: Bool)
    {
        let simulationMode: SimulationMode = _simulateRoute ? .always : .never
        setNavigationOptions(wayPoints: wayPoints)
        
        Directions.shared.calculate(_options!) { [weak self](session, result) in
            guard let strongSelf = self else { return }
            switch result {
            case .failure(let error):
                strongSelf.sendEvent(eventType: MapBoxEventType.route_build_failed, data: error.localizedDescription)
                flutterResult(FlutterError(code: "ROUTE_BUILD_FAILED", message: error.localizedDescription, details: nil))
            case .success(let response):
                guard let routes = response.routes else { return }
                //TODO: if more than one route found, give user option to select one: DOES NOT WORK
                if(routes.count > 1 && strongSelf.ALLOW_ROUTE_SELECTION)
                {
                    //show map to select a specific route
                    strongSelf._routes = routes
                    let routeOptionsView = RouteOptionsViewController(routes: routes, options: strongSelf._options!)
                    
                    guard let flutterViewController = strongSelf.flutterRootViewController() else {
                        strongSelf.sendEvent(eventType: MapBoxEventType.route_build_failed, data: "Could not find FlutterViewController to present route options")
                        flutterResult(FlutterError(code: "NO_FLUTTER_VC", message: "Could not find FlutterViewController", details: nil))
                        return
                    }
                    flutterViewController.present(routeOptionsView, animated: true, completion: nil)
                }
                else
                {
                    let navigationService = MapboxNavigationService(routeResponse: response, routeIndex: 0, routeOptions: strongSelf._options!, simulating: simulationMode)
                    var dayStyle = CustomDayStyle()
                    if(strongSelf._mapStyleUrlDay != nil){
                        dayStyle = CustomDayStyle(url: strongSelf._mapStyleUrlDay)
                    }
                    let nightStyle = CustomNightStyle()
                    if(strongSelf._mapStyleUrlNight != nil){
                        nightStyle.mapStyleURL = URL(string: strongSelf._mapStyleUrlNight!)!
                    }
                    let navigationOptions = NavigationOptions(styles: [dayStyle, nightStyle], navigationService: navigationService)
                    if (isUpdatingWaypoints) {
                        strongSelf._navigationViewController?.navigationService.router.updateRoute(with: IndexedRouteResponse(routeResponse: response, routeIndex: 0), routeOptions: strongSelf._options) { success in
                            if (success) {
                                flutterResult("true")
                            } else {
                                flutterResult("failed to add stop")
                            }
                        }
                    }
                    else {
                        strongSelf.startNavigation(routeResponse: response, options: strongSelf._options!, navOptions: navigationOptions)
                    }
                }
            }
        }
        
    }
    
    func startNavigation(routeResponse: RouteResponse, options: NavigationRouteOptions, navOptions: NavigationOptions)
    {
        isEmbeddedNavigation = false
        // A second start while a session is still presented used to re-present
        // the same view controller (UIKit throws) with the OLD route. End the
        // previous session and dismiss it first, then present the new one.
        if let previous = self._navigationViewController
        {
            previous.navigationService.endNavigation(feedback: nil)
            self._navigationViewController = nil
            if(previous.presentingViewController != nil)
            {
                previous.dismiss(animated: false, completion: { [weak self] in
                    self?.presentNavigation(routeResponse: routeResponse, options: options, navOptions: navOptions)
                })
                return
            }
        }
        presentNavigation(routeResponse: routeResponse, options: options, navOptions: navOptions)
    }

    private func presentNavigation(routeResponse: RouteResponse, options: NavigationRouteOptions, navOptions: NavigationOptions)
    {
        self._navigationViewController = NavigationViewController(for: routeResponse, routeIndex: 0, routeOptions: options, navigationOptions: navOptions)
        self._navigationViewController!.modalPresentationStyle = .fullScreen
        self._navigationViewController!.delegate = self
        self._navigationViewController?.navigationMapView?.localizeLabels()
        self._navigationViewController!.showsReportFeedback = _showReportFeedbackButton
        self._navigationViewController!.showsEndOfRouteFeedback = _showEndOfRouteFeedback
        guard let flutterViewController = flutterRootViewController() else {
            self._navigationViewController = nil
            sendEvent(eventType: MapBoxEventType.route_build_failed, data: "Could not find FlutterViewController to present navigation")
            return
        }
        sendEvent(eventType: MapBoxEventType.route_built)
        flutterViewController.present(self._navigationViewController!, animated: true, completion: nil)
    }
    
    func setNavigationOptions(wayPoints: [Waypoint]) {
        var mode: ProfileIdentifier = .automobileAvoidingTraffic
        
        if (_navigationMode == "cycling")
        {
            mode = .cycling
        }
        else if(_navigationMode == "driving")
        {
            mode = .automobile
        }
        else if(_navigationMode == "walking")
        {
            mode = .walking
        }
        let options = NavigationRouteOptions(waypoints: wayPoints, profileIdentifier: mode)
        options.roadClassesToAvoid = .toll
        
        if (_allowsUTurnAtWayPoints != nil)
        {
            options.allowsUTurnAtWaypoint = _allowsUTurnAtWayPoints!
        }
        
        options.distanceMeasurementSystem = _voiceUnits == "imperial" ? .imperial : .metric
        options.locale = Locale(identifier: _language)
        _options = options
    }
    
    func parseFlutterArguments(arguments: NSDictionary?) {
        _language = arguments?["language"] as? String ?? _language
        _voiceUnits = arguments?["units"] as? String ?? _voiceUnits
        _simulateRoute = arguments?["simulateRoute"] as? Bool ?? _simulateRoute
        _isOptimized = arguments?["isOptimized"] as? Bool ?? _isOptimized
        _allowsUTurnAtWayPoints = arguments?["allowsUTurnAtWayPoints"] as? Bool
        _navigationMode = arguments?["mode"] as? String ?? "drivingWithTraffic"
        _showReportFeedbackButton = arguments?["showReportFeedbackButton"] as? Bool ?? _showReportFeedbackButton
        _showEndOfRouteFeedback = arguments?["showEndOfRouteFeedback"] as? Bool ?? _showEndOfRouteFeedback
        _enableOnMapTapCallback = arguments?["enableOnMapTapCallback"] as? Bool ?? _enableOnMapTapCallback
        _mapStyleUrlDay = arguments?["mapStyleUrlDay"] as? String
        _mapStyleUrlNight = arguments?["mapStyleUrlNight"] as? String
        _zoom = arguments?["zoom"] as? Double ?? _zoom
        _bearing = arguments?["bearing"] as? Double ?? _bearing
        _tilt = arguments?["tilt"] as? Double ?? _tilt
        _animateBuildRoute = arguments?["animateBuildRoute"] as? Bool ?? _animateBuildRoute
        _longPressDestinationEnabled = arguments?["longPressDestinationEnabled"] as? Bool ?? _longPressDestinationEnabled
        _alternatives = arguments?["alternatives"] as? Bool ?? _alternatives
    }
    
    
    func continueNavigationWithWayPoints(wayPoints: [Waypoint])
    {
        _options?.waypoints = wayPoints
        Directions.shared.calculate(_options!) { [weak self](session, result) in
            guard let strongSelf = self else { return }
            switch result {
            case .failure(let error):
                strongSelf.sendEvent(eventType: MapBoxEventType.route_build_failed, data: error.localizedDescription)
            case .success(let response):
                strongSelf.sendEvent(eventType: MapBoxEventType.route_built, data: strongSelf.encodeRouteResponse(response: response))
                guard let routes = response.routes else { return }
                //TODO: if more than one route found, give user option to select one: DOES NOT WORK
                if(routes.count > 1 && strongSelf.ALLOW_ROUTE_SELECTION)
                {
                    //TODO: show map to select a specific route
                    
                }
                else
                {
                    strongSelf._navigationViewController?.navigationService.start()
                }
            }
        }
        
    }
    
    /// Ends the session and replies to `result` exactly once, in every branch.
    ///
    /// The embedded branch and the "nothing to end" branch used to return
    /// without replying, so a Dart `await finishNavigation()` sat until its
    /// own timeout (the driver-visible "back from navigation hangs").
    /// Ends a trip for good.
    ///
    /// `endNavigation` on the service only stops its location updates:
    /// the route stays set in the navigator, and the route controller
    /// keeps listening to it, until the controller is deallocated. That
    /// can be long after the screen has gone. The next trip then started
    /// beside a live one (the SDK logs "Two simultaneous active
    /// navigation sessions"), with the navigator still on the old route:
    /// the driver was at once "off route" and was re-routed from where
    /// the last trip ended. The route controller is told to finish now.
    static func finish(_ service: NavigationService) {
        navTrace("trip finished")
        service.endNavigation(feedback: nil)
        service.router.finishRouting()
    }

    /// Lets go of everything that belongs to the trip's map, which has
    /// just been taken down: the managers point at a map that is gone,
    /// and holding them kept that map alive.
    func releaseTripObjects() {
        _stopPinManager = nil
        _doorPointManager = nil
        _doorPolygonManager = nil
        _tagManager = nil
        _shownTags = ""
        _turnTag = nil
        _fasterTips = []
        _fasterRouteOnMap = nil
        _routeLook = "normal"
        _doorView = nil
        _lastProgress = nil
        _stillTicks = 0
        _lastTickLocation = nil
        _mapAwakeUntil = Date.distantPast
        _frameTrace?.stopWatching("nav")
    }

    func endNavigation(result: FlutterResult?)
    {
        navTrace("finishNavigation asked, live trip: \(_navigationViewController != nil)")
        sendEvent(eventType: MapBoxEventType.navigation_finished)
        guard let navigationViewController = self._navigationViewController else {
            result?(true)
            return
        }
        Self.finish(navigationViewController.navigationService)
        if(isEmbeddedNavigation)
        {
            navigationViewController.willMove(toParent: nil)
            navigationViewController.view.removeFromSuperview()
            navigationViewController.removeFromParent()
            self._navigationViewController = nil
            releaseTripObjects()
            result?(true)
        }
        else if(navigationViewController.presentingViewController != nil)
        {
            navigationViewController.dismiss(animated: true, completion: { [weak self] in
                if(self?._navigationViewController === navigationViewController)
                {
                    self?._navigationViewController = nil
                }
                result?(true)
            })
        }
        else
        {
            // Not presented (already dismissed): dismiss(completion:) may never
            // fire its completion, so reply now.
            self._navigationViewController = nil
            result?(true)
        }
    }
    
    func getLocationsFromFlutterArgument(arguments: NSDictionary?) -> [Location]? {
        
        var locations = [Location]()
        guard let oWayPoints = arguments?["wayPoints"] as? NSDictionary else {return nil}
        for item in oWayPoints as NSDictionary
        {
            let point = item.value as! NSDictionary
            guard let oName = point["Name"] as? String else {return nil }
            guard let oLatitude = point["Latitude"] as? Double else {return nil}
            guard let oLongitude = point["Longitude"] as? Double else {return nil}
            let oIsSilent = point["IsSilent"] as? Bool ?? false
            let order = point["Order"] as? Int
            let location = Location(name: oName, latitude: oLatitude, longitude: oLongitude, order: order,isSilent: oIsSilent)
            locations.append(location)
        }
        if(!_isOptimized)
        {
            //waypoints must be in the right order
            locations.sort(by: {$0.order ?? 0 < $1.order ?? 0})
        }
        return locations
    }
    
    func getLastKnownLocation() -> Waypoint
    {
        return Waypoint(coordinate: CLLocationCoordinate2D(latitude: _lastKnownLocation!.coordinate.latitude, longitude: _lastKnownLocation!.coordinate.longitude))
    }
    
    
    
    func sendEvent(eventType: MapBoxEventType, data: String = "")
    {
        let routeEvent = MapBoxRouteEvent(eventType: eventType, data: data)
        
        let jsonEncoder = JSONEncoder()
        guard let jsonData = try? jsonEncoder.encode(routeEvent),
              let eventJson = String(data: jsonData, encoding: String.Encoding.utf8) else { return }
        _eventSink?(eventJson)
        
    }
    
    func downloadOfflineRoute(arguments: NSDictionary?, flutterResult: @escaping FlutterResult)
    {
        /*
         // Create a directions client and store it as a property on the view controller.
         self.navigationDirections = NavigationDirections(credentials: Directions.shared.credentials)
         
         // Fetch available routing tile versions.
         _ = self.navigationDirections!.fetchAvailableOfflineVersions { (versions, error) in
         guard let version = versions?.first else { return }
         
         let coordinateBounds = CoordinateBounds(southWest: CLLocationCoordinate2DMake(0, 0), northEast: CLLocationCoordinate2DMake(1, 1))
         
         // Download tiles using the most recent version.
         _ = self.navigationDirections!.downloadTiles(in: coordinateBounds, version: version) { (url, response, error) in
         guard let url = url else {
         flutterResult(false)
         preconditionFailure("Unable to locate temporary file.")
         }
         
         guard let outputDirectoryURL = Bundle.mapboxCoreNavigation.suggestedTileURL(version: version) else {
         flutterResult(false)
         preconditionFailure("No suggested tile URL.")
         }
         try? FileManager.default.createDirectory(at: outputDirectoryURL, withIntermediateDirectories: true, attributes: nil)
         
         // Unpack downloaded routing tiles.
         NavigationDirections.unpackTilePack(at: url, outputDirectoryURL: outputDirectoryURL, progressHandler: { (totalBytes, bytesRemaining) in
         // Show unpacking progress.
         }, completionHandler: { (result, error) in
         // Configure the offline router with the output directory where the tiles have been unpacked.
         self.navigationDirections!.configureRouter(tilesURL: outputDirectoryURL) { (numberOfTiles) in
         // Completed, dismiss UI
         flutterResult(true)
         }
         })
         }
         }
         */
    }
    
    func encodeRouteResponse(response: RouteResponse) -> String {
        let routes = response.routes
        
        if routes != nil && !routes!.isEmpty {
            let jsonEncoder = JSONEncoder()
            guard let jsonData = try? jsonEncoder.encode(response.routes!) else { return "{}" }
            return String(data: jsonData, encoding: String.Encoding.utf8) ?? "{}"
        }
        
        return "{}"
    }
    
    //MARK: EventListener Delegates
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        _eventSink = events
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        _eventSink = nil
        return nil
    }
}


extension NavigationFactory : NavigationViewControllerDelegate {
    //MARK: NavigationViewController Delegates
    public func navigationViewController(_ navigationViewController: NavigationViewController, didUpdate progress: RouteProgress, with location: CLLocation, rawLocation: CLLocation) {
        _lastKnownLocation = location
        _lastRawFixAt = rawLocation.timestamp
        settleNavigator(navigationViewController, at: location, phone: rawLocation)
        // A re-route that fails, or that the navigator drops, tells nobody
        // it is over. A driver back on the route was left "off route" for
        // the rest of the trip: the route hidden, no door view.
        if (_offRoute || _rerouting), navigationViewController.navigationService.router.userIsOnRoute(location) {
            _offRoute = false
            _rerouting = false
        }
        navTrace("tick lat=\(String(format: "%.5f", location.coordinate.latitude)) lng=\(String(format: "%.5f", location.coordinate.longitude)) course=\(Int(location.course)) speed=\(String(format: "%.1f", location.speed)) rawOffset=\(Int(location.distance(from: rawLocation))) rawAge=\(String(format: "%.1f", Date().timeIntervalSince(rawLocation.timestamp))) remaining=\(Int(progress.distanceRemaining)) toManeuver=\(Int(progress.currentLegProgress.currentStepProgress.distanceRemaining)) offRoute=\(_offRoute) rerouting=\(_rerouting) cam=\(String(describing: navigationViewController.navigationMapView?.navigationCamera.state)) camBearing=\(Int(navigationViewController.navigationMapView?.mapView.cameraState.bearing ?? -1))")
        _distanceRemaining = progress.distanceRemaining
        _durationRemaining = progress.durationRemaining
        sendEvent(eventType: MapBoxEventType.navigation_running)
        //_currentLegDescription =  progress.currentLeg.description
        if(_eventSink != nil && !_navigatorBehind)
        {
            let jsonEncoder = JSONEncoder()
            
            let progressEvent = MapBoxRouteProgressEvent(progress: progress)
            // A progress sample that fails to encode is skipped, never fatal.
            if let progressEventJsonData = try? jsonEncoder.encode(progressEvent),
               let progressEventJson = String(data: progressEventJsonData, encoding: String.Encoding.utf8)
            {
                _eventSink?(progressEventJson)
            }

            // Full-screen sessions stop reporting at the destination. An
            // embedded session keeps going: the host's own UI is still on
            // screen and still needs the state.
            if(!isEmbeddedNavigation && progress.isFinalLeg && progress.currentLegProgress.userHasArrivedAtWaypoint && !_showEndOfRouteFeedback)
            {
                _eventSink = nil
            }
        }
        _lastProgress = progress
        // While the navigator is still where the last trip ended, what it
        // says about this one is not true yet; the host keeps its
        // "finding the route" state for the second or two it takes.
        emitNavState()
        updateDoorView()
        placeOrnaments()
        if _routeLook != "normal" { applyRouteLook() }
        if let map = navigationViewController.navigationMapView { paceMap(map, at: location, phone: rawLocation) }
    }
    
    public func navigationViewController(_ navigationViewController: NavigationViewController, didArriveAt waypoint: Waypoint) -> Bool {
        sendEvent(eventType: MapBoxEventType.on_arrival, data: "true")
        if(!_wayPoints.isEmpty && IsMultipleUniqueRoutes)
        {
            continueNavigationWithWayPoints(wayPoints: [getLastKnownLocation(), _wayPoints.remove(at: 0)])
            return false
        }
        
        return true
    }
    
    
    
    public func navigationViewControllerDidDismiss(_ navigationViewController: NavigationViewController, byCanceling canceled: Bool) {
        if(canceled)
        {
            sendEvent(eventType: MapBoxEventType.navigation_cancelled)
        }
        endNavigation(result: nil)
    }
    
    public func navigationViewController(_ navigationViewController: NavigationViewController, shouldRerouteFrom location: CLLocation) -> Bool {
        guard mayReroute(from: location) else {
            navTrace("off route ignored: navigator \(_lastKnownLocation.map { String(Int($0.distance(from: location))) } ?? "?") m from the phone, behind=\(_navigatorBehind)")
            return false
        }
        if !_offRoute {
            _offRoute = true
            navTrace("off route at lat=\(String(format: "%.5f", location.coordinate.latitude)) lng=\(String(format: "%.5f", location.coordinate.longitude))")
            sendEvent(eventType: MapBoxEventType.user_off_route)
            emitNavState()
        }
        return _shouldReRoute
    }

    // MARK: A navigator that starts a trip somewhere else

    /// The navigator outlives a trip, and keeps the position the last one
    /// ended at. At the start of the next trip it still stands there for
    /// a second or two. Two things went wrong with that:
    ///
    /// - It called the driver off route and asked for a new route from
    ///   that old spot, with the jump to the real position read as the
    ///   way they were driving. The trip then did not start where the
    ///   driver was, or set off the wrong way round the block, and the
    ///   route stuck whenever it happened to pass them.
    /// - Where the new route ran through the old spot, the navigator
    ///   took the driver to be that far along it already, and stayed
    ///   there.
    ///
    /// So while the navigator is behind, no re-route is allowed; and the
    /// moment it has the phone's position, the trip's own route is set
    /// again, which makes the navigator place the driver on it afresh.
    func settleNavigator(_ navigationViewController: NavigationViewController, at navigator: CLLocation, phone: CLLocation) {
        let behind = navigator.distance(from: phone) > Self.navigatorSlack(for: phone)
        if _guidanceStartedAt == nil {
            let started = Date()
            _guidanceStartedAt = started
            _navigatorBehind = behind
            _routeNeedsSettingAgain = behind
            if behind {
                navTrace("trip starts with the navigator \(Int(navigator.distance(from: phone))) m from the phone")
                // It catches up within a few fixes. If the fixes never
                // come, the host is not left waiting for it for ever.
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.navigatorCatchUpLimit) { [weak self] in
                    guard let self = self, self._navigatorBehind, self._guidanceStartedAt == started else { return }
                    navTrace("navigator still behind after \(Int(Self.navigatorCatchUpLimit)) s: reporting it as it is")
                    self._navigatorBehind = false
                    self.emitNavState()
                }
            }
            return
        }
        guard !behind else { return }
        _navigatorBehind = false
        guard _routeNeedsSettingAgain else { return }
        _routeNeedsSettingAgain = false
        // A moment for the navigator to take the route in again before a
        // re-route may be asked for.
        _noRerouteUntil = Date().addingTimeInterval(3)
        let router = navigationViewController.navigationService.router
        let voice = navigationViewController.voiceController
        let dings = voice?.playRerouteSound ?? true
        // Setting the same route again is not a re-route to tell the
        // driver about.
        voice?.playRerouteSound = false
        navTrace("navigator has the phone's position: route set again")
        router.updateRoute(with: router.indexedRouteResponse, routeOptions: nil) { [weak voice] _ in
            voice?.playRerouteSound = dings
        }
    }

    /// Longest the host is kept waiting for the navigator to catch up.
    static let navigatorCatchUpLimit: TimeInterval = 6

    /// How far the navigator's position may sit from the phone's fix and
    /// still be the same place: the fix's error, and the second of travel
    /// the navigator looks ahead by.
    static func navigatorSlack(for fix: CLLocation) -> CLLocationDistance {
        return max(25, fix.horizontalAccuracy * 2) + 1.5 * max(0, fix.speed)
    }

    /// Longest a re-route is held back because the navigator and the
    /// phone disagree. Past this the driver needs a route more than the
    /// route needs to be from exactly the right spot.
    static let rerouteHoldLimit: TimeInterval = 10

    func mayReroute(from fix: CLLocation) -> Bool {
        guard let navigator = _lastKnownLocation else { return false }
        if let until = _noRerouteUntil, Date() < until { return false }
        let agree = !_navigatorBehind && navigator.distance(from: fix) <= Self.navigatorSlack(for: fix)
        if agree {
            _rerouteHeldSince = nil
            return true
        }
        let since = _rerouteHeldSince ?? Date()
        _rerouteHeldSince = since
        return Date().timeIntervalSince(since) >= Self.rerouteHoldLimit
    }

    public func navigationViewController(_ navigationViewController: NavigationViewController, willRerouteFrom location: CLLocation?) {
        _rerouting = true
        emitNavState()
    }

    public func navigationViewController(_ navigationViewController: NavigationViewController, didRerouteAlong route: Route) {
        _offRoute = false
        _rerouting = false
        sendEvent(eventType: MapBoxEventType.reroute_along)
        emitNavState()
    }

    public func navigationViewController(_ navigationViewController: NavigationViewController, didFailToRerouteWith error: Error) {
        _rerouting = false
        sendEvent(eventType: MapBoxEventType.failed_to_reroute, data: error.localizedDescription)
        emitNavState()
    }

    public func navigationViewController(_ navigationViewController: NavigationViewController, didUpdateAlternatives updatedAlternatives: [AlternativeRoute], removedAlternatives: [AlternativeRoute]) {
        if fasterAlternative() != nil {
            sendEvent(eventType: MapBoxEventType.faster_route_found)
        }
        emitNavState()
    }
    
    /// The stop is marked with the host's own numbered pin, so the SDK's
    /// stock destination marker is taken off again.
    public func navigationViewController(_ navigationViewController: NavigationViewController, didAdd finalDestinationAnnotation: PointAnnotation, pointAnnotationManager: PointAnnotationManager) {
        pointAnnotationManager.annotations = []
    }

    public func navigationViewController(_ navigationViewController: NavigationViewController, didSubmitArrivalFeedback feedback: EndOfRouteFeedback) {
        
        if(_eventSink != nil)
        {
            let jsonEncoder = JSONEncoder()
            
            let localFeedback = Feedback(rating: feedback.rating, comment: feedback.comment)
            let feedbackJson = (try? jsonEncoder.encode(localFeedback)).flatMap { String(data: $0, encoding: String.Encoding.utf8) }
            
            sendEvent(eventType: MapBoxEventType.navigation_finished, data: feedbackJson ?? "")
            
            _eventSink = nil
            
        }
    }
}
