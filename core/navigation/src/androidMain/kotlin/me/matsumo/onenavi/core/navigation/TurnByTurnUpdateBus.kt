package me.matsumo.onenavi.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object TurnByTurnUpdateBus {

    private val _navInfo = MutableStateFlow<NavigationFeedSnapshot?>(null)
    val navInfo = _navInfo.asStateFlow()

    fun publish(navInfo: NavInfo) {
        _navInfo.value = NavigationFeedSnapshot(
            navState = navInfo.navState,
            currentStep = navInfo.currentStep?.toSnapshot(),
            remainingSteps = navInfo.remainingSteps.orEmpty().mapNotNull { it?.toSnapshot() },
            distanceToCurrentStepMeters = navInfo.distanceToCurrentStepMeters,
            timeToCurrentStepSeconds = navInfo.timeToCurrentStepSeconds,
        )
    }

    fun clear() {
        _navInfo.value = null
    }
}

private fun StepInfo.toSnapshot(): NavigationStepSnapshot {
    return NavigationStepSnapshot(
        maneuver = maneuver,
        instruction = fullInstructionText.orEmpty(),
        roadName = fullRoadName,
        lanes = lanes.orEmpty().map { lane ->
            val directions = lane.laneDirections().orEmpty()
            val recommendedDirection = directions.firstOrNull { it.isRecommended }?.laneShape()

            NavigationLaneSnapshot(
                directions = directions.map { it.laneShape() },
                activeDirection = recommendedDirection,
                isRecommended = directions.any { it.isRecommended },
            )
        },
        drivingSide = drivingSide,
        distanceFromPreviousMeters = distanceFromPrevStepMeters,
        roundaboutTurnNumber = roundaboutTurnNumber,
    )
}
