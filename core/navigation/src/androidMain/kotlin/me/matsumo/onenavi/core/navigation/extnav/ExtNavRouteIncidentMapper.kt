package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.model.RouteIncidentMarker
import me.matsumo.onenavi.core.model.RouteIncidentMarkerCategory
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.drive.supporter.api.guidance.domain.RouteIncident as ExtNavRouteIncident
import me.matsumo.drive.supporter.api.guidance.domain.RouteIncidentCategory as ExtNavRouteIncidentCategory

/**
 * 外部ナビ API ライブラリのルートインシデントを OneNavi の中立モデルへ詰め替える mapper。
 */
internal object ExtNavRouteIncidentMapper {

    /**
     * [RouteGuidance.routeIncidents] を [RouteIncidentMarker] に変換する。
     *
     * 外部ナビ API ライブラリの index / 距離は [RouteGuidance.polyline] 基準で、OneNavi の
     * [geometry] は出発地を先頭に追加している場合があるため、marker 描画や周辺 UI で
     * 使えるよう geometry 基準へ補正する。
     */
    fun map(routeGuidance: RouteGuidance, geometry: ImmutableList<RoutePoint>): ImmutableList<RouteIncidentMarker> {
        if (routeGuidance.routeIncidents.isEmpty() || geometry.isEmpty()) return persistentListOf()

        val indexOffset = if (isOriginPrepended(routeGuidance.polyline, geometry)) 1 else 0
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val distanceOffsetMeters = routeGuidance.polyline.geometryStartMetres(geometry, cumulativeMetres)

        return routeGuidance.routeIncidents
            .map { incident ->
                incident.toModel(
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

    private fun ExtNavRouteIncident.toModel(
        indexOffset: Int,
        lastGeometryIndex: Int,
        distanceOffsetMeters: Double,
        totalGeometryMetres: Double,
    ): RouteIncidentMarker {
        val geometryDistanceFromStartMeters = (distanceFromStartMetres.toDouble() + distanceOffsetMeters)
            .coerceIn(0.0, totalGeometryMetres)

        return RouteIncidentMarker(
            category = category.toModel(),
            coord = RoutePoint(
                latitude = coord.latDegrees,
                longitude = coord.lonDegrees,
            ),
            displayText = displayText,
            distanceFromStartMeters = geometryDistanceFromStartMeters,
            polylinePointIndex = (polylinePointIndex + indexOffset).coerceIn(0, lastGeometryIndex),
            placeName = placeName?.ifBlank { null },
            roadNumbering = roadNumbering?.ifBlank { null },
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

    private fun ExtNavRouteIncidentCategory.toModel(): RouteIncidentMarkerCategory = when (this) {
        ExtNavRouteIncidentCategory.Accident -> RouteIncidentMarkerCategory.Accident
        ExtNavRouteIncidentCategory.Regulation -> RouteIncidentMarkerCategory.Regulation
    }
}
