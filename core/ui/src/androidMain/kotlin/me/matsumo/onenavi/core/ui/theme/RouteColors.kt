package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.ui.theme.RouteColors.accent
import me.matsumo.onenavi.core.ui.theme.RouteColors.polyline

/**
 * ルート / 道路種別ごとの色定義の統一エントリポイント。
 *
 * 用途によって高速・一般道の色が反転している点に注意:
 * - [polyline]: 地図上のルート描画用。高速＝青系、一般道＝緑系。
 *   他社地図 (Google Maps 等) と並べた時の視認性に合わせる。
 * - [accent]: 案内カード・所要時間表示などの UI 用。高速＝緑系、一般道＝青系。
 *   実際の道路標識色 (高速＝緑、一般道＝青) に合わせる。
 */
object RouteColors {

    /**
     * Polyline 描画用の色セット。
     *
     * @param border 枠線 (外側) の濃色
     * @param body 本体 (内側) の明色
     */
    @Immutable
    data class PolylineColors(
        val border: Color,
        val body: Color,
    )

    /**
     * 案内 UI 用の色セット。Material3 の primary / onPrimary / primaryContainer / onPrimaryContainer 相当。
     *
     * @param primary 主色 (ボタン背景・強調テキスト等)
     * @param onPrimary [primary] の上に乗せるコンテンツ色
     * @param container 主色の弱いトーン (バッジ・チップ等の背景)
     * @param onContainer [container] の上に乗せるコンテンツ色
     */
    @Immutable
    data class AccentColors(
        val primary: Color,
        val onPrimary: Color,
        val container: Color,
        val onContainer: Color,
    )

    private val HighwayPolyline = PolylineColors(
        border = Color(0xFF1A56C7),
        body = Color(0xFF5AB7FF),
    )

    private val OrdinaryPolyline = PolylineColors(
        border = Color(0xFF146C34),
        body = Color(0xFF8BD24A),
    )

    private val HighwayAccent = AccentColors(
        primary = Color(0xFF2E7D32),
        onPrimary = Color(0xFFFFFFFF),
        container = Color(0xFFC8E6C9),
        onContainer = Color(0xFF0E3A12),
    )

    private val OrdinaryAccent = AccentColors(
        primary = Color(0xFF1976D2),
        onPrimary = Color(0xFFFFFFFF),
        container = Color(0xFFBBDEFB),
        onContainer = Color(0xFF0D3A66),
    )

    fun polyline(roadClass: RoadClass): PolylineColors = when (roadClass) {
        RoadClass.HIGHWAY -> HighwayPolyline
        RoadClass.ORDINARY -> OrdinaryPolyline
    }

    fun accent(roadClass: RoadClass): AccentColors = when (roadClass) {
        RoadClass.HIGHWAY -> HighwayAccent
        RoadClass.ORDINARY -> OrdinaryAccent
    }
}
