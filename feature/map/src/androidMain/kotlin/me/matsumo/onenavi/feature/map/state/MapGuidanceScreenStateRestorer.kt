package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState

/** 案内 state から地図画面 stack を復元する helper。 */
internal object MapGuidanceScreenStateRestorer {

    fun restore(
        states: List<MapScreenState>,
        guidanceState: GuidanceState,
    ): List<MapScreenState> {
        val currentStates = states.ifEmpty { listOf(MapScreenState.Browsing) }

        if (!guidanceState.requiresNavigatingScreen()) {
            return currentStates
        }

        if (currentStates.lastOrNull() is MapScreenState.Navigating) {
            return currentStates
        }

        return listOf(
            MapScreenState.Browsing,
            MapScreenState.Navigating,
        )
    }

    private fun GuidanceState.requiresNavigatingScreen(): Boolean {
        return when (this) {
            is GuidanceState.Guiding,
            is GuidanceState.Preparing,
            is GuidanceState.Rerouting,
            -> true

            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> false
        }
    }
}
