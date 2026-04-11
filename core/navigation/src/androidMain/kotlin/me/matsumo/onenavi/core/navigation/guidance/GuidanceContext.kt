package me.matsumo.onenavi.core.navigation.guidance

import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.trip.model.RouteProgress

data class GuidanceContext(
    val routeId: String,
    val routeProgress: RouteProgress,
    val currentLegIndex: Int,
    val currentStepIndex: Int,
    val currentStep: LegStep?,
    val nextStep: LegStep?,
    val upcomingSteps: List<UpcomingStepContext>,
    val upcomingIntersections: List<UpcomingIntersectionContext>,
    val currentRoadKind: RoadKind,
    val previousRoadKind: RoadKind?,
    val lastVoiceInstruction: VoiceInstructions?,
    val lastBannerInstruction: BannerInstructions?,
    val sessionState: GuidanceSessionState,
    val waypointKinds: List<DestinationKind>,
    val speechHistory: GuidanceSpeechHistory,
)

data class UpcomingStepContext(
    val legIndex: Int,
    val stepIndex: Int,
    val step: LegStep,
    val distanceFromCurrentMeters: Double,
)

data class UpcomingIntersectionContext(
    val legIndex: Int,
    val stepIndex: Int,
    val intersectionIndex: Int,
    val geometryIndex: Int?,
    val distanceFromCurrentMeters: Double,
    val intersection: StepIntersection,
)

data class GuidanceSessionState(
    val startedAtMillis: Long,
    val muted: Boolean,
)
