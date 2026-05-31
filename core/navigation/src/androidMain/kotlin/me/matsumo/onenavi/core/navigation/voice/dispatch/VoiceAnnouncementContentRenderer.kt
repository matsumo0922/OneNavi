package me.matsumo.onenavi.core.navigation.voice.dispatch

import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.tts.PhonemeConverter
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer.Companion.PIECE_BREAK
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage

/**
 * 距離段の発話素片を category gate に通し、読み上げる SSML に確定する。
 *
 * plan の段は素片を位置非依存で持つだけで、どの素片を鳴らすかは発話直前に決まる。本クラスは OFF にした
 * category の素片を除外し、残りを 1 本の SSML に結合する。発話する内容が無ければ null を返し、scheduler は
 * 発話を起こさずに段を処理済みとして畳む。
 *
 * 発話は常に SSML で行う (SSML を持たない素片は text を escape して取り込む)。素片どうしは [PIECE_BREAK]
 * (ポーズ) で繋ぐ。素片は API が意味単位ごとに分割して送る区切りなので、その境界で間を空けることで
 * TTS が全文を一息で繋げて読むのを防ぐ。
 */
internal class VoiceAnnouncementContentRenderer(
    private val categoryGate: VoiceAnnouncementCategoryGate,
) {

    /**
     * 距離段を読み上げ用の SSML に変換する。発話すべき素片が無い (全 OFF) 場合は null。
     *
     * @param stage 発話対象の距離段
     * @return `<speak>` で囲んだ Google Cloud TTS 向け SSML。発話するものが無ければ null
     */
    fun render(stage: AnnouncementStage): String? {
        val enabledPieces = stage.pieces.filter { piece -> categoryGate.isEnabled(piece.category) }
        if (enabledPieces.isEmpty()) return null

        val rawSsml = enabledPieces.joinToString(separator = PIECE_BREAK) { piece -> ssmlFragmentOf(piece) }

        return PhonemeConverter.toGoogleCloudSsml(rawSsml)
    }

    /** 素片を SSML 断片にする。SSML を持つ素片はそのまま、持たない素片は text を escape して取り込む。 */
    private fun ssmlFragmentOf(piece: GuideAnnouncementPiece): String =
        piece.ssml ?: escapeSsmlText(piece.text)

    /** SSML 本文に直接埋め込めるよう、最低限の XML 特殊文字 (`&` `<` `>`) を escape する。 */
    private fun escapeSsmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {

        /**
         * 素片 (piece) 間に挟むポーズ。API が意味単位 (ポーン / 距離 / 方向 / 地点名 / 述語 など) ごとに
         * 分割して送る piece の境界で間を空け、TTS が全文を一息で繋げて読むのを防ぐ。piece 内部
         * (例: 施設名 + 述語) は API が分割しないため、ここでは区切らない。
         */
        const val PIECE_BREAK = "<break time=\"100ms\"/>"
    }
}
