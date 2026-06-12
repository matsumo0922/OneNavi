package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import kotlin.test.Test
import kotlin.test.assertEquals

/** Google Cloud TTS 合成設定の AppSetting 反映を検証するテスト。 */
class GoogleCloudTtsSynthesisConfigTest {

    @Test
    fun `developer override feature が無効なら voice と rate は既定値を使う`() {
        val setting = AppSetting.DEFAULT.copy(
            developerMode = true,
            enabledDeveloperFeatures = emptySet(),
            ttsVoiceNameOverride = "ja-JP-Chirp3-HD-Kore",
            ttsSpeakingRateOverride = 0.85,
            ttsVolumeGainDb = 4.0,
        )

        val config = GoogleCloudTtsSynthesisConfig.fromAppSetting(setting)

        assertEquals(GoogleCloudTtsSynthesisConfig.DEFAULT_VOICE_NAME, config.voiceName)
        assertEquals(GoogleCloudTtsSynthesisConfig.DEFAULT_SPEAKING_RATE, config.speakingRate)
        assertEquals(4.0, config.volumeGainDb)
    }

    @Test
    fun `developer override feature が有効なら voice と rate を上書きする`() {
        val setting = AppSetting.DEFAULT.copy(
            developerMode = true,
            enabledDeveloperFeatures = setOf(DeveloperFeature.TTS_VOICE_OVERRIDE),
            ttsVoiceNameOverride = " ja-JP-Chirp3-HD-Kore ",
            ttsSpeakingRateOverride = 0.85,
            ttsVolumeGainDb = 4.0,
        )

        val config = GoogleCloudTtsSynthesisConfig.fromAppSetting(setting)

        assertEquals("ja-JP-Chirp3-HD-Kore", config.voiceName)
        assertEquals(0.85, config.speakingRate)
        assertEquals(4.0, config.volumeGainDb)
    }

    @Test
    fun `voice override が空なら feature 有効でも既定 voice を使う`() {
        val setting = AppSetting.DEFAULT.copy(
            developerMode = true,
            enabledDeveloperFeatures = setOf(DeveloperFeature.TTS_VOICE_OVERRIDE),
            ttsVoiceNameOverride = " ",
            ttsSpeakingRateOverride = 0.85,
        )

        val config = GoogleCloudTtsSynthesisConfig.fromAppSetting(setting)

        assertEquals(GoogleCloudTtsSynthesisConfig.DEFAULT_VOICE_NAME, config.voiceName)
        assertEquals(0.85, config.speakingRate)
    }
}
