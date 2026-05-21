package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState

/**
 * Guidance 進捗から地図に表示する自車位置を選ぶ selector。
 */
internal object GuidanceVehicleLocationSelector {

    /**
     * 地図表示に使う自車位置 tick を返す。
     *
     * Route 上にいる間は route-snapped 位置を使う。off-route 候補以上では古い route への吸着を止め、
     * guidance tick に含まれる観測位置を使う。
     *
     * @param progress Guidance 進捗
     * @return 地図表示に使う自車位置 state
     */
    fun select(
        progress: GuidanceProgress,
    ): VehicleLocationState {
        return when (progress.routeMatchState) {
            RouteMatchState.ON_ROUTE -> progress.toRouteSnappedVehicleLocationState()
            RouteMatchState.OFF_ROUTE_CANDIDATE,
            RouteMatchState.OFF_ROUTE_CONFIRMED,
            -> progress
                .toObservedVehicleLocationState()
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
     * Guidance tick に含まれる観測位置を地図表示用 tick に変換する。
     *
     * route 進捗は表示補間に使わせないため null にする。これにより route 逸脱中の自車アイコンと
     * カメラは古い route geometry ではなく実位置を追う。
     *
     * @return route 逸脱中の表示に使う自車位置 state
     */
    private fun GuidanceProgress.toObservedVehicleLocationState(): VehicleLocationState? {
        val observedLocation = observedLocation ?: return null

        return VehicleLocationState(
            location = observedLocation,
            bearingDegrees = observedBearingDegrees ?: bearingDegrees,
            accuracyMeters = observedAccuracyMeters,
            timestampMillis = locationTimestampMillis,
            elapsedRealtimeNanos = locationElapsedRealtimeNanos,
            speedMps = vehicleSpeedMps,
            routeProgressMeters = null,
            source = VehicleLocationSource.RAW_GPS,
            routeMatchState = routeMatchState,
            projectionErrorMeters = projectionErrorMeters,
        )
    }
}
