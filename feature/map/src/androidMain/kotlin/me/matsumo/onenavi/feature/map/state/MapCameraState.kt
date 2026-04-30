package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.GoogleMap

@Composable
internal fun rememberMapCameraState(googleMap: GoogleMap?): MapCameraState {
    val state =  remember(googleMap) { MapCameraState(googleMap) }

    LaunchedEffect(googleMap) {
        if (googleMap != null) {
            state.followMyLocation()
        }
    }

    return state
}

@Stable
internal class MapCameraState internal constructor (
    private val googleMap: GoogleMap?,
) {
    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    init {
        followMyLocation()
    }

    fun followMyLocation() {
        googleMap?.followMyLocation(cameraState.perspective)
    }

    fun setPerspective(perspective: Int) {
        cameraState = cameraState.copy(perspective = perspective)
    }
}

/**
 * 地図カメラの現在状態。
 *
 * @param latitude カメラ中心の緯度
 * @param longitude カメラ中心の経度
 * @param zoom 現在のズーム値
 * @param perspective GoogleMap.CameraPerspective
 */
@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val perspective: Int = GoogleMap.CameraPerspective.TILTED
)

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
private const val CAMERA_ANIMATION_DURATION_MS = 600