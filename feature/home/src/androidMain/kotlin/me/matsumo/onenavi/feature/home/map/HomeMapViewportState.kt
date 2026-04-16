package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.matsumo.onenavi.core.model.RoutePoint

@Composable
internal fun rememberHomeMapViewportState(): HomeMapViewportState {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE),
            DEFAULT_CAMERA_ZOOM,
        )
    }
    val viewportState = remember(cameraPositionState) {
        HomeMapViewportState(cameraPositionState)
    }

    LaunchedEffect(cameraPositionState) {
        snapshotFlow {
            ViewportSnapshot(
                position = cameraPositionState.position,
                isMoving = cameraPositionState.isMoving,
                moveStartedReason = cameraPositionState.cameraMoveStartedReason,
            )
        }
            .distinctUntilChanged()
            .collect { snapshot ->
                viewportState.updateFromSnapshot(snapshot)
            }
    }

    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.projection != null }
            .map { projectionReady ->
                projectionReady &&
                    cameraPositionState.isMoving &&
                    cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
            }
            .distinctUntilChanged()
            .collect { isGestureInProgress ->
                viewportState.setGestureInProgress(isGestureInProgress)
            }
    }

    return viewportState
}

@Stable
class HomeMapViewportState internal constructor(
    val cameraPositionState: CameraPositionState,
) {

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    var isGestureInProgress by mutableStateOf(false)
        private set

    fun moveTo(point: RoutePoint, zoom: Float = DEFAULT_CAMERA_ZOOM, tilt: Float = 0f, bearing: Float = 0f) {
        cameraPositionState.move(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(point.latitude, point.longitude))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(bearing)
                    .build(),
            ),
        )
    }

    fun moveToBounds(points: List<RoutePoint>, paddingPx: Int) {
        if (points.isEmpty()) return

        val bounds = LatLngBounds.Builder().apply {
            points.forEach { point ->
                include(LatLng(point.latitude, point.longitude))
            }
        }.build()

        cameraPositionState.move(
            CameraUpdateFactory.newLatLngBounds(bounds, paddingPx),
        )
    }

    fun zoomBy(delta: Float) {
        cameraPositionState.move(CameraUpdateFactory.zoomBy(delta))
    }

    internal fun updateFromSnapshot(snapshot: ViewportSnapshot) {
        val position = snapshot.position
        cameraState = HomeMapCameraState(
            latitude = position.target.latitude,
            longitude = position.target.longitude,
            zoom = position.zoom,
            bearing = position.bearing.toDouble(),
            tilt = position.tilt,
        )
    }

    internal fun setGestureInProgress(isGestureInProgress: Boolean) {
        this.isGestureInProgress = isGestureInProgress
    }
}

@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val bearing: Double = 0.0,
    val tilt: Float = 0f,
)

internal data class ViewportSnapshot(
    val position: CameraPosition,
    val isMoving: Boolean,
    val moveStartedReason: CameraMoveStartedReason,
)

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
