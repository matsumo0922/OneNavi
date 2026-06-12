package me.matsumo.onenavi.core.navigation.voice.dispatch

import androidx.compose.runtime.Immutable

/**
 * 発話 1 件で再生する内容。
 *
 * TTS に渡す SSML と、TTS では読ませずローカル音源で鳴らす案内効果音をまとめて扱う。
 * scheduler は [dedupeKey] を使い、同一案内地点で同じ内容を二重に発話しないようにする。
 *
 * @property ssml TTS に渡す SSML。効果音だけを鳴らす場合は null
 * @property cue TTS 前に鳴らす案内効果音。効果音が無い場合は null
 * @property displayText デバッグ表示で読むための plain text
 */
@Immutable
internal data class VoiceAnnouncementContent(
    val ssml: String?,
    val cue: VoiceAnnouncementCue?,
    val displayText: String = ssml.orEmpty(),
) {

    /** 同一内容の二重発話を防ぐための安定キー。 */
    val dedupeKey: String
        get() = "cue=${cue?.name.orEmpty()}|ssml=${ssml.orEmpty()}"

    /** 再生する効果音または発話があるか。 */
    val hasOutput: Boolean
        get() = cue != null || !ssml.isNullOrBlank()
}

/**
 * TTS ではなくローカル音源で鳴らす案内効果音。
 */
internal enum class VoiceAnnouncementCue {

    /** 案内文の前に鳴らす短いチャイム。 */
    CHIME,
}
