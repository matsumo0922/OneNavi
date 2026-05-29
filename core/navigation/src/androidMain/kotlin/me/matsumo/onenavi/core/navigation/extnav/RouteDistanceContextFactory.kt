package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.drive.supporter.api.core.model.Coord as ExtNavCoord

/**
 * payload と中立 route から [ExtNavRouteDistanceContext] を構築する factory。
 *
 * source→geometry 変換の基準そろえ ([RouteDistanceMapper] の anchor 構築) をここに集約し、
 * semantic 射影 ([GuidanceRouteMapper]) と音声プランの双方が同じ手順で context を得られる
 * ようにする。同じ payload / route / 累積距離を渡せば常に同じ context を返す (決定的)。
 */
internal class RouteDistanceContextFactory {

    /**
     * 外部 API の source 距離基準を OneNavi geometry 距離基準へ変換する context を作る。
     *
     * source 総距離は summary 距離 → route 詳細距離 → geometry 積算距離の優先順で決める。
     * API polyline が geometry の一部区間として入っている場合は、その区間を source 距離の
     * 始端 / 終端に対応させる。
     *
     * @param payload summary / polyline を含む payload
     * @param route geometry と総距離を持つ中立 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param totalGeometryMetres geometry 上の総距離
     * @return source→geometry 距離変換 context
     */
    fun create(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        totalGeometryMetres: Double,
    ): ExtNavRouteDistanceContext {
        val summaryMetres = payload.routeGuidance.summary.distanceMetres
            .toDouble()
            .takeIf { metres -> metres > 0.0 }
        val routeMetres = route.distanceMeters.takeIf { metres -> metres > 0.0 }
        val sourceTotalMetres = summaryMetres ?: routeMetres ?: totalGeometryMetres
        val sourceGeometryRange = sourceGeometryDistanceRange(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
        )
        val sourceGeometryStartMetres = sourceGeometryRange?.first ?: 0.0
        val sourceGeometryEndMetres = sourceGeometryRange?.second ?: totalGeometryMetres

        val distanceMapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = sourceGeometryStartMetres),
                DistanceAnchor(sourceMetres = sourceTotalMetres, geometryMetres = sourceGeometryEndMetres),
            ),
        )

        return ExtNavRouteDistanceContext(
            distanceMapper = distanceMapper,
            totalGeometryMetres = totalGeometryMetres,
        )
    }

    /**
     * API source 距離が対応する route geometry 上の区間を返す。
     *
     * [ExtNavRouteDataSource] は地図表示用に origin / destination を geometry の前後へ足すことが
     * ある。一方、GUIDE の source 距離は API polyline の距離系なので、0m を geometry 全体の
     * 0m に対応させると案内点が手前へずれる。API polyline の両端が geometry 内で占める距離を
     * 使い、距離変換の基準をそろえる。
     */
    private fun sourceGeometryDistanceRange(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): Pair<Double, Double>? {
        val sourcePolyline = payload.routeGuidance.polyline
        if (sourcePolyline.isEmpty()) return null

        if (route.geometry.isEmpty() || cumulativeMetres.isEmpty()) return null

        val sourceStart = sourcePolyline.first().toRoutePoint()
        val sourceEnd = sourcePolyline.last().toRoutePoint()
        val sourceStartIndex = route.geometry.indexOfFirst { point -> point == sourceStart }
        val sourceEndIndex = route.geometry.indexOfLast { point -> point == sourceEnd }

        if (sourceStartIndex < 0 || sourceEndIndex < 0 || sourceEndIndex <= sourceStartIndex) return null

        val sourceStartMetres = cumulativeMetres.valueAtOrNull(sourceStartIndex) ?: return null
        val sourceEndMetres = cumulativeMetres.valueAtOrNull(sourceEndIndex) ?: return null

        if (sourceEndMetres <= sourceStartMetres) return null

        return sourceStartMetres to sourceEndMetres
    }

    /** index 範囲内の累積距離を返す。範囲外なら null。 */
    private fun DoubleArray.valueAtOrNull(index: Int): Double? =
        if (index in indices) this[index] else null

    /** 外部 API 座標を route geometry の座標型へ変換する。 */
    private fun ExtNavCoord.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latDegrees,
        longitude = lonDegrees,
    )
}
