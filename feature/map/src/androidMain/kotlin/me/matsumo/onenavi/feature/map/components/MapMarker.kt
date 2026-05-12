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
) {
    DisposableEffect(googleMap, latitude, longitude, title) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(title),
        )
        onDispose { marker?.remove() }
    }
}
