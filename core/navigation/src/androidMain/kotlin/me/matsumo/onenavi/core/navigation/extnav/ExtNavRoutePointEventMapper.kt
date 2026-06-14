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
     * 外部ナビ API ライブラリの index は [RouteGuidance.polyline] 基準で、OneNavi の [geometry] は
     * 出発地を先頭に追加している場合があるため、marker 描画や周辺 UI で使えるよう geometry
     * 基準の index に補正する。
     */
    fun map(routeGuidance: RouteGuidance, geometry: ImmutableList<RoutePoint>): ImmutableList<RoutePointEvent> {
        if (routeGuidance.pointEvents.isEmpty() || geometry.isEmpty()) return persistentListOf()

        val indexOffset = if (isOriginPrepended(routeGuidance.polyline, geometry)) 1 else 0

        return routeGuidance.pointEvents
            .map { event -> event.toModel(indexOffset, geometry.lastIndex) }
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

    private fun ExtNavRoutePointEvent.toModel(indexOffset: Int, lastGeometryIndex: Int): RoutePointEvent {
        return RoutePointEvent(
            kind = kind.toModel(),
            location = RoutePoint(
                latitude = coord.latDegrees,
                longitude = coord.lonDegrees,
            ),
            distanceFromStartMeters = distanceFromStartMetres,
            polylinePointIndex = (polylinePointIndex + indexOffset).coerceIn(0, lastGeometryIndex),
        )
    }

    private fun ExtNavRoutePointEventKind.toModel(): RoutePointEventKind = when (this) {
        ExtNavRoutePointEventKind.TrafficLight -> RoutePointEventKind.TRAFFIC_LIGHT
        ExtNavRoutePointEventKind.StopLine -> RoutePointEventKind.STOP_LINE
        ExtNavRoutePointEventKind.RailwayCrossing -> RoutePointEventKind.RAILWAY_CROSSING
    }
}
