package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.ui.theme.RouteColors.accent
import me.matsumo.onenavi.core.ui.theme.RouteColors.callOut
import me.matsumo.onenavi.core.ui.theme.RouteColors.maneuver
import me.matsumo.onenavi.core.ui.theme.RouteColors.polyline

/**
 * ルート / 道路種別ごとの色定義の統一エントリポイント。
 *
 * 用途ごとの色が混ざると案内中 UI の視認性が崩れるため、表示面ごとに色セットを分ける。
 * - [polyline]: 地図上のルート描画用。高速＝青系、一般道＝緑系。
 *   他社地図 (Google Maps 等) と並べた時の視認性に合わせる。
 * - [callOut]: 地図上の選択 CallOut 用。道路種別では変えず、route 選択状態の青として扱う。
 * - [accent]: ルートプレビュー・IC バッジなどの補助 UI 用。高速＝緑系、一般道＝青系。
 *   実際の道路標識色 (高速＝緑、一般道＝青) に合わせる。
 * - [maneuver]: 案内中の Maneuver 表示用。[polyline] より濃くし、白い案内アイコンを優先して読ませる。
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
     * 補助 UI 用の色セット。Material3 の primary / onPrimary / primaryContainer / onPrimaryContainer 相当。
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

    /**
     * 地図上の CallOut 用の色セット。
     *
     * @param selectedContainer 選択中 CallOut の内側背景色
     * @param onSelectedContainer [selectedContainer] の上に乗せるコンテンツ色
     * @param unselectedContent 非選択 CallOut のコンテンツ色
     */
    @Immutable
    data class CallOutColors(
        val selectedContainer: Color,
        val onSelectedContainer: Color,
        val unselectedContent: Color,
    )

    /**
     * 案内中の Maneuver 表示用の色セット。Material3 の primary / onPrimary / primaryContainer /
     * onPrimaryContainer 相当。
     *
     * @param primary 主色 (上部 Maneuver バナー・route 上矢印の外側色)
     * @param onPrimary [primary] の上に乗せるコンテンツ色
     * @param container 主色よりさらに濃い補助領域色
     * @param onContainer [container] の上に乗せるコンテンツ色
     */
    @Immutable
    data class ManeuverColors(
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

    private val RouteCallOut = CallOutColors(
        selectedContainer = Color(0xFF1A73E8),
        onSelectedContainer = Color(0xFFFFFFFF),
        unselectedContent = Color(0xFF202124),
    )

    private val HighwayManeuver = ManeuverColors(
        primary = Color(0xFF005C46),
        onPrimary = Color(0xFFFFFFFF),
        container = Color(0xFF003C2F),
        onContainer = Color(0xFFE8FFF5),
    )

    private val OrdinaryManeuver = ManeuverColors(
        primary = Color(0xFF0B3D91),
        onPrimary = Color(0xFFFFFFFF),
        container = Color(0xFF082B66),
        onContainer = Color(0xFFEAF2FF),
    )

    /**
     * 地図上の CallOut 用色。
     */
    val callOut: CallOutColors = RouteCallOut

    fun polyline(roadClass: RoadClass): PolylineColors = when (roadClass) {
        RoadClass.HIGHWAY -> HighwayPolyline
        RoadClass.ORDINARY -> OrdinaryPolyline
    }

    fun accent(roadClass: RoadClass): AccentColors = when (roadClass) {
        RoadClass.HIGHWAY -> HighwayAccent
        RoadClass.ORDINARY -> OrdinaryAccent
    }

    fun maneuver(roadClass: RoadClass): ManeuverColors = when (roadClass) {
        RoadClass.HIGHWAY -> HighwayManeuver
        RoadClass.ORDINARY -> OrdinaryManeuver
    }
}
