package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.drive.supporter.api.image.domain.GuideImage
import me.matsumo.drive.supporter.api.image.domain.GuideImageFormat
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ExtNavGuideImageGateway] の案内画像キー変換テスト。
 */
class ExtNavGuideImageGatewayTest {

    @Test
    fun `リクエスト ID は minor だけで重複排除する`() {
        val keys = listOf(
            GuideImageKey(major = 201, minor = 100),
            GuideImageKey(major = 5, minor = 100),
            GuideImageKey(major = 101, minor = 200),
            GuideImageKey(major = 101, minor = 200),
        )

        val requestIds = keys.toGuideImageRequestIds()

        assertEquals(listOf(100, 200), requestIds)
    }

    @Test
    fun `同一 minor の画像は major の違う key ごとに返す`() {
        val tollGateKey = GuideImageKey(major = 201, minor = 100)
        val junctionKey = GuideImageKey(major = 5, minor = 100)
        val responseBytes = byteArrayOf(1, 2, 3)
        val guideImage = GuideImage(
            id = 100,
            bytes = responseBytes,
            format = GuideImageFormat.Webp,
            isMissing = false,
        )
        val keysByMinor = listOf(tollGateKey, junctionKey).groupBy { key -> key.minor }

        val images = guideImage.toExtNavGuideImages(keysByMinor)

        assertEquals(2, images.size)
        assertEquals(setOf(tollGateKey, junctionKey), images.map { image -> image.key }.toSet())
        assertTrue(images.all { image -> image.bytes.contentEquals(responseBytes) })
    }
}
