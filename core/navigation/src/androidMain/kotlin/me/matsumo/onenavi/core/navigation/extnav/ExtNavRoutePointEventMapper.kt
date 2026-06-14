package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RoutePointEvent
import me.matsumo.onenavi.core.model.RoutePointEventKind
import me.matsumo.drive.supporter.api.guidance.domain.RoutePointEvent as ExtNavRoutePointEvent
import me.matsumo.drive.supporter.api.guidance.domain.RoutePointEventKind as ExtNavRoutePointEventKind

/**
 * 外部ナビ API ライブラリの地点イベントを OneNavi の中立モデルへ詰め替える mapper。
 */
internal object ExtNavRoutePointEventMapper {

    /**
     * [RouteGuidance.pointEvents] を [RoutePointEvent] に変換する。
     *
     * 外部ナビ API ライブラリの index / 距離は [RouteGuidance.polyline] 基準で、OneNavi の
     * [geometry] は出発地を先頭に追加している場合があるため、marker 描画や周辺 UI で
     * 使えるよう geometry 基準へ補正する。
     */
    fun map(routeGuidance: RouteGuidance, geometry: ImmutableList<RoutePoint>): ImmutableList<RoutePointEvent> {
        if (routeGuidance.pointEvents.isEmpty() || geometry.isEmpty()) return persistentListOf()

        val indexOffset = if (isOriginPrepended(routeGuidance.polyline, geometry)) 1 else 0
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val distanceOffsetMeters = routeGuidance.polyline.geometryStartMetres(geometry, cumulativeMetres)

        return routeGuidance.pointEvents
            .map { event ->
                event.toModel(
                    indexOffset = indexOffset,
                    lastGeometryIndex = geometry.lastIndex,
                    distanceOffsetMeters = distanceOffsetMeters,
                    totalGeometryMetres = totalGeometryMetres,
                )
            }
            .toImmutableList()
    }

    private fun isOriginPrepended(polyline: ImmutableList<Coord>, geometry: ImmutableList<RoutePoint>): Boolean {
        if (polyline.isEmpty() || geometry.isEmpty()) return false

        val firstPolylinePoint = polyline.first()
        val firstGeometryPoint = geometry.first()
        val latitudeDiffers = firstGeometryPoint.latitude != firstPolylinePoint.latDegrees
        val longitudeDiffers = firstGeometryPoint.longitude != firstPolylinePoint.lonDegrees

        return latitudeDiffers || longitudeDiffers
    }

    private fun ExtNavRoutePointEvent.toModel(
        indexOffset: Int,
        lastGeometryIndex: Int,
        distanceOffsetMeters: Double,
        totalGeometryMetres: Double,
    ): RoutePointEvent {
        val geometryDistanceFromStartMeters = (distanceFromStartMetres + distanceOffsetMeters)
            .coerceIn(0.0, totalGeometryMetres)

        return RoutePointEvent(
            kind = kind.toModel(),
            location = RoutePoint(
                latitude = coord.latDegrees,
                longitude = coord.lonDegrees,
            ),
            distanceFromStartMeters = geometryDistanceFromStartMeters,
            polylinePointIndex = (polylinePointIndex + indexOffset).coerceIn(0, lastGeometryIndex),
        )
    }

    private fun ImmutableList<Coord>.geometryStartMetres(
        geometry: ImmutableList<RoutePoint>,
        cumulativeMetres: DoubleArray,
    ): Double {
        if (isEmpty() || cumulativeMetres.isEmpty()) return 0.0

        val firstPolylinePoint = first()
        val geometryIndex = geometry.indexOfFirst { point ->
            point.latitude == firstPolylinePoint.latDegrees && point.longitude == firstPolylinePoint.lonDegrees
        }

        return cumulativeMetres.getOrNull(geometryIndex) ?: 0.0
    }

    private fun ExtNavRoutePointEventKind.toModel(): RoutePointEventKind = when (this) {
        ExtNavRoutePointEventKind.TrafficLight -> RoutePointEventKind.TRAFFIC_LIGHT
        ExtNavRoutePointEventKind.StopLine -> RoutePointEventKind.STOP_LINE
        ExtNavRoutePointEventKind.RailwayCrossing -> RoutePointEventKind.RAILWAY_CROSSING
    }
}
