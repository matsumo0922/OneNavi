package me.matsumo.onenavi.feature.map.state

import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity

/**
 * 案内中 ETA 表示の渋滞レベル。
 *
 * 残ルート上の渋滞区間の通過予想時間の合計から決まり、ETA の文字色に対応する。
 */
enum class NavigationTrafficLevel {
    /** 渋滞ほぼ無し（合計通過時間 5 分未満。緑表示）。 */
    CLEAR,

    /** やや渋滞（合計通過時間 5 分以上 15 分未満。黄表示）。 */
    MODERATE,

    /** 強い渋滞（合計通過時間 15 分以上。赤表示）。 */
    HEAVY,
}

/** [NavigationTrafficLevel.MODERATE] と判定する合計通過時間の下限（分）。 */
private const val MODERATE_THRESHOLD_MINUTES = 5

/** [NavigationTrafficLevel.HEAVY] と判定する合計通過時間の下限（分）。 */
private const val HEAVY_THRESHOLD_MINUTES = 15

/**
 * 残ルート上の渋滞区間の通過予想時間を合計し、ETA の渋滞レベルを算出する。
 *
 * 通過済みの区間と通過時間が不明な区間は合計に算入しない。
 *
 * @param congestionSegments ルート全体の渋滞区間
 * @param currentCumulativeMeters ルート始点からの現在累積距離
 */
fun calculateNavigationTrafficLevel(
    congestionSegments: ImmutableList<CongestionSegment>,
    currentCumulativeMeters: Double,
): NavigationTrafficLevel {
    val totalCongestionMinutes = congestionSegments.sumOf { segment ->
        remainingCongestionMinutesOf(segment, currentCumulativeMeters)
    }

    return when {
        totalCongestionMinutes < MODERATE_THRESHOLD_MINUTES -> NavigationTrafficLevel.CLEAR
        totalCongestionMinutes < HEAVY_THRESHOLD_MINUTES -> NavigationTrafficLevel.MODERATE
        else -> NavigationTrafficLevel.HEAVY
    }
}

/**
 * ETA 色の判定に算入する渋滞区間の通過予想時間（分）。
 *
 * 既に通過済みの区間、平常・不明レベルの区間、通過時間が取得できない区間は 0 分とする。
 */
private fun remainingCongestionMinutesOf(
    segment: CongestionSegment,
    currentCumulativeMeters: Double,
): Int {
    val isAhead = segment.endDistanceMeters >= currentCumulativeMeters
    if (!isAhead) {
        return 0
    }

    return when (segment.severity) {
        CongestionSeverity.SLOW, CongestionSeverity.TRAFFIC_JAM -> segment.transitMinutes ?: 0
        CongestionSeverity.NORMAL, CongestionSeverity.UNKNOWN -> 0
    }
}
