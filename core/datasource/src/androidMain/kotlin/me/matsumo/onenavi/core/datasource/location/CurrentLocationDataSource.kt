package me.matsumo.onenavi.core.datasource.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.model.DeveloperFeature
import kotlin.time.Duration.Companion.milliseconds

/**
 * 端末の現在地を [UserLocation] として提供する data source。
 *
 * このクラスは位置情報 API の callback / task を coroutine で扱いやすい形に変換するだけに責務を絞る。
 * 位置権限の確認や権限要求 UI は呼び出し側で済ませてから利用する。
 * Fake GPS 機能が有効な間は実端末の現在地を読まず、東京駅の固定位置を返す。
 *
 * @param context 位置情報クライアントを作るための Android context
 * @param appSettingDataSource 開発モード設定を読む data source
 */
@Suppress("unused")
class CurrentLocationDataSource(
    context: Context,
    private val appSettingDataSource: AppSettingDataSource,
) {

    private val locationProviderClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val speedEstimatorLock = Any()
    private val speedEstimator = VehicleSpeedEstimator()
    private val _vehicleSpeedState = MutableStateFlow(VehicleSpeedState())

    /**
     * UI と案内ロジックが共有する表示用の自車速度。
     */
    val vehicleSpeedState: StateFlow<VehicleSpeedState> = _vehicleSpeedState.asStateFlow()

    /**
     * 最後に取得済みの位置を 1 件だけ返す。
     *
     * 端末側に直近位置がない場合や位置取得 task が失敗した場合は null を返す。権限チェックは行わないため、
     * 呼び出し側はこのメソッドを呼ぶ前に位置権限を確認する。
     *
     * @return 直近位置を変換した [UserLocation]。取得できない場合は null
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): UserLocation? {
        if (appSettingDataSource.currentSetting().isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS)) {
            return applySpeedEstimation(createDevelopmentUserLocation())
        }

        return try {
            val location = locationProviderClient.lastLocation.await()?.toUserLocation()

            location?.let(::applySpeedEstimation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logLastKnownFailure(error)
            null
        }
    }

    /**
     * lastKnown の取得失敗を記録する。
     *
     * @param error 位置取得 task から返った失敗
     */
    private fun logLastKnownFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "lastKnown failed" }
    }

    /**
     * 現在地の連続更新を [Flow] として返す。
     *
     * collect 開始時に位置更新を登録し、collect 終了・cancel・例外時には callback を解除する。位置権限の確認は
     * 呼び出し側の責務で、この Flow は権限要求 UI や権限分岐を持たない。
     *
     * @param intervalMillis 位置更新の希望間隔。1 以上の値を指定する
     * @param minDistanceMeters 更新を受け取る最小移動距離。0 以上の値を指定する
     * @return [UserLocation] の連続更新 Flow
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun locationUpdates(
        intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
        minDistanceMeters: Float = DEFAULT_MIN_DISTANCE_METERS,
    ): Flow<UserLocation> {
        require(intervalMillis > 0L) { "intervalMillis must be greater than 0." }
        require(minDistanceMeters >= 0f) { "minDistanceMeters must be greater than or equal to 0." }

        return appSettingDataSource.setting
            .map { setting -> setting.isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS) }
            .distinctUntilChanged()
            .flatMapLatest { isFakeGpsEnabled ->
                if (isFakeGpsEnabled) {
                    developmentLocationUpdates(intervalMillis = intervalMillis)
                } else {
                    rawLocationUpdates(
                        intervalMillis = intervalMillis,
                        minDistanceMeters = minDistanceMeters,
                    )
                }
            }
            .buffer(Channel.CONFLATED)
    }

    /**
     * 端末の位置情報 API から現在地の連続更新を読む。
     *
     * @param intervalMillis 位置更新の希望間隔
     * @param minDistanceMeters 更新を受け取る最小移動距離
     * @return 実端末位置の連続更新 Flow
     */
    @SuppressLint("MissingPermission")
    private fun rawLocationUpdates(
        intervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<UserLocation> {
        return callbackFlow {
            val request = buildLocationRequest(
                intervalMillis = intervalMillis,
                minDistanceMeters = minDistanceMeters,
            )
            val callback = buildLocationCallback()

            locationProviderClient
                .requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    Napier.w(tag = TAG, throwable = error) { "locationUpdates request failed" }
                    close(error)
                }

            awaitClose {
                locationProviderClient.removeLocationUpdates(callback)
            }
        }.buffer(Channel.CONFLATED)
    }

    /**
     * 開発モード用に東京駅の固定位置を定期的に流す。
     *
     * @param intervalMillis 位置 tick を流す間隔
     * @return 東京駅に固定された位置更新 Flow
     */
    private fun developmentLocationUpdates(intervalMillis: Long): Flow<UserLocation> = flow {
        while (true) {
            val location = createDevelopmentUserLocation()

            emit(applySpeedEstimation(location))
            delay(intervalMillis.milliseconds)
        }
    }

    /**
     * 位置更新リクエストを作る。
     *
     * @param intervalMillis 位置更新の希望間隔
     * @param minDistanceMeters 更新を受け取る最小移動距離
     * @return 高精度位置の継続取得に使う [LocationRequest]
     */
    private fun buildLocationRequest(
        intervalMillis: Long,
        minDistanceMeters: Float,
    ): LocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
        .setMinUpdateIntervalMillis(intervalMillis)
        .setMinUpdateDistanceMeters(minDistanceMeters)
        .setWaitForAccurateLocation(false)
        .build()

    /**
     * Fused Location の callback を作る。
     *
     * @return 受け取った [LocationResult] を [UserLocation] に変換して Flow へ投入する callback
     */
    private fun ProducerScope<UserLocation>.buildLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations
                    .mapNotNull { location ->
                        location.toUserLocation()?.let(::applySpeedEstimation)
                    }
                    .forEach { userLocation ->
                        val sendResult = trySend(userLocation)
                        if (sendResult.isFailure) return@forEach
                    }
            }
        }
    }

    /**
     * 速度推定を適用した位置 tick を返し、共有速度 state を更新する。
     *
     * @param location provider から受け取った位置 tick
     * @return 速度推定を反映した位置 tick
     */
    private fun applySpeedEstimation(location: UserLocation): UserLocation {
        val estimation = synchronized(speedEstimatorLock) {
            speedEstimator.estimate(location)
        }

        _vehicleSpeedState.value = estimation.state

        return estimation.location
    }

    /**
     * ログタグと位置取得の既定値をまとめる companion object。
     */
    private companion object {

        /** Logcat で現在地 data source のログを絞り込むためのタグ。 */
        const val TAG = "CurrentLocationDataSource"

        /** 通常案内中の位置更新希望間隔。 */
        const val DEFAULT_INTERVAL_MILLIS = 1_000L

        /** すべての tick を tracker に渡すための既定最小移動距離。 */
        const val DEFAULT_MIN_DISTANCE_METERS = 0f
    }
}

/**
 * 開発モード用に東京駅の固定位置を作る。
 *
 * @return 東京駅に固定された現在地 tick
 */
private fun createDevelopmentUserLocation(): UserLocation = UserLocation(
    latitude = DEVELOPMENT_TOKYO_STATION_LATITUDE,
    longitude = DEVELOPMENT_TOKYO_STATION_LONGITUDE,
    bearingDegrees = null,
    speedMps = null,
    accuracyMeters = DEVELOPMENT_LOCATION_ACCURACY_METERS,
    timestampMillis = System.currentTimeMillis(),
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
)

/**
 * Android の [Location] を案内ロジック用の [UserLocation] に変換する。
 *
 * 緯度経度が有限値でない location は破棄する。bearing / speed / accuracy は端末が提供している場合だけ採用し、
 * 不明な水平精度は非常に粗い値として扱う。
 *
 * @return 変換できた [UserLocation]。緯度経度が無効な場合は null
 */
private fun Location.toUserLocation(): UserLocation? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null

    return UserLocation(
        latitude = latitude,
        longitude = longitude,
        bearingDegrees = bearing.takeIf { hasBearing() },
        speedMps = speed.takeIf { hasSpeed() },
        accuracyMeters = accuracy.takeIf { hasAccuracy() } ?: UNKNOWN_ACCURACY_METERS,
        timestampMillis = time
            .takeIf { timestampMillis -> timestampMillis > 0L }
            ?: System.currentTimeMillis(),
        elapsedRealtimeNanos = elapsedRealtimeNanos
            .takeIf { elapsedRealtimeNanos -> elapsedRealtimeNanos > 0L },
    )
}

/** 水平精度が端末から返らなかった場合に使う保守的な精度値。 */
private const val UNKNOWN_ACCURACY_METERS = 1_000f

/** 開発モードで自車位置として使う東京駅の緯度。 */
private const val DEVELOPMENT_TOKYO_STATION_LATITUDE = 35.681236

/** 開発モードで自車位置として使う東京駅の経度。 */
private const val DEVELOPMENT_TOKYO_STATION_LONGITUDE = 139.767125

/** 開発モード固定位置の水平精度。 */
private const val DEVELOPMENT_LOCATION_ACCURACY_METERS = 0f
