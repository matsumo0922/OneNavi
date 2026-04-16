package me.matsumo.onenavi.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object TurnByTurnUpdateBus {

    private val _navInfo = MutableStateFlow<NavigationFeedSnapshot?>(null)
    val navInfo = _navInfo.asStateFlow()

    fun publish(navInfo: NavInfo) {
        _navInfo.value = NavigationFeedSnapshot(
            navState = navInfo.getNavState(),
            currentStep = navInfo.getCurrentStep()?.toSnapshot(),
            remainingSteps = navInfo.getRemainingSteps().orEmpty().mapNotNull { it?.toSnapshot() },
            distanceToCurrentStepMeters = navInfo.getDistanceToCurrentStepMeters(),
            timeToCurrentStepSeconds = navInfo.getTimeToCurrentStepSeconds(),
        )
    }

    fun clear() {
        _navInfo.value = null
    }
}

private fun com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo.toSnapshot(): NavigationStepSnapshot {
    return NavigationStepSnapshot(
        maneuver = getManeuver(),
        instruction = getFullInstructionText().orEmpty(),
        roadName = getFullRoadName(),
        lanes = getLanes().orEmpty().map { lane ->
            val directions = lane.laneDirections().orEmpty()
            val activeDirection = directions.firstOrNull { it.isRecommended() }?.laneShape()
            NavigationLaneSnapshot(
                directions = directions.map { it.laneShape() },
                activeDirection = activeDirection,
                isRecommended = activeDirection != null,
            )
        },
        drivingSide = getDrivingSide(),
        distanceFromPreviousMeters = getDistanceFromPrevStepMeters(),
        roundaboutTurnNumber = getRoundaboutTurnNumber(),
    )
}
