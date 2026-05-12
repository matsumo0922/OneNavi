package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.tts_conjunction_beyond
import me.matsumo.onenavi.core.resource.tts_depart_east
import me.matsumo.onenavi.core.resource.tts_depart_north
import me.matsumo.onenavi.core.resource.tts_depart_northeast
import me.matsumo.onenavi.core.resource.tts_depart_northwest
import me.matsumo.onenavi.core.resource.tts_depart_south
import me.matsumo.onenavi.core.resource.tts_depart_southeast
import me.matsumo.onenavi.core.resource.tts_depart_southwest
import me.matsumo.onenavi.core.resource.tts_depart_west
import me.matsumo.onenavi.core.resource.tts_destination_approach
import me.matsumo.onenavi.core.resource.tts_direction_left_end
import me.matsumo.onenavi.core.resource.tts_direction_right_end
import me.matsumo.onenavi.core.resource.tts_direction_sharp_left_end
import me.matsumo.onenavi.core.resource.tts_direction_sharp_right_end
import me.matsumo.onenavi.core.resource.tts_direction_slight_left_end
import me.matsumo.onenavi.core.resource.tts_direction_slight_right_end
import me.matsumo.onenavi.core.resource.tts_direction_straight_end
import me.matsumo.onenavi.core.resource.tts_direction_uturn_end
import me.matsumo.onenavi.core.resource.tts_distance_2km
import me.matsumo.onenavi.core.resource.tts_distance_500m
import me.matsumo.onenavi.core.resource.tts_distance_approx_100m_at
import me.matsumo.onenavi.core.resource.tts_distance_approx_200m_at
import me.matsumo.onenavi.core.resource.tts_distance_approx_300m_at
import me.matsumo.onenavi.core.resource.tts_distance_approx_400m_at
import me.matsumo.onenavi.core.resource.tts_distance_approx_500m_at
import me.matsumo.onenavi.core.resource.tts_distance_approx_50m_at
import me.matsumo.onenavi.core.resource.tts_follow_traffic_rules
import me.matsumo.onenavi.core.resource.tts_fork_end
import me.matsumo.onenavi.core.resource.tts_lane_center
import me.matsumo.onenavi.core.resource.tts_lane_left_side
import me.matsumo.onenavi.core.resource.tts_lane_proceed
import me.matsumo.onenavi.core.resource.tts_lane_right_side
import me.matsumo.onenavi.core.resource.tts_merge
import me.matsumo.onenavi.core.resource.tts_merge_left
import me.matsumo.onenavi.core.resource.tts_merge_right
import me.matsumo.onenavi.core.resource.tts_navigation_finished
import me.matsumo.onenavi.core.resource.tts_navigation_started
import me.matsumo.onenavi.core.resource.tts_off_route
import me.matsumo.onenavi.core.resource.tts_on_route_recovered
import me.matsumo.onenavi.core.resource.tts_rerouted_found
import me.matsumo.onenavi.core.resource.tts_rerouted_start
import me.matsumo.onenavi.core.resource.tts_straightforward_long
import me.matsumo.onenavi.core.resource.tts_straightforward_short
import me.matsumo.onenavi.core.resource.tts_timing_imminent
import me.matsumo.onenavi.core.resource.tts_timing_very_imminent
import me.matsumo.onenavi.core.resource.tts_waypoint_approach
import org.jetbrains.compose.resources.StringResource

/**
 * TTS 発話フレーズの識別子。
 *
 * 各 entry は [resource] を通して strings.xml の固定文言に解決される。
 * フレーズの組み立ては [PhraseSegment] を介して行い、文字列化は `PhraseComposer.resolve()` 側で実施する。
 */
@Immutable
enum class TtsPhraseId(val resource: StringResource) {
    NAVIGATION_STARTED(Res.string.tts_navigation_started),
    FOLLOW_TRAFFIC_RULES(Res.string.tts_follow_traffic_rules),
    NAVIGATION_FINISHED(Res.string.tts_navigation_finished),

    DEPART_NORTH(Res.string.tts_depart_north),
    DEPART_NORTHEAST(Res.string.tts_depart_northeast),
    DEPART_EAST(Res.string.tts_depart_east),
    DEPART_SOUTHEAST(Res.string.tts_depart_southeast),
    DEPART_SOUTH(Res.string.tts_depart_south),
    DEPART_SOUTHWEST(Res.string.tts_depart_southwest),
    DEPART_WEST(Res.string.tts_depart_west),
    DEPART_NORTHWEST(Res.string.tts_depart_northwest),

    DISTANCE_2KM(Res.string.tts_distance_2km),
    DISTANCE_500M(Res.string.tts_distance_500m),
    TIMING_IMMINENT(Res.string.tts_timing_imminent),
    TIMING_VERY_IMMINENT(Res.string.tts_timing_very_imminent),

    CONJUNCTION_BEYOND(Res.string.tts_conjunction_beyond),
    DISTANCE_APPROX_50M_AT(Res.string.tts_distance_approx_50m_at),
    DISTANCE_APPROX_100M_AT(Res.string.tts_distance_approx_100m_at),
    DISTANCE_APPROX_200M_AT(Res.string.tts_distance_approx_200m_at),
    DISTANCE_APPROX_300M_AT(Res.string.tts_distance_approx_300m_at),
    DISTANCE_APPROX_400M_AT(Res.string.tts_distance_approx_400m_at),
    DISTANCE_APPROX_500M_AT(Res.string.tts_distance_approx_500m_at),

    DIR_STRAIGHT_END(Res.string.tts_direction_straight_end),
    DIR_SLIGHT_RIGHT_END(Res.string.tts_direction_slight_right_end),
    DIR_RIGHT_END(Res.string.tts_direction_right_end),
    DIR_SHARP_RIGHT_END(Res.string.tts_direction_sharp_right_end),
    DIR_UTURN_END(Res.string.tts_direction_uturn_end),
    DIR_SHARP_LEFT_END(Res.string.tts_direction_sharp_left_end),
    DIR_LEFT_END(Res.string.tts_direction_left_end),
    DIR_SLIGHT_LEFT_END(Res.string.tts_direction_slight_left_end),

    FORK_END(Res.string.tts_fork_end),
    MERGE_RIGHT(Res.string.tts_merge_right),
    MERGE_LEFT(Res.string.tts_merge_left),
    MERGE(Res.string.tts_merge),

    LANE_RIGHT_SIDE(Res.string.tts_lane_right_side),
    LANE_LEFT_SIDE(Res.string.tts_lane_left_side),
    LANE_CENTER(Res.string.tts_lane_center),
    LANE_PROCEED(Res.string.tts_lane_proceed),

    STRAIGHT_SHORT(Res.string.tts_straightforward_short),
    STRAIGHT_LONG(Res.string.tts_straightforward_long),

    OFF_ROUTE(Res.string.tts_off_route),
    ON_ROUTE_RECOVERED(Res.string.tts_on_route_recovered),
    REROUTED_FOUND(Res.string.tts_rerouted_found),
    REROUTED_START(Res.string.tts_rerouted_start),

    WAYPOINT_APPROACH(Res.string.tts_waypoint_approach),
    DESTINATION_APPROACH(Res.string.tts_destination_approach),
}
