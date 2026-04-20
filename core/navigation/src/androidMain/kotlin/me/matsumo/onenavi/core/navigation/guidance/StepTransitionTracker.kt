package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.NavigationStepSnapshot

/**
 * 現在ステップが変化したかを検出し、セッション中のみ有効なステップ識別子（カウンタ）を払い出す。
 *
 * Google Navigation SDK の `NavigationStepSnapshot` に ID がないため、
 * ヒューリスティック一致（[isSameStep]）＋距離急増の二条件で遷移判定する。
 */
internal class StepTransitionTracker {

    private var counter: Int = 0
    private var lastStep: NavigationStepSnapshot? = null
    private var lastDistanceToStep: Int? = null

    /**
     * 新しい snapshot を受け取ってカウンタを更新する。
     *
     * @param currentStep 今回のステップ。ENROUTE → REROUTING で null になり得る。
     * @param currentDistance 今回のステップまでの残距離（メートル）。
     * @return 更新後のカウンタと遷移有無。
     */
    fun update(
        currentStep: NavigationStepSnapshot?,
        currentDistance: Int?,
    ): StepTransitionResult {
        val previousStep = lastStep
        val transitioned = when {
            previousStep == null && currentStep == null -> false
            previousStep == null && currentStep != null -> {
                counter = 0
                true
            }
            previousStep != null && currentStep == null -> false
            else -> !isSameStep(
                previousStep = previousStep!!,
                currentStep = currentStep!!,
                currentDistance = currentDistance,
            )
        }
        if (transitioned) {
            counter++
        }
        lastStep = currentStep
        lastDistanceToStep = currentDistance
        return StepTransitionResult(counter = counter, transitioned = transitioned)
    }

    fun reset() {
        counter = 0
        lastStep = null
        lastDistanceToStep = null
    }

    private fun isSameStep(
        previousStep: NavigationStepSnapshot,
        currentStep: NavigationStepSnapshot,
        currentDistance: Int?,
    ): Boolean {
        val fieldsMatch = previousStep.maneuver == currentStep.maneuver &&
            previousStep.roadName == currentStep.roadName &&
            previousStep.simpleRoadName == currentStep.simpleRoadName &&
            previousStep.drivingSide == currentStep.drivingSide &&
            previousStep.roundaboutTurnNumber == currentStep.roundaboutTurnNumber
        if (!fieldsMatch) return false

        val previousDistance = lastDistanceToStep ?: return true
        val currentDistanceMeters = currentDistance ?: return true
        return currentDistanceMeters - previousDistance < DISTANCE_SURGE_THRESHOLD_METERS
    }

    companion object {
        private const val DISTANCE_SURGE_THRESHOLD_METERS = 300
    }
}

/**
 * [StepTransitionTracker.update] の結果。
 *
 * @property counter 現在のステップ識別子。
 * @property transitioned 今回のティックでステップが変わったかどうか。
 */
@Immutable
data class StepTransitionResult(
    val counter: Int,
    val transitioned: Boolean,
)
