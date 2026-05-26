package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import me.matsumo.onenavi.core.model.ManeuverModifier

/**
 * 案内イベントに付随するレーン情報 (semantic 層)。
 *
 * レーンを「視覚配列 ([layout])」「指示 ([instruction])」「警告 ([warning])」「出所
 * ([sources])」「確度 ([confidence])」に直交分解して保持する。marker 由来 (総数既知・
 * 値の意味は不確実) とテキスト由来 (側 + 本数・総数不明) では持てる情報が異なるため、
 * 単一の表示形に早期抽象化せず source 段階の差を残す。
 *
 * @property layout 視覚的なレーン配列。得られなければ null。
 * @property instruction どの車線をどうするかの指示。得られなければ null。
 * @property warning レーン減少・専用レーン等の警告。無ければ null。
 * @property sources このレーン情報の出所 (marker / text の合成があり得る)。
 * @property confidence レーン情報の確度。marker 値の意味が未確定な間は必須。
 * @property sourceRefs 元データへの参照。
 */
@Immutable
data class GuidanceLane(
    val layout: LaneLayout?,
    val instruction: LaneInstruction?,
    val warning: LaneWarning?,
    val sources: ImmutableSet<LaneSource>,
    val confidence: LaneConfidence,
    val sourceRefs: ImmutableList<SourceRef>,
)

/** レーンの視覚配列。 */
@Immutable
sealed interface LaneLayout {
    /**
     * marker 由来の per-lane 配列 (総数は既知)。
     *
     * @property lanes 左から右の順に並んだレーンマーカー。
     * @property kind marker の種別 (料金所系 / 分岐系 等)。不明なら null。
     */
    @Immutable
    data class MarkerLayout(
        val lanes: ImmutableList<LaneMark>,
        val kind: Int?,
    ) : LaneLayout

    /**
     * テキスト由来の「側 + 本数」(総数不明)。
     *
     * @property side 寄せる側。
     * @property laneCount 該当する車線数。不明なら null。
     * @property totalLaneCount 全車線数。テキスト由来では通常不明 (null)。
     */
    @Immutable
    data class SideCount(
        val side: LaneSide,
        val laneCount: Int?,
        val totalLaneCount: Int?,
    ) : LaneLayout
}

/**
 * 1 車線分のマーカー (marker 由来)。
 *
 * @property rawA marker の生値 A (`1` で推奨車線を示すと観測)。
 * @property rawB marker の生値 B (進行方向ビット等。意味は未確定)。
 */
@Immutable
data class LaneMark(
    val rawA: Int,
    val rawB: Int,
) {
    /** この車線が推奨車線か。 */
    val isRecommended: Boolean get() = rawA == RECOMMENDED_VALUE

    private companion object {
        const val RECOMMENDED_VALUE: Int = 1
    }
}

/** レーンの指示 (どの車線をどうするか)。テキスト解析経路で本格的に埋める。 */
@Immutable
sealed interface LaneInstruction {
    /**
     * 指定の側に寄り続ける指示。
     *
     * @property side 寄せる側。
     * @property laneCount 対象車線数。不明なら null。
     * @property isJamAware 渋滞考慮レーン (`HighwayRecommendedLaneJamAware` 由来) か。
     */
    @Immutable
    data class KeepSide(
        val side: LaneSide,
        val laneCount: Int?,
        val isJamAware: Boolean,
    ) : LaneInstruction

    /**
     * 指定の側へ進入する指示。
     *
     * @property side 進入する側。
     * @property laneCount 対象車線数。不明なら null。
     */
    @Immutable
    data class EnterSide(
        val side: LaneSide,
        val laneCount: Int?,
    ) : LaneInstruction
}

/** レーンの警告。テキスト解析経路で本格的に埋める。 */
@Immutable
sealed interface LaneWarning {
    /**
     * 車線減少。
     *
     * @property side 減少する側。不明なら null。
     */
    @Immutable
    data class Reduction(val side: LaneSide?) : LaneWarning

    /**
     * 専用レーン (バス専用等)。
     *
     * @property side 該当する側。不明なら null。
     * @property direction 専用方向。不明なら null。
     */
    @Immutable
    data class DedicatedLane(
        val side: LaneSide?,
        val direction: ManeuverModifier?,
    ) : LaneWarning
}

/** レーンの寄せ側。 */
enum class LaneSide { LEFT, CENTER, RIGHT }

/** レーン情報の出所。 */
enum class LaneSource {
    /** marker (構造化レーンデータ) 由来。 */
    MARKER,

    /** 発話テキスト / テンプレート由来。 */
    TEXT,
}

/** レーン情報の確度。 */
enum class LaneConfidence {
    /** 値の意味が確定している (高)。 */
    HIGH,

    /** 推定を含む (中)。 */
    MEDIUM,

    /** 値の意味が未確定 (低)。 */
    LOW,
}
