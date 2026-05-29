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
     * 有効な素片の SSML を結合し、Google Cloud TTS 向けへ変換する。
     *
     * SSML を 1 つも持たない場合は null を返し、dispatcher 側にプレーンテキストでの読み上げを委ねる。
     */
    private fun buildSsml(pieces: List<GuideAnnouncementPiece>): String? {
        val rawSsml = pieces
            .mapNotNull { piece -> piece.ssml }
            .joinToString(separator = "")
        if (rawSsml.isBlank()) return null

        return PhonemeConverter.toGoogleCloudSsml(rawSsml)
    }
}
