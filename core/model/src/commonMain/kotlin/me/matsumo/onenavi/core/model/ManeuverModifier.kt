package me.matsumo.onenavi.core.model

/**
 * マニューバの方向修飾子。
 *
 * UI のアイコン選択と TTS 発話の方向判定の双方で使用する。
 * 方向を持たないマニューバ（出発・到着など）ではフィールド側で null を用いる。
 */
enum class ManeuverModifier {
    /** 左折。 */
    LEFT,

    /** 右折。 */
    RIGHT,

    /** やや左（slight left）。 */
    SLIGHT_LEFT,

    /** やや右（slight right）。 */
    SLIGHT_RIGHT,

    /** 鋭角左折（sharp left）。 */
    SHARP_LEFT,

    /** 鋭角右折（sharp right）。 */
    SHARP_RIGHT,

    /** 直進。 */
    STRAIGHT,

    /** U ターン。 */
    UTURN,

    /** 修飾子は存在するが SDK が解釈できなかったケースのフォールバック。 */
    UNKNOWN,
}
