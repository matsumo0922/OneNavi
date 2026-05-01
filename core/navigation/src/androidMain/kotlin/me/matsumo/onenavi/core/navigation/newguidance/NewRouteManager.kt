package me.matsumo.onenavi.core.navigation.newguidance

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.RouteRepository

/**
 * Preview 期のルート探索と候補管理を行うマネージャ。
 *
 * spec/24 §3.3 に対応。外部ナビ API ライブラリから候補ルートを取得し、各候補を
 * [ExtNavRouteRefiner] で refine して Routes API 上の polyline / route_token に変換する。
 * 結果は [state] (StateFlow<[RoutePreviewState]>) で公開する。
 *
 * 並走方式 (spec/24 §0.1 B 案) のため、既存の `:core:navigation` クラス (RouteManager
 * など) には触れない。Guidance 開始時に [RoutePreviewState.Ready.selectedRoute] を取り出して
 * [NewGuidanceManager] に渡す想定。
 *
 * @param routeRepository [me.matsumo.onenavi.core.datasource.RouteDataSource] のラッパ。
 *                        既存の [me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDataSource]
 *                        を再利用する (spec/24 §15 残課題: alternatives は現状 1 本固定)
 * @param extNavRouteRefiner spec/23 の Routes API + polyline FPS を実行する純粋関数群
 */
class NewRouteManager(
    private val routeRepository: RouteRepository,
    private val extNavRouteRefiner: ExtNavRouteRefiner,
) {
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    /**
     * 指定 waypoint 列のルート候補を探索し、refine 完了まで待ってから Ready を発行する。
     *
     * [waypoints] の先頭が origin、末尾が destination、間の要素は intermediate 経由地として
     * 扱う。intermediate は外部ナビ API ライブラリへの問い合わせと spec/23 §8.5.2
     * `forcedWaypoints` 双方に渡され、必ず通る経路として refine される。
     *
     * spec/24 §4.4 に従い、全 alternatives × 全 chunk の Routes API 呼び出しが完了する
     * まで Searching 状態を維持し、UI は何も出さない。途中失敗で Failed に遷移する。
     */
    suspend fun searchRoutes(waypoints: List<RouteWaypoint>) {
        require(waypoints.size >= 2) {
            "waypoints must contain at least origin and destination (size=${waypoints.size})"
        }

        _state.value = RoutePreviewState.Searching

        val origin = waypoints.first().toRoutePoint()
        val destination = waypoints.last().toRoutePoint()
        val intermediates = waypoints.subList(1, waypoints.lastIndex).map { it.toRoutePoint() }

        val refinedRoutes = runCatching {
            val results = routeRepository.searchRoutes(
                originLatitude = origin.latitude,
                originLongitude = origin.longitude,
                destinationLatitude = destination.latitude,
                destinationLongitude = destination.longitude,
                intermediateWaypoints = intermediates.map { it.latitude to it.longitude },
            ).getOrThrow()

            require(results.isNotEmpty()) { "No route candidates returned" }

            results.map { result ->
                extNavRouteRefiner.refine(
                    extPolyline = result.item.geometry,
                    origin = origin,
                    destination = destination,
                    intermediates = intermediates,
                )
            }
        }

        refinedRoutes
            .onSuccess { refined ->
                Napier.i(tag = TAG) { "searchRoutes ready: routes=${refined.size} totalChunks=${refined.sumOf { it.chunks.size }}" }
                _state.value = RoutePreviewState.Ready(
                    routes = refined.toImmutableList(),
                    selectedIndex = 0,
                )
            }
            .onFailure { error ->
                Napier.w(tag = TAG, throwable = error) { "searchRoutes failed" }
                _state.value = RoutePreviewState.Failed(error)
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

    private fun RouteWaypoint.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    private companion object {
        const val TAG = "NewRouteManager"
    }
}
