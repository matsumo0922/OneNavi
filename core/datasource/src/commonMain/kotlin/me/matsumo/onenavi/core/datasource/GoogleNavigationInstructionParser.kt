package me.matsumo.onenavi.core.datasource

internal data class ParsedGoogleManeuver(
    val type: String,
    val modifier: String?,
)

internal fun parseGoogleManeuver(raw: String?): ParsedGoogleManeuver {
    val maneuver = raw
        ?.trim()
        ?.uppercase()
        ?.replace('-', '_')
        .orEmpty()

    return when {
        maneuver == "STRAIGHT" -> ParsedGoogleManeuver(type = "continue", modifier = "straight")
        maneuver == "MERGE" || maneuver == "MERGE_UNSPECIFIED" -> ParsedGoogleManeuver(type = "merge", modifier = "straight")
        maneuver == "NAME_CHANGE" -> ParsedGoogleManeuver(type = "new_name", modifier = "straight")
        maneuver == "DESTINATION" -> ParsedGoogleManeuver(type = "arrive", modifier = null)
        maneuver == "DEPART" -> ParsedGoogleManeuver(type = "depart", modifier = null)
        maneuver == "FERRY" || maneuver == "FERRY_TRAIN" -> ParsedGoogleManeuver(type = "continue", modifier = "straight")
        maneuver.startsWith("UTURN_") -> ParsedGoogleManeuver(type = "uturn", modifier = null)
        maneuver.startsWith("TURN_") -> ParsedGoogleManeuver(type = "turn", modifier = maneuver.removePrefix("TURN_").toGuidanceModifier())
        maneuver.startsWith("RAMP_") -> ParsedGoogleManeuver(type = "on ramp", modifier = maneuver.removePrefix("RAMP_").toGuidanceModifier())
        maneuver.startsWith("OFF_RAMP_") -> ParsedGoogleManeuver(type = "off ramp", modifier = maneuver.removePrefix("OFF_RAMP_").toGuidanceModifier())
        maneuver.startsWith("ON_RAMP_") -> ParsedGoogleManeuver(type = "on ramp", modifier = maneuver.removePrefix("ON_RAMP_").toGuidanceModifier())
        maneuver.startsWith("FORK_") -> ParsedGoogleManeuver(type = "fork", modifier = maneuver.removePrefix("FORK_").toGuidanceModifier())
        maneuver.startsWith("MERGE_") -> ParsedGoogleManeuver(type = "merge", modifier = maneuver.removePrefix("MERGE_").toGuidanceModifier())
        maneuver.startsWith("ROUNDABOUT_") -> ParsedGoogleManeuver(type = "roundabout", modifier = maneuver.removePrefix("ROUNDABOUT_").toGuidanceModifier())
        maneuver.startsWith("ROTARY_") -> ParsedGoogleManeuver(type = "rotary", modifier = maneuver.removePrefix("ROTARY_").toGuidanceModifier())
        maneuver.startsWith("TRAFFIC_CIRCLE_") -> ParsedGoogleManeuver(type = "traffic_circle", modifier = maneuver.removePrefix("TRAFFIC_CIRCLE_").toGuidanceModifier())
        maneuver.startsWith("DESTINATION_") -> ParsedGoogleManeuver(type = "arrive", modifier = maneuver.removePrefix("DESTINATION_").toGuidanceModifier())
        maneuver.startsWith("DEPART_") -> ParsedGoogleManeuver(type = "depart", modifier = maneuver.removePrefix("DEPART_").toGuidanceModifier())
        else -> ParsedGoogleManeuver(type = "continue", modifier = if (maneuver.isBlank()) null else "straight")
    }
}

private fun String.toGuidanceModifier(): String {
    return when (this) {
        "LEFT" -> "left"
        "RIGHT" -> "right"
        "SLIGHT_LEFT", "KEEP_LEFT" -> "slight left"
        "SLIGHT_RIGHT", "KEEP_RIGHT" -> "slight right"
        "SHARP_LEFT" -> "sharp left"
        "SHARP_RIGHT" -> "sharp right"
        "STRAIGHT", "UNSPECIFIED" -> "straight"
        else -> "straight"
    }
}
