package me.matsumo.onenavi.core.navigation.tts

/**
 * 外部ナビ API 由来の SSML (Toshiba `x-toshiba-ruby` 付き) を、表示用プレーンテキストや
 * Google Cloud TTS が解釈できる SSML へ変換するユーティリティ。
 *
 * `x-toshiba-ruby` の `<phoneme>` は `ph` 属性に読み仮名、本文に表記を持つ独自拡張で、
 * そのままでは Google Cloud TTS が解釈できない。プレーンテキスト化では読み仮名を採用し、
 * Google Cloud SSML 化では `<sub alias>` に書き換える。
 */
object PhonemeConverter {

    /** `x-toshiba-ruby` の phoneme タグ。group1 = 読み仮名 (`ph`)、group2 = 本文 (表記)。 */
    private val TOSHIBA_RUBY_PHONEME = Regex(
        "<phoneme\\s+alphabet=\"x-toshiba-ruby\"\\s+ph=\"([^\"]*)\">(.*?)</phoneme>",
    )

    /** 任意の XML/SSML タグ。プレーンテキスト化で残ったタグを除去するのに使う。 */
    private val ANY_TAG = Regex("<[^>]+>")

    private const val SPEAK_OPEN = "<speak>"
    private const val SPEAK_CLOSE = "</speak>"

    /**
     * SSML から読み上げ用のプレーンテキストを得る。
     *
     * `x-toshiba-ruby` の phoneme は読み仮名 (`ph`) に置換し、`ph` が空なら本文で代替する。
     * その後、残る全タグを除去する。
     */
    fun toPlainText(ssml: String): String {
        val withReadings = TOSHIBA_RUBY_PHONEME.replace(ssml, ::toReadingText)
        return ANY_TAG.replace(withReadings, "")
    }

    /**
     * SSML を Google Cloud TTS 向けの SSML へ変換する。
     *
     * `x-toshiba-ruby` の phoneme を `<sub alias>` に書き換え、`<speak>` で囲む
     * (既に囲まれている場合は二重に囲まない)。他の alphabet の phoneme は変更しない。
     */
    fun toGoogleCloudSsml(ssml: String): String {
        val withSubTags = TOSHIBA_RUBY_PHONEME.replace(ssml, ::toSubTag)
        return wrapInSpeak(withSubTags)
    }

    /** toshiba-ruby phoneme を読み仮名 (空なら本文) に変換する。 */
    private fun toReadingText(match: MatchResult): String {
        val reading = match.groupValues[1]
        val body = match.groupValues[2]
        return reading.ifEmpty { body }
    }

    /** toshiba-ruby phoneme を `<sub alias>` に変換する。読み仮名が空なら本文をそのまま返す。 */
    private fun toSubTag(match: MatchResult): String {
        val reading = match.groupValues[1]
        val body = match.groupValues[2]
        if (reading.isEmpty()) return body
        return "<sub alias=\"$reading\">$body</sub>"
    }

    /** `<speak>` で囲む。既に先頭末尾が `<speak>` / `</speak>` なら何もしない。 */
    private fun wrapInSpeak(ssml: String): String {
        val alreadyWrapped = ssml.startsWith(SPEAK_OPEN) && ssml.endsWith(SPEAK_CLOSE)
        if (alreadyWrapped) return ssml
        return "$SPEAK_OPEN$ssml$SPEAK_CLOSE"
    }
}
