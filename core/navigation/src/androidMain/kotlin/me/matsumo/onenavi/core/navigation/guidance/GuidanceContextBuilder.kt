package me.matsumo.onenavi.core.navigation.guidance

import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.trip.model.RouteProgress

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
            upcomingIntersections = buildUpcomingIntersections(upcomingSteps),
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
        var cumulativeDistance = 0.0

        for (legIndex in currentLegIndex until legs.size) {
            val steps = legs[legIndex].steps().orEmpty()
            val startStepIndex = if (legIndex == currentLegIndex) currentStepIndex else 0

            for (stepIndex in startStepIndex until steps.size) {
                val step = steps[stepIndex]
                val distanceFromCurrent = if (legIndex == currentLegIndex && stepIndex == currentStepIndex) {
                    currentStepDistanceRemaining
                } else {
                    cumulativeDistance + step.distance()
                }

                result.add(
                    UpcomingStepContext(
                        legIndex = legIndex,
                        stepIndex = stepIndex,
                        step = step,
                        distanceFromCurrentMeters = distanceFromCurrent,
                    ),
                )

                cumulativeDistance = distanceFromCurrent
            }
        }

        return result
    }

    private fun buildUpcomingIntersections(
        upcomingSteps: List<UpcomingStepContext>,
    ): List<UpcomingIntersectionContext> {
        return upcomingSteps
            .take(8)
            .flatMap { stepContext ->
                stepContext.step.intersections().orEmpty().mapIndexed { intersectionIndex, intersection ->
                    UpcomingIntersectionContext(
                        legIndex = stepContext.legIndex,
                        stepIndex = stepContext.stepIndex,
                        intersectionIndex = intersectionIndex,
                        geometryIndex = intersection.geometryIndex(),
                        distanceFromCurrentMeters = stepContext.distanceFromCurrentMeters,
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

    private companion object {
        private val HIGHWAY_CLASSES = setOf("motorway", "trunk")
    }
}

