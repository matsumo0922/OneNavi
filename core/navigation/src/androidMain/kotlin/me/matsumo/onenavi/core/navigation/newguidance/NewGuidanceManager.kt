package me.matsumo.onenavi.core.navigation.newguidance

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState

/**
 * Guidance 期 (案内中) のマネージャ。
 *
 * 地図描画・callout・音声案内はすべて自前で行い、Google Navigation SDK の Navigator は使わない
 * (NavigationView は地図描画専用)。現状は [GuidanceState] の state machine のみを持ち、走行進捗の
 * 追跡・音声案内・リルートの実体は [me.matsumo.onenavi.core.navigation.extnav] 配下のコンポーネント
 * と接続予定。
 */
class NewGuidanceManager(
    private val routeRegistry: ExtNavRouteRegistry? = null,
    private val guidanceTracker: ExtNavGuidanceTracker? = null,
) {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** 指定ルートで案内を開始する。 */
    fun startGuidance(route: RouteDetail) {
        Napier.i(tag = TAG) { "Guidance started: routeId=${route.id}" }
        val trackerProgress = startTrackerForRoute(route)
        _state.value = GuidanceState.Guiding(
            route = route,
            progress = trackerProgress ?: route.toInitialProgress(),
        )
    }

    /** 案内を停止して Idle に戻す。 */
    fun stopGuidance() {
        Napier.i(tag = TAG) { "Guidance stopped" }
        guidanceTracker?.detach()
        _state.value = GuidanceState.Idle
    }

    /** Manager 破棄時に呼ぶ。 */
    fun release() {
        stopGuidance()
    }

    private fun startTrackerForRoute(route: RouteDetail): GuidanceProgress? {
        val registry = routeRegistry
        val tracker = guidanceTracker
        if (registry == null || tracker == null) {
            Napier.w(tag = TAG) { "Tracker dependencies are not injected. Falling back to initial progress." }
            return null
        }

        val payload = registry.get(route.id)
        if (payload == null) {
            Napier.w(tag = TAG) { "Route payload not found. routeId=${route.id}" }
            return null
        }

        tracker.attach(payload = payload, route = route)
        tracker.onLocation(route.toOriginUserLocation())

        val snapshot = tracker.snapshot.value
        if (snapshot == null) {
            Napier.w(tag = TAG) { "Tracker snapshot was not produced. routeId=${route.id}" }
            return null
        }

        Napier.i(tag = TAG) {
            "Tracker initial snapshot: routeId=${route.id}, " +
                "remainingMeters=${snapshot.progress.distanceRemainingMeters}, " +
                "nextGp=${snapshot.nextGuidancePointIndex}"
        }
        return snapshot.progress
    }

    private companion object {
        const val TAG = "NewGuidanceManager"
    }
}

private fun RouteDetail.toOriginUserLocation(): UserLocation = UserLocation(
    latitude = origin.latitude,
    longitude = origin.longitude,
    bearingDegrees = null,
    speedMps = null,
    accuracyMeters = 0f,
    timestampMillis = System.currentTimeMillis(),
)

private fun RouteDetail.toInitialProgress(): GuidanceProgress = GuidanceProgress(
    distanceRemainingMeters = distanceMeters.toInt(),
    durationRemainingSeconds = durationSeconds.toInt(),
    etaEpochMillis = System.currentTimeMillis() + durationSeconds.toLong() * 1_000L,
    traveledMeters = 0,
    snappedLocation = origin,
    bearingDegrees = 0f,
    nextManeuver = null,
    followupManeuver = null,
    lanes = persistentListOf(),
    directionSign = null,
    highwayPanel = null,
    currentRoadName = null,
    currentRoadClass = roadClassSegments.firstOrNull()?.roadClass ?: RoadClass.ORDINARY,
    currentSpeedLimitKmh = null,
)
