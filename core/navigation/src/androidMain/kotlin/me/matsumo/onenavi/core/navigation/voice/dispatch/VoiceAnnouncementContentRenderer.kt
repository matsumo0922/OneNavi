package me.matsumo.onenavi.core.navigation.voice.dispatch

import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.tts.PhonemeConverter
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage

/**
 * 距離段の発話素片を category gate に通し、読み上げる発話内容 ([VoiceAnnouncementContent]) に確定する。
 *
 * plan の段は素片を位置非依存で持つだけで、どの素片を鳴らすかは発話直前に決まる。本クラスは OFF にした
 * category の素片を除外し、残りの text / SSML を結合する。発話する内容が無ければ null を返し、scheduler は
 * 発話を起こさずに段を処理済みとして畳む。
 */
internal class VoiceAnnouncementContentRenderer(
    private val categoryGate: VoiceAnnouncementCategoryGate,
) {

    /**
     * 距離段を発話内容に変換する。発話すべき素片が無い (全 OFF / 空文) 場合は null。
     *
     * @param stage 発話対象の距離段
     * @return category gate 適用後の発話内容。発話するものが無ければ null
     */
    fun render(stage: AnnouncementStage): VoiceAnnouncementContent? {
        val enabledPieces = stage.pieces.filter { piece -> categoryGate.isEnabled(piece.category) }
        val text = joinText(enabledPieces)
        if (text.isBlank()) return null

        return VoiceAnnouncementContent(
            text = text,
            ssml = buildSsml(enabledPieces),
        )
    }

    /** 有効な素片のプレーンテキストを順に結合する。 */
    private fun joinText(pieces: List<GuideAnnouncementPiece>): String =
        pieces.joinToString(separator = "") { piece -> piece.text }

    /**
     * 有効な素片を結合し、Google Cloud TTS 向けの SSML へ変換する。
     *
     * SSML を持つ素片が 1 つも無ければ null を返し、dispatcher 側にプレーンテキストでの読み上げを委ねる。
     * SSML 素片が 1 つでもあれば、SSML を持たない素片はその text を escape して取り込む。SSML だけを
     * 結合すると、混在時に plain text 素片 (例:「300m先」) が SSML 経路から欠落して読み上げられないため。
     */
    private fun buildSsml(pieces: List<GuideAnnouncementPiece>): String? {
        val hasAnySsml = pieces.any { piece -> piece.ssml != null }
        if (!hasAnySsml) return null

        val rawSsml = pieces.joinToString(separator = "") { piece -> ssmlFragmentOf(piece) }

        return PhonemeConverter.toGoogleCloudSsml(rawSsml)
    }

    /** 素片の SSML を取り出す。SSML を持たない素片は text を escape したものを使う。 */
    private fun ssmlFragmentOf(piece: GuideAnnouncementPiece): String =
        piece.ssml ?: escapeSsmlText(piece.text)

    /** SSML 本文に直接埋め込めるよう、最低限の XML 特殊文字 (`&` `<` `>`) を escape する。 */
    private fun escapeSsmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
