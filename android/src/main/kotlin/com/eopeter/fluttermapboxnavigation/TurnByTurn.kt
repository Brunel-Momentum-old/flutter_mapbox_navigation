package com.eopeter.fluttermapboxnavigation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Bundle
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
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*

/** How much faster an alternative has to be before it is offered. */
private const val FASTER_ROUTE_MIN_SAVING_S = 120.0

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
        val navigationOptions = NavigationOptions.Builder(this.context)
            .accessToken(this.token)
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
                if (enabled && !wasOn) {
                    // The door view drives the camera by hand.
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Idle))
                    this.lastLocation?.let { this.mapLook.onLocation(it) }
                } else if (!enabled && wasOn) {
                    SharedApp.store.dispatch(CameraAction.SetCameraMode(TargetCameraMode.Following))
                }
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
        this.binding.navigationView.api.startActiveGuidance(this.currentRoutes!!)
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

    private fun finishNavigation(isOffRouted: Boolean = false) {
        // Null-safe: finishing before Drop-In initialised used to NPE.
        MapboxNavigationApp.current()?.stopTripSession()
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

    // State behind the nav_state event.
    private var lastProgress: RouteProgress? = null
    private var activeRoutes: List<NavigationRoute> = emptyList()
    private var offRoute = false
    private var rerouting = false
    private var arrived = false
    private var speedLimitKmph: Int? = null
    private var cameraState: String? = null
    private var hostPadding: EdgeInsets? = null
    private val declinedAlternativeIds = mutableSetOf<String>()
    private var fasterRouteSaving: Double? = null
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
            // no impl
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

    /** The alternative worth offering right now, if any. */
    private fun fasterAlternative(): NavigationRoute? {
        val navigation = MapboxNavigationApp.current() ?: return null
        val routes = this.activeRoutes
        if (routes.size < 2) return null
        val primaryDuration = routes.first().directionsRoute.duration()
        var best: NavigationRoute? = null
        var bestSaving = FASTER_ROUTE_MIN_SAVING_S
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
        try {
            val state = JSONObject()
            state.put("distanceRemaining", progress.distanceRemaining.toDouble())
            state.put("durationRemaining", progress.durationRemaining)
            state.put(
                "etaEpochMs",
                System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()
            )
            state.put("offRoute", this.offRoute)
            state.put("rerouting", this.rerouting)
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

            if (!this.offRoute && !this.rerouting && this.fasterAlternative() != null) {
                this.fasterRouteSaving?.let {
                    state.put("fasterRoute", JSONObject().put("savingSeconds", it))
                }
            }

            PluginUtilities.sendEvent(MapBoxEvents.NAV_STATE, state.toString())
        } catch (_: java.lang.Exception) {
            // A state that fails to encode is skipped, never fatal.
        }
    }

    /**
     * Gets notified with progress along the currently active route.
     */
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        // update flutter events
        if (!this.isNavigationCanceled) {
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
