package me.matsumo.onenavi.core.navigation.newguidance.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSide

/**
 * レーンを UI に出せる形へ整形した presentation 値 (L3)。
 *
 * semantic の [me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane] は
 * marker 由来 (視覚配列) とテキスト由来 (側 + 本数) で持てる情報が異なるため、表示形を
 * source ごとに分ける。未確定 source は projection 段階で落とし、ここには「描画できると
 * 判断したレーン」だけが現れる。
 */
@Immutable
sealed interface LanePresentation {

    /**
     * 車線ごとのアイコンを並べて見せる視覚レーン (marker 由来)。
     *
     * @property lanes 左から右の順に並んだ車線セル
     */
    @Immutable
    data class VisualLanes(
        val lanes: ImmutableList<LaneCell>,
    ) : LanePresentation

    /**
     * 「右側 2 車線」のように側 + 本数だけをテキストで見せるレーン (テキスト由来)。
     *
     * @property side 寄せる側
     * @property laneCount 該当する車線数。不明なら null
     */
    @Immutable
    data class SideInstructionText(
        val side: LaneSide,
        val laneCount: Int?,
    ) : LanePresentation

    /**
     * 車線減少・専用レーン等の警告をテキストで見せるレーン。
     *
     * @property text 表示する警告文
     */
    @Immutable
    data class WarningText(
        val text: String,
    ) : LanePresentation
}

/**
 * 視覚レーン 1 車線分の表示データ。
 *
 * @property allowedDirections この車線で許可される進行方向
 * @property recommendedDirection 推奨される進行方向。推奨車線でなければ null
 * @property isActive 案内中の推奨車線として強調するか
 */
@Immutable
data class LaneCell(
    val allowedDirections: ImmutableList<ManeuverModifier>,
    val recommendedDirection: ManeuverModifier?,
    val isActive: Boolean,
)
