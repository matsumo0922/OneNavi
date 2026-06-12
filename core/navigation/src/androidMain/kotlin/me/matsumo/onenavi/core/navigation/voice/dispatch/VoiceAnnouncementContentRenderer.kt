package me.matsumo.onenavi.core.navigation.voice.dispatch

import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.tts.PhonemeConverter
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer.Companion.PAUSE
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage

/**
 * 距離段の発話素片を category gate に通し、読み上げる SSML に確定する。
 *
 * plan の段は素片を位置非依存で持つだけで、どの素片を鳴らすかは発話直前に決まる。本クラスは OFF にした
 * category の素片を除外し、残りを 1 本の SSML とローカル効果音指定に結合する。発話する内容が無ければ null を
 * 返し、scheduler は発話を起こさずに段を処理済みとして畳む。
 *
 * TTS に渡す部分は常に SSML で行う (SSML を持たない素片は text を escape して取り込む)。素片どうしは読点
 * ([PAUSE]) で繋ぐ。素片は API が意味単位ごとに分割して送る区切りなので、その境界に文中ポーズを置くことで
 * TTS が全文を一息で繋げて読むのを防ぐ。`<break>` は文末扱いになりイントネーションが切れるため使わず、
 * 文中ポーズの読点を使う。前の素片が既に句読点で終わっている場合は重複させない (「。、」「、、」を避ける)。
 */
internal class VoiceAnnouncementContentRenderer(
    private val categoryGateProvider: () -> VoiceAnnouncementCategoryGate,
) {

    /** 固定 gate で生成する。テスト・静的構成用。 */
    constructor(categoryGate: VoiceAnnouncementCategoryGate) : this({ categoryGate })

    /**
     * 距離段を読み上げ内容に変換する。発話すべき素片が無い (全 OFF) 場合は null。
     *
     * @param stage 発話対象の距離段
     * @return TTS 用 SSML とローカル効果音指定。発話するものが無ければ null
     */
    fun render(stage: AnnouncementStage): VoiceAnnouncementContent? {
        val categoryGate = categoryGateProvider()
        val enabledPieces = stage.pieces.filter { piece -> categoryGate.isEnabled(piece.category) }
        if (enabledPieces.isEmpty()) return null

        val fragments = enabledPieces
            .map { piece -> ssmlFragmentOf(piece) }
            .filter { fragment -> fragment.isNotBlank() }
        val rawSsml = fragments
            .reduceOrNull { joined, fragment -> joined + separatorBefore(fragment, previous = joined) + fragment }
        val ssml = rawSsml?.let(PhonemeConverter::toGoogleCloudSsml)
        val displayText = enabledPieces
            .map { piece -> displayTextOf(piece) }
            .filter { text -> text.isNotBlank() }
            .reduceOrNull { joined, text -> joined + separatorBefore(text, previous = joined) + text }
            .orEmpty()
        val cue = if (enabledPieces.any { piece -> piece.hasChimeCue() }) VoiceAnnouncementCue.CHIME else null
        val content = VoiceAnnouncementContent(
            ssml = ssml,
            cue = cue,
            displayText = displayText,
        )

        return content.takeIf { it.hasOutput }
    }

    /** 直前の素片が句読点で終わっていれば追加のポーズは挟まず、そうでなければ読点を挟む。 */
    private fun separatorBefore(fragment: String, previous: String): String {
        if (fragment.isEmpty()) return ""

        val lastChar = previous.trimEnd().lastOrNull()
        return if (lastChar in PAUSE_PUNCTUATION) "" else PAUSE
    }

    /** 素片を SSML 断片にする。SSML を持つ素片はそのまま、持たない素片は text を escape して取り込む。 */
    private fun ssmlFragmentOf(piece: GuideAnnouncementPiece): String =
        piece.ssml?.removeChimeText() ?: escapeSsmlText(piece.text.removeChimeText())

    /** デバッグ表示用に素片の plain text を返す。 */
    private fun displayTextOf(piece: GuideAnnouncementPiece): String =
        piece.text.removeChimeText()

    /** 素片にローカル効果音へ差し替えるチャイム表現が含まれるか。 */
    private fun GuideAnnouncementPiece.hasChimeCue(): Boolean =
        text.contains(CHIME_TEXT) || ssml?.contains(CHIME_TEXT) == true

    /** TTS に読ませないチャイム表現を取り除く。 */
    private fun String.removeChimeText(): String =
        if (contains(CHIME_TEXT)) replace(CHIME_TEXT, "").trimStart() else this

    /** SSML 本文に直接埋め込めるよう、最低限の XML 特殊文字 (`&` `<` `>`) を escape する。 */
    private fun escapeSsmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {

        /**
         * 素片 (piece) 間に挟む文中ポーズ。API が意味単位 (ポーン / 距離 / 方向 / 地点名 / 述語 など) ごとに
         * 分割して送る piece の境界に置き、TTS が全文を一息で繋げて読むのを防ぐ。読点は文中ポーズなので
         * イントネーションは 1 文として連続する (`<break>` のような文末区切りにならない)。
         */
        const val PAUSE = "、"

        /** 外部データ上で案内前チャイムを表す文字列。 */
        const val CHIME_TEXT = "ポーン"

        /** 既にポーズになっている句読点。直前がこれらで終わるなら読点を重ねない。 */
        val PAUSE_PUNCTUATION = setOf('、', '。', '，', '．', '！', '？', '!', '?')
    }
}
