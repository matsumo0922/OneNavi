package me.matsumo.onenavi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompassDirectionTest {

    @Test
    fun `parse extracts single-char directions`() {
        assertEquals(CompassDirection.NORTH, CompassDirection.parse("北に進む"))
        assertEquals(CompassDirection.SOUTH, CompassDirection.parse("南に進みます"))
        assertEquals(CompassDirection.EAST, CompassDirection.parse("東に向かいます"))
        assertEquals(CompassDirection.WEST, CompassDirection.parse("西に進みます"))
    }

    @Test
    fun `parse prefers compound directions over single-char ones`() {
        assertEquals(CompassDirection.NORTHEAST, CompassDirection.parse("北東に進みます"))
        assertEquals(CompassDirection.NORTHWEST, CompassDirection.parse("北西に進みます"))
        assertEquals(CompassDirection.SOUTHEAST, CompassDirection.parse("南東に進みます"))
        assertEquals(CompassDirection.SOUTHWEST, CompassDirection.parse("南西に進みます"))
    }

    @Test
    fun `parse picks first matching compound even if embedded in sentence`() {
        assertEquals(
            CompassDirection.NORTHEAST,
            CompassDirection.parse("メインストリートを北東方向に進みます"),
        )
    }

    @Test
    fun `parse returns null when no direction keyword is present`() {
        assertNull(CompassDirection.parse("Head on the road"))
        assertNull(CompassDirection.parse("出発します"))
        assertNull(CompassDirection.parse(""))
    }
}
