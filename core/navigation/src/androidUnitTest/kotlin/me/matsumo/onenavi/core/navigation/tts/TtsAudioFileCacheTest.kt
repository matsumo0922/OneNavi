package me.matsumo.onenavi.core.navigation.tts

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * [TtsAudioFileCache] の保存・読み出し・LRU 削除のテスト。
 */
class TtsAudioFileCacheTest {

    @Test
    fun `同じ key は同じファイルから読める`() = withCache { cache, _ ->
        val audio = byteArrayOf(1, 2, 3)

        cache.write("key", audio)

        assertContentEquals(audio, cache.read("key"))
    }

    @Test
    fun `音声設定や SSML が違うと cache key が変わる`() {
        val config = GoogleCloudTtsSynthesisConfig()
        val ssml = "<speak>右です</speak>"

        val baseKey = config.cacheKeyOf(ssml)
        val textKey = config.cacheKeyOf("<speak>左です</speak>")
        val voiceKey = config.copy(voiceName = "ja-JP-Test").cacheKeyOf(ssml)
        val sampleRateKey = config.copy(sampleRateHertz = 16_000).cacheKeyOf(ssml)

        assertNotEquals(baseKey, textKey)
        assertNotEquals(baseKey, voiceKey)
        assertNotEquals(baseKey, sampleRateKey)
    }

    @Test
    fun `cache は最大容量を超えると古いファイルを削除する`() = withCache(maxBytes = 8L) { cache, _ ->
        val oldAudio = byteArrayOf(1, 1, 1, 1)
        val keptAudio = byteArrayOf(2, 2, 2, 2)
        val newAudio = byteArrayOf(3, 3, 3, 3)

        cache.write("old", oldAudio)
        cache.write("kept", keptAudio)
        cache.fileFor("old").setLastModified(1L)
        cache.write("new", newAudio)

        assertNull(cache.read("old"))
        assertContentEquals(keptAudio, cache.read("kept"))
        assertContentEquals(newAudio, cache.read("new"))
    }

    @Test
    fun `空ファイルや一時ファイルがあっても落ちない`() = withCache(maxBytes = 4L) { cache, directory ->
        cache.fileFor("empty").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
        }
        File(directory, "leftover.tmp").writeBytes(byteArrayOf(9, 9, 9, 9))

        cache.write("valid", byteArrayOf(1, 2, 3, 4))

        assertNull(cache.read("empty"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), cache.read("valid"))
    }

    private fun withCache(
        maxBytes: Long = 1024L,
        block: (TtsAudioFileCache, File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("tts-audio-cache-test").toFile()

        try {
            block(TtsAudioFileCache(directory, maxBytes = maxBytes), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
