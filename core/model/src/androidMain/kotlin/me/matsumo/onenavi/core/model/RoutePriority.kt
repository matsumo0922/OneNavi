package me.matsumo.onenavi.core.model

/**
 * ルート種別。複数候補ルートの並び（推奨 / 渋滞回避 / 高速優先 等）を
 * UI とロジックで区別するための中立 enum。取得元の DataSource に依存しない。
 *
 * @param label 表示用ラベル
 */
enum class RoutePriority(val label: String) {
    /** 推奨 */
    Recommended("推奨"),

    /** AI ルート */
    AiRoute("AI"),

    /** 一般道優先（高速を使わない） */
    Free("一般道優先"),

    /** 高速優先 */
    Express("高速優先"),

    /** 距離優先 */
    Distance("距離優先"),

    /** 渋滞回避 */
    AvoidCongestion("渋滞回避"),

    /** 燃費優先 */
    EcoPriority("燃費優先"),

    /** 景観優先 */
    Scenic("景観優先"),

    /** 一般道距離優先 */
    FreeDistance("一般道距離優先"),

    /** 都市高速優先 */
    UrbanExpress("都市高速優先"),

    /** 都市高速回避 */
    AvoidUrbanExpress("都市高速回避"),

    /** 第 2 推奨 */
    SecondRecommended("第 2 推奨"),
}
