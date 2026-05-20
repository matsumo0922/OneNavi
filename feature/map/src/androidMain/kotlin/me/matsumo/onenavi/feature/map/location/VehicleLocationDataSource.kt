package me.matsumo.onenavi.feature.map.location

import android.location.Location
import android.os.SystemClock
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
 * raw GPS を fallback として流す。
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

        val providerJob = launch {
            collectProviderLocations(lastProviderUpdateElapsedMillis)
        }
        val rawGpsJob = launch {
            collectRawGpsFallback(lastProviderUpdateElapsedMillis)
        }

        awaitClose {
            providerJob.cancel()
            rawGpsJob.cancel()
        }
    }.buffer(Channel.CONFLATED)

    private suspend fun ProducerScope<VehicleLocationState>.collectProviderLocations(
        lastProviderUpdateElapsedMillis: AtomicLong,
    ) {
        try {
            navigationSdkManager.roadSnappedLocationProvider
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { provider ->
                    provider.locationUpdates().collect { state ->
                        lastProviderUpdateElapsedMillis.set(SystemClock.elapsedRealtime())
                        send(state)
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Napier.w(tag = TAG, throwable = error) { "SDK vehicle location updates failed" }
        }
    }

    private suspend fun ProducerScope<VehicleLocationState>.collectRawGpsFallback(
        lastProviderUpdateElapsedMillis: AtomicLong,
    ) {
        try {
            currentLocationDataSource.lastKnown()
                ?.toVehicleLocationState()
                ?.let { state ->
                    if (shouldEmitRawGps(lastProviderUpdateElapsedMillis)) {
                        send(state)
                    }
                }

            currentLocationDataSource.locationUpdates().collect { location ->
                if (shouldEmitRawGps(lastProviderUpdateElapsedMillis)) {
                    send(location.toVehicleLocationState())
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
                ?.let { state -> trySend(state) }
        }

        override fun onRawLocationUpdate(location: Location) {
            location
                .toVehicleLocationState(source = VehicleLocationSource.RAW_GPS)
                ?.let { state -> trySend(state) }
        }
    }

    addLocationListener(listener)
    awaitClose { removeLocationListener(listener) }
}.buffer(Channel.CONFLATED)

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

private val isRoadSnappedKey = RoadSnappedLocationProvider.LocationListener.IS_ROAD_SNAPPED_KEY

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
        source = source,
    )
}

private fun UserLocation.toVehicleLocationState(): VehicleLocationState = VehicleLocationState(
    location = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    ),
    bearingDegrees = bearingDegrees,
    accuracyMeters = accuracyMeters,
    timestampMillis = timestampMillis,
    source = VehicleLocationSource.RAW_GPS,
)
