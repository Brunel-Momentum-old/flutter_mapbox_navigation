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
import android.view.Gravity
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.delegates.listeners.OnStyleLoadedListener
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolygonAnnotationManager
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.navigation.dropin.NavigationView
import com.mapbox.navigation.ui.maps.building.api.MapboxBuildingsApi
import com.mapbox.navigation.ui.maps.building.model.MapboxBuildingHighlightOptions
import com.mapbox.navigation.ui.maps.building.view.MapboxBuildingView
import com.mapbox.navigation.ui.maps.puck.LocationPuckOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineResources
import kotlin.math.PI
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
        val TRAVELLED = Color.parseColor("#B9B6AE")
        val GREEN = Color.parseColor("#2B7348")
        val BUILDING_FILL = Color.parseColor("#BFDCCB")
        val INK = Color.parseColor("#2B2B2B")
        private const val HOUSE_NUMBER_LAYER = "host-house-numbers"

        /** Clear of the host's Sound and Route buttons on the right. */
        private const val ORNAMENT_RIGHT_DP = 96f

        // The route line goes under the first of these the style has: under
        // street names where it can, and under the puck whatever happens.
        // Left to itself it was drawn last, on top of the puck.
        private val ROUTE_LINE_ANCHORS = listOf(
            "road-label-navigation",
            "road-label",
            "road-label-simple",
            "mapbox-location-indicator-layer",
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
            field = value
            placeOrnaments()
        }

    /** Dark map in use: labels this class adds switch to light on dark. */
    var night = false

    private var routeLineAnchor = "road-label-simple"
    private val styleLoaded = OnStyleLoadedListener {
        mapView?.getMapboxMap()?.getStyle { onStyle(it) }
    }

    val isDoorView: Boolean get() = doorEnabled

    /** Route line colours and the puck. Call before guidance starts. */
    fun applyStyle() {
        val colors = RouteLineColorResources.Builder()
            .routeDefaultColor(ROUTE)
            .routeUnknownCongestionColor(ROUTE)
            .routeLowCongestionColor(ROUTE)
            .routeModerateCongestionColor(TRAFFIC_MODERATE)
            .routeHeavyCongestionColor(TRAFFIC_HEAVY)
            .routeSevereCongestionColor(TRAFFIC_SEVERE)
            .routeCasingColor(Color.WHITE)
            .routeLineTraveledColor(TRAVELLED)
            .routeLineTraveledCasingColor(Color.WHITE)
            .build()
        val puck = LocationPuck2D(
            bearingImage = BitmapDrawable(context.resources, puckBitmap())
        )
        routeLineResources = RouteLineResources.Builder()
            .routeLineColorResources(colors)
            .originWaypointIcon(R.drawable.host_no_waypoint)
            .destinationWaypointIcon(R.drawable.host_no_waypoint)
            .build()
        navigationView.customizeViewOptions {
            routeLineOptions = routeLineOptions()
            enableBuildingHighlightOnArrival = false
        }
        navigationView.customizeViewStyles {
            locationPuckOptions = LocationPuckOptions.Builder(context)
                .defaultPuck(puck)
                .build()
            // The stop is marked with the host's own numbered pin, so
            // Drop-In's stock marker is swapped for nothing.
            destinationMarkerAnnotationOptions = PointAnnotationOptions()
                .withIconImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
    }

    private var routeLineResources: RouteLineResources? = null

    private fun routeLineOptions(): MapboxRouteLineOptions {
        val builder = MapboxRouteLineOptions.Builder(context)
        routeLineResources?.let { builder.withRouteLineResources(it) }
        return builder
            .withRouteLineBelowLayerId(routeLineAnchor)
            .withVanishingRouteLineEnabled(true)
            .build()
    }

    /** Runs for the style in place and for every style loaded after it. */
    private fun onStyle(style: Style) {
        val anchor = ROUTE_LINE_ANCHORS.firstOrNull { style.styleLayerExists(it) }
        if (anchor != null && anchor != routeLineAnchor) {
            routeLineAnchor = anchor
            navigationView.customizeViewOptions { routeLineOptions = routeLineOptions() }
        }
        // A new style drops the layers this class added.
        if (doorEnabled) addHouseNumbers()
    }

    fun onMapAttached(view: MapView) {
        mapView = view
        buildingsApi = MapboxBuildingsApi(view.getMapboxMap())
        pinManager = null
        doorPointManager = null
        doorPolygonManager = null
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
        buildingsApi?.cancel()
        buildingsApi = null
        mapView = null
        pinManager = null
        doorPointManager = null
        doorPolygonManager = null
    }

    // ---- stop pins ----------------------------------------------------

    fun setStopPins(next: List<Map<*, *>>) {
        pins = next
        refreshPins()
    }

    fun onCameraState(state: String) {
        overview = state == "overview"
        refreshPins()
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
        if (entering) {
            addHouseNumbers()
            refreshPins()
        }
    }

    fun leaveDoorView() {
        if (!doorEnabled) return
        doorEnabled = false
        mapView?.getMapboxMap()?.getStyle { buildingView.removeBuildingHighlight(it, highlightOptions) }
        doorPointManager?.deleteAll()
        doorPolygonManager?.deleteAll()
        refreshPins()
    }

    /** Runs on every location update while the door view is on. */
    fun onLocation(location: Location) {
        if (!doorEnabled) return
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
                    view.getMapboxMap().getStyle { buildingView.highlightBuilding(it, buildings, highlightOptions) }
                }
                // No footprint under the pin once it has had time to
                // come on screen: mark the spot instead of the building.
                showDoorMarker(destination, ring = buildings.isEmpty() && attempt >= 4)
            }
        }
    }

    private fun showDoorMarker(point: Point, ring: Boolean) {
        val view = mapView ?: return
        val polygons = doorPolygonManager ?: view.annotations.createPolygonAnnotationManager().also {
            doorPolygonManager = it
        }
        val points = doorPointManager ?: view.annotations.createPointAnnotationManager().also {
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
        points.create(
            PointAnnotationOptions()
                .withPoint(point)
                .withIconImage(doorBitmap(doorLabel?.trim().orEmpty()))
                .withIconAnchor(IconAnchor.BOTTOM)
        )
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

    /** House numbers from the street data the base style already carries. */
    private fun addHouseNumbers() {
        mapView?.getMapboxMap()?.getStyle { style ->
            if (style.styleLayerExists(HOUSE_NUMBER_LAYER) || !style.styleSourceExists("composite")) {
                return@getStyle
            }
            style.addLayer(
                symbolLayer(HOUSE_NUMBER_LAYER, "composite") {
                    sourceLayer("housenum_label")
                    minZoom(16.5)
                    textField(get { literal("house_num") })
                    textSize(15.0)
                    textColor(if (night) Color.WHITE else INK)
                    textHaloColor(if (night) INK else Color.WHITE)
                    textHaloWidth(1.5)
                }
            )
        }
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
