package me.matsumo.onenavi.feature.map.state

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath
import me.matsumo.onenavi.feature.map.camera.WebMercatorProjection
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * GoogleMap camera animation の実行を担当する helper。
 */
internal class MapCameraAnimator(
    private val mapProvider: () -> GoogleMap?,
    private val viewportWidthDpProvider: () -> Double,
) {

    private var animator: ValueAnimator? = null

    /** 自前の camera animator が動作中なら true。 */
    val isRunning: Boolean
        get() = animator != null

    /**
     * 実行中の camera animation を中断する。
     */
    fun cancel() {
        animator?.cancel()
        animator = null
    }

    /**
     * カメラを [target] へ van Wijk–Nuij "Smooth and efficient zooming and panning" の経路で移動させる。
     *
     * 始点と終点が遠ければ途中で一旦ズームアウトしてから寄り直す弧を描き、近ければ弧が消えて
     * ただのイージングになる。bearing / tilt は経路とは別チャンネルで線形補間する。
     *
     * @param target 移動先 camera position
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出する
     * @param zoomEasing zoom だけを弧長から切り離して補間する場合の easing。通常は null
     * @param moveCamera frame ごとの camera 適用処理
     * @param onFallback viewport が未確定で fly-to を使えない場合の fallback
     * @param onStarted animation 開始時 callback
     * @param onFinished animation 終了時 callback
     */
    fun flyTo(
        target: CameraPosition,
        durationMs: Long? = null,
        zoomEasing: TimeInterpolator? = null,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = DEFAULT_MOVE_CAMERA,
        onFallback: () -> Unit,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        val map = mapProvider() ?: return
        val viewportWidthDp = viewportWidthDpProvider()
        if (viewportWidthDp <= 0.0) {
            onFallback()
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
        val totalDurationMs = durationMs
            ?: (path.naturalDurationMs() * CAMERA_FLY_TO_SPEED_SCALE)
                .toLong()
                .coerceIn(MIN_FLY_TO_DURATION_MS, MAX_FLY_TO_DURATION_MS)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        cancel()
        onStarted()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                val rawFraction = animation.animatedValue as Float
                val arcFraction = easing.getInterpolation(rawFraction)
                val viewport = path.at(arcFraction.toDouble())
                val widthWorldPx = if (zoomEasing == null) {
                    viewport.viewportWidthWorldPx
                } else {
                    zoomViewportWidth(
                        startViewport = startViewport,
                        endViewport = endViewport,
                        rawFraction = rawFraction,
                        zoomEasing = zoomEasing,
                    )
                }
                val zoom = (ln(viewportWidthDp / widthWorldPx) / ln(2.0)).toFloat()
                    .coerceIn(MIN_ZOOM, MAX_ZOOM)

                moveCamera(
                    map,
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                WebMercatorProjection.worldYToLatitude(viewport.worldY),
                                WebMercatorProjection.worldXToLongitude(viewport.worldX),
                            ),
                        )
                        .zoom(zoom)
                        .bearing(MapInterpolation.lerpAngleDegrees(start.bearing, target.bearing, arcFraction))
                        .tilt(MapInterpolation.lerp(start.tilt, target.tilt, arcFraction))
                        .build(),
                )
            }
            doOnEnd { finishAnimation(this, onFinished) }
            start()
        }
    }

    /**
     * カメラを [target] へ単純補間で移動させる。
     *
     * pan と zoom はそれぞれ独立した duration を持つ。fly-to が使えない場面の fallback と、
     * 現在地追従ではない通常 zoom 操作に使う。
     *
     * @param target 移動先 camera position
     * @param panDurationMs pan の所要時間（ms）。null なら既定値
     * @param zoomDurationMs zoom の所要時間（ms）。null ならズーム差から自動算出
     * @param moveCamera frame ごとの camera 適用処理
     * @param onStarted animation 開始時 callback
     * @param onFinished animation 終了時 callback
     */
    fun animateTo(
        target: CameraPosition,
        panDurationMs: Long? = null,
        zoomDurationMs: Long? = null,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = DEFAULT_MOVE_CAMERA,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        val map = mapProvider() ?: return
        val start = map.cameraPosition
        val resolvedPanDurationMs = panDurationMs ?: CAMERA_PAN_DURATION_MS
        val resolvedZoomDurationMs = zoomDurationMs ?: cameraZoomDurationMs(zoomDelta = abs(target.zoom - start.zoom))
        val totalDurationMs = max(resolvedPanDurationMs, resolvedZoomDurationMs)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        cancel()
        onStarted()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                val elapsedMs = (animation.animatedValue as Float) * totalDurationMs
                val panFraction = easing.getInterpolation((elapsedMs / resolvedPanDurationMs).coerceAtMost(1f))
                val zoomFraction = easing.getInterpolation((elapsedMs / resolvedZoomDurationMs).coerceAtMost(1f))
                val lat = MapInterpolation.lerp(start.target.latitude, target.target.latitude, panFraction)
                val lng = MapInterpolation.lerp(start.target.longitude, target.target.longitude, panFraction)
                val zoom = MapInterpolation.lerp(start.zoom, target.zoom, zoomFraction)
                val bearing = MapInterpolation.lerpAngleDegrees(start.bearing, target.bearing, panFraction)
                val tilt = MapInterpolation.lerp(start.tilt, target.tilt, panFraction)

                moveCamera(
                    map,
                    CameraPosition.Builder()
                        .target(LatLng(lat, lng))
                        .zoom(zoom)
                        .bearing(bearing)
                        .tilt(tilt)
                        .build(),
                )
            }
            doOnEnd { finishAnimation(this, onFinished) }
            start()
        }
    }

    /**
     * follow 中の zoom button 操作を、毎 frame の自車追従 target 更新に委譲しながら実行する。
     *
     * @param targetZoom 移動先 zoom 値
     * @param onFrame frame ごとの zoom 反映処理
     * @param onStarted animation 開始時 callback
     * @param onFinished animation 終了時 callback
     */
    fun animateFollowZoomTo(
        targetZoom: Float,
        onFrame: (Float) -> Unit,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        val map = mapProvider() ?: return
        val startZoom = map.cameraPosition.zoom
        val totalDurationMs = cameraZoomDurationMs(zoomDelta = abs(targetZoom - startZoom))
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        cancel()
        onStarted()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                val fraction = easing.getInterpolation(animation.animatedValue as Float)
                val zoom = MapInterpolation.lerp(startZoom, targetZoom, fraction)
                onFrame(zoom)
            }
            doOnEnd { finishAnimation(this, onFinished) }
            start()
        }
    }

    /**
     * GoogleMap のカメラ位置を van Wijk–Nuij 経路用のズーム 0 ワールドピクセル座標へ変換する。
     *
     * @param viewportWidthDp viewport 幅（dp）
     * @param center camera target
     * @param zoom GoogleMap zoom
     * @return fly-to 経路計算用 viewport
     */
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
     * zoomEasing 指定時の viewport 幅を log 空間で補間する。
     *
     * @param startViewport 開始 viewport
     * @param endViewport 終了 viewport
     * @param rawFraction animator の生進捗
     * @param zoomEasing zoom 用 easing
     * @return 補間後 viewport 幅
     */
    private fun zoomViewportWidth(
        startViewport: VanWijkZoomPath.Viewport,
        endViewport: VanWijkZoomPath.Viewport,
        rawFraction: Float,
        zoomEasing: TimeInterpolator,
    ): Double {
        val zoomFraction = zoomEasing.getInterpolation(rawFraction).toDouble()
        val logStartWidth = ln(startViewport.viewportWidthWorldPx)
        val logEndWidth = ln(endViewport.viewportWidthWorldPx)

        return exp(MapInterpolation.lerp(logStartWidth, logEndWidth, zoomFraction))
    }

    /**
     * animator 終了時の共通後処理を行う。
     *
     * @param finishedAnimator 終了した animator
     * @param onFinished animation 終了時 callback
     */
    private fun finishAnimation(
        finishedAnimator: ValueAnimator,
        onFinished: () -> Unit,
    ) {
        if (animator == finishedAnimator) {
            animator = null
        }
        onFinished()
    }

    private companion object {

        /** GoogleMap に許容する最小ズーム値。 */
        const val MIN_ZOOM = 2f

        /** GoogleMap に許容する最大ズーム値。 */
        const val MAX_ZOOM = 21f

        /** 通常の pan アニメーション時間（ms）。 */
        const val CAMERA_PAN_DURATION_MS = 3000L

        /** 最大ズーム差のときに使う zoom アニメーション時間（ms）。 */
        const val CAMERA_ZOOM_DURATION_MS = 10000L

        /** zoom アニメーション時間を最大にするズーム差。 */
        const val FULL_ZOOM_DELTA = 10f

        /** camera animation に使う減速補間の強さ。 */
        const val CAMERA_DECELERATE_FACTOR = 2.5f

        /** fly-to の曲率 ρ。大きいほど遠距離移動で大胆にズームアウトする。 */
        const val CAMERA_FLY_TO_RHO = 0.6

        /** fly-to の自然な所要時間に掛ける係数。大きいほどゆっくり動く。 */
        const val CAMERA_FLY_TO_SPEED_SCALE = 1.0

        /** fly-to の所要時間の下限（ms）。ごく短い移動でも一瞬で飛ばないようにする。 */
        const val MIN_FLY_TO_DURATION_MS = 1500L

        /** fly-to の所要時間の上限（ms）。遠距離移動で長く待たせすぎないための上限。 */
        const val MAX_FLY_TO_DURATION_MS = 3000L

        /** GoogleMap camera position を即時反映する既定処理。 */
        val DEFAULT_MOVE_CAMERA: (GoogleMap, CameraPosition) -> Unit = { map, cameraPosition ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }

        /**
         * ズーム差に応じて zoom 側のアニメーション時間を
         * [CAMERA_PAN_DURATION_MS]〜[CAMERA_ZOOM_DURATION_MS] の範囲で線形に決める。
         *
         * @param zoomDelta ズーム差
         * @return zoom animation duration（ms）
         */
        fun cameraZoomDurationMs(zoomDelta: Float): Long {
            val ratio = (zoomDelta / FULL_ZOOM_DELTA).coerceIn(0f, 1f)
            return CAMERA_PAN_DURATION_MS + ((CAMERA_ZOOM_DURATION_MS - CAMERA_PAN_DURATION_MS) * ratio).toLong()
        }
    }
}
