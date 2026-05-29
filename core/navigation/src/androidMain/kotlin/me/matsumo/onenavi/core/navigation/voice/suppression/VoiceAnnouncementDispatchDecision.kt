package me.matsumo.onenavi.core.navigation.voice.suppression

/**
 * 選ばれた発話候補を実際にどう扱うかの判断。scheduler はこの結果に従って発話開始 / 割り込み / キュー投入を行う。
 */
internal enum class VoiceAnnouncementDispatchDecision {

    /** 何も発話していないので、そのまま発話を開始する。 */
    PLAY,

    /** 発話中だが新候補の方が緊急なため、発話中を中断して割り込む。 */
    BARGE_IN,

    /** 発話中かつ新候補が緊急でないため、worker の直列消化キューに積んで後で発話する (破棄しない)。 */
    ENQUEUE,
}
