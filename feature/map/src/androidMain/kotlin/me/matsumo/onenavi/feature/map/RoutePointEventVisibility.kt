package me.matsumo.onenavi.feature.map

import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePointEvent

/**
 * ルート上の地点イベント marker を案内中の表示範囲へ切り出す。
 */
internal object RoutePointEventVisibility {

    /**
     * 地点イベント marker の描画対象を route 進行距離と次の案内地点距離に基づいて切り出す。
     *
     * @param route 描画対象 route
     * @param routeProgressMeters route 上の現在地累積距離。取得できない場合は null
     * @param guidanceTargetDistanceFromStartMeters 現在の案内地点の route 始点からの累積距離。取得できない場合は null
     * @return 現在地以降、案内地点距離が有効な場合はその地点までに制限した地点イベント
     */
    fun visiblePointEvents(
        route: RouteDetail,
        routeProgressMeters: Double?,
        guidanceTargetDistanceFromStartMeters: Double?,
    ): List<RoutePointEvent> {
        val minimumDistanceFromStartMeters = routeProgressMeters?.coerceAtLeast(0.0)
        val maximumDistanceFromStartMeters = pointEventTargetDistanceLimit(
            guidanceTargetDistanceFromStartMeters = guidanceTargetDistanceFromStartMeters,
            minimumDistanceFromStartMeters = minimumDistanceFromStartMeters,
        )

        if (minimumDistanceFromStartMeters == null) {
            return route.pointEvents
                .asSequence()
                .filterWithinTargetDistance(maximumDistanceFromStartMeters)
                .take(ROUTE_POINT_EVENT_MARKER_MAX_COUNT)
                .toList()
        }

        return route.pointEvents
            .asSequence()
            .filter { pointEvent -> pointEvent.distanceFromStartMeters >= minimumDistanceFromStartMeters }
            .filterWithinTargetDistance(maximumDistanceFromStartMeters)
            .take(ROUTE_POINT_EVENT_MARKER_MAX_COUNT)
            .toList()
    }

    private fun pointEventTargetDistanceLimit(
        guidanceTargetDistanceFromStartMeters: Double?,
        minimumDistanceFromStartMeters: Double?,
    ): Double? {
        val targetDistanceFromStartMeters = guidanceTargetDistanceFromStartMeters ?: return null
        val targetDistanceLimit = targetDistanceFromStartMeters + ROUTE_POINT_EVENT_TARGET_DISTANCE_TOLERANCE_METERS
        val canApplyLimit = minimumDistanceFromStartMeters == null || targetDistanceLimit >= minimumDistanceFromStartMeters

        return targetDistanceLimit.takeIf { canApplyLimit }
    }

    private fun Sequence<RoutePointEvent>.filterWithinTargetDistance(
        maximumDistanceFromStartMeters: Double?,
    ): Sequence<RoutePointEvent> {
        return if (maximumDistanceFromStartMeters == null) {
            this
        } else {
            filter { pointEvent -> pointEvent.distanceFromStartMeters <= maximumDistanceFromStartMeters }
        }
    }
}

/** 1 route で同時に描画する地点イベント marker の最大件数。 */
private const val ROUTE_POINT_EVENT_MARKER_MAX_COUNT = 120

/** 案内地点までを表示対象に含めるための距離許容値 (m)。 */
private const val ROUTE_POINT_EVENT_TARGET_DISTANCE_TOLERANCE_METERS = 1.0
