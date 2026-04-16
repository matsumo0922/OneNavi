package me.matsumo.onenavi.core.datasource

import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleNavigationInstructionParserTest {

    @Test
    fun `TURN_LEFT maps to left turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = "turn", modifier = "left"),
            parseGoogleManeuver("TURN_LEFT"),
        )
    }

    @Test
    fun `UTURN_LEFT maps to uturn icon instead of left turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = "uturn", modifier = null),
            parseGoogleManeuver("UTURN_LEFT"),
        )
    }

    @Test
    fun `FORK_RIGHT stays fork instead of generic turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = "fork", modifier = "right"),
            parseGoogleManeuver("FORK_RIGHT"),
        )
    }

    @Test
    fun `MERGE without side uses merge straight icon`() {
        assertEquals(
            ParsedGoogleManeuver(type = "merge", modifier = "straight"),
            parseGoogleManeuver("MERGE"),
        )
    }

    @Test
    fun `NAME_CHANGE maps to new name straight icon`() {
        assertEquals(
            ParsedGoogleManeuver(type = "new_name", modifier = "straight"),
            parseGoogleManeuver("NAME_CHANGE"),
        )
    }

    @Test
    fun `RAMP_LEFT maps to ramp icon family instead of turn`() {
        assertEquals(
            ParsedGoogleManeuver(type = "on ramp", modifier = "left"),
            parseGoogleManeuver("RAMP_LEFT"),
        )
    }

    @Test
    fun `OFF_RAMP_KEEP_LEFT maps to slight left off ramp`() {
        assertEquals(
            ParsedGoogleManeuver(type = "off ramp", modifier = "slight left"),
            parseGoogleManeuver("OFF_RAMP_KEEP_LEFT"),
        )
    }

    @Test
    fun `unknown maneuver falls back to continue straight`() {
        assertEquals(
            ParsedGoogleManeuver(type = "continue", modifier = "straight"),
            parseGoogleManeuver("SOMETHING_NEW"),
        )
    }
}
