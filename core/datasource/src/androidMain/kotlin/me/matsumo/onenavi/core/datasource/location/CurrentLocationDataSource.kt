package me.matsumo.onenavi.core.datasource.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * 端末の現在地を [UserLocation] として提供する data source。
 *
 * このクラスは位置情報 API の callback / task を coroutine で扱いやすい形に変換するだけに責務を絞る。
 * 位置権限の確認や権限要求 UI は呼び出し側で済ませてから利用する。
 *
 * @param context 位置情報クライアントを作るための Android context
 */
@Suppress("unused")
class CurrentLocationDataSource(context: Context) {

    private val locationProviderClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

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
        return try {
            locationProviderClient.lastLocation.await()?.toUserLocation()
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
    @SuppressLint("MissingPermission")
    fun locationUpdates(
        intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
        minDistanceMeters: Float = DEFAULT_MIN_DISTANCE_METERS,
    ): Flow<UserLocation> {
        require(intervalMillis > 0L) { "intervalMillis must be greater than 0." }
        require(minDistanceMeters >= 0f) { "minDistanceMeters must be greater than or equal to 0." }

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
                    .mapNotNull { location -> location.toUserLocation() }
                    .forEach { userLocation ->
                        val sendResult = trySend(userLocation)
                        if (sendResult.isFailure) return@forEach
                    }
            }
        }
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
