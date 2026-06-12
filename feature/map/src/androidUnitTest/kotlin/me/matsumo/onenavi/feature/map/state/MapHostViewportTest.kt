package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** 地図 host viewport 由来の inset 解決を検証するテスト。 */
class MapHostViewportTest {

    @Test
    fun `host stable inset が system bar より大きい辺では host stable inset を採用する`() {
        val systemBarInsets = MapHostInsets(
            top = 24.dp,
            bottom = 34.dp,
        )
        val hostStableInsets = MapHostInsets(
            start = 12.dp,
            top = 80.dp,
            end = 20.dp,
            bottom = 200.dp,
        )

        val actual = resolveMapContentInsets(systemBarInsets, hostStableInsets)

        assertEquals(
            expected = MapHostInsets(
                start = 12.dp,
                top = 80.dp,
                end = 20.dp,
                bottom = 200.dp,
            ),
            actual = actual,
        )
    }

    @Test
    fun `host stable inset が無い電話表示では system bar inset を維持する`() {
        val systemBarInsets = MapHostInsets(
            top = 24.dp,
            bottom = 34.dp,
        )
        val hostStableInsets = MapHostInsets.Zero

        val actual = resolveMapContentInsets(systemBarInsets, hostStableInsets)

        assertEquals(systemBarInsets, actual)
    }

    @Test
    fun `host stable inset が system bar より小さい辺では system bar inset を維持する`() {
        val systemBarInsets = MapHostInsets(
            top = 24.dp,
            bottom = 34.dp,
        )
        val hostStableInsets = MapHostInsets(
            top = 8.dp,
            bottom = 16.dp,
        )

        val actual = resolveMapContentInsets(systemBarInsets, hostStableInsets)

        assertEquals(systemBarInsets, actual)
    }
}
