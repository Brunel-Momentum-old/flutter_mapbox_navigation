package com.eopeter.fluttermapboxnavigation.models.views

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.eopeter.fluttermapboxnavigation.TurnByTurn
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.mapbox.geojson.Point
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.dropin.map.MapViewBinder
import com.mapbox.navigation.dropin.map.MapViewObserver
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import org.json.JSONObject

class EmbeddedNavigationMapView(
    context: Context,
    activity: Activity,
    binding: NavigationActivityBinding,
    binaryMessenger: BinaryMessenger,
    vId: Int,
    args: Any?,
    accessToken: String
) : PlatformView, TurnByTurn(context, activity, binding, accessToken) {
    private val viewId: Int = vId
    private val messenger: BinaryMessenger = binaryMessenger
    private val arguments = args as Map<*, *>

    // ternFlutterHost — marker for patch_mapbox_avoid_tolls.sh
    private val host: FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            binding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    private var dropInReady = false

    override fun initFlutterChannelHandlers() {
        methodChannel = MethodChannel(messenger, "flutter_mapbox_navigation/${viewId}")
        eventChannel = EventChannel(messenger, "flutter_mapbox_navigation/${viewId}/events")
        super.initFlutterChannelHandlers()
    }

    open fun initialize() {
        initFlutterChannelHandlers()
        host.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!dropInReady && (right - left) >= 200 && (bottom - top) >= 200) {
                dropInReady = true
                host.post { startDropInWhenSized() }
            }
        }
        // If Flutter already sized the host before we registered, kick once.
        host.post {
            if (!dropInReady && host.width >= 200 && host.height >= 200) {
                dropInReady = true
                startDropInWhenSized()
            }
        }
    }

    private fun startDropInWhenSized() {
        initNavigation()

        // TextureView works inside Flutter platform views; SurfaceView stalls at 0x0.
        this.binding.navigationView.customizeViewBinders {
            mapViewBinder = object : MapViewBinder() {
                override fun getMapView(ctx: Context): MapView {
                    val options = MapInitOptions(
                        context = ctx,
                        resourceOptions = MapInitOptions.getDefaultResourceOptions(ctx),
                        textureView = true,
                    )
                    return MapView(ctx, options).apply {
                        compass.enabled = false
                    }
                }
            }
        }

        // Flutter owns top/bottom chrome — Drop-In padding breaks tiny first frames.
        this.binding.navigationView.customizeViewOptions {
            showManeuver = false
            showRoadName = false
            showTripProgress = false
            showInfoPanelInFreeDrive = false
            isInfoPanelHideable = true
            infoPanelForcedState = BottomSheetBehavior.STATE_HIDDEN
            showEndNavigationButton = false
            showStartNavigationButton = false
            showRoutePreviewButton = false
            showActionButtons = false
            showSpeedLimit = false
            showMapScalebar = false
            showPoiName = false
            showArrivalText = false
        }

        if (!(this.arguments?.get("longPressDestinationEnabled") as? Boolean ?: true)) {
            this.binding.navigationView.customizeViewOptions {
                enableMapLongClickIntercept = false
            }
        }

        if ((this.arguments?.get("enableOnMapTapCallback") as? Boolean ?: false)) {
            this.binding.navigationView.registerMapObserver(onMapClick)
        }
    }

    override fun getView(): View {
        return host
    }

    override fun dispose() {
        if ((this.arguments?.get("enableOnMapTapCallback") as? Boolean ?: false)) {
            this.binding.navigationView.unregisterMapObserver(onMapClick)
        }
        unregisterObservers()
    }

    private val onMapClick = object : MapViewObserver(), OnMapClickListener {
        override fun onAttached(mapView: MapView) {
            mapView.gestures.addOnMapClickListener(this)
        }

        override fun onDetached(mapView: MapView) {
            mapView.gestures.removeOnMapClickListener(this)
        }

        override fun onMapClick(point: Point): Boolean {
            val waypoint = mapOf(
                "latitude" to point.latitude().toString(),
                "longitude" to point.longitude().toString(),
            )
            PluginUtilities.sendEvent(MapBoxEvents.ON_MAP_TAP, JSONObject(waypoint).toString())
            return false
        }
    }
}
