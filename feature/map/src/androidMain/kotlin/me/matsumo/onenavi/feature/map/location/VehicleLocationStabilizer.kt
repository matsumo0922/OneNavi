package me.matsumo.onenavi.feature.map.location

import me.matsumo.onenavi.feature.map.state.MapGeodesy
import me.matsumo.onenavi.feature.map.state.MapTime
import me.matsumo.onenavi.feature.map.state.VehicleLocationSource
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import kotlin.math.max
import kotlin.math.min

/**
 * 地図表示へ流す非案内中の自車位置 tick を安定化する。
 *
 * Fused Location や road-snapped provider は、静止中でも数 m 単位の揺れや低速時に信頼できない
 * course bearing を返すことがある。この class は UI に入る前の境界で、古い fix、粗い fix、
 * 物理的に成立しないジャンプ、静止時の bearing 更新を抑制する。
 */
internal class VehicleLocationStabilizer(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    private var lastAccepted: VehicleLocationState? = null
    private var relocationCandidate: VehicleLocationState? = null
    private var relocationCandidateCount: Int = 0

    /**
     * 位置 tick を検証し、地図表示に使える安定化済み tick を返す。
     *
     * @param sample provider から届いた元の位置 tick
     * @return 表示に使う tick。破棄すべき tick の場合は null
     */
    fun stabilize(sample: VehicleLocationState): VehicleLocationState? {
        if (!sample.hasFiniteCoordinate() || sample.isStale()) return null

        val previous = lastAccepted
        if (previous == null) {
            return sample
                .takeUnless { state -> state.isTooCoarseForInitialFix() }
                ?.withStableBearing(previous = null)
                ?.also(::accept)
        }

        val candidate = sample.withStableBearing(previous = previous)
        if (candidate.isTooCoarseForTracking()) {
            return holdPreviousIfStillNearby(
                previous = previous,
                candidate = candidate,
            )
        }

        if (previous.isImplausibleJumpTo(candidate)) {
            return acceptConfirmedRelocation(candidate)
        }

        clearRelocationCandidate()

        val next = if (previous.shouldHoldStationaryLocation(candidate)) {
            previous.copyForHeldLocation(candidate)
        } else {
            candidate
        }

        return next.also(::accept)
    }

    /**
     * 前回位置の近傍にある粗い fix なら、座標を動かさず時刻だけ更新する。
     *
     * @param previous 最後に表示へ採用した tick
     * @param candidate 今回届いた粗い tick
     * @return 表示に使う tick。粗い上に離れている場合は null
     */
    private fun holdPreviousIfStillNearby(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): VehicleLocationState? {
        if (!previous.shouldHoldStationaryLocation(candidate)) return null

        return previous
            .copyForHeldLocation(candidate)
            .also(::accept)
    }

    /**
     * 物理的に成立しない移動先候補が連続した場合だけ、現在地の大きな変更として採用する。
     *
     * @param candidate 前回採用位置から見ると外れ値に見える tick
     * @return 連続確認できた tick。まだ確認中の場合は null
     */
    private fun acceptConfirmedRelocation(candidate: VehicleLocationState): VehicleLocationState? {
        val previousCandidate = relocationCandidate
        val isSameRelocation = previousCandidate?.isConsistentRelocationCandidate(candidate) == true

        relocationCandidate = candidate
        relocationCandidateCount = if (isSameRelocation) {
            relocationCandidateCount + 1
        } else {
            1
        }

        if (relocationCandidateCount < RELOCATION_CONFIRMATION_SAMPLE_COUNT) return null

        clearRelocationCandidate()

        return candidate
            .copy(
                bearingDegrees = candidate.bearingDegrees
                    .takeIf { candidate.hasReliableCourseBearing(previous = previousCandidate) },
            )
            .also(::accept)
    }

    /**
     * tick を採用済み状態として保持する。
     *
     * @param state 表示へ流した tick
     */
    private fun accept(state: VehicleLocationState) {
        lastAccepted = state
    }

    /**
     * 保留中の大移動候補を破棄する。
     */
    private fun clearRelocationCandidate() {
        relocationCandidate = null
        relocationCandidateCount = 0
    }

    /**
     * 座標が有限値かを返す。
     *
     * @return 緯度経度が有限値なら true
     */
    private fun VehicleLocationState.hasFiniteCoordinate(): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite()

    /**
     * 位置 fix が古すぎるかを返す。
     *
     * @return 表示に使うべきでない古い fix なら true
     */
    private fun VehicleLocationState.isStale(): Boolean {
        val ageMillis = currentTimeMillis() - timestampMillis

        return ageMillis > MAX_LOCATION_AGE_MILLIS ||
            ageMillis < -MAX_LOCATION_FUTURE_SKEW_MILLIS
    }

    /**
     * 初回表示に使うには水平精度が粗すぎるかを返す。
     *
     * @return 初回 tick として破棄すべきなら true
     */
    private fun VehicleLocationState.isTooCoarseForInitialFix(): Boolean =
        accuracyMeters?.let { accuracy -> accuracy.isFinite() && accuracy > MAX_INITIAL_ACCURACY_METERS } == true

    /**
     * 継続追跡に使うには水平精度が粗すぎるかを返す。
     *
     * @return 継続 tick として直接採用すべきでないなら true
     */
    private fun VehicleLocationState.isTooCoarseForTracking(): Boolean =
        accuracyMeters?.let { accuracy -> accuracy.isFinite() && accuracy > MAX_TRACKING_ACCURACY_METERS } == true

    /**
     * 表示向けに安定化した bearing を持つ tick を返す。
     *
     * @param previous 最後に採用した tick。初回の場合は null
     * @return bearing を安定化した tick
     */
    private fun VehicleLocationState.withStableBearing(previous: VehicleLocationState?): VehicleLocationState {
        val nextBearing = when {
            bearingDegrees != null && hasReliableCourseBearing(previous) -> MapGeodesy.normalizeBearingDegrees(
                bearingDegrees,
            )
            shouldRetainPreviousBearing(previous) -> previous?.bearingDegrees
            else -> null
        }

        return copy(bearingDegrees = nextBearing)
    }

    /**
     * provider の course bearing を信頼できる状態かを返す。
     *
     * @param previous 最後に採用した tick。初回の場合は null
     * @return bearing を採用してよいなら true
     */
    private fun VehicleLocationState.hasReliableCourseBearing(previous: VehicleLocationState? = lastAccepted): Boolean {
        val speed = speedMps
        if (speed != null && speed.isFinite() && speed >= MIN_COURSE_BEARING_SPEED_MPS) return true

        val previousLocation = previous?.location ?: return false
        val movedMeters = MapGeodesy.haversineMeters(previousLocation, location)

        return movedMeters >= minBearingMovementMeters(previous = previous, candidate = this)
    }

    /**
     * 低速・静止中として前回 bearing を維持すべきかを返す。
     *
     * @param previous 最後に採用した tick。初回の場合は null
     * @return 前回 bearing を維持すべきなら true
     */
    private fun VehicleLocationState.shouldRetainPreviousBearing(previous: VehicleLocationState?): Boolean {
        if (previous?.bearingDegrees == null) return false

        val speed = speedMps
        if (speed != null && speed.isFinite() && speed < MIN_COURSE_BEARING_SPEED_MPS) return true

        val movedMeters = MapGeodesy.haversineMeters(previous.location, location)

        return movedMeters < minBearingMovementMeters(previous = previous, candidate = this)
    }

    /**
     * 前回採用位置から今回 tick への移動が物理的に成立しないかを返す。
     *
     * @param candidate 今回届いた tick
     * @return 外れ値として隔離すべきなら true
     */
    private fun VehicleLocationState.isImplausibleJumpTo(candidate: VehicleLocationState): Boolean {
        val elapsedSeconds = elapsedSecondsTo(candidate) ?: return false
        if (elapsedSeconds <= 0.0) return false

        val distanceMeters = MapGeodesy.haversineMeters(location, candidate.location)
        val accuracyBufferMeters = accuracyBufferMeters(this, candidate)
        val effectiveDistanceMeters = max(0.0, distanceMeters - accuracyBufferMeters)
        val plausibleDistanceMeters = MAX_REASONABLE_FREE_DRIVE_SPEED_MPS * elapsedSeconds + JUMP_GRACE_METERS

        return effectiveDistanceMeters > plausibleDistanceMeters
    }

    /**
     * 大移動候補同士が同じ移動先を指しているかを返す。
     *
     * @param candidate 今回届いた大移動候補
     * @return 同じ移動先として扱えるなら true
     */
    private fun VehicleLocationState.isConsistentRelocationCandidate(candidate: VehicleLocationState): Boolean {
        val distanceMeters = MapGeodesy.haversineMeters(location, candidate.location)

        return distanceMeters <= relocationConsistencyMeters(this, candidate)
    }

    /**
     * 静止中の測位ノイズとして前回座標を保持すべきかを返す。
     *
     * @param candidate 今回届いた tick
     * @return 座標を動かさず時刻だけ更新すべきなら true
     */
    private fun VehicleLocationState.shouldHoldStationaryLocation(candidate: VehicleLocationState): Boolean {
        val distanceMeters = MapGeodesy.haversineMeters(location, candidate.location)
        if (distanceMeters <= stationaryNoiseMeters(this, candidate)) return true

        val speed = candidate.speedMps ?: return false
        if (!speed.isFinite() || speed >= STATIONARY_SPEED_MPS) return false

        return distanceMeters <= stationarySpeedHoldMeters(this, candidate)
    }

    /**
     * 前回座標を保持したまま、新しい tick の計測時刻だけ反映した state を返す。
     *
     * @param candidate 今回届いた tick
     * @return 座標を保持した表示用 tick
     */
    private fun VehicleLocationState.copyForHeldLocation(candidate: VehicleLocationState): VehicleLocationState =
        copy(
            bearingDegrees = bearingDegrees,
            accuracyMeters = minAccuracyMeters(this, candidate),
            timestampMillis = candidate.timestampMillis,
            elapsedRealtimeNanos = candidate.elapsedRealtimeNanos ?: elapsedRealtimeNanos,
            speedMps = 0f,
        )

    /**
     * 2 tick 間の経過秒数を返す。
     *
     * @param candidate 次の tick
     * @return monotonic clock または wall clock から算出した経過秒数
     */
    private fun VehicleLocationState.elapsedSecondsTo(candidate: VehicleLocationState): Double? {
        val elapsedNanos = elapsedRealtimeNanos
        val candidateElapsedNanos = candidate.elapsedRealtimeNanos

        if (elapsedNanos != null && candidateElapsedNanos != null) {
            return MapTime.elapsedSeconds(
                fromElapsedRealtimeNanos = elapsedNanos,
                toElapsedRealtimeNanos = candidateElapsedNanos,
            )
        }

        return MapTime.elapsedWallClockSeconds(
            fromTimestampMillis = timestampMillis,
            toTimestampMillis = candidate.timestampMillis,
        )
    }

    /**
     * bearing 更新に必要な最小移動距離を返す。
     *
     * @param previous 最後に採用した tick
     * @param candidate 今回届いた tick
     * @return bearing を移動方向から判断できる最小距離
     */
    private fun minBearingMovementMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Double = max(
        MIN_BEARING_MOVEMENT_METERS,
        min(
            MAX_ACCURACY_BASED_BEARING_MOVEMENT_METERS,
            accuracyBufferMeters(previous, candidate),
        ),
    )

    /**
     * 静止中とみなして位置を保持するノイズ半径を返す。
     *
     * @param previous 最後に採用した tick
     * @param candidate 今回届いた tick
     * @return 測位ノイズとして吸収する距離
     */
    private fun stationaryNoiseMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Double = max(
        MIN_STATIONARY_NOISE_METERS,
        min(
            MAX_ACCURACY_BASED_STATIONARY_NOISE_METERS,
            accuracyBufferMeters(previous, candidate),
        ),
    )

    /**
     * speed が静止を示す場合に前回位置を保持する最大距離を返す。
     *
     * @param previous 最後に採用した tick
     * @param candidate 今回届いた tick
     * @return 静止扱いで保持できる最大距離
     */
    private fun stationarySpeedHoldMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Double {
        val baseMeters = if (
            previous.source == VehicleLocationSource.SDK_ROAD_SNAPPED ||
            candidate.source == VehicleLocationSource.SDK_ROAD_SNAPPED
        ) {
            ROAD_SNAPPED_STATIONARY_HOLD_METERS
        } else {
            RAW_GPS_STATIONARY_HOLD_METERS
        }

        return max(baseMeters, stationaryNoiseMeters(previous, candidate))
    }

    /**
     * 2 tick の水平精度から、距離判定で許容する誤差幅を返す。
     *
     * @param previous 最後に採用した tick
     * @param candidate 今回届いた tick
     * @return 距離判定で差し引く誤差幅
     */
    private fun accuracyBufferMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Double = previous.accuracyMeters.orDefaultAccuracyMeters() +
        candidate.accuracyMeters.orDefaultAccuracyMeters()

    /**
     * 大移動候補同士の一致判定に使う距離を返す。
     *
     * @param previous 直前の大移動候補
     * @param candidate 今回の大移動候補
     * @return 同じ移動先として扱う距離
     */
    private fun relocationConsistencyMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Double = max(
        MIN_RELOCATION_CONSISTENCY_METERS,
        min(
            MAX_RELOCATION_CONSISTENCY_METERS,
            accuracyBufferMeters(previous, candidate),
        ),
    )

    /**
     * 2 tick のうち良い方の水平精度を返す。
     *
     * @param previous 最後に採用した tick
     * @param candidate 今回届いた tick
     * @return 表示用 state に残す水平精度
     */
    private fun minAccuracyMeters(
        previous: VehicleLocationState,
        candidate: VehicleLocationState,
    ): Float? {
        val previousAccuracy = previous.accuracyMeters?.takeIf { accuracy -> accuracy.isFinite() }
        val candidateAccuracy = candidate.accuracyMeters?.takeIf { accuracy -> accuracy.isFinite() }

        return when {
            previousAccuracy == null -> candidateAccuracy
            candidateAccuracy == null -> previousAccuracy
            else -> min(previousAccuracy, candidateAccuracy)
        }
    }

    /**
     * 水平精度が無い場合の距離判定用 default を返す。
     *
     * @return 距離判定に使う水平精度
     */
    private fun Float?.orDefaultAccuracyMeters(): Double =
        this
            ?.takeIf { accuracy -> accuracy.isFinite() && accuracy >= 0f }
            ?.toDouble()
            ?: DEFAULT_ACCURACY_BUFFER_METERS

    /**
     * stabilizer の判定値をまとめる companion object。
     */
    private companion object {

        /** 位置 fix を現在値として扱う最大 age。 */
        const val MAX_LOCATION_AGE_MILLIS = 120_000L

        /** 端末 clock ずれとして許容する未来方向の timestamp 差。 */
        const val MAX_LOCATION_FUTURE_SKEW_MILLIS = 10_000L

        /** 初回表示に使う最大水平精度。 */
        const val MAX_INITIAL_ACCURACY_METERS = 200f

        /** 継続追跡に直接使う最大水平精度。 */
        const val MAX_TRACKING_ACCURACY_METERS = 250f

        /** course bearing を信用する最低速度。 */
        const val MIN_COURSE_BEARING_SPEED_MPS = 2.0f

        /** 静止扱いにする速度。 */
        const val STATIONARY_SPEED_MPS = 1.0f

        /** bearing 更新に必要な最小移動距離。 */
        const val MIN_BEARING_MOVEMENT_METERS = 8.0

        /** 精度由来で大きくなりすぎる bearing 更新距離の上限。 */
        const val MAX_ACCURACY_BASED_BEARING_MOVEMENT_METERS = 35.0

        /** 静止ノイズとして吸収する最小距離。 */
        const val MIN_STATIONARY_NOISE_METERS = 6.0

        /** 精度由来で大きくなりすぎる静止ノイズ半径の上限。 */
        const val MAX_ACCURACY_BASED_STATIONARY_NOISE_METERS = 30.0

        /** raw GPS が静止速度を示す場合に保持する最大距離。 */
        const val RAW_GPS_STATIONARY_HOLD_METERS = 35.0

        /** road-snapped 位置が静止速度を示す場合に保持する最大距離。 */
        const val ROAD_SNAPPED_STATIONARY_HOLD_METERS = 60.0

        /** 非案内中の自車位置として許容する上限速度。 */
        const val MAX_REASONABLE_FREE_DRIVE_SPEED_MPS = 75.0

        /** jump 判定で速度上限とは別に許容する余白距離。 */
        const val JUMP_GRACE_METERS = 60.0

        /** 大移動候補を採用するために必要な連続 sample 数。 */
        const val RELOCATION_CONFIRMATION_SAMPLE_COUNT = 3

        /** 大移動候補同士を同一点とみなす最小距離。 */
        const val MIN_RELOCATION_CONSISTENCY_METERS = 25.0

        /** 大移動候補同士を同一点とみなす最大距離。 */
        const val MAX_RELOCATION_CONSISTENCY_METERS = 120.0

        /** accuracy が無い provider に使う保守的な距離 buffer。 */
        const val DEFAULT_ACCURACY_BUFFER_METERS = 8.0
    }
}
