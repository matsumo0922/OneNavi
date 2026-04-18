package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * 次のマニューバ（曲がる地点）の情報。
 * Google Routes API / Navigation SDK の案内情報から変換される。
 *
 * @param type マニューバ種別。
 * @param modifier 方向修飾子。方向を持たないマニューバの場合は null。
 * @param degrees ロータリー等で使用される進入角度（度）。無ければ null。
 * @param drivingSide 走行側。アイコン生成時のミラー判定に使用。
 * @param distanceMeters 次のマニューバまでの残り距離（メートル）。
 * @param instruction 交差点名 / JCT 名 / Google が生成した案内テキスト。
 * @param roadName 進入後の道路名（Navigation SDK の fullRoadName）。長く詳細な形式。
 * @param simpleRoadName 進入後の道路名の短縮版（Navigation SDK の simpleRoadName）。
 *   Callout 等、短く表示したい場面向け。
 * @param destinations 方面情報（step.destinations）。
 * @param iconBitmap Navigation SDK が生成したマニューバアイコンの bitmap。
 *   `GeneratedStepImagesType.BITMAP` を有効化したセッションでのみ供給される。
 * @param lanesBitmap Navigation SDK が生成したレーン情報の bitmap（1 ステップ分まとめて 1 枚）。
 * @param lanes レーン情報。Navigation SDK の turn-by-turn feed で取得できなかった場合は空。
 */
@Immutable
data class ManeuverInfo(
    val type: ManeuverType,
    val modifier: ManeuverModifier?,
    val degrees: Double? = null,
    val drivingSide: DrivingSide? = null,
    val distanceMeters: Double,
    val instruction: String,
    val roadName: String? = null,
    val simpleRoadName: String? = null,
    val destinations: String? = null,
    val iconBitmap: ImageBitmap? = null,
    val lanesBitmap: ImageBitmap? = null,
    val lanes: ImmutableList<LaneInfo> = persistentListOf(),
)
