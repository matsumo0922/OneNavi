package me.matsumo.onenavi.core.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import me.matsumo.onenavi.core.common.serializer.ColorSerializer

/**
 * アプリ全体で共有するユーザー設定。
 *
 * @property id アプリ内で利用するユーザー識別子
 * @property theme 画面テーマ
 * @property useDynamicColor Dynamic Color を利用するか
 * @property seedColor アプリテーマの基準色
 * @property plusMode Plus 機能が有効か
 * @property developerMode 開発者向けオプションが解放されているか
 * @property enabledDeveloperFeatures 有効化されている開発者向け機能
 * @property useMediaAudioChannelOnCar Android Auto で音声案内をメディア音量として再生するか
 * @property ttsVolumeGainDb Google Cloud TTS に渡す音量ゲイン。0dB が標準、正の値で増幅する。
 * @property ttsVoiceNameOverride Google Cloud TTS に渡す voice 名の開発者向け上書き値。空文字なら既定値を使う。
 * @property ttsSpeakingRateOverride Google Cloud TTS に渡す話速の開発者向け上書き値。1.0 が標準。
 * @property disabledGuidanceCategories 発話を OFF にした案内カテゴリの識別子集合
 * @property extNavDeviceUuid 外部ナビ API ライブラリ向けの端末識別子
 */
@Serializable
data class AppSetting(
    val id: String,
    val theme: Theme,
    val useDynamicColor: Boolean,
    @Serializable(with = ColorSerializer::class)
    val seedColor: Color,
    val plusMode: Boolean,
    val developerMode: Boolean,
    val enabledDeveloperFeatures: Set<DeveloperFeature>,
    val useMediaAudioChannelOnCar: Boolean,
    /** Google Cloud TTS に渡す音量ゲイン。0dB が標準、正の値で増幅する。 */
    val ttsVolumeGainDb: Double,
    /** Google Cloud TTS に渡す voice 名の開発者向け上書き値。空文字なら既定値を使う。 */
    val ttsVoiceNameOverride: String,
    /** Google Cloud TTS に渡す話速の開発者向け上書き値。1.0 が標準。 */
    val ttsSpeakingRateOverride: Double,
    /** 発話を OFF にした案内カテゴリの識別子集合 (外部ナビ API の category 名)。未登録カテゴリは ON 扱い。 */
    val disabledGuidanceCategories: Set<String>,
    val extNavDeviceUuid: String,
) {
    val hasPrivilege get() = plusMode || isDeveloperFeatureEnabled(DeveloperFeature.FORCE_PLUS_PRIVILEGE)

    fun isDeveloperFeatureEnabled(feature: DeveloperFeature): Boolean {
        if (!developerMode) return false

        return feature in enabledDeveloperFeatures
    }

    /** アプリ設定の既定値と UI 制約値。 */
    companion object {
        /** TTS 音量ゲインの既定値。 */
        const val TTS_VOLUME_GAIN_DB_DEFAULT = 0.0

        /** 設定 UI で選べる TTS 音量ゲインの最小値。 */
        const val TTS_VOLUME_GAIN_DB_MIN = -10.0

        /** Google Cloud TTS が強く非推奨としない範囲での推奨最大値。 */
        const val TTS_VOLUME_GAIN_DB_MAX = 10.0

        /** TTS 音量ゲイン slider のステップ数。両端を除く 1dB 刻みの目盛り数。 */
        const val TTS_VOLUME_GAIN_DB_STEPS = 19

        /** TTS voice 名上書きの既定値。空文字なら合成側の既定 voice 名を使う。 */
        const val TTS_VOICE_NAME_OVERRIDE_DEFAULT = ""

        /** TTS 話速上書きの既定値。 */
        const val TTS_SPEAKING_RATE_OVERRIDE_DEFAULT = 1.0

        /** 設定 UI で選べる TTS 話速上書きの最小値。 */
        const val TTS_SPEAKING_RATE_OVERRIDE_MIN = 0.25

        /** 設定 UI で選べる TTS 話速上書きの最大値。 */
        const val TTS_SPEAKING_RATE_OVERRIDE_MAX = 2.0

        /** TTS 話速上書き slider のステップ数。両端を除く 0.05 刻みの目盛り数。 */
        const val TTS_SPEAKING_RATE_OVERRIDE_STEPS = 34

        /** アプリ設定の既定値。 */
        val DEFAULT = AppSetting(
            id = "",
            theme = Theme.System,
            useDynamicColor = currentPlatform == Platform.Android,
            seedColor = Color(0xFF7FD0FF),
            plusMode = false,
            developerMode = false,
            enabledDeveloperFeatures = emptySet(),
            useMediaAudioChannelOnCar = false,
            ttsVolumeGainDb = TTS_VOLUME_GAIN_DB_DEFAULT,
            ttsVoiceNameOverride = TTS_VOICE_NAME_OVERRIDE_DEFAULT,
            ttsSpeakingRateOverride = TTS_SPEAKING_RATE_OVERRIDE_DEFAULT,
            // 走行に必須でない既定 OFF カテゴリ。VoiceAnnouncementCategoryGate.OneNaviDefault と揃えること。
            disabledGuidanceCategories = setOf("Curve", "Scenic", "AccidentBlackSpot", "Merge"),
            extNavDeviceUuid = "",
        )
    }
}
