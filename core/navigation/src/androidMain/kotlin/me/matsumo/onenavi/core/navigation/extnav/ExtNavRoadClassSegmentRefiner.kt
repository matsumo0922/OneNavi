package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 座標道路種別 API の結果で、短距離 route の道路種別セグメントを補正する helper。
 */
internal object ExtNavRoadClassSegmentRefiner {

    /** 座標道路種別による短距離 route 補正を行う最大距離。 */
    private const val SHORT_ROUTE_ROAD_TYPE_MAX_METRES = 500.0

    /** route として扱える最小 geometry 点数。 */
    private const val MIN_GEOMETRY_POINT_COUNT = 2

    /**
     * 短距離 route を座標道路種別で補正する対象かを返す。
     */
    fun shouldRefineShortRoute(
        routeDistanceMeters: Double,
        geometry: ImmutableList<RoutePoint>,
        roadClassSegments: ImmutableList<RoadClassSegment>,
    ): Boolean {
        if (routeDistanceMeters > SHORT_ROUTE_ROAD_TYPE_MAX_METRES) return false
        if (geometry.size < MIN_GEOMETRY_POINT_COUNT) return false
        if (roadClassSegments.isEmpty()) return true

        // 短距離 route で高速道路扱いに寄りすぎるケースだけを保守的に補正する。
        return roadClassSegments.any { segment -> segment.roadClass == RoadClass.HIGHWAY }
    }

    /**
     * 短距離 route から道路種別確認用の代表点を選ぶ。
     */
    fun samplePoints(geometry: ImmutableList<RoutePoint>): List<RoutePoint> {
        if (geometry.isEmpty()) return emptyList()

        val middleIndex = geometry.lastIndex / 2
        return listOf(
            geometry.first(),
            geometry[middleIndex],
            geometry.last(),
        ).distinct()
    }

    /**
     * API の代表点判定が全て一致する場合だけ、route 全体の道路種別を補正する。
     */
    fun refineShortRoute(
        routeDistanceMeters: Double,
        geometry: ImmutableList<RoutePoint>,
        roadClassSegments: ImmutableList<RoadClassSegment>,
        roadClassSamples: List<RoadClass>,
    ): ImmutableList<RoadClassSegment> {
        if (!shouldRefineShortRoute(routeDistanceMeters, geometry, roadClassSegments)) {
            return roadClassSegments
        }

        val agreedRoadClass = roadClassSamples.unanimousRoadClass() ?: return roadClassSegments
        val existingRoadClasses = roadClassSegments
            .map { segment -> segment.roadClass }
            .distinct()
        val alreadyMatches = existingRoadClasses.size == 1 && existingRoadClasses.first() == agreedRoadClass
        if (alreadyMatches) return roadClassSegments

        return persistentListOf(
            RoadClassSegment(
                startPointIndex = 0,
                endPointIndex = geometry.lastIndex,
                roadClass = agreedRoadClass,
            ),
        )
    }

    private fun List<RoadClass>.unanimousRoadClass(): RoadClass? {
        val firstRoadClass = firstOrNull() ?: return null
        return if (all { roadClass -> roadClass == firstRoadClass }) firstRoadClass else null
    }
}
