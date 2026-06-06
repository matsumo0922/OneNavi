package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.repository.AppSettingRepository

/**
 * 発話ごとに使用する [NavigationAudioChannel] を決定する。
 *
 * メディア枠再生は「設定が ON」かつ「Android Auto 接続中 ([CarDisplayState])」のときのみ有効化し、
 * スマホ単体利用時は常に案内チャンネルを使う。
 *
 * @property appSettingRepository メディア枠再生フラグの参照元
 */
internal class NavigationAudioChannelResolver(
    private val appSettingRepository: AppSettingRepository,
) {

    /** 現在の設定と車載状態から出力チャンネルを決定する。 */
    fun resolve(): NavigationAudioChannel {
        val prefersMediaChannel = appSettingRepository.setting.value.useMediaAudioChannelOnCar
        val shouldUseMediaChannel = prefersMediaChannel && CarDisplayState.isOnCar
        return if (shouldUseMediaChannel) NavigationAudioChannel.Media else NavigationAudioChannel.Guidance
    }
}
