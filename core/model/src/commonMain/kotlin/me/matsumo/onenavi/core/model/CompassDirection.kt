package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 出発案内用の方角。
 *
 * Google Navigation SDK の `DEPART` ステップ `instruction`（例:「北に進む」「西に進みます」）から
 * [parse] で抽出し、[PhraseComposer] で「〇〇方向に進みます。」の固定フレーズに解決する。
 *
 * @property keyword SDK テキスト内で照合する日本語キーワード。
 */
@Immutable
enum class CompassDirection(val keyword: String) {
    /** 北東（「北」より先に照合するため上位に配置）。 */
    NORTHEAST("北東"),

    /** 北西。 */
    NORTHWEST("北西"),

    /** 南東。 */
    SOUTHEAST("南東"),

    /** 南西。 */
    SOUTHWEST("南西"),

    /** 北。 */
    NORTH("北"),

    /** 南。 */
    SOUTH("南"),

    /** 東。 */
    EAST("東"),

    /** 西。 */
    WEST("西"),
    ;

    companion object {
        /**
         * 自然文から方角を抽出する。2 文字の複合方位（北東等）を単体方位（北等）より優先して照合する。
         *
         * 該当が無ければ null。
         */
        fun parse(text: String): CompassDirection? {
            return entries.firstOrNull { direction -> text.contains(direction.keyword) }
        }
    }
}
