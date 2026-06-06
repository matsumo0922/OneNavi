package me.matsumo.onenavi.core.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import me.matsumo.onenavi.core.common.serializer.ColorSerializer

@Serializable
data class AppSetting(
    val id: String,
    val theme: Theme,
    val useDynamicColor: Boolean,
    @Serializable(with = ColorSerializer::class)
    val seedColor: Color,
    val plusMode: Boolean,
    val developerMode: Boolean,
    val useMediaAudioChannelOnCar: Boolean,
    /** 発話を OFF にした案内カテゴリの識別子集合 (外部ナビ API の category 名)。未登録カテゴリは ON 扱い。 */
    val disabledGuidanceCategories: Set<String>,
    val extNavDeviceUuid: String,
) {
    val hasPrivilege get() = plusMode || developerMode

    companion object {
        val DEFAULT = AppSetting(
            id = "",
            theme = Theme.System,
            useDynamicColor = currentPlatform == Platform.Android,
            seedColor = Color(0xFF7FD0FF),
            plusMode = false,
            developerMode = false,
            useMediaAudioChannelOnCar = false,
            // 走行に必須でない既定 OFF カテゴリ。VoiceAnnouncementCategoryGate.OneNaviDefault と揃えること。
            disabledGuidanceCategories = setOf("Curve", "Scenic", "AccidentBlackSpot", "Merge"),
            extNavDeviceUuid = "",
        )
    }
}
