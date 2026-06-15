package me.matsumo.onenavi.feature.map.location

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.state.VehicleLocationSource
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

/**
 * 地図表示用の自車位置を提供する data source。
 *
 * 端末の raw GPS を UI に渡す前に、古い fix、粗い fix、外れ値、静止時の bearing ぶれを
 * [VehicleLocationStabilizer] で抑制する。
 */
class VehicleLocationDataSource(
    private val currentLocationDataSource: CurrentLocationDataSource,
) {

    /**
     * 地図 UI 向け自車位置の連続更新。
     *
     * collect 中だけ raw GPS listener を登録し、UI に渡す前に [VehicleLocationStabilizer] で安定化する。
     */
    fun locationUpdates(): Flow<VehicleLocationState> = flow {
        collectRawGpsLocations()
    }.buffer(Channel.CONFLATED)

    private suspend fun FlowCollector<VehicleLocationState>.collectRawGpsLocations() {
        val stabilizer = VehicleLocationStabilizer()

        try {
            emitLastKnownLocation(stabilizer = stabilizer)
            emitContinuousLocations(stabilizer = stabilizer)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Napier.w(tag = TAG, throwable = error) { "Raw GPS vehicle location updates failed" }
        }
    }

    private suspend fun FlowCollector<VehicleLocationState>.emitLastKnownLocation(
        stabilizer: VehicleLocationStabilizer,
    ) {
        currentLocationDataSource.lastKnown()
            ?.toVehicleLocationState()
            ?.let { locationState ->
                emitStabilized(
                    stabilizer = stabilizer,
                    state = locationState,
                )
            }
    }

    private suspend fun FlowCollector<VehicleLocationState>.emitContinuousLocations(
        stabilizer: VehicleLocationStabilizer,
    ) {
        currentLocationDataSource.locationUpdates().collect { location ->
            emitStabilized(
                stabilizer = stabilizer,
                state = location.toVehicleLocationState(),
            )
        }
    }

    private suspend fun FlowCollector<VehicleLocationState>.emitStabilized(
        stabilizer: VehicleLocationStabilizer,
        state: VehicleLocationState,
    ) {
        val stabilizedState = stabilizer.stabilize(state) ?: return
        emit(stabilizedState)
    }

    private companion object {

        /** Logcat で地図用自車位置 data source のログを絞り込むためのタグ。 */
        const val TAG = "VehicleLocationDataSource"
    }
}

/**
 * raw GPS の [UserLocation] を地図表示用の自車位置 state に変換する。
 *
 * @return raw GPS 由来の自車位置 state
 */
private fun UserLocation.toVehicleLocationState(): VehicleLocationState = VehicleLocationState(
    location = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    ),
    bearingDegrees = bearingDegrees,
    accuracyMeters = accuracyMeters,
    timestampMillis = timestampMillis,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    speedMps = speedMps,
    routeProgressMeters = null,
    source = VehicleLocationSource.RAW_GPS,
    routeMatchState = null,
    positionSource = null,
    projectionErrorMeters = null,
)
