package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityHint
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverHint
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.SpeedLimit
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListDetail
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListIcon
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [ExtNavGuidanceTracker] の案内地点分類テスト。
 */
class ExtNavGuidanceTrackerTest {

    @Test
    fun `通過施設は nextManeuver ではなく listItems に入る`() {
        val tracker = ExtNavGuidanceTracker()
        val route = buildRoute()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(),
            ),
            route = route,
        )

        tracker.onLocation(locationAt(route.origin))

        val presentation = tracker.snapshot.value!!.presentation
        assertEquals(2, presentation.nextManeuver?.guidancePointIndex)
        assertEquals(3, presentation.followupManeuver?.guidancePointIndex)
        assertEquals(3, presentation.listItems.size)

        val maneuverItem = presentation.listItems[0]
        assertIs<GuidanceListIcon.Maneuver>(maneuverItem.icon)
        assertEquals(presentation.nextManeuver?.guidancePointIndex, maneuverItem.id.removePrefix("maneuver-").toInt())

        val junctionItem = presentation.listItems[1]
        val junctionBadge = assertIs<GuidanceListIcon.FacilityBadge>(junctionItem.icon)
        assertEquals(FacilityKind.JCT, junctionBadge.kind)

        val tollGateItem = presentation.listItems[2]
        val tollGateBadge = assertIs<GuidanceListIcon.FacilityBadge>(tollGateItem.icon)
        assertEquals(FacilityKind.TOLL_GATE, tollGateBadge.kind)
        assertEquals(GuidanceListDetail.Toll(amountYen = 320), tollGateItem.detail)
    }

    @Test
    fun `次の主案内地点の制限速度を現在区間の制限速度として返す`() {
        val tracker = ExtNavGuidanceTracker()
        val route = buildRoute()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(turnSpeedLimitKmh = 80),
            ),
            route = route,
        )

        tracker.onLocation(locationAt(route.origin))

        assertEquals(80, tracker.snapshot.value?.progress?.currentSpeedLimitKmh)
    }

    private fun buildRoute(): RouteDetail {
        val origin = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE)
        val tollGate = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.002)
        val junction = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.004)
        val turn = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.006)
        val destination = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.01)
        return RouteDetail(
            id = "route-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = listOf(origin, tollGate, junction, turn, destination).toImmutableList(),
            distanceMeters = 1_000.0,
            durationSeconds = 300.0,
            steps = persistentListOf(),
            tollFee = 320,
        )
    }

    private fun buildRouteGuidance(
        turnSpeedLimitKmh: Int? = null,
    ): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = listOf(
            buildGuidancePoint(
                index = 0,
                distanceFromStartMetres = 200,
                category = GuidanceCategory.IntersectionGuide,
                facilityKind = GuidanceFacilityKind.TOLL_GATE,
                direction = ManeuverDirection.Straight,
            ),
            buildGuidancePoint(
                index = 1,
                distanceFromStartMetres = 400,
                category = GuidanceCategory.TunnelBranch,
                facilityKind = GuidanceFacilityKind.INTERCHANGE,
                direction = ManeuverDirection.Straight,
            ),
            buildGuidancePoint(
                index = 2,
                distanceFromStartMetres = 600,
                category = GuidanceCategory.TunnelBranch,
                facilityKind = null,
                direction = ManeuverDirection.SlantRight,
                speedLimitKmh = turnSpeedLimitKmh,
            ),
            buildGuidancePoint(
                index = 3,
                distanceFromStartMetres = 950,
                category = GuidanceCategory.Unspecified,
                facilityKind = null,
                direction = ManeuverDirection.Straight,
            ),
        ).toImmutableList(),
        intersections = listOf(
            buildIntersection(
                name = "テスト料金所",
                distanceRatio = 0.2,
                facilityKind = GuidanceFacilityKind.TOLL_GATE,
            ),
            buildIntersection(
                name = "通過JCT",
                distanceRatio = 0.4,
                facilityKind = GuidanceFacilityKind.INTERCHANGE,
            ),
            buildIntersection(
                name = "分岐JCT",
                distanceRatio = 0.6,
                facilityKind = null,
            ),
        ).toImmutableList(),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun buildGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        category: GuidanceCategory,
        facilityKind: GuidanceFacilityKind?,
        direction: ManeuverDirection,
        speedLimitKmh: Int? = null,
    ): GuidancePoint = GuidancePoint(
        index = index,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = distanceFromStartMetres,
        phrases = persistentListOf(
            SsmlPhrase(
                ssml = category.name,
                distanceMetres = 0,
                category = category,
            ),
        ),
        announcementBlocks = persistentListOf(),
        imageRefs = persistentListOf(),
        maneuver = buildManeuverHint(
            facilityKind = facilityKind,
            direction = direction,
            speedLimitKmh = speedLimitKmh,
        ),
    )

    private fun buildManeuverHint(
        facilityKind: GuidanceFacilityKind?,
        direction: ManeuverDirection,
        speedLimitKmh: Int?,
    ): ManeuverHint = ManeuverHint(
        angleIn = 0,
        angleOut = 0,
        direction = direction,
        laneInfo = null,
        specialNode = null,
        speedLimit = speedLimitKmh?.let { limit ->
            SpeedLimit(
                kind = 0,
                value = limit,
                delta = 0,
                limit = limit,
            )
        },
        flagsGroup = persistentListOf(),
        mergeSide = null,
        facilityHint = facilityKind?.let { kind -> GuidanceFacilityHint(kind = kind) },
    )

    private fun buildIntersection(
        name: String,
        distanceRatio: Double,
        facilityKind: GuidanceFacilityKind?,
    ): Intersection = Intersection(
        id = 0,
        name = name,
        nameRuby = "",
        roadName = "",
        roadNameOfficial = "",
        roadNumberSign = "",
        directionSignA = "",
        directionSignAKana = "",
        directionSignB = "",
        directionSignBKana = "",
        position = Coord.fromDegrees(
            latDeg = ORIGIN_LATITUDE,
            lonDeg = ORIGIN_LONGITUDE + 0.01 * distanceRatio,
        ),
        angleIn = 0,
        angleOut = 0,
        direction = ManeuverDirection.Straight,
        imageRefs = persistentListOf(),
        facilityHint = facilityKind?.let { kind -> GuidanceFacilityHint(kind = kind) },
    )

    private fun locationAt(point: RoutePoint): UserLocation = UserLocation(
        latitude = point.latitude,
        longitude = point.longitude,
        bearingDegrees = null,
        speedMps = 10f,
        accuracyMeters = 3f,
        timestampMillis = 1_000L,
        elapsedRealtimeNanos = 1_000_000L,
    )

    /** テスト用の基準座標。 */
    private companion object {
        private const val ORIGIN_LATITUDE: Double = 35.0
        private const val ORIGIN_LONGITUDE: Double = 139.0
    }
}
