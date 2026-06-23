package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.image.domain.GuideImage
import me.matsumo.drive.supporter.api.image.domain.GuideImageFormat
import me.matsumo.drive.supporter.api.image.domain.GuideImageRequest
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ExtNavGuideImageGateway] の案内画像キー変換・dedup・キャッシュテスト。
 */
class ExtNavGuideImageGatewayTest {

    @Test
    fun `preload は route 未登録なら失敗し backend を呼ばない`() = runTest {
        val backend = FakeGuideImageGatewayBackend()
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val result = gateway.preload(routeId = "missing-route")

        val exception = assertIs<ExtNavGuideImageRouteNotFoundException>(result.exceptionOrNull())
        assertEquals("missing-route", exception.routeId)
        assertEquals(0, backend.ensureSignedInCallCount)
        assertEquals(0, backend.fetchCallCount)
    }

    @Test
    fun `fetchAll は空 list なら認証と API を呼ばない`() = runTest {
        val backend = FakeGuideImageGatewayBackend()
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val images = gateway.fetchAll(emptyList()).getOrThrow()

        assertEquals(emptyList(), images)
        assertEquals(0, backend.ensureSignedInCallCount)
        assertEquals(0, backend.fetchCallCount)
    }

    @Test
    fun `fetchAll は認証失敗をそのまま返し API を呼ばない`() = runTest {
        val authFailure = IllegalStateException("auth failed")
        val backend = FakeGuideImageGatewayBackend().apply {
            signInResult = Result.failure(authFailure)
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val result = gateway.fetchAll(listOf(GuideImageKey(major = 5, minor = 100)))

        assertEquals(authFailure, result.exceptionOrNull())
        assertEquals(1, backend.ensureSignedInCallCount)
        assertEquals(0, backend.fetchCallCount)
    }

    @Test
    fun `fetch はレスポンスに対象 ID が無い場合 failure にする`() = runTest {
        val backend = FakeGuideImageGatewayBackend().apply {
            fetchResult = ApiResult.Success(persistentListOf())
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val result = gateway.fetch(GuideImageKey(major = 5, minor = 100))

        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertEquals(1, backend.ensureSignedInCallCount)
        assertEquals(1, backend.fetchCallCount)
    }

    @Test
    fun `fetchAll は同一 minor の画像を major の違う key ごとに返す`() = runTest {
        val tollGateKey = GuideImageKey(major = 201, minor = 100)
        val junctionKey = GuideImageKey(major = 5, minor = 100)
        val responseBytes = byteArrayOf(1, 2, 3)
        val backend = FakeGuideImageGatewayBackend().apply {
            fetchResult = ApiResult.Success(
                persistentListOf(
                    buildGuideImage(id = 100, bytes = responseBytes),
                ),
            )
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val images = gateway.fetchAll(listOf(tollGateKey, junctionKey, tollGateKey)).getOrThrow()

        assertEquals(listOf(100), requireNotNull(backend.lastRequest).ids.toList())
        assertEquals(2, images.size)
        assertEquals(setOf(tollGateKey, junctionKey), images.map { image -> image.key }.toSet())
        assertTrue(images.all { image -> image.bytes.contentEquals(responseBytes) })
    }

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

    // ---- 空 ID / 重複 ID dedup の regression tests ----

    @Test
    fun `filterValidKeys は minor が 0 以下のキーを除外する`() {
        val keys = listOf(
            GuideImageKey(major = 5, minor = 0),
            GuideImageKey(major = 5, minor = -1),
            GuideImageKey(major = 5, minor = 100),
            GuideImageKey(major = 201, minor = 200),
        )

        val validKeys = keys.filterValidKeys()

        assertEquals(
            listOf(
                GuideImageKey(major = 5, minor = 100),
                GuideImageKey(major = 201, minor = 200),
            ),
            validKeys,
        )
    }

    @Test
    fun `filterValidKeys は重複キーも除外する`() {
        val key = GuideImageKey(major = 5, minor = 100)
        val keys = listOf(key, key, GuideImageKey(major = 101, minor = 100))

        val validKeys = keys.filterValidKeys()

        assertEquals(
            listOf(
                GuideImageKey(major = 5, minor = 100),
                GuideImageKey(major = 101, minor = 100),
            ),
            validKeys,
        )
    }

    @Test
    fun `fetchAll は空 minor のキーのみなら API を呼ばない`() = runTest {
        val backend = FakeGuideImageGatewayBackend()
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val keys = listOf(
            GuideImageKey(major = 5, minor = 0),
            GuideImageKey(major = 201, minor = -1),
        )
        val images = gateway.fetchAll(keys).getOrThrow()

        assertEquals(emptyList(), images)
        assertEquals(0, backend.ensureSignedInCallCount)
        assertEquals(0, backend.fetchCallCount)
    }

    @Test
    fun `fetchAll は空 minor を除外して有効な ID だけ API に送る`() = runTest {
        val validKey = GuideImageKey(major = 5, minor = 100)
        val responseBytes = byteArrayOf(4, 5, 6)
        val backend = FakeGuideImageGatewayBackend().apply {
            fetchResult = ApiResult.Success(
                persistentListOf(
                    buildGuideImage(id = 100, bytes = responseBytes),
                ),
            )
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        val keys = listOf(
            GuideImageKey(major = 201, minor = 0),
            validKey,
            GuideImageKey(major = 101, minor = -5),
        )
        val images = gateway.fetchAll(keys).getOrThrow()

        assertEquals(listOf(100), requireNotNull(backend.lastRequest).ids.toList())
        assertEquals(1, images.size)
        assertEquals(validKey, images.first().key)
        assertTrue(images.first().bytes.contentEquals(responseBytes))
    }

    // ---- キャッシュ hit の regression tests ----

    @Test
    fun `fetchAll はキャッシュ済み minor に対して再 fetch しない`() = runTest {
        val key = GuideImageKey(major = 5, minor = 100)
        val responseBytes = byteArrayOf(7, 8, 9)
        val backend = FakeGuideImageGatewayBackend().apply {
            fetchResult = ApiResult.Success(
                persistentListOf(
                    buildGuideImage(id = 100, bytes = responseBytes),
                ),
            )
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        gateway.fetchAll(listOf(key)).getOrThrow()
        val secondImages = gateway.fetchAll(listOf(key)).getOrThrow()

        assertEquals(1, backend.fetchCallCount)
        assertEquals(1, secondImages.size)
        assertTrue(secondImages.first().bytes.contentEquals(responseBytes))
    }

    @Test
    fun `fetchAll はキャッシュ未命中の minor だけ API に送る`() = runTest {
        val cachedKey = GuideImageKey(major = 5, minor = 100)
        val newKey = GuideImageKey(major = 201, minor = 200)
        val cachedBytes = byteArrayOf(1, 2, 3)
        val newBytes = byteArrayOf(4, 5, 6)
        val backend = FakeGuideImageGatewayBackend()
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        backend.fetchResult = ApiResult.Success(
            persistentListOf(buildGuideImage(id = 100, bytes = cachedBytes)),
        )
        gateway.fetchAll(listOf(cachedKey)).getOrThrow()

        backend.fetchResult = ApiResult.Success(
            persistentListOf(buildGuideImage(id = 200, bytes = newBytes)),
        )
        val secondImages = gateway.fetchAll(listOf(cachedKey, newKey)).getOrThrow()

        assertEquals(2, backend.fetchCallCount)
        assertEquals(listOf(200), requireNotNull(backend.lastRequest).ids.toList())
        assertEquals(2, secondImages.size)
        val imageByKey = secondImages.associateBy { image -> image.key }
        assertTrue(imageByKey[cachedKey]!!.bytes.contentEquals(cachedBytes))
        assertTrue(imageByKey[newKey]!!.bytes.contentEquals(newBytes))
    }

    @Test
    fun `fetchAll は major の異なる同一 minor でキャッシュを再利用する`() = runTest {
        val firstKey = GuideImageKey(major = 5, minor = 100)
        val secondKey = GuideImageKey(major = 201, minor = 100)
        val responseBytes = byteArrayOf(10, 11, 12)
        val backend = FakeGuideImageGatewayBackend().apply {
            fetchResult = ApiResult.Success(
                persistentListOf(buildGuideImage(id = 100, bytes = responseBytes)),
            )
        }
        val gateway = ExtNavGuideImageGateway(
            backend = backend,
            routeRegistry = ExtNavRouteRegistry(),
        )

        gateway.fetchAll(listOf(firstKey)).getOrThrow()
        val secondImages = gateway.fetchAll(listOf(secondKey)).getOrThrow()

        assertEquals(1, backend.fetchCallCount)
        assertEquals(1, secondImages.size)
        assertEquals(secondKey, secondImages.first().key)
        assertTrue(secondImages.first().bytes.contentEquals(responseBytes))
    }
}

private fun buildGuideImage(
    id: Int,
    bytes: ByteArray = byteArrayOf(1, 2, 3),
): GuideImage = GuideImage(
    id = id,
    bytes = bytes,
    format = GuideImageFormat.Webp,
    isMissing = false,
)

/**
 * [ExtNavGuideImageGatewayBackend] の fake。
 */
private class FakeGuideImageGatewayBackend : ExtNavGuideImageGatewayBackend {
    var ensureSignedInCallCount: Int = 0
        private set

    var fetchCallCount: Int = 0
        private set

    var signInResult: Result<Unit> = Result.success(Unit)

    var fetchResult: ApiResult<ImmutableList<GuideImage>> = ApiResult.Success(persistentListOf())

    var lastRequest: GuideImageRequest? = null
        private set

    override suspend fun ensureSignedIn(): Result<Unit> {
        ensureSignedInCallCount += 1
        return signInResult
    }

    override suspend fun fetch(request: GuideImageRequest): ApiResult<ImmutableList<GuideImage>> {
        fetchCallCount += 1
        lastRequest = request
        return fetchResult
    }
}
