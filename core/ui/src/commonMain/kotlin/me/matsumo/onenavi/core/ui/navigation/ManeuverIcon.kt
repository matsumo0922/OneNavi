package me.matsumo.onenavi.core.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.direction_arrive
import me.matsumo.onenavi.core.resource.direction_arrive_left
import me.matsumo.onenavi.core.resource.direction_arrive_right
import me.matsumo.onenavi.core.resource.direction_arrive_straight
import me.matsumo.onenavi.core.resource.direction_continue
import me.matsumo.onenavi.core.resource.direction_continue_left
import me.matsumo.onenavi.core.resource.direction_continue_right
import me.matsumo.onenavi.core.resource.direction_continue_slight_left
import me.matsumo.onenavi.core.resource.direction_continue_slight_right
import me.matsumo.onenavi.core.resource.direction_continue_straight
import me.matsumo.onenavi.core.resource.direction_continue_uturn
import me.matsumo.onenavi.core.resource.direction_depart
import me.matsumo.onenavi.core.resource.direction_depart_left
import me.matsumo.onenavi.core.resource.direction_depart_right
import me.matsumo.onenavi.core.resource.direction_depart_straight
import me.matsumo.onenavi.core.resource.direction_end_of_road_left
import me.matsumo.onenavi.core.resource.direction_end_of_road_right
import me.matsumo.onenavi.core.resource.direction_fork
import me.matsumo.onenavi.core.resource.direction_fork_left
import me.matsumo.onenavi.core.resource.direction_fork_right
import me.matsumo.onenavi.core.resource.direction_fork_slight_left
import me.matsumo.onenavi.core.resource.direction_fork_slight_right
import me.matsumo.onenavi.core.resource.direction_fork_straight
import me.matsumo.onenavi.core.resource.direction_merge_left
import me.matsumo.onenavi.core.resource.direction_merge_right
import me.matsumo.onenavi.core.resource.direction_merge_slight_left
import me.matsumo.onenavi.core.resource.direction_merge_slight_right
import me.matsumo.onenavi.core.resource.direction_merge_straight
import me.matsumo.onenavi.core.resource.direction_new_name_left
import me.matsumo.onenavi.core.resource.direction_new_name_right
import me.matsumo.onenavi.core.resource.direction_new_name_sharp_left
import me.matsumo.onenavi.core.resource.direction_new_name_sharp_right
import me.matsumo.onenavi.core.resource.direction_new_name_slight_left
import me.matsumo.onenavi.core.resource.direction_new_name_slight_right
import me.matsumo.onenavi.core.resource.direction_new_name_straight
import me.matsumo.onenavi.core.resource.direction_off_ramp
import me.matsumo.onenavi.core.resource.direction_off_ramp_left
import me.matsumo.onenavi.core.resource.direction_off_ramp_right
import me.matsumo.onenavi.core.resource.direction_off_ramp_slight_left
import me.matsumo.onenavi.core.resource.direction_off_ramp_slight_right
import me.matsumo.onenavi.core.resource.direction_on_ramp
import me.matsumo.onenavi.core.resource.direction_on_ramp_left
import me.matsumo.onenavi.core.resource.direction_on_ramp_right
import me.matsumo.onenavi.core.resource.direction_on_ramp_sharp_left
import me.matsumo.onenavi.core.resource.direction_on_ramp_sharp_right
import me.matsumo.onenavi.core.resource.direction_on_ramp_slight_left
import me.matsumo.onenavi.core.resource.direction_on_ramp_slight_right
import me.matsumo.onenavi.core.resource.direction_on_ramp_straight
import me.matsumo.onenavi.core.resource.direction_rotary
import me.matsumo.onenavi.core.resource.direction_rotary_left
import me.matsumo.onenavi.core.resource.direction_rotary_right
import me.matsumo.onenavi.core.resource.direction_rotary_sharp_left
import me.matsumo.onenavi.core.resource.direction_rotary_sharp_right
import me.matsumo.onenavi.core.resource.direction_rotary_slight_left
import me.matsumo.onenavi.core.resource.direction_rotary_slight_right
import me.matsumo.onenavi.core.resource.direction_rotary_straight
import me.matsumo.onenavi.core.resource.direction_roundabout
import me.matsumo.onenavi.core.resource.direction_roundabout_left
import me.matsumo.onenavi.core.resource.direction_roundabout_right
import me.matsumo.onenavi.core.resource.direction_roundabout_sharp_left
import me.matsumo.onenavi.core.resource.direction_roundabout_sharp_right
import me.matsumo.onenavi.core.resource.direction_roundabout_slight_left
import me.matsumo.onenavi.core.resource.direction_roundabout_slight_right
import me.matsumo.onenavi.core.resource.direction_roundabout_straight
import me.matsumo.onenavi.core.resource.direction_traffic_circle
import me.matsumo.onenavi.core.resource.direction_traffic_circle_left
import me.matsumo.onenavi.core.resource.direction_traffic_circle_right
import me.matsumo.onenavi.core.resource.direction_traffic_circle_slight_left
import me.matsumo.onenavi.core.resource.direction_traffic_circle_slight_right
import me.matsumo.onenavi.core.resource.direction_turn_left
import me.matsumo.onenavi.core.resource.direction_turn_right
import me.matsumo.onenavi.core.resource.direction_turn_sharp_left
import me.matsumo.onenavi.core.resource.direction_turn_sharp_right
import me.matsumo.onenavi.core.resource.direction_turn_slight_left
import me.matsumo.onenavi.core.resource.direction_turn_slight_right
import me.matsumo.onenavi.core.resource.direction_turn_straight
import me.matsumo.onenavi.core.resource.direction_uturn
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Navigation SDK が生成した bitmap 優先のマニューバアイコン。
 * bitmap が無い場合は [type] / [maneuverModifier] をもとに内蔵 VectorDrawable を選択する。
 */
@Composable
fun ManeuverIcon(
    type: ManeuverType,
    maneuverModifier: ManeuverModifier?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconBitmap: ImageBitmap? = null,
    tint: Color = LocalContentColor.current,
) {
    if (iconBitmap != null) {
        Image(
            modifier = modifier,
            bitmap = iconBitmap,
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
        )
    } else {
        Icon(
            modifier = modifier,
            painter = painterResource(maneuverIconResource(type, maneuverModifier)),
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

/**
 * マニューバ情報から描画用 VectorDrawable を解決する。
 *
 * 解決の優先順位:
 * 1. `type` と `maneuverModifier` の完全一致
 * 2. `type` 単独のアイコン
 * 3. `TURN` + `maneuverModifier` へのフォールバック
 * 4. 最終フォールバックとして直進アイコン
 */
fun maneuverIconResource(type: ManeuverType, maneuverModifier: ManeuverModifier?): DrawableResource {
    return exactDirectionIcon(type, maneuverModifier)
        ?: typeOnlyDirectionIcon(type)
        ?: exactDirectionIcon(ManeuverType.TURN, maneuverModifier)
        ?: Res.drawable.direction_continue
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun exactDirectionIcon(type: ManeuverType, modifier: ManeuverModifier?): DrawableResource? {
    if (modifier == null) return null
    return when (type) {
        ManeuverType.ARRIVE -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_arrive_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_arrive_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_arrive_straight
            else -> null
        }
        ManeuverType.CONTINUE -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_continue_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_continue_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_continue_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_continue_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_continue_straight
            ManeuverModifier.UTURN -> Res.drawable.direction_continue_uturn
            else -> null
        }
        ManeuverType.DEPART -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_depart_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_depart_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_depart_straight
            else -> null
        }
        ManeuverType.END_OF_ROAD -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_end_of_road_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_end_of_road_right
            else -> null
        }
        ManeuverType.FORK -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_fork_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_fork_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_fork_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_fork_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_fork_straight
            else -> null
        }
        ManeuverType.MERGE -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_merge_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_merge_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_merge_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_merge_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_merge_straight
            else -> null
        }
        ManeuverType.NAME_CHANGE -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_new_name_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_new_name_right
            ManeuverModifier.SHARP_LEFT -> Res.drawable.direction_new_name_sharp_left
            ManeuverModifier.SHARP_RIGHT -> Res.drawable.direction_new_name_sharp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_new_name_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_new_name_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_new_name_straight
            else -> null
        }
        ManeuverType.OFF_RAMP -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_off_ramp_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_off_ramp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_off_ramp_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_off_ramp_slight_right
            else -> null
        }
        ManeuverType.ON_RAMP -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_on_ramp_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_on_ramp_right
            ManeuverModifier.SHARP_LEFT -> Res.drawable.direction_on_ramp_sharp_left
            ManeuverModifier.SHARP_RIGHT -> Res.drawable.direction_on_ramp_sharp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_on_ramp_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_on_ramp_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_on_ramp_straight
            else -> null
        }
        ManeuverType.ROTARY -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_rotary_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_rotary_right
            ManeuverModifier.SHARP_LEFT -> Res.drawable.direction_rotary_sharp_left
            ManeuverModifier.SHARP_RIGHT -> Res.drawable.direction_rotary_sharp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_rotary_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_rotary_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_rotary_straight
            else -> null
        }
        ManeuverType.ROUNDABOUT -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_roundabout_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_roundabout_right
            ManeuverModifier.SHARP_LEFT -> Res.drawable.direction_roundabout_sharp_left
            ManeuverModifier.SHARP_RIGHT -> Res.drawable.direction_roundabout_sharp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_roundabout_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_roundabout_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_roundabout_straight
            else -> null
        }
        ManeuverType.TRAFFIC_CIRCLE -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_traffic_circle_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_traffic_circle_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_traffic_circle_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_traffic_circle_slight_right
            else -> null
        }
        ManeuverType.TURN -> when (modifier) {
            ManeuverModifier.LEFT -> Res.drawable.direction_turn_left
            ManeuverModifier.RIGHT -> Res.drawable.direction_turn_right
            ManeuverModifier.SHARP_LEFT -> Res.drawable.direction_turn_sharp_left
            ManeuverModifier.SHARP_RIGHT -> Res.drawable.direction_turn_sharp_right
            ManeuverModifier.SLIGHT_LEFT -> Res.drawable.direction_turn_slight_left
            ManeuverModifier.SLIGHT_RIGHT -> Res.drawable.direction_turn_slight_right
            ManeuverModifier.STRAIGHT -> Res.drawable.direction_turn_straight
            else -> null
        }
        ManeuverType.UTURN -> null
    }
}

private fun typeOnlyDirectionIcon(type: ManeuverType): DrawableResource? {
    return when (type) {
        ManeuverType.ARRIVE -> Res.drawable.direction_arrive
        ManeuverType.CONTINUE -> Res.drawable.direction_continue
        ManeuverType.DEPART -> Res.drawable.direction_depart
        ManeuverType.FORK -> Res.drawable.direction_fork
        ManeuverType.OFF_RAMP -> Res.drawable.direction_off_ramp
        ManeuverType.ON_RAMP -> Res.drawable.direction_on_ramp
        ManeuverType.ROTARY -> Res.drawable.direction_rotary
        ManeuverType.ROUNDABOUT -> Res.drawable.direction_roundabout
        ManeuverType.TRAFFIC_CIRCLE -> Res.drawable.direction_traffic_circle
        ManeuverType.UTURN -> Res.drawable.direction_uturn
        ManeuverType.TURN,
        ManeuverType.END_OF_ROAD,
        ManeuverType.MERGE,
        ManeuverType.NAME_CHANGE,
        -> null
    }
}
