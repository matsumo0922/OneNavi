package me.matsumo.onenavi.feature.map.state

/**
 * 地図表示で使う補間 helper。
 */
internal object MapInterpolation {

    /**
     * 2 つの [Double] 値を線形補間する。
     *
     * @param from 開始値
     * @param to 終了値
     * @param fraction 補間率
     * @return 補間後の値
     */
    fun lerp(from: Double, to: Double, fraction: Double): Double =
        from + (to - from) * fraction

    /**
     * 2 つの [Double] 値を線形補間する。
     *
     * @param from 開始値
     * @param to 終了値
     * @param fraction 補間率
     * @return 補間後の値
     */
    fun lerp(from: Double, to: Double, fraction: Float): Double =
        lerp(from = from, to = to, fraction = fraction.toDouble())

    /**
     * 2 つの [Float] 値を線形補間する。
     *
     * @param from 開始値
     * @param to 終了値
     * @param fraction 補間率
     * @return 補間後の値
     */
    fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    /**
     * 方位角を 360 度境界をまたぐ場合も短い回転方向で補間する。
     *
     * @param from 開始角度
     * @param to 終了角度
     * @param fraction 補間率
     * @return 0〜360 度に正規化した補間後角度
     */
    fun lerpAngleDegrees(from: Float, to: Float, fraction: Float): Float {
        val delta = MapGeodesy.shortestAngleDeltaDegrees(from = from, to = to)
        return MapGeodesy.normalizeBearingDegrees(from + delta * fraction)
    }
}
