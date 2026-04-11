package me.matsumo.onenavi.core.navigation.guidance

import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement

/**
 * RouteProgress と直近の Mapbox instruction から案内生成に必要な文脈を構築するクラス。
 */
class GuidanceContextBuilder {

    private var previousRoadKind: RoadKind? = null

    fun build(
        routeProgress: RouteProgress,
        lastVoiceInstruction: VoiceInstructions?,
        lastBannerInstruction: BannerInstructions?,
        sessionState: GuidanceSessionState,
        waypointKinds: List<DestinationKind>,
        speechHistory: GuidanceSpeechHistory,
    ): GuidanceContext {
        val currentLegProgress = routeProgress.currentLegProgress
        val currentStepProgress = currentLegProgress?.currentStepProgress
        val currentStep = currentStepProgress?.step
        val currentRoadKind = currentStep?.roadKind() ?: RoadKind.GENERAL
        val oldRoadKind = previousRoadKind
        previousRoadKind = currentRoadKind

        val upcomingSteps = buildUpcomingSteps(routeProgress)

        return GuidanceContext(
            routeId = routeProgress.navigationRoute.id,
            routeProgress = routeProgress,
            currentLegIndex = currentLegProgress?.legIndex ?: 0,
            currentStepIndex = currentStepProgress?.stepIndex ?: 0,
            currentStep = currentStep,
            nextStep = currentLegProgress?.upcomingStep,
            upcomingSteps = upcomingSteps,
            upcomingIntersections = buildUpcomingIntersections(
                upcomingSteps = upcomingSteps,
                currentLegIndex = currentLegProgress?.legIndex ?: 0,
                currentStepIndex = currentStepProgress?.stepIndex ?: 0,
            ),
            currentRoadKind = currentRoadKind,
            previousRoadKind = oldRoadKind,
            lastVoiceInstruction = lastVoiceInstruction,
            lastBannerInstruction = lastBannerInstruction,
            sessionState = sessionState,
            waypointKinds = waypointKinds,
            speechHistory = speechHistory,
        )
    }

    fun reset() {
        previousRoadKind = null
    }

    private fun buildUpcomingSteps(routeProgress: RouteProgress): List<UpcomingStepContext> {
        val legs = routeProgress.navigationRoute.directionsRoute.legs().orEmpty()
        val currentLegIndex = routeProgress.currentLegProgress?.legIndex ?: 0
        val currentStepIndex = routeProgress.currentLegProgress?.currentStepProgress?.stepIndex ?: 0
        val currentStepDistanceRemaining = routeProgress.currentLegProgress
            ?.currentStepProgress
            ?.distanceRemaining
            ?.toDouble() ?: 0.0

        val result = mutableListOf<UpcomingStepContext>()
        var cumulativeDistance = currentStepDistanceRemaining

        for (legIndex in currentLegIndex until legs.size) {
            val steps = legs[legIndex].steps().orEmpty()
            val startStepIndex = if (legIndex == currentLegIndex) currentStepIndex else 0

            for (stepIndex in startStepIndex until steps.size) {
                val step = steps[stepIndex]
                val isCurrentStep = legIndex == currentLegIndex && stepIndex == currentStepIndex
                val distanceFromCurrent = if (isCurrentStep) {
                    currentStepDistanceRemaining
                } else {
                    cumulativeDistance
                }

                result.add(
                    UpcomingStepContext(
                        legIndex = legIndex,
                        stepIndex = stepIndex,
                        step = step,
                        distanceFromCurrentMeters = distanceFromCurrent,
                    ),
                )

                if (!isCurrentStep) {
                    cumulativeDistance += step.distance()
                }
            }
        }

        return result
    }

    private fun buildUpcomingIntersections(
        upcomingSteps: List<UpcomingStepContext>,
        currentLegIndex: Int,
        currentStepIndex: Int,
    ): List<UpcomingIntersectionContext> {
        return upcomingSteps
            .take(8)
            .flatMap { stepContext ->
                val intersections = stepContext.step.intersections().orEmpty()
                intersections.mapIndexedNotNull { intersectionIndex, intersection ->
                    val distanceFromCurrent = stepContext.distanceToIntersection(
                        intersection = intersection,
                        intersectionIndex = intersectionIndex,
                        intersectionCount = intersections.size,
                        currentLegIndex = currentLegIndex,
                        currentStepIndex = currentStepIndex,
                    )
                    if (distanceFromCurrent < 0.0) return@mapIndexedNotNull null

                    UpcomingIntersectionContext(
                        legIndex = stepContext.legIndex,
                        stepIndex = stepContext.stepIndex,
                        intersectionIndex = intersectionIndex,
                        geometryIndex = intersection.geometryIndex(),
                        distanceFromCurrentMeters = distanceFromCurrent,
                        intersection = intersection,
                    )
                }
            }
    }

    private fun LegStep.roadKind(): RoadKind {
        val intersections = intersections().orEmpty()
        return when {
            intersections.any { it.tollCollection() != null } -> RoadKind.TOLL
            intersections.any { intersection ->
                intersection.classes().orEmpty().any { roadClass -> roadClass in HIGHWAY_CLASSES }
            } -> RoadKind.HIGHWAY
            else -> RoadKind.GENERAL
        }
    }

    private fun UpcomingStepContext.distanceToIntersection(
        intersection: StepIntersection,
        intersectionIndex: Int,
        intersectionCount: Int,
        currentLegIndex: Int,
        currentStepIndex: Int,
    ): Double {
        val distanceFromStepStart = step.distanceFromStepStartMeters(
            intersection = intersection,
            intersectionIndex = intersectionIndex,
            intersectionCount = intersectionCount,
        )
        val isCurrentStep = legIndex == currentLegIndex && stepIndex == currentStepIndex

        return if (isCurrentStep) {
            val traveledInStep = step.distance() - distanceFromCurrentMeters
            distanceFromStepStart - traveledInStep
        } else {
            distanceFromCurrentMeters + distanceFromStepStart
        }
    }

    private fun LegStep.distanceFromStepStartMeters(
        intersection: StepIntersection,
        intersectionIndex: Int,
        intersectionCount: Int,
    ): Double {
        val geometryIndex = intersection.geometryIndex()
        val geometryPoints = decodeGeometry()
        if (geometryIndex != null && geometryPoints.size >= 2 && geometryIndex in geometryPoints.indices) {
            return geometryPoints.lengthUntil(geometryIndex).coerceIn(0.0, distance())
        }

        if (intersectionCount <= 1) return 0.0

        val ratio = intersectionIndex.toDouble() / (intersectionCount - 1).toDouble()
        return (distance() * ratio).coerceIn(0.0, distance())
    }

    private fun LegStep.decodeGeometry(): List<Point> {
        val geometry = geometry()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            PolylineUtils.decode(geometry, POLYLINE_PRECISION)
        }.getOrElse {
            emptyList()
        }
    }

    private fun List<Point>.lengthUntil(pointIndex: Int): Double {
        val points = take(pointIndex + 1)
        if (points.size < 2) return 0.0
        return TurfMeasurement.length(points, TurfConstants.UNIT_METERS)
    }

    /**
     * 文脈構築で使う道路種別とジオメトリの定数。
     */
    private companion object {
        private val HIGHWAY_CLASSES = setOf("motorway", "trunk")
        private const val POLYLINE_PRECISION = 6
    }
}
