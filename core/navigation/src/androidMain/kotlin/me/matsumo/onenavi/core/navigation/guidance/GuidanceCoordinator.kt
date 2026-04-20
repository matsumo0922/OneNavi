package me.matsumo.onenavi.core.navigation.guidance

import io.github.aakira.napier.Napier
import me.matsumo.onenavi.core.model.DistanceBucket
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

    private companion object {
        private const val TAG = "GuidanceCoordinator"
    }

    fun onNavigationUpdate(snapshot: NavigationFeedSnapshot) {
        val transition = stepTracker.update(
            currentStep = snapshot.currentStep,
            currentDistance = snapshot.distanceToCurrentStepMeters,
        )
        Napier.d(tag = TAG) {
            "[P4] TICK step=${transition.counter} " +
                "prev=${previousSnapshot?.distanceToCurrentStepMeters} " +
                "curr=${snapshot.distanceToCurrentStepMeters} " +
                "transitioned=${transition.transitioned} " +
                "spokenKeysSize=${spokenKeys.size}"
        }
        if (transition.transitioned) {
            spokenKeys.forgetBefore(transition.counter)
            val currentDistance = snapshot.distanceToCurrentStepMeters
            if (currentDistance != null) {
                markAlreadyPassedBuckets(transition.counter, currentDistance)
            }
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

    /**
     * 発話対象イベントに対応する [SpokenGuideKey] を登録する。
     *
     * 既に同一キーが記録されている場合は false を返して dispatch をスキップする。
     * 状態を持たないセッション制御系・ルート系イベントは常に true を返す。
     */
    private fun markSpokenIfNeeded(event: GuidanceEvent): Boolean {
        val key = when (event) {
            is GuidanceEvent.Maneuver -> SpokenGuideKey(
                stepCounter = event.stepCounter,
                category = SpokenGuideKey.Category.MANEUVER,
                bucket = event.bucket,
            )
            is GuidanceEvent.Lane -> SpokenGuideKey(
                stepCounter = event.stepCounter,
                category = SpokenGuideKey.Category.LANE,
                bucket = event.bucket,
            )
            is GuidanceEvent.Straightforward -> SpokenGuideKey(
                stepCounter = event.stepCounter,
                category = SpokenGuideKey.Category.STRAIGHTFORWARD,
                bucket = null,
            )
            else -> return true
        }
        if (spokenKeys.contains(key)) return false
        spokenKeys.add(key)
        return true
    }

    /**
     * ステップ遷移直後の catch-up。
     *
     * 新ステップの開始距離が既にバケット閾値以下なら「通過済み」として spokenKeys に事前登録し、
     * 以降のティックで遠距離バケットが誤って下抜け検出されないようにする。
     */
    private fun markAlreadyPassedBuckets(stepCounter: Int, currentDistance: Int) {
        DistanceBucket.entries
            .filter { bucket -> currentDistance <= bucket.thresholdMeters }
            .forEach { bucket ->
                spokenKeys.add(
                    SpokenGuideKey(
                        stepCounter = stepCounter,
                        category = SpokenGuideKey.Category.MANEUVER,
                        bucket = bucket,
                    ),
                )
            }
    }
}
