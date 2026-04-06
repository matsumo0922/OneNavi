package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * ナビゲーション中の UI 表示に必要な全情報を集約したデータクラス。
 * GuidanceSessionManager が Observer 群から収集し、StateFlow で公開する。
 *
 * @param currentManeuver 現在の（次に到達する）マニューバ情報。null は初期化中。
 * @param nextManeuver その次のマニューバ情報。null の場合は非表示。
 * @param upcomingSteps 現在位置から先の全ステップ一覧（パネル表示用）
 * @param tripProgress トリップ進捗（残距離・残時間・ETA）
 * @param currentRoadName 現在走行中の道路名。null の場合はバッジ非表示。
 * @param isOffRoute 経路から逸脱中かどうか
 * @param isTtsAvailable TTS エンジンが利用可能かどうか
 * @param isLocationStale GPS 信号が途絶えているかどうか
 */
@Immutable
data class GuidanceUiState(
    val currentManeuver: ManeuverInfo?,
    val nextManeuver: ManeuverInfo?,
    val upcomingSteps: ImmutableList<RouteStepInfo>,
    val tripProgress: TripProgressInfo,
    val currentRoadName: String?,
    val isOffRoute: Boolean,
    val isTtsAvailable: Boolean,
    val isLocationStale: Boolean,
) {
    companion object {
        /** 初期状態。ナビ開始直後、最初の RouteProgress を受信するまで使用する。 */
        val Initial = GuidanceUiState(
            currentManeuver = null,
            nextManeuver = null,
            upcomingSteps = persistentListOf(),
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = 0.0,
                durationRemainingSeconds = 0.0,
                estimatedArrivalTimeMillis = 0L,
            ),
            currentRoadName = null,
            isOffRoute = false,
            isTtsAvailable = false,
            isLocationStale = false,
        )
    }
}
