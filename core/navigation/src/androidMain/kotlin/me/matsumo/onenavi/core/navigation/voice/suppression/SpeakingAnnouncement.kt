package me.matsumo.onenavi.core.navigation.voice.suppression

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency

/**
 * 現在発話中の段の最小情報。barge-in 判定で発話中の緊急度と新候補の緊急度を比較するために保持する。
 *
 * @property stageId 発話中の段の id
 * @property urgency 発話開始時点の緊急度
 */
@Immutable
internal data class SpeakingAnnouncement(
    val stageId: VoiceAnnouncementId,
    val urgency: VoiceAnnouncementUrgency,
)
