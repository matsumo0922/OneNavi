package me.matsumo.onenavi.core.navigation.tts

import java.io.File
import java.security.MessageDigest

/**
 * Google Cloud TTS の WAV バイト列を保存する永続 LRU ファイルキャッシュ。
 *
 * cacheDir 配下の専用 directory に `SHA-256(cacheKey).wav` で保存し、読み出し時に更新時刻を触ることで
 * LRU として扱う。ファイル破損や削除失敗は発話失敗にせず、キャッシュミスとして握りつぶす。
 *
 * @property directory 音声ファイルを保存する directory
 * @property maxBytes キャッシュ全体の最大容量
 */
internal class TtsAudioFileCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /**
     * 指定 key の音声を読み出す。
     *
     * @param cacheKey 音声設定と SSML から作った安定キー
     * @return 保存済み音声。未保存・破損・空ファイルの場合は null
     */
    @Synchronized
    fun read(cacheKey: String): ByteArray? {
        if (directory.isDirectory) cleanupTemporaryFiles()

        val file = fileFor(cacheKey)
        if (!file.isFile) return null
        if (file.length() <= 0L) {
            runCatching { file.delete() }
            return null
        }

        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    /**
     * 指定 key の音声を保存する。
     *
     * @param cacheKey 音声設定と SSML から作った安定キー
     * @param audio WAV バイト列
     */
    @Synchronized
    fun write(
        cacheKey: String,
        audio: ByteArray,
    ) {
        if (audio.isEmpty()) return
        if (!directory.exists()) directory.mkdirs()
        if (!directory.isDirectory) return

        val targetFile = fileFor(cacheKey)
        var temporaryFile: File? = null

        runCatching {
            temporaryFile = File.createTempFile(targetFile.nameWithoutExtension, TEMPORARY_SUFFIX, directory)
            val resolvedTemporaryFile = requireNotNull(temporaryFile)
            resolvedTemporaryFile.writeBytes(audio)
            if (targetFile.exists()) targetFile.delete()
            check(resolvedTemporaryFile.renameTo(targetFile))
            targetFile.setLastModified(System.currentTimeMillis())
            cleanupTemporaryFilesAndTrimToSize()
        }.onFailure {
            temporaryFile?.let { file -> runCatching { file.delete() } }
        }
    }

    /**
     * 指定 key に対応するファイルを返す。
     *
     * @param cacheKey 音声設定と SSML から作った安定キー
     * @return cacheKey に対応する WAV ファイル
     */
    fun fileFor(cacheKey: String): File = directory.resolve("${sha256(cacheKey)}$AUDIO_EXTENSION")

    /** 一時ファイルを掃除し、キャッシュ容量が上限を超えていれば古いファイルから削除する。 */
    private fun cleanupTemporaryFilesAndTrimToSize() {
        val allFiles = directory
            .listFiles()
            .orEmpty()

        cleanupTemporaryFiles(allFiles)

        val audioFiles = allFiles.filter { file -> file.isFile && file.name.endsWith(AUDIO_EXTENSION) }
        var totalBytes = audioFiles.sumOf { file -> file.length() }
        if (totalBytes <= maxBytes) return

        val sortedFiles = audioFiles.sortedWith(
            compareBy<File> { file -> file.lastModified() }
                .thenBy { file -> file.name },
        )

        for (file in sortedFiles) {
            if (totalBytes <= maxBytes) return

            val fileBytes = file.length()
            if (file.delete()) {
                totalBytes -= fileBytes
            }
        }
    }

    /** 書き込み中断で残った一時ファイルを削除する。 */
    private fun cleanupTemporaryFiles() {
        val allFiles = directory
            .listFiles()
            .orEmpty()

        cleanupTemporaryFiles(allFiles)
    }

    /** 指定ファイル一覧に含まれる一時ファイルを削除する。 */
    private fun cleanupTemporaryFiles(allFiles: Array<out File>) {
        allFiles
            .filter { file -> file.isFile && file.name.endsWith(TEMPORARY_SUFFIX) }
            .forEach { file -> runCatching { file.delete() } }
    }

    /** 指定文字列の SHA-256 hex を返す。 */
    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(value.toByteArray(Charsets.UTF_8))

        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** ファイルキャッシュで使う定数定義。 */
    private companion object {

        /** 既定の最大キャッシュ容量。 */
        const val DEFAULT_MAX_BYTES = 100L * 1024L * 1024L

        /** キャッシュファイル拡張子。 */
        const val AUDIO_EXTENSION = ".wav"

        /** 一時ファイルの suffix。 */
        const val TEMPORARY_SUFFIX = ".tmp"

        /** キャッシュファイル名に使う digest algorithm。 */
        const val SHA_256 = "SHA-256"
    }
}
