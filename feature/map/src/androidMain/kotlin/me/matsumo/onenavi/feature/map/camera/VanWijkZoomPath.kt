package me.matsumo.onenavi.feature.map.camera

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath.Companion.DEFAULT_RHO
import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath.Companion.of
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * van Wijk–Nuij "Smooth and efficient zooming and panning"（IEEE InfoVis 2003）に基づく
 * fly-to カメラ経路。
 *
 * カメラを「対象平面の上空を高さ w で漂う点」とモデル化し、知覚速度がほぼ一定になる測地線を
 * 解析的に与える。始点と終点が遠いほど途中でズームアウトの弧を描き、近いと弧が消えて
 * 純粋な指数ズーム（パンなし）へ退化する。
 *
 * 座標はすべて Web Mercator のズーム 0 ワールドピクセル空間（[WebMercatorProjection] 参照）で扱う。
 * [of] で生成し、[at] に t ∈ `[0, 1]` を渡すとその時点のカメラ位置が返る（t = 0 で始点、t = 1 で終点）。
 * d3-interpolate の `interpolateZoom` 実装をそのまま移植したもの。
 */
internal class VanWijkZoomPath private constructor(
    private val sample: (Double) -> Viewport,
    /** ρ-radian で測った経路の弧長 S（符号付き）。所要時間の算出に使う。 */
    val arcLength: Double,
    /** 曲率パラメータ ρ。大きいほど大胆にズームアウトする。 */
    val rho: Double,
) {
    /**
     * ズーム 0 ワールドピクセル空間でのカメラ注視点（[worldX] / [worldY]）と、
     * ビューポート幅をズーム 0 ワールドピクセルで測った値（[viewportWidthWorldPx]）。
     * van Wijk のモデルでの (u_x, u_y, w) に対応する。
     */
    @Immutable
    data class Viewport(
        val worldX: Double,
        val worldY: Double,
        val viewportWidthWorldPx: Double,
    )

    /** t ∈ `[0, 1]` の時点のカメラ位置を返す。範囲外の t は `[0, 1]` にクランプする。 */
    fun at(t: Double): Viewport = sample(t.coerceIn(0.0, 1.0))

    /**
     * d3-interpolate と同じ式で「自然な所要時間（ミリ秒）」を返す: |S| × 1000 × ρ / √2。
     * 体感調整の係数や上限クランプは呼び出し側で行う。
     */
    fun naturalDurationMs(): Double = abs(arcLength) * MILLIS_PER_SECOND * rho / SQRT_2

    companion object {
        /** ρ の既定値。Mapbox GL JS の `flyTo` の `curve` と同じ 1.42。 */
        const val DEFAULT_RHO = 1.42

        /** 始点と終点の水平距離の二乗がこれ未満なら退化ケース（純粋な指数ズーム）として扱う。 */
        private const val DEGENERATE_DISTANCE_SQUARED = 1e-12
        private const val MILLIS_PER_SECOND = 1000.0
        private val SQRT_2 = sqrt(2.0)

        /**
         * 始点 [start] から終点 [end] への fly-to 経路を構築する。
         * [rho] は曲率（既定 [DEFAULT_RHO]）。0 以下を渡した場合は [DEFAULT_RHO] にフォールバックする。
         */
        fun of(start: Viewport, end: Viewport, rho: Double = DEFAULT_RHO): VanWijkZoomPath {
            val curvature = if (rho > 0.0) rho else DEFAULT_RHO
            val curvatureSquared = curvature * curvature
            val curvaturePow4 = curvatureSquared * curvatureSquared

            val startX = start.worldX
            val startY = start.worldY
            val startWidth = start.viewportWidthWorldPx
            val endWidth = end.viewportWidthWorldPx
            val deltaX = end.worldX - startX
            val deltaY = end.worldY - startY
            val distanceSquared = deltaX * deltaX + deltaY * deltaY

            if (distanceSquared < DEGENERATE_DISTANCE_SQUARED) {
                // u0 ≅ u1 → パンなしの純粋な指数ズーム。「近い地点では引きが消える」の極限。
                val arcLength = ln(endWidth / startWidth) / curvature
                return VanWijkZoomPath(
                    sample = { fraction ->
                        Viewport(
                            worldX = startX + fraction * deltaX,
                            worldY = startY + fraction * deltaY,
                            viewportWidthWorldPx = startWidth * exp(curvature * fraction * arcLength),
                        )
                    },
                    arcLength = arcLength,
                    rho = curvature,
                )
            }

            val distance = sqrt(distanceSquared)
            val b0 = (endWidth * endWidth - startWidth * startWidth + curvaturePow4 * distanceSquared) /
                (2.0 * startWidth * curvatureSquared * distance)
            val b1 = (endWidth * endWidth - startWidth * startWidth - curvaturePow4 * distanceSquared) /
                (2.0 * endWidth * curvatureSquared * distance)
            val r0 = ln(sqrt(b0 * b0 + 1.0) - b0)
            val r1 = ln(sqrt(b1 * b1 + 1.0) - b1)
            val arcLength = (r1 - r0) / curvature
            val coshR0 = cosh(r0)
            val sinhR0 = sinh(r0)
            return VanWijkZoomPath(
                sample = { fraction ->
                    val s = fraction * arcLength
                    val u = startWidth / (curvatureSquared * distance) *
                        (coshR0 * tanh(curvature * s + r0) - sinhR0)
                    Viewport(
                        worldX = startX + u * deltaX,
                        worldY = startY + u * deltaY,
                        viewportWidthWorldPx = startWidth * coshR0 / cosh(curvature * s + r0),
                    )
                },
                arcLength = arcLength,
                rho = curvature,
            )
        }
    }
}
