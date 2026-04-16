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

/**
 * Home 画面の地図カメラ状態とユーザー操作状態を管理する。
 *
 * Compose の `CameraPositionState` を監視しつつ、UI から扱いやすい
 * カメラ状態とジェスチャー中フラグを提供する。
 *
 * @property cameraPositionState Google Maps Compose が保持するカメラ状態
 */
@Stable
class HomeMapViewportState internal constructor(
    val cameraPositionState: CameraPositionState,
) {

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    var isGestureInProgress by mutableStateOf(false)
        private set

    suspend fun moveTo(point: RoutePoint, zoom: Float = DEFAULT_CAMERA_ZOOM, tilt: Float = 0f, bearing: Float = 0f) {
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(point.latitude, point.longitude))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(bearing)
                    .build(),
            ),
            CAMERA_ANIMATION_DURATION_MS,
        )
    }

    suspend fun moveToBounds(points: List<RoutePoint>, paddingPx: Int) {
        if (points.isEmpty()) return

        val bounds = LatLngBounds.Builder().apply {
            points.forEach { point ->
                include(LatLng(point.latitude, point.longitude))
            }
        }.build()

        cameraPositionState.animate(
            CameraUpdateFactory.newLatLngBounds(bounds, paddingPx),
            CAMERA_ANIMATION_DURATION_MS,
        )
    }

    suspend fun zoomBy(delta: Float) {
        cameraPositionState.animate(
            CameraUpdateFactory.zoomBy(delta),
            CAMERA_ANIMATION_DURATION_MS,
        )
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

/**
 * 地図カメラの現在状態。
 *
 * @param latitude カメラ中心の緯度
 * @param longitude カメラ中心の経度
 * @param zoom 現在のズーム値
 * @param bearing 現在のベアリング
 * @param tilt 現在のチルト値
 */
@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val bearing: Double = 0.0,
    val tilt: Float = 0f,
)

/**
 * CameraPositionState から取得したスナップショット。
 *
 * @param position 現在のカメラ位置
 * @param isMoving カメラ移動中かどうか
 * @param moveStartedReason 移動開始理由
 */
internal data class ViewportSnapshot(
    val position: CameraPosition,
    val isMoving: Boolean,
    val moveStartedReason: CameraMoveStartedReason,
)

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
private const val CAMERA_ANIMATION_DURATION_MS = 600
