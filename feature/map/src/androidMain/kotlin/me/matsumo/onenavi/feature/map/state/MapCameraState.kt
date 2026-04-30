package me.matsumo.onenavi.feature.map.state

import android.animation.ValueAnimator
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.FollowMyLocationOptions
import com.google.android.gms.maps.model.LatLng

@Composable
internal fun rememberMapCameraState(): MapCameraState {
    val context = LocalContext.current
    val state = remember { MapCameraState() }

    DisposableEffect(context) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()

        val callback: LocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.also {
                    state.updateMyLocation(it.latitude, it.longitude)
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose {
            client.removeLocationUpdates(callback)
        }
    }

    return state
}

@Stable
internal class MapCameraState internal constructor () {

    private var googleMap: GoogleMap? = null
    private var cameraAnimator: ValueAnimator? = null

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

    fun updateMyLocation(latitude: Double, longitude: Double) {
        cameraState = cameraState.copy(
            myLocationLatitude = latitude,
            myLocationLongitude = longitude,
        )
    }

    fun updatePadding(top: Int, bottom: Int, start: Int, end: Int) {
        googleMap?.setPadding(start, top, end, bottom)
    }

    fun followMyLocation() {
        val options = FollowMyLocationOptions.builder()
            .setZoomLevel(cameraState.zoom)
            .build()

        googleMap?.followMyLocation(cameraState.perspective, options)
        cameraState = cameraState.copy(isFollowingMyLocation = true)
    }

    fun setPerspective(perspective: Int) {
        cameraState = cameraState.copy(perspective = perspective)
        followMyLocation()
    }

    fun zoomIn() {
        changeZoom((cameraState.zoom + 1f).coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    fun zoomOut() {
        changeZoom((cameraState.zoom - 1f).coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    fun moveTo(latitude: Double, longitude: Double, zoom: Float = cameraState.zoom) {
        animateCameraTo(
            target = CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .bearing(0f)
                .tilt(0f)
                .build(),
        )
    }

    private fun updateCameraPosition(cameraPosition: CameraPosition) {
        cameraState = cameraState.copy(
            latitude = cameraPosition.target.latitude,
            longitude = cameraPosition.target.longitude,
            bearing = cameraPosition.bearing.toDouble(),
            zoom = cameraPosition.zoom,
        )
    }

    private fun changeZoom(targetZoom: Float) {
        val map = googleMap ?: return
        val wasTracking = cameraState.isFollowingMyLocation
        val current = map.cameraPosition

        val target = CameraPosition.Builder()
            .target(current.target)
            .zoom(targetZoom)
            .bearing(current.bearing)
            .tilt(current.tilt)
            .build()

        animateCameraTo(
            target = target,
            durationMs = CAMERA_ANIMATION_DURATION_MS,
            onFinished = { if (wasTracking) followMyLocation() },
        )
    }

    private fun animateCameraTo(
        target: CameraPosition,
        durationMs: Long = CAMERA_ANIMATION_DURATION_MS,
        onFinished: () -> Unit = {},
    ) {
        val map = googleMap ?: return
        val start = map.cameraPosition

        cameraState = cameraState.copy(isFollowingMyLocation = false)
        cameraAnimator?.cancel()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = FastOutSlowInInterpolator()

            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val lat = lerp(start.target.latitude, target.target.latitude, fraction)
                val lng = lerp(start.target.longitude, target.target.longitude, fraction)
                val zoom = lerp(start.zoom, target.zoom, fraction)
                val bearing = lerpAngle(start.bearing, target.bearing, fraction)
                val tilt = lerp(start.tilt, target.tilt, fraction)

                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(lat, lng))
                            .zoom(zoom)
                            .bearing(bearing)
                            .tilt(tilt)
                            .build(),
                    ),
                )
            }
            doOnEnd { onFinished() }
            start()
        }
    }

    private fun lerp(from: Double, to: Double, fraction: Float): Double =
        from + (to - from) * fraction

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    private fun lerpAngle(from: Float, to: Float, fraction: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff * fraction + 360f) % 360f
    }

    companion object {
        private const val MIN_ZOOM = 2f
        private const val MAX_ZOOM = 21f
    }
}

/**
 * 地図カメラの現在状態。
 *
 * @param latitude カメラ中心の緯度
 * @param longitude カメラ中心の経度
 * @param myLocationLatitude 自分の位置の緯度
 * @param myLocationLongitude 自分の位置の経度
 * @param zoom 現在のズーム値
 * @param bearing 現在のカメラの向き
 * @param perspective GoogleMap.CameraPerspective
 * @param isFollowingMyLocation マップカメラが現在自分の位置に追従しているかどうか
 */
@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val myLocationLatitude: Double = DEFAULT_LATITUDE,
    val myLocationLongitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val bearing: Double = 0.0,
    val perspective: Int = GoogleMap.CameraPerspective.TILTED,
    val isFollowingMyLocation: Boolean = false,
)

private const val DEFAULT_LATITUDE = 35.681236
private const val DEFAULT_LONGITUDE = 139.767125
private const val DEFAULT_CAMERA_ZOOM = 15f
private const val CAMERA_ANIMATION_DURATION_MS = 500L
