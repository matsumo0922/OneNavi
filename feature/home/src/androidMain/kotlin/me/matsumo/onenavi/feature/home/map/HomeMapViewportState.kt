package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.coroutines.resume

@Composable
internal fun rememberHomeMapViewportState(): HomeMapViewportState {
    return remember { HomeMapViewportState() }
}

/**
 * Home 画面の地図カメラ状態とユーザー操作状態を管理する。
 *
 * Navigation SDK が同梱する GoogleMap を imperative に扱いながら、
 * 既存 Compose UI が参照しやすい状態を提供する。
 */
@Stable
class HomeMapViewportState internal constructor() {

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    var isGestureInProgress by mutableStateOf(false)
        private set

    internal var googleMap: GoogleMap? by mutableStateOf(null)
        private set

    private var pendingCameraAction: ((GoogleMap) -> Unit)? = null

    suspend fun moveTo(point: RoutePoint, zoom: Float = DEFAULT_CAMERA_ZOOM, tilt: Float = 0f, bearing: Float = 0f) {
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(point.latitude, point.longitude))
                .zoom(zoom)
                .tilt(tilt)
                .bearing(bearing)
                .build(),
        )
        animate(update)
    }

    suspend fun moveToBounds(points: List<RoutePoint>, paddingPx: Int) {
        if (points.isEmpty()) return

        val bounds = LatLngBounds.Builder().apply {
            points.forEach { point ->
                include(LatLng(point.latitude, point.longitude))
            }
        }.build()

        animate(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    suspend fun zoomBy(delta: Float) {
        animate(CameraUpdateFactory.zoomBy(delta))
    }

    internal fun attachMap(googleMap: GoogleMap) {
        this.googleMap = googleMap
        updateCameraPosition(googleMap.cameraPosition)
        pendingCameraAction?.let { action ->
            pendingCameraAction = null
            action(googleMap)
        }
    }

    internal fun clearMap(googleMap: GoogleMap) {
        if (this.googleMap == googleMap) {
            this.googleMap = null
        }
    }

    internal fun updateCameraPosition(position: CameraPosition) {
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

    private suspend fun animate(update: CameraUpdate) {
        val map = googleMap
        if (map == null) {
            pendingCameraAction = { readyMap ->
                readyMap.animateCamera(update)
            }
            return
        }

        suspendCancellableCoroutine { continuation ->
            map.animateCamera(
                update,
                CAMERA_ANIMATION_DURATION_MS,
                object : GoogleMap.CancelableCallback {
                    override fun onFinish() {
                        continuation.resume(Unit)
                    }

                    override fun onCancel() {
                        continuation.resume(Unit)
                    }
                },
            )
        }
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

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
private const val CAMERA_ANIMATION_DURATION_MS = 600
