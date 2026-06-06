package me.matsumo.onenavi.core.navigation.voice.config

import me.matsumo.onenavi.core.repository.AppSettingRepository

/**
 * 発話直前に、現在の設定から [VoiceAnnouncementCategoryGate] を組み立てる。
 *
 * gate を起動時に固定すると設定画面での変更が即座に反映されないため、発話ごとに最新の
 * [AppSettingRepository] の値を読んで gate を作り直す。
 *
 * @property appSettingRepository OFF カテゴリ集合の参照元
 */
internal class VoiceAnnouncementCategoryGateResolver(
    private val appSettingRepository: AppSettingRepository,
) {

    /** 現在の設定値から category gate を組み立てる。 */
    fun resolve(): VoiceAnnouncementCategoryGate {
        val disabledCategories = appSettingRepository.setting.value.disabledGuidanceCategories
        return VoiceAnnouncementCategoryGate.ofDisabledNames(disabledCategories)
    }
}
