package me.matsumo.onenavi.core.navigation.newguidance.progress

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute

/**
 * 位置非依存の [GuidanceRoute] と現在地 (geometry 累積距離) から、tick ごとに参照する
 * イベントカーソルを導出する selector (L2 progress 層)。
 *
 * semantic イベントの中身は複製せず、「現在地より先のどのイベントか」だけを選び出す。
 * 整形や閾値判定 (レーン可視距離など) は presentation / adapter 側の責務。状態を持たない。
 */
internal class GuidanceRouteSelector {

    /**
     * 現在地より先のイベントから、主案内カーソルと先行イベント列を選ぶ。
     *
     * @param route 案内ルート (events は geometry 距離の昇順)
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 次 / 次々の主案内イベントと、現在地より先の全イベント
     */
    fun select(
        route: GuidanceRoute,
        currentCumulativeMeters: Double,
    ): GuidanceSelection {
        val thresholdMeters = currentCumulativeMeters + NEXT_EVENT_EPSILON_METRES
        val eventsAfterCurrent = route.events.filter { event -> event.anchor.geometryDistanceFromStartMeters > thresholdMeters }.toImmutableList()
        val primaryEvents = eventsAfterCurrent.filter { event -> event.primary != null }

        return GuidanceSelection(
            nextPrimaryEvent = primaryEvents.getOrNull(0),
            followupPrimaryEvent = primaryEvents.getOrNull(1),
            eventsAfterCurrent = eventsAfterCurrent,
        )
    }

    private companion object {
        /** 現在地と同距離のイベントを通過済みにするための小さな epsilon。 */
        private const val NEXT_EVENT_EPSILON_METRES: Double = 1.0
    }
}

/**
 * selector が 1 tick で選んだイベントカーソル。
 *
 * @property nextPrimaryEvent 現在地より先の最初の主案内イベント。無ければ null。
 * @property followupPrimaryEvent その次の主案内イベント。無ければ null。
 * @property eventsAfterCurrent 現在地より先の全イベント (geometry 距離の昇順)。
 */
@Immutable
data class GuidanceSelection(
    val nextPrimaryEvent: GuidanceEvent?,
    val followupPrimaryEvent: GuidanceEvent?,
    val eventsAfterCurrent: ImmutableList<GuidanceEvent>,
)
