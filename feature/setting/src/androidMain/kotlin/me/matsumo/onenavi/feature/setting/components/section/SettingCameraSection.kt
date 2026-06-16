package me.matsumo.onenavi.feature.setting.components.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_camera
import me.matsumo.onenavi.core.resource.setting_camera_default_zoom
import me.matsumo.onenavi.core.resource.setting_camera_default_zoom_description
import me.matsumo.onenavi.core.resource.setting_camera_guidance_maneuver_zoom
import me.matsumo.onenavi.core.resource.setting_camera_guidance_maneuver_zoom_description
import me.matsumo.onenavi.core.resource.setting_camera_tilted_camera_degrees
import me.matsumo.onenavi.core.resource.setting_camera_tilted_camera_degrees_description
import me.matsumo.onenavi.feature.setting.components.SettingSliderItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun SettingCameraSection(
    setting: AppSetting,
    onMapDefaultZoomChanged: (Float) -> Unit,
    onMapGuidanceManeuverZoomChanged: (Float) -> Unit,
    onMapTiltedCameraDegreesChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var defaultZoomDraft by remember(setting.mapDefaultZoom) {
        mutableFloatStateOf(setting.mapDefaultZoom)
    }
    var guidanceManeuverZoomDraft by remember(setting.mapGuidanceManeuverZoom) {
        mutableFloatStateOf(setting.mapGuidanceManeuverZoom)
    }
    var tiltedCameraDegreesDraft by remember(setting.mapTiltedCameraDegrees) {
        mutableFloatStateOf(setting.mapTiltedCameraDegrees)
    }

    val resolvedDefaultZoomDraft = defaultZoomDraft.coerceIn(MapDefaultZoomRange)
    val guidanceManeuverZoomRange = guidanceManeuverZoomRange(resolvedDefaultZoomDraft)
    val resolvedGuidanceManeuverZoomDraft = resolveGuidanceManeuverZoom(
        guidanceManeuverZoom = guidanceManeuverZoomDraft,
        defaultZoom = resolvedDefaultZoomDraft,
    )
    val resolvedTiltedCameraDegreesDraft = tiltedCameraDegreesDraft.coerceIn(TiltedCameraDegreesRange)

    Column(modifier = modifier) {
        SettingTitleItem(
            modifier = Modifier.fillMaxWidth(),
            text = Res.string.setting_camera,
        )

        SettingSliderItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_camera_default_zoom,
            description = Res.string.setting_camera_default_zoom_description,
            valueLabel = formatZoom(resolvedDefaultZoomDraft),
            value = resolvedDefaultZoomDraft,
            onValueChanged = { zoom ->
                val roundedZoom = roundZoom(zoom)
                defaultZoomDraft = roundedZoom
                guidanceManeuverZoomDraft = resolveGuidanceManeuverZoom(
                    guidanceManeuverZoom = guidanceManeuverZoomDraft,
                    defaultZoom = roundedZoom,
                )
            },
            onValueChangeFinished = {
                onMapDefaultZoomChanged(resolvedDefaultZoomDraft)
            },
            valueRange = MapDefaultZoomRange,
            steps = AppSetting.MAP_DEFAULT_ZOOM_STEPS,
        )

        SettingSliderItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_camera_guidance_maneuver_zoom,
            description = Res.string.setting_camera_guidance_maneuver_zoom_description,
            valueLabel = formatZoom(resolvedGuidanceManeuverZoomDraft),
            value = resolvedGuidanceManeuverZoomDraft,
            onValueChanged = { zoom ->
                guidanceManeuverZoomDraft = roundZoom(zoom)
            },
            onValueChangeFinished = {
                onMapGuidanceManeuverZoomChanged(resolvedGuidanceManeuverZoomDraft)
            },
            valueRange = guidanceManeuverZoomRange,
            steps = guidanceManeuverZoomSteps(resolvedDefaultZoomDraft),
        )

        SettingSliderItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_camera_tilted_camera_degrees,
            description = Res.string.setting_camera_tilted_camera_degrees_description,
            valueLabel = formatDegrees(resolvedTiltedCameraDegreesDraft),
            value = resolvedTiltedCameraDegreesDraft,
            onValueChanged = { degrees ->
                tiltedCameraDegreesDraft = roundTiltedCameraDegrees(degrees)
            },
            onValueChangeFinished = {
                onMapTiltedCameraDegreesChanged(resolvedTiltedCameraDegreesDraft)
            },
            valueRange = TiltedCameraDegreesRange,
            steps = AppSetting.MAP_TILTED_CAMERA_DEGREES_STEPS,
        )
    }
}

private fun formatZoom(zoom: Float): String {
    return String.format(
        Locale.US,
        "%.0f",
        zoom,
    )
}

private fun formatDegrees(degrees: Float): String {
    return String.format(
        Locale.US,
        "%.0f°",
        degrees,
    )
}

private fun roundZoom(zoom: Float): Float = zoom.roundToInt().toFloat()

private fun roundTiltedCameraDegrees(degrees: Float): Float {
    val step = AppSetting.MAP_TILTED_CAMERA_DEGREES_STEP
    return (degrees / step).roundToInt() * step
}

private fun resolveGuidanceManeuverZoom(guidanceManeuverZoom: Float, defaultZoom: Float): Float {
    return guidanceManeuverZoom.coerceIn(
        minimumValue = AppSetting.mapGuidanceManeuverZoomMin(defaultZoom),
        maximumValue = AppSetting.MAP_GUIDANCE_MANEUVER_ZOOM_MAX,
    )
}

private fun guidanceManeuverZoomRange(defaultZoom: Float): ClosedFloatingPointRange<Float> {
    return AppSetting.mapGuidanceManeuverZoomMin(defaultZoom)
        .rangeTo(AppSetting.MAP_GUIDANCE_MANEUVER_ZOOM_MAX)
}

private fun guidanceManeuverZoomSteps(defaultZoom: Float): Int {
    val zoomIntervalCount = AppSetting.MAP_GUIDANCE_MANEUVER_ZOOM_MAX -
        AppSetting.mapGuidanceManeuverZoomMin(defaultZoom)

    return (zoomIntervalCount.roundToInt() - 1).coerceAtLeast(0)
}

/** 通常ズーム slider の範囲。 */
private val MapDefaultZoomRange =
    AppSetting.MAP_DEFAULT_ZOOM_MIN.rangeTo(AppSetting.MAP_DEFAULT_ZOOM_MAX)

/** 3D 追従表示チルト角度 slider の範囲。 */
private val TiltedCameraDegreesRange =
    AppSetting.MAP_TILTED_CAMERA_DEGREES_MIN.rangeTo(AppSetting.MAP_TILTED_CAMERA_DEGREES_MAX)
