package me.matsumo.onenavi.core.navigation.newguidance.presentation

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneDirectionCell
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark

/**
 * semantic の [GuidanceLane] を表示用 [LanePresentation] へ整形する factory (L3)。
 *
 * 視覚レーンとして描けるのは構造化レーン 2 系統:
 * - marker layout (`lane_markers` 由来。料金所・高速ゲート)
 * - direction layout (`flags_group` 由来。一般道交差点 / 高速入口の方向付き車線図)
 *
 * テキスト由来 (側 + 本数 / 警告) のレーンは発話 (音声案内) で伝える方針のため視覚レーンにはしない。
 * marker のレーン矢印の向きは主案内の有無に依らず geometry から決まる値を呼び出し側が渡す。direction
 * layout は各レーンが実際の向きを持つので、ターゲットレーンの強調方向だけ maneuver 方向で曖昧解消する。
 * 状態を持たない。
 */
internal class LanePresentationFactory {

    /**
     * レーンが視覚レーンとして描画できるかを返す。
     *
     * marker layout は推奨車線が 1 つ以上ある場合、direction layout は方向を持つレーンが 1 つ以上ある
     * 場合に true。フルリストの detail 枠選択やバナー下段でレーンを出すかの判断に使う。
     *
     * @param lane 判定対象のレーン
     * @return 視覚レーンとして描画できる場合 true
     */
    fun canRenderVisual(lane: GuidanceLane): Boolean = when (val layout = lane.layout) {
        is LaneLayout.MarkerLayout -> layout.lanes.any { mark -> mark.isRecommended }
        is LaneLayout.DirectionLayout -> layout.lanes.any { cell -> cell.directions.isNotEmpty() }
        else -> false
    }

    /**
     * レーンを表示用 [LanePresentation] へ変換する。描画対象が無ければ null。
     *
     * marker layout と direction layout を視覚レーンにする。テキスト由来のレーンは音声案内で伝えるため
     * 視覚レーンにはしない。
     *
     * @param lane semantic レーン情報
     * @param recommendedDirection marker の推奨車線に付ける進行方向 / direction layout のターゲット強調の曖昧解消に使う maneuver 方向 (geometry の方位差由来)
     * @return 表示用レーン。描画対象が無い場合は null
     */
    fun create(
        lane: GuidanceLane,
        recommendedDirection: ManeuverModifier,
    ): LanePresentation? = when (val layout = lane.layout) {
        is LaneLayout.MarkerLayout -> markerPresentation(layout = layout, recommendedDirection = recommendedDirection)
        is LaneLayout.DirectionLayout -> directionPresentation(layout = layout, maneuverDirection = recommendedDirection)
        else -> null
    }

    /**
     * marker layout を視覚レーンへ変換する。推奨車線が無ければ null。
     *
     * @param layout marker 由来のレーン配列
     * @param recommendedDirection 推奨車線に付ける進行方向
     * @return 視覚レーン。描画対象が無い場合は null
     */
    private fun markerPresentation(
        layout: LaneLayout.MarkerLayout,
        recommendedDirection: ManeuverModifier,
    ): LanePresentation? {
        if (layout.lanes.isEmpty()) return null
        if (layout.lanes.none { mark -> mark.isRecommended }) return null

        val cells = layout.lanes.map { mark -> toLaneCell(mark = mark, recommendedDirection = recommendedDirection) }
        return LanePresentation.VisualLanes(lanes = cells.toImmutableList())
    }

    /**
     * direction layout (車線図) を視覚レーンへ変換する。方向を持つレーンが無ければ null。
     *
     * @param layout flags_group 由来の方向付きレーン配列
     * @param maneuverDirection ターゲットレーンの強調方向を曖昧解消するための maneuver 方向
     * @return 視覚レーン。描画対象が無い場合は null
     */
    private fun directionPresentation(
        layout: LaneLayout.DirectionLayout,
        maneuverDirection: ManeuverModifier,
    ): LanePresentation? {
        if (layout.lanes.none { cell -> cell.directions.isNotEmpty() }) return null

        val cells = layout.lanes.map { cell -> toLaneCell(cell = cell, maneuverDirection = maneuverDirection) }
        return LanePresentation.VisualLanes(lanes = cells.toImmutableList())
    }

    /**
     * marker の 1 車線を表示用セルへ変換する。
     *
     * @param mark marker の 1 車線
     * @param recommendedDirection 推奨車線に付ける進行方向
     * @return 表示用レーンセル
     */
    private fun toLaneCell(
        mark: LaneMark,
        recommendedDirection: ManeuverModifier,
    ): LaneCell = LaneCell(
        allowedDirections = persistentListOf(recommendedDirection),
        recommendedDirection = recommendedDirection.takeIf { mark.isRecommended },
        isActive = mark.isRecommended,
    )

    /**
     * 車線図の 1 車線を表示用セルへ変換する。
     *
     * allowedDirections はレーンの実際の向きを左→右の正準順に並べる。ターゲットレーンは強調方向を
     * 付ける (maneuver 方向がレーンの向きに含まれればそれを、無ければ正準順の先頭を採用)。
     *
     * @param cell 車線図の 1 車線
     * @param maneuverDirection ターゲット強調の曖昧解消に使う maneuver 方向
     * @return 表示用レーンセル
     */
    private fun toLaneCell(
        cell: LaneDirectionCell,
        maneuverDirection: ManeuverModifier,
    ): LaneCell {
        val ordered = cell.directions.sortedBy { direction -> direction.laneRank() }
        val highlight = if (cell.isTarget) targetHighlight(ordered = ordered, maneuverDirection = maneuverDirection) else null
        return LaneCell(
            allowedDirections = ordered.toImmutableList(),
            recommendedDirection = highlight,
            isActive = cell.isTarget,
        )
    }

    /**
     * ターゲットレーンの強調方向を決める。maneuver 方向がレーンの向きに含まれればそれを優先する。
     *
     * @param ordered 正準順に並んだレーンの向き
     * @param maneuverDirection この地点の maneuver 方向
     * @return 強調する向き。レーンが向きを持たなければ null
     */
    private fun targetHighlight(
        ordered: List<ManeuverModifier>,
        maneuverDirection: ManeuverModifier,
    ): ManeuverModifier? {
        if (maneuverDirection in ordered) return maneuverDirection
        return ordered.firstOrNull()
    }

    /**
     * 車線アイコンを左→右に並べるための正準順位を返す。
     *
     * @return 左ほど小さい順位
     */
    private fun ManeuverModifier.laneRank(): Int = when (this) {
        ManeuverModifier.SHARP_LEFT -> 0
        ManeuverModifier.LEFT -> 1
        ManeuverModifier.SLIGHT_LEFT -> 2
        ManeuverModifier.STRAIGHT -> 3
        ManeuverModifier.UTURN -> 4
        ManeuverModifier.SLIGHT_RIGHT -> 5
        ManeuverModifier.RIGHT -> 6
        ManeuverModifier.SHARP_RIGHT -> 7
    }
}
