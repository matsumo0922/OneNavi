package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleNavigationInstructionParserTest {

    @Test
    fun `TURN_LEFT maps to left turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.TURN, modifier = ManeuverModifier.LEFT),
            parseGoogleManeuver("TURN_LEFT"),
        )
    }

    @Test
    fun `UTURN_LEFT maps to uturn icon instead of left turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.UTURN, modifier = null),
            parseGoogleManeuver("UTURN_LEFT"),
        )
    }

    @Test
    fun `FORK_RIGHT stays fork instead of generic turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.FORK, modifier = ManeuverModifier.RIGHT),
            parseGoogleManeuver("FORK_RIGHT"),
        )
    }

    @Test
    fun `MERGE without side uses merge straight icon`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.MERGE, modifier = ManeuverModifier.STRAIGHT),
            parseGoogleManeuver("MERGE"),
        )
    }

    @Test
    fun `NAME_CHANGE maps to new name straight icon`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.NAME_CHANGE, modifier = ManeuverModifier.STRAIGHT),
            parseGoogleManeuver("NAME_CHANGE"),
        )
    }

    @Test
    fun `RAMP_LEFT maps to ramp icon family instead of turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.ON_RAMP, modifier = ManeuverModifier.LEFT),
            parseGoogleManeuver("RAMP_LEFT"),
        )
    }

    @Test
    fun `OFF_RAMP_KEEP_LEFT maps to slight left off ramp`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.OFF_RAMP, modifier = ManeuverModifier.SLIGHT_LEFT),
            parseGoogleManeuver("OFF_RAMP_KEEP_LEFT"),
        )
    }

    @Test
    fun `unknown maneuver falls back to continue straight`() {
        assertEquals(
            ParsedGoogleManeuver(type = ManeuverType.CONTINUE, modifier = ManeuverModifier.STRAIGHT),
            parseGoogleManeuver("SOMETHING_NEW"),
        )
    }
}
