package me.matsumo.onenavi.core.navigation.newguidance.presentation

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark

/**
 * semantic の [GuidanceLane] を表示用 [LanePresentation] へ整形する factory (L3)。
 *
 * marker 由来の視覚配列だけを「描画できるレーン」とみなして [LanePresentation.VisualLanes] に
 * する。テキスト由来 (側 + 本数) のレーンは発話テキスト解析経路 (別タスク) で実データが入るまで
 * 未確定扱いとし、視覚レーンとしては描かない。レーン矢印の向きは主案内の有無に依らず geometry
 * から決まる値を呼び出し側が渡す。状態を持たない。
 */
internal class LanePresentationFactory {

    /**
     * レーンが視覚レーンとして描画できるかを返す。
     *
     * marker layout かつ推奨車線が 1 つ以上ある場合だけ true。フルリストの detail 枠選択や
     * バナー下段でレーンを出すかの判断に使う。
     *
     * @param lane 判定対象のレーン
     * @return 視覚レーンとして描画できる場合 true
     */
    fun canRenderVisual(lane: GuidanceLane): Boolean {
        val layout = lane.layout as? LaneLayout.MarkerLayout ?: return false
        if (layout.lanes.isEmpty()) return false
        return layout.lanes.any { mark -> mark.isRecommended }
    }

    /**
     * レーンを表示用 [LanePresentation] へ変換する。描画対象が無ければ null。
     *
     * 現状は marker 由来の視覚レーンのみを生成する。テキスト由来 (側 + 本数) のレーンは
     * 発話テキスト解析経路 (別タスク) で実データが入るまで生成しない。
     *
     * @param lane semantic レーン情報
     * @param recommendedDirection 推奨車線に付ける進行方向 (geometry の方位差由来)
     * @return 表示用レーン。描画対象が無い場合は null
     */
    fun create(
        lane: GuidanceLane,
        recommendedDirection: ManeuverModifier,
    ): LanePresentation? {
        val layout = lane.layout as? LaneLayout.MarkerLayout ?: return null
        return markerPresentation(layout = layout, recommendedDirection = recommendedDirection)
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
     * 1 車線分のマーカーを表示用セルへ変換する。
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
}
