package me.matsumo.onenavi.core.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.direction_arrive
import me.matsumo.onenavi.core.resource.direction_arrive_left
import me.matsumo.onenavi.core.resource.direction_arrive_right
import me.matsumo.onenavi.core.resource.direction_arrive_straight
import me.matsumo.onenavi.core.resource.direction_close
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
import me.matsumo.onenavi.core.resource.direction_flag
import me.matsumo.onenavi.core.resource.direction_fork
import me.matsumo.onenavi.core.resource.direction_fork_left
import me.matsumo.onenavi.core.resource.direction_fork_right
import me.matsumo.onenavi.core.resource.direction_fork_slight_left
import me.matsumo.onenavi.core.resource.direction_fork_slight_right
import me.matsumo.onenavi.core.resource.direction_fork_straight
import me.matsumo.onenavi.core.resource.direction_invalid
import me.matsumo.onenavi.core.resource.direction_invalid_left
import me.matsumo.onenavi.core.resource.direction_invalid_right
import me.matsumo.onenavi.core.resource.direction_invalid_slight_left
import me.matsumo.onenavi.core.resource.direction_invalid_slight_right
import me.matsumo.onenavi.core.resource.direction_invalid_straight
import me.matsumo.onenavi.core.resource.direction_invalid_uturn
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
import me.matsumo.onenavi.core.resource.direction_notification_left
import me.matsumo.onenavi.core.resource.direction_notification_right
import me.matsumo.onenavi.core.resource.direction_notification_sharp_left
import me.matsumo.onenavi.core.resource.direction_notification_sharp_right
import me.matsumo.onenavi.core.resource.direction_notification_slight_left
import me.matsumo.onenavi.core.resource.direction_notification_slight_right
import me.matsumo.onenavi.core.resource.direction_notification_straight
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
import me.matsumo.onenavi.core.resource.direction_ramp
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
import me.matsumo.onenavi.core.resource.direction_updown
import me.matsumo.onenavi.core.resource.direction_uturn
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun ManeuverIcon(
    type: String,
    maneuverModifier: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        modifier = modifier,
        painter = painterResource(maneuverIconResource(type, maneuverModifier)),
        contentDescription = contentDescription,
        tint = tint,
    )
}

/**
 * Google Routes API の maneuver 情報から描画用 VectorDrawable を解決する。
 *
 * 解決の優先順位:
 * 1. `type` と `maneuverModifier` の完全一致
 * 2. `type` 単独のアイコン
 * 3. `"turn"` + `maneuverModifier` へのフォールバック
 * 4. 最終フォールバックとして直進アイコン
 */
fun maneuverIconResource(type: String, maneuverModifier: String?): DrawableResource {
    val normalizedType = type.normalizeKey()
    val normalizedModifier = maneuverModifier?.normalizeKey()

    return exactDirectionIcon(normalizedType, normalizedModifier)
        ?: typeOnlyDirectionIcon(normalizedType)
        ?: exactDirectionIcon(TURN_TYPE, normalizedModifier)
        ?: Res.drawable.direction_continue
}

private fun String.normalizeKey(): String = trim().lowercase().replace(' ', '_')

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun exactDirectionIcon(type: String, modifier: String?): DrawableResource? {
    if (modifier == null) return null
    return when (type) {
        "arrive" -> when (modifier) {
            "left" -> Res.drawable.direction_arrive_left
            "right" -> Res.drawable.direction_arrive_right
            "straight" -> Res.drawable.direction_arrive_straight
            else -> null
        }
        "continue" -> when (modifier) {
            "left" -> Res.drawable.direction_continue_left
            "right" -> Res.drawable.direction_continue_right
            "slight_left" -> Res.drawable.direction_continue_slight_left
            "slight_right" -> Res.drawable.direction_continue_slight_right
            "straight" -> Res.drawable.direction_continue_straight
            "uturn" -> Res.drawable.direction_continue_uturn
            else -> null
        }
        "depart" -> when (modifier) {
            "left" -> Res.drawable.direction_depart_left
            "right" -> Res.drawable.direction_depart_right
            "straight" -> Res.drawable.direction_depart_straight
            else -> null
        }
        "end_of_road" -> when (modifier) {
            "left" -> Res.drawable.direction_end_of_road_left
            "right" -> Res.drawable.direction_end_of_road_right
            else -> null
        }
        "fork" -> when (modifier) {
            "left" -> Res.drawable.direction_fork_left
            "right" -> Res.drawable.direction_fork_right
            "slight_left" -> Res.drawable.direction_fork_slight_left
            "slight_right" -> Res.drawable.direction_fork_slight_right
            "straight" -> Res.drawable.direction_fork_straight
            else -> null
        }
        "invalid" -> when (modifier) {
            "left" -> Res.drawable.direction_invalid_left
            "right" -> Res.drawable.direction_invalid_right
            "slight_left" -> Res.drawable.direction_invalid_slight_left
            "slight_right" -> Res.drawable.direction_invalid_slight_right
            "straight" -> Res.drawable.direction_invalid_straight
            "uturn" -> Res.drawable.direction_invalid_uturn
            else -> null
        }
        "merge" -> when (modifier) {
            "left" -> Res.drawable.direction_merge_left
            "right" -> Res.drawable.direction_merge_right
            "slight_left" -> Res.drawable.direction_merge_slight_left
            "slight_right" -> Res.drawable.direction_merge_slight_right
            "straight" -> Res.drawable.direction_merge_straight
            else -> null
        }
        "new_name" -> when (modifier) {
            "left" -> Res.drawable.direction_new_name_left
            "right" -> Res.drawable.direction_new_name_right
            "sharp_left" -> Res.drawable.direction_new_name_sharp_left
            "sharp_right" -> Res.drawable.direction_new_name_sharp_right
            "slight_left" -> Res.drawable.direction_new_name_slight_left
            "slight_right" -> Res.drawable.direction_new_name_slight_right
            "straight" -> Res.drawable.direction_new_name_straight
            else -> null
        }
        "notification" -> when (modifier) {
            "left" -> Res.drawable.direction_notification_left
            "right" -> Res.drawable.direction_notification_right
            "sharp_left" -> Res.drawable.direction_notification_sharp_left
            "sharp_right" -> Res.drawable.direction_notification_sharp_right
            "slight_left" -> Res.drawable.direction_notification_slight_left
            "slight_right" -> Res.drawable.direction_notification_slight_right
            "straight" -> Res.drawable.direction_notification_straight
            else -> null
        }
        "off_ramp" -> when (modifier) {
            "left" -> Res.drawable.direction_off_ramp_left
            "right" -> Res.drawable.direction_off_ramp_right
            "slight_left" -> Res.drawable.direction_off_ramp_slight_left
            "slight_right" -> Res.drawable.direction_off_ramp_slight_right
            else -> null
        }
        "on_ramp" -> when (modifier) {
            "left" -> Res.drawable.direction_on_ramp_left
            "right" -> Res.drawable.direction_on_ramp_right
            "sharp_left" -> Res.drawable.direction_on_ramp_sharp_left
            "sharp_right" -> Res.drawable.direction_on_ramp_sharp_right
            "slight_left" -> Res.drawable.direction_on_ramp_slight_left
            "slight_right" -> Res.drawable.direction_on_ramp_slight_right
            "straight" -> Res.drawable.direction_on_ramp_straight
            else -> null
        }
        "rotary" -> when (modifier) {
            "left" -> Res.drawable.direction_rotary_left
            "right" -> Res.drawable.direction_rotary_right
            "sharp_left" -> Res.drawable.direction_rotary_sharp_left
            "sharp_right" -> Res.drawable.direction_rotary_sharp_right
            "slight_left" -> Res.drawable.direction_rotary_slight_left
            "slight_right" -> Res.drawable.direction_rotary_slight_right
            "straight" -> Res.drawable.direction_rotary_straight
            else -> null
        }
        "roundabout" -> when (modifier) {
            "left" -> Res.drawable.direction_roundabout_left
            "right" -> Res.drawable.direction_roundabout_right
            "sharp_left" -> Res.drawable.direction_roundabout_sharp_left
            "sharp_right" -> Res.drawable.direction_roundabout_sharp_right
            "slight_left" -> Res.drawable.direction_roundabout_slight_left
            "slight_right" -> Res.drawable.direction_roundabout_slight_right
            "straight" -> Res.drawable.direction_roundabout_straight
            else -> null
        }
        "traffic_circle" -> when (modifier) {
            "left" -> Res.drawable.direction_traffic_circle_left
            "right" -> Res.drawable.direction_traffic_circle_right
            "slight_left" -> Res.drawable.direction_traffic_circle_slight_left
            "slight_right" -> Res.drawable.direction_traffic_circle_slight_right
            else -> null
        }
        "turn" -> when (modifier) {
            "left" -> Res.drawable.direction_turn_left
            "right" -> Res.drawable.direction_turn_right
            "sharp_left" -> Res.drawable.direction_turn_sharp_left
            "sharp_right" -> Res.drawable.direction_turn_sharp_right
            "slight_left" -> Res.drawable.direction_turn_slight_left
            "slight_right" -> Res.drawable.direction_turn_slight_right
            "straight" -> Res.drawable.direction_turn_straight
            else -> null
        }
        else -> null
    }
}

private fun typeOnlyDirectionIcon(type: String): DrawableResource? {
    return when (type) {
        "arrive" -> Res.drawable.direction_arrive
        "close" -> Res.drawable.direction_close
        "continue" -> Res.drawable.direction_continue
        "depart" -> Res.drawable.direction_depart
        "flag" -> Res.drawable.direction_flag
        "fork" -> Res.drawable.direction_fork
        "invalid" -> Res.drawable.direction_invalid
        "off_ramp" -> Res.drawable.direction_off_ramp
        "on_ramp" -> Res.drawable.direction_on_ramp
        "ramp" -> Res.drawable.direction_ramp
        "rotary" -> Res.drawable.direction_rotary
        "roundabout" -> Res.drawable.direction_roundabout
        "traffic_circle" -> Res.drawable.direction_traffic_circle
        "updown" -> Res.drawable.direction_updown
        "uturn" -> Res.drawable.direction_uturn
        else -> null
    }
}

private const val TURN_TYPE = "turn"
