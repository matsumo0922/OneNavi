package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RoutePoint

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

/**
 * ナビゲーション中のルート + 残距離から、二つ先までの案内地点 Callout 情報を算出する。
 *
 * 位置情報（`maneuverLocation`）を持たない step、および既に通り過ぎた step は除外する。
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
        .mapNotNull { step ->
            val location = step.maneuverLocation ?: return@mapNotNull null
            if (step.cumulativeDistanceMeters <= traveledMeters + MANEUVER_PASSED_MARGIN_METERS) {
                return@mapNotNull null
            }
            NavigationCalloutInfo(
                maneuverLocation = location,
                maneuverType = step.maneuverType,
                maneuverModifier = step.modifier,
                // TODO: 交差点名の抽出は未実装。現状 Routes API の instructions は HTML を含む
                //       長文のためそのまま使えない。専用フィールド追加まで非表示とする。
                intersectionName = null,
            )
        }
        .take(UPCOMING_NAV_CALLOUT_MAX_COUNT)
        .toList()
        .toImmutableList()
}
