package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
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
 * @property isSpeedAdaptiveTtsGainEnabled 速度に連動して再生時の追加ゲインを掛けるか
 * @property speedAdaptiveTtsGainMaxDb 速度連動ゲインが上限速度で掛ける最大追加ゲイン
 * @property disabledGuidanceCategories 発話を OFF にした案内カテゴリの識別子集合
 * @property extNavDeviceUuid 外部ナビ API ライブラリ向けの端末識別子
 * @property hasDetectedClusterSession これまでに Android Auto のインストルメントクラスター用 Session を受け取ったことがあるか
 */
@Immutable
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
    /** 速度に連動して AudioTrack 書き込み前の PCM に追加ゲインを掛けるか。 */
    val isSpeedAdaptiveTtsGainEnabled: Boolean,
    /** 速度連動ゲインが上限速度で掛ける最大追加ゲイン。 */
    val speedAdaptiveTtsGainMaxDb: Double,
    /** 発話を OFF にした案内カテゴリの識別子集合 (外部ナビ API の category 名)。未登録カテゴリは ON 扱い。 */
    val disabledGuidanceCategories: Set<String>,
    val extNavDeviceUuid: String,
    /** これまでに Android Auto のインストルメントクラスター用 Session を受け取ったことがあるか。 */
    val hasDetectedClusterSession: Boolean,
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

        /** 速度連動 TTS ゲインが既定で有効か。 */
        const val SPEED_ADAPTIVE_TTS_GAIN_ENABLED_DEFAULT = false

        /** 速度連動 TTS ゲイン最大量の既定値。 */
        const val SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_DEFAULT = 6.0

        /** 設定 UI で選べる速度連動 TTS ゲイン最大量の最小値。 */
        const val SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MIN = 0.0

        /** 設定 UI で選べる速度連動 TTS ゲイン最大量の最大値。 */
        const val SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MAX = 10.0

        /** 速度連動 TTS ゲイン最大量 slider のステップ数。両端を除く 1dB 刻みの目盛り数。 */
        const val SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_STEPS = 9

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
            isSpeedAdaptiveTtsGainEnabled = SPEED_ADAPTIVE_TTS_GAIN_ENABLED_DEFAULT,
            speedAdaptiveTtsGainMaxDb = SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_DEFAULT,
            // 走行に必須でない既定 OFF カテゴリ。VoiceAnnouncementCategoryGate.OneNaviDefault と揃えること。
            disabledGuidanceCategories = setOf("Curve", "Scenic", "AccidentBlackSpot", "Merge"),
            extNavDeviceUuid = "",
            hasDetectedClusterSession = false,
        )
    }
}
