package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteStepInfo

/**
 * ナビゲーション中に表示する案内地点 Callout 1 個分の情報。
 *
 * @param maneuverLocation マニューバ点の緯度経度
 * @param maneuverType マニューバ種別。[me.matsumo.onenavi.core.model.ManeuverInfo.type] 相当
 * @param maneuverModifier 方向修飾子。[me.matsumo.onenavi.core.model.ManeuverInfo.modifier] 相当
 * @param intersectionName 交差点名などの表示用テキスト。取得できない場合は null
 */
@Immutable
internal data class NavigationCalloutInfo(
    val maneuverLocation: RoutePoint,
    val maneuverType: String,
    val maneuverModifier: String?,
    val intersectionName: String?,
)

/** 描画する最大案内地点数（次 + 二つ先まで）。 */
private const val UPCOMING_NAV_CALLOUT_MAX_COUNT = 2

/** 「通り過ぎた」判定に使う余裕距離（メートル）。 */
private const val MANEUVER_PASSED_MARGIN_METERS = 5.0

/** 交差点名として採用するには短すぎる bold テキスト長の閾値（日本語を想定）。 */
private const val INTERSECTION_NAME_MIN_LENGTH = 2

/**
 * Callout を描画する対象外のマニューバ種別。
 *
 * "continue"/"depart" は直進や出発で案内すべき地点ではなく、"new_name" は道路名変更の通知、
 * "arrive" は目的地到着で別 UI が担当するため除外する。
 */
private val NON_MANEUVER_TYPES = setOf(
    "continue",
    "depart",
    "new_name",
    "arrive",
)

/** bold で囲まれた部分を全て取り出すための正規表現。 */
private val BOLD_TAG_REGEX = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)

/**
 * Google Routes API の instruction テキストから日本語の交差点名として使えそうな
 * bold テキストを 1 つ抽出する。
 *
 * Google は交差点名 / 道路名を `<b>...</b>` で囲んで返すが、方向語（右折 / 左折 など）も
 * 同じタグで囲まれるため、最も長い bold テキストを採用することで方向語との衝突を避ける。
 * 該当がなければ null を返す。
 */
internal fun extractIntersectionName(instruction: String): String? {
    if (instruction.isBlank()) return null
    val boldTexts = BOLD_TAG_REGEX
        .findAll(instruction)
        .map { match -> match.groupValues[1].trim() }
        .filter { text -> text.length > INTERSECTION_NAME_MIN_LENGTH }
        .toList()
    return boldTexts.maxByOrNull { it.length }
}

/**
 * ナビゲーション中のルート + 残距離から、二つ先までの案内地点 Callout 情報を算出する。
 *
 * 位置情報（`maneuverLocation`）を持たない step、既に通り過ぎた step、および直進等の
 * 実質的な案内を伴わない step は除外する。
 */
internal fun buildUpcomingNavigationCallouts(
    activeRoute: GoogleRoute?,
    distanceRemainingMeters: Double,
): ImmutableList<NavigationCalloutInfo> {
    if (activeRoute == null) return persistentListOf()
    if (distanceRemainingMeters <= 0.0) return persistentListOf()

    val traveledMeters = (activeRoute.distanceMeters - distanceRemainingMeters)
        .coerceAtLeast(0.0)

    return activeRoute.steps
        .asSequence()
        .mapNotNull { step -> step.toUpcomingCalloutOrNull(traveledMeters) }
        .take(UPCOMING_NAV_CALLOUT_MAX_COUNT)
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
        intersectionName = extractIntersectionName(instruction),
    )
}
