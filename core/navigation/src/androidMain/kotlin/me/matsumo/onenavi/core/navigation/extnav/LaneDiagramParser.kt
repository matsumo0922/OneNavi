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
 * `flags_group` は車線以外の離散メタにも使われる汎用フラグ配列のため、「2 レーン以上が既知方向を
 * 持つ」ものだけを車線図とみなす guard を置く。各 entry が左から 1 レーンで、`compactDirections` が
 * 進行方向 compact code (1..8)、`b` がターゲット (推奨 / 強調) レーンを表す。状態を持たない。
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
     * 1 entry を方向付きレーンセルへ変換する。未確定の compact code は捨てる。
     *
     * @return 方向 / ターゲット / 付加フラグを持つレーンセル
     */
    private fun ExtNavFlagsGroupEntry.toCell(): LaneDirectionCell {
        val modifiers = compactDirections.mapNotNull { code -> code.toModifierOrNull() }
        return LaneDirectionCell(
            directions = modifiers.toImmutableSet(),
            isTarget = b == TARGET_LANE_FLAG,
            isAppend = a == APPEND_LANE_FLAG,
        )
    }

    /**
     * `flags_group` の compact code (1..8) を [ManeuverModifier] へ変換する。範囲外は null。
     *
     * 手前左 / 手前右 (this-side) は鋭角の左右折、斜め左 / 斜め右 (slant) はやや左右に対応させる。
     *
     * @return 対応する方向。範囲外なら null
     */
    private fun Int.toModifierOrNull(): ManeuverModifier? = when (this) {
        CODE_SLANT_LEFT -> ManeuverModifier.SLIGHT_LEFT
        CODE_LEFT -> ManeuverModifier.LEFT
        CODE_THIS_SIDE_LEFT -> ManeuverModifier.SHARP_LEFT
        CODE_STRAIGHT -> ManeuverModifier.STRAIGHT
        CODE_SLANT_RIGHT -> ManeuverModifier.SLIGHT_RIGHT
        CODE_RIGHT -> ManeuverModifier.RIGHT
        CODE_THIS_SIDE_RIGHT -> ManeuverModifier.SHARP_RIGHT
        CODE_UTURN -> ManeuverModifier.UTURN
        else -> null
    }

    private companion object {
        /** 車線図とみなす最小レーン数。 */
        private const val MIN_LANE_COUNT: Int = 2

        /** ターゲット (推奨 / 強調) レーンを示す `b` の値。 */
        private const val TARGET_LANE_FLAG: Int = 1

        /** 付加 / 側方レーンを示す `a` の値。 */
        private const val APPEND_LANE_FLAG: Int = 1

        /** 斜め左を示す compact code。 */
        private const val CODE_SLANT_LEFT: Int = 1

        /** 左折を示す compact code。 */
        private const val CODE_LEFT: Int = 2

        /** 手前左を示す compact code。 */
        private const val CODE_THIS_SIDE_LEFT: Int = 3

        /** 直進を示す compact code。 */
        private const val CODE_STRAIGHT: Int = 4

        /** 斜め右を示す compact code。 */
        private const val CODE_SLANT_RIGHT: Int = 5

        /** 右折を示す compact code。 */
        private const val CODE_RIGHT: Int = 6

        /** 手前右を示す compact code。 */
        private const val CODE_THIS_SIDE_RIGHT: Int = 7

        /** U ターンを示す compact code。 */
        private const val CODE_UTURN: Int = 8
    }
}
