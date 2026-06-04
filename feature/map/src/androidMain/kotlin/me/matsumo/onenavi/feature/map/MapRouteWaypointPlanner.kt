package me.matsumo.onenavi.feature.map

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.feature.map.state.MapGeodesy

/** ルートプレビューで保持できる最大 waypoint 数。 */
internal const val MAP_ROUTE_PREVIEW_MAX_WAYPOINTS = 5

/** ルート waypoint 列へ地点を追加するための計算を行う。 */
internal object MapRouteWaypointPlanner {

    fun addWaypointToRoutePreview(
        waypoints: ImmutableList<RouteWaypoint>,
        waypoint: RouteWaypoint.Place,
    ): ImmutableList<RouteWaypoint>? {
        if (waypoints.size >= MAP_ROUTE_PREVIEW_MAX_WAYPOINTS) return null

        require(waypoints.size >= 2) {
            "waypoints must contain at least origin and destination (size=${waypoints.size})"
        }

        val originWaypoint = waypoints.first()
        val destinationWaypoint = waypoints.last()
        val intermediateWaypoints = waypoints.drop(1).dropLast(1)
        val sortedIntermediateWaypoints = insertWaypointByMinimalDetour(
            intermediateWaypoints = intermediateWaypoints,
            waypoint = waypoint,
            origin = originWaypoint.toRoutePoint(),
            destination = destinationWaypoint.toRoutePoint(),
        )

        return buildList {
            add(originWaypoint)
            addAll(sortedIntermediateWaypoints)
            add(destinationWaypoint)
        }.toImmutableList()
    }

    fun insertIntermediateWaypointByMinimalDetour(
        intermediateWaypoints: List<RouteWaypoint.Place>,
        waypoint: RouteWaypoint.Place,
        origin: RoutePoint,
        destination: RoutePoint,
    ): ImmutableList<RouteWaypoint.Place> {
        return insertWaypointByMinimalDetour(
            intermediateWaypoints = intermediateWaypoints,
            waypoint = waypoint,
            origin = origin,
            destination = destination,
        )
    }

    private fun <Waypoint : RouteWaypoint> insertWaypointByMinimalDetour(
        intermediateWaypoints: List<Waypoint>,
        waypoint: Waypoint,
        origin: RoutePoint,
        destination: RoutePoint,
    ): ImmutableList<Waypoint> {
        val routePoints = buildList {
            add(origin)
            addAll(intermediateWaypoints.map { routeWaypoint -> routeWaypoint.toRoutePoint() })
            add(destination)
        }
        var bestInsertIndex = intermediateWaypoints.size
        var bestAddedDistanceMeters = Double.POSITIVE_INFINITY

        for (segmentIndex in 0 until routePoints.lastIndex) {
            val fromPoint = routePoints[segmentIndex]
            val toPoint = routePoints[segmentIndex + 1]
            val addedDistanceMeters = calculateDetourDistanceMeters(
                fromPoint = fromPoint,
                waypoint = waypoint.toRoutePoint(),
                toPoint = toPoint,
            )

            if (addedDistanceMeters < bestAddedDistanceMeters) {
                bestInsertIndex = segmentIndex
                bestAddedDistanceMeters = addedDistanceMeters
            }
        }

        val sortedWaypoints = intermediateWaypoints.toMutableList()
        sortedWaypoints.add(bestInsertIndex, waypoint)
        return sortedWaypoints.toImmutableList()
    }

    private fun calculateDetourDistanceMeters(
        fromPoint: RoutePoint,
        waypoint: RoutePoint,
        toPoint: RoutePoint,
    ): Double {
        val viaWaypointDistanceMeters = MapGeodesy.haversineMeters(fromPoint, waypoint) +
            MapGeodesy.haversineMeters(waypoint, toPoint)
        val directDistanceMeters = MapGeodesy.haversineMeters(fromPoint, toPoint)

        return viaWaypointDistanceMeters - directDistanceMeters
    }
}

internal fun RouteWaypoint.toRoutePoint(): RoutePoint = RoutePoint(
    latitude = latitude,
    longitude = longitude,
)
