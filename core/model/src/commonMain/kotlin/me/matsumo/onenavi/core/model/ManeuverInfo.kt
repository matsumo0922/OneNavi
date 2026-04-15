package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * 次のマニューバ（曲がる地点）の情報。
 * Google Routes API / Navigation SDK の案内情報から変換される。
 *
 * @param type マニューバ種別（"turn", "fork", "merge", "on ramp", "off ramp", "arrive" 等）
 * @param modifier 方向修飾子（"left", "right", "slight left", "sharp right", "straight", "uturn" 等）
 * @param degrees ロータリー等で使用される進入角度（度）。無ければ null
 * @param drivingSide 走行側（"right" / "left"）。アイコン生成時に使用
 * @param distanceMeters 次のマニューバまでの残り距離（メートル）
 * @param instruction 交差点名 / JCT 名 / Google が生成した案内テキスト
 * @param roadName 進入後の道路名（step.name）
 * @param destinations 方面情報（step.destinations）
 * @param lanes レーン情報。交差点接近中のみ SDK が sub banner に含める
 */
@Immutable
data class ManeuverInfo(
    val type: String,
    val modifier: String?,
    val degrees: Double? = null,
    val drivingSide: String? = null,
    val distanceMeters: Double,
    val instruction: String,
    val roadName: String? = null,
    val destinations: String? = null,
    val lanes: ImmutableList<LaneInfo> = persistentListOf(),
)
