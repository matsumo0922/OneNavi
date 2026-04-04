package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 目的地到着時の集計情報。到着画面に表示する。
 *
 * @param destinationName 目的地の名前
 * @param totalDistanceMeters 総走行距離（メートル）
 * @param totalDurationSeconds 総走行時間（秒）
 */
@Immutable
data class ArrivalInfo(
    val destinationName: String,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
)
