package me.matsumo.onenavi.core.navigation.guidance

import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.navigation.NavigationFeedSnapshot

/**
 * 音声案内の状態保持と経路分岐を担うコーディネーター。
 *
 * `GuidanceSessionManager` から以下の経路で呼ばれる:
 * - [onNavigationUpdate] : ENROUTE 時のみ。Planner を駆動してターン系・レーン・道なりイベントを生成する。
 * - [onOffRouteChanged] : navState に依らず `isOffRoute` の変化だけで OffRoute / OnRouteRecovered を発火する。
 * - [onRerouted] : 新ルート確定時に state を初期化し Rerouted を発火する。
 * - [emit] : セッション制御や到着などの単発イベント。
 *
 * Phase 0 時点では配線用の骨組み。`GuidancePlanner.plan()` が空リストを返すため、
 * [onNavigationUpdate] を呼んでも発話イベントは発生しない。
 */
internal class GuidanceCoordinator(
    private val planner: GuidancePlanner,
    private val dispatcher: SpeechDispatcher,
) {

    private val stepTracker = StepTransitionTracker()
    private val spokenKeys = SpokenGuideKeyStore()
    private var previousSnapshot: NavigationFeedSnapshot? = null
    private var previousIsOffRoute: Boolean = false

    fun onNavigationUpdate(snapshot: NavigationFeedSnapshot) {
        val transition = stepTracker.update(
            currentStep = snapshot.currentStep,
            currentDistance = snapshot.distanceToCurrentStepMeters,
        )
        if (transition.transitioned) {
            spokenKeys.forgetBefore(transition.counter)
        }

        val events = planner.plan(
            GuidancePlannerInput(
                previousSnapshot = previousSnapshot,
                currentSnapshot = snapshot,
                stepCounter = transition.counter,
                stepTransitioned = transition.transitioned,
                spokenKeys = spokenKeys.snapshot(),
            ),
        )
        events.forEach { event ->
            if (markSpokenIfNeeded(event)) {
                dispatcher.send(event)
            }
        }

        previousSnapshot = snapshot
    }

    fun onOffRouteChanged(isOffRoute: Boolean) {
        if (isOffRoute == previousIsOffRoute) return
        previousIsOffRoute = isOffRoute
        val event = if (isOffRoute) {
            GuidanceEvent.OffRoute(priority = GuidancePriority.CRITICAL)
        } else {
            GuidanceEvent.OnRouteRecovered(priority = GuidancePriority.CRITICAL)
        }
        dispatcher.send(event)
    }

    fun onRerouted() {
        stepTracker.reset()
        spokenKeys.clear()
        previousSnapshot = null
        dispatcher.send(GuidanceEvent.Rerouted(priority = GuidancePriority.CRITICAL))
    }

    fun emit(event: GuidanceEvent) {
        dispatcher.send(event)
    }

    fun reset() {
        stepTracker.reset()
        spokenKeys.clear()
        previousSnapshot = null
        previousIsOffRoute = false
    }

    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    private fun markSpokenIfNeeded(event: GuidanceEvent): Boolean {
        return true
    }
}
