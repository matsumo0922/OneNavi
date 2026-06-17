package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker

/**
 * GoogleMap の marker click listener を地図 overlay 間で共有する dispatcher。
 *
 * GoogleMap は marker click listener を 1 つしか保持できないため、bookmark marker と callout marker
 * が互いの listener を上書きしないよう tag 単位で click handler を登録する。
 *
 * @param googleMap click listener を設定する GoogleMap
 */
internal class MapMarkerClickDispatcher(
    private val googleMap: GoogleMap,
) {
    private val handlers = mutableMapOf<String, () -> Unit>()

    /** GoogleMap に共有 click listener を接続する。 */
    fun attach() {
        googleMap.setOnMarkerClickListener(::onMarkerClicked)
    }

    /** 共有 click listener を解除する。 */
    fun detach() {
        handlers.clear()
        googleMap.setOnMarkerClickListener(null)
    }

    /** marker tag に対応する click handler を登録する。 */
    fun register(tag: String, handler: () -> Unit) {
        handlers[tag] = handler
    }

    /** marker tag に対応する click handler を解除する。 */
    fun unregister(tag: String) {
        handlers.remove(tag)
    }

    private fun onMarkerClicked(marker: Marker): Boolean {
        val tag = marker.tag as? String ?: return false
        val handler = handlers[tag] ?: return false

        handler.invoke()

        return true
    }
}

/** GoogleMap marker click dispatcher を overlay component へ共有する CompositionLocal。 */
internal val LocalMapMarkerClickDispatcher = staticCompositionLocalOf<MapMarkerClickDispatcher?> { null }

@Composable
internal fun MapMarkerClickDispatcherProvider(
    googleMap: GoogleMap,
    content: @Composable () -> Unit,
) {
    val dispatcher = remember(googleMap) {
        MapMarkerClickDispatcher(googleMap)
    }

    DisposableEffect(dispatcher) {
        dispatcher.attach()

        onDispose {
            dispatcher.detach()
        }
    }

    CompositionLocalProvider(LocalMapMarkerClickDispatcher provides dispatcher) {
        content()
    }
}
