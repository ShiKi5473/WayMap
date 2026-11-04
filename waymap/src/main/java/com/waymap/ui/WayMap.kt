package com.waymap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waymap.geo.LatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val TAG = "WayMap"
private const val PROP_ID = "id"
private const val PROP_ICON = "icon"

private fun pointSourceId(layerId: String) = "waymap-pts-src-$layerId"
private fun pointLayerId(layerId: String) = "waymap-pts-$layerId"

object WayMapDefaults {
    /** OpenFreeMap Liberty — free, keyless vector style closest to Google Maps' default. */
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    val TAIPEI = LatLng(25.0330, 121.5654)
    const val ZOOM = 16.0
    /**
     * Label font for markers; must be one the style's glyph server provides. The
     * annotation plugin's default (Open Sans) 404s on OpenFreeMap and hides the marker.
     */
    val LABEL_FONT: List<String> = listOf("Noto Sans Bold")
    /** Hit radius around tappable markers, in dp (zoom-independent). */
    const val MARKER_TAP_RADIUS_DP = 28f
}

/**
 * MapLibre GL Native [MapView] hosted in Compose: GPU-rendered vector tiles with
 * [markers] (few, interactive), [lines] and [pointLayers] (many, static), plus the
 * device location puck.
 *
 * MapLibre loads the map and style asynchronously, so all drawn state lives in a
 * holder; it is reconciled with the latest props both when the style becomes ready
 * and on every recomposition, and no-ops until the style exists.
 *
 * Keep the map composed while other screens are shown (draw them over it) rather
 * than removing it: disposing the MapView while the LocationComponent still has a
 * pending fix can crash inside MapLibre.
 *
 * @param onMarkerTap a tap within [markerTapRadiusDp] of a [MapMarker.tappable] marker.
 * @param onPointTap a tap on a point of a [PointLayer.tappable] layer.
 * @param onTap any other tap.
 * @param onFollowDismissed the user panned the map while [userLocation] was a FOLLOW
 *   mode; the camera stopped following. Set the mode to [UserLocationMode.SHOW] and
 *   offer a way back to FOLLOW (the mode is re-applied only when it changes).
 */
@Composable
fun WayMap(
    modifier: Modifier = Modifier,
    icons: List<MapIcon> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    lines: List<MapLine> = emptyList(),
    pointLayers: List<PointLayer> = emptyList(),
    centerOn: CenterRequest? = null,
    userLocation: UserLocationMode = UserLocationMode.HIDDEN,
    styleUrl: String = WayMapDefaults.STYLE_URL,
    initialCenter: LatLng = WayMapDefaults.TAIPEI,
    initialZoom: Double = WayMapDefaults.ZOOM,
    compass: CompassPlacement? = CompassPlacement(),
    labelFont: List<String> = WayMapDefaults.LABEL_FONT,
    markerTapRadiusDp: Float = WayMapDefaults.MARKER_TAP_RADIUS_DP,
    onTap: (LatLng) -> Unit = {},
    onLongPress: (LatLng) -> Unit = {},
    onMarkerTap: (id: String) -> Unit = {},
    onPointTap: (layerId: String, pointId: String) -> Unit = { _, _ -> },
    onFollowDismissed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val props = MapProps(icons, markers, lines, pointLayers, centerOn, userLocation, labelFont)
    // Latest props/callbacks for the async style-ready callback and the map listeners.
    val currentProps = rememberUpdatedState(props)
    val currentTapRadius = rememberUpdatedState(markerTapRadiusDp)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnLongPress = rememberUpdatedState(onLongPress)
    val currentOnMarkerTap = rememberUpdatedState(onMarkerTap)
    val currentOnPointTap = rememberUpdatedState(onPointTap)
    val currentOnFollowDismissed = rememberUpdatedState(onFollowDismissed)

    val mapView = remember {
        // MapLibre must be initialized before any MapView is constructed.
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }
    val holder = remember { MapHolder() }
    holder.onFollowDismissed = { currentOnFollowDismissed.value() }

    // Mirror the host lifecycle onto the MapView (MapLibre needs the full set).
    // onCreate is called in the factory below, before getMapAsync, to guarantee order.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.onCreate(null)
            mapView.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(MlLatLng(initialCenter.lat, initialCenter.lon))
                    .zoom(initialZoom)
                    .build()
                val density = context.resources.displayMetrics.density
                map.uiSettings.apply {
                    isCompassEnabled = compass != null
                    if (compass != null) {
                        compassGravity = compass.gravity
                        setCompassMargins(
                            (compass.startDp * density).toInt(), (compass.topDp * density).toInt(),
                            (compass.endDp * density).toInt(), (compass.bottomDp * density).toInt(),
                        )
                    }
                }
                map.addOnMapLongClickListener { point ->
                    currentOnLongPress.value(LatLng(point.latitude, point.longitude))
                    true
                }
                map.addOnMapClickListener { point ->
                    handleTap(
                        map, holder, currentProps.value, point,
                        radiusPx = currentTapRadius.value * density,
                        onMarkerTap = currentOnMarkerTap.value,
                        onPointTap = currentOnPointTap.value,
                        onTap = currentOnTap.value,
                    )
                    true
                }
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    // A style-loaded callback that throws is rethrown into MapLibre's
                    // native code and hard-aborts the process with an opaque message, so
                    // log any failure here rather than letting it crash the app.
                    try {
                        if (holder.released) return@setStyle
                        holder.map = map
                        holder.style = style
                        holder.lineManager = LineManager(mapView, map, style)
                        holder.symbolManager = SymbolManager(mapView, map, style).apply {
                            iconAllowOverlap = true
                            textAllowOverlap = true
                            // If a label can't be laid out (e.g. its font fails to load),
                            // still draw the icon instead of dropping the whole marker.
                            textOptional = true
                        }
                        // Draw whatever state exists now that the managers are ready.
                        applyState(context, holder, currentProps.value)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to set up map style/annotations", t)
                    }
                }
            }
            mapView
        },
        update = {
            try {
                applyState(context, holder, props)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update map", t)
            }
        },
        onRelease = {
            holder.released = true
            holder.symbolManager?.onDestroy()
            holder.lineManager?.onDestroy()
            holder.map = null
            holder.style = null
            holder.symbolManager = null
            holder.lineManager = null
            it.onDestroy()
        },
    )
}

/** Snapshot of the props that describe what should be drawn. */
private class MapProps(
    val icons: List<MapIcon>,
    val markers: List<MapMarker>,
    val lines: List<MapLine>,
    val pointLayers: List<PointLayer>,
    val centerOn: CenterRequest?,
    val userLocation: UserLocationMode,
    val labelFont: List<String>,
)

/** Async-populated map objects plus everything currently drawn on them. */
private class MapHolder {
    var released = false
    var map: MapLibreMap? = null
    var style: Style? = null
    var symbolManager: SymbolManager? = null
    var lineManager: LineManager? = null

    val registeredIcons = HashSet<String>()
    val markers = LinkedHashMap<String, MapMarker>()
    val symbols = HashMap<String, Symbol>()
    val lines = LinkedHashMap<String, MapLine>()
    /** Per line id: optional casing (drawn first, underneath) and the line itself. */
    val lineAnnotations = HashMap<String, Pair<Line?, Line>>()
    val pointLayers = LinkedHashMap<String, PointLayer>()

    var lastCenter: CenterRequest? = null
    var locationActivated = false
    var appliedLocationMode = UserLocationMode.HIDDEN
    var didCenterOnUser = false
    var onFollowDismissed: () -> Unit = {}
}

/**
 * Routes a tap: the nearest tappable marker within [radiusPx] (in screen pixels, so
 * the hit area is zoom-independent) wins; otherwise the nearest point of the topmost
 * tappable point layer; otherwise it is a plain map tap.
 */
private fun handleTap(
    map: MapLibreMap,
    h: MapHolder,
    p: MapProps,
    point: MlLatLng,
    radiusPx: Float,
    onMarkerTap: (String) -> Unit,
    onPointTap: (String, String) -> Unit,
    onTap: (LatLng) -> Unit,
) {
    val proj = map.projection
    val tap = proj.toScreenLocation(point)
    val radiusSq = radiusPx * radiusPx
    fun distSq(lat: Double, lon: Double): Float {
        val sp = proj.toScreenLocation(MlLatLng(lat, lon))
        val dx = sp.x - tap.x
        val dy = sp.y - tap.y
        return dx * dx + dy * dy
    }

    var bestMarker: String? = null
    var bestMarkerDist = Float.MAX_VALUE
    for (m in p.markers) {
        if (!m.tappable) continue
        val d2 = distSq(m.pos.lat, m.pos.lon)
        if (d2 < bestMarkerDist) { bestMarkerDist = d2; bestMarker = m.id }
    }
    if (bestMarker != null && bestMarkerDist <= radiusSq) {
        onMarkerTap(bestMarker)
        return
    }

    val rect = RectF(tap.x - radiusPx, tap.y - radiusPx, tap.x + radiusPx, tap.y + radiusPx)
    for (layer in p.pointLayers.asReversed()) {
        if (!layer.tappable || layer.id !in h.pointLayers) continue
        val hit = map.queryRenderedFeatures(rect, pointLayerId(layer.id))
            .mapNotNull { f ->
                val pt = f.geometry() as? Point ?: return@mapNotNull null
                val id = f.getStringProperty(PROP_ID) ?: return@mapNotNull null
                id to distSq(pt.latitude(), pt.longitude())
            }
            .minByOrNull { it.second }
        if (hit != null) {
            onPointTap(layer.id, hit.first)
            return
        }
    }

    onTap(LatLng(point.latitude, point.longitude))
}

/** Reconciles the map with [p]; no-ops until the style/managers are ready. */
private fun applyState(context: Context, h: MapHolder, p: MapProps) {
    val map = h.map ?: return
    val style = h.style ?: return
    val symbols = h.symbolManager ?: return
    val lines = h.lineManager ?: return

    registerIcons(style, h, p.icons)
    applyLines(h, lines, p.lines)
    applyPointLayers(style, h, symbols, p.pointLayers)
    applyMarkers(h, symbols, p.markers, p.labelFont)

    // Recenter only when a new center is requested.
    val centerOn = p.centerOn
    if (centerOn != null && centerOn !== h.lastCenter) {
        val ll = MlLatLng(centerOn.point.lat, centerOn.point.lon)
        val zoom = centerOn.zoom
        map.animateCamera(
            if (zoom != null) CameraUpdateFactory.newLatLngZoom(ll, maxOf(zoom, map.cameraPosition.zoom))
            else CameraUpdateFactory.newLatLng(ll)
        )
        h.lastCenter = centerOn
    }

    updateUserLocation(context, h, map, style, p.userLocation)
}

private fun registerIcons(style: Style, h: MapHolder, icons: List<MapIcon>) {
    for (icon in icons) {
        if (icon.id in h.registeredIcons) continue
        try {
            style.addImage(icon.id, icon.factory())
            h.registeredIcons += icon.id
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register icon ${icon.id}", t)
        }
    }
}

private fun applyMarkers(h: MapHolder, symbols: SymbolManager, next: List<MapMarker>, font: List<String>) {
    val diff = diffById(h.markers, next) { it.id }
    if (diff.added.isEmpty() && diff.changed.isEmpty() && diff.removed.isEmpty()) return

    val toDelete = ArrayList<Symbol>()
    for (id in diff.removed) {
        h.symbols.remove(id)?.let(toDelete::add)
        h.markers.remove(id)
    }

    val toUpdate = ArrayList<Symbol>()
    val toCreate = ArrayList<MapMarker>(diff.added)
    for ((old, new) in diff.changed) {
        val s = h.symbols[new.id]
        // Moving / re-skinning is done in place; anything else (label, order) is recreated.
        if (s != null && old.copy(pos = new.pos, icon = new.icon, tappable = new.tappable) == new) {
            s.latLng = MlLatLng(new.pos.lat, new.pos.lon)
            s.iconImage = new.icon
            toUpdate += s
            h.markers[new.id] = new
        } else {
            s?.let(toDelete::add)
            h.symbols.remove(new.id)
            toCreate += new
        }
    }

    if (toDelete.isNotEmpty()) symbols.delete(toDelete)
    if (toUpdate.isNotEmpty()) symbols.update(toUpdate)
    if (toCreate.isNotEmpty()) {
        val created = symbols.create(toCreate.map { symbolOptions(it, font) })
        toCreate.forEachIndexed { i, m ->
            h.symbols[m.id] = created[i]
            h.markers[m.id] = m
        }
    }
}

private fun symbolOptions(m: MapMarker, font: List<String>): SymbolOptions =
    SymbolOptions()
        .withLatLng(MlLatLng(m.pos.lat, m.pos.lon))
        .withIconImage(m.icon)
        .withSymbolSortKey(m.sortKey)
        .apply {
            if (m.label != null) {
                withTextField(m.label)
                withTextFont(font.toTypedArray())
                withTextColor(m.labelColor)
                withTextSize(m.labelSize)
                withTextHaloColor(m.labelHaloColor)
                withTextHaloWidth(m.labelHaloWidth)
            }
        }

private fun applyLines(h: MapHolder, manager: LineManager, next: List<MapLine>) {
    val diff = diffById(h.lines, next) { it.id }
    fun remove(id: String) {
        h.lineAnnotations.remove(id)?.let { (casing, line) ->
            casing?.let(manager::delete)
            manager.delete(line)
        }
        h.lines.remove(id)
    }
    fun add(l: MapLine) {
        // A line needs two points; keep the id tracked so a later update can draw it.
        if (l.points.size >= 2) {
            val latLngs = l.points.map { MlLatLng(it.lat, it.lon) }
            // Casing first so the coloured line is drawn on top of it.
            val casing = l.casingColor?.let { c ->
                manager.create(LineOptions().withLatLngs(latLngs).withLineColor(c).withLineWidth(l.casingWidth))
            }
            val line = manager.create(
                LineOptions().withLatLngs(latLngs).withLineColor(l.color).withLineWidth(l.width)
            )
            h.lineAnnotations[l.id] = casing to line
        }
        h.lines[l.id] = l
    }
    diff.removed.forEach(::remove)
    for ((_, new) in diff.changed) {
        remove(new.id)
        add(new)
    }
    diff.added.forEach(::add)
}

private fun applyPointLayers(style: Style, h: MapHolder, symbols: SymbolManager, next: List<PointLayer>) {
    val diff = diffById(h.pointLayers, next) { it.id }
    for (id in diff.removed) {
        style.removeLayer(pointLayerId(id))
        style.removeSource(pointSourceId(id))
        h.pointLayers.remove(id)
    }
    for ((old, new) in diff.changed) {
        if (old.points != new.points) {
            style.getSourceAs<GeoJsonSource>(pointSourceId(new.id))?.setGeoJson(featureCollection(new.points))
        }
        (style.getLayer(pointLayerId(new.id)) as? SymbolLayer)?.let { layer ->
            if (old.minZoom != new.minZoom) layer.minZoom = new.minZoom
            if (old.iconSize != new.iconSize) layer.setProperties(PropertyFactory.iconSize(new.iconSize))
        }
        h.pointLayers[new.id] = new
    }
    for (layer in diff.added) {
        style.addSource(GeoJsonSource(pointSourceId(layer.id), featureCollection(layer.points)))
        val symbolLayer = SymbolLayer(pointLayerId(layer.id), pointSourceId(layer.id)).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(layer.iconSize),
        )
        symbolLayer.minZoom = layer.minZoom
        // Directly below the marker layer: above lines, under interactive markers.
        // Later layers are inserted after earlier ones, so list order is draw order.
        style.addLayerBelow(symbolLayer, symbols.layerId)
        h.pointLayers[layer.id] = layer
    }
}

private fun featureCollection(points: List<MapPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(
        points.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.pos.lon, p.pos.lat)).also {
                it.addStringProperty(PROP_ID, p.id)
                it.addStringProperty(PROP_ICON, p.icon)
            }
        }
    )

/** Enables/disables the MapLibre LocationComponent and applies the follow mode. */
@Suppress("MissingPermission")
private fun updateUserLocation(
    context: Context,
    h: MapHolder,
    map: MapLibreMap,
    style: Style,
    mode: UserLocationMode,
) {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (mode != UserLocationMode.HIDDEN && granted) {
        val lc = map.locationComponent
        if (!h.locationActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).build()
            )
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = h.onFollowDismissed()
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            h.locationActivated = true
        }
        if (!lc.isLocationComponentEnabled) lc.isLocationComponentEnabled = true
        if (mode == h.appliedLocationMode) return
        h.appliedLocationMode = mode
        when (mode) {
            UserLocationMode.SHOW -> {
                lc.cameraMode = CameraMode.NONE   // never hijack the camera
                lc.renderMode = RenderMode.NORMAL
                // Center on the user only on the first fix — don't take over the camera afterwards.
                if (!h.didCenterOnUser) {
                    lc.locationEngine?.getLastLocation(object : LocationEngineCallback<LocationEngineResult> {
                        override fun onSuccess(result: LocationEngineResult?) {
                            val loc = result?.lastLocation ?: return
                            if (h.didCenterOnUser || h.released) return
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(MlLatLng(loc.latitude, loc.longitude), 16.0)
                            )
                            h.didCenterOnUser = true
                        }
                        override fun onFailure(exception: Exception) {}
                    })
                }
            }
            UserLocationMode.FOLLOW -> {
                lc.cameraMode = CameraMode.TRACKING_GPS_NORTH
                lc.renderMode = RenderMode.NORMAL
                h.didCenterOnUser = true
            }
            UserLocationMode.FOLLOW_BEARING -> {
                lc.cameraMode = CameraMode.TRACKING_GPS
                lc.renderMode = RenderMode.GPS
                h.didCenterOnUser = true
            }
            UserLocationMode.HIDDEN -> Unit
        }
    } else if (h.locationActivated && h.appliedLocationMode != UserLocationMode.HIDDEN) {
        map.locationComponent.isLocationComponentEnabled = false
        h.appliedLocationMode = UserLocationMode.HIDDEN
    }
}
