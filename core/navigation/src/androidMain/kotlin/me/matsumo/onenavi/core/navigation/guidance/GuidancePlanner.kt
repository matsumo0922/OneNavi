package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.NavigationFeedSnapshot

/**
 * `NavigationFeedSnapshot` の差分から音声案内イベントを生成する純関数ラッパー。
 *
 * Phase 0 時点では骨組みのみで、常に空リストを返す。
 * 距離バケット下抜け判定、レーン案内判定、道なり判定などの実装は Phase 2 / Phase 3 で行う。
 */
class GuidancePlanner {

    @Suppress("UNUSED_PARAMETER")
    fun plan(input: GuidancePlannerInput): List<GuidanceEvent> {
        return emptyList()
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
