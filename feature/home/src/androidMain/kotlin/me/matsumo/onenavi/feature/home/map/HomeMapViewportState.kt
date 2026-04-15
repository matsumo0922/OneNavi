package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import me.matsumo.onenavi.core.model.RoutePoint

@Stable
class HomeMapViewportState {

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    var isGestureInProgress by mutableStateOf(false)
        private set

    private var googleMap: GoogleMap? = null

    fun attachMap(map: GoogleMap) {
        googleMap = map
        updateCameraState(map.cameraPosition)
        map.setOnCameraMoveStartedListener { reason ->
            isGestureInProgress = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE
        }
        map.setOnCameraIdleListener {
            updateCameraState(map.cameraPosition)
            isGestureInProgress = false
        }
    }

    fun detachMap() {
        googleMap = null
    }

    fun moveTo(point: RoutePoint, zoom: Float = DEFAULT_ZOOM, tilt: Float = 0f, bearing: Float = 0f) {
        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(zoom)
            .tilt(tilt)
            .bearing(bearing)
            .build()
        googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    fun moveToBounds(points: List<RoutePoint>, paddingPx: Int) {
        if (points.isEmpty()) return
        val bounds = LatLngBounds.Builder().apply {
            points.forEach { point ->
                include(LatLng(point.latitude, point.longitude))
            }
        }.build()
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    fun zoomBy(delta: Float) {
        googleMap?.animateCamera(CameraUpdateFactory.zoomBy(delta))
    }

    private fun updateCameraState(position: CameraPosition) {
        cameraState = HomeMapCameraState(
            latitude = position.target.latitude,
            longitude = position.target.longitude,
            zoom = position.zoom,
            bearing = position.bearing.toDouble(),
            tilt = position.tilt,
        )
    }

    companion object {
        private const val DEFAULT_ZOOM = 16f
    }
}

@Stable
data class HomeMapCameraState(
    val latitude: Double = 35.681236,
    val longitude: Double = 139.767125,
    val zoom: Float = 15f,
    val bearing: Double = 0.0,
    val tilt: Float = 0f,
)
