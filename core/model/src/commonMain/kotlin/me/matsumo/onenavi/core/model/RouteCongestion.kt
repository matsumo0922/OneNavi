package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の渋滞度合い。
 * Google Routes API の `speedReadingIntervals.speed` に対応する。
 */
enum class CongestionSeverity {
    /** 通常の流れ */
    NORMAL,

    /** やや渋滞（黄色表示） */
    SLOW,

    /** 強い渋滞（赤色表示） */
    TRAFFIC_JAM,

    /** 不明 */
    UNKNOWN,
}

/**
 * ルートの形状（polyline）の中で同一渋滞度合いが続く区間を表す。
 *
 * @param startPolylinePointIndex 区間開始の geometry インデックス（包含）
 * @param endPolylinePointIndex 区間終了の geometry インデックス（包含）
 * @param severity 渋滞度合い
 */
@Immutable
data class CongestionSegment(
    val startPolylinePointIndex: Int,
    val endPolylinePointIndex: Int,
    val severity: CongestionSeverity,
)
