package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType

/**
 * Google Routes API の `navigationInstruction.maneuver` 文字列をパースした結果。
 *
 * @param type マニューバ種別（UI のアイコン選択に使用）。
 * @param modifier 方向修飾子。該当しない場合は null。
 */
internal data class ParsedGoogleManeuver(
    val type: ManeuverType,
    val modifier: ManeuverModifier?,
)

internal fun parseGoogleManeuver(raw: String?): ParsedGoogleManeuver {
    val maneuver = raw
        ?.trim()
        ?.uppercase()
        ?.replace('-', '_')
        .orEmpty()

    return when {
        maneuver == "STRAIGHT" -> ParsedGoogleManeuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT)
        maneuver == "MERGE" || maneuver == "MERGE_UNSPECIFIED" ->
            ParsedGoogleManeuver(ManeuverType.MERGE, ManeuverModifier.STRAIGHT)
        maneuver == "NAME_CHANGE" -> ParsedGoogleManeuver(ManeuverType.NAME_CHANGE, ManeuverModifier.STRAIGHT)
        maneuver == "DESTINATION" -> ParsedGoogleManeuver(ManeuverType.ARRIVE, null)
        maneuver == "DEPART" -> ParsedGoogleManeuver(ManeuverType.DEPART, null)
        maneuver == "FERRY" || maneuver == "FERRY_TRAIN" ->
            ParsedGoogleManeuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT)
        maneuver.startsWith("UTURN_") -> ParsedGoogleManeuver(ManeuverType.UTURN, null)
        maneuver.startsWith("TURN_") ->
            ParsedGoogleManeuver(ManeuverType.TURN, maneuver.removePrefix("TURN_").toGuidanceModifier())
        maneuver.startsWith("RAMP_") ->
            ParsedGoogleManeuver(ManeuverType.ON_RAMP, maneuver.removePrefix("RAMP_").toGuidanceModifier())
        maneuver.startsWith("OFF_RAMP_") ->
            ParsedGoogleManeuver(ManeuverType.OFF_RAMP, maneuver.removePrefix("OFF_RAMP_").toGuidanceModifier())
        maneuver.startsWith("ON_RAMP_") ->
            ParsedGoogleManeuver(ManeuverType.ON_RAMP, maneuver.removePrefix("ON_RAMP_").toGuidanceModifier())
        maneuver.startsWith("FORK_") ->
            ParsedGoogleManeuver(ManeuverType.FORK, maneuver.removePrefix("FORK_").toGuidanceModifier())
        maneuver.startsWith("MERGE_") ->
            ParsedGoogleManeuver(ManeuverType.MERGE, maneuver.removePrefix("MERGE_").toGuidanceModifier())
        maneuver.startsWith("ROUNDABOUT_") ->
            ParsedGoogleManeuver(ManeuverType.ROUNDABOUT, maneuver.removePrefix("ROUNDABOUT_").toGuidanceModifier())
        maneuver.startsWith("ROTARY_") ->
            ParsedGoogleManeuver(ManeuverType.ROTARY, maneuver.removePrefix("ROTARY_").toGuidanceModifier())
        maneuver.startsWith("TRAFFIC_CIRCLE_") ->
            ParsedGoogleManeuver(
                ManeuverType.TRAFFIC_CIRCLE,
                maneuver.removePrefix("TRAFFIC_CIRCLE_").toGuidanceModifier(),
            )
        maneuver.startsWith("DESTINATION_") ->
            ParsedGoogleManeuver(ManeuverType.ARRIVE, maneuver.removePrefix("DESTINATION_").toGuidanceModifier())
        maneuver.startsWith("DEPART_") ->
            ParsedGoogleManeuver(ManeuverType.DEPART, maneuver.removePrefix("DEPART_").toGuidanceModifier())
        else -> ParsedGoogleManeuver(
            type = ManeuverType.CONTINUE,
            modifier = if (maneuver.isBlank()) null else ManeuverModifier.STRAIGHT,
        )
    }
}

private fun String.toGuidanceModifier(): ManeuverModifier {
    return when (this) {
        "LEFT" -> ManeuverModifier.LEFT
        "RIGHT" -> ManeuverModifier.RIGHT
        "SLIGHT_LEFT", "KEEP_LEFT" -> ManeuverModifier.SLIGHT_LEFT
        "SLIGHT_RIGHT", "KEEP_RIGHT" -> ManeuverModifier.SLIGHT_RIGHT
        "SHARP_LEFT" -> ManeuverModifier.SHARP_LEFT
        "SHARP_RIGHT" -> ManeuverModifier.SHARP_RIGHT
        "STRAIGHT", "UNSPECIFIED" -> ManeuverModifier.STRAIGHT
        else -> ManeuverModifier.STRAIGHT
    }
}
