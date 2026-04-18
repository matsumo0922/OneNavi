package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteStepInfo

/**
 * ナビゲーション中に表示する案内地点 Callout 1 個分の情報。
 *
 * @param maneuverLocation マニューバ点の緯度経度
 * @param maneuverType マニューバ種別
 * @param maneuverModifier 方向修飾子
 * @param intersectionName Callout に表示する行き先の道路名ラベル。取得できない場合は null
 */
@Immutable
internal data class NavigationCalloutInfo(
    val maneuverLocation: RoutePoint,
    val maneuverType: ManeuverType,
    val maneuverModifier: ManeuverModifier?,
    val intersectionName: String?,
)

/** 描画する最大案内地点数（次 + 二つ先まで）。 */
private const val UPCOMING_NAV_CALLOUT_MAX_COUNT = 2

/** 「通り過ぎた」判定に使う余裕距離（メートル）。 */
private const val MANEUVER_PASSED_MARGIN_METERS = 5.0

/**
 * Callout を描画する対象外のマニューバ種別。
 *
 * `CONTINUE` / `DEPART` は直進や出発で案内すべき地点ではなく、`NAME_CHANGE` は道路名変更の通知、
 * `ARRIVE` は目的地到着で別 UI が担当するため除外する。
 */
private val NON_MANEUVER_TYPES = setOf(
    ManeuverType.CONTINUE,
    ManeuverType.DEPART,
    ManeuverType.NAME_CHANGE,
    ManeuverType.ARRIVE,
)

/**
 * ナビゲーション中のルート + 残距離から、二つ先までの案内地点 Callout 情報を算出する。
 *
 * 位置情報（`maneuverLocation`）を持たない step、既に通り過ぎた step、および直進等の
 * 実質的な案内を伴わない step は除外する。
 *
 * @param activeRoute 現在案内中のルート
 * @param distanceRemainingMeters 目的地までの残距離（メートル）
 * @param upcomingRoadNames Navigation SDK が把握する今後のマニューバ道路名。
 *   先頭から順に、算出された Callout にインデックス対応で割り当てる。null / 空文字は非表示扱い
 */
internal fun buildUpcomingNavigationCallouts(
    activeRoute: GoogleRoute?,
    distanceRemainingMeters: Double,
    upcomingRoadNames: List<String?>,
): ImmutableList<NavigationCalloutInfo> {
    if (activeRoute == null) return persistentListOf()
    if (distanceRemainingMeters <= 0.0) return persistentListOf()

    val traveledMeters = (activeRoute.distanceMeters - distanceRemainingMeters)
        .coerceAtLeast(0.0)

    return activeRoute.steps
        .asSequence()
        .mapNotNull { step -> step.toUpcomingCalloutOrNull(traveledMeters) }
        .take(UPCOMING_NAV_CALLOUT_MAX_COUNT)
        .withIndex()
        .map { (calloutIndex, callout) ->
            callout.copy(
                intersectionName = upcomingRoadNames.getOrNull(calloutIndex)
                    ?.takeIf { name -> name.isNotBlank() },
            )
        }
        .toList()
        .toImmutableList()
}

private fun RouteStepInfo.toUpcomingCalloutOrNull(
    traveledMeters: Double,
): NavigationCalloutInfo? {
    val location = maneuverLocation ?: return null
    if (maneuverType in NON_MANEUVER_TYPES) return null
    if (cumulativeDistanceMeters <= traveledMeters + MANEUVER_PASSED_MARGIN_METERS) return null

    return NavigationCalloutInfo(
        maneuverLocation = location,
        maneuverType = maneuverType,
        maneuverModifier = modifier,
        intersectionName = null,
    )
}
