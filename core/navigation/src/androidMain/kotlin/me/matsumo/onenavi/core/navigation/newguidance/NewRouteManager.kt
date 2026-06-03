package me.matsumo.onenavi.core.navigation.newguidance

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
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
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    /**
     * 指定 waypoint 列のルート候補を探索する。
     *
     * [waypoints] の先頭が origin、末尾が destination、間の要素は intermediate 経由地として扱う。
     * 探索中は [RoutePreviewState.Searching]、完了で [RoutePreviewState.Ready]、失敗で
     * [RoutePreviewState.Failed] に遷移する。
     */
    suspend fun searchRoutes(waypoints: List<RouteWaypoint>) {
        require(waypoints.size >= 2) {
            "waypoints must contain at least origin and destination (size=${waypoints.size})"
        }

        _state.value = RoutePreviewState.Searching

        val origin = waypoints.first().toRoutePoint()
        val destination = waypoints.last().toRoutePoint()
        val intermediates = waypoints.subList(1, waypoints.lastIndex).map { it.toRoutePoint() }

        runCatching {
            searchRouteDetails(
                origin = origin,
                destination = destination,
                intermediatePoints = intermediates,
                originDirectionDegrees = null,
            )
        }
            .onSuccess { routes ->
                Napier.i(tag = TAG) { "searchRoutes ready: routes=${routes.size}" }
                _state.value = RoutePreviewState.Ready(
                    routes = routes.toImmutableList(),
                    selectedIndex = 0,
                )
            }
            .onFailure { error ->
                Napier.w(tag = TAG, throwable = error) { "searchRoutes failed" }
                _state.value = RoutePreviewState.Failed(error)
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
        originDirectionDegrees: Int? = null,
    ): RoutePreviewState {
        return runCatching {
            searchRouteDetails(
                origin = origin,
                destination = destination,
                intermediatePoints = intermediatePoints,
                originDirectionDegrees = originDirectionDegrees,
            )
        }.fold(
            onSuccess = { routes ->
                RoutePreviewState.Ready(
                    routes = routes.toImmutableList(),
                    selectedIndex = 0,
                )
            },
            onFailure = { error ->
                RoutePreviewState.Failed(error)
            },
        )
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

    private fun RouteWaypoint.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    private companion object {
        const val TAG = "NewRouteManager"
    }
}
