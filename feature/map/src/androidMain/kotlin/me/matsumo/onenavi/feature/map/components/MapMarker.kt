package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

@Composable
internal fun MapMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    title: String? = null,
    zIndex: Float = DEFAULT_MARKER_Z_INDEX,
) {
    DisposableEffect(googleMap, latitude, longitude, title, zIndex) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(title)
                .zIndex(zIndex),
        )
        onDispose { marker?.remove() }
    }
}

private const val DEFAULT_MARKER_Z_INDEX = 10_000f
