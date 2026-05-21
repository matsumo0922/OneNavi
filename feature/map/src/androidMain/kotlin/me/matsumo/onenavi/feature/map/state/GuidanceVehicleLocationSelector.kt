package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState

/**
 * Guidance 進捗と実位置 stream から地図に表示する自車位置を選ぶ selector。
 */
internal object GuidanceVehicleLocationSelector {

    /** Guidance tick と実位置 tick の許容時刻差。 */
    private const val MAX_DEVICE_LOCATION_PROGRESS_SKEW_MILLIS = 5_000L

    /**
     * 地図表示に使う自車位置 tick を返す。
     *
     * Route 上にいる間は route-snapped 位置を使う。off-route 候補以上では古い route への吸着を止め、
     * SDK road-snapped / raw GPS 由来の実位置を優先する。
     *
     * @param progress Guidance 進捗
     * @param deviceLocationState SDK road-snapped / raw GPS 由来の実位置
     * @return 地図表示に使う自車位置 state
     */
    fun select(
        progress: GuidanceProgress,
        deviceLocationState: VehicleLocationState?,
    ): VehicleLocationState {
        return when (progress.routeMatchState) {
            RouteMatchState.ON_ROUTE -> progress.toRouteSnappedVehicleLocationState()
            RouteMatchState.OFF_ROUTE_CANDIDATE,
            RouteMatchState.OFF_ROUTE_CONFIRMED,
            -> deviceLocationState
                ?.takeIf { state -> state.isFreshFor(progress) }
                ?.toOffRouteVehicleLocationState(progress)
                ?: progress.toRouteSnappedVehicleLocationState()
        }
    }

    /**
     * Guidance 進捗を route-snapped の地図表示用 tick に変換する。
     *
     * @return route-snapped の自車位置 state
     */
    private fun GuidanceProgress.toRouteSnappedVehicleLocationState(): VehicleLocationState = VehicleLocationState(
        location = snappedLocation,
        bearingDegrees = bearingDegrees,
        accuracyMeters = null,
        timestampMillis = locationTimestampMillis,
        elapsedRealtimeNanos = locationElapsedRealtimeNanos,
        speedMps = vehicleSpeedMps,
        routeProgressMeters = currentCumulativeMeters,
        source = VehicleLocationSource.ROUTE_SNAPPED,
        routeMatchState = routeMatchState,
        projectionErrorMeters = projectionErrorMeters,
    )

    /**
     * 実位置 stream の tick に route match 情報を付与する。
     *
     * route 進捗は表示補間に使わせないため null にする。これにより route 逸脱中の自車アイコンと
     * カメラは古い route geometry ではなく実位置を追う。
     *
     * @param progress 同時点付近の Guidance 進捗
     * @return route 逸脱中の表示に使う自車位置 state
     */
    private fun VehicleLocationState.toOffRouteVehicleLocationState(
        progress: GuidanceProgress,
    ): VehicleLocationState = copy(
        routeProgressMeters = null,
        routeMatchState = progress.routeMatchState,
        projectionErrorMeters = progress.projectionErrorMeters,
    )

    /**
     * Guidance 進捗と同時点付近の実位置かを返す。
     *
     * @param progress 比較対象の Guidance 進捗
     * @return 実位置 stream が古すぎず、off-route 表示へ使える場合 true
     */
    private fun VehicleLocationState.isFreshFor(progress: GuidanceProgress): Boolean {
        val timestampDeltaMillis = kotlin.math.abs(timestampMillis - progress.locationTimestampMillis)

        return timestampDeltaMillis <= MAX_DEVICE_LOCATION_PROGRESS_SKEW_MILLIS
    }
}
