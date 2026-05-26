package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import kotlin.math.abs
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection as ExtNavManeuverDirection

/**
 * 発話 category・進行方位差・外部 API 由来の方向から、UI 中立な maneuver 種別 /
 * modifier を推定する純粋な分類ヘルパ。
 *
 * tick 時の TBT 判定 ([ExtNavGuidanceTracker]) と attach 時の案内イベント構築
 * (semantic mapper) の双方から使う共通実装。状態を持たない。
 */
internal object ManeuverClassifier {

    /** 方位差から turn とみなす最小角度。 */
    const val TURN_BEARING_DIFF_DEGREES: Float = 30f

    /** straight modifier とみなす最大角度差。 */
    private const val STRAIGHT_MAX_DEGREES: Float = 5f

    /** slight modifier とみなす最大角度差。 */
    private const val SLIGHT_MAX_DEGREES: Float = 60f

    /** left / right modifier とみなす最大角度差。 */
    private const val TURN_MAX_DEGREES: Float = 150f

    /** merge 系 maneuver として扱う phrase category。 */
    val MERGE_CATEGORIES: Set<GuidanceCategory> = setOf(
        GuidanceCategory.Merge,
        GuidanceCategory.MergeAttention,
        GuidanceCategory.HighwayLaneReduction,
    )

    /** fork 系 maneuver として扱う phrase category。 */
    val FORK_CATEGORIES: Set<GuidanceCategory> = setOf(
        GuidanceCategory.AutoExpresswayEntry,
        GuidanceCategory.TunnelBranch,
    )

    /** turn 系 maneuver として扱う phrase category。 */
    val TURN_CATEGORIES: Set<GuidanceCategory> = setOf(
        GuidanceCategory.IntersectionGuide,
        GuidanceCategory.IntersectionGuideSoon,
        GuidanceCategory.TrafficLight,
        GuidanceCategory.TurnAttention,
        GuidanceCategory.LocalRoadDirection,
    )

    /** 経路選択を伴う maneuver として扱う phrase category。 */
    val ROUTE_DECISION_CATEGORIES: Set<GuidanceCategory> = FORK_CATEGORIES + TURN_CATEGORIES

    /** TBT の対象にする phrase category。 */
    val MANEUVER_CATEGORIES: Set<GuidanceCategory> = ROUTE_DECISION_CATEGORIES

    /**
     * phrase category と方位差から maneuver 種別を推定する。
     *
     * @param categories GP の phrase category 一覧
     * @param isLastGuidancePoint 最終 GP かどうか
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver 種別
     */
    fun maneuverType(
        categories: List<GuidanceCategory>,
        isLastGuidancePoint: Boolean,
        bearingDiffDegrees: Float,
    ): ManeuverType {
        if (isLastGuidancePoint) return ManeuverType.ARRIVE
        if (categories.any { category -> category in MERGE_CATEGORIES }) return ManeuverType.MERGE
        if (categories.any { category -> category in FORK_CATEGORIES }) return ManeuverType.FORK
        if (categories.any { category -> category == GuidanceCategory.RoadName }) return ManeuverType.NAME_CHANGE

        val isTurn = abs(bearingDiffDegrees) >= TURN_BEARING_DIFF_DEGREES ||
            categories.any { category -> category in TURN_CATEGORIES }
        if (isTurn) return ManeuverType.TURN

        return ManeuverType.CONTINUE
    }

    /**
     * 方位差から左右・直進などの modifier を推定する。
     *
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver modifier
     */
    fun maneuverModifier(bearingDiffDegrees: Float): ManeuverModifier {
        val absDiffDegrees = abs(bearingDiffDegrees)
        val turnsRight = bearingDiffDegrees >= 0f
        return when {
            absDiffDegrees <= STRAIGHT_MAX_DEGREES -> ManeuverModifier.STRAIGHT
            absDiffDegrees <= SLIGHT_MAX_DEGREES -> slightModifier(turnsRight)
            absDiffDegrees <= TURN_MAX_DEGREES -> turnModifier(turnsRight)
            else -> ManeuverModifier.UTURN
        }
    }

    /**
     * 外部 API 由来の方向が進路判断を伴うかを返す。
     *
     * @param direction 外部 API 由来の方向
     * @return 直進・不明以外なら true
     */
    fun isRouteDecisionDirection(direction: ExtNavManeuverDirection): Boolean = when (direction) {
        ExtNavManeuverDirection.Straight,
        ExtNavManeuverDirection.Unknown,
        -> false
        ExtNavManeuverDirection.UTurn,
        ExtNavManeuverDirection.Left,
        ExtNavManeuverDirection.SlantLeft,
        ExtNavManeuverDirection.ThisSideLeft,
        ExtNavManeuverDirection.Right,
        ExtNavManeuverDirection.SlantRight,
        ExtNavManeuverDirection.ThisSideRight,
        -> true
    }

    /**
     * 外部 API 由来の方向を UI 用 modifier へ変換する。
     *
     * @param direction 外部 API 由来の方向
     * @return UI 用 maneuver modifier
     */
    fun toManeuverModifier(direction: ExtNavManeuverDirection): ManeuverModifier = when (direction) {
        ExtNavManeuverDirection.Straight,
        ExtNavManeuverDirection.Unknown,
        -> ManeuverModifier.STRAIGHT
        ExtNavManeuverDirection.UTurn,
        -> ManeuverModifier.UTURN
        ExtNavManeuverDirection.Left,
        ExtNavManeuverDirection.ThisSideLeft,
        -> ManeuverModifier.LEFT
        ExtNavManeuverDirection.SlantLeft,
        -> ManeuverModifier.SLIGHT_LEFT
        ExtNavManeuverDirection.Right,
        ExtNavManeuverDirection.ThisSideRight,
        -> ManeuverModifier.RIGHT
        ExtNavManeuverDirection.SlantRight,
        -> ManeuverModifier.SLIGHT_RIGHT
    }

    /** 浅い角度の左右 modifier を返す。 */
    private fun slightModifier(turnsRight: Boolean): ManeuverModifier =
        if (turnsRight) ManeuverModifier.SLIGHT_RIGHT else ManeuverModifier.SLIGHT_LEFT

    /** 通常角度の左右 modifier を返す。 */
    private fun turnModifier(turnsRight: Boolean): ManeuverModifier =
        if (turnsRight) ManeuverModifier.RIGHT else ManeuverModifier.LEFT
}
