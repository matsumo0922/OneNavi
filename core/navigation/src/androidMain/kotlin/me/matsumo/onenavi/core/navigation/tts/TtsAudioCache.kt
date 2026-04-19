package me.matsumo.onenavi.core.navigation.tts

import android.util.LruCache

/**
 * 同一文言の合成結果を再利用するための LRU キャッシュ。
 *
 * Google Cloud TTS は LINEAR16 24kHz mono 16bit で 48KB/s、base64 でさらに約 1.33 倍に膨らむため
 * 同一文言の繰り返しを抑えるだけで通信量・レイテンシ共に大きく下がる。
 * 容量の単位はバイト数で、キャッシュ対象は WAV ヘッダ付きの生バイト列そのもの。
 */
internal class TtsAudioCache(
    maxSizeBytes: Int = DEFAULT_MAX_SIZE_BYTES,
) {

    private val cache = object : LruCache<String, ByteArray>(maxSizeBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    fun get(key: String): ByteArray? = cache.get(key)

    fun put(key: String, audio: ByteArray) {
        cache.put(key, audio)
    }

    fun clear() {
        cache.evictAll()
    }

    private companion object {
        private const val DEFAULT_MAX_SIZE_BYTES = 4 * 1024 * 1024
    }
}
