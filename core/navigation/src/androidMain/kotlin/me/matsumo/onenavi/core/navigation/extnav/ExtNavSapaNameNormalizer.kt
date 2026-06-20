package me.matsumo.onenavi.core.navigation.extnav

/**
 * SA/PA 名を API 間の照合に使うための正規化 helper。
 */
internal object ExtNavSapaNameNormalizer {

    /** 全角 ASCII の Unicode 範囲。 */
    private val FULL_WIDTH_ASCII_RANGE: IntRange = 0xFF01..0xFF5E

    /** 全角 ASCII を半角 ASCII に戻すための差分。 */
    private const val FULL_WIDTH_ASCII_OFFSET: Int = 0xFEE0

    /**
     * SA/PA 名を空白差分と全角 ASCII 差分に強い key へ変換する。
     */
    fun normalize(name: String): String =
        name.trim()
            .map { character -> character.toHalfWidthAscii() }
            .joinToString(separator = "")
            .replace(" ", "")
            .replace("　", "")
            .uppercase()

    /**
     * 片方が路線名や上下線を含む場合も同一候補として扱う。
     */
    fun matches(left: String, right: String): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft.isBlank()) return false
        if (normalizedRight.isBlank()) return false

        val isExactMatch = normalizedLeft == normalizedRight
        val containsRight = normalizedLeft.contains(normalizedRight)
        val containsLeft = normalizedRight.contains(normalizedLeft)

        return isExactMatch || containsRight || containsLeft
    }

    private fun Char.toHalfWidthAscii(): Char {
        val unicodeCode = code
        if (unicodeCode !in FULL_WIDTH_ASCII_RANGE) return this

        return (unicodeCode - FULL_WIDTH_ASCII_OFFSET).toChar()
    }
}
