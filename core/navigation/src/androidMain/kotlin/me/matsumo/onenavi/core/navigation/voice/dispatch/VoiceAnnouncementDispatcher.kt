package me.matsumo.onenavi.core.navigation.voice.dispatch

/**
 * 確定した発話内容を実際の音声出力へ流す出口。TTS エンジン / MediaPlayer / AudioFocus 等の実装詳細を
 * scheduler から切り離すための抽象。
 *
 * [speak] は発話の完了 (または中断) まで suspend する契約とする。scheduler はこの suspend 完了をもって
 * 「発話が終わった」と扱い、直列消化キューの次段へ進む。barge-in 時は呼び出し側が coroutine を cancel し、
 * 進行中の発話を中断する。
 */
internal interface VoiceAnnouncementDispatcher {

    /**
     * 1 件の発話内容を再生する。再生完了まで suspend する。
     *
     * @param content category gate / 結合を適用済みの発話内容
     */
    suspend fun speak(content: VoiceAnnouncementContent)
}
