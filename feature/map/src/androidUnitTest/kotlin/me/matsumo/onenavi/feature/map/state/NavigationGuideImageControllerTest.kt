package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [NavigationGuideImageController] の案内画像取得と cache 制御のテスト。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationGuideImageControllerTest {

    @Test
    fun `同じ key は cache から表示し再取得しない`() = runTest {
        val guideImageKey = guideImageKeyOf(minor = 100)
        val guideImage = navigationGuideImageOf(guideImageKey)
        val loader = FakeNavigationGuideImageLoader().apply {
            results[guideImageKey] = Result.success(guideImage)
        }
        val displayedImages = mutableListOf<NavigationGuideImage?>()
        val controller = NavigationGuideImageController(
            loader = loader,
            scope = this,
            imageChanged = { navigationGuideImage -> displayedImages += navigationGuideImage },
        )

        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()
        controller.onGuideImageKeyChanged(null)
        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()

        assertEquals(1, loader.loadCount(guideImageKey))
        assertEquals(guideImage, displayedImages.last())
    }

    @Test
    fun `null 結果は cache から表示し再取得しない`() = runTest {
        val guideImageKey = guideImageKeyOf(minor = 100)
        val loader = FakeNavigationGuideImageLoader().apply {
            results[guideImageKey] = Result.success(null)
        }
        val displayedImages = mutableListOf<NavigationGuideImage?>()
        val controller = NavigationGuideImageController(
            loader = loader,
            scope = this,
            imageChanged = { navigationGuideImage -> displayedImages += navigationGuideImage },
        )

        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()
        controller.onGuideImageKeyChanged(null)
        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()

        assertEquals(1, loader.loadCount(guideImageKey))
        assertNull(displayedImages.last())
    }

    @Test
    fun `取得失敗は null として cache し再取得しない`() = runTest {
        val guideImageKey = guideImageKeyOf(minor = 100)
        val loader = FakeNavigationGuideImageLoader().apply {
            results[guideImageKey] = Result.failure(IllegalStateException("failed"))
        }
        val displayedImages = mutableListOf<NavigationGuideImage?>()
        val controller = NavigationGuideImageController(
            loader = loader,
            scope = this,
            imageChanged = { navigationGuideImage -> displayedImages += navigationGuideImage },
        )

        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()
        controller.onGuideImageKeyChanged(null)
        controller.onGuideImageKeyChanged(guideImageKey)
        advanceUntilIdle()

        assertEquals(1, loader.loadCount(guideImageKey))
        assertNull(displayedImages.last())
    }

    @Test
    fun `遅い取得結果は key 遷移後に表示しない`() = runTest {
        val slowGuideImageKey = guideImageKeyOf(minor = 100)
        val nextGuideImageKey = guideImageKeyOf(minor = 200)
        val slowGuideImage = navigationGuideImageOf(slowGuideImageKey)
        val nextGuideImage = navigationGuideImageOf(nextGuideImageKey)
        val loader = FakeNavigationGuideImageLoader().apply {
            results[slowGuideImageKey] = Result.success(slowGuideImage)
            results[nextGuideImageKey] = Result.success(nextGuideImage)
            delaysMillis[slowGuideImageKey] = 1_000L
        }
        val displayedImages = mutableListOf<NavigationGuideImage?>()
        val controller = NavigationGuideImageController(
            loader = loader,
            scope = this,
            imageChanged = { navigationGuideImage -> displayedImages += navigationGuideImage },
        )

        controller.onGuideImageKeyChanged(slowGuideImageKey)
        runCurrent()
        controller.onGuideImageKeyChanged(nextGuideImageKey)
        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertEquals(nextGuideImage, displayedImages.last())
        assertFalse(displayedImages.contains(slowGuideImage))
    }

    @Test
    fun `cache は最大件数を超えると古い key を破棄する`() = runTest {
        val firstGuideImageKey = guideImageKeyOf(minor = 100)
        val secondGuideImageKey = guideImageKeyOf(minor = 200)
        val loader = FakeNavigationGuideImageLoader().apply {
            results[firstGuideImageKey] = Result.success(navigationGuideImageOf(firstGuideImageKey))
            results[secondGuideImageKey] = Result.success(navigationGuideImageOf(secondGuideImageKey))
        }
        val controller = NavigationGuideImageController(
            loader = loader,
            scope = this,
            imageChanged = {},
            maxCacheSize = 1,
        )

        controller.onGuideImageKeyChanged(firstGuideImageKey)
        advanceUntilIdle()
        controller.onGuideImageKeyChanged(secondGuideImageKey)
        advanceUntilIdle()
        controller.onGuideImageKeyChanged(firstGuideImageKey)
        advanceUntilIdle()

        assertEquals(2, loader.loadCount(firstGuideImageKey))
        assertEquals(1, loader.loadCount(secondGuideImageKey))
    }
}

private fun guideImageKeyOf(minor: Int): GuideImageKey = GuideImageKey(
    major = 101,
    minor = minor,
)

private fun navigationGuideImageOf(guideImageKey: GuideImageKey): NavigationGuideImage = NavigationGuideImage(
    key = guideImageKey,
    bitmap = FakeImageBitmap(),
)

/**
 * Android framework に依存しないテスト用 bitmap。
 */
private class FakeImageBitmap : ImageBitmap {
    override val width: Int = 1
    override val height: Int = 1
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = true
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}

/**
 * 案内画像 loader の fake。
 */
private class FakeNavigationGuideImageLoader : NavigationGuideImageLoader {
    val results = mutableMapOf<GuideImageKey, Result<NavigationGuideImage?>>()
    val delaysMillis = mutableMapOf<GuideImageKey, Long>()
    private val loadCounts = mutableMapOf<GuideImageKey, Int>()

    override suspend fun load(guideImageKey: GuideImageKey): Result<NavigationGuideImage?> {
        loadCounts[guideImageKey] = loadCount(guideImageKey) + 1
        delaysMillis[guideImageKey]?.let { delayMillis -> delay(delayMillis) }
        return results[guideImageKey] ?: Result.success(null)
    }

    fun loadCount(guideImageKey: GuideImageKey): Int {
        return loadCounts[guideImageKey] ?: 0
    }
}
