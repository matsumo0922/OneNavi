package me.matsumo.onenavi.feature.map.state

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath
import me.matsumo.onenavi.feature.map.camera.WebMercatorProjection
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_DECELERATE_FACTOR
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_PAN_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ZOOM_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.MAX_FLY_TO_DURATION_MS
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

@SuppressLint("MissingPermission")
@Composable
internal fun rememberMapCameraState(): MapCameraState {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val state = remember { MapCameraState() }

    SideEffect {
        state.updateDensity(density)
    }

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
    private var mapViewWidthPx: Int = 0
    private var density: Float = DEFAULT_DENSITY

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

    /** 地図ビューの実ピクセル幅を記録する。fly-to のビューポート幅算出に使う。 */
    fun updateViewportWidth(widthPx: Int) {
        mapViewWidthPx = widthPx
    }

    /** 画面密度を記録する。GoogleMap の zoom は dp 基準なので fly-to で px → dp 換算に使う。 */
    fun updateDensity(density: Float) {
        this.density = density
    }

    fun updatePadding(top: Int, bottom: Int, start: Int, end: Int) {
        googleMap?.setPadding(start, top, end, bottom)
    }

    @SuppressLint("MissingPermission")
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
        flyCameraTo(
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
     * その目標位置へ [flyCameraTo] で van Wijk–Nuij 経路で寄せる。「現在地 → 引いて全体」という
     * 自然なシネマティック移動になり、`updatePadding` で設定済みの地図パディング
     * （トップバー / ボトムシート分）も尊重される。ルート全体表示は引き量が大きく、弧長へ減速を
     * かけるだけだと終端の減速が知覚されにくいため、ズームレベルへ直接かける強めの減速
     * （[CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR]）を [flyCameraTo] に渡す。
     * 地図ビューのサイズが未確定の場合は単純補間（[animateCameraTo]）にフォールバックする。
     *
     * @param routePoints フィット対象の座標列（全候補ルートをまとめて渡してよい）
     * @param paddingPx 画面端とルートの間に確保する余白（px）
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出
     */
    fun showRouteOverview(
        routePoints: List<RoutePoint>,
        paddingPx: Int = ROUTE_OVERVIEW_PADDING_PX,
        durationMs: Long? = 1500,
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
            flyCameraTo(
                target = target,
                durationMs = durationMs,
                zoomEasing = DecelerateInterpolator(CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR),
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
     * カメラを [target] へ van Wijk–Nuij "Smooth and efficient zooming and panning" の経路で移動させる。
     * 始点と終点が遠ければ途中で一旦ズームアウトしてから寄り直す弧を描き、近ければ弧が消えてただのイージングになる。
     * bearing / tilt は経路とは別チャンネルで線形補間する。地図ビューのサイズが未確定（レイアウト前）なら
     * 単純補間の [animateCameraTo] にフォールバックする。
     *
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出し、
     *   [MIN_FLY_TO_DURATION_MS]〜[MAX_FLY_TO_DURATION_MS] にクランプする
     * @param zoomEasing 指定すると、ズーム（= log ビューポート幅）を弧長から切り離し、生のアニメ進捗に
     *   このイージングをかけて log 空間で補間する。引き量が大きいときに終端の減速をはっきり効かせるための逃げ道。
     *   null なら従来どおりズームも弧長パラメータから導出する（中心パンと完全に結合）。
     */
    private fun flyCameraTo(
        target: CameraPosition,
        durationMs: Long? = null,
        zoomEasing: TimeInterpolator? = null,
        onFinished: () -> Unit = {},
    ) {
        val map = googleMap ?: return
        val viewportWidthDp = mapViewWidthPx.toDouble() / density
        if (viewportWidthDp <= 0.0) {
            animateCameraTo(
                target = target,
                panDurationMs = durationMs,
                zoomDurationMs = durationMs,
                onFinished = onFinished,
            )
            return
        }

        val start = map.cameraPosition
        val startViewport = flyToViewport(viewportWidthDp, start.target, start.zoom)
        val endViewport = flyToViewport(viewportWidthDp, target.target, target.zoom)

        val isSamePose = startViewport == endViewport &&
            start.bearing == target.bearing &&
            start.tilt == target.tilt
        if (isSamePose) {
            onFinished()
            return
        }

        val path = VanWijkZoomPath.of(startViewport, endViewport, rho = CAMERA_FLY_TO_RHO)
        val totalDurationMs = (durationMs ?: (path.naturalDurationMs() * CAMERA_FLY_TO_SPEED_SCALE).toLong()).coerceAtMost(MAX_FLY_TO_DURATION_MS)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        cameraState = cameraState.copy(isFollowingMyLocation = false)
        cameraAnimator?.cancel()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val rawFraction = anim.animatedValue as Float
                val arcFraction = easing.getInterpolation(rawFraction)
                val viewport = path.at(arcFraction.toDouble())

                val widthWorldPx = if (zoomEasing == null) {
                    viewport.viewportWidthWorldPx
                } else {
                    val zoomFraction = zoomEasing.getInterpolation(rawFraction).toDouble()
                    val logStartWidth = ln(startViewport.viewportWidthWorldPx)
                    val logEndWidth = ln(endViewport.viewportWidthWorldPx)
                    exp(logStartWidth + (logEndWidth - logStartWidth) * zoomFraction)
                }
                val zoom = (ln(viewportWidthDp / widthWorldPx) / ln(2.0)).toFloat()
                    .coerceIn(MIN_ZOOM, MAX_ZOOM)

                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(
                                LatLng(
                                    WebMercatorProjection.worldYToLatitude(viewport.worldY),
                                    WebMercatorProjection.worldXToLongitude(viewport.worldX),
                                ),
                            )
                            .zoom(zoom)
                            .bearing(lerpAngle(start.bearing, target.bearing, arcFraction))
                            .tilt(lerp(start.tilt, target.tilt, arcFraction))
                            .build(),
                    ),
                )
            }
            doOnEnd { onFinished() }
            start()
        }
    }

    /** GoogleMap のカメラ位置を van Wijk–Nuij 経路用のズーム 0 ワールドピクセル座標へ変換する。 */
    private fun flyToViewport(
        viewportWidthDp: Double,
        center: LatLng,
        zoom: Float,
    ): VanWijkZoomPath.Viewport = VanWijkZoomPath.Viewport(
        worldX = WebMercatorProjection.longitudeToWorldX(center.longitude),
        worldY = WebMercatorProjection.latitudeToWorldY(center.latitude),
        viewportWidthWorldPx = viewportWidthDp / 2.0.pow(zoom.toDouble()),
    )

    /**
     * カメラを [target] へアニメーション移動させる。pan（位置・向き・傾き）と zoom は
     * それぞれ独立した duration を持ち、短い方が先に到達して止まる（fraction を頭打ちにする）。
     * animator 本体は両者の長い方の長さで線形に回し、各チャンネルへ [DecelerateInterpolator] を個別に適用する。
     * 現在は [flyCameraTo] が使えない（地図ビューのサイズが未確定など）ときの単純補間フォールバックとして残してある。
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

        /**
         * [showRouteOverview] の引きアニメで、ズームレベル（= log ビューポート幅）へ直接かける減速の強さ。
         * 引き量が大きく弧長への減速だと終端の減速が知覚されにくいので、[CAMERA_DECELERATE_FACTOR] より強めにする。
         */
        private const val CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR = 5f

        /** fly-to の曲率 ρ。大きいほど遠距離移動で大胆にズームアウトする（d3 / Mapbox の既定は約 1.42）。 */
        private const val CAMERA_FLY_TO_RHO = 0.6

        /** fly-to の自然な所要時間に掛ける係数。大きいほどゆっくり動く。 */
        private const val CAMERA_FLY_TO_SPEED_SCALE = 1.0

        /** fly-to の所要時間の上限（ms）。地球の裏側へ飛ぶときに何秒も待たされないように。 */
        private const val MAX_FLY_TO_DURATION_MS = 3000L

        private const val DEFAULT_DENSITY = 1f
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
