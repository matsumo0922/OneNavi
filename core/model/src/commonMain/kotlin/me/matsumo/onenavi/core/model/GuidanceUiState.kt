package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ナビゲーション中の UI 表示に必要な全情報を集約したデータクラス。
 * GuidanceSessionManager が Observer 群から収集し、StateFlow で公開する。
 *
 * @param currentManeuver 現在の（次に到達する）マニューバ情報。null は初期化中。
 * @param nextManeuver その次のマニューバ情報。null の場合は非表示。
 * @param tripProgress トリップ進捗（残距離・残時間・ETA）
 * @param isOffRoute 経路から逸脱中かどうか
 * @param isTtsAvailable TTS エンジンが利用可能かどうか
 */
@Immutable
data class GuidanceUiState(
    val currentManeuver: ManeuverInfo?,
    val nextManeuver: ManeuverInfo?,
    val tripProgress: TripProgressInfo,
    val isOffRoute: Boolean,
    val isTtsAvailable: Boolean,
) {
    companion object {
        /** 初期状態。ナビ開始直後、最初の RouteProgress を受信するまで使用する。 */
        val Initial = GuidanceUiState(
            currentManeuver = null,
            nextManeuver = null,
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = 0.0,
                durationRemainingSeconds = 0.0,
                estimatedArrivalTimeMillis = 0L,
            ),
            isOffRoute = false,
            isTtsAvailable = false,
        )
    }
}
