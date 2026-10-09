package com.eopeter.fluttermapboxnavigation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Bundle
import com.mapbox.android.core.location.LocationEngineProvider
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.CustomInfoPanelEndNavButtonBinder
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.google.gson.Gson
import com.mapbox.maps.EdgeInsets
import com.mapbox.navigation.core.reroute.RerouteController
import com.mapbox.navigation.core.reroute.RerouteState
import com.mapbox.navigation.dropin.navigationview.NavigationViewListener
import com.mapbox.navigation.ui.app.internal.SharedApp
import com.mapbox.navigation.ui.app.internal.audioguidance.AudioAction
import com.mapbox.navigation.ui.app.internal.camera.CameraAction
import com.mapbox.navigation.ui.app.internal.camera.TargetCameraMode
import org.json.JSONArray
import org.json.JSONObject
import com.mapbox.maps.Style
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.*
import io.flutter.plugin.common.EventChannel
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*
import kotlin.math.roundToInt

/** How much faster an alternative has to be before it is offered. */
private const val DEFAULT_FASTER_ROUTE_MIN_SAVING_S = 120.0

/** Map kept clear between the host's sheet and the centre of the puck. */
private const val PUCK_CLEARANCE_DP = 72f

/** Room for the host's floating buttons under its banner. */
private const val CAMERA_TOP_MARGIN_DP = 64f

/** Keeps the route and its pins off the screen edges in the overview. */
private const val CAMERA_SIDE_MARGIN_DP = 32f

open class TurnByTurn(
    ctx: Context,
    act: Activity,
    bind: NavigationActivityBinding,
    accessToken: String
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    Application.ActivityLifecycleCallbacks {

    open fun initFlutterChannelHandlers() {
        this.methodChannel?.setMethodCallHandler(this)
        this.eventChannel?.setStreamHandler(this)
    }

    open fun initNavigation() {
        val engine = SteadyLocationEngine(LocationEngineProvider.getBestLocationEngine(this.context))
        this.locationEngine = engine
        val navigationOptions = NavigationOptions.Builder(this.context)
            .accessToken(this.token)
            // Fixes that wander while the driver stands still are held
            // in place before the navigator sees them.
            .locationEngine(engine)
            .build()

        MapboxNavigationApp
            .setup(navigationOptions)
            .attach(this.activity as LifecycleOwner)

        // initialize navigation trip observers
        this.registerObservers()
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "enableOfflineRouting" -> {
                // downloadRegionForOfflineRouting(call, result)
            }
            "buildRoute" -> {
                this.buildRoute(methodCall, result)
            }
            "clearRoute" -> {
                this.clearRoute(methodCall, result)
            }
            "startFreeDrive" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = true
                this.startFreeDrive()
                result.success(true)
            }
            "startNavigation" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = false
                this.startNavigation(methodCall, result)
            }
            "finishNavigation" -> {
                this.finishNavigation(methodCall, result)
            }
            "getDistanceRemaining" -> {
                result.success(this.distanceRemaining)
            }
            "getDurationRemaining" -> {
                result.success(this.durationRemaining)
            }
            "setMuted" -> {
                val muted = methodCall.argument<Boolean>("muted") ?: false
                SharedApp.store.dispatch(if (muted) AudioAction.Mute else AudioAction.Unmute)
                result.success(true)
            }
            "showOverview" -> {
                SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Overview))
                // Said here and now, not when Drop-In gets round to
                // reporting it: the door view moves the camera on the
                // next fix unless it already knows the overview is up,
                // and any move of its own ends the overview.
                this.sendCameraState("overview")
                result.success(true)
            }
            "recenter", "reCenter" -> {
                if (this.mapLook.isDoorView) {
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Idle))
                    this.sendCameraState("following")
                    this.lastLocation?.let { this.mapLook.onLocation(it) }
                } else {
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Following))
                }
                result.success(true)
            }
            "setCameraPadding" -> {
                val density = this.context.resources.displayMetrics.density.toDouble()
                this.hostPadding = EdgeInsets(
                    (methodCall.argument<Double>("top") ?: 0.0) * density,
                    (methodCall.argument<Double>("left") ?: 0.0) * density,
                    (methodCall.argument<Double>("bottom") ?: 0.0) * density,
                    (methodCall.argument<Double>("right") ?: 0.0) * density,
                )
                this.applyHostPadding()
                // The door view frames the map by hand. Left to the next
                // fix, the marker sat behind the host's sheet whenever
                // the sheet came up.
                if (this.mapLook.isDoorView) this.lastLocation?.let { this.mapLook.onLocation(it) }
                result.success(true)
            }
            "setStopPins" -> {
                val pins = methodCall.argument<List<Map<*, *>>>("pins") ?: emptyList()
                this.mapLook.setStopPins(pins)
                result.success(true)
            }
            "setDoorView" -> {
                val enabled = methodCall.argument<Boolean>("enabled") ?: false
                val wasOn = this.mapLook.isDoorView
                this.mapLook.setDoorView(
                    enabled,
                    methodCall.argument<String>("side"),
                    methodCall.argument<Double>("latitude"),
                    methodCall.argument<Double>("longitude"),
                    methodCall.argument<String>("label"),
                )
                // The overview the driver asked for outlasts the door view
                // coming and going. It is theirs to leave, by re-centring;
                // only then does the door view, if it is on, take the camera.
                val overviewUp = this.cameraState == "overview"
                if (enabled && !wasOn && !overviewUp) {
                    // The door view drives the camera by hand.
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Idle))
                    this.lastLocation?.let { this.mapLook.onLocation(it) }
                } else if (!enabled && wasOn && !overviewUp) {
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Following))
                }
                result.success(true)
            }
            "setFasterRouteMinimumSaving" -> {
                this.fasterRouteMinSavingS =
                    methodCall.argument<Double>("seconds") ?: DEFAULT_FASTER_ROUTE_MIN_SAVING_S
                this.emitNavState()
                result.success(true)
            }
            "setRouteLook" -> {
                val look = methodCall.argument<String>("look") ?: "normal"
                this.mapLook.setRouteLook(look, if (look == "left") this.routeStillAhead() else null)
                result.success(true)
            }
            "setNightMode" -> {
                this.nightMode = methodCall.argument<Boolean>("night") ?: false
                this.mapLook.night = this.nightMode
                this.applyMapStyleUris()
                result.success(true)
            }
            "acceptFasterRoute" -> {
                result.success(this.acceptFasterRoute())
            }
            "declineFasterRoute" -> {
                this.fasterAlternative()?.let { this.declinedAlternativeIds.add(it.id) }
                this.emitNavState()
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun buildRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.isNavigationCanceled = false
        this.offRoute = false
        this.rerouting = false
        this.arrived = false
        this.lastProgress = null
        this.declinedAlternativeIds.clear()
        this.mapLook.leaveDoorView()

        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) this.setOptions(arguments)
        this.addedWaypoints.clear()
        val points = arguments?.get("wayPoints") as? Map<*, *>
        if (points == null || MapboxNavigationApp.current() == null) {
            // Not ready (Drop-In not initialised yet) or no waypoints: reply
            // false instead of crashing on a null navigation instance.
            PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
            result.success(false)
            return
        }
        for (item in points) {
            val point = item.value as? Map<*, *> ?: continue
            val latitude = point["Latitude"] as? Double ?: continue
            val longitude = point["Longitude"] as? Double ?: continue
            val isSilent = point["IsSilent"] as? Boolean ?: false
            this.addedWaypoints.add(Waypoint(Point.fromLngLat(longitude, latitude),isSilent))
        }
        this.getRoute(this.context)
        result.success(true)
    }

    private fun getRoute(context: Context) {
        val navigation = MapboxNavigationApp.current()
        if (navigation == null) {
            PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
            return
        }
        navigation.requestRoutes(
            routeOptions = RouteOptions
                .builder()
                .applyDefaultNavigationOptions(navigationMode)
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(this.addedWaypoints.coordinatesList())
                .waypointIndicesList(this.addedWaypoints.waypointsIndices())
                .waypointNamesList(this.addedWaypoints.waypointsNames())
                .language(navigationLanguage)
                .alternatives(alternatives)
                .steps(true)
                .voiceUnits(navigationVoiceUnits)
                .bannerInstructions(bannerInstructionsEnabled)
                .voiceInstructions(voiceInstructionsEnabled)
                .exclude(DirectionsCriteria.EXCLUDE_TOLL)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: RouterOrigin
                ) {
                    this@TurnByTurn.currentRoutes = routes
                    PluginUtilities.sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
                    this@TurnByTurn.binding.navigationView.api.routeReplayEnabled(
                        this@TurnByTurn.simulateRoute
                    )
                    this@TurnByTurn.binding.navigationView.api.startRoutePreview(routes)
                    this@TurnByTurn.binding.navigationView.customizeViewBinders {
                        this.infoPanelEndNavigationButtonBinder =
                            CustomInfoPanelEndNavButtonBinder(activity)
                    }
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                }

                override fun onCanceled(
                    routeOptions: RouteOptions,
                    routerOrigin: RouterOrigin
                ) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }
            }
        )
    }

    private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.currentRoutes = null
        val navigation = MapboxNavigationApp.current()
        navigation?.stopTripSession()
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        result.success(true)
    }

    private fun startFreeDrive() {
        this.binding.navigationView.api.startFreeDrive()
    }

    private fun startNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) {
            this.setOptions(arguments)
        }

        this.startNavigation()

        if (this.currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun finishNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        this.finishNavigation()

        if (this.currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        if (this.currentRoutes == null) {
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
            return
        }
        // Whatever route the shared navigation object still holds from a
        // trip before this one is not this trip's to report on.
        val mine = this.currentRoutes!!.map { it.id }.toSet()
        this.leftoverRouteIds = this.activeRoutes.map { it.id }.toSet() - mine
        this.lastProgress = null
        this.guidanceStarted = true
        // The host fades the route while it re-targets and takes it to be
        // back to normal once guidance has started. The SDK draws the new
        // route into the layers it already has, faded ones included.
        this.mapLook.setRouteLook("normal", null)
        // And the last trip's stop is not this one's.
        this.mapLook.setDestinationPin(null, null)
        this.binding.navigationView.api.startActiveGuidance(this.currentRoutes!!)
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

    private fun finishNavigation(isOffRouted: Boolean = false) {
        // Null-safe: finishing before Drop-In initialised used to NPE.
        MapboxNavigationApp.current()?.stopTripSession()
        // The navigation object is shared and outlives this view. Left
        // with this trip's route, it handed the next trip's view this
        // trip's progress until that one's own route was in.
        MapboxNavigationApp.current()?.setNavigationRoutes(emptyList())
        // No route, no progress to say the tunnel has ended.
        this.locationEngine?.inTunnel = false
        this.guidanceStarted = false
        this.isNavigationCanceled = true
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    private fun setOptions(arguments: Map<*, *>) {
        val navMode = arguments["mode"] as? String
        if (navMode != null) {
            when (navMode) {
                "walking" -> this.navigationMode = DirectionsCriteria.PROFILE_WALKING
                "cycling" -> this.navigationMode = DirectionsCriteria.PROFILE_CYCLING
                "driving" -> this.navigationMode = DirectionsCriteria.PROFILE_DRIVING
            }
        }

        val simulated = arguments["simulateRoute"] as? Boolean
        if (simulated != null) {
            this.simulateRoute = simulated
        }

        val language = arguments["language"] as? String
        if (language != null) {
            this.navigationLanguage = language
        }

        val units = arguments["units"] as? String

        if (units != null) {
            if (units == "imperial") {
                this.navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            } else if (units == "metric") {
                this.navigationVoiceUnits = DirectionsCriteria.METRIC
            }
        }

        this.mapStyleUrlDay = arguments["mapStyleUrlDay"] as? String
        this.mapStyleUrlNight = arguments["mapStyleUrlNight"] as? String

        //Set the style Uri
        if (this.mapStyleUrlDay == null) this.mapStyleUrlDay = Style.MAPBOX_STREETS
        if (this.mapStyleUrlNight == null) this.mapStyleUrlNight = Style.DARK

        this.applyMapStyleUris()

        this.initialLatitude = arguments["initialLatitude"] as? Double
        this.initialLongitude = arguments["initialLongitude"] as? Double

        val zm = arguments["zoom"] as? Double
        if (zm != null) {
            this.zoom = zm
        }

        val br = arguments["bearing"] as? Double
        if (br != null) {
            this.bearing = br
        }

        val tt = arguments["tilt"] as? Double
        if (tt != null) {
            this.tilt = tt
        }

        val optim = arguments["isOptimized"] as? Boolean
        if (optim != null) {
            this.isOptimized = optim
        }

        val anim = arguments["animateBuildRoute"] as? Boolean
        if (anim != null) {
            this.animateBuildRoute = anim
        }

        val altRoute = arguments["alternatives"] as? Boolean
        if (altRoute != null) {
            this.alternatives = altRoute
        }

        val voiceEnabled = arguments["voiceInstructionsEnabled"] as? Boolean
        if (voiceEnabled != null) {
            this.voiceInstructionsEnabled = voiceEnabled
        }

        val bannerEnabled = arguments["bannerInstructionsEnabled"] as? Boolean
        if (bannerEnabled != null) {
            this.bannerInstructionsEnabled = bannerEnabled
        }

        val longPress = arguments["longPressDestinationEnabled"] as? Boolean
        if (longPress != null) {
            this.longPressDestinationEnabled = longPress
        }

        val onMapTap = arguments["enableOnMapTapCallback"] as? Boolean
        if (onMapTap != null) {
            this.enableOnMapTapCallback = onMapTap
        }
    }

    open fun registerObservers() {
        // register event listeners
        MapboxNavigationApp.current()?.registerBannerInstructionsObserver(this.bannerInstructionObserver)
        MapboxNavigationApp.current()?.registerVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.registerOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.registerRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.registerLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(this.arrivalObserver)
        MapboxNavigationApp.current()?.getRerouteController()
            ?.registerRerouteStateObserver(this.rerouteStateObserver)
        this.binding.navigationView.addListener(this.cameraListener)
    }

    open fun unregisterObservers() {
        // unregister event listeners to prevent leaks or unnecessary resource consumption
        MapboxNavigationApp.current()?.unregisterBannerInstructionsObserver(this.bannerInstructionObserver)
        MapboxNavigationApp.current()?.unregisterVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.unregisterOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.unregisterRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.unregisterLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.unregisterRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.unregisterArrivalObserver(this.arrivalObserver)
        MapboxNavigationApp.current()?.getRerouteController()
            ?.unregisterRerouteStateObserver(this.rerouteStateObserver)
        this.binding.navigationView.removeListener(this.cameraListener)
    }

    // Flutter stream listener delegate methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        FlutterMapboxNavigationPlugin.eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        FlutterMapboxNavigationPlugin.eventSink = null
    }

    private val context: Context = ctx
    val activity: Activity = act
    private val token: String = accessToken
    open var methodChannel: MethodChannel? = null
    open var eventChannel: EventChannel? = null
    private var lastLocation: Location? = null

    /// The location engine the navigator was set up with, kept so it can
    /// be told when the route has the driver in a tunnel.
    private var locationEngine: SteadyLocationEngine? = null

    /**
     * Helper class that keeps added waypoints and transforms them to the [RouteOptions] params.
     */
    private val addedWaypoints = WaypointSet()

    // Config
    private var initialLatitude: Double? = null
    private var initialLongitude: Double? = null

    // val wayPoints: MutableList<Point> = mutableListOf()
    private var navigationMode = DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    var simulateRoute = false
    private var mapStyleUrlDay: String? = null
    private var mapStyleUrlNight: String? = null
    private var navigationLanguage = "en"
    private var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
    private var zoom = 15.0
    private var bearing = 0.0
    private var tilt = 0.0
    private var distanceRemaining: Float? = null
    private var durationRemaining: Double? = null

    private var alternatives = true

    var allowsUTurnAtWayPoints = false
    var enableRefresh = false
    private var voiceInstructionsEnabled = true
    private var bannerInstructionsEnabled = true
    private var longPressDestinationEnabled = true
    private var enableOnMapTapCallback = false
    private var animateBuildRoute = true
    private var isOptimized = false

    private var currentRoutes: List<NavigationRoute>? = null
    private var isNavigationCanceled = false

    /// Set once this view has started guidance on a route of its own.
    private var guidanceStarted = false
    private var leftoverRouteIds: Set<String> = emptySet()

    // State behind the nav_state event.
    private var lastProgress: RouteProgress? = null
    private var activeRoutes: List<NavigationRoute> = emptyList()
    private var offRoute = false

    /// When the phone last reported a position, on the uptime clock.
    private var lastRawFixAtMs: Long? = null
    private var rerouting = false
    private var arrived = false
    private var speedLimitKmph: Int? = null
    private var cameraState: String? = null
    private var hostPadding: EdgeInsets? = null
    private val declinedAlternativeIds = mutableSetOf<String>()
    private var fasterRouteSaving: Double? = null

    /// How much faster an alternative has to be before it is offered.
    /// The host may change it (`setFasterRouteMinimumSaving`).
    private var fasterRouteMinSavingS = DEFAULT_FASTER_ROUTE_MIN_SAVING_S
    private var fasterRouteOnMap: String? = null
    private var nightMode = false

    /** Route line, puck, stop pins and the door view. */
    val mapLook: MapLook by lazy { MapLook(ctx, bind.navigationView) }

    /**
     * Bindings to the example layout.
     */
    open val binding: NavigationActivityBinding = bind

    /**
     * Gets notified with location updates.
     *
     * Exposes raw updates coming directly from the location services
     * and the updates enhanced by the Navigation SDK (cleaned up and matched to the road).
     */
    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            this@TurnByTurn.lastLocation = locationMatcherResult.enhancedLocation
            this@TurnByTurn.speedLimitKmph = locationMatcherResult.speedLimit?.speedKmph
            this@TurnByTurn.mapLook.onLocation(locationMatcherResult.enhancedLocation)
        }

        override fun onNewRawLocation(rawLocation: Location) {
            // A fix the location engine made up to hold a lost driver in
            // place is not the phone speaking.
            if (rawLocation.extras?.getBoolean(SteadyLocationEngine.MADE_UP) == true) return
            this@TurnByTurn.lastRawFixAtMs = SystemClock.elapsedRealtime()
        }
    }

    private val bannerInstructionObserver = BannerInstructionsObserver { bannerInstructions ->
        PluginUtilities.sendEvent(MapBoxEvents.BANNER_INSTRUCTION, bannerInstructions.primary().text())
    }

    private val voiceInstructionObserver = VoiceInstructionsObserver { voiceInstructions ->
        PluginUtilities.sendEvent(MapBoxEvents.SPEECH_ANNOUNCEMENT, voiceInstructions.announcement().toString())
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        this.offRoute = offRoute
        if (offRoute) {
            PluginUtilities.sendEvent(MapBoxEvents.USER_OFF_ROUTE)
        }
        this.emitNavState()
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        this.activeRoutes = routeUpdateResult.navigationRoutes
        this.mapLook.setTrafficLights(this.trafficLightsOn(routeUpdateResult.navigationRoutes.firstOrNull()))
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            PluginUtilities.sendEvent(MapBoxEvents.REROUTE_ALONG);
        }
        if (this.fasterAlternative() != null) {
            PluginUtilities.sendEvent(MapBoxEvents.FASTER_ROUTE_FOUND)
        }
        this.emitNavState()
    }

    private val rerouteStateObserver = RerouteController.RerouteStateObserver { state ->
        this.rerouting = state is RerouteState.FetchingRoute
        if (state is RerouteState.Failed) {
            PluginUtilities.sendEvent(MapBoxEvents.FAILED_TO_REROUTE, state.message)
        }
        this.emitNavState()
    }

    /**
     * Reports the Drop-In camera mode to the host, and puts the host's
     * padding back whenever Drop-In recomputes its own.
     */
    private val cameraListener = object : NavigationViewListener() {
        override fun onFollowingCameraMode() = sendCameraState("following")
        override fun onOverviewCameraMode() = sendCameraState("overview")
        override fun onIdleCameraMode() = sendCameraState("free")
        override fun onCameraPaddingChanged(padding: EdgeInsets) {
            val wanted = this@TurnByTurn.hostPadding ?: return
            if (padding != cameraPadding(wanted)) this@TurnByTurn.applyHostPadding()
        }
    }

    private fun sendCameraState(state: String) {
        // The door view parks Drop-In's camera on purpose; to the host
        // the map is still following the driver.
        val reported = if (state == "free" && this.mapLook.isDoorView) "following" else state
        if (reported == this.cameraState) return
        this.cameraState = reported
        PluginUtilities.sendEvent(MapBoxEvents.CAMERA_STATE, reported)
        this.mapLook.onCameraState(reported)
    }

    private fun applyHostPadding() {
        val padding = this.hostPadding ?: return
        this.mapLook.hostPadding = padding
        SharedApp.store.dispatch(CameraAction.UpdatePadding(cameraPadding(padding)))
    }

    /**
     * Drop-In's following camera centres the puck on the bottom edge of
     * its padding, which left half of it behind the host's sheet, and its
     * overview ran the route to the very edges of the screen. The camera
     * gets room on every side on top of what the host covers.
     */
    private fun cameraPadding(host: EdgeInsets): EdgeInsets {
        val density = this.context.resources.displayMetrics.density
        return EdgeInsets(
            host.top + CAMERA_TOP_MARGIN_DP * density,
            host.left + CAMERA_SIDE_MARGIN_DP * density,
            host.bottom + PUCK_CLEARANCE_DP * density,
            host.right + CAMERA_SIDE_MARGIN_DP * density,
        )
    }

    /**
     * Drop-In picks its day or night URI from the phone's dark mode. The
     * host decides instead (`setNightMode`), so both slots carry the
     * style it asked for.
     */
    private fun applyMapStyleUris() {
        val uri = if (this.nightMode) this.mapStyleUrlNight else this.mapStyleUrlDay
        if (uri == null) return
        this.binding.navigationView.customizeViewOptions {
            mapStyleUriDay = uri
            mapStyleUriNight = uri
        }
    }

    /** Draws the faster route on offer, or takes it off when the offer has gone. */
    private fun showFasterRouteOnMap(faster: NavigationRoute?) {
        if (faster?.id == this.fasterRouteOnMap) return
        this.fasterRouteOnMap = faster?.id
        val navigation = MapboxNavigationApp.current()
        val metadata = faster?.let { navigation?.getAlternativeMetadataFor(it) }
        val geometry = faster?.directionsRoute?.geometry()
        if (faster == null || metadata == null || geometry == null) {
            this.mapLook.setFasterRoute(null, 0, null)
            return
        }
        // Only where it leaves the route in use: the shared stretch stays
        // the colour of the route the driver is on.
        val whole = LineString.fromPolyline(geometry, 6)
        val fork = metadata.forkIntersectionOfAlternativeRoute.location
        val branch = TurfMisc.lineSlice(fork, whole.coordinates().last(), whole)
        val yours = this.activeRoutes.firstOrNull()?.directionsRoute?.geometry()?.let {
            val mine = LineString.fromPolyline(it, 6)
            val rest = TurfMisc.lineSlice(
                metadata.forkIntersectionOfPrimaryRoute.location, mine.coordinates().last(), mine)
            TurfMeasurement.along(
                rest, TurfMeasurement.length(rest, TurfConstants.UNIT_METERS) * 0.4, TurfConstants.UNIT_METERS)
        }
        val minutes = ((this.fasterRouteSaving ?: 0.0) / 60.0).roundToInt().coerceAtLeast(1)
        this.mapLook.setFasterRoute(branch, minutes, yours)
    }

    /**
     * The end of the route, marked on the last stretch and in the door
     * view with a pin that points at the stop: from the two points when
     * they are far enough apart to tell, otherwise from the side the
     * directions give for the arrival.
     */
    private fun markDestination(progress: RouteProgress) {
        val leg = progress.currentLegProgress
        val last = leg?.routeLeg?.steps()?.lastOrNull()?.maneuver()
        val arriving = leg?.upcomingStep?.maneuver()?.type() == "arrive" || this.mapLook.isDoorView
        if (last == null || !arriving) {
            this.mapLook.setDestinationPin(null, null)
            return
        }
        val road = last.location()
        val stop = this.mapLook.doorTarget
            ?: progress.navigationRoute.routeOptions.coordinatesList().lastOrNull()
        var bearing: Double? = null
        if (stop != null && TurfMeasurement.distance(road, stop, TurfConstants.UNIT_METERS) >= 4.0) {
            bearing = (TurfMeasurement.bearing(road, stop) + 360.0) % 360.0
        } else {
            val heading = last.bearingBefore()
            val side = last.modifier().orEmpty()
            if (heading != null && side.contains("left")) bearing = (heading + 270.0) % 360.0
            if (heading != null && side.contains("right")) bearing = (heading + 90.0) % 360.0
        }
        this.mapLook.setDestinationPin(road, bearing)
    }

    /** The street the next turn goes onto, tagged at the turn on the map. */
    private fun tagNextTurn(progress: RouteProgress) {
        val step = progress.currentLegProgress?.upcomingStep
        val maneuver = step?.maneuver()
        val street = progress.bannerInstructions?.primary()?.text()
        // Arriving is not a turn: the door view marks the stop itself.
        if (maneuver == null || maneuver.type() == "arrive") {
            this.mapLook.setTurnTag(null, null)
        } else {
            this.mapLook.setTurnTag(maneuver.location(), street ?: step.name())
        }
    }

    /** Where the route in use passes a traffic light. */
    private fun trafficLightsOn(route: NavigationRoute?): List<Point> {
        val lights = ArrayList<Point>()
        route?.directionsRoute?.legs()?.forEach { leg ->
            leg.steps()?.forEach { step ->
                step.intersections()?.forEach { if (it.trafficSignal() == true) lights.add(it.location()) }
            }
        }
        return lights
    }

    /** The part of the route in use that the driver has not driven yet. */
    private fun routeStillAhead(): LineString? {
        val geometry = this.activeRoutes.firstOrNull()?.directionsRoute?.geometry() ?: return null
        val line = LineString.fromPolyline(geometry, 6)
        val travelled = (this.lastProgress?.distanceTraveled ?: 0f).toDouble()
        val length = TurfMeasurement.length(line, TurfConstants.UNIT_METERS)
        if (travelled <= 0.0 || travelled >= length) return line
        return TurfMisc.lineSliceAlong(line, travelled, length, TurfConstants.UNIT_METERS)
    }

    /** The alternative worth offering right now, if any. */
    private fun fasterAlternative(): NavigationRoute? {
        val navigation = MapboxNavigationApp.current() ?: return null
        val routes = this.activeRoutes
        if (routes.size < 2) return null
        val primaryDuration = routes.first().directionsRoute.duration()
        var best: NavigationRoute? = null
        var bestSaving = this.fasterRouteMinSavingS
        for (route in routes.drop(1)) {
            if (this.declinedAlternativeIds.contains(route.id)) continue
            val metadata = navigation.getAlternativeMetadataFor(route) ?: continue
            val saving = primaryDuration - metadata.infoFromStartOfPrimary.duration
            if (saving >= bestSaving) {
                best = route
                bestSaving = saving
            }
        }
        this.fasterRouteSaving = if (best == null) null else bestSaving
        return best
    }

    private fun acceptFasterRoute(): Boolean {
        val navigation = MapboxNavigationApp.current() ?: return false
        val alternative = this.fasterAlternative() ?: return false
        navigation.setNavigationRoutes(
            listOf(alternative) + this.activeRoutes.filter { it.id != alternative.id }
        )
        return true
    }

    /**
     * Sends the latest guidance state to the host: everything a custom
     * turn-by-turn UI needs, in one event.
     */
    private fun emitNavState() {
        if (this.isNavigationCanceled) return
        val progress = this.lastProgress ?: return
        val faster: NavigationRoute? = try {
            val state = JSONObject()
            state.put("distanceRemaining", progress.distanceRemaining.toDouble())
            state.put("durationRemaining", progress.durationRemaining)
            state.put(
                "etaEpochMs",
                System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()
            )
            state.put("offRoute", this.offRoute)
            state.put("rerouting", this.rerouting)
            // The navigator keeps ticking when fixes stop, so the host
            // needs to be told how old the last real one is.
            this.lastRawFixAtMs?.let {
                state.put("fixAgeSeconds", (SystemClock.elapsedRealtime() - it) / 1000.0)
            }
            state.put("arrived", this.arrived)

            progress.currentLegProgress?.currentStepProgress?.let {
                state.put("distanceToManeuver", it.distanceRemaining.toDouble())
            }

            val banner = progress.bannerInstructions
            if (banner != null) {
                val maneuver = JSONObject()
                banner.primary().type()?.let { maneuver.put("type", it) }
                banner.primary().modifier()?.let { maneuver.put("modifier", it) }
                maneuver.put("text", banner.primary().text())
                banner.secondary()?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { maneuver.put("secondaryText", it) }
                state.put("maneuver", maneuver)

                val sub = banner.sub()
                if (sub != null) {
                    val lanes = JSONArray()
                    for (component in sub.components() ?: emptyList()) {
                        if (component.type() != "lane") continue
                        val lane = JSONObject()
                        lane.put("indications", JSONArray(component.directions() ?: emptyList<String>()))
                        lane.put("valid", component.active() ?: false)
                        component.activeDirection()?.let { lane.put("active", it) }
                        lanes.put(lane)
                    }
                    if (lanes.length() > 0) {
                        state.put("lanes", lanes)
                    } else {
                        val then = JSONObject()
                        sub.type()?.let { then.put("type", it) }
                        sub.modifier()?.let { then.put("modifier", it) }
                        then.put("text", sub.text())
                        state.put("then", then)
                    }
                }
            }

            this.speedLimitKmph?.let { kmph ->
                val metric = this.navigationVoiceUnits != DirectionsCriteria.IMPERIAL
                state.put(
                    "speedLimit",
                    if (metric) kmph else Math.round(kmph / 1.609344 / 5.0).toInt() * 5
                )
                state.put("speedLimitUnit", if (metric) "km/h" else "mph")
            }

            this.lastLocation?.let {
                state.put("latitude", it.latitude)
                state.put("longitude", it.longitude)
                if (it.hasBearing()) state.put("bearing", it.bearing.toDouble())
                if (it.hasSpeed()) state.put("speed", it.speed.toDouble())
            }

            val offered = if (!this.offRoute && !this.rerouting) this.fasterAlternative() else null
            if (offered != null) {
                this.fasterRouteSaving?.let {
                    state.put("fasterRoute", JSONObject().put("savingSeconds", it))
                }
            }

            PluginUtilities.sendEvent(MapBoxEvents.NAV_STATE, state.toString())
            offered
        } catch (_: java.lang.Exception) {
            // A state that fails to encode is skipped, never fatal.
            return
        }
        // What the map shows for this state is drawn once the state has
        // gone out. Drawn first, a route that would not slice took the
        // whole tick's state down with it.
        try {
            this.showFasterRouteOnMap(faster)
        } catch (e: java.lang.Exception) {
            this.logDrawingFailureOnce("the faster route", e)
        }
        try {
            this.tagNextTurn(progress)
        } catch (e: java.lang.Exception) {
            this.logDrawingFailureOnce("the next turn's tag", e)
        }
        try {
            this.markDestination(progress)
        } catch (e: java.lang.Exception) {
            this.logDrawingFailureOnce("the pin at the stop", e)
        }
    }

    /// What has already failed to draw: a fault that comes back on every
    /// tick is logged the first time only.
    private val drawingFailuresLogged = mutableSetOf<String>()

    private fun logDrawingFailureOnce(what: String, error: java.lang.Exception) {
        if (this.drawingFailuresLogged.add(what)) Log.w("TurnByTurn", "$what was not drawn: $error")
    }

    /**
     * Gets notified with progress along the currently active route.
     */
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        // Whichever route the navigator is carrying the driver along,
        // the location engine has to know when it runs through a tunnel.
        this.locationEngine?.inTunnel = routeProgress.inTunnel
        // update flutter events
        if (!this.isNavigationCanceled && this.guidanceStarted &&
            routeProgress.navigationRoute.id !in this.leftoverRouteIds) {
            try {

                this.distanceRemaining = routeProgress.distanceRemaining
                this.durationRemaining = routeProgress.durationRemaining

                val progressEvent = MapBoxRouteProgressEvent(routeProgress)
                PluginUtilities.sendEvent(progressEvent)
                this.lastProgress = routeProgress
                this.emitNavState()
            } catch (_: java.lang.Exception) {
                // handle this error
            }
        }
    }

    private val arrivalObserver: ArrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            this@TurnByTurn.arrived = true
            PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
            this@TurnByTurn.emitNavState()
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
            // not impl
        }

        override fun onWaypointArrival(routeProgress: RouteProgress) {
            // not impl
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d("Embedded", "onActivityCreated not implemented")
    }

    override fun onActivityStarted(activity: Activity) {
        Log.d("Embedded", "onActivityStarted not implemented")
    }

    override fun onActivityResumed(activity: Activity) {
        Log.d("Embedded", "onActivityResumed not implemented")
    }

    override fun onActivityPaused(activity: Activity) {
        Log.d("Embedded", "onActivityPaused not implemented")
    }

    override fun onActivityStopped(activity: Activity) {
        Log.d("Embedded", "onActivityStopped not implemented")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d("Embedded", "onActivitySaveInstanceState not implemented")
    }

    override fun onActivityDestroyed(activity: Activity) {
        Log.d("Embedded", "onActivityDestroyed not implemented")
    }
}
