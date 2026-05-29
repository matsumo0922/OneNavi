package me.matsumo.onenavi.core.navigation.voice.plan

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 1 案内地点 (GP) に対応する発話対象。同一地点を異なる手前距離で予告する距離段をまとめる。
 *
 * @property guidancePointIndex 対応する GP の index
 * @property geometryMeters GP の通過位置 (geometry 累積距離 m)
 * @property stages 緊急度昇順 (= [AnnouncementStage.triggerSourceMeters] 昇順) で並んだ距離段。
 *   最も緊急な段 (FINAL) が末尾に来る
 */
@Immutable
internal data class AnnouncementTarget(
    val guidancePointIndex: Int,
    val geometryMeters: Double,
    val stages: ImmutableList<AnnouncementStage>,
)
