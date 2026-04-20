package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.DistanceBucket
import me.matsumo.onenavi.core.model.FollowupDistanceBucket
import me.matsumo.onenavi.core.model.FollowupManeuver
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.model.LanePosition
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.StraightforwardLevel
import me.matsumo.onenavi.core.navigation.NavigationFeedSnapshot
import me.matsumo.onenavi.core.navigation.NavigationLaneSnapshot
import me.matsumo.onenavi.core.navigation.NavigationStepSnapshot
import me.matsumo.onenavi.core.navigation.toDrivingSide
import me.matsumo.onenavi.core.navigation.toManeuverModifier
import me.matsumo.onenavi.core.navigation.toManeuverType

/**
 * `NavigationFeedSnapshot` の差分から音声案内イベントを生成する純関数ラッパー。
 *
 * v1 では以下のイベントを生成する:
 * - マニューバ予告（距離バケット下抜け + AT_50M 単独モード）
 * - レーン案内（AT_500M 下抜け、ターン系マニューバかつ推奨位置が判定可能なときのみ）
 * - 道なり案内（ステップ遷移直後に 1 回、距離が 1000m 以上）
 */
class GuidancePlanner {

    fun plan(input: GuidancePlannerInput): List<GuidanceEvent> {
        val events = mutableListOf<GuidanceEvent>()
        planStraightforward(input)?.let { events.add(it) }
        planManeuver(input)?.let { events.add(it) }
        planLane(input)?.let { events.add(it) }
        return events
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

        val followup = if (bucket == DistanceBucket.AT_100M) {
            resolveFollowup(
                currentDistance = currentDistance,
                nextStep = input.currentSnapshot.remainingSteps.firstOrNull(),
            )
        } else {
            null
        }

        return GuidanceEvent.Maneuver(
            stepCounter = input.stepCounter,
            bucket = bucket,
            maneuverType = currentStep.maneuver.toManeuverType(),
            modifier = currentStep.maneuver.toManeuverModifier(),
            drivingSide = currentStep.drivingSide.toDrivingSide(),
            isStandaloneAt50m = isStandaloneAt50m,
            followup = followup,
            priority = priorityFor(bucket),
        )
    }

    /**
     * 次ステップが近接しているとき、連続案内（followup）の内容を組み立てる。
     *
     * 条件:
     * - 次ステップが存在する（`nextStep != null`）
     * - `currentDistance + nextStep.distanceFromPreviousMeters <= 500m`
     * - 次ステップが方向を持つターン系（modifier が null でなく、道なり・分岐・合流・ランプでないこと）
     *
     * いずれか満たさなければ null。
     */
    private fun resolveFollowup(
        currentDistance: Int,
        nextStep: NavigationStepSnapshot?,
    ): FollowupManeuver? {
        val step = nextStep ?: return null
        val gapToNext = step.distanceFromPreviousMeters ?: return null
        val totalDistance = currentDistance + gapToNext
        if (totalDistance > FOLLOWUP_MAX_DISTANCE_METERS) return null

        val maneuverType = step.maneuver.toManeuverType()
        if (maneuverType !in FOLLOWUP_SUPPORTED_TYPES) return null
        val modifier = step.maneuver.toManeuverModifier() ?: return null

        return FollowupManeuver(
            distanceBucket = FollowupDistanceBucket.fromMeters(totalDistance),
            maneuverType = maneuverType,
            modifier = modifier,
        )
    }

    private fun planLane(input: GuidancePlannerInput): GuidanceEvent.Lane? {
        if (input.stepTransitioned) return null
        val currentStep = input.currentSnapshot.currentStep ?: return null
        val currentDistance = input.currentSnapshot.distanceToCurrentStepMeters ?: return null
        val previousDistance = input.previousSnapshot?.distanceToCurrentStepMeters ?: return null

        if (currentStep.maneuver.toManeuverType() !in LANE_TRIGGER_TYPES) return null
        if (currentStep.lanes.isEmpty()) return null

        val threshold = DistanceBucket.AT_500M.thresholdMeters
        if (!(previousDistance > threshold && currentDistance <= threshold)) return null

        val laneKey = SpokenGuideKey(
            stepCounter = input.stepCounter,
            category = SpokenGuideKey.Category.LANE,
            bucket = DistanceBucket.AT_500M,
        )
        if (input.spokenKeys.contains(laneKey)) return null

        val position = resolveLanePosition(currentStep.lanes) ?: return null

        return GuidanceEvent.Lane(
            stepCounter = input.stepCounter,
            bucket = DistanceBucket.AT_500M,
            lanePosition = position,
            priority = GuidancePriority.NORMAL,
        )
    }

    private fun planStraightforward(input: GuidancePlannerInput): GuidanceEvent.Straightforward? {
        if (!input.stepTransitioned) return null
        val distance = input.currentSnapshot.distanceToCurrentStepMeters ?: return null
        val level = when {
            distance >= STRAIGHT_LONG_THRESHOLD_METERS -> StraightforwardLevel.LONG
            distance >= STRAIGHT_SHORT_THRESHOLD_METERS -> StraightforwardLevel.SHORT
            else -> return null
        }
        val key = SpokenGuideKey(
            stepCounter = input.stepCounter,
            category = SpokenGuideKey.Category.STRAIGHTFORWARD,
            bucket = null,
        )
        if (input.spokenKeys.contains(key)) return null
        return GuidanceEvent.Straightforward(
            stepCounter = input.stepCounter,
            level = level,
            priority = GuidancePriority.LOW,
        )
    }

    /**
     * 推奨車線インデックス集合から発話対象の [LanePosition] を判定する。
     *
     * - 最左側に連続する集合（かつ最右は含まない） → [LanePosition.LEFT]
     * - 最右側に連続する集合（かつ最左は含まない） → [LanePosition.RIGHT]
     * - 3 車線以上で両端を含まない集合 → [LanePosition.CENTER]
     * - それ以外（飛び飛び、全車線推奨、推奨なし） → null（発話しない）
     */
    private fun resolveLanePosition(lanes: List<NavigationLaneSnapshot>): LanePosition? {
        if (lanes.isEmpty()) return null
        val totalLanes = lanes.size
        val recommendedIndices = lanes.mapIndexedNotNull { index, lane ->
            index.takeIf { lane.isRecommended }
        }
        if (recommendedIndices.isEmpty()) return null

        val minIndex = recommendedIndices.first()
        val maxIndex = recommendedIndices.last()
        val isContiguous = recommendedIndices == (minIndex..maxIndex).toList()
        if (!isContiguous) return null

        val rightmost = totalLanes - 1
        return when {
            minIndex == 0 && maxIndex < rightmost -> LanePosition.LEFT
            maxIndex == rightmost && minIndex > 0 -> LanePosition.RIGHT
            totalLanes >= CENTER_MIN_LANES && minIndex > 0 && maxIndex < rightmost -> LanePosition.CENTER
            else -> null
        }
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

    companion object {
        private const val STRAIGHT_SHORT_THRESHOLD_METERS = 1000
        private const val STRAIGHT_LONG_THRESHOLD_METERS = 5000
        private const val CENTER_MIN_LANES = 3
        private const val FOLLOWUP_MAX_DISTANCE_METERS = 500

        private val LANE_TRIGGER_TYPES = setOf(
            ManeuverType.TURN,
            ManeuverType.FORK,
            ManeuverType.MERGE,
            ManeuverType.ON_RAMP,
            ManeuverType.OFF_RAMP,
        )

        // v1 では方向を持つターン系のみ followup 対象。分岐・合流・ランプ・道なりは除外。
        private val FOLLOWUP_SUPPORTED_TYPES = setOf(
            ManeuverType.TURN,
            ManeuverType.NAME_CHANGE,
            ManeuverType.END_OF_ROAD,
            ManeuverType.UTURN,
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
