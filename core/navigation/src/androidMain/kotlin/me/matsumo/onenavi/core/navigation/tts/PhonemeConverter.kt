package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.tts.PhonemeConverter.PAUSE

/**
 * 外部ナビ API 由来の SSML (Toshiba `x-toshiba-ruby` 付き) を、表示用プレーンテキストや
 * Google Cloud TTS が解釈できる SSML へ変換するユーティリティ。
 *
 * `x-toshiba-ruby` の `<phoneme>` は `ph` 属性に読み仮名、本文に表記を持つ独自拡張で、
 * そのままでは Google Cloud TTS が解釈できない。プレーンテキスト化では読み仮名を採用し、
 * Google Cloud SSML 化では読みを発音として確定させる `<phoneme alphabet="yomigana">` に書き換える。
 *
 * `<sub alias>` は alias を engine の通常 G2P に読ませる仕組みのため、辞書に無い読みがモーラ単位に
 * 分割されて誤読される。`yomigana` は読み仮名 (ひらがな/カタカナ) をそのまま発音として渡し、複合語の
 * 分割・長音処理も自動で行うため、読みを確実に反映できる。
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

    /** x-toshiba-ruby が 1 タグ内で複数の読みを区切るのに使う中黒。yomigana へは区切りごとに分割する。 */
    private const val MIDDLE_DOT = "・"

    /** 読みの区切りに挿入する読点。短いポーズとして読ませる。 */
    private const val PAUSE = "、"

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
     * `x-toshiba-ruby` の phoneme を `<phoneme alphabet="yomigana">` に書き換え、`<speak>` で囲む
     * (既に囲まれている場合は二重に囲まない)。他の alphabet の phoneme は変更しない。
     */
    fun toGoogleCloudSsml(ssml: String): String {
        val withYomigana = TOSHIBA_RUBY_PHONEME.replace(ssml, ::toYomiganaPhoneme)
        return wrapInSpeak(withYomigana)
    }

    /** toshiba-ruby phoneme を読み仮名 (空なら本文) に変換する。 */
    private fun toReadingText(match: MatchResult): String {
        val reading = match.groupValues[1]
        val body = match.groupValues[2]
        return reading.ifEmpty { body }
    }

    /**
     * toshiba-ruby phoneme を `<phoneme alphabet="yomigana">` に変換する。読みが空なら本文をそのまま返す。
     *
     * x-toshiba-ruby は 1 タグの `ph` に複数の読みを中黒区切りで詰める (例: `ph="がいかん・みさと"`)。
     * yomigana は「1 タグ = 1 読み」で `ph` に中黒を入れられない (誤読される) ため、中黒で区切られた
     * 読み・本文を個別の phoneme に分割し、区切りには読点 ([PAUSE]) を挟んでポーズとして読ませる。
     * 中黒が無ければ単一の phoneme になる。
     */
    private fun toYomiganaPhoneme(match: MatchResult): String {
        val reading = match.groupValues[1]
        val body = match.groupValues[2]
        if (reading.isEmpty()) return body

        val readingParts = reading.split(MIDDLE_DOT)
        val bodyParts = body.split(MIDDLE_DOT)
        return readingParts.indices.joinToString(separator = PAUSE) { index ->
            yomiganaTag(reading = readingParts[index], body = bodyParts.getOrElse(index) { "" })
        }
    }

    /** 単一の `<phoneme alphabet="yomigana">` を組み立てる。読みが空なら本文をそのまま返す。 */
    private fun yomiganaTag(reading: String, body: String): String {
        if (reading.isEmpty()) return body
        return "<phoneme alphabet=\"yomigana\" ph=\"$reading\">$body</phoneme>"
    }

    /** `<speak>` で囲む。既に先頭末尾が `<speak>` / `</speak>` なら何もしない。 */
    private fun wrapInSpeak(ssml: String): String {
        val alreadyWrapped = ssml.startsWith(SPEAK_OPEN) && ssml.endsWith(SPEAK_CLOSE)
        if (alreadyWrapped) return ssml
        return "$SPEAK_OPEN$ssml$SPEAK_CLOSE"
    }
}
