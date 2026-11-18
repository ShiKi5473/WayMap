package com.waymap.ui

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

/** Tile cache settings. */
object WayMapCache {
    /**
     * Sets the maximum size of MapLibre's ambient (automatic) tile cache. Tiles of
     * areas already viewed stay usable offline, up to this size; the oldest are
     * evicted beyond it. [onDone] gets null on success, or an error message.
     */
    fun setAmbientCacheSize(context: Context, bytes: Long, onDone: (error: String?) -> Unit = {}) {
        // MapLibre must be initialized before OfflineManager is used.
        MapLibre.getInstance(context.applicationContext)
        OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(
            bytes,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = onDone(null)
                override fun onError(message: String) = onDone(message)
            },
        )
    }
}
