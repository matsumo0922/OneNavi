package me.matsumo.onenavi.feature.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.core.common.car.CarHardwareConnectionStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDataStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareEnergySnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationPointSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareSpeedSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareTollCardSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareValueSnapshot
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_connection
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_description
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_energy
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_location
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_speed
import me.matsumo.onenavi.core.resource.setting_developer_options_car_hardware_diagnostics_toll_card
import me.matsumo.onenavi.core.resource.setting_developer_options_car_vd_debug_overlay
import me.matsumo.onenavi.core.resource.setting_developer_options_car_vd_debug_overlay_description
import me.matsumo.onenavi.core.resource.setting_developer_options_fake_gps
import me.matsumo.onenavi.core.resource.setting_developer_options_fake_gps_description
import me.matsumo.onenavi.core.resource.setting_developer_options_force_plus_privilege
import me.matsumo.onenavi.core.resource.setting_developer_options_force_plus_privilege_description
import me.matsumo.onenavi.core.resource.setting_developer_options_map_diagnostics
import me.matsumo.onenavi.core.resource.setting_developer_options_map_diagnostics_description
import me.matsumo.onenavi.core.resource.setting_developer_options_master
import me.matsumo.onenavi.core.resource.setting_developer_options_master_description
import me.matsumo.onenavi.core.resource.setting_developer_options_section_access
import me.matsumo.onenavi.core.resource.setting_developer_options_section_android_auto
import me.matsumo.onenavi.core.resource.setting_developer_options_section_location
import me.matsumo.onenavi.core.resource.setting_developer_options_section_map
import me.matsumo.onenavi.core.resource.setting_developer_options_section_tts
import me.matsumo.onenavi.core.resource.setting_developer_options_show_developer_badge
import me.matsumo.onenavi.core.resource.setting_developer_options_show_developer_badge_description
import me.matsumo.onenavi.core.resource.setting_developer_options_show_paywall_section
import me.matsumo.onenavi.core.resource.setting_developer_options_show_paywall_section_description
import me.matsumo.onenavi.core.resource.setting_developer_options_title
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_speaking_rate_override
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_speaking_rate_override_description
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_voice_name_override
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_voice_name_override_description
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_voice_override
import me.matsumo.onenavi.core.resource.setting_developer_options_tts_voice_override_description
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.setting.components.SettingSliderItem
import me.matsumo.onenavi.feature.setting.components.SettingSwitchItem
import me.matsumo.onenavi.feature.setting.components.SettingTextItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingDeveloperOptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingViewModel = koinViewModel(),
) {
    val navBackStack = LocalNavBackStack.current
    val setting by viewModel.setting.collectAsStateWithLifecycle()
    val carHardwareDiagnostics by viewModel.carHardwareDiagnostics.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(Res.string.setting_developer_options_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navBackStack.removeAt(navBackStack.size - 1) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            item {
                SettingSwitchItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = Res.string.setting_developer_options_master,
                    description = Res.string.setting_developer_options_master_description,
                    value = setting.developerMode,
                    onValueChanged = { isEnabled ->
                        viewModel.setDeveloperMode(isEnabled)
                    },
                )
            }

            for (sectionTitle in developerFeatureSections) {
                val features = DeveloperFeature.entries.filter { developerFeature -> developerFeature.sectionTitle == sectionTitle }

                item {
                    SettingTitleItem(
                        modifier = Modifier.fillMaxWidth(),
                        text = sectionTitle,
                    )
                }

                items(
                    items = features,
                    key = { developerFeature -> developerFeature.name },
                ) { developerFeature ->
                    SettingSwitchItem(
                        modifier = Modifier.fillMaxWidth(),
                        title = developerFeature.title,
                        description = developerFeature.description,
                        value = setting.isDeveloperFeatureEnabled(developerFeature),
                        onValueChanged = { isEnabled ->
                            viewModel.setDeveloperFeatureEnabled(developerFeature, isEnabled)
                        },
                        isEnabled = setting.developerMode,
                    )
                }
            }

            if (setting.isDeveloperFeatureEnabled(DeveloperFeature.TTS_VOICE_OVERRIDE)) {
                ttsVoiceOverrideItems(
                    setting = setting,
                    onVoiceNameChanged = viewModel::setTtsVoiceNameOverride,
                    onSpeakingRateChanged = viewModel::setTtsSpeakingRateOverride,
                )
            }

            if (setting.isDeveloperFeatureEnabled(DeveloperFeature.CAR_HARDWARE_DIAGNOSTICS)) {
                carHardwareDiagnosticsItems(carHardwareDiagnostics)
            }
        }
    }
}

/** 開発者向けオプション画面に表示する機能グループ。 */
private val developerFeatureSections = listOf(
    Res.string.setting_developer_options_section_access,
    Res.string.setting_developer_options_section_location,
    Res.string.setting_developer_options_section_map,
    Res.string.setting_developer_options_section_tts,
    Res.string.setting_developer_options_section_android_auto,
)

private val DeveloperFeature.sectionTitle: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_section_access
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_section_access
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_section_access
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_section_location
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_section_map
        DeveloperFeature.TTS_VOICE_OVERRIDE -> Res.string.setting_developer_options_section_tts
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_section_android_auto
        DeveloperFeature.CAR_HARDWARE_DIAGNOSTICS -> Res.string.setting_developer_options_section_android_auto
    }

private val DeveloperFeature.title: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_force_plus_privilege
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_show_paywall_section
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_show_developer_badge
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_fake_gps
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_map_diagnostics
        DeveloperFeature.TTS_VOICE_OVERRIDE -> Res.string.setting_developer_options_tts_voice_override
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_car_vd_debug_overlay
        DeveloperFeature.CAR_HARDWARE_DIAGNOSTICS -> Res.string.setting_developer_options_car_hardware_diagnostics
    }

private val DeveloperFeature.description: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_force_plus_privilege_description
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_show_paywall_section_description
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_show_developer_badge_description
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_fake_gps_description
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_map_diagnostics_description
        DeveloperFeature.TTS_VOICE_OVERRIDE -> Res.string.setting_developer_options_tts_voice_override_description
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_car_vd_debug_overlay_description
        DeveloperFeature.CAR_HARDWARE_DIAGNOSTICS -> Res.string.setting_developer_options_car_hardware_diagnostics_description
    }

private fun LazyListScope.ttsVoiceOverrideItems(
    setting: AppSetting,
    onVoiceNameChanged: (String) -> Unit,
    onSpeakingRateChanged: (Double) -> Unit,
) {
    item {
        SettingTitleItem(
            modifier = Modifier.fillMaxWidth(),
            text = Res.string.setting_developer_options_tts_voice_override,
        )
    }

    item {
        SettingDeveloperOptionsVoiceNameItem(
            modifier = Modifier.fillMaxWidth(),
            voiceName = setting.ttsVoiceNameOverride,
            onVoiceNameChanged = onVoiceNameChanged,
        )
    }

    item {
        SettingDeveloperOptionsSpeakingRateItem(
            modifier = Modifier.fillMaxWidth(),
            speakingRate = setting.ttsSpeakingRateOverride,
            onSpeakingRateChanged = onSpeakingRateChanged,
        )
    }
}

@Composable
private fun SettingDeveloperOptionsVoiceNameItem(
    voiceName: String,
    onVoiceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var voiceNameDraft by remember(voiceName) {
        mutableStateOf(voiceName)
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = voiceNameDraft,
            onValueChange = { updatedVoiceName ->
                voiceNameDraft = updatedVoiceName
                onVoiceNameChanged(updatedVoiceName)
            },
            label = {
                Text(text = stringResource(Res.string.setting_developer_options_tts_voice_name_override))
            },
            supportingText = {
                Text(
                    text = stringResource(
                        Res.string.setting_developer_options_tts_voice_name_override_description,
                    ),
                )
            },
            singleLine = true,
        )
    }
}

@Composable
private fun SettingDeveloperOptionsSpeakingRateItem(
    speakingRate: Double,
    onSpeakingRateChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var speakingRateDraft by remember(speakingRate) {
        mutableDoubleStateOf(speakingRate)
    }
    val minimumSpeakingRate = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MIN.toFloat()
    val maximumSpeakingRate = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX.toFloat()
    val speakingRateRange = minimumSpeakingRate..maximumSpeakingRate

    SettingSliderItem(
        modifier = modifier,
        title = Res.string.setting_developer_options_tts_speaking_rate_override,
        description = Res.string.setting_developer_options_tts_speaking_rate_override_description,
        valueLabel = formatTtsSpeakingRate(speakingRateDraft),
        value = speakingRateDraft.toFloat(),
        onValueChanged = { updatedSpeakingRate ->
            speakingRateDraft = roundTtsSpeakingRate(updatedSpeakingRate)
        },
        onValueChangeFinished = {
            onSpeakingRateChanged(speakingRateDraft)
        },
        valueRange = speakingRateRange,
        steps = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_STEPS,
    )
}

private fun LazyListScope.carHardwareDiagnosticsItems(snapshot: CarHardwareDiagnosticsSnapshot) {
    item {
        SettingTitleItem(
            modifier = Modifier.fillMaxWidth(),
            text = Res.string.setting_developer_options_car_hardware_diagnostics,
        )
    }

    item {
        SettingDeveloperOptionsDiagnosticItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_developer_options_car_hardware_diagnostics_connection,
            description = snapshot.connectionDescription(),
        )
    }

    item {
        SettingDeveloperOptionsDiagnosticItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_developer_options_car_hardware_diagnostics_speed,
            description = snapshot.speed.description(),
        )
    }

    item {
        SettingDeveloperOptionsDiagnosticItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_developer_options_car_hardware_diagnostics_toll_card,
            description = snapshot.tollCard.description(),
        )
    }

    item {
        SettingDeveloperOptionsDiagnosticItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_developer_options_car_hardware_diagnostics_energy,
            description = snapshot.energy.description(),
        )
    }

    item {
        SettingDeveloperOptionsDiagnosticItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_developer_options_car_hardware_diagnostics_location,
            description = snapshot.location.description(),
        )
    }
}

@Composable
private fun SettingDeveloperOptionsDiagnosticItem(
    title: StringResource,
    description: String,
    modifier: Modifier = Modifier,
) {
    SettingTextItem(
        modifier = modifier,
        title = stringResource(title),
        description = description,
    )
}

private fun CarHardwareDiagnosticsSnapshot.connectionDescription(): String {
    val statusLabel = connectionStatus.label
    val currentMessage = message ?: return statusLabel

    return "$statusLabel\n$currentMessage"
}

private fun CarHardwareSpeedSnapshot.description(): String {
    val lines = listOf(
        "raw: ${rawSpeedMetersPerSecond.formatSpeed()}",
        "display: ${displaySpeedMetersPerSecond.formatSpeed()}",
        "unit: ${speedDisplayUnit.formatText()}",
    )

    return lines.joinToString(separator = "\n")
}

private fun CarHardwareTollCardSnapshot.description(): String {
    return "cardState: ${cardState.formatText()}"
}

private fun CarHardwareEnergySnapshot.description(): String {
    val lines = listOf(
        "batteryPercent: ${batteryPercent.formatFloat()}",
        "fuelPercent: ${fuelPercent.formatFloat()}",
        "energyIsLow: ${energyIsLow.formatBoolean()}",
        "rangeRemaining: ${rangeRemainingMeters.formatMeters()}",
        "distanceUnit: ${distanceDisplayUnit.formatText()}",
    )

    return lines.joinToString(separator = "\n")
}

private fun CarHardwareLocationSnapshot.description(): String {
    return location.formatValue { point -> point.description() }
}

private fun CarHardwareLocationPointSnapshot.description(): String {
    val currentAccuracyMeters = accuracyMeters
    val currentAltitudeMeters = altitudeMeters
    val currentBearingDegrees = bearingDegrees
    val currentSpeedMetersPerSecond = speedMetersPerSecond

    val lines = mutableListOf(
        "lat/lng: ${formatLatLng()}",
        "provider: ${provider ?: "なし"}",
        "locationTime: ${locationTimeMillis}ms",
        "elapsedRealtime: ${elapsedRealtimeNanos}ns",
    )

    if (currentAccuracyMeters != null) {
        lines += "accuracy: ${currentAccuracyMeters.formatNumber()}m"
    }

    if (currentAltitudeMeters != null) {
        lines += "altitude: ${currentAltitudeMeters.formatNumber()}m"
    }

    if (currentBearingDegrees != null) {
        lines += "bearing: ${currentBearingDegrees.formatNumber()}deg"
    }

    if (currentSpeedMetersPerSecond != null) {
        lines += "speed: ${currentSpeedMetersPerSecond.formatSpeedValue()}"
    }

    return lines.joinToString(separator = "\n")
}

private fun CarHardwareLocationPointSnapshot.formatLatLng(): String {
    return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}

private fun CarHardwareValueSnapshot<Float>.formatSpeed(): String {
    return formatValue { speedMetersPerSecond -> speedMetersPerSecond.formatSpeedValue() }
}

private fun Float.formatSpeedValue(): String {
    return String.format(Locale.US, "%.2f m/s (%.1f km/h)", this, this * MPS_TO_KMH)
}

private fun CarHardwareValueSnapshot<Float>.formatMeters(): String {
    return formatValue { meters -> "${meters.formatNumber()}m" }
}

private fun CarHardwareValueSnapshot<Float>.formatFloat(): String {
    return formatValue { value -> value.formatNumber() }
}

private fun CarHardwareValueSnapshot<Boolean>.formatBoolean(): String {
    return formatValue { value -> value.toString() }
}

private fun CarHardwareValueSnapshot<String>.formatText(): String {
    return formatValue { value -> value }
}

private fun <Value> CarHardwareValueSnapshot<Value>.formatValue(formatter: (Value) -> String): String {
    val formattedValue = value?.let(formatter) ?: "値なし"

    return "$formattedValue / ${status.statusLabel} / t=${timestampMillis}ms"
}

private fun Number.formatNumber(): String {
    return String.format(Locale.US, "%.1f", toDouble())
}

private fun formatTtsSpeakingRate(speakingRate: Double): String {
    return String.format(Locale.US, "%.2fx", speakingRate)
}

private fun roundTtsSpeakingRate(speakingRate: Float): Double {
    val clampedRate = speakingRate.toDouble().coerceIn(
        minimumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MIN,
        maximumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX,
    )
    val roundedRate = (clampedRate / TTS_SPEAKING_RATE_SLIDER_STEP).roundToInt() *
        TTS_SPEAKING_RATE_SLIDER_STEP

    return roundToTwoDecimals(roundedRate).coerceIn(
        minimumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MIN,
        maximumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX,
    )
}

private fun roundToTwoDecimals(value: Double): Double {
    return (value * 100.0).roundToInt() / 100.0
}

private val CarHardwareConnectionStatus.label: String
    get() = when (this) {
        CarHardwareConnectionStatus.DISCONNECTED -> "DISCONNECTED"
        CarHardwareConnectionStatus.CONNECTED -> "CONNECTED"
        CarHardwareConnectionStatus.UNAVAILABLE -> "UNAVAILABLE"
    }

private val CarHardwareDataStatus.statusLabel: String
    get() = when (this) {
        CarHardwareDataStatus.UNKNOWN -> "STATUS_UNKNOWN"
        CarHardwareDataStatus.SUCCESS -> "STATUS_SUCCESS"
        CarHardwareDataStatus.UNIMPLEMENTED -> "STATUS_UNIMPLEMENTED"
        CarHardwareDataStatus.UNAVAILABLE -> "STATUS_UNAVAILABLE"
    }

/** m/s から km/h への変換係数。 */
private const val MPS_TO_KMH = 3.6f

/** TTS 話速 slider の 1 目盛り幅。 */
private const val TTS_SPEAKING_RATE_SLIDER_STEP = 0.05
