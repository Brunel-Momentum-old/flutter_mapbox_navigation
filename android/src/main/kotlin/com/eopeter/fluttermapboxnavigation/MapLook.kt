package com.eopeter.fluttermapboxnavigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.bindgen.Value
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.delegates.listeners.OnStyleLoadedListener
import com.mapbox.maps.plugin.delegates.listeners.OnMapLoadErrorListener
import com.mapbox.maps.extension.observable.eventdata.MapLoadingErrorEventData
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.extension.style.expressions.generated.Expression
import org.json.JSONArray
import org.json.JSONObject
import com.mapbox.maps.LayerPosition
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.gestures.OnFlingListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.OnShoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolygonAnnotationManager
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.navigation.dropin.NavigationView
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.building.api.MapboxBuildingsApi
import com.mapbox.navigation.ui.maps.building.model.MapboxBuildingHighlightOptions
import com.mapbox.navigation.ui.maps.building.view.MapboxBuildingView
import com.mapbox.navigation.ui.maps.puck.LocationPuckOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineResources
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The host app's look for the embedded map — route line, puck, numbered
 * stop pins in overview — and the door view: a close, low camera pushed
 * toward the stop's side of the street with the destination marked.
 */
class MapLook(private val context: Context, private val navigationView: NavigationView) {

    companion object {
        val ROUTE = Color.parseColor("#2463EB")
        val TRAFFIC_MODERATE = Color.parseColor("#F0A91B")
        val TRAFFIC_HEAVY = Color.parseColor("#C8402F")
        val TRAFFIC_SEVERE = Color.parseColor("#9E3325")

        // The part of the route already driven: the route's own blue,
        // faded towards the map it lies on. Pale on the day map, dim on
        // the night one. iOS uses the same.
        val TRAVELLED_DAY = Color.parseColor("#A9BFEC")
        val TRAVELLED_NIGHT = Color.parseColor("#2E3F66")

        // The edge of the route line. White lifts it off the day map; on
        // the night map a white edge glared, so it is a deeper blue there.
        val CASING_DAY = Color.WHITE
        val CASING_NIGHT = Color.parseColor("#122F78")
        val GREEN = Color.parseColor("#2B7348")
        val BUILDING_FILL = Color.parseColor("#BFDCCB")
        val INK = Color.parseColor("#2B2B2B")
        val LEFT_ROUTE = Color.parseColor("#8E8B83")
        // The layer the SDK lights the stop's building with.
        private const val BUILDING_HIGHLIGHT_LAYER = "mapbox-building-highlight-layer"

        // A house number this close to the stop and the same as its own is
        // the stop's: the host draws that one itself. iOS uses the same.
        private const val OWN_NUMBER_M = 30.0

        /**
         * Frames a second while the driver is moving, or something on
         * the map is changing. Left alone the map draws at the display's
         * rate, 90 or 120 a second on most phones, for the whole trip.
         * Guidance does not need it and the battery pays for it. This is
         * what Mapbox's own navigation on iOS draws at on battery.
         */
        const val MOVING_FPS = 30

        /**
         * Stopped, with nothing on the map changing. The picture is the
         * same at any rate, so this only sets how soon a change shows.
         */
        private const val RESTING_FPS = 10

        /**
         * Fixes without movement before the map rests. A van at a stop
         * sign is moving again before this.
         */
        private const val STILL_FIXES_BEFORE_REST = 3

        /** Less than this many metres between two fixes is not movement. */
        private const val STILL_M = 0.5f

        /**
         * How long the map keeps the moving rate after something changed
         * on it with the driver stopped: the sheet moved, the door view
         * came up, the driver touched it.
         */
        private const val AWAKE_FOR_MS = 3000L

        /**
         * The least time between two updates of where the route line
         * turns from travelled to still ahead, in nanoseconds: four a
         * second. The SDK's own is sixteen a second (62.5 ms), and each
         * one is a change to the line's layers that the map then has to
         * draw.
         */
        private const val TRAVELLED_LINE_UPDATE_NS = 250_000_000L
        private const val LEFT_ROUTE_ID = "host-left-route"
        private const val TRAFFIC_LIGHT_ID = "host-traffic-lights"
        private const val FASTER_ROUTE_ID = "host-faster-route"
        private const val FASTER_ROUTE_CASING_ID = "host-faster-route-casing"
        val TIP = Color.parseColor("#1E1E1C")
        val TIP_GREEN = Color.parseColor("#1F5A37")

        // Every layer the SDK draws a route with starts with this.
        private const val ROUTE_LAYER_PREFIX = "mapbox-layerGroup-"
        private const val HOUSE_NUMBER_LAYER = "host-house-numbers"

        /** Clear of the host's Sound and Route buttons on the right. */
        private const val ORNAMENT_RIGHT_DP = 96f

        // The layer the SDK draws the driver's marker in.
        private const val PUCK_LAYER = "mapbox-location-indicator-layer"

        // The layer the SDK draws the 3D truck in, when that is the marker.
        private const val MODEL_PUCK_LAYER = "mapbox-location-model-layer"
        private const val MODEL_PUCK_SOURCE = "mapbox-location-model-source"

        // The route line goes under the first of these the style has: under
        // street names where it can, and under the puck whatever happens.
        // Left to itself it was drawn last, on top of the puck.
        private val ROUTE_LINE_ANCHORS = listOf(
            "road-label-navigation",
            "road-label",
            "road-label-simple",
            PUCK_LAYER,
            MODEL_PUCK_LAYER,
        )
    }

    private val density = context.resources.displayMetrics.density
    private var mapView: MapView? = null
    private var pinManager: PointAnnotationManager? = null
    private var doorPointManager: PointAnnotationManager? = null
    private var doorPolygonManager: PolygonAnnotationManager? = null
    private val buildingView = MapboxBuildingView()
    private var buildingsApi: MapboxBuildingsApi? = null
    private val highlightOptions = MapboxBuildingHighlightOptions.Builder()
        .fillExtrusionColor(BUILDING_FILL)
        .fillExtrusionOpacity(0.9)
        .build()

    private var pins: List<Map<*, *>> = emptyList()
    private var overview = false

    private var doorEnabled = false
    private var doorSide: String? = null
    private var doorPoint: Point? = null
    private var doorLabel: String? = null
    private var footprintFound = false
    private var highlightAttempts = 0

    /** Padding the host UI covers, in pixels. */
    var hostPadding: EdgeInsets? = null
        set(value) {
            // The camera moves to suit: the host's sheet going up or
            // down, as often as not with the driver stopped.
            if (value != field) wakeMap()
            field = value
            placeOrnaments()
        }

    /** Dark map in use: labels this class adds switch to light on dark. */
    var night = false
        set(value) {
            if (value == field) return
            wakeMap()
            field = value
            // The route line takes the colours of the map it lies on,
            // and the house numbers their ink.
            if (routeLineResources != null) {
                routeLineResources = routeLineResources()
                navigationView.customizeViewOptions { routeLineOptions = routeLineOptions() }
            }
            mapView?.getMapboxMap()?.getStyle { applyFasterRoute(it) }
        }

    /**
     * What the following camera keeps clear, in pixels: the host's
     * padding and the room Drop-In's camera is given on top of it. The
     * driver's marker sits at the bottom edge of it.
     */
    var cameraPadding: EdgeInsets? = null

    private var routeLineAnchor = "road-label-simple"
    private val styleLoaded = OnStyleLoadedListener {
        mapView?.getMapboxMap()?.getStyle { onStyle(it) }
    }

    val isDoorView: Boolean get() = doorEnabled

    /** Where the host says the stop is, while the door view is on. */
    val doorTarget: Point? get() = doorPoint

    /** Route line colours and the puck. Call before guidance starts. */
    fun applyStyle() {
        routeLineResources = routeLineResources()
        navigationView.customizeViewOptions {
            routeLineOptions = routeLineOptions()
            enableBuildingHighlightOnArrival = false
        }
        applyMarker()
        navigationView.customizeViewStyles {
            // The stop is marked with the host's own numbered pin, so
            // Drop-In's stock marker is swapped for nothing.
            destinationMarkerAnnotationOptions = PointAnnotationOptions()
                .withIconImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
    }

    // ---- the driver's marker: pin or truck (TruckPuck.kt) --------------

    private var marker = DriverMarker()

    /** The layer the driver's marker is drawn in, whichever it is. */
    private fun puckLayer(style: Style): String? =
        listOf(PUCK_LAYER, MODEL_PUCK_LAYER).firstOrNull { style.styleLayerExists(it) }

    /** The host chose a marker: applied to the map now and kept for later. */
    fun setMarker(next: DriverMarker) {
        if (next == marker) return
        marker = next
        wakeMap()
        applyMarker()
    }

    private fun pinPuck() = LocationPuck2D(
        bearingImage = BitmapDrawable(context.resources, puckBitmap())
    )

    /**
     * Hands Drop-In the marker. Whatever goes wrong with the truck, the
     * driver gets the pin: never an exception out of here, never no marker.
     */
    private fun applyMarker() {
        val puck = marker.puck3D() ?: pinPuck()
        try {
            navigationView.customizeViewStyles {
                locationPuckOptions = LocationPuckOptions.Builder(context)
                    .defaultPuck(puck)
                    .build()
            }
        } catch (t: Throwable) {
            Log.w("MapLook", "marker could not be set; using the pin", t)
            if (puck is LocationPuck2D) return
            marker = marker.copy(truck = false)
            try {
                navigationView.customizeViewStyles {
                    locationPuckOptions = LocationPuckOptions.Builder(context)
                        .defaultPuck(pinPuck())
                        .build()
                }
            } catch (t2: Throwable) {
                Log.w("MapLook", "pin could not be set either", t2)
            }
        }
    }

    /** The model would not load: the pin, never no marker. */
    private val modelLoadError = object : OnMapLoadErrorListener {
        override fun onMapLoadError(eventData: MapLoadingErrorEventData) {
            if (!marker.showsTruck) return
            val message = eventData.message
            val aboutModel = eventData.sourceId == MODEL_PUCK_SOURCE ||
                message.contains("gltf", ignoreCase = true) ||
                message.contains("model", ignoreCase = true)
            if (!aboutModel) return
            Log.w("MapLook", "truck marker failed to load ($message); using the pin")
            marker = marker.copy(truck = false)
            applyMarker()
        }
    }

    private var routeLineResources: RouteLineResources? = null

    /** The route line's colours, for the day map or the night one. */
    private fun routeLineResources(): RouteLineResources {
        val travelled = if (night) TRAVELLED_NIGHT else TRAVELLED_DAY
        val colors = RouteLineColorResources.Builder()
            .routeDefaultColor(ROUTE)
            .routeUnknownCongestionColor(ROUTE)
            .routeLowCongestionColor(ROUTE)
            .routeModerateCongestionColor(TRAFFIC_MODERATE)
            .routeHeavyCongestionColor(TRAFFIC_HEAVY)
            .routeSevereCongestionColor(TRAFFIC_SEVERE)
            .routeCasingColor(if (night) CASING_NIGHT else CASING_DAY)
            .routeLineTraveledColor(travelled)
            // One faded line with no edge of its own, as on iOS.
            .routeLineTraveledCasingColor(travelled)
            .build()
        return RouteLineResources.Builder()
            .routeLineColorResources(colors)
            .originWaypointIcon(R.drawable.host_no_waypoint)
            .destinationWaypointIcon(R.drawable.host_no_waypoint)
            .build()
    }

    private fun routeLineOptions(): MapboxRouteLineOptions {
        val builder = MapboxRouteLineOptions.Builder(context)
        routeLineResources?.let { builder.withRouteLineResources(it) }
        return builder
            .withRouteLineBelowLayerId(routeLineAnchor)
            .withVanishingRouteLineEnabled(true)
            .vanishingRouteLineUpdateInterval(TRAVELLED_LINE_UPDATE_NS)
            .build()
    }

    /** Runs for the style in place and for every style loaded after it. */
    private fun onStyle(style: Style) {
        // Day to night or back. The new style can arrive well after it
        // was asked for, so it is this that wakes the map for it.
        wakeMap()
        val anchor = ROUTE_LINE_ANCHORS.firstOrNull { style.styleLayerExists(it) }
        if (anchor != null && anchor != routeLineAnchor) {
            routeLineAnchor = anchor
            navigationView.customizeViewOptions { routeLineOptions = routeLineOptions() }
        }
        // A new style drops the layers this class added.
        houseNumbersKey = null
        if (doorEnabled) addHouseNumbers()
        if (routeLook != "normal") applyRouteLook(style)
        applyTrafficLights(style)
        applyFasterRoute(style)
    }

    fun onMapAttached(view: MapView) {
        mapView?.getMapboxMap()?.removeOnMapLoadErrorListener(modelLoadError)
        view.getMapboxMap().addOnMapLoadErrorListener(modelLoadError)
        mapView = view
        buildingsApi = MapboxBuildingsApi(view.getMapboxMap())
        pinManager = null
        doorPointManager = null
        doorPolygonManager = null
        tagManager = null
        shownTurnTag = ""
        shownTrafficTag = ""
        shownFasterTips = ""
        shownAlternativeTips = ""
        destinationPinManager = null
        shownDestinationPin = ""
        // A new map has a camera of its own, to be found and tuned again.
        followCameraTuned = false
        followViewport = null
        followCamera = null
        turnView = TurnView.State()
        turnPitch = null
        turnZoom = null
        turnZoomTarget = null
        turnViewAtMs = 0L
        houseNumbersKey = null
        watchTouches(view)
        // Whatever rate this map was made with, it starts at the moving
        // one, and what the last map was set to says nothing about it.
        mapFps = 0
        resetPace()
        placeOrnaments()
        view.getMapboxMap().addOnStyleLoadedListener(styleLoaded)
        view.getMapboxMap().getStyle { onStyle(it) }
        refreshPins()
    }

    /**
     * The Mapbox wordmark and the attribution button have to stay in
     * view. Left alone they sat at the bottom edge, under the host's
     * sheet; they go just above it, left of the host's tool column.
     */
    private fun placeOrnaments() {
        val view = mapView ?: return
        val bottom = (hostPadding?.bottom ?: 0.0).toFloat()
        view.logo.updateSettings {
            position = Gravity.BOTTOM or Gravity.END
            marginRight = dp(ORNAMENT_RIGHT_DP)
            marginBottom = bottom + dp(6f)
        }
        view.attribution.updateSettings {
            position = Gravity.BOTTOM or Gravity.END
            marginRight = dp(ORNAMENT_RIGHT_DP)
            marginBottom = bottom + dp(32f)
        }
    }

    fun onMapDetached() {
        mapView?.getMapboxMap()?.removeOnStyleLoadedListener(styleLoaded)
        mapView?.getMapboxMap()?.removeOnMapLoadErrorListener(modelLoadError)
        // A map let go of while resting is not left at the resting rate.
        resetPace()
        mapView?.let { stopWatchingTouches(it) }
        buildingsApi?.cancel()
        buildingsApi = null
        mapView = null
        pinManager = null
        doorPointManager = null
        doorPolygonManager = null
        followViewport = null
        followCamera = null
    }

    // ---- route look ---------------------------------------------------

    private var routeLook = "normal"
    private var leftRoute: LineString? = null

    /**
     * "normal", "faded" or "left" (see `NavRouteLook` on the Dart side).
     * [ahead] is the part of the old route still ahead of the driver, for
     * "left".
     */
    fun setRouteLook(look: String, ahead: LineString?) {
        if (look == routeLook) return
        routeLook = look
        leftRoute = ahead
        mapView?.getMapboxMap()?.getStyle { applyRouteLook(it) }
    }

    private fun applyRouteLook(style: Style) {
        val opacity = when (routeLook) {
            "faded" -> 0.55
            "left" -> 0.0
            else -> 1.0
        }
        // The SDK keeps its route layers and redraws into them, so an
        // opacity set here stays until it is set back.
        for (layer in style.styleLayers) {
            if (layer.id.startsWith(ROUTE_LAYER_PREFIX)) {
                style.setStyleLayerProperty(layer.id, "line-opacity", Value(opacity))
            }
        }
        if (style.styleLayerExists(LEFT_ROUTE_ID)) style.removeStyleLayer(LEFT_ROUTE_ID)
        if (style.styleSourceExists(LEFT_ROUTE_ID)) style.removeStyleSource(LEFT_ROUTE_ID)
        val ahead = leftRoute
        if (routeLook != "left" || ahead == null) return
        style.addSource(geoJsonSource(LEFT_ROUTE_ID) { geometry(ahead) })
        val layer = lineLayer(LEFT_ROUTE_ID, LEFT_ROUTE_ID) {
            lineColor(LEFT_ROUTE)
            lineWidth(8.0)
            lineCap(LineCap.ROUND)
            lineDasharray(listOf(0.5, 1.5))
        }
        val anchor = ROUTE_LINE_ANCHORS.firstOrNull { style.styleLayerExists(it) }
        if (anchor != null) style.addLayerBelow(layer, anchor) else style.addLayer(layer)
    }

    // ---- stop pins ----------------------------------------------------

    fun setStopPins(next: List<Map<*, *>>) {
        pins = next
        refreshPins()
    }

    fun onCameraState(state: String) {
        overview = state == "overview"
        refreshPins()
        refreshTags()
        refreshDestinationPin()
    }

    // ---- traffic lights -----------------------------------------------

    private var trafficLights: List<Point> = emptyList()

    /** Where the route in use passes a traffic light. */
    fun setTrafficLights(points: List<Point>) {
        if (points == trafficLights) return
        trafficLights = points
        mapView?.getMapboxMap()?.getStyle { applyTrafficLights(it) }
    }

    private fun applyTrafficLights(style: Style) {
        val lights = FeatureCollection.fromFeatures(trafficLights.map { Feature.fromGeometry(it) })
        style.getSourceAs<GeoJsonSource>(TRAFFIC_LIGHT_ID)?.let {
            it.featureCollection(lights)
            return
        }
        if (trafficLights.isEmpty()) return
        style.addImage(TRAFFIC_LIGHT_ID, trafficLightBitmap())
        style.addSource(geoJsonSource(TRAFFIC_LIGHT_ID) { featureCollection(lights) })
        val layer = symbolLayer(TRAFFIC_LIGHT_ID, TRAFFIC_LIGHT_ID) {
            iconImage(TRAFFIC_LIGHT_ID)
            iconAllowOverlap(true)
            // Taking no room from the street names, and smaller the
            // further out the camera is: the two lights of one crossing,
            // some 20 m apart, sit side by side at the driving view
            // instead of on top of each other (founder, 2026-10-19).
            iconIgnorePlacement(true)
            iconSize(
                interpolate {
                    linear()
                    zoom()
                    stop { literal(15.5); literal(0.62) }
                    stop { literal(17.5); literal(0.85) }
                    stop { literal(19.0); literal(1.0) }
                }
            )
            // Only close enough to matter: a route's worth of lights
            // at city zoom is confetti.
            minZoom(15.5)
        }
        // Under the driver's marker where there is one. Added last, a
        // light was drawn over the driver waiting at it.
        val puck = puckLayer(style)
        if (puck != null) style.addLayerBelow(layer, puck) else style.addLayer(layer)
    }

    // ---- tags on the map: the next turn, and a faster route -----------

    private var tagManager: PointAnnotationManager? = null
    private var turnTag: Pair<Point, String>? = null
    private var shownTurnTag = ""
    private var fasterRoute: LineString? = null
    private var fasterTips: List<Pair<Point, String>> = emptyList()
    private var shownFasterTips = ""
    /** Every other way, tagged with what it costs or saves. */
    private var alternativeTips: List<Pair<Point, String>> = emptyList()
    private var shownAlternativeTips = ""

    /** The street the next turn goes onto, tagged at the turn. Null for none. */
    fun setTurnTag(point: Point?, street: String?) {
        turnTag = if (point == null || street.isNullOrBlank()) null else point to street
        refreshTags()
    }

    private var trafficTag: Pair<Point, String>? = null
    private var shownTrafficTag = ""

    /**
     * Heavy traffic ahead on the route, tagged in the middle of it with
     * what it costs ("+3 min · Heavy traffic"). Null for none.
     */
    fun setTrafficTag(point: Point?, text: String?) {
        trafficTag = if (point == null || text.isNullOrBlank()) null else point to text
        refreshTags()
    }

    private var destinationPinManager: PointAnnotationManager? = null
    private var destinationPin: Pair<Point, Double?>? = null
    private var shownDestinationPin = ""

    /**
     * Marks the end of the route with a pin whose arrow points at the
     * stop. The route ends on the road; the stop is a house or a door to
     * one side of it, and which side is the first thing the driver needs
     * to know as they pull up. [bearing] is the compass direction from
     * [road] to the stop, null when nothing says. A null [road] takes the
     * pin off.
     */
    fun setDestinationPin(road: Point?, bearing: Double?) {
        destinationPin = road?.let { it to bearing }
        refreshDestinationPin()
    }

    private fun refreshDestinationPin() {
        val view = mapView ?: return
        // The overview has the numbered pins.
        val pin = destinationPin?.takeIf { !overview }
        val key = pin?.let {
            "${it.first.latitude()},${it.first.longitude()},${it.second?.let { b -> Math.round(b) } ?: "-"}"
        }.orEmpty()
        if (key == shownDestinationPin) return
        shownDestinationPin = key
        val manager = destinationPinManager ?: run {
            // Under the driver's own marker: stopped at the end of the
            // route the two are in the same place, and the marker is the
            // one that has to show. On top, the pin hid it.
            val underMarker = view.getMapboxMap().getStyle()
                ?.let { puckLayer(it) }
                ?.let { AnnotationConfig(belowLayerId = it) }
            view.annotations.createPointAnnotationManager(underMarker).also {
                // The arrow is a direction on the ground: it turns with
                // the map, and no label may hide it.
                it.iconRotationAlignment = IconRotationAlignment.MAP
                it.iconAllowOverlap = true
                it.iconIgnorePlacement = true
                destinationPinManager = it
            }
        }
        manager.deleteAll()
        if (pin == null) return
        manager.create(
            PointAnnotationOptions().withPoint(pin.first)
                .withIconImage(destinationPinBitmap(pointing = pin.second != null))
                .withIconRotate(pin.second ?: 0.0)
        )
    }

    /**
     * A dark disc with a white ring; [pointing] adds a white arrow, tip
     * up, to be turned towards the stop.
     */
    private fun destinationPinBitmap(pointing: Boolean): Bitmap {
        val u = density
        val diameter = 40f * u
        val size = (diameter + 4f * u).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = TIP
        canvas.drawCircle(c, c, diameter / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * u
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, diameter / 2f - 1.5f * u, paint)
        paint.style = Paint.Style.FILL
        if (!pointing) {
            canvas.drawCircle(c, c, 5f * u, paint)
            return bitmap
        }
        val arrow = Path().apply {
            moveTo(c, c - 13f * u)
            lineTo(c + 10f * u, c - u)
            lineTo(c + 3.5f * u, c - u)
            lineTo(c + 3.5f * u, c + 12f * u)
            lineTo(c - 3.5f * u, c + 12f * u)
            lineTo(c - 3.5f * u, c - u)
            lineTo(c - 10f * u, c - u)
            close()
        }
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    /**
     * A faster route on offer: the part of it that leaves the route in
     * use, in green, with "N min faster" on it and "Yours" on the route
     * in use. Null takes it off again.
     */
    fun setFasterRoute(branch: LineString?, minutes: Int, yours: Point?) {
        fasterRoute = branch
        fasterTips = if (branch == null) emptyList() else listOfNotNull(
            pointAlong(branch, 0.4)?.let { it to "$minutes min faster" },
            yours?.let { it to "Yours" },
        )
        mapView?.getMapboxMap()?.getStyle { applyFasterRoute(it) }
        refreshTags()
    }

    private fun applyFasterRoute(style: Style) {
        for (id in listOf(FASTER_ROUTE_ID, FASTER_ROUTE_CASING_ID)) {
            if (style.styleLayerExists(id)) style.removeStyleLayer(id)
        }
        if (style.styleSourceExists(FASTER_ROUTE_ID)) style.removeStyleSource(FASTER_ROUTE_ID)
        val branch = fasterRoute ?: return
        style.addSource(geoJsonSource(FASTER_ROUTE_ID) { geometry(branch) })
        val anchor = ROUTE_LINE_ANCHORS.firstOrNull { style.styleLayerExists(it) }
        val casing = lineLayer(FASTER_ROUTE_CASING_ID, FASTER_ROUTE_ID) {
            lineColor(if (night) TIP_GREEN else Color.WHITE)
            lineWidth(12.0)
            lineCap(LineCap.ROUND)
        }
        val line = lineLayer(FASTER_ROUTE_ID, FASTER_ROUTE_ID) {
            lineColor(GREEN)
            lineWidth(7.0)
            lineCap(LineCap.ROUND)
        }
        if (anchor != null) {
            style.addLayerBelow(casing, anchor)
            style.addLayerBelow(line, anchor)
        } else {
            style.addLayer(casing)
            style.addLayer(line)
        }
    }

    /**
     * Every other way the route could go, each tagged with what it costs
     * or saves against the route in use ("+3 min", "2 min faster", "Same
     * time"), in the look of the street tag at a turn (founder,
     * 2026-10-19). A tap on a tag, or on the line, takes that way
     * (`TurnByTurn.takeAlternativeAt`).
     */
    fun setAlternativeTags(tips: List<Pair<Point, String>>) {
        alternativeTips = tips
        refreshTags()
    }

    /** One manager for both kinds of tag, so they stack predictably. */
    private fun refreshTags() {
        val view = mapView ?: return
        // The door view has the stop's own marking; the turn tag would
        // sit on top of it. The faster route's tags are for the overview
        // the host switches to when it offers one, and stay in any view.
        val turn = turnTag?.takeIf { !doorEnabled && !overview && fasterRoute == null }
        val turnKey = turn?.let { "${it.first.latitude()},${it.first.longitude()},${it.second}" }.orEmpty()
        // Traffic is a thing of the road, not of the door; and a faster
        // route on offer says the same with a way out attached.
        val traffic = trafficTag?.takeIf { !doorEnabled && fasterRoute == null }
        val trafficKey = traffic?.let { "${it.first.latitude()},${it.first.longitude()},${it.second}" }.orEmpty()
        val tipsKey = fasterTips.joinToString(";") { "${it.first.latitude()},${it.first.longitude()},${it.second}" }
        // The other ways' tags are for the road, not the door.
        val alternatives = if (doorEnabled) emptyList() else alternativeTips
        val alternativesKey = alternatives.joinToString(";") {
            "${it.first.latitude()},${it.first.longitude()},${it.second}"
        }
        if (turnKey == shownTurnTag && trafficKey == shownTrafficTag && tipsKey == shownFasterTips &&
            alternativesKey == shownAlternativeTips
        ) return
        shownTurnTag = turnKey
        shownTrafficTag = trafficKey
        shownFasterTips = tipsKey
        shownAlternativeTips = alternativesKey
        val manager = tagManager ?: view.annotations.createPointAnnotationManager().also { tagManager = it }
        manager.deleteAll()
        if (turn != null) {
            manager.create(
                PointAnnotationOptions().withPoint(turn.first)
                    .withIconImage(tagBitmap(turn.second, TIP, pointer = true))
                    .withIconAnchor(IconAnchor.BOTTOM)
                    .withIconOffset(listOf(0.0, -8.0))
            )
        }
        if (traffic != null) {
            // In the red the route line has for heavy traffic, and sat
            // on the line: it marks a stretch, not a point.
            manager.create(
                PointAnnotationOptions().withPoint(traffic.first)
                    .withIconImage(tagBitmap(traffic.second, TRAFFIC_HEAVY, pointer = false))
            )
        }
        for ((point, text) in fasterTips) {
            manager.create(
                PointAnnotationOptions().withPoint(point)
                    .withIconImage(tagBitmap(text, if (text == "Yours") TIP else TIP_GREEN, pointer = false))
                    .withSymbolSortKey(if (text == "Yours") 0.0 else 1.0)
            )
        }
        for ((point, text) in alternatives) {
            manager.create(
                PointAnnotationOptions().withPoint(point)
                    .withIconImage(tagBitmap(text, TIP, pointer = false))
                    .withSymbolSortKey(2.0)
            )
        }
    }

    /** The point [fraction] of the way along [line]. */
    private fun pointAlong(line: LineString, fraction: Double): Point? {
        val coordinates = line.coordinates()
        if (coordinates.size < 2) return coordinates.firstOrNull()
        val length = TurfMeasurement.length(line, TurfConstants.UNIT_METERS)
        return TurfMeasurement.along(line, length * fraction, TurfConstants.UNIT_METERS)
    }

    private fun refreshPins() {
        val view = mapView ?: return
        val manager = pinManager ?: view.annotations.createPointAnnotationManager().also {
            pinManager = it
        }
        manager.deleteAll()
        for (pin in pins) {
            val lat = pin["latitude"] as? Double ?: continue
            val lng = pin["longitude"] as? Double ?: continue
            val label = pin["label"] as? String ?: ""
            val current = pin["current"] as? Boolean ?: false
            val partner = pin["kind"] == "partner"
            // Later stops belong to the overview. The stop being driven
            // to stays marked until the door view takes over, and the
            // other driver's position is worth seeing all the way in.
            if (!partner && !overview && !(current && !doorEnabled)) continue
            // Stops can share a spot (pickup and sort at the depot): the
            // one being driven to goes on top, the other driver above all.
            val options = PointAnnotationOptions()
                .withPoint(Point.fromLngLat(lng, lat))
                .withSymbolSortKey(if (partner) 2.0 else if (current) 1.0 else 0.0)
            manager.create(
                if (partner) {
                    options.withIconImage(partnerBitmap(label)).withIconAnchor(IconAnchor.TOP)
                        .withIconOffset(listOf(0.0, -18.0))
                } else {
                    options.withIconImage(pinBitmap(label, current))
                }
            )
        }
    }

    // ---- door view ----------------------------------------------------

    fun setDoorView(enabled: Boolean, side: String?, latitude: Double?, longitude: Double?, label: String?) {
        // Coming up, changing or going away, the camera moves for it,
        // and the driver has usually just stopped.
        wakeMap()
        if (!enabled) {
            leaveDoorView()
            return
        }
        val entering = !doorEnabled
        doorEnabled = true
        doorSide = side
        doorLabel = label
        val point = if (latitude != null && longitude != null) Point.fromLngLat(longitude, latitude) else null
        if (point != doorPoint) {
            footprintFound = false
            highlightAttempts = 0
        }
        doorPoint = point
        // Every time: the number left out of the map's own is the stop's.
        addHouseNumbers()
        if (entering) {
            dropTurnView()
            refreshPins()
            refreshTags()
        }
    }

    fun leaveDoorView() {
        if (!doorEnabled) return
        doorEnabled = false
        wakeMap()
        // The building's marking goes with the view. Coming back for the
        // same stop has to look for the building again, or it marks
        // nothing.
        footprintFound = false
        highlightAttempts = 0
        mapView?.getMapboxMap()?.getStyle { buildingView.removeBuildingHighlight(it, highlightOptions) }
        doorPointManager?.deleteAll()
        doorPolygonManager?.deleteAll()
        refreshPins()
        refreshTags()
    }

    // ---- heading up ---------------------------------------------------

    private var followCameraTuned = false

    /**
     * Heading up, always, with the driver's marker in one place.
     *
     * By default the camera looks towards the next turn, up to 45 degrees
     * off the way the driver is going, so on a curving road the marker's
     * arrow swung round while the map stayed put; and near a turn it
     * flattened and slid over to frame the turn, taking the marker off its
     * spot. Drop-In keeps the camera's settings to itself, so they are
     * reached through the objects that hold them, found by type (names do
     * not survive a release build). If the SDK ever moves them this does
     * nothing and the camera behaves as the SDK ships it.
     */
    private fun tuneFollowCamera() {
        if (followCameraTuned) return
        val view = mapView ?: return
        try {
            val plugin: Any = view.camera
            val handler = valuesIn(plugin).filterIsInstance<Iterable<*>>()
                .flatMap { it.toList() }
                .firstOrNull { it is NavigationBasicGesturesHandler } ?: return
            val camera = valuesIn(handler).firstOrNull { it is NavigationCamera } ?: return
            val viewport = valuesIn(camera).filterIsInstance<MapboxNavigationViewportDataSource>()
                .firstOrNull() ?: return
            viewport.options.followingFrameOptions.bearingSmoothing.enabled = false
            viewport.options.followingFrameOptions.pitchNearManeuvers.enabled = false
            // Looking straight down, the SDK stops following the driver
            // and frames the road ahead instead. The bird's-eye view at
            // a turn keeps the marker where it is ([frameTurn]).
            viewport.options.followingFrameOptions.maximizeViewableGeometryWhenPitchZero = false
            viewport.evaluate()
            followCameraTuned = true
            // Kept, so that the view at a turn and pacing the map do not
            // have to go looking for them again on every tick.
            followViewport = viewport
            followCamera = camera as? NavigationCamera
        } catch (e: Exception) {
            followCameraTuned = true
            Log.w("MapLook", "follow camera left as the SDK ships it: $e")
        }
    }

    // ---- bird's-eye view at a turn ------------------------------------

    // Where the following camera takes its tilt and zoom from: found
    // once by [tuneFollowCamera], for the map now attached.
    private var followViewport: MapboxNavigationViewportDataSource? = null
    private var turnViewFailed = false
    private var turnView = TurnView.State()

    // The tilt and zoom the following camera is being held at, while
    // this class has them. Null while the SDK has its camera.
    private var turnPitch: Double? = null
    private var turnZoom: Double? = null
    private var turnZoomTarget: Double? = null
    private var turnViewAtMs = 0L

    /**
     * Takes the following camera straight down as a turn comes up, and
     * back to its tilt once past it. Runs on every progress tick. When
     * and how close is decided in [TurnView]; this moves the camera.
     *
     * [needsChoice] is whether the step being driven ends in a turn the
     * driver has to pick a way at, with a route that can be trusted.
     * [distanceToTurn] and [distanceIntoStep] are in metres, [speed] in
     * metres a second. [ahead] is the road from the driver to the turn
     * and the first stretch after it.
     *
     * The SDK's following camera goes on placing the driver and turning
     * the map: only its tilt and its zoom are overridden, and only from
     * the moment the view starts to come up until it is fully back. The
     * SDK moves the camera to whatever it is given over the second
     * between two ticks, so a target that changes a little each tick is
     * a smooth move on screen.
     *
     * This replaces the earlier "close in on a turn", which raised the
     * tilted camera's least zoom as a turn neared: two rules moving one
     * zoom fought each other.
     */
    fun frameTurn(
        stepKey: Int,
        needsChoice: Boolean,
        distanceToTurn: Double?,
        distanceIntoStep: Double,
        speed: Double,
        location: Location?,
        ahead: List<Point>,
    ) {
        val viewport = followViewport ?: return
        val view = mapView ?: return
        if (turnViewFailed) return
        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (turnViewAtMs == 0L) 1.0 else ((now - turnViewAtMs) / 1000.0).coerceIn(0.2, 1.5)
            turnViewAtMs = now

            // The door view has the camera to itself.
            val choice = needsChoice && !doorEnabled
            val wasTopDown = turnView.topDown
            turnView = TurnView.next(
                turnView, stepKey, choice, distanceToTurn,
                if (doorEnabled) Double.POSITIVE_INFINITY else distanceIntoStep, speed,
            )
            if (turnView.topDown != wasTopDown) wakeMap()

            val tilt = viewport.options.followingFrameOptions.defaultPitch
            // The SDK has the camera and nothing is asked of it.
            if (!turnView.topDown && turnPitch == null) return

            val camera = view.getMapboxMap().cameraState
            var pitch = turnPitch ?: camera.pitch
            var zoom = turnZoom ?: camera.zoom
            val zoomTarget: Double
            if (turnView.topDown) {
                // While the turn is ahead the zoom follows it in.
                // Through the turn and just past it, it stays where it
                // was: what is ahead then is the whole of the next street.
                if (choice && turnView.stepKey == stepKey && location != null) {
                    turnZoom(location, ahead, view)?.let { turnZoomTarget = it }
                }
                zoomTarget = turnZoomTarget ?: zoom
            } else {
                // Back to where the SDK's own zoom takes over without a jump.
                zoomTarget = min(zoom, TurnView.TILTED_CLOSEST_ZOOM)
            }
            val pitchTarget = if (turnView.topDown) 0.0 else tilt
            if (followCamera?.state == NavigationCameraState.FOLLOWING) {
                pitch = TurnView.step(pitch, pitchTarget, tilt / TurnView.EASE_SECONDS * elapsed)
                zoom = TurnView.step(zoom, zoomTarget, TurnView.ZOOM_PER_SECOND * elapsed)
            } else {
                // Nobody is watching the following camera (the overview,
                // a map the driver has moved, the door view): it is
                // simply where it should be when they come back to it.
                pitch = pitchTarget
                zoom = zoomTarget
            }

            if (!turnView.topDown && pitch == pitchTarget && zoom == zoomTarget) {
                // Fully back: the SDK has its camera again.
                dropTurnView()
                return
            }
            turnPitch = pitch
            turnZoom = zoom
            viewport.followingPitchPropertyOverride(pitch)
            viewport.followingZoomPropertyOverride(zoom)
            // The SDK reads these when it works the frame out, not when
            // they are set, so it is asked to do that now and not at its
            // next fix.
            viewport.evaluate()
        } catch (e: Exception) {
            turnViewFailed = true
            Log.w("MapLook", "no bird's-eye view at turns: $e")
            try {
                dropTurnView()
            } catch (_: Exception) {
                // The camera keeps what it has.
            }
        }
    }

    /**
     * The bird's-eye zoom that has the driver, the turn and the first
     * stretch after it between the host's banner and its sheet.
     */
    private fun turnZoom(location: Location, ahead: List<Point>, view: MapView): Double? {
        if (ahead.isEmpty()) return null
        val heading = (if (location.hasBearing()) location.bearing.toDouble() else view.getMapboxMap().cameraState.bearing) *
            PI / 180
        val northMetres = 111_320.0
        val eastMetres = northMetres * cos(location.latitude * PI / 180)
        val points = ahead.map {
            val north = (it.latitude() - location.latitude) * northMetres
            val east = (it.longitude() - location.longitude) * eastMetres
            TurnView.Offset(
                ahead = east * sin(heading) + north * cos(heading),
                side = east * cos(heading) - north * sin(heading),
            )
        }
        val padding = cameraPadding ?: hostPadding ?: EdgeInsets(0.0, 0.0, 0.0, 0.0)
        val aheadDp = (view.height - padding.top - padding.bottom) / density
        val halfWidthDp = (view.width - padding.left - padding.right) / density / 2
        return TurnView.fitZoom(points, max(120.0, aheadDp), halfWidthDp, location.latitude)
    }

    /**
     * Hands the camera back to the SDK at once: for a trip starting or
     * ending, and when the view has eased all the way back.
     */
    fun dropTurnView() {
        turnView = TurnView.State()
        turnPitch = null
        turnZoom = null
        turnZoomTarget = null
        turnViewAtMs = 0L
        val viewport = followViewport ?: return
        try {
            viewport.followingPitchPropertyOverride(null)
            viewport.followingZoomPropertyOverride(null)
            viewport.evaluate()
        } catch (e: Exception) {
            Log.w("MapLook", "camera not handed back after a turn: $e")
        }
    }

    // ---- how often the map draws --------------------------------------

    // The SDK's camera, kept from the same search as [followViewport]:
    // it knows when it is part-way between following and overview.
    private var followCamera: NavigationCamera? = null
    private var stillFixes = 0
    private var lastPacedFix: Location? = null
    private var awakeUntilMs = 0L

    // The rate the map was last set to. 0 for a map not set yet.
    private var mapFps = 0
    private var pacingFailed = false

    /**
     * Sets how often the map draws, for the fix just handled: the moving
     * rate while the van moves, the resting rate once it has stood still
     * for a few fixes. A van waiting at a stop or at a light shows the
     * same picture from one fix to the next, and the battery pays for
     * every frame of it. The first fix that moves brings the moving rate
     * straight back.
     *
     * [location] is where the navigator has the driver. [phoneSpeed] is
     * the phone's own reading, in metres a second: the navigator can
     * take a fix or two to move off after the phone has.
     */
    fun paceMap(location: Location, phoneSpeed: Float) {
        try {
            val last = lastPacedFix
            val moved = last == null || location.distanceTo(last) > STILL_M
            val speed = max(if (location.hasSpeed()) location.speed else 0f, phoneSpeed)
            lastPacedFix = location
            stillFixes = if (moved || speed >= LocationSteadier.MOVING_SPEED) 0 else stillFixes + 1
            val resting = stillFixes >= STILL_FIXES_BEFORE_REST &&
                !cameraBetweenModes() &&
                SystemClock.elapsedRealtime() >= awakeUntilMs
            setMapFps(if (resting) RESTING_FPS else MOVING_FPS)
        } catch (e: Exception) {
            stopPacing("map not paced to the driver", e)
        }
    }

    /**
     * Something is about to change on the map, perhaps with the driver
     * stopped: the moving rate from now until it has had time to finish.
     */
    fun wakeMap() {
        awakeUntilMs = SystemClock.elapsedRealtime() + AWAKE_FOR_MS
        setMapFps(MOVING_FPS)
    }

    /**
     * Back to the moving rate with nothing remembered of the fixes
     * before. For a trip starting, and for a map coming or going.
     */
    fun resetPace() {
        stillFixes = 0
        lastPacedFix = null
        awakeUntilMs = 0L
        setMapFps(MOVING_FPS)
    }

    /**
     * Part-way between following and overview the camera is moving,
     * whatever the driver is doing.
     */
    private fun cameraBetweenModes(): Boolean {
        val state = followCamera?.state
        return state == NavigationCameraState.TRANSITION_TO_FOLLOWING ||
            state == NavigationCameraState.TRANSITION_TO_OVERVIEW
    }

    private fun setMapFps(fps: Int) {
        if (fps == mapFps || pacingFailed) return
        val view = mapView ?: return
        try {
            view.setMaximumFps(fps)
            mapFps = fps
        } catch (e: Exception) {
            stopPacing("map frame rate not set", e)
        }
    }

    /**
     * Pacing that has failed once is not tried again. The map is put back
     * to the moving rate, if it will go, and stays there: a map that
     * draws more than it needs is better than one stuck at the resting
     * rate under a moving van.
     */
    private fun stopPacing(what: String, e: Exception) {
        if (pacingFailed) return
        pacingFailed = true
        Log.w("MapLook", "$what, the map keeps its moving rate: $e")
        try {
            mapView?.setMaximumFps(MOVING_FPS)
        } catch (_: Exception) {
            // It keeps whatever rate it has.
        }
    }

    // A touch on the map changes the picture with the driver stopped.
    // Each of these wakes the map through to the end of the gesture, and
    // the awake time after it covers the glide that follows.
    private val moveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) = wakeMap()

        override fun onMove(detector: MoveGestureDetector): Boolean {
            wakeMap()
            // Not handled here: the map still has to move.
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) = wakeMap()
    }

    private val scaleListener = object : OnScaleListener {
        override fun onScaleBegin(detector: StandardScaleGestureDetector) = wakeMap()
        override fun onScale(detector: StandardScaleGestureDetector) = wakeMap()
        override fun onScaleEnd(detector: StandardScaleGestureDetector) = wakeMap()
    }

    private val rotateListener = object : OnRotateListener {
        override fun onRotateBegin(detector: RotateGestureDetector) = wakeMap()
        override fun onRotate(detector: RotateGestureDetector) = wakeMap()
        override fun onRotateEnd(detector: RotateGestureDetector) = wakeMap()
    }

    // Two fingers dragged up or down, which tilts the map.
    private val shoveListener = object : OnShoveListener {
        override fun onShoveBegin(detector: ShoveGestureDetector) = wakeMap()
        override fun onShove(detector: ShoveGestureDetector) = wakeMap()
        override fun onShoveEnd(detector: ShoveGestureDetector) = wakeMap()
    }

    private val flingListener = object : OnFlingListener {
        override fun onFling() = wakeMap()
    }

    private fun watchTouches(view: MapView) {
        try {
            val gestures = view.gestures
            gestures.addOnMoveListener(moveListener)
            gestures.addOnScaleListener(scaleListener)
            gestures.addOnRotateListener(rotateListener)
            gestures.addOnShoveListener(shoveListener)
            gestures.addOnFlingListener(flingListener)
        } catch (e: Exception) {
            // Unwatched, a touch would find the map at the resting rate
            // and drag it about in jerks. So it does not rest at all.
            stopPacing("touches on the map not watched", e)
        }
    }

    private fun stopWatchingTouches(view: MapView) {
        try {
            val gestures = view.gestures
            gestures.removeOnMoveListener(moveListener)
            gestures.removeOnScaleListener(scaleListener)
            gestures.removeOnRotateListener(rotateListener)
            gestures.removeOnShoveListener(shoveListener)
            gestures.removeOnFlingListener(flingListener)
        } catch (_: Exception) {
            // The map is on its way out with its listeners.
        }
    }

    /** The values of every field [target] declares, however private. */
    private fun valuesIn(target: Any): List<Any> {
        val values = ArrayList<Any>()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                try {
                    field.isAccessible = true
                    field.get(target)?.let { values.add(it) }
                } catch (_: Exception) {
                    // A field that will not be read is not the one wanted.
                }
            }
            type = type.superclass
        }
        return values
    }

    /** Runs on every location update. */
    fun onLocation(location: Location) {
        tuneFollowCamera()
        if (!doorEnabled) return
        // The overview the driver asked for stays up until they
        // re-centre. Moving the camera back to the door on every fix
        // took it away again within a second. The building is not looked
        // for meanwhile either: from that far out it cannot be found, and
        // the tries are few.
        if (overview) return
        val view = mapView ?: return
        val padding = hostPadding ?: EdgeInsets(0.0, 0.0, 0.0, 0.0)
        val height = view.height.toDouble()
        val width = view.width.toDouble()
        val clearHeight = max(120.0 * density, height - padding.top - padding.bottom)

        // Close enough to read house numbers, far enough that the stop
        // is on screen.
        var zoom = 18.6
        val destination = doorPoint
        if (destination != null) {
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                destination.latitude(), destination.longitude(), results
            )
            val metresPerDp = (results[0] + 20.0) / ((clearHeight / density) * 0.62)
            val fitted = ln(78271.484 * cos(location.latitude * PI / 180) / max(metresPerDp, 0.01)) / ln(2.0)
            zoom = min(18.8, max(16.6, fitted))
        }

        // The stop's side of the street gets most of the width.
        val shift = width * 0.3
        val camera = CameraOptions.Builder()
            .center(Point.fromLngLat(location.longitude, location.latitude))
            .zoom(zoom)
            .pitch(20.0)
            .padding(
                EdgeInsets(
                    padding.top + clearHeight * 0.38,
                    if (doorSide == "left") shift else 0.0,
                    padding.bottom,
                    if (doorSide == "right") shift else 0.0,
                )
            )
            .apply { if (location.hasBearing()) bearing(location.bearing.toDouble()) }
            .build()
        view.camera.easeTo(camera, MapAnimationOptions.mapAnimationOptions { duration(1000L) })

        if (destination != null && !footprintFound && highlightAttempts < 12) {
            highlightAttempts += 1
            val attempt = highlightAttempts
            buildingsApi?.queryBuildingToHighlight(destination) { expected ->
                val buildings = expected.value?.buildings ?: emptyList()
                if (!doorEnabled) return@queryBuildingToHighlight
                if (buildings.isNotEmpty()) {
                    footprintFound = true
                    view.getMapboxMap().getStyle {
                        buildingView.highlightBuilding(it, buildings, highlightOptions)
                        // The lit building goes on top of everything
                        // there is, the house numbers included.
                        raiseHouseNumbers(it)
                    }
                }
                // No footprint under the pin once it has had time to
                // come on screen: mark the spot instead of the building.
                // Once the building itself is lit up, nothing is drawn
                // over it: a number tag on top hid the very house the
                // driver is looking for. The number itself is always
                // there, though: as a small badge on the building once
                // it is lit, since the map's own number for it cannot be
                // counted on (see [addHouseNumbers]).
                showDoorMarker(destination, ring = buildings.isEmpty() && attempt >= 4, tag = buildings.isEmpty())
            }
        }
    }

    private fun showDoorMarker(point: Point, ring: Boolean, tag: Boolean) {
        val view = mapView ?: return
        val polygons = doorPolygonManager ?: view.annotations.createPolygonAnnotationManager().also {
            doorPolygonManager = it
        }
        val points = doorPointManager ?: view.annotations.createPointAnnotationManager().also {
            // The stop's own number: no other label may push it off.
            it.iconAllowOverlap = true
            doorPointManager = it
        }
        polygons.deleteAll()
        if (ring) {
            polygons.create(
                PolygonAnnotationOptions()
                    .withPoints(listOf(circle(point, 15.0)))
                    .withFillColor(BUILDING_FILL)
                    .withFillOpacity(0.55)
                    .withFillOutlineColor(GREEN)
            )
        }
        points.deleteAll()
        val label = doorLabel?.trim().orEmpty()
        if (tag) {
            points.create(
                PointAnnotationOptions()
                    .withPoint(point)
                    .withIconImage(doorBitmap(label))
                    .withIconAnchor(IconAnchor.BOTTOM)
            )
        } else if (label.isNotEmpty()) {
            // The building is lit: its number sits on it, small enough
            // to leave the building in view.
            points.create(PointAnnotationOptions().withPoint(point).withIconImage(doorBadgeBitmap(label)))
        }
    }

    private fun circle(center: Point, radius: Double): List<Point> {
        val steps = 48
        val latMetres = 111_320.0
        val lngMetres = latMetres * cos(center.latitude() * PI / 180)
        return (0..steps).map { step ->
            val angle = step.toDouble() / steps * 2 * PI
            Point.fromLngLat(
                center.longitude() + radius * sin(angle) / lngMetres,
                center.latitude() + radius * cos(angle) / latMetres,
            )
        }
    }

    // What the house-number layer on the map was built for.
    private var houseNumbersKey: String? = null

    /**
     * House numbers on every building, from the street data the base
     * style already carries but does not draw.
     *
     * Three things used to lose numbers:
     *
     * - The lit building is a layer the SDK adds on top of everything
     *   when it finds the building, after this layer: the stop's own
     *   number was under its fill. This layer is kept above it
     *   ([raiseHouseNumbers]).
     * - Numbers that touch are dropped by the map, and at the zoom the
     *   door view starts at, with 15-point text, the houses of a terrace
     *   were close enough for every other one to go. The text is smaller
     *   further out and has less room kept round it.
     * - Not every building has a number in the map's data at all.
     *
     * The last cannot be fixed from here, so the stop's own number does
     * not rely on this layer: it is drawn by [showDoorMarker], and the
     * map's number for the same house is left out so it is not there
     * twice.
     */
    private fun addHouseNumbers() {
        mapView?.getMapboxMap()?.getStyle { style ->
            if (!style.styleSourceExists("composite")) return@getStyle
            val own = doorLabel?.trim().orEmpty()
            val stop = doorPoint
            val key = "$night|$own|${stop?.latitude()},${stop?.longitude()}"
            if (style.styleLayerExists(HOUSE_NUMBER_LAYER)) {
                if (key == houseNumbersKey) {
                    raiseHouseNumbers(style)
                    return@getStyle
                }
                style.removeStyleLayer(HOUSE_NUMBER_LAYER)
            }
            style.addLayer(
                symbolLayer(HOUSE_NUMBER_LAYER, "composite") {
                    sourceLayer("housenum_label")
                    minZoom(16.0)
                    textField(get { literal("house_num") })
                    if (own.isNotEmpty() && stop != null) filter(notTheStopsNumber(own, stop))
                    textSize(
                        interpolate {
                            linear()
                            zoom()
                            stop { literal(16.5); literal(12.0) }
                            stop { literal(18.0); literal(16.0) }
                        }
                    )
                    textPadding(1.0)
                    textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold"))
                    textColor(if (night) Color.WHITE else INK)
                    textHaloColor(if (night) TIP else Color.WHITE)
                    textHaloWidth(2.0)
                }
            )
            houseNumbersKey = key
            raiseHouseNumbers(style)
        }
    }

    /**
     * Every house number but the stop's own: the same number within
     * [OWN_NUMBER_M] of the stop. Further off it is another house's (or
     * the stop's pin is not on its house), and stays.
     */
    private fun notTheStopsNumber(number: String, stop: Point): Expression {
        val dLat = OWN_NUMBER_M / 111_320.0
        val dLng = OWN_NUMBER_M / (111_320.0 * cos(stop.latitude() * PI / 180))
        val s = stop.latitude() - dLat
        val n = stop.latitude() + dLat
        val w = stop.longitude() - dLng
        val e = stop.longitude() + dLng
        val ring = JSONArray(listOf(listOf(w, s), listOf(e, s), listOf(e, n), listOf(w, n), listOf(w, s)).map { JSONArray(it) })
        val polygon = JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
        val filter = JSONArray()
            .put("!")
            .put(
                JSONArray().put("all")
                    .put(JSONArray().put("==").put(JSONArray().put("to-string").put(JSONArray().put("get").put("house_num"))).put(number))
                    .put(JSONArray().put("within").put(polygon))
            )
        return Expression.fromRaw(filter.toString())
    }

    /** Puts the house numbers above the lit building, if it is above them. */
    private fun raiseHouseNumbers(style: Style) {
        val ids = style.styleLayers.map { it.id }
        val lit = ids.indexOf(BUILDING_HIGHLIGHT_LAYER)
        val numbers = ids.indexOf(HOUSE_NUMBER_LAYER)
        if (lit < 0 || numbers < 0 || numbers > lit) return
        style.moveStyleLayer(HOUSE_NUMBER_LAYER, LayerPosition(BUILDING_HIGHLIGHT_LAYER, null, null))
    }

    // ---- bitmaps ------------------------------------------------------

    private fun dp(value: Float) = value * density

    private fun puckBitmap(): Bitmap {
        val size = dp(48f).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(56, 43, 115, 72)
        canvas.drawCircle(c, c, c, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, dp(16f), paint)
        paint.color = GREEN
        canvas.drawCircle(c, c, dp(13f), paint)
        paint.color = Color.WHITE
        val arrow = Path().apply {
            moveTo(c, c - dp(7.5f))
            lineTo(c + dp(6f), c + dp(6.5f))
            lineTo(c, c + dp(3.5f))
            lineTo(c - dp(6f), c + dp(6.5f))
            close()
        }
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    private fun pinBitmap(label: String, current: Boolean): Bitmap {
        val diameter = dp(if (current) 44f else 32f)
        val halo = if (current) dp(6f) else 0f
        val size = (diameter + halo * 2 + dp(4f)).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (current) {
            paint.color = Color.argb(115, 43, 115, 72)
            canvas.drawCircle(c, c, diameter / 2 + halo, paint)
        }
        paint.color = INK
        canvas.drawCircle(c, c, diameter / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, diameter / 2 - dp(1.25f), paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(if (current) 19f else 15f)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, c, c - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** The other driver: a blue disc with a person, and a tag saying who. */
    private fun partnerBitmap(tag: String): Bitmap {
        val disc = dp(32f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(14f)
        paint.textAlign = Paint.Align.CENTER
        val tagWidth = if (tag.isEmpty()) 0f else paint.measureText(tag) + dp(16f)
        val tagHeight = if (tag.isEmpty()) 0f else dp(26f)
        val width = max(disc + dp(4f), tagWidth + dp(4f))
        val height = disc + dp(4f) + if (tag.isEmpty()) 0f else tagHeight + dp(6f)
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2
        val cy = disc / 2 + dp(2f)
        paint.color = ROUTE
        canvas.drawCircle(cx, cy, disc / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, disc / 2 - dp(1.25f), paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy - dp(4.5f), dp(4.5f), paint)
        canvas.drawRoundRect(
            RectF(cx - dp(8f), cy + dp(1.5f), cx + dp(8f), cy + dp(10f)),
            dp(7f), dp(7f), paint,
        )
        if (tag.isNotEmpty()) {
            val rect = RectF(cx - tagWidth / 2, disc + dp(8f), cx + tagWidth / 2, disc + dp(8f) + tagHeight)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
            paint.color = INK
            canvas.drawText(tag, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return bitmap
    }

    /** A rounded tag in [fill] with white text; [pointer] adds a tail pointing down. */
    private fun tagBitmap(text: String, fill: Int, pointer: Boolean): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(if (pointer) 14f else 16f)
        paint.textAlign = Paint.Align.CENTER
        val width = paint.measureText(text) + dp(24f)
        val height = dp(if (pointer) 31f else 36f)
        val tail = if (pointer) dp(9f) else 0f
        val bitmap = Bitmap.createBitmap(
            (width + dp(4f)).toInt(),
            (height + tail + dp(4f)).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val rect = RectF(dp(2f), dp(2f), dp(2f) + width, dp(2f) + height)
        paint.color = fill
        canvas.drawRoundRect(rect, dp(if (pointer) 9f else 12f), dp(if (pointer) 9f else 12f), paint)
        if (pointer) {
            canvas.drawPath(
                Path().apply {
                    moveTo(rect.centerX() - dp(8f), rect.bottom - 1)
                    lineTo(rect.centerX(), rect.bottom + tail)
                    lineTo(rect.centerX() + dp(8f), rect.bottom - 1)
                    close()
                },
                paint,
            )
        }
        paint.color = Color.WHITE
        canvas.drawText(text, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** A signal head: dark, white-edged, red over amber over green. */
    private fun trafficLightBitmap(): Bitmap {
        val width = dp(17f)
        val height = dp(38f)
        val bitmap = Bitmap.createBitmap((width + dp(4f)).toInt(), (height + dp(4f)).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(dp(2f), dp(2f), dp(2f) + width, dp(2f) + height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = TIP
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paint)
        paint.style = Paint.Style.FILL
        val lamps = listOf("#E5483A", "#F0A91B", "#3BB273")
        for ((index, lamp) in lamps.withIndex()) {
            paint.color = Color.parseColor(lamp)
            canvas.drawCircle(rect.centerX(), rect.top + dp(9f) + index * dp(10f), dp(3.8f), paint)
        }
        return bitmap
    }

    /**
     * The stop's house number on the lit building: a small green pill
     * with a white edge, the same on the day and the night map.
     */
    private fun doorBadgeBitmap(label: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(16f)
        paint.textAlign = Paint.Align.CENTER
        val width = max(dp(34f), paint.measureText(label) + dp(16f))
        val height = dp(26f)
        val bitmap = Bitmap.createBitmap((width + dp(6f)).toInt(), (height + dp(6f)).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(dp(3f), dp(3f), dp(3f) + width, dp(3f) + height)
        paint.color = GREEN
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, height / 2, height / 2, paint)
        paint.style = Paint.Style.FILL
        canvas.drawText(label, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** A green callout with the house number, pointing down at the stop. */
    private fun doorBitmap(label: String): Bitmap {
        val text = label.ifEmpty { "Stop" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(20f)
        paint.textAlign = Paint.Align.CENTER
        val textWidth = paint.measureText(text)
        val bubbleWidth = max(dp(52f), textWidth + dp(24f))
        val bubbleHeight = dp(40f)
        val pointer = dp(9f)
        val bitmap = Bitmap.createBitmap(
            (bubbleWidth + dp(4f)).toInt(),
            (bubbleHeight + pointer + dp(4f)).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val rect = RectF(dp(2f), dp(2f), dp(2f) + bubbleWidth, dp(2f) + bubbleHeight)
        paint.color = GREEN
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        val tip = Path().apply {
            moveTo(rect.centerX() - pointer, rect.bottom - 1)
            lineTo(rect.centerX(), rect.bottom + pointer)
            lineTo(rect.centerX() + pointer, rect.bottom - 1)
            close()
        }
        canvas.drawPath(tip, paint)
        paint.color = Color.WHITE
        canvas.drawText(text, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }
}
