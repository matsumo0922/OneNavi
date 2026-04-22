package me.matsumo.onenavi.core.navigation.tts

/**
 * drive-supporter-api が返す SSML 内の独自 phoneme 拡張を、TTS エンジンで読める形に変換する。
 *
 * 入力例:
 * ```
 * 直進方向、<phoneme alphabet="x-toshiba-ruby" ph="しゅとこう">首都高</phoneme>方面です。
 * ```
 *
 * - [toGoogleCloudSsml] は W3C SSML 互換 (`<speak>` + `<phoneme alphabet="x-amazon-pron-kana">`) に変換する。
 *   Google Cloud TTS は独自 `x-toshiba-ruby` を受け付けないが、読み (`ph` 属性のかな) を sub alias として利用する。
 * - [toPlainText] は phoneme タグを剥がし、読み仮名を本文に置換する。Android 内蔵 TTS はこちらを使う。
 */
object PhonemeConverter {

    private val PHONEME_REGEX = Regex(
        pattern = "<phoneme\\s+alphabet=\"([^\"]+)\"\\s+ph=\"([^\"]*)\"\\s*>([\\s\\S]*?)</phoneme>",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    /**
     * Google Cloud TTS 向けに SSML 全体を整形する。
     *
     * - `<speak>` ルートが無ければ付与
     * - `alphabet="x-toshiba-ruby"` は `<sub alias="{ph}">{original}</sub>` に変換
     *   (読みが確実に採用される。Chirp 3 HD は IPA の kana alphabet を直接サポートしていない)
     * - その他の alphabet はタグを残す
     */
    fun toGoogleCloudSsml(raw: String): String {
        val sanitized = raw.trim()
        val withoutPhonemes = PHONEME_REGEX.replace(sanitized) { match ->
            val alphabet = match.groupValues[1].lowercase()
            val ph = match.groupValues[2]
            val body = match.groupValues[3]
            if (alphabet == "x-toshiba-ruby" && ph.isNotEmpty()) {
                "<sub alias=\"${escapeXml(ph)}\">${body}</sub>"
            } else {
                match.value
            }
        }
        return if (withoutPhonemes.startsWith("<speak", ignoreCase = true)) {
            withoutPhonemes
        } else {
            "<speak>$withoutPhonemes</speak>"
        }
    }

    /**
     * Android 内蔵 TTS 向けに、タグを剥がした素のテキストを返す。
     * 読み仮名が付いていた場合は本文ではなく読み仮名側を採用する (通称・固有名詞の誤読を減らす)。
     */
    fun toPlainText(raw: String): String {
        val replaced = PHONEME_REGEX.replace(raw) { match ->
            val ph = match.groupValues[2]
            val body = match.groupValues[3]
            ph.ifEmpty { body }
        }
        return replaced.replace(OTHER_TAGS_REGEX, "").trim()
    }

    private val OTHER_TAGS_REGEX = Regex("<[^>]+>")

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
