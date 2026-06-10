package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** [MapHostInsets] の合成処理を検証するテスト。 */
class MapHostInsetsTest {

    @Test
    fun `withAddedHorizontal は左右だけに inset を加算する`() {
        val insets = MapHostInsets(
            start = 4.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = 16.dp,
        )

        val result = insets.withAddedHorizontal(20.dp)

        assertEquals(
            expected = MapHostInsets(
                start = 24.dp,
                top = 8.dp,
                end = 32.dp,
                bottom = 16.dp,
            ),
            actual = result,
        )
    }

    @Test
    fun `maxWith は各辺の大きい inset を採用する`() {
        val result = MapHostInsets(
            start = 4.dp,
            top = 20.dp,
            end = 12.dp,
            bottom = 16.dp,
        ).maxWith(
            MapHostInsets(
                start = 8.dp,
                top = 10.dp,
                end = 24.dp,
                bottom = 6.dp,
            ),
        )

        assertEquals(
            expected = MapHostInsets(
                start = 8.dp,
                top = 20.dp,
                end = 24.dp,
                bottom = 16.dp,
            ),
            actual = result,
        )
    }
}
