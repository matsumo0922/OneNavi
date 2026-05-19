package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable

/**
 * 音声再生キューへ渡す 1 発話ぶんの情報。
 *
 * @param guidancePointIndex 元の案内ポイント index
 * @param phraseIndex 案内ポイント内の phrase index
 * @param categoryKey phrase category から作る安定キー
 * @param text 発話文言
 * @param ssml SSML 文言
 */
@Immutable
internal data class ExtNavAnnouncement(
    val guidancePointIndex: Int,
    val phraseIndex: Int,
    val categoryKey: String,
    val text: String,
    val ssml: String?,
)
