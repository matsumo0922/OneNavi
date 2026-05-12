package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ナビゲーション中のトリップ進捗情報。
 * RouteProgress から毎秒更新される。
 *
 * @param distanceRemainingMeters 目的地（または次の経由地）までの残り距離（メートル）
 * @param durationRemainingSeconds 目的地（または次の経由地）までの残り時間（秒）
 * @param estimatedArrivalTimeMillis 到着予想時刻（エポックミリ秒）
 */
@Immutable
data class TripProgressInfo(
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
    val estimatedArrivalTimeMillis: Long,
)
