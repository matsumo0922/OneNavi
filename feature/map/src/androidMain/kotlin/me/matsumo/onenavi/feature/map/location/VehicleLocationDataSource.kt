package me.matsumo.onenavi.feature.map.location

import android.location.Location
import android.os.SystemClock
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.feature.map.state.VehicleLocationSource
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import java.util.concurrent.atomic.AtomicLong

/**
 * 地図表示用の自車位置を提供する data source。
 *
 * SDK road-snapped location を優先し、SDK provider が未初期化または更新停止している間だけ
 * raw GPS を fallback として流す。UI に渡す前に古い fix、粗い fix、外れ値、静止時の bearing ぶれを
 * [VehicleLocationStabilizer] で抑制する。
 */
class VehicleLocationDataSource(
    private val navigationSdkManager: NavigationSdkManager,
    private val currentLocationDataSource: CurrentLocationDataSource,
) {

    /**
     * 地図 UI 向け自車位置の連続更新。
     *
     * collect 中だけ SDK listener と raw GPS listener を登録する。SDK provider から更新が届いている間は
     * raw GPS の重複 emission を抑制する。
     */
    fun locationUpdates(): Flow<VehicleLocationState> = channelFlow {
        val lastProviderUpdateElapsedMillis = AtomicLong(NO_PROVIDER_UPDATE)
        val stabilizer = VehicleLocationStabilizer()
        val stabilizerMutex = Mutex()

        suspend fun emitStabilized(state: VehicleLocationState) {
            val stabilizedState = stabilizerMutex.withLock {
                stabilizer.stabilize(state)
            } ?: return

            send(stabilizedState)
        }

        val providerJob = launch {
            collectProviderLocations(
                lastProviderUpdateElapsedMillis = lastProviderUpdateElapsedMillis,
                emitState = ::emitStabilized,
            )
        }
        val rawGpsJob = launch {
            collectRawGpsFallback(
                lastProviderUpdateElapsedMillis = lastProviderUpdateElapsedMillis,
                emitState = ::emitStabilized,
            )
        }

        awaitClose {
            providerJob.cancel()
            rawGpsJob.cancel()
        }
    }.buffer(Channel.CONFLATED)

    private suspend fun collectProviderLocations(
        lastProviderUpdateElapsedMillis: AtomicLong,
        emitState: suspend (VehicleLocationState) -> Unit,
    ) {
        try {
            navigationSdkManager.roadSnappedLocationProvider
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { provider ->
                    provider.locationUpdates().collect { state ->
                        lastProviderUpdateElapsedMillis.set(SystemClock.elapsedRealtime())
                        emitState(state)
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Napier.w(tag = TAG, throwable = error) { "SDK vehicle location updates failed" }
        }
    }

    private suspend fun collectRawGpsFallback(
        lastProviderUpdateElapsedMillis: AtomicLong,
        emitState: suspend (VehicleLocationState) -> Unit,
    ) {
        try {
            currentLocationDataSource.lastKnown()
                ?.toVehicleLocationState()
                ?.let { state ->
                    if (shouldEmitRawGps(lastProviderUpdateElapsedMillis)) {
                        emitState(state)
                    }
                }

            currentLocationDataSource.locationUpdates().collect { location ->
                if (shouldEmitRawGps(lastProviderUpdateElapsedMillis)) {
                    emitState(location.toVehicleLocationState())
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Napier.w(tag = TAG, throwable = error) { "Raw GPS vehicle location fallback failed" }
        }
    }

    private fun shouldEmitRawGps(lastProviderUpdateElapsedMillis: AtomicLong): Boolean {
        val lastProviderUpdate = lastProviderUpdateElapsedMillis.get()
        if (lastProviderUpdate == NO_PROVIDER_UPDATE) return true

        return SystemClock.elapsedRealtime() - lastProviderUpdate > PROVIDER_FRESHNESS_TIMEOUT_MILLIS
    }

    private companion object {

        const val TAG = "VehicleLocationDataSource"
        const val NO_PROVIDER_UPDATE = Long.MIN_VALUE
        const val PROVIDER_FRESHNESS_TIMEOUT_MILLIS = 2_500L
    }
}

private fun RoadSnappedLocationProvider.locationUpdates(): Flow<VehicleLocationState> = callbackFlow {
    val listener = object : RoadSnappedLocationProvider.LocationListener {
        override fun onLocationChanged(location: Location) {
            location
                .toVehicleLocationState(source = location.vehicleLocationSource(defaultRoadSnapped = true))
                ?.takeIf { state -> state.source == VehicleLocationSource.SDK_ROAD_SNAPPED }
                ?.let { state -> trySend(state) }
        }

        override fun onRawLocationUpdate(location: Location) {
            // Raw fallback は CurrentLocationDataSource に一本化し、SDK provider 由来の raw tick では
            // road-snapped provider の freshness を更新しない。
        }
    }

    addLocationListener(listener)
    awaitClose { removeLocationListener(listener) }
}.buffer(Channel.CONFLATED)

/**
 * SDK location extras から自車位置の取得元を判定する。
 *
 * @param defaultRoadSnapped extras が無い場合に road-snapped とみなすか
 * @return 地図表示に使う自車位置 source
 */
private fun Location.vehicleLocationSource(defaultRoadSnapped: Boolean): VehicleLocationSource {
    val extras = extras
    val isRoadSnapped = if (extras?.containsKey(isRoadSnappedKey) == true) {
        extras.getBoolean(isRoadSnappedKey)
    } else {
        defaultRoadSnapped
    }

    return if (isRoadSnapped) {
        VehicleLocationSource.SDK_ROAD_SNAPPED
    } else {
        VehicleLocationSource.RAW_GPS
    }
}

/** SDK location extras から road-snapped 判定を読む key。 */
private val isRoadSnappedKey = RoadSnappedLocationProvider.LocationListener.IS_ROAD_SNAPPED_KEY

/**
 * Android location を地図表示用の自車位置 state に変換する。
 *
 * @param source location の取得元
 * @return 緯度経度が有効な場合の自車位置 state。無効な場合は null
 */
private fun Location.toVehicleLocationState(source: VehicleLocationSource): VehicleLocationState? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null

    return VehicleLocationState(
        location = RoutePoint(
            latitude = latitude,
            longitude = longitude,
        ),
        bearingDegrees = bearing.takeIf { hasBearing() },
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        timestampMillis = time
            .takeIf { timestampMillis -> timestampMillis > 0L }
            ?: System.currentTimeMillis(),
        elapsedRealtimeNanos = elapsedRealtimeNanos
            .takeIf { elapsedRealtimeNanos -> elapsedRealtimeNanos > 0L },
        speedMps = speed.takeIf { hasSpeed() },
        routeProgressMeters = null,
        source = source,
    )
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
)
