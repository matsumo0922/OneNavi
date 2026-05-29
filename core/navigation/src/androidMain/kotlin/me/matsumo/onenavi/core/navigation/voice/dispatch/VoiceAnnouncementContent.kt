package me.matsumo.onenavi.core.navigation.voice.dispatch

import androidx.compose.runtime.Immutable

/**
 * 実際に読み上げる 1 発話ぶんの確定テキスト。category gate と結合を適用し終えた後の発話内容。
 *
 * 距離段 ([me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage]) は素片 (pieces) を
 * 位置非依存で持つだけで、どの素片を鳴らすか・どう結合するかは発話直前に決まる。本型はその確定結果で、
 * dispatcher へ渡す唯一の発話入力になる。
 *
 * @property text 読み上げ用プレーンテキスト (フォールバックや fallback 表示にも使う)
 * @property ssml Google Cloud TTS 向けに変換済みの SSML。素片が SSML を持たない場合は null
 */
@Immutable
internal data class VoiceAnnouncementContent(
    val text: String,
    val ssml: String?,
)
