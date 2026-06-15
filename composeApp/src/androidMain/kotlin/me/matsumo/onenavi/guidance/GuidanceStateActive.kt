package me.matsumo.onenavi.guidance

import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState

/** 案内状態が位置更新と案内継続を必要とする active 状態かを返す。 */
internal val GuidanceState.isActiveGuidance: Boolean
    get() = when (this) {
        is GuidanceState.Guiding,
        is GuidanceState.Preparing,
        is GuidanceState.Rerouting,
        -> true
        GuidanceState.Arrived,
        is GuidanceState.Failed,
        GuidanceState.Idle,
        -> false
    }
