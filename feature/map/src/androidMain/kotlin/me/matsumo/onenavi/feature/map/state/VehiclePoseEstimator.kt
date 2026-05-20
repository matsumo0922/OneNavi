package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.absoluteValue
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 低頻度の位置 tick から、画面描画時点の自車 pose を推定する。
 *
 * GPS / tracker から届く tick は描画 frame より粗いため、案内中は route geometry 上の累積距離と速度から
 * 現在 frame 時点の位置を推定する。案内中以外は route 進捗を持たないため、最後に届いた位置をそのまま返す。
 */
internal class VehiclePoseEstimator {

    private var routeMeterIndex: RouteMeterIndex? = null
    private var routeGeometryRef: List<RoutePoint>? = null
    private var previousSample: TimedVehicleLocation? = null
    private var latestSample: TimedVehicleLocation? = null
    private var displayRouteProgressMeters: Double? = null
    private var displayFreeLocation: RoutePoint? = null
    private var displayBearingDegrees: Float? = null
    private var lastEstimateElapsedRealtimeNanos: Long? = null
    private var latestSampleIntervalSeconds: Double? = null

    /**
     * 新しい自車位置 tick を登録する。
     *
     * @param sample tracker または位置 provider から届いた自車位置
     * @param routeGeometry 案内中 route の geometry。案内中以外は空でよい
     * @param nowElapsedRealtimeNanos sample が monotonic 時刻を持たない場合に補完する現在時刻
     */
    fun updateSample(
        sample: VehicleLocationState,
        routeGeometry: List<RoutePoint>,
        nowElapsedRealtimeNanos: Long,
    ) {
        val nextSample = TimedVehicleLocation(
            state = sample,
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos ?: nowElapsedRealtimeNanos,
        )
        val previousLatestSample = latestSample
        val isRouteChanged = updateRouteGeometryIfNeeded(
            sample = sample,
            routeGeometry = routeGeometry,
        )

        latestSampleIntervalSeconds = sampleIntervalSeconds(
            latest = nextSample,
            previous = previousLatestSample,
        ) ?: latestSampleIntervalSeconds

        previousSample = if (isRouteChanged) null else previousLatestSample
        latestSample = nextSample

        if (displayRouteProgressMeters == null) {
            displayRouteProgressMeters = nextSample.state.routeProgressMeters
        }
        if (nextSample.state.routeProgressMeters == null && displayFreeLocation == null) {
            displayFreeLocation = nextSample.state.location
        } else if (nextSample.state.routeProgressMeters != null) {
            displayFreeLocation = null
        }
    }

    /**
     * 指定時刻に描画すべき自車 pose を返す。
     *
     * @param nowElapsedRealtimeNanos 推定対象の monotonic clock 時刻
     * @return 推定可能な自車 pose。まだ sample が無い場合は null
     */
    fun estimate(nowElapsedRealtimeNanos: Long): VehiclePose? {
        val latest = latestSample ?: return null
        val routeProgressMeters = latest.state.routeProgressMeters
        val route = routeMeterIndex
        val frameElapsedSeconds = frameElapsedSeconds(nowElapsedRealtimeNanos)

        if (routeProgressMeters == null || route == null) {
            return displayFreePose(
                latest = latest,
                previous = previousSample,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                frameElapsedSeconds = frameElapsedSeconds,
            )
        }

        val displayProgressMeters = displayProgressMeters(
            latest = latest,
            previous = previousSample,
            route = route,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            frameElapsedSeconds = frameElapsedSeconds,
        )
        val targetPose = route.poseAt(
            distanceMeters = displayProgressMeters,
            fallbackBearingDegrees = latest.state.bearingDegrees,
        )

        return targetPose.copy(
            bearingDegrees = displayBearingDegrees(
                targetBearingDegrees = targetPose.bearingDegrees,
                sampleIntervalSeconds = recentSampleIntervalSeconds(latest = latest, previous = previousSample),
                frameElapsedSeconds = frameElapsedSeconds,
            ),
        )
    }

    /**
     * route geometry が変わった場合だけ、route 累積距離 index を更新する。
     *
     * route-snapped の tick が届いているのに画面 state 側の route geometry だけ一時的に空になることがある。
     * その場合に route を捨てると、次の frame で方位や進捗の表示状態が初期化されて急回転に見えるため、
     * 既存 route がある間はそれを保持する。route が実際に変わった場合でも、表示方位は保持して新しい
     * target 方位へ連続補間させる。
     *
     * @param sample tracker または位置 provider から届いた自車位置
     * @param routeGeometry 案内中 route の geometry
     * @return route geometry を更新した場合は true
     */
    private fun updateRouteGeometryIfNeeded(
        sample: VehicleLocationState,
        routeGeometry: List<RoutePoint>,
    ): Boolean {
        if (shouldKeepCurrentRouteGeometry(sample = sample, routeGeometry = routeGeometry)) {
            return false
        }

        if (routeGeometryRef == routeGeometry) return false

        val routeGeometrySnapshot = routeGeometry.toList()
        routeGeometryRef = routeGeometrySnapshot
        routeMeterIndex = RouteMeterIndex.from(routeGeometrySnapshot)
        displayRouteProgressMeters = null
        if (sample.routeProgressMeters != null) {
            displayFreeLocation = null
        }

        return true
    }

    /**
     * route-snapped tick と route geometry の一時的な不整合を無視すべきかを返す。
     *
     * @param sample tracker または位置 provider から届いた自車位置
     * @param routeGeometry 案内中 route の geometry
     * @return 既存 route geometry を保持すべき場合は true
     */
    private fun shouldKeepCurrentRouteGeometry(
        sample: VehicleLocationState,
        routeGeometry: List<RoutePoint>,
    ): Boolean {
        return sample.routeProgressMeters != null &&
            routeGeometry.size < MIN_ROUTE_GEOMETRY_POINT_COUNT &&
            routeMeterIndex != null
    }

    /**
     * route progress を持たない位置情報から、frame 時点の表示 pose を返す。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param nowElapsedRealtimeNanos 推定対象の monotonic clock 時刻
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return route 外で表示する自車 pose
     */
    private fun displayFreePose(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
        nowElapsedRealtimeNanos: Long,
        frameElapsedSeconds: Double,
    ): VehiclePose {
        val currentLocation = displayFreeLocation ?: latest.state.location
        val sampleIntervalSeconds = recentSampleIntervalSeconds(latest = latest, previous = previous)
        val targetLocation = targetFreeLocation(
            latest = latest,
            previous = previous,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        val nextLocation = advanceDisplayLocation(
            currentLocation = currentLocation,
            targetLocation = targetLocation,
            baseSpeedMps = latest.speedMps() ?: derivedFreeSpeedMps(latest = latest, previous = previous) ?: 0.0,
            sampleIntervalSeconds = sampleIntervalSeconds,
            frameElapsedSeconds = frameElapsedSeconds,
        )

        displayFreeLocation = nextLocation

        return VehiclePose(
            location = nextLocation,
            bearingDegrees = displayBearingDegrees(
                targetBearingDegrees = latest.state.bearingDegrees ?: bearingDegreesOrNull(currentLocation, targetLocation),
                sampleIntervalSeconds = sampleIntervalSeconds,
                frameElapsedSeconds = frameElapsedSeconds,
            ),
        )
    }

    /**
     * 最新 tick と速度・方位から、route 外で追いかける target 位置を返す。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param nowElapsedRealtimeNanos 推定対象の monotonic clock 時刻
     * @return 追従対象の位置
     */
    private fun targetFreeLocation(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
        nowElapsedRealtimeNanos: Long,
    ): RoutePoint {
        val speedMps = latest.speedMps()
            ?: derivedFreeSpeedMps(latest = latest, previous = previous)
            ?: return latest.state.location
        val bearingDegrees = latest.state.bearingDegrees
            ?: bearingDegreesOrNull(previous?.state?.location, latest.state.location)
            ?: return latest.state.location
        val elapsedSeconds = elapsedSeconds(
            fromElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
            toElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )

        return destinationPoint(
            origin = latest.state.location,
            bearingDegrees = bearingDegrees,
            distanceMeters = speedMps * elapsedSeconds,
        )
    }

    /**
     * route 外の表示位置を target へ向けて 1 frame 分進める。
     *
     * @param currentLocation 現在表示中の位置
     * @param targetLocation 追従対象の位置
     * @param baseSpeedMps tick 由来または tick 間差分由来の基礎速度
     * @param sampleIntervalSeconds 直近 tick 間隔。未取得の場合は null
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return 1 frame 分進めた表示位置
     */
    private fun advanceDisplayLocation(
        currentLocation: RoutePoint,
        targetLocation: RoutePoint,
        baseSpeedMps: Double,
        sampleIntervalSeconds: Double?,
        frameElapsedSeconds: Double,
    ): RoutePoint {
        if (frameElapsedSeconds <= 0.0) return currentLocation

        val errorMeters = haversineMeters(currentLocation, targetLocation)
        if (errorMeters == 0.0) return currentLocation

        val correctionSpeedMps = sampleIntervalSeconds
            ?.takeIf { seconds -> seconds > 0.0 }
            ?.let { seconds -> errorMeters / seconds }
            ?: 0.0
        val maxStepMeters = (baseSpeedMps + correctionSpeedMps) * frameElapsedSeconds
        if (errorMeters <= maxStepMeters) return targetLocation

        return destinationPoint(
            origin = currentLocation,
            bearingDegrees = bearingDegrees(currentLocation, targetLocation),
            distanceMeters = maxStepMeters,
        )
    }

    /**
     * frame 時点で表示する route 累積距離を更新する。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param route route geometry と累積距離 index
     * @param nowElapsedRealtimeNanos 推定対象の monotonic clock 時刻
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return 今回 frame で表示する route 累積距離
     */
    private fun displayProgressMeters(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
        route: RouteMeterIndex,
        nowElapsedRealtimeNanos: Long,
        frameElapsedSeconds: Double,
    ): Double {
        val currentDisplayProgress = displayRouteProgressMeters
            ?: latest.state.routeProgressMeters
            ?: return route.coerceDistance(0.0)
        val targetProgressMeters = targetProgressMeters(
            latest = latest,
            previous = previous,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )

        val nextDisplayProgress = advanceDisplayProgress(
            currentProgressMeters = currentDisplayProgress,
            targetProgressMeters = targetProgressMeters,
            baseSpeedMps = latest.speedMps() ?: derivedSpeedMps(latest = latest, previous = previous) ?: 0.0,
            sampleIntervalSeconds = recentSampleIntervalSeconds(latest = latest, previous = previous),
            frameElapsedSeconds = frameElapsedSeconds,
        )

        return route.coerceDistance(nextDisplayProgress).also { progressMeters ->
            displayRouteProgressMeters = progressMeters
        }
    }

    /**
     * 最新 tick と速度から、今 frame で追いかける target route 累積距離を返す。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param nowElapsedRealtimeNanos 推定対象の monotonic clock 時刻
     * @return 追従対象の route 累積距離
     */
    private fun targetProgressMeters(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
        nowElapsedRealtimeNanos: Long,
    ): Double {
        val latestProgressMeters = latest.state.routeProgressMeters ?: return 0.0
        val speedMps = latest.speedMps()
            ?: derivedSpeedMps(latest = latest, previous = previous)
            ?: 0.0
        val elapsedSeconds = elapsedSeconds(
            fromElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
            toElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )

        return latestProgressMeters + speedMps * elapsedSeconds
    }

    /**
     * 表示中の route 累積距離を target へ向けて 1 frame 分進める。
     *
     * target との誤差は、直近の tick 間隔を使って自然に追いつく速度へ変換する。
     * 固定 duration は使わず、位置更新が速い環境では短く、遅い環境では長く補正される。
     *
     * @param currentProgressMeters 現在表示中の route 累積距離
     * @param targetProgressMeters 追従対象の route 累積距離
     * @param baseSpeedMps tick 由来または tick 間差分由来の基礎速度
     * @param sampleIntervalSeconds 直近 tick 間隔。未取得の場合は null
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return 1 frame 分進めた表示 route 累積距離
     */
    private fun advanceDisplayProgress(
        currentProgressMeters: Double,
        targetProgressMeters: Double,
        baseSpeedMps: Double,
        sampleIntervalSeconds: Double?,
        frameElapsedSeconds: Double,
    ): Double {
        if (frameElapsedSeconds <= 0.0) return currentProgressMeters

        val errorMeters = targetProgressMeters - currentProgressMeters
        if (errorMeters == 0.0) return currentProgressMeters

        val correctionSpeedMps = sampleIntervalSeconds
            ?.takeIf { seconds -> seconds > 0.0 }
            ?.let { seconds -> errorMeters.absoluteValue / seconds }
            ?: 0.0
        val maxStepMeters = (baseSpeedMps + correctionSpeedMps) * frameElapsedSeconds

        return if (errorMeters.absoluteValue <= maxStepMeters) {
            targetProgressMeters
        } else {
            currentProgressMeters + errorMeters.sign * maxStepMeters
        }
    }

    /**
     * 表示中の方位を target へ向けて 1 frame 分回転させる。
     *
     * 直近 tick 間隔に応じて補正角速度を決めるため、固定 duration には依存しない。
     *
     * @param targetBearingDegrees 追従対象の方位。取得できない場合は null
     * @param sampleIntervalSeconds 直近 tick 間隔。未取得の場合は null
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return 今回 frame で表示する方位。算出できない場合は null
     */
    private fun displayBearingDegrees(
        targetBearingDegrees: Float?,
        sampleIntervalSeconds: Double?,
        frameElapsedSeconds: Double,
    ): Float? {
        val target = targetBearingDegrees ?: return displayBearingDegrees
        val current = displayBearingDegrees ?: return target.also { bearingDegrees ->
            displayBearingDegrees = bearingDegrees
        }
        val deltaDegrees = shortestAngleDeltaDegrees(from = current, to = target)
        if (deltaDegrees == 0f || frameElapsedSeconds <= 0.0) {
            displayBearingDegrees = current
            return current
        }

        val remainingDegrees = deltaDegrees.absoluteValue
        if (remainingDegrees <= BEARING_SNAP_EPSILON_DEGREES) {
            displayBearingDegrees = target
            return target
        }

        val stepDegrees = bearingStepDegrees(
            remainingDegrees = remainingDegrees,
            sampleIntervalSeconds = sampleIntervalSeconds,
            frameElapsedSeconds = frameElapsedSeconds,
        )
        val next = current + deltaDegrees.sign * stepDegrees

        return normalizeBearingDegrees(next).also { bearingDegrees ->
            displayBearingDegrees = bearingDegrees
        }
    }

    /**
     * 方位補間で 1 frame に進める角度を返す。
     *
     * 小さい方位差は低域フィルタで複数 frame に分け、大きい方位差は角速度上限で急回転を抑える。
     * 単純な debounce / delay は緩やかなカーブで target 更新を溜めてしまうため、ここでは入力を遅延させず
     * 出力側だけを滑らかにする。
     *
     * @param remainingDegrees target 方位までの残り角度
     * @param sampleIntervalSeconds 直近 tick 間隔。未取得の場合は null
     * @param frameElapsedSeconds 前回 estimate からの経過秒数
     * @return 今 frame で進める角度
     */
    private fun bearingStepDegrees(
        remainingDegrees: Float,
        sampleIntervalSeconds: Double?,
        frameElapsedSeconds: Double,
    ): Float {
        if (frameElapsedSeconds <= 0.0 || remainingDegrees <= 0f) return 0f

        val observedSampleSeconds = sampleIntervalSeconds
            ?.takeIf { seconds -> seconds > 0.0 }
            ?: frameElapsedSeconds
        val minimumVisibleSeconds = frameElapsedSeconds * MIN_BEARING_ANIMATION_FRAME_COUNT
        val effectiveSampleSeconds = max(observedSampleSeconds, minimumVisibleSeconds)
        val responseSeconds = effectiveSampleSeconds / BEARING_LOW_PASS_RESPONSE_PER_SAMPLE
        val lowPassFraction = frameElapsedSeconds / (responseSeconds + frameElapsedSeconds)
        val smoothedStepDegrees = remainingDegrees * lowPassFraction
        val degreesPerSecond = RIGHT_ANGLE_DEGREES / effectiveSampleSeconds
        val maxStepDegrees = degreesPerSecond * frameElapsedSeconds

        return smoothedStepDegrees
            .coerceAtMost(maxStepDegrees)
            .toFloat()
            .coerceAtMost(remainingDegrees)
    }

    /**
     * 前回 estimate から今回 estimate までの経過秒数を返す。
     *
     * @param nowElapsedRealtimeNanos 今回 frame の monotonic clock 時刻
     * @return 前回 frame からの経過秒数。初回は 0
     */
    private fun frameElapsedSeconds(nowElapsedRealtimeNanos: Long): Double {
        val previousEstimateElapsedRealtimeNanos = lastEstimateElapsedRealtimeNanos
        lastEstimateElapsedRealtimeNanos = nowElapsedRealtimeNanos

        return previousEstimateElapsedRealtimeNanos
            ?.let { previous ->
                elapsedSeconds(
                    fromElapsedRealtimeNanos = previous,
                    toElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                )
            }
            ?: 0.0
    }

    /**
     * 直近 2 tick の route 累積距離差から速度を補完する。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @return 算出できた非負の速度。算出できない場合は null
     */
    private fun derivedSpeedMps(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
    ): Double? {
        val latestProgress = latest.state.routeProgressMeters ?: return null
        val previousProgress = previous?.state?.routeProgressMeters ?: return null
        val elapsedSeconds = elapsedSeconds(
            fromElapsedRealtimeNanos = previous.elapsedRealtimeNanos,
            toElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
        )
        if (elapsedSeconds <= 0.0) return null

        return max(0.0, latestProgress - previousProgress) / elapsedSeconds
    }

    /**
     * 直近 2 tick の位置差から route 外の速度を補完する。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @return 算出できた非負の速度。算出できない場合は null
     */
    private fun derivedFreeSpeedMps(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
    ): Double? {
        val previousLocation = previous?.state?.location ?: return null
        val elapsedSeconds = elapsedSeconds(
            fromElapsedRealtimeNanos = previous.elapsedRealtimeNanos,
            toElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
        )
        if (elapsedSeconds <= 0.0) return null

        return haversineMeters(previousLocation, latest.state.location) / elapsedSeconds
    }

    /**
     * 直近 2 tick の monotonic clock 差分を秒で返す。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。未取得の場合は null
     * @return 直近 tick 間隔。算出できない場合は null
     */
    private fun sampleIntervalSeconds(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
    ): Double? = previous
        ?.let { previousSample ->
            elapsedSeconds(
                fromElapsedRealtimeNanos = previousSample.elapsedRealtimeNanos,
                toElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
            )
        }
        ?.takeIf { seconds -> seconds > 0.0 }

    /**
     * 方位・位置補間に使う直近の tick 間隔を返す。
     *
     * route 切替直後は進捗距離の基準が変わるため [previousSample] を切るが、tick 間隔そのものは
     * 補間速度の基準として使える。そこで直前に観測した tick 間隔を fallback にし、route 切替直後も
     * 方位補間が急に速くならないようにする。
     *
     * @param latest 最新 tick
     * @param previous 1 つ前の tick。route 切替直後は null になる
     * @return 補間に使う tick 間隔。算出できない場合は null
     */
    private fun recentSampleIntervalSeconds(
        latest: TimedVehicleLocation,
        previous: TimedVehicleLocation?,
    ): Double? = sampleIntervalSeconds(latest = latest, previous = previous)
        ?: latestSampleIntervalSeconds

    /**
     * monotonic 時刻を補完済みの自車位置 tick。
     *
     * @param state 元の自車位置 tick
     * @param elapsedRealtimeNanos tick の monotonic clock 時刻
     */
    private data class TimedVehicleLocation(
        val state: VehicleLocationState,
        val elapsedRealtimeNanos: Long,
    ) {
        /**
         * tick に含まれる有効な速度を Double に変換する。
         *
         * @return 有効な非負速度。取得できない場合は null
         */
        fun speedMps(): Double? = state.speedMps
            ?.takeIf { speed -> speed.isFinite() && speed >= 0f }
            ?.toDouble()
    }

    /**
     * route geometry と累積距離 index。
     *
     * @param points route geometry
     * @param cumulativeMeters 各 geometry 点までの累積距離
     */
    private class RouteMeterIndex private constructor(
        private val points: List<RoutePoint>,
        private val cumulativeMeters: List<Double>,
    ) {
        private val totalMeters: Double = cumulativeMeters.last()

        /**
         * route 上の距離を geometry 範囲内に丸める。
         *
         * @param distanceMeters route 始点からの距離
         * @return route geometry 範囲に収まる距離
         */
        fun coerceDistance(distanceMeters: Double): Double = distanceMeters.coerceIn(0.0, totalMeters)

        /**
         * route 上の距離から表示 pose を作る。
         *
         * @param distanceMeters route 始点からの距離
         * @param fallbackBearingDegrees segment 方位が使えない場合の向き
         * @return route geometry 上に補間した表示 pose
         */
        fun poseAt(
            distanceMeters: Double,
            fallbackBearingDegrees: Float?,
        ): VehiclePose {
            val targetMeters = coerceDistance(distanceMeters)
            val segmentIndex = segmentIndexAt(targetMeters)
            val segmentStart = points[segmentIndex]
            val segmentEnd = points[segmentIndex + 1]
            val segmentStartMeters = cumulativeMeters[segmentIndex]
            val segmentEndMeters = cumulativeMeters[segmentIndex + 1]
            val segmentMeters = segmentEndMeters - segmentStartMeters
            val fraction = if (segmentMeters > 0.0) {
                ((targetMeters - segmentStartMeters) / segmentMeters).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val segmentBearingDegrees = if (segmentMeters > 0.0) {
                bearingDegrees(segmentStart, segmentEnd)
            } else {
                fallbackBearingDegrees
            }

            return VehiclePose(
                location = RoutePoint(
                    latitude = lerp(segmentStart.latitude, segmentEnd.latitude, fraction),
                    longitude = lerp(segmentStart.longitude, segmentEnd.longitude, fraction),
                ),
                bearingDegrees = segmentBearingDegrees,
            )
        }

        /**
         * 指定距離を含む geometry segment index を二分探索で返す。
         *
         * @param distanceMeters route 始点からの距離
         * @return [points] の segment 開始 index
         */
        private fun segmentIndexAt(distanceMeters: Double): Int {
            var low = 0
            var high = cumulativeMeters.lastIndex

            while (low <= high) {
                val mid = (low + high).ushr(1)
                val midValue = cumulativeMeters[mid]

                when {
                    midValue < distanceMeters -> low = mid + 1
                    midValue > distanceMeters -> high = mid - 1
                    else -> return mid.coerceAtMost(points.lastIndex - 1)
                }
            }

            return (low - 1).coerceIn(0, points.lastIndex - 1)
        }

        companion object {

            /**
             * route geometry から累積距離 index を作る。
             *
             * @param points route geometry
             * @return 2 点以上の geometry から作った index。点数不足の場合は null
             */
            fun from(points: List<RoutePoint>): RouteMeterIndex? {
                if (points.size < 2) return null

                val cumulativeMeters = buildList(capacity = points.size) {
                    var totalMeters = 0.0
                    add(totalMeters)

                    for (index in 1 until points.size) {
                        totalMeters += haversineMeters(points[index - 1], points[index])
                        add(totalMeters)
                    }
                }

                return RouteMeterIndex(
                    points = points,
                    cumulativeMeters = cumulativeMeters,
                )
            }
        }
    }
}

/**
 * 2 つの monotonic 時刻の差分を秒へ変換する。
 *
 * @param fromElapsedRealtimeNanos 開始時刻
 * @param toElapsedRealtimeNanos 終了時刻
 * @return 負値を 0 に丸めた経過秒数
 */
private fun elapsedSeconds(
    fromElapsedRealtimeNanos: Long,
    toElapsedRealtimeNanos: Long,
): Double = (toElapsedRealtimeNanos - fromElapsedRealtimeNanos)
    .coerceAtLeast(0L)
    .toDouble() / NANOS_PER_SECOND

/**
 * 2 点間の球面距離を Haversine で計算する。
 *
 * @param from 始点
 * @param to 終点
 * @return 2 点間距離（m）
 */
private fun haversineMeters(from: RoutePoint, to: RoutePoint): Double {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)

    val haversine = sin(latitudeDelta / 2).let { value -> value * value } +
        cos(fromLatitude) * cos(toLatitude) *
        sin(longitudeDelta / 2).let { value -> value * value }

    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
}

/**
 * 2 点を結ぶ初期方位を返す。
 *
 * @param from 始点
 * @param to 終点
 * @return 北を 0 度とする時計回り方位
 */
private fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    val y = sin(longitudeDelta) * cos(toLatitude)
    val x = cos(fromLatitude) * sin(toLatitude) -
        sin(fromLatitude) * cos(toLatitude) * cos(longitudeDelta)

    return ((Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES).toFloat()
}

/**
 * 2 点を結ぶ初期方位を返す。
 *
 * @param from 始点。null の場合は null
 * @param to 終点
 * @return 北を 0 度とする時計回り方位。始点が無い場合や同一点の場合は null
 */
private fun bearingDegreesOrNull(from: RoutePoint?, to: RoutePoint): Float? {
    if (from == null || from == to) return null

    return bearingDegrees(from = from, to = to)
}

/**
 * 2 つの方位角の最短差分を返す。
 *
 * @param from 開始方位
 * @param to 目標方位
 * @return -180〜180 度の差分
 */
private fun shortestAngleDeltaDegrees(from: Float, to: Float): Float =
    ((to - from + ANGLE_DELTA_NORMALIZE_OFFSET_DEGREES) % FULL_CIRCLE_DEGREES - HALF_CIRCLE_DEGREES)
        .toFloat()

/**
 * 方位角を 0〜360 度へ正規化する。
 *
 * @param bearingDegrees 正規化前の方位
 * @return 正規化後の方位
 */
private fun normalizeBearingDegrees(bearingDegrees: Float): Float =
    ((bearingDegrees + FULL_CIRCLE_DEGREES.toFloat()) % FULL_CIRCLE_DEGREES.toFloat())

/**
 * 始点から指定距離だけ指定方位へ進んだ地点を返す。
 *
 * @param origin 始点
 * @param bearingDegrees 北を 0 度とする時計回り方位
 * @param distanceMeters 移動距離（m）
 * @return 移動後の緯度経度
 */
private fun destinationPoint(
    origin: RoutePoint,
    bearingDegrees: Float,
    distanceMeters: Double,
): RoutePoint {
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS
    val bearingRadians = Math.toRadians(bearingDegrees.toDouble())
    val originLatitude = Math.toRadians(origin.latitude)
    val originLongitude = Math.toRadians(origin.longitude)
    val targetLatitude = asin(
        sin(originLatitude) * cos(angularDistance) +
            cos(originLatitude) * sin(angularDistance) * cos(bearingRadians),
    )
    val targetLongitude = originLongitude + atan2(
        sin(bearingRadians) * sin(angularDistance) * cos(originLatitude),
        cos(angularDistance) - sin(originLatitude) * sin(targetLatitude),
    )

    return RoutePoint(
        latitude = Math.toDegrees(targetLatitude),
        longitude = normalizeLongitude(Math.toDegrees(targetLongitude)),
    )
}

/**
 * 経度を -180〜180 度へ正規化する。
 *
 * @param longitude 正規化前の経度
 * @return 正規化後の経度
 */
private fun normalizeLongitude(longitude: Double): Double =
    ((longitude + LONGITUDE_NORMALIZE_OFFSET_DEGREES) % FULL_CIRCLE_DEGREES) -
        HALF_CIRCLE_DEGREES

/**
 * 2 つの数値を線形補間する。
 *
 * @param from 開始値
 * @param to 終了値
 * @param fraction 補間率
 * @return 補間後の値
 */
private fun lerp(from: Double, to: Double, fraction: Double): Double =
    from + (to - from) * fraction

/** 1 秒あたりの nanosecond 数。 */
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** Haversine 計算に使う平均地球半径（m）。 */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/** 方位を正規化するための 1 周分の角度。 */
private const val FULL_CIRCLE_DEGREES = 360.0

/** 経度正規化に使う半周分の角度。 */
private const val HALF_CIRCLE_DEGREES = 180.0

/** 経度正規化で 0 未満の剰余を避けるための offset。 */
private const val LONGITUDE_NORMALIZE_OFFSET_DEGREES = 540.0

/** 方位差を -180〜180 度へ正規化するための offset。 */
private const val ANGLE_DELTA_NORMALIZE_OFFSET_DEGREES = 540.0

/** route geometry として扱うために必要な最小点数。 */
private const val MIN_ROUTE_GEOMETRY_POINT_COUNT = 2

/** 角速度の基準にする 1 直角分の角度。 */
private const val RIGHT_ANGLE_DEGREES = 90.0

/** 方位差が大きい tick でも最低限分割する描画 frame 数。 */
private const val MIN_BEARING_ANIMATION_FRAME_COUNT = 12.0

/** 目視できない残差として target 方位へ丸める角度。 */
private const val BEARING_SNAP_EPSILON_DEGREES = 0.05f

/** 直近 tick 間隔のうち低域フィルタの応答時間として使う割合の逆数。 */
private const val BEARING_LOW_PASS_RESPONSE_PER_SAMPLE = 3.0
