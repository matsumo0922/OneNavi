package me.matsumo.onenavi.core.navigation.voice.plan

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece

/**
 * 距離段の種別。
 */
internal enum class AnnouncementStageKind {

    /** 中間段。距離トリガで発話し、より緊急な段に追い越されたらスキップしてよい。 */
    MIDDLE,

    /** 直前段。到達リードタイム逆算で発話し、barge-in してでも発話を保証する。 */
    FINAL,
}

/**
 * 1 案内地点に対する 1 つの距離段 (= 1 [GuideAnnouncementBlock] 由来、または距離 override で複製した 1 段)。
 *
 * 発話トリガの距離は source 系 / geometry 系を分けて持つ。MIDDLE 段は [triggerGeometryMeters]
 * の越え判定で発話し、FINAL 段は実行時に到達リードタイム逆算で発話するため
 * [triggerGeometryMeters] は debug / 検証用にとどまる。
 *
 * @property id route 寿命内で一意な安定キー
 * @property kind 距離段の種別 (MIDDLE / FINAL)
 * @property triggerSourceMeters 外部データ source 距離上で発話トリガされる累積距離 (m)
 * @property triggerGeometryMeters route geometry 上で発話トリガされる累積距離 (m)。source→geometry 変換済み
 * @property pieces 発話素片。category gate / 結合は発話 dispatch 直前に適用する
 * @property categories この段が属する block の category 群
 */
@Immutable
internal data class AnnouncementStage(
    val id: VoiceAnnouncementId,
    val kind: AnnouncementStageKind,
    val triggerSourceMeters: Double,
    val triggerGeometryMeters: Double,
    val pieces: ImmutableList<GuideAnnouncementPiece>,
    val categories: ImmutableSet<GuidanceCategory>,
)
