package me.matsumo.onenavi.core.navigation.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CachedGoogleCloudTtsSynthesizer] の cache / in-flight 共有 / prefetch 再試行のテスト。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedGoogleCloudTtsSynthesizerTest {

    @Test
    fun `cache hit 時は API を呼ばない`() = runTest {
        val backend = FakeBackend()
        val config = GoogleCloudTtsSynthesisConfig()
        val cache = cacheOf()
        val synthesizer = synthesizerOf(
            backend = backend,
            cache = cache,
            config = config,
        )
        cache.write(config.cacheKeyOf(SSML), AUDIO)

        val audio = synthesizer.synthesize(SSML)

        assertContentEquals(AUDIO, audio)
        assertEquals(0, backend.calls.size)
    }

    @Test
    fun `prefetch 済み音声を speak が再利用する`() = runTest {
        val backend = FakeBackend()
        val synthesizer = synthesizerOf(backend)

        synthesizer.prefetch(SSML)
        advanceUntilIdle()
        val audio = synthesizer.synthesize(SSML)

        assertContentEquals(audioOf(SSML), audio)
        assertEquals(listOf(SSML), backend.calls)
    }

    @Test
    fun `同一 key の in-flight request は重複 API 呼び出ししない`() = runTest {
        val backend = FakeBackend()
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        val synthesizer = synthesizerOf(backend)

        val first = async { synthesizer.synthesize(SSML) }
        advanceUntilIdle()
        val second = async { synthesizer.synthesize(SSML) }
        advanceUntilIdle()

        assertEquals(1, backend.calls.size)

        gate.complete(Unit)

        assertContentEquals(audioOf(SSML), first.await())
        assertContentEquals(audioOf(SSML), second.await())
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `prefetch transient failure 後も speak で再試行する`() = runTest {
        val backend = FakeBackend()
        backend.enqueueFailure(GoogleCloudTtsException(statusCode = null, message = "timeout"))
        backend.enqueueAudio(AUDIO)
        val synthesizer = synthesizerOf(backend)

        synthesizer.prefetch(SSML)
        advanceUntilIdle()
        val audio = synthesizer.synthesize(SSML)

        assertContentEquals(AUDIO, audio)
        assertEquals(2, backend.calls.size)
    }

    @Test
    fun `clearPrefetch は進行中 prefetch を破棄し speak で取り直す`() = runTest {
        val backend = FakeBackend()
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        val synthesizer = synthesizerOf(backend)

        synthesizer.prefetch(SSML)
        advanceUntilIdle()

        assertEquals(1, backend.calls.size)

        synthesizer.clearPrefetch()
        gate.complete(Unit)
        backend.gate = null
        val audio = synthesizer.synthesize(SSML)

        assertContentEquals(audioOf(SSML), audio)
        assertEquals(2, backend.calls.size)
    }

    @Test
    fun `permanent error で sessionDisabled になる`() = runTest {
        val backend = FakeBackend()
        backend.enqueueFailure(GoogleCloudTtsException(statusCode = 403, message = "forbidden"))
        val synthesizer = synthesizerOf(backend)

        val first = synthesizer.synthesize(SSML)
        val second = synthesizer.synthesize(SSML)

        assertNull(first)
        assertNull(second)
        assertEquals(1, backend.calls.size)
    }

    private fun TestScope.synthesizerOf(
        backend: FakeBackend,
        cache: TtsAudioFileCache = cacheOf(),
        config: GoogleCloudTtsSynthesisConfig = GoogleCloudTtsSynthesisConfig(),
    ): CachedGoogleCloudTtsSynthesizer = CachedGoogleCloudTtsSynthesizer(
        backend = backend,
        cache = cache,
        synthesisConfig = config,
        apiKey = "api-key",
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
    )

    private fun cacheOf(): TtsAudioFileCache {
        val directory = Files.createTempDirectory("cached-google-cloud-tts-test").toFile()

        return TtsAudioFileCache(directory)
    }

    /**
     * テスト用の Google Cloud TTS backend。
     */
    private class FakeBackend : GoogleCloudTtsSynthesizerBackend {

        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        private val results = ArrayDeque<Result<ByteArray>>()

        override suspend fun synthesize(ssml: String): ByteArray {
            calls += ssml
            gate?.await()

            if (results.isEmpty()) return audioOf(ssml)

            return results.removeFirst().getOrThrow()
        }

        fun enqueueAudio(audio: ByteArray) {
            results.addLast(Result.success(audio))
        }

        fun enqueueFailure(error: Throwable) {
            results.addLast(Result.failure(error))
        }
    }

    /** テストデータ定義。 */
    private companion object {

        /** テスト用 SSML。 */
        const val SSML = "<speak>右です</speak>"

        /** テスト用 WAV バイト列。 */
        val AUDIO = byteArrayOf(1, 2, 3)

        /** SSML ごとに変わるテスト用 WAV バイト列を返す。 */
        fun audioOf(ssml: String): ByteArray = ssml.encodeToByteArray()
    }
}
