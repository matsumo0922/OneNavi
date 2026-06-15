package me.matsumo.onenavi.core.navigation.newguidance

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.RouteRepository

/**
 * Preview 期のルート探索と候補管理を行うマネージャ。
 *
 * 外部ナビ API ライブラリ ([me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDataSource]) から
 * 候補ルートを取得し、結果を [state] (StateFlow<[RoutePreviewState]>) で公開する。地図描画は
 * [me.matsumo.onenavi.core.model.RouteDetail.geometry] をそのまま自前 polyline で描く。Guidance 開始時は
 * [RoutePreviewState.Ready.selectedRoute] を [NewGuidanceManager] に渡す。
 *
 * @param routeRepository [me.matsumo.onenavi.core.datasource.RouteDataSource] のラッパ
 */
class NewRouteManager(
    private val routeRepository: RouteRepository,
) {
    private val searchGenerationLock = Any()
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    private var latestSearchGeneration = INITIAL_SEARCH_GENERATION

    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    /**
     * 指定 waypoint 列のルート候補を探索する。
     *
     * [waypoints] の先頭が origin、末尾が destination、間の要素は intermediate 経由地として扱う。
     * 探索中は [RoutePreviewState.Searching]、完了で [RoutePreviewState.Ready]、失敗で
     * [RoutePreviewState.Failed] に遷移する。より新しい探索に追い越された場合は state を更新せず
     * null を返す。
     *
     * @return この呼び出しの探索結果。より新しい探索に追い越された場合は null
     */
    suspend fun searchRoutes(waypoints: List<RouteWaypoint>): RoutePreviewState? {
        require(waypoints.size >= 2) {
            "waypoints must contain at least origin and destination (size=${waypoints.size})"
        }

        val searchGeneration = nextSearchGeneration()
        _state.value = RoutePreviewState.Searching

        val origin = waypoints.first().toRoutePoint()
        val destination = waypoints.last().toRoutePoint()
        val intermediates = waypoints.subList(1, waypoints.lastIndex).map { it.toRoutePoint() }

        return try {
            val routes = searchRouteDetails(
                origin = origin,
                destination = destination,
                intermediatePoints = intermediates,
                originDirectionDegrees = null,
            ).withRouteWaypoints(waypoints)
            if (!isLatestSearchGeneration(searchGeneration)) return null

            Napier.i(tag = TAG) { "searchRoutes ready: routes=${routes.size}" }
            RoutePreviewState.Ready(
                routes = routes.toImmutableList(),
                selectedIndex = 0,
            ).also { readyState ->
                _state.value = readyState
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (!isLatestSearchGeneration(searchGeneration)) return null

            Napier.w(tag = TAG, throwable = error) { "searchRoutes failed" }
            RoutePreviewState.Failed(error).also { failedState ->
                _state.value = failedState
            }
        }
    }

    /**
     * [state] を更新せず、指定地点列のルート候補を探索する。
     *
     * ナビゲーション中に一時的な候補ルートを表示したい場合など、通常の RoutePreview 状態を壊したくない
     * 呼び出し元が使用する。
     */
    suspend fun searchRoutePreview(
        origin: RoutePoint,
        destination: RoutePoint,
        intermediatePoints: List<RoutePoint> = emptyList(),
        routeWaypoints: List<RouteWaypoint> = emptyList(),
        originDirectionDegrees: Int? = null,
    ): RoutePreviewState {
        return try {
            val routes = searchRouteDetails(
                origin = origin,
                destination = destination,
                intermediatePoints = intermediatePoints,
                originDirectionDegrees = originDirectionDegrees,
            ).withRouteWaypoints(routeWaypoints)
            RoutePreviewState.Ready(
                routes = routes.toImmutableList(),
                selectedIndex = 0,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            RoutePreviewState.Failed(error)
        }
    }

    /**
     * 候補を切り替える。Ready 以外の状態では何もしない (誤呼び出しを許容)。
     * 範囲外 index は [IllegalArgumentException]。
     */
    fun selectRoute(index: Int) {
        val current = _state.value as? RoutePreviewState.Ready ?: return

        require(index in current.routes.indices) {
            "selectRoute index $index out of routes.indices=${current.routes.indices}"
        }

        if (index == current.selectedIndex) return
        _state.value = current.copy(selectedIndex = index)
    }

    /** Idle に戻す。case 切り替え (Browsing への離脱) で呼ぶ。 */
    fun reset() {
        _state.value = RoutePreviewState.Idle
    }

    private suspend fun searchRouteDetails(
        origin: RoutePoint,
        destination: RoutePoint,
        intermediatePoints: List<RoutePoint>,
        originDirectionDegrees: Int? = null,
    ): List<RouteDetail> {
        val results = routeRepository.searchRoutes(
            originLatitude = origin.latitude,
            originLongitude = origin.longitude,
            destinationLatitude = destination.latitude,
            destinationLongitude = destination.longitude,
            intermediateWaypoints = intermediatePoints.map { point -> point.latitude to point.longitude },
            originDirectionDegrees = originDirectionDegrees,
        ).getOrThrow()

        require(results.isNotEmpty()) { "No route candidates returned" }

        return results.map { result -> result.detail }
    }

    private fun List<RouteDetail>.withRouteWaypoints(
        routeWaypoints: List<RouteWaypoint>,
    ): List<RouteDetail> {
        if (routeWaypoints.isEmpty()) return this
        val immutableRouteWaypoints = routeWaypoints.toImmutableList()
        return map { route -> route.copy(routeWaypoints = immutableRouteWaypoints) }
    }

    private fun RouteWaypoint.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    private fun nextSearchGeneration(): Long {
        return synchronized(searchGenerationLock) {
            latestSearchGeneration += SEARCH_GENERATION_INCREMENT
            latestSearchGeneration
        }
    }

    private fun isLatestSearchGeneration(searchGeneration: Long): Boolean {
        return synchronized(searchGenerationLock) {
            latestSearchGeneration == searchGeneration
        }
    }

    private companion object {
        /** 最初の探索世代。 */
        const val INITIAL_SEARCH_GENERATION = 0L

        /** 探索世代の加算値。 */
        const val SEARCH_GENERATION_INCREMENT = 1L

        /** ログ出力用タグ。 */
        const val TAG = "NewRouteManager"
    }
}
