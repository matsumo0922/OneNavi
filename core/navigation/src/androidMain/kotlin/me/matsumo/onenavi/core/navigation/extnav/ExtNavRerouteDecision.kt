package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * リルート判定器の出力。
 */
internal sealed interface ExtNavRerouteDecision {

    /** リルート要求なし。 */
    data object None : ExtNavRerouteDecision

    /**
     * リルート要求。
     *
     * @param origin 再探索の出発地
     * @param destination 再探索の目的地
     * @param remainingViaPoints 未通過の経由地
     * @param currentCumulativeMeters リルート判定時点のルート上累積距離
     * @param originDirectionDegrees 出発地点の進行方向 (コンパス方位角、北 = 0°・時計回り、0..359)。
     *   進行方向沿いの経路を得るため再探索へ渡す。求められなければ null
     * @param reason リルート理由
     */
    @Immutable
    data class Request(
        val origin: RoutePoint,
        val destination: RoutePoint,
        val remainingViaPoints: ImmutableList<RoutePoint>,
        val currentCumulativeMeters: Double,
        val originDirectionDegrees: Int?,
        val reason: ExtNavRerouteReason,
    ) : ExtNavRerouteDecision
}

/**
 * リルート理由。
 */
internal enum class ExtNavRerouteReason {
    OffRoute,
}
