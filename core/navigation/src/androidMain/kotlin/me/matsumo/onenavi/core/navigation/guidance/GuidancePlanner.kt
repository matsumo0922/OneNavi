package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.DistanceBucket
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.navigation.NavigationFeedSnapshot
import me.matsumo.onenavi.core.navigation.toDrivingSide
import me.matsumo.onenavi.core.navigation.toManeuverModifier
import me.matsumo.onenavi.core.navigation.toManeuverType

/**
 * `NavigationFeedSnapshot` の差分から音声案内イベントを生成する純関数ラッパー。
 *
 * v1 ではマニューバ予告（距離バケット下抜け + AT_50M 単独モード）のみを実装する。
 * Lane / Straightforward は Phase 3 で追加する。
 */
class GuidancePlanner {

    fun plan(input: GuidancePlannerInput): List<GuidanceEvent> {
        val maneuver = planManeuver(input) ?: return emptyList()
        return listOf(maneuver)
    }

    private fun planManeuver(input: GuidancePlannerInput): GuidanceEvent.Maneuver? {
        if (input.stepTransitioned) return null
        val currentStep = input.currentSnapshot.currentStep ?: return null
        val currentDistance = input.currentSnapshot.distanceToCurrentStepMeters ?: return null
        val previousDistance = input.previousSnapshot?.distanceToCurrentStepMeters ?: return null

        val at100mSpoken = input.spokenKeys.contains(maneuverKey(input.stepCounter, DistanceBucket.AT_100M))
        val bucket = crossedBuckets(previousDistance, currentDistance)
            .filter { candidate -> !input.spokenKeys.contains(maneuverKey(input.stepCounter, candidate)) }
            .filter { candidate -> !(candidate == DistanceBucket.AT_50M && at100mSpoken) }
            .minByOrNull { candidate -> candidate.thresholdMeters }
            ?: return null

        val isStandaloneAt50m = bucket == DistanceBucket.AT_50M && !at100mSpoken

        return GuidanceEvent.Maneuver(
            stepCounter = input.stepCounter,
            bucket = bucket,
            maneuverType = currentStep.maneuver.toManeuverType(),
            modifier = currentStep.maneuver.toManeuverModifier(),
            drivingSide = currentStep.drivingSide.toDrivingSide(),
            isStandaloneAt50m = isStandaloneAt50m,
            priority = priorityFor(bucket),
        )
    }

    private fun crossedBuckets(previousDistance: Int, currentDistance: Int): List<DistanceBucket> {
        return DistanceBucket.entries.filter { bucket ->
            previousDistance > bucket.thresholdMeters && currentDistance <= bucket.thresholdMeters
        }
    }

    private fun priorityFor(bucket: DistanceBucket): GuidancePriority = when (bucket) {
        DistanceBucket.AT_100M,
        DistanceBucket.AT_50M,
        -> GuidancePriority.HIGH
        DistanceBucket.AT_2KM,
        DistanceBucket.AT_500M,
        -> GuidancePriority.NORMAL
    }

    private fun maneuverKey(stepCounter: Int, bucket: DistanceBucket): SpokenGuideKey {
        return SpokenGuideKey(
            stepCounter = stepCounter,
            category = SpokenGuideKey.Category.MANEUVER,
            bucket = bucket,
        )
    }
}

/**
 * [GuidancePlanner.plan] の入力。
 *
 * @property previousSnapshot 前回のティックで観測した snapshot。初回は null。
 * @property currentSnapshot 今回のティックで観測した snapshot。
 * @property stepCounter `StepTransitionTracker` が払い出した現在ステップ識別子。
 * @property stepTransitioned 今回のティックでステップが変わったか。
 * @property spokenKeys 既発話キーのスナップショット。副作用を持たないよう読み取り専用 Set で渡す。
 */
@Immutable
data class GuidancePlannerInput(
    val previousSnapshot: NavigationFeedSnapshot?,
    val currentSnapshot: NavigationFeedSnapshot,
    val stepCounter: Int,
    val stepTransitioned: Boolean,
    val spokenKeys: Set<SpokenGuideKey>,
)
