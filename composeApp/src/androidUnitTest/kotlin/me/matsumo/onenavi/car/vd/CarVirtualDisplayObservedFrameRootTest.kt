package me.matsumo.onenavi.car.vd

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.feature.map.state.MapHostInsets
import me.matsumo.onenavi.feature.map.state.MapHostViewport
import kotlin.test.Test
import kotlin.test.assertEquals

/** VD observed frame root の viewport 変換を検証するテスト。 */
class CarVirtualDisplayObservedFrameRootTest {

    @Test
    fun `observedFramePaddingValues は縦方向の visible area を content padding にしない`() {
        val viewport = CarVirtualDisplayProbeViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 800,
            densityDpi = 160,
            visibleLeft = 100,
            visibleTop = 50,
            visibleRight = 900,
            visibleBottom = 700,
            stableLeft = 0,
            stableTop = 0,
            stableRight = 1_000,
            stableBottom = 800,
        )

        val paddingValues = viewport.observedFramePaddingValues(Density(1f))

        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateTopPadding(),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateBottomPadding(),
        )
    }

    @Test
    fun `toMapHostViewport は visible と stable を host inset へ変換する`() {
        val viewport = CarVirtualDisplayProbeViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 800,
            densityDpi = 160,
            visibleLeft = 100,
            visibleTop = 50,
            visibleRight = 900,
            visibleBottom = 700,
            stableLeft = 160,
            stableTop = 90,
            stableRight = 860,
            stableBottom = 640,
        )

        val hostViewport = viewport.toMapHostViewport(Density(1f))

        assertEquals(
            expected = MapHostViewport(
                visibleInsets = MapHostInsets(
                    start = 100.dp,
                    top = 50.dp,
                    end = 100.dp,
                    bottom = 100.dp,
                ),
                stableInsets = MapHostInsets(
                    start = 160.dp,
                    top = 90.dp,
                    end = 140.dp,
                    bottom = 160.dp,
                ),
            ),
            actual = hostViewport,
        )
    }

    @Test
    fun `observedFramePaddingValues は横 split の実描画 frame だけ content padding にする`() {
        val viewport = CarVirtualDisplayProbeViewport(
            surfaceWidth = 1_000,
            surfaceHeight = 800,
            densityDpi = 160,
            visibleLeft = 300,
            visibleTop = 50,
            visibleRight = 1_000,
            visibleBottom = 700,
            stableLeft = 330,
            stableTop = 90,
            stableRight = 980,
            stableBottom = 640,
        )

        val paddingValues = viewport.observedFramePaddingValues(Density(1f))

        assertEquals(
            expected = 300.dp,
            actual = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateTopPadding(),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
        )
        assertEquals(
            expected = 0.dp,
            actual = paddingValues.calculateBottomPadding(),
        )
    }
}
