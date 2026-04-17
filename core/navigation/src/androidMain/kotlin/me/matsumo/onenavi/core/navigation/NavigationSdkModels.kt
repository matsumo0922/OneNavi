package me.matsumo.onenavi.core.navigation

data class NavigationFeedSnapshot(
    val navState: Int,
    val currentStep: NavigationStepSnapshot?,
    val remainingSteps: List<NavigationStepSnapshot>,
    val distanceToCurrentStepMeters: Int?,
    val timeToCurrentStepSeconds: Int?,
)

data class NavigationStepSnapshot(
    val maneuver: Int,
    val instruction: String,
    val roadName: String?,
    val simpleRoadName: String?,
    val lanes: List<NavigationLaneSnapshot>,
    val drivingSide: Int,
    val distanceFromPreviousMeters: Int?,
    val roundaboutTurnNumber: Int?,
)

data class NavigationLaneSnapshot(
    val directions: List<Int>,
    val activeDirection: Int?,
    val isRecommended: Boolean,
)

data class NavigationTripProgressSnapshot(
    val timeRemainingSeconds: Int,
    val distanceRemainingMeters: Int,
)

data class NavigationArrivalSnapshot(
    val waypointTitle: String?,
    val isFinalDestination: Boolean,
)
