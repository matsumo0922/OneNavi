package me.matsumo.onenavi.core.model

/**
 * マニューバ（案内地点）の種別。
 *
 * UI のアイコン選択および Callout の表示可否判定に使用する。
 * Google Navigation SDK / Google Routes API いずれの入力も、この共通 enum に射影して扱う。
 */
enum class ManeuverType {
    /** 一般的な曲がり角。 */
    TURN,

    /** 目的地到着。 */
    ARRIVE,

    /** そのまま進行（交差点名の通知など、方向転換を伴わない）。 */
    CONTINUE,

    /** 出発地点。 */
    DEPART,

    /** 突き当たり。 */
    END_OF_ROAD,

    /** 道路の分岐。 */
    FORK,

    /** 車線合流。 */
    MERGE,

    /** 道路名のみが変化する地点。 */
    NAME_CHANGE,

    /** 高速道路・有料道路への乗り口。 */
    ON_RAMP,

    /** 高速道路・有料道路の降り口。 */
    OFF_RAMP,

    /** ロータリー（英国圏）。 */
    ROTARY,

    /** ラウンドアバウト。 */
    ROUNDABOUT,

    /** トラフィックサークル（一般的な環状交差点）。 */
    TRAFFIC_CIRCLE,

    /** U ターン。 */
    UTURN,
}
