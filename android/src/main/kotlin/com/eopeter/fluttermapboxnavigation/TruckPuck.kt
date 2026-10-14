package com.eopeter.fluttermapboxnavigation

import android.content.Context
import android.util.Log
import com.mapbox.maps.plugin.LocationPuck
import com.mapbox.maps.plugin.LocationPuck3D
import io.flutter.FlutterInjector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The driver's marker on the map: the host's pin, or a 3D model of the
 * host's van (`setMarker`). iOS has the same in `TruckPuck.swift`.
 *
 * The model is a glTF binary in the host's Flutter assets: metres, +Y up,
 * front towards +Z, origin on the ground under its middle. The host sends
 * how big to draw it at each zoom (`scale`, a style expression as JSON).
 */
data class DriverMarker(
    val truck: Boolean = false,
    val modelUri: String? = null,
    val scale: String? = null,
    val rotation: List<Float> = listOf(0f, 0f, 0f),
) {
    val showsTruck: Boolean get() = truck && modelUri != null

    /** The 3D puck, or null when this marker is the pin or cannot be built. */
    fun puck3D(): LocationPuck? {
        val uri = modelUri ?: return null
        if (!truck) return null
        return try {
            LocationPuck3D(
                modelUri = uri,
                modelScaleExpression = scale,
                modelRotation = rotation,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "truck marker could not be built; using the pin", t)
            null
        }
    }

    companion object {
        private const val TAG = "TruckPuck"

        /**
         * From `setMarker`'s arguments. Nothing in here throws: a model
         * that is missing or is not a glTF binary leaves the pin.
         */
        fun from(context: Context, args: Map<*, *>?): DriverMarker {
            return try {
                val truck = args?.get("marker") == "truck"
                val asset = args?.get("modelAsset") as? String
                val uri = asset?.let { modelUri(context, it) }
                val rotation = (args?.get("rotation") as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toFloat() }
                    ?.takeIf { it.size == 3 }
                    ?: listOf(0f, 0f, 0f)
                DriverMarker(truck, uri, args?.get("scale") as? String, rotation)
            } catch (t: Throwable) {
                Log.w(TAG, "bad marker arguments; using the pin", t)
                DriverMarker()
            }
        }

        /** `asset://` URI of a Flutter asset, if it is a readable glTF binary. */
        private fun modelUri(context: Context, asset: String): String? {
            val key = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(asset)
            return try {
                context.assets.open(key).use { input ->
                    val header = ByteArray(12)
                    if (input.read(header) != 12) return null
                    val words = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    if (words.getInt(0) != 0x46546C67 || words.getInt(4) != 2) {
                        Log.w(TAG, "marker model is not a glTF binary: $asset; using the pin")
                        return null
                    }
                }
                "asset://$key"
            } catch (t: Throwable) {
                Log.w(TAG, "marker model missing: $asset; using the pin")
                null
            }
        }
    }
}
