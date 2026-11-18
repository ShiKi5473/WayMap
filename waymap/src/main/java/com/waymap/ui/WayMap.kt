package com.waymap.ui

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
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
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TAG = "WayMap"
private const val PROP_ID = "id"
private const val PROP_ICON = "icon"
private const val PROP_COLOR = "color"
private const val PROP_WIDTH = "width"
private const val PROP_CASING_COLOR = "casingColor"
private const val PROP_CASING_WIDTH = "casingWidth"
private const val PROP_ORDER = "order"

/** Prefix of every source/layer this library adds to the style. */
private const val OWN_PREFIX = "waymap-"
private const val POINT_LAYER_PREFIX = "${OWN_PREFIX}pts-"
private const val LINES_SOURCE_ID = "${OWN_PREFIX}lines-src"
private const val LINES_CASING_LAYER_ID = "${OWN_PREFIX}lines-casing"
private const val LINES_LAYER_ID = "${OWN_PREFIX}lines"
/** Id prefix of the LocationComponent's own layers (it adds SymbolLayers too). */
private const val LOCATION_LAYER_PREFIX = "mapbox-location"

/** Durations of the tilt / zoom camera animations while following, in ms. */
private const val FOLLOW_TILT_MS = 750L
private const val FOLLOW_ZOOM_MS = 1000L
/** Re-send an unchanged app-pushed fix after this long, so the puck never goes stale. */
private const val FIX_REPUSH_MS = 10_000L

private fun pointSourceId(layerId: String) = "${OWN_PREFIX}pts-src-$layerId"
private fun pointLayerId(layerId: String) = "$POINT_LAYER_PREFIX$layerId"

object WayMapDefaults {
    /** OpenFreeMap Liberty — free, keyless vector style closest to Google Maps' default. */
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    /** OpenFreeMap Dark — keyless dark counterpart of [STYLE_URL]. */
    const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"
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
 * @param fitTo a one-shot camera move framing a set of points; applied when the
 *   instance changes (and once the view has a size). Don't combine with a FOLLOW mode.
 * @param userFix a location pushed by the app. While non-null (and [userLocation] is
 *   not HIDDEN) the puck shows it instead of the device location MapLibre reads itself.
 * @param followZoomForSpeed in [UserLocationMode.FOLLOW_BEARING], maps the current
 *   speed (km/h) to a zoom level; changes are throttled (>= 0.25 zoom apart, >= 3 s
 *   between changes). Speed comes from [userFix], else the location component.
 * @param followTilt camera tilt in degrees while in [UserLocationMode.FOLLOW_BEARING];
 *   tilted back to 0 when leaving that mode.
 * @param styleUrl changing it reloads the style; markers, lines and point layers are
 *   redrawn on the new style.
 * @param linesBelowLabels draw [lines] under the basemap's labels (below its first
 *   symbol layer). When false, or the style has none, they sit under the point layers.
 * @param onMapLoadError the map or its style failed to load (e.g. offline with an
 *   empty cache); the message comes from MapLibre.
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
    fitTo: FitRequest? = null,
    userFix: UserFix? = null,
    followZoomForSpeed: ((speedKmh: Double) -> Double)? = null,
    followTilt: Double = 0.0,
    linesBelowLabels: Boolean = true,
    onMapLoadError: (message: String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val props = MapProps(
        icons, markers, lines, pointLayers, centerOn, userLocation, labelFont, styleUrl,
        fitTo, userFix, followZoomForSpeed, followTilt, linesBelowLabels,
    )
    // Latest props/callbacks for the async style-ready callback and the map listeners.
    val currentProps = rememberUpdatedState(props)
    val currentTapRadius = rememberUpdatedState(markerTapRadiusDp)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnLongPress = rememberUpdatedState(onLongPress)
    val currentOnMarkerTap = rememberUpdatedState(onMarkerTap)
    val currentOnPointTap = rememberUpdatedState(onPointTap)
    val currentOnFollowDismissed = rememberUpdatedState(onFollowDismissed)
    val currentOnMapLoadError = rememberUpdatedState(onMapLoadError)

    val mapView = remember {
        // MapLibre must be initialized before any MapView is constructed.
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }
    val holder = remember { MapHolder().also { it.mapView = mapView } }
    holder.onFollowDismissed = { currentOnFollowDismissed.value() }
    // Lets deferred work (e.g. a fit waiting for layout) re-run the reconciliation.
    holder.requestApply = {
        try {
            applyState(context, holder, currentProps.value)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update map", t)
        }
    }

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

    // Let MapLibre drop its tile/render caches when the system runs low on memory.
    DisposableEffect(context) {
        val appContext = context.applicationContext
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // UI_HIDDEN only means the app went to the background (e.g. guarding
                // behind a navigation app): not memory pressure, keep the caches.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                    level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) {
                    mapView.onLowMemory()
                }
            }
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        appContext.registerComponentCallbacks(callbacks)
        onDispose { appContext.unregisterComponentCallbacks(callbacks) }
    }

    /** Loads [url] into [map]; the drawn state is rebuilt once the new style is ready. */
    fun loadStyle(map: MapLibreMap, url: String) {
        holder.requestedStyleUrl = url
        // Nothing may touch the old style while the new one loads.
        holder.resetForNewStyle()
        map.setStyle(Style.Builder().fromUri(url)) { style ->
            // A style-loaded callback that throws is rethrown into MapLibre's
            // native code and hard-aborts the process with an opaque message, so
            // log any failure here rather than letting it crash the app.
            try {
                if (holder.released) return@setStyle
                holder.map = map
                holder.style = style
                holder.loadedStyleUrl = url
                holder.symbolManager = SymbolManager(mapView, map, style).apply {
                    iconAllowOverlap = true
                    textAllowOverlap = true
                    // If a label can't be laid out (e.g. its font fails to load),
                    // still draw the icon instead of dropping the whole marker.
                    textOptional = true
                }
                // Draw whatever state exists now that the manager is ready.
                applyState(context, holder, currentProps.value)
                // The style URL may have changed while this one was loading.
                val wanted = currentProps.value.styleUrl
                if (wanted != url) loadStyle(map, wanted)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set up map style/annotations", t)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.onCreate(null)
            mapView.addOnDidFailLoadingMapListener { message ->
                currentOnMapLoadError.value(message)
            }
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
                loadStyle(map, currentProps.value.styleUrl)
            }
            mapView
        },
        update = {
            try {
                // Switch style at runtime (the first load is started by the factory).
                val map = holder.map
                if (map != null && !holder.released && holder.requestedStyleUrl != props.styleUrl) {
                    loadStyle(map, props.styleUrl)
                }
                applyState(context, holder, props)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update map", t)
            }
        },
        onRelease = {
            holder.released = true
            holder.symbolManager?.onDestroy()
            holder.map = null
            holder.style = null
            holder.symbolManager = null
            holder.mapView = null
            holder.requestApply = {}
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
    val styleUrl: String,
    val fitTo: FitRequest?,
    val userFix: UserFix?,
    val followZoomForSpeed: ((Double) -> Double)?,
    val followTilt: Double,
    val linesBelowLabels: Boolean,
)

/** Async-populated map objects plus everything currently drawn on them. */
private class MapHolder {
    var released = false
    var map: MapLibreMap? = null
    var style: Style? = null
    var symbolManager: SymbolManager? = null
    var mapView: MapView? = null
    /** Re-runs the reconciliation with the latest props; set by the composable. */
    var requestApply: () -> Unit = {}
    private var retryPosted = false

    /** Style URL last asked for / the one actually loaded into [style]. */
    var requestedStyleUrl: String? = null
    var loadedStyleUrl: String? = null

    val registeredIcons = HashSet<String>()
    val markers = LinkedHashMap<String, MapMarker>()
    val symbols = HashMap<String, Symbol>()
    /** Lines as last uploaded to the lines source; null until the layers exist. */
    var appliedLines: List<MapLine>? = null
    var linesLayersAdded = false
    var linesPlacedBelowLabels = true
    val pointLayers = LinkedHashMap<String, PointLayer>()

    var lastCenter: CenterRequest? = null
    var lastFit: FitRequest? = null
    var locationActivated = false
    var appliedLocationMode = UserLocationMode.HIDDEN
    var didCenterOnUser = false
    var onFollowDismissed: () -> Unit = {}

    /** The engine the LocationComponent had right after activation. */
    var defaultEngine: LocationEngine? = null
    /** True while the engine is detached because the app pushes the location. */
    var engineDetached = false
    var lastPushedFix: UserFix? = null
    /** [SystemClock.elapsedRealtime] of the last push of [lastPushedFix]. */
    var lastPushMs = 0L
    /** [SystemClock.elapsedRealtime] of the last automatic follow-zoom change. */
    var lastAutoZoomMs: Long? = null
    /** Whether [MapProps.followTilt] is currently applied and must be undone on exit. */
    var tiltApplied = false

    /**
     * Forgets everything drawn on the old style: it is discarded by MapLibre, taking
     * its images, layers and sources along. Camera requests and location bookkeeping
     * are kept; the pushed fix is re-sent because the puck layers are rebuilt.
     */
    fun resetForNewStyle() {
        symbolManager?.onDestroy()
        symbolManager = null
        style = null
        loadedStyleUrl = null
        registeredIcons.clear()
        markers.clear()
        symbols.clear()
        appliedLines = null
        linesLayersAdded = false
        pointLayers.clear()
        lastPushedFix = null
    }

    /** Re-runs [requestApply] shortly, once; used while the view has no size yet. */
    fun retryLater() {
        if (retryPosted) return
        val view = mapView ?: return
        retryPosted = true
        view.postDelayed({
            retryPosted = false
            if (!released) requestApply()
        }, 100)
    }
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

    registerIcons(style, h, p.icons)
    applyPointLayers(style, h, symbols, p.pointLayers)
    applyLines(style, h, symbols, p.lines, p.linesBelowLabels)
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

    // Refit only when a new fit is requested.
    val fitTo = p.fitTo
    if (fitTo != null && fitTo !== h.lastFit) applyFit(map, h, fitTo)

    updateUserLocation(context, h, map, style, p)
}

/** Frames [fit]'s points; waits (and retries) while the view has no size yet. */
private fun applyFit(map: MapLibreMap, h: MapHolder, fit: FitRequest) {
    val points = fit.points
    if (points.isEmpty()) {
        h.lastFit = fit
        return
    }
    val view = h.mapView
    if (view == null || view.width <= 0 || view.height <= 0) {
        // Not laid out yet: the camera math needs the viewport size.
        h.retryLater()
        return
    }
    h.lastFit = fit
    try {
        val first = points[0]
        val update = if (points.all { it == first }) {
            CameraUpdateFactory.newLatLngZoom(MlLatLng(first.lat, first.lon), fit.maxZoom)
        } else {
            val bounds = LatLngBounds.Builder().apply {
                for (pt in points) include(MlLatLng(pt.lat, pt.lon))
            }.build()
            val pad = (fit.paddingDp * view.resources.displayMetrics.density).toInt()
            val cam = map.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad)) ?: return
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cam).zoom(minOf(cam.zoom, fit.maxZoom)).build()
            )
        }
        if (fit.animate) map.animateCamera(update) else map.moveCamera(update)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to fit camera to points", t)
    }
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

/**
 * Draws [next] from one GeoJSON source through two line layers (casing underneath,
 * line on top). The source is re-uploaded only when the list changed.
 */
private fun applyLines(
    style: Style,
    h: MapHolder,
    symbols: SymbolManager,
    next: List<MapLine>,
    belowLabels: Boolean,
) {
    // Nothing drawn and nothing to draw: don't add layers for an empty list.
    if (!h.linesLayersAdded && next.none { it.points.size >= 2 }) return

    // Placement changed: rebuild the layers at the new position.
    if (h.linesLayersAdded && h.linesPlacedBelowLabels != belowLabels) {
        style.removeLayer(LINES_LAYER_ID)
        style.removeLayer(LINES_CASING_LAYER_ID)
        style.removeSource(LINES_SOURCE_ID)
        h.linesLayersAdded = false
        h.appliedLines = null
    }

    if (!h.linesLayersAdded) {
        style.addSource(GeoJsonSource(LINES_SOURCE_ID, linesCollection(next)))
        val casing = LineLayer(LINES_CASING_LAYER_ID, LINES_SOURCE_ID)
            .withFilter(Expression.has(PROP_CASING_COLOR))
            .withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(PROP_CASING_COLOR))),
                PropertyFactory.lineWidth(Expression.toNumber(Expression.get(PROP_CASING_WIDTH))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineSortKey(Expression.toNumber(Expression.get(PROP_ORDER))),
            )
        val line = LineLayer(LINES_LAYER_ID, LINES_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get(PROP_COLOR))),
            PropertyFactory.lineWidth(Expression.toNumber(Expression.get(PROP_WIDTH))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineSortKey(Expression.toNumber(Expression.get(PROP_ORDER))),
        )
        // Casing first, then the line directly below the same anchor: line is above casing.
        val anchor = linesAnchor(style, symbols, belowLabels)
        style.addLayerBelow(casing, anchor)
        style.addLayerBelow(line, anchor)
        h.linesLayersAdded = true
        h.linesPlacedBelowLabels = belowLabels
        h.appliedLines = next
        return
    }

    if (next != h.appliedLines) {
        style.getSourceAs<GeoJsonSource>(LINES_SOURCE_ID)?.setGeoJson(linesCollection(next))
        h.appliedLines = next
    }
}

/**
 * The layer the lines go directly below. Basemap labels: its first SymbolLayer, not
 * counting our own layers, the annotation layer or the location puck. Otherwise the
 * lowest point layer, else the marker layer — so lines < point layers < markers.
 */
private fun linesAnchor(style: Style, symbols: SymbolManager, belowLabels: Boolean): String {
    val layers = style.layers
    if (belowLabels) {
        layers.firstOrNull {
            it is SymbolLayer && it.id != symbols.layerId &&
                !it.id.startsWith(OWN_PREFIX) && !it.id.startsWith(LOCATION_LAYER_PREFIX)
        }?.let { return it.id }
    }
    return layers.firstOrNull { it.id.startsWith(POINT_LAYER_PREFIX) }?.id ?: symbols.layerId
}

/** One LineString feature per drawable line, in list order (also its sort key). */
private fun linesCollection(lines: List<MapLine>): FeatureCollection =
    FeatureCollection.fromFeatures(
        lines.filter { it.points.size >= 2 }.mapIndexed { i, l ->
            val geometry = LineString.fromLngLats(l.points.map { Point.fromLngLat(it.lon, it.lat) })
            Feature.fromGeometry(geometry).also {
                it.addStringProperty(PROP_COLOR, l.color)
                it.addNumberProperty(PROP_WIDTH, l.width)
                if (l.casingColor != null) {
                    it.addStringProperty(PROP_CASING_COLOR, l.casingColor)
                    it.addNumberProperty(PROP_CASING_WIDTH, l.casingWidth)
                }
                it.addNumberProperty(PROP_ORDER, i)
            }
        }
    )

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
    p: MapProps,
) {
    val mode = p.userLocation
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (mode != UserLocationMode.HIDDEN && granted) {
        val lc = map.locationComponent
        if (!h.locationActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).build()
            )
            // Remember the engine so it can be restored after an app-pushed fix.
            h.defaultEngine = lc.locationEngine
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = h.onFollowDismissed()
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            h.locationActivated = true
        }
        if (!lc.isLocationComponentEnabled) lc.isLocationComponentEnabled = true
        applyUserFix(h, lc, map, p.userFix, mode)
        if (mode != h.appliedLocationMode) applyLocationMode(h, lc, map, mode, p.followTilt)
        if (mode == UserLocationMode.FOLLOW_BEARING) applyFollowZoom(h, lc, map, p)
    } else if (h.locationActivated && h.appliedLocationMode != UserLocationMode.HIDDEN) {
        map.locationComponent.isLocationComponentEnabled = false
        h.appliedLocationMode = UserLocationMode.HIDDEN
        h.lastPushedFix = null
        if (h.tiltApplied) {
            map.animateCamera(CameraUpdateFactory.tiltTo(0.0), FOLLOW_TILT_MS.toInt())
            h.tiltApplied = false
        }
    }
}

/**
 * Feeds an app-pushed [fix] to the puck instead of MapLibre's own location engine,
 * and restores that engine once the fix is gone. Each distinct fix is pushed once.
 */
@Suppress("MissingPermission")
private fun applyUserFix(
    h: MapHolder,
    lc: LocationComponent,
    map: MapLibreMap,
    fix: UserFix?,
    mode: UserLocationMode,
) {
    if (fix == null) {
        if (h.engineDetached) {
            lc.locationEngine = h.defaultEngine
            h.engineDetached = false
            h.lastPushedFix = null
        }
        return
    }
    if (!h.engineDetached) {
        lc.locationEngine = null
        h.engineDetached = true
    }
    // An identical fix (standing still) is still re-sent now and then: without updates
    // MapLibre greys the puck out as stale after its timeout (30 s by default).
    val now = SystemClock.elapsedRealtime()
    if (fix != h.lastPushedFix || now - h.lastPushMs >= FIX_REPUSH_MS) {
        lc.forceLocationUpdate(fix.toLocation())
        h.lastPushedFix = fix
        h.lastPushMs = now
    }
    // SHOW centers once on the first fix, which here is the first pushed one.
    if (mode == UserLocationMode.SHOW && !h.didCenterOnUser && !h.released) {
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(MlLatLng(fix.pos.lat, fix.pos.lon), 16.0)
        )
        h.didCenterOnUser = true
    }
}

private fun UserFix.toLocation(): Location =
    Location("waymap").also { loc ->
        loc.latitude = pos.lat
        loc.longitude = pos.lon
        bearingDeg?.let { loc.bearing = it }
        speedMps?.let { loc.speed = it }
        accuracyM?.let { loc.accuracy = it }
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

/** Applies camera/render mode (and the follow tilt) after [mode] changed. */
private fun applyLocationMode(
    h: MapHolder,
    lc: LocationComponent,
    map: MapLibreMap,
    mode: UserLocationMode,
    followTilt: Double,
) {
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
            if (followTilt != 0.0) {
                lc.tiltWhileTracking(followTilt, FOLLOW_TILT_MS)
                h.tiltApplied = true
            }
        }
        UserLocationMode.HIDDEN -> Unit
    }
    // Leaving FOLLOW_BEARING: undo the tilt (through the tracking API while still tracking).
    if (h.tiltApplied && mode != UserLocationMode.FOLLOW_BEARING) {
        if (mode == UserLocationMode.FOLLOW) lc.tiltWhileTracking(0.0, FOLLOW_TILT_MS)
        else map.animateCamera(CameraUpdateFactory.tiltTo(0.0), FOLLOW_TILT_MS.toInt())
        h.tiltApplied = false
    }
}

/** Speed-based zoom while following with bearing; see [shouldApplyFollowZoom]. */
private fun applyFollowZoom(h: MapHolder, lc: LocationComponent, map: MapLibreMap, p: MapProps) {
    val zoomForSpeed = p.followZoomForSpeed ?: return
    val speedMps = p.userFix?.speedMps
        ?: lc.lastKnownLocation?.takeIf { it.hasSpeed() }?.speed
        ?: return
    val target = zoomForSpeed(speedMps * 3.6)
    val now = SystemClock.elapsedRealtime()
    if (!shouldApplyFollowZoom(target, map.cameraPosition.zoom, now, h.lastAutoZoomMs)) return
    lc.zoomWhileTracking(target, FOLLOW_ZOOM_MS)
    h.lastAutoZoomMs = now
}
