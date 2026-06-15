package me.matsumo.onenavi.core.common.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** [parseAssistantNavUri] の解釈テスト。 */
class AssistantNavUriParserTest {

    @Test
    fun navigateActionsParseNavigationIntent() {
        val actions = listOf(
            "androidx.car.app.action.NAVIGATE",
            "android.intent.action.NAVIGATE",
        )

        for (action in actions) {
            val request = parseAssistantNavUri(
                uri = "geo:0,0?q=%E6%96%B0%E5%AE%BF%E9%A7%85&intent=navigation",
                action = action,
            )
            val navigate = assertIs<AssistantNavRequest.Navigate>(request)

            assertEquals("新宿駅", navigate.query)
            assertNull(navigate.coordinate)
        }
    }

    @Test
    fun navigateActionsParseDirectionsIntent() {
        val actions = listOf(
            "androidx.car.app.action.NAVIGATE",
            "android.intent.action.NAVIGATE",
        )

        for (action in actions) {
            val request = parseAssistantNavUri(
                uri = "geo:35.681236,139.767125?q=Tokyo%20Station&intent=directions",
                action = action,
            )
            val preview = assertIs<AssistantNavRequest.Preview>(request)

            assertEquals("Tokyo Station", preview.query)
            assertEquals(AssistantNavCoordinate(35.681236, 139.767125), preview.coordinate)
        }
    }

    @Test
    fun navigateActionsParseAddStopIntent() {
        val actions = listOf(
            "androidx.car.app.action.NAVIGATE",
            "android.intent.action.NAVIGATE",
        )

        for (action in actions) {
            val request = parseAssistantNavUri(
                uri = "geo:35.170915,136.881537?intent=add_a_stop&mode=d",
                action = action,
            )
            val addStop = assertIs<AssistantNavRequest.AddStop>(request)

            assertNull(addStop.query)
            assertEquals(AssistantNavCoordinate(35.170915, 136.881537), addStop.coordinate)
        }
    }

    @Test
    fun navigateActionsDefaultToNavigationWhenIntentIsMissing() {
        val actions = listOf(
            "androidx.car.app.action.NAVIGATE",
            "android.intent.action.NAVIGATE",
        )

        for (action in actions) {
            val request = parseAssistantNavUri(
                uri = "geo:0,0?q=%E6%B8%8B%E8%B0%B7%E9%A7%85",
                action = action,
            )
            val navigate = assertIs<AssistantNavRequest.Navigate>(request)

            assertEquals("渋谷駅", navigate.query)
            assertNull(navigate.coordinate)
        }
    }

    @Test
    fun viewActionAlwaysParsesSearch() {
        val request = parseAssistantNavUri(
            uri = "geo:35.0,139.0?q=%E8%BF%91%E3%81%8F%E3%81%AE%E3%82%AB%E3%83%95%E3%82%A7&intent=navigation",
            action = "android.intent.action.VIEW",
        )
        val search = assertIs<AssistantNavRequest.Search>(request)

        assertEquals("近くのカフェ", search.query)
    }

    @Test
    fun zeroZeroCoordinateIsIgnoredAsSentinel() {
        val request = parseAssistantNavUri(
            uri = "geo:0,0?q=Tokyo+Tower&intent=navigation",
            action = "android.intent.action.NAVIGATE",
        )
        val navigate = assertIs<AssistantNavRequest.Navigate>(request)

        assertEquals("Tokyo Tower", navigate.query)
        assertNull(navigate.coordinate)
    }

    @Test
    fun returnsNullForUnsupportedOrIncompleteUri() {
        assertNull(
            parseAssistantNavUri(
                uri = "https://example.com/?q=Tokyo",
                action = "android.intent.action.NAVIGATE",
            ),
        )
        assertNull(
            parseAssistantNavUri(
                uri = "geo:0,0?intent=navigation",
                action = "android.intent.action.NAVIGATE",
            ),
        )
        assertNull(
            parseAssistantNavUri(
                uri = "geo:0,0?q=Tokyo&intent=unknown",
                action = "android.intent.action.NAVIGATE",
            ),
        )
        assertNull(
            parseAssistantNavUri(
                uri = "geo:not-a-number,139.0?intent=navigation",
                action = "android.intent.action.NAVIGATE",
            ),
        )
    }
}
