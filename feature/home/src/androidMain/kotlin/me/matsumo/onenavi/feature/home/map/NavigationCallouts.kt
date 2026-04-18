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

/**
 * cumulative-distance ベースで「通り過ぎた」判定に使う余裕距離（メートル）。
 *
 * Routes API / Navigation SDK 間の距離ドリフトに耐えられるよう、単純な 1 ステップの揺らぎでは
 * callout が消えない程度に大きめに取る。
 */
private const val MANEUVER_PASSED_MARGIN_METERS = 30.0

/**
 * Navigation SDK の `distanceToCurrentStepMeters` と Routes API の累積距離を揃える際のずれ許容（メートル）。
 *
 * 両系統で距離計算が微妙に異なるため、SDK が示す「次マニューバまでの距離」から逆算した累積距離が
 * Routes API のステップと厳密一致しないことを許容する。
 */
private const val STEP_SYNC_SLACK_METERS = 50.0

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
 * 位置情報（`maneuverLocation`）を持たない step、および直進等の実質的な案内を伴わない step は除外する。
 * 「通過済み」判定は以下の優先順位で行う:
 *
 * 1. `distanceToCurrentStepMeters` が与えられている場合は Navigation SDK の現在ステップを基準に揃え、
 *    Nav SDK が既に次ステップへ進んだ API step を通過扱いにする。
 * 2. 与えられていない場合は Routes API の累積距離と残距離から算出した進行距離で判定する。
 *
 * @param activeRoute 現在案内中のルート
 * @param distanceRemainingMeters 目的地までの残距離（メートル）
 * @param distanceToCurrentStepMeters Navigation SDK が把握する次マニューバ地点までの距離（メートル）。
 *   null の場合は Routes API の累積距離のみで判定する
 * @param upcomingRoadNames Navigation SDK が把握する今後のマニューバ道路名。
 *   先頭から順に、算出された Callout にインデックス対応で割り当てる。null / 空文字は非表示扱い
 */
internal fun buildUpcomingNavigationCallouts(
    activeRoute: GoogleRoute?,
    distanceRemainingMeters: Double,
    distanceToCurrentStepMeters: Double?,
    upcomingRoadNames: List<String?>,
): ImmutableList<NavigationCalloutInfo> {
    if (activeRoute == null) return persistentListOf()
    if (distanceRemainingMeters <= 0.0) return persistentListOf()

    val traveledMeters = (activeRoute.distanceMeters - distanceRemainingMeters)
        .coerceAtLeast(0.0)

    val candidateSteps = activeRoute.steps.filter { step ->
        step.maneuverLocation != null && step.maneuverType !in NON_MANEUVER_TYPES
    }
    if (candidateSteps.isEmpty()) return persistentListOf()

    val currentIndex = resolveCurrentStepIndex(
        candidateSteps = candidateSteps,
        traveledMeters = traveledMeters,
        distanceToCurrentStepMeters = distanceToCurrentStepMeters,
    )
    if (currentIndex < 0) return persistentListOf()

    return candidateSteps
        .asSequence()
        .drop(currentIndex)
        .take(UPCOMING_NAV_CALLOUT_MAX_COUNT)
        .withIndex()
        .map { (calloutIndex, step) ->
            NavigationCalloutInfo(
                maneuverLocation = requireNotNull(step.maneuverLocation),
                maneuverType = step.maneuverType,
                maneuverModifier = step.modifier,
                intersectionName = upcomingRoadNames.getOrNull(calloutIndex)
                    ?.takeIf { name -> name.isNotBlank() },
            )
        }
        .toList()
        .toImmutableList()
}

/**
 * 次に案内すべき API step のインデックスを返す。通過済みの step は除外される。
 * 対象が無ければ -1 を返す。
 */
private fun resolveCurrentStepIndex(
    candidateSteps: List<RouteStepInfo>,
    traveledMeters: Double,
    distanceToCurrentStepMeters: Double?,
): Int {
    if (distanceToCurrentStepMeters != null) {
        val expectedCumulative = traveledMeters + distanceToCurrentStepMeters
        val syncedIndex = candidateSteps.indexOfFirst { step ->
            step.cumulativeDistanceMeters >= expectedCumulative - STEP_SYNC_SLACK_METERS
        }
        if (syncedIndex >= 0) return syncedIndex
    }
    return candidateSteps.indexOfFirst { step ->
        step.cumulativeDistanceMeters > traveledMeters + MANEUVER_PASSED_MARGIN_METERS
    }
}
