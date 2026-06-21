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
import me.matsumo.drive.supporter.api.guidance.domain.SpeedLimitSegment
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListDetail
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListIcon
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `現在地を含む速度区間の制限速度を返す`() {
        val tracker = ExtNavGuidanceTracker()
        val route = buildRoute()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(
                    speedLimitSegments = listOf(
                        SpeedLimitSegment(
                            startDistanceFromRouteStartMetres = 0,
                            endDistanceFromRouteStartMetres = 500,
                            limitKmh = 80,
                        ),
                    ),
                ),
            ),
            route = route,
        )

        tracker.onLocation(locationAt(route.origin))

        assertEquals(80, tracker.snapshot.value?.progress?.currentSpeedLimitKmh)
    }

    @Test
    fun `速度区間外では制限速度を返さない`() {
        val tracker = ExtNavGuidanceTracker()
        val route = buildRoute()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(
                    speedLimitSegments = listOf(
                        SpeedLimitSegment(
                            startDistanceFromRouteStartMetres = 0,
                            endDistanceFromRouteStartMetres = 500,
                            limitKmh = 80,
                        ),
                    ),
                ),
            ),
            route = route,
        )

        tracker.onLocation(locationAt(route.destination))

        assertEquals(null, tracker.snapshot.value?.progress?.currentSpeedLimitKmh)
    }

    @Test
    fun `表示範囲外の制限速度は返さない`() {
        val tracker = ExtNavGuidanceTracker()
        val route = buildRoute()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(
                    speedLimitSegments = listOf(
                        SpeedLimitSegment(
                            startDistanceFromRouteStartMetres = 0,
                            endDistanceFromRouteStartMetres = 500,
                            limitKmh = 512,
                        ),
                    ),
                ),
            ),
            route = route,
        )

        tracker.onLocation(locationAt(route.origin))

        assertEquals(null, tracker.snapshot.value?.progress?.currentSpeedLimitKmh)
    }

    @Test
    fun `停止中の GPS 途絶では DR を進めない`() {
        val tracker = attachTracker()
        val route = buildRoute()
        tracker.onLocation(locationAt(point = route.origin, speedMps = 0f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
            nowWallClockMillis = ONE_SECOND_MILLIS,
        )

        assertFalse(didAdvance)
        assertEquals(VehiclePositionSource.OBSERVED, tracker.snapshot.value?.positionSource)
    }

    @Test
    fun `低速 GPS 途絶では DR を進めない`() {
        val tracker = attachTracker()
        val route = buildRoute()
        tracker.onLocation(locationAt(point = route.origin, speedMps = 2.0f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
            nowWallClockMillis = ONE_SECOND_MILLIS,
        )

        assertFalse(didAdvance)
        assertEquals(VehiclePositionSource.OBSERVED, tracker.snapshot.value?.positionSource)
    }

    @Test
    fun `速度なしの GPS 途絶では DR を進めない`() {
        val tracker = attachTracker()
        val route = buildRoute()
        tracker.onLocation(locationAt(point = route.origin, speedMps = null))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
            nowWallClockMillis = ONE_SECOND_MILLIS,
        )

        assertFalse(didAdvance)
        assertEquals(VehiclePositionSource.OBSERVED, tracker.snapshot.value?.positionSource)
    }

    @Test
    fun `GPS 途絶ではトンネル情報なしで保持速度の DR を進める`() {
        val tracker = attachTracker()
        val route = buildRoute()
        tracker.onLocation(locationAt(point = route.origin, speedMps = 3f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
            nowWallClockMillis = ONE_SECOND_MILLIS,
        )

        val snapshot = tracker.snapshot.value
        assertTrue(didAdvance)
        assertEquals(VehiclePositionSource.DEAD_RECKONING, snapshot?.positionSource)
        assertEquals(3.0, snapshot?.currentCumulativeMeters ?: 0.0, 0.2)
    }

    @Test
    fun `長距離 DR は 3000m で止めず route 上を進む`() {
        val route = buildLongRoute()
        val tracker = attachTracker(route = route)
        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 200L * ONE_SECOND_NANOS,
            nowWallClockMillis = 200L * ONE_SECOND_MILLIS,
        )

        val snapshot = tracker.snapshot.value
        assertTrue(didAdvance)
        assertEquals(VehiclePositionSource.DEAD_RECKONING, snapshot?.positionSource)
        assertTrue((snapshot?.currentCumulativeMeters ?: 0.0) > 5_900.0)
    }

    @Test
    fun `DR は route 末端を越えない`() {
        val route = buildRoute()
        val tracker = attachTracker(route = route)
        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 100L * ONE_SECOND_NANOS,
            nowWallClockMillis = 100L * ONE_SECOND_MILLIS,
        )

        val snapshot = tracker.snapshot.value
        assertTrue(didAdvance)
        val totalGeometryMeters = RouteGeometryMath.cumulativeMetres(route.geometry).last()
        assertEquals(totalGeometryMeters, snapshot?.currentCumulativeMeters ?: 0.0, 0.2)
        assertEquals(0.0, snapshot?.distanceRemainingMeters ?: -1.0, 0.2)
    }

    @Test
    fun `DR snapshot は観測値を持たない`() {
        val route = buildBranchLikeRoute()
        val tracker = attachTracker(route = route)
        tracker.onLocation(locationAt(point = route.origin, speedMps = 15f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 4L * ONE_SECOND_NANOS,
            nowWallClockMillis = 4L * ONE_SECOND_MILLIS,
        )

        val snapshot = tracker.snapshot.value
        assertTrue(didAdvance)
        assertEquals(VehiclePositionSource.DEAD_RECKONING, snapshot?.positionSource)
        assertNull(snapshot?.rawLocation)
        assertNull(snapshot?.projectionErrorMeters)
        assertNull(snapshot?.progress?.observedLocation)
    }

    @Test
    fun `OFF_ROUTE snapshot からは DR を開始しない`() {
        val tracker = attachTracker()
        val route = buildRoute()
        val farPoint = RoutePoint(
            latitude = ORIGIN_LATITUDE + 0.01,
            longitude = ORIGIN_LONGITUDE,
        )
        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))
        tracker.onLocation(locationAt(point = farPoint, speedMps = 30f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 2L * ONE_SECOND_NANOS,
            nowWallClockMillis = 2L * ONE_SECOND_MILLIS,
        )

        assertFalse(didAdvance)
        assertEquals(RouteMatchState.OFF_ROUTE_CANDIDATE, tracker.snapshot.value?.routeMatchState)
    }

    @Test
    fun `DR seed は usable observed snapshot を保持する`() {
        val tracker = attachTracker()
        val route = buildRoute()
        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))
        tracker.onLocation(
            location = locationAt(point = route.destination, speedMps = 0f),
            canSeedDeadReckoning = false,
        )

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
            nowWallClockMillis = ONE_SECOND_MILLIS,
        )

        val snapshot = tracker.snapshot.value
        assertTrue(didAdvance)
        assertEquals(VehiclePositionSource.DEAD_RECKONING, snapshot?.positionSource)
        assertEquals(30.0, snapshot?.currentCumulativeMeters ?: 0.0, 0.2)
    }

    @Test
    fun `長距離 DR 後の実測復帰は最後の実測 projection から再探索する`() {
        val route = buildDenseLongRoute()
        val tracker = attachTracker(route = route)
        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))

        val didAdvance = tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 200L * ONE_SECOND_NANOS,
            nowWallClockMillis = 200L * ONE_SECOND_MILLIS,
        )
        assertTrue(didAdvance)
        assertTrue((tracker.snapshot.value?.currentCumulativeMeters ?: 0.0) > 5_900.0)

        tracker.onLocation(locationAt(point = route.origin, speedMps = 30f))

        val snapshot = tracker.snapshot.value
        assertEquals(VehiclePositionSource.OBSERVED, snapshot?.positionSource)
        assertEquals(0.0, snapshot?.currentCumulativeMeters ?: -1.0, 1.0)
        assertEquals(RouteMatchState.ON_ROUTE, snapshot?.routeMatchState)
    }

    private fun attachTracker(route: RouteDetail = buildRoute()): ExtNavGuidanceTracker {
        val tracker = ExtNavGuidanceTracker()
        tracker.attach(
            payload = ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(),
            ),
            route = route,
        )
        return tracker
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

    private fun buildLongRoute(): RouteDetail {
        val origin = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE)
        val destination = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.1)
        return RouteDetail(
            id = "route-long-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = listOf(origin, destination).toImmutableList(),
            distanceMeters = 10_000.0,
            durationSeconds = 600.0,
            steps = persistentListOf(),
            tollFee = 0,
        )
    }

    private fun buildDenseLongRoute(): RouteDetail {
        val geometry = (0..1_000).map { pointIndex ->
            RoutePoint(
                latitude = ORIGIN_LATITUDE,
                longitude = ORIGIN_LONGITUDE + 0.1 * pointIndex / 1_000.0,
            )
        }.toImmutableList()
        val origin = geometry.first()
        val destination = geometry.last()

        return RouteDetail(
            id = "route-dense-long-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = geometry,
            distanceMeters = 10_000.0,
            durationSeconds = 600.0,
            steps = persistentListOf(),
            tollFee = 0,
        )
    }

    private fun buildBranchLikeRoute(): RouteDetail {
        val origin = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE)
        val bend = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + 0.003)
        val branch = RoutePoint(latitude = ORIGIN_LATITUDE + 0.002, longitude = ORIGIN_LONGITUDE + 0.006)
        val destination = RoutePoint(latitude = ORIGIN_LATITUDE + 0.002, longitude = ORIGIN_LONGITUDE + 0.01)
        return RouteDetail(
            id = "route-branch-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = listOf(origin, bend, branch, destination).toImmutableList(),
            distanceMeters = 1_000.0,
            durationSeconds = 180.0,
            steps = persistentListOf(),
            tollFee = 0,
        )
    }

    private fun buildRouteGuidance(
        speedLimitSegments: List<SpeedLimitSegment> = emptyList(),
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
        speedLimitSegments = speedLimitSegments.toImmutableList(),
    )

    private fun buildGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        category: GuidanceCategory,
        facilityKind: GuidanceFacilityKind?,
        direction: ManeuverDirection,
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
        ),
    )

    private fun buildManeuverHint(
        facilityKind: GuidanceFacilityKind?,
        direction: ManeuverDirection,
    ): ManeuverHint = ManeuverHint(
        angleIn = 0,
        angleOut = 0,
        direction = direction,
        laneInfo = null,
        specialNode = null,
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

    private fun locationAt(
        point: RoutePoint,
        speedMps: Float? = 10f,
    ): UserLocation = UserLocation(
        latitude = point.latitude,
        longitude = point.longitude,
        bearingDegrees = null,
        speedMps = speedMps,
        accuracyMeters = 3f,
        timestampMillis = 1_000L,
        elapsedRealtimeNanos = 0L,
    )

    /** テスト用の基準座標。 */
    private companion object {
        private const val ORIGIN_LATITUDE: Double = 35.0
        private const val ORIGIN_LONGITUDE: Double = 139.0
        private const val ONE_SECOND_MILLIS: Long = 1_000L
        private const val ONE_SECOND_NANOS: Long = 1_000_000_000L
    }
}
