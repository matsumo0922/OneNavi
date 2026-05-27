package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneDirectionCell
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import me.matsumo.onenavi.core.navigation.newguidance.semantic.SourceRef
import me.matsumo.drive.supporter.api.guidance.domain.FlagsGroupEntry as ExtNavFlagsGroupEntry

/**
 * GP の `flags_group` を、一般道交差点 / 高速入口の車線図 ([LaneLayout.DirectionLayout]) として
 * 解釈する parser。
 *
 * `flags_group` は車線以外の離散メタにも使われる汎用フラグ配列のため、「2 レーン以上が既知方向
 * (左 / 直 / 右) を持つ」ものだけを車線図とみなす guard を置く。各 entry が左から 1 レーンで、
 * `directions` が進行方向コード、`b` がターゲット (推奨 / 強調) レーンを表す。状態を持たない。
 */
internal class LaneDiagramParser {

    /**
     * `flags_group` の entry 列を車線図レーンへ変換する。車線図と判定できなければ null。
     *
     * @param entries GP の `flags_group` entry 列 (左から右の順)
     * @param sourceRefs 元データへの参照
     * @return 方向付き車線図の [GuidanceLane]。車線図でなければ null
     */
    fun parse(
        entries: List<ExtNavFlagsGroupEntry>,
        sourceRefs: ImmutableList<SourceRef>,
    ): GuidanceLane? {
        if (entries.size < MIN_LANE_COUNT) return null

        val cells = entries.map { entry -> entry.toCell() }
        val lanesWithDirection = cells.count { cell -> cell.directions.isNotEmpty() }
        if (lanesWithDirection < MIN_LANE_COUNT) return null

        return GuidanceLane(
            layout = LaneLayout.DirectionLayout(lanes = cells.toImmutableList()),
            instruction = null,
            warning = null,
            sources = persistentSetOf(LaneSource.LANE_DIAGRAM),
            confidence = LaneConfidence.HIGH,
            sourceRefs = sourceRefs,
        )
    }

    /**
     * 1 entry を方向付きレーンセルへ変換する。未確定の方向コードは捨てる。
     *
     * @return 方向 / ターゲット / 付加フラグを持つレーンセル
     */
    private fun ExtNavFlagsGroupEntry.toCell(): LaneDirectionCell {
        val modifiers = directions.mapNotNull { code -> code.toModifierOrNull() }
        return LaneDirectionCell(
            directions = modifiers.toImmutableSet(),
            isTarget = b == TARGET_LANE_FLAG,
            isAppend = a == APPEND_LANE_FLAG,
        )
    }

    /**
     * `flags_group` の方向コードを [ManeuverModifier] へ変換する。未確定コード (5 / 7 等) は null。
     *
     * @return 対応する方向。未確定なら null
     */
    private fun Int.toModifierOrNull(): ManeuverModifier? = when (this) {
        DIRECTION_LEFT -> ManeuverModifier.LEFT
        DIRECTION_STRAIGHT -> ManeuverModifier.STRAIGHT
        DIRECTION_RIGHT -> ManeuverModifier.RIGHT
        else -> null
    }

    private companion object {
        /** 車線図とみなす最小レーン数。 */
        private const val MIN_LANE_COUNT: Int = 2

        /** ターゲット (推奨 / 強調) レーンを示す `b` の値。 */
        private const val TARGET_LANE_FLAG: Int = 1

        /** 付加 / 側方レーンを示す `a` の値。 */
        private const val APPEND_LANE_FLAG: Int = 1

        /** 左折を示す方向コード。 */
        private const val DIRECTION_LEFT: Int = 2

        /** 直進を示す方向コード。 */
        private const val DIRECTION_STRAIGHT: Int = 4

        /** 右折を示す方向コード。 */
        private const val DIRECTION_RIGHT: Int = 6
    }
}
