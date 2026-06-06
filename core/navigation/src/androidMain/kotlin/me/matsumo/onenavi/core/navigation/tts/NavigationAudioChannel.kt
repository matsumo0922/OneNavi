package me.matsumo.onenavi.core.navigation.tts

import android.media.AudioAttributes

/**
 * 案内音声を出力する音声チャンネル([AudioAttributes] の usage)。
 *
 * @property usage [AudioAttributes.Builder.setUsage] に渡す usage 値
 */
internal enum class NavigationAudioChannel(val usage: Int) {

    /** ナビ案内チャンネル。通常時はこちらで再生する。 */
    Guidance(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),

    /** メディアチャンネル。車種により高音質化処理が乗るため Android Auto 接続中のみ選択する。 */
    Media(AudioAttributes.USAGE_MEDIA),
}
