package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress

/**
 * Tracker が周辺コンポーネントへ渡す projection snapshot。
 *
 * @param progress UI に公開する進捗モデル
 * @param rawLocation 位置情報プロバイダ由来の生位置
 * @param currentCumulativeMeters ルート geometry 上の現在累積距離
 * @param distanceRemainingMeters 内部計算用の残距離
 * @param matchedSegmentIndex snap 先 polyline segment index
 * @param projectionErrorMeters 生位置と snap 点の距離
 * @param locationTimestampMillis 位置 tick の時刻
 * @param vehicleSpeedMps 車速
 * @param isOffRouteCandidate debounce 前のオフルート候補
 * @param nextGuidancePointIndex 次の案内ポイント index
 */
@Immutable
data class ExtNavProgressSnapshot(
    val progress: GuidanceProgress,
    val rawLocation: UserLocation?,
    val currentCumulativeMeters: Double,
    val distanceRemainingMeters: Double,
    val matchedSegmentIndex: Int,
    val projectionErrorMeters: Double,
    val locationTimestampMillis: Long,
    val vehicleSpeedMps: Float?,
    val isOffRouteCandidate: Boolean,
    val nextGuidancePointIndex: Int?,
)
