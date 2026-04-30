package me.matsumo.onenavi.feature.map.state

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
import com.google.android.gms.maps.model.FollowMyLocationOptions

@Composable
internal fun rememberMapCameraState(): MapCameraState =
    remember { MapCameraState() }

@Stable
internal class MapCameraState internal constructor () {
    private var googleMap: GoogleMap? = null

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    fun attachMap(googleMap: GoogleMap) {
        this.googleMap = googleMap

        followMyLocation()

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                cameraState = cameraState.copy(isFollowingMyLocation = false)
            }
        }
        googleMap.setOnCameraMoveListener {
            updateCameraPosition(googleMap.cameraPosition)
        }
        googleMap.setOnCameraIdleListener {
            updateCameraPosition(googleMap.cameraPosition)
        }
    }

    fun followMyLocation() {
        val options = FollowMyLocationOptions.builder()
            .setZoomLevel(cameraState.zoom)
            .build()

        googleMap?.followMyLocation(cameraState.perspective, options)
        cameraState = cameraState.copy(isFollowingMyLocation = true)
    }

    fun setPerspective(perspective: Int) {
        followMyLocation()
        cameraState = cameraState.copy(perspective = perspective)
    }

    fun zoomIn() {
        animateZoom(CameraUpdateFactory.zoomIn())
    }

    fun zoomOut() {
        animateZoom(CameraUpdateFactory.zoomOut())
    }

    private fun updateCameraPosition(cameraPosition: CameraPosition) {
        cameraState = cameraState.copy(
            latitude = cameraPosition.target.latitude,
            longitude = cameraPosition.target.longitude,
            bearing = cameraPosition.bearing.toDouble(),
            zoom = cameraPosition.zoom,
        )
    }

    private fun animateZoom(update: CameraUpdate) {
        val map = googleMap ?: return
        val wasTracking = cameraState.isFollowingMyLocation

        map.animateCamera(
            update,
            CAMERA_ANIMATION_DURATION_MS,
            object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    if (wasTracking) followMyLocation()
                }

                override fun onCancel() = Unit
            },
        )
    }
}

/**
 * 地図カメラの現在状態。
 *
 * @param latitude カメラ中心の緯度
 * @param longitude カメラ中心の経度
 * @param zoom 現在のズーム値
 * @param bearing 現在のカメラの向き
 * @param perspective GoogleMap.CameraPerspective
 * @param isFollowingMyLocation マップカメラが現在自分の位置に追従しているかどうか
 */
@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val bearing: Double = 0.0,
    val perspective: Int = GoogleMap.CameraPerspective.TILTED,
    val isFollowingMyLocation: Boolean = false,
)

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
private const val CAMERA_ANIMATION_DURATION_MS = 600