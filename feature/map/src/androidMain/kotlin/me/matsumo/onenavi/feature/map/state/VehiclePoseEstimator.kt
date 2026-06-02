package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.sign

/**
 * 低頻度の位置 tick から、画面描画時点の自車 pose を推定する。
 *
 * GPS / tracker から届く tick は描画 frame より粗いため、案内中は route geometry 上の累積距離と速度から
 * 現在 frame 時点の位置を推定する。案内中以外は route 進捗を持たないため、最後に届いた位置をそのまま返す。
 */
internal class VehiclePoseEstimator {

    private var routeMeterIndex: RouteMeterIndex? = null
    private var routeKeyRef: String? = null
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
     * @param routeKey 案内中 route を識別する key。案内中以外は null
     * @param routeGeometry 案内中 route の geometry。案内中以外は空でよい
     * @param nowElapsedRealtimeNanos sample が monotonic 時刻を持たない場合に補完する現在時刻
     */
    fun updateSample(
        sample: VehicleLocationState,
        routeKey: String?,
        routeGeometry: List<RoutePoint>,
        nowElapsedRealtimeNanos: Long,
    ) {
        val nextSample = TimedVehicleLocation(
            state = sample,
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos ?: nowElapsedRealtimeNanos,
        )
        val previousLatestSample = latestSample
        val previousRouteKey = routeKeyRef
        val isRouteChanged = updateRouteGeometryIfNeeded(
            sample = sample,
            routeKey = routeKey,
            routeGeometry = routeGeometry,
        )
        val hasProgressDiscontinuity = hasRouteProgressDiscontinuity(
            previous = previousLatestSample,
            next = nextSample,
        )
        val hasRouteProgressResumed = hasRouteProgressResumed(
            previous = previousLatestSample,
            next = nextSample,
        )
        val hasDisplayRouteProgressJump = hasDisplayRouteProgressJump(next = nextSample)
        val hasDisplayFreeLocationJump = hasDisplayFreeLocationJump(next = nextSample)
        val shouldResetProgressByRouteState = isRouteChanged || hasProgressDiscontinuity || hasRouteProgressResumed
        val shouldResetProgress = shouldResetProgressByRouteState || hasDisplayRouteProgressJump
        val shouldResetSampleHistory = shouldResetProgress || hasDisplayFreeLocationJump
        val shouldResetBearingByRouteSession = shouldResetBearingForRouteSession(
            previousRouteKey = previousRouteKey,
            nextRouteKey = routeKey,
            nextSample = nextSample,
        )
        val shouldResetBearingByProgressReset = shouldResetBearingForRouteProgressReset(
            hasProgressDiscontinuity = hasProgressDiscontinuity,
            nextSample = nextSample,
        )
        val shouldResetBearingByDisplayJump = hasDisplayRouteProgressJump || hasDisplayFreeLocationJump
        val shouldResetBearingByRoute = shouldResetBearingByRouteSession || shouldResetBearingByProgressReset
        val shouldResetBearing = shouldResetBearingByRoute || shouldResetBearingByDisplayJump

        latestSampleIntervalSeconds = sampleIntervalSeconds(
            latest = nextSample,
            previous = previousLatestSample,
        ) ?: latestSampleIntervalSeconds

        previousSample = if (shouldResetSampleHistory) null else previousLatestSample
        latestSample = nextSample

        if (shouldResetProgress || displayRouteProgressMeters == null) {
            displayRouteProgressMeters = nextSample.state.routeProgressMeters
        }
        if (shouldResetBearing) {
            displayBearingDegrees = null
        }
        if (hasDisplayFreeLocationJump) {
            displayFreeLocation = nextSample.state.location
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
     * @param routeKey 案内中 route を識別する key。案内中以外は null
     * @param routeGeometry 案内中 route の geometry
     * @return route geometry を更新した場合は true
     */
    private fun updateRouteGeometryIfNeeded(
        sample: VehicleLocationState,
        routeKey: String?,
        routeGeometry: List<RoutePoint>,
    ): Boolean {
        if (shouldKeepCurrentRouteGeometry(sample = sample, routeGeometry = routeGeometry)) {
            return false
        }

        if (routeKeyRef == routeKey && routeGeometryRef == routeGeometry) return false

        val routeGeometrySnapshot = routeGeometry.toList()
        routeKeyRef = routeKey
        routeGeometryRef = routeGeometrySnapshot
        routeMeterIndex = RouteMeterIndex.from(routeGeometrySnapshot)
        displayRouteProgressMeters = null
        if (sample.routeProgressMeters != null) {
            displayFreeLocation = null
        }

        return true
    }

    /**
     * route 進捗の不連続を検知する。
     *
     * 同一 geometry の案内再開始や同一 route id の再利用では、route geometry だけでは表示状態を
     * reset できない。進捗が大きく戻った場合は別セッション相当として位置の表示状態を reset する。
     *
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param next 最新 tick
     * @return 表示中の route 進捗を reset すべき場合は true
     */
    private fun hasRouteProgressDiscontinuity(
        previous: TimedVehicleLocation?,
        next: TimedVehicleLocation,
    ): Boolean {
        val previousProgressMeters = previous?.state?.routeProgressMeters ?: return false
        val nextProgressMeters = next.state.routeProgressMeters ?: return false

        return previousProgressMeters - nextProgressMeters > ROUTE_PROGRESS_RESET_BACKWARD_METERS
    }

    /**
     * route 外表示から route-snapped 表示へ戻ったかを返す。
     *
     * route 逸脱中は [displayRouteProgressMeters] を進めないため、復帰時に古い route 進捗から
     * catch-up させると自車アイコンが route 上を急スライドして見える。route progress が再開した
     * tick では、表示進捗を最新の snap 進捗に合わせて再開する。
     *
     * @param previous 1 つ前の tick。未取得の場合は null
     * @param next 最新 tick
     * @return route progress が null から非 null へ復帰した場合は true
     */
    private fun hasRouteProgressResumed(
        previous: TimedVehicleLocation?,
        next: TimedVehicleLocation,
    ): Boolean {
        val previousProgressMeters = previous?.state?.routeProgressMeters
        val nextProgressMeters = next.state.routeProgressMeters

        return previousProgressMeters == null && nextProgressMeters != null
    }

    /**
     * 表示中の route 進捗から最新 tick まで大きく前進したかを返す。
     *
     * GPS 復帰や provider 切替で route projection が一気に進んだ場合は、古い表示進捗から補間すると
     * route 上を不自然に滑るため、表示進捗を最新 tick へ即時 reset する。
     *
     * @param next 最新 tick
     * @return 表示進捗を最新 tick へ即時 reset すべき場合 true
     */
    private fun hasDisplayRouteProgressJump(next: TimedVehicleLocation): Boolean {
        val currentDisplayProgressMeters = displayRouteProgressMeters ?: return false
        val nextProgressMeters = next.state.routeProgressMeters ?: return false
        val forwardMeters = nextProgressMeters - currentDisplayProgressMeters

        return forwardMeters > ROUTE_PROGRESS_SNAP_FORWARD_METERS
    }

    /**
     * 表示中の自由位置から最新 tick まで大きく離れたかを返す。
     *
     * 入力 tick は [VehicleLocationStabilizer] や案内 tracker 側で既に採否判定済みなので、ここでは
     * 採用済み位置への見た目の追従だけを判定する。小さな GPS 揺れや通常移動は従来どおり補間する。
     *
     * @param next 最新 tick
     * @return 表示位置を最新 tick へ即時 reset すべき場合 true
     */
    private fun hasDisplayFreeLocationJump(next: TimedVehicleLocation): Boolean {
        if (next.state.routeProgressMeters != null) return false

        val currentDisplayLocation = displayFreeLocation ?: return false
        val distanceMeters = MapGeodesy.haversineMeters(currentDisplayLocation, next.state.location)

        return distanceMeters > FREE_LOCATION_SNAP_DISTANCE_METERS
    }

    /**
     * route セッション切替時に表示方位を reset すべきかを返す。
     *
     * 走行中の reroute では現在の表示方位から新 route の方位へ補間したい。一方、同じ geometry で案内を
     * 始点付近から再開始した場合は、前セッション終端の表示方位を引き継がない方が自然になる。
     *
     * @param previousRouteKey 直前に保持していた route key
     * @param nextRouteKey 最新 tick に紐づく route key
     * @param nextSample 最新 tick
     * @return 表示方位を reset すべき場合は true
     */
    private fun shouldResetBearingForRouteSession(
        previousRouteKey: String?,
        nextRouteKey: String?,
        nextSample: TimedVehicleLocation,
    ): Boolean {
        if (previousRouteKey == null || nextRouteKey == null || previousRouteKey == nextRouteKey) {
            return false
        }

        val nextProgressMeters = nextSample.state.routeProgressMeters ?: return false

        return nextProgressMeters <= ROUTE_SESSION_START_PROGRESS_METERS
    }

    /**
     * route 進捗の reset に合わせて表示方位も reset すべきかを返す。
     *
     * 同じ route key が再利用されても、進捗が始点付近へ大きく戻った場合は前セッションの方位を
     * 引き継がない方が自然になる。
     *
     * @param hasProgressDiscontinuity route 進捗が大きく戻ったか
     * @param nextSample 最新 tick
     * @return 表示方位を reset すべき場合は true
     */
    private fun shouldResetBearingForRouteProgressReset(
        hasProgressDiscontinuity: Boolean,
        nextSample: TimedVehicleLocation,
    ): Boolean {
        if (!hasProgressDiscontinuity) return false

        val nextProgressMeters = nextSample.state.routeProgressMeters ?: return false

        return nextProgressMeters <= ROUTE_SESSION_START_PROGRESS_METERS
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
            !RouteMeterIndex.canBuild(routeGeometry) &&
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
                targetBearingDegrees = latest.state.bearingDegrees ?: MapGeodesy.bearingDegreesOrNull(
                    from = currentLocation,
                    to = targetLocation,
                ),
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
            ?: MapGeodesy.bearingDegreesOrNull(
                from = previous?.state?.location,
                to = latest.state.location,
            )
            ?: return latest.state.location
        val elapsedSeconds = elapsedSeconds(
            fromElapsedRealtimeNanos = latest.elapsedRealtimeNanos,
            toElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )

        return MapGeodesy.destinationPoint(
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

        val errorMeters = MapGeodesy.haversineMeters(currentLocation, targetLocation)
        if (errorMeters == 0.0) return currentLocation

        val correctionSpeedMps = sampleIntervalSeconds
            ?.takeIf { seconds -> seconds > 0.0 }
            ?.let { seconds -> errorMeters / seconds }
            ?: 0.0
        val maxStepMeters = (baseSpeedMps + correctionSpeedMps) * frameElapsedSeconds
        if (errorMeters <= maxStepMeters) return targetLocation

        return MapGeodesy.destinationPoint(
            origin = currentLocation,
            bearingDegrees = MapGeodesy.bearingDegrees(from = currentLocation, to = targetLocation),
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
        val deltaDegrees = MapGeodesy.shortestAngleDeltaDegrees(from = current, to = target)
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

        return MapGeodesy.normalizeBearingDegrees(next).also { bearingDegrees ->
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

        return MapGeodesy.haversineMeters(previousLocation, latest.state.location) / elapsedSeconds
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
): Double = MapTime.elapsedSeconds(
    fromElapsedRealtimeNanos = fromElapsedRealtimeNanos,
    toElapsedRealtimeNanos = toElapsedRealtimeNanos,
)

/** route 進捗がこの距離以上戻った場合に別セッション相当として reset する距離。 */
private const val ROUTE_PROGRESS_RESET_BACKWARD_METERS = 100.0

/** 表示中の route 進捗からこの距離を超えて前進した場合に補間せず即時反映する距離。 */
private const val ROUTE_PROGRESS_SNAP_FORWARD_METERS = 200.0

/** 表示中の自由位置からこの距離を超えて離れた場合に補間せず即時反映する距離。 */
private const val FREE_LOCATION_SNAP_DISTANCE_METERS = 200.0

/** route key 切替時に案内再開始とみなす始点付近の進捗距離。 */
private const val ROUTE_SESSION_START_PROGRESS_METERS = 50.0

/** 角速度の基準にする 1 直角分の角度。 */
private const val RIGHT_ANGLE_DEGREES = 90.0

/** 方位差が大きい tick でも最低限分割する描画 frame 数。 */
private const val MIN_BEARING_ANIMATION_FRAME_COUNT = 12.0

/** 目視できない残差として target 方位へ丸める角度。 */
private const val BEARING_SNAP_EPSILON_DEGREES = 0.05f

/** 直近 tick 間隔のうち低域フィルタの応答時間として使う割合の逆数。 */
private const val BEARING_LOW_PASS_RESPONSE_PER_SAMPLE = 3.0
