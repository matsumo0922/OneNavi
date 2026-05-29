package me.matsumo.onenavi.core.navigation.voice.plan

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * route 1 本ぶんの位置非依存な発話プラン。attach 時に確定し、tick ごとの発話済み状態は持たない。
 *
 * @property routeId 対応する payload の id
 * @property targets 全発話対象を [AnnouncementTarget.geometryMeters] 昇順で保持
 */
@Immutable
internal data class VoiceAnnouncementPlan(
    val routeId: String,
    val targets: ImmutableList<AnnouncementTarget>,
)
