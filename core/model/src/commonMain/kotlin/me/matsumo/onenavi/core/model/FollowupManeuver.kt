package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 連続案内（followup）の予告内容。
 *
 * 主ターンの [GuidanceEvent.Maneuver] 発話に続けて「その先、およそ XXm で〇〇方向です。」と
 * 次ステップのターン予告を載せるための値オブジェクト。
 *
 * v1 では方向を持つマニューバ（`modifier != null`）のみ対応。分岐・合流 (FORK / MERGE) や
 * ランプ系は followup に載せない方針（分岐/合流は直前まで待って主フレーズで案内する）。
 *
 * @property distanceBucket 主ターンから次ターンまでの距離をスナップしたバケット。
 * @property maneuverType 次ターンの種別。
 * @property modifier 次ターンの方向修飾子。null の場合は followup そのものを生成しない。
 */
@Immutable
data class FollowupManeuver(
    val distanceBucket: FollowupDistanceBucket,
    val maneuverType: ManeuverType,
    val modifier: ManeuverModifier?,
)
