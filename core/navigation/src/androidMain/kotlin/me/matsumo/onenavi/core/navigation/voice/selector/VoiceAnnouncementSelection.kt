package me.matsumo.onenavi.core.navigation.voice.selector

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage

/**
 * ある tick で発話すべきと判定された 1 件の距離段。実際に発話するか (barge-in / skip) の最終判断は
 * suppression レイヤが本選択を入力に行う。
 *
 * @property targetIndex 対象 [me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget] の plan 内 index
 * @property stage 発話する距離段
 * @property urgency この発話の緊急度。発話中の段との barge-in 比較に使う
 */
@Immutable
internal data class VoiceAnnouncementSelection(
    val targetIndex: Int,
    val stage: AnnouncementStage,
    val urgency: VoiceAnnouncementUrgency,
)
