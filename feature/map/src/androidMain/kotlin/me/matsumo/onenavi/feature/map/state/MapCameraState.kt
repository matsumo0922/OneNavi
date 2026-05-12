package me.matsumo.onenavi.feature.map.state

import android.animation.ValueAnimator
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.animation.doOnEnd
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
import com.google.android.gms.maps.model.LatLngBounds
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_PAN_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ZOOM_DURATION_MS
import kotlin.math.abs
import kotlin.math.max

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
internal class MapCameraState internal constructor() {

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

    /**
     * ルート全体が画面に収まるようにカメラを移動させる。
     *
     * bounds にフィットする [CameraPosition] を一旦 `moveCamera` で算出してから元に戻し、
     * その目標位置へ [animateCameraTo] で補間する。これにより `moveTo` 等と同じ
     * イージング・速度になり、duration もカスタマイズできる。`updatePadding` で
     * 設定済みの地図パディング（トップバー / ボトムシート分）も尊重される。
     *
     * @param routePoints フィット対象の座標列（全候補ルートをまとめて渡してよい）
     * @param paddingPx 画面端とルートの間に確保する余白（px）
     * @param durationMs アニメーション時間（ms）。null なら pan / zoom それぞれ自動算出
     */
    fun showRouteOverview(
        routePoints: List<RoutePoint>,
        paddingPx: Int = ROUTE_OVERVIEW_PADDING_PX,
        durationMs: Long? = null,
    ) {
        val map = googleMap ?: return
        if (routePoints.isEmpty()) return

        val bounds = LatLngBounds.builder()
            .apply { for (point in routePoints) include(LatLng(point.latitude, point.longitude)) }
            .build()

        val current = map.cameraPosition
        val target = runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
            map.cameraPosition
        }.getOrNull()

        map.moveCamera(CameraUpdateFactory.newCameraPosition(current))

        if (target != null) {
            animateCameraTo(
                target = target,
                panDurationMs = durationMs,
                zoomDurationMs = durationMs,
            )
        }
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
            onFinished = { if (wasTracking) followMyLocation() },
        )
    }

    /**
     * カメラを [target] へアニメーション移動させる。pan（位置・向き・傾き）と zoom は
     * それぞれ独立した duration を持ち、短い方が先に到達して止まる（fraction を頭打ちにする）。
     * animator 本体は両者の長い方の長さで線形に回し、各チャンネルへ [DecelerateInterpolator] を個別に適用する。
     *
     * @param panDurationMs pan の所要時間（ms）。null なら [CAMERA_PAN_DURATION_MS]
     * @param zoomDurationMs zoom の所要時間（ms）。null ならズーム差から自動算出
     */
    private fun animateCameraTo(
        target: CameraPosition,
        panDurationMs: Long? = null,
        zoomDurationMs: Long? = null,
        onFinished: () -> Unit = {},
    ) {
        val map = googleMap ?: return
        val start = map.cameraPosition

        val resolvedPanDurationMs = panDurationMs ?: CAMERA_PAN_DURATION_MS
        val resolvedZoomDurationMs = zoomDurationMs ?: cameraZoomDurationMs(zoomDelta = abs(target.zoom - start.zoom))
        val totalDurationMs = max(resolvedPanDurationMs, resolvedZoomDurationMs)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        cameraState = cameraState.copy(isFollowingMyLocation = false)
        cameraAnimator?.cancel()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val elapsedMs = (anim.animatedValue as Float) * totalDurationMs
                val panFraction = easing.getInterpolation((elapsedMs / resolvedPanDurationMs).coerceAtMost(1f))
                val zoomFraction = easing.getInterpolation((elapsedMs / resolvedZoomDurationMs).coerceAtMost(1f))

                val lat = lerp(start.target.latitude, target.target.latitude, panFraction)
                val lng = lerp(start.target.longitude, target.target.longitude, panFraction)
                val zoom = lerp(start.zoom, target.zoom, zoomFraction)
                val bearing = lerpAngle(start.bearing, target.bearing, panFraction)
                val tilt = lerp(start.tilt, target.tilt, panFraction)

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

    /** ズーム差に応じて zoom 側のアニメーション時間を [CAMERA_PAN_DURATION_MS]〜[CAMERA_ZOOM_DURATION_MS] の範囲で線形に決める。 */
    private fun cameraZoomDurationMs(zoomDelta: Float): Long {
        val ratio = (zoomDelta / FULL_ZOOM_DELTA).coerceIn(0f, 1f)
        return CAMERA_PAN_DURATION_MS + ((CAMERA_ZOOM_DURATION_MS - CAMERA_PAN_DURATION_MS) * ratio).toLong()
    }

    companion object {
        private const val MIN_ZOOM = 2f
        private const val MAX_ZOOM = 21f
        private const val ROUTE_OVERVIEW_PADDING_PX = 64
        private const val CAMERA_PAN_DURATION_MS = 3000L
        private const val CAMERA_ZOOM_DURATION_MS = 10000L
        private const val FULL_ZOOM_DELTA = 10f
        private const val CAMERA_DECELERATE_FACTOR = 2.5f
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
