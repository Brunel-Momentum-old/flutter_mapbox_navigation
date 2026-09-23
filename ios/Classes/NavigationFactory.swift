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
    var _voiceUnits = "imperial"
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
    func endNavigation(result: FlutterResult?)
    {
        sendEvent(eventType: MapBoxEventType.navigation_finished)
        guard let navigationViewController = self._navigationViewController else {
            result?(true)
            return
        }
        navigationViewController.navigationService.endNavigation(feedback: nil)
        if(isEmbeddedNavigation)
        {
            navigationViewController.willMove(toParent: nil)
            navigationViewController.view.removeFromSuperview()
            navigationViewController.removeFromParent()
            self._navigationViewController = nil
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
        _distanceRemaining = progress.distanceRemaining
        _durationRemaining = progress.durationRemaining
        sendEvent(eventType: MapBoxEventType.navigation_running)
        //_currentLegDescription =  progress.currentLeg.description
        if(_eventSink != nil)
        {
            let jsonEncoder = JSONEncoder()
            
            let progressEvent = MapBoxRouteProgressEvent(progress: progress)
            // A progress sample that fails to encode is skipped, never fatal.
            if let progressEventJsonData = try? jsonEncoder.encode(progressEvent),
               let progressEventJson = String(data: progressEventJsonData, encoding: String.Encoding.utf8)
            {
                _eventSink?(progressEventJson)
            }

            if(progress.isFinalLeg && progress.currentLegProgress.userHasArrivedAtWaypoint && !_showEndOfRouteFeedback)
            {
                _eventSink = nil
            }
        }
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
        return _shouldReRoute
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
