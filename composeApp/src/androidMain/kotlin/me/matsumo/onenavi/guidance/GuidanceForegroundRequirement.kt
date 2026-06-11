package me.matsumo.onenavi.guidance

import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState

/** 案内状態が Foreground Service による所有を必要とするかを返す。 */
internal fun GuidanceState.requiresForegroundService(): Boolean {
    return when (this) {
        is GuidanceState.Guiding,
        is GuidanceState.Rerouting,
        -> true
        GuidanceState.Arrived,
        is GuidanceState.Failed,
        GuidanceState.Idle,
        -> false
    }
}
