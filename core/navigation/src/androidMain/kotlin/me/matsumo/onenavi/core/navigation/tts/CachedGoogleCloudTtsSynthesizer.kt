package me.matsumo.onenavi.core.navigation.tts

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Google Cloud TTS の合成結果をファイルキャッシュし、近傍発話の先読みを行う合成器。
 *
 * 実発話ではキャッシュ、進行中リクエスト、新規リクエストの順に音声を取得する。先読みは同じ in-flight
 * map を共有するため、実発話と先読みで同一 SSML を二重に合成しない。
 *
 * @property backend Google Cloud TTS の合成 backend
 * @property cache WAV バイト列のファイルキャッシュ
 * @property synthesisConfigProvider キャッシュキーと合成リクエストに使う音声設定を返す provider
 * @property apiKey 空なら Cloud TTS への新規 request を出さない
 * @property scope 先読み worker と in-flight 合成を動かす scope
 */
internal class CachedGoogleCloudTtsSynthesizer(
    private val backend: GoogleCloudTtsSynthesizerBackend,
    private val cache: TtsAudioFileCache,
    private val synthesisConfigProvider: () -> GoogleCloudTtsSynthesisConfig = { GoogleCloudTtsSynthesisConfig() },
    private val apiKey: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val lock = Any()
    private val prefetchChannel = Channel<PrefetchRequest>(Channel.UNLIMITED)
    private val inFlight = mutableMapOf<String, InFlightSynthesis>()
    private val queuedPrefetchGenerations = mutableMapOf<String, Int>()

    @Volatile
    private var sessionDisabled = false

    private var prefetchGeneration = FIRST_PREFETCH_GENERATION

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (request in prefetchChannel) {
                handlePrefetchRequest(request)
            }
        }
    }

    /**
     * SSML があれば音声合成し、合成できない場合は null を返す。
     *
     * @param ssml 合成対象 SSML
     * @return WAV バイト列。合成不可の場合は null
     */
    suspend fun synthesize(ssml: String?): ByteArray? {
        val request = synthesisRequestOf(ssml) ?: return null
        cache.read(request.cacheKey)?.let { audio -> return audio }
        if (!canRequestCloudTts()) return null

        val firstEntry = inFlightOrStart(request, SynthesisSource.SPEECH)

        return awaitSpeech(request, firstEntry)
    }

    /**
     * 近い将来に発話しそうな SSML を先読みキューへ積む。
     *
     * @param ssml 合成対象 SSML
     */
    fun prefetch(ssml: String?) {
        val request = synthesisRequestOf(ssml) ?: return
        if (!canRequestCloudTts(shouldLogMissingApiKey = false)) return
        val queuedRequest = markPrefetchQueued(request) ?: return

        if (!prefetchChannel.trySend(queuedRequest).isSuccess) {
            unmarkPrefetchQueued(queuedRequest)
        }
    }

    /** 現在の route に紐づく未処理の先読みを破棄する。 */
    fun clearPrefetch() {
        val cancelledEntries = synchronized(lock) {
            prefetchGeneration += PREFETCH_GENERATION_INCREMENT
            queuedPrefetchGenerations.clear()

            inFlight
                .filterValues { entry -> entry.source == SynthesisSource.PREFETCH }
                .toList()
        }

        for ((cacheKey, entry) in cancelledEntries) {
            entry.deferred.cancel()
            removeInFlight(cacheKey, entry)
        }
    }

    /** 先読み request を処理する。古い世代の request は破棄する。 */
    private suspend fun handlePrefetchRequest(request: PrefetchRequest) {
        try {
            if (!isPrefetchCurrent(request)) return
            if (cache.read(request.cacheKey) != null) return

            val entry = inFlightOrStart(request.toSynthesisRequest(), SynthesisSource.PREFETCH)
            val result = entry.deferred.await()
            handlePrefetchResult(result)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            Napier.d(tag = TAG, throwable = cancellation) { "voice prefetch cancelled" }
        } catch (error: Throwable) {
            error.throwIfCancellation()
            Napier.w(tag = TAG, throwable = error) { "voice prefetch failed (transient)" }
        } finally {
            unmarkPrefetchQueued(request)
        }
    }

    /** 発話用 await。先読み in-flight の一時失敗だけは実発話として 1 度取り直す。 */
    private suspend fun awaitSpeech(
        request: SynthesisRequest,
        entry: InFlightSynthesis,
    ): ByteArray? {
        val result = try {
            entry.deferred.await()
        } catch (cancellation: CancellationException) {
            return handleSpeechCancellation(request, entry, cancellation)
        }
        result.getOrNull()?.let { audio -> return audio }

        val error = result.exceptionOrNull() ?: return null
        error.throwIfCancellation()

        if (error is GoogleCloudTtsException) {
            return handleSpeechGoogleCloudError(request, entry, error)
        }

        return handleSpeechTransientError(request, entry, error)
    }

    /** 発話側 await 中に先読み in-flight が破棄された場合だけ、実発話として取り直す。 */
    private suspend fun handleSpeechCancellation(
        request: SynthesisRequest,
        entry: InFlightSynthesis,
        cancellation: CancellationException,
    ): ByteArray? {
        currentCoroutineContext().ensureActive()

        if (entry.source == SynthesisSource.PREFETCH) {
            removeInFlight(request.cacheKey, entry)
            Napier.d(tag = TAG, throwable = cancellation) { "voice prefetch cancelled; retrying for speech" }
            return awaitSpeech(request, inFlightOrStart(request, SynthesisSource.SPEECH))
        }

        throw cancellation
    }

    /** Google Cloud TTS 例外を発話側で処理する。 */
    private suspend fun handleSpeechGoogleCloudError(
        request: SynthesisRequest,
        entry: InFlightSynthesis,
        error: GoogleCloudTtsException,
    ): ByteArray? {
        if (isPermanent(error)) {
            disableSession(error)
            return null
        }

        if (entry.source == SynthesisSource.PREFETCH) {
            removeInFlight(request.cacheKey, entry)
            Napier.w(tag = TAG, throwable = error) { "voice prefetch failed; retrying for speech" }
            return awaitSpeech(request, inFlightOrStart(request, SynthesisSource.SPEECH))
        }

        Napier.w(tag = TAG, throwable = error) {
            "voice synthesize failed (transient): HTTP ${error.statusCode}"
        }
        return null
    }

    /** 発話側の一時例外を処理する。 */
    private suspend fun handleSpeechTransientError(
        request: SynthesisRequest,
        entry: InFlightSynthesis,
        error: Throwable,
    ): ByteArray? {
        if (entry.source == SynthesisSource.PREFETCH) {
            removeInFlight(request.cacheKey, entry)
            Napier.w(tag = TAG, throwable = error) { "voice prefetch failed; retrying for speech" }
            return awaitSpeech(request, inFlightOrStart(request, SynthesisSource.SPEECH))
        }

        Napier.w(tag = TAG, throwable = error) { "voice synthesize failed (transient)" }
        return null
    }

    /** 先読み側の合成結果を処理する。 */
    private fun handlePrefetchResult(result: Result<ByteArray>) {
        val error = result.exceptionOrNull() ?: return
        error.throwIfCancellation()

        if (error is GoogleCloudTtsException) {
            handlePrefetchGoogleCloudError(error)
            return
        }

        Napier.w(tag = TAG, throwable = error) { "voice prefetch failed (transient)" }
    }

    /** 先読み側の Google Cloud TTS 例外を処理する。 */
    private fun handlePrefetchGoogleCloudError(error: GoogleCloudTtsException) {
        if (isPermanent(error)) {
            disableSession(error)
            return
        }

        Napier.w(tag = TAG, throwable = error) {
            "voice prefetch failed (transient): HTTP ${error.statusCode}"
        }
    }

    /** in-flight があれば共有し、無ければ新しい合成 job を開始する。 */
    private fun inFlightOrStart(
        request: SynthesisRequest,
        source: SynthesisSource,
    ): InFlightSynthesis = synchronized(lock) {
        inFlight[request.cacheKey]?.let { existing -> return@synchronized existing }

        val deferred = scope.async(start = CoroutineStart.LAZY) {
            requestAndCache(request)
        }
        val entry = InFlightSynthesis(
            deferred = deferred,
            source = source,
        )
        inFlight[request.cacheKey] = entry
        deferred.invokeOnCompletion {
            removeInFlight(request.cacheKey, entry)
        }
        deferred.start()

        entry
    }

    /** Google Cloud TTS を叩き、成功した音声をキャッシュして返す。 */
    private suspend fun requestAndCache(request: SynthesisRequest): Result<ByteArray> {
        Napier.d(tag = TAG) { "voice synthesize: ${request.ssml}" }

        return runCatching {
            val audio = backend.synthesize(request.ssml, request.synthesisConfig)
            cache.write(
                cacheKey = request.cacheKey,
                audio = audio,
            )

            audio
        }.onFailure { error ->
            error.throwIfCancellation()
        }
    }

    /** in-flight から対象 entry を外す。 */
    private fun removeInFlight(
        cacheKey: String,
        entry: InFlightSynthesis,
    ) {
        synchronized(lock) {
            if (inFlight[cacheKey] === entry) {
                inFlight.remove(cacheKey)
            }
        }
    }

    /** 先読み request を同一 route 世代で重複しないよう記録する。 */
    private fun markPrefetchQueued(request: SynthesisRequest): PrefetchRequest? = synchronized(lock) {
        if (request.cacheKey in queuedPrefetchGenerations) return@synchronized null
        if (request.cacheKey in inFlight) return@synchronized null

        queuedPrefetchGenerations[request.cacheKey] = prefetchGeneration
        PrefetchRequest(
            cacheKey = request.cacheKey,
            ssml = request.ssml,
            synthesisConfig = request.synthesisConfig,
            generation = prefetchGeneration,
        )
    }

    /** 先読み request の記録を解除する。 */
    private fun unmarkPrefetchQueued(request: PrefetchRequest) {
        synchronized(lock) {
            if (queuedPrefetchGenerations[request.cacheKey] == request.generation) {
                queuedPrefetchGenerations.remove(request.cacheKey)
            }
        }
    }

    /** 指定 request が現在世代の先読みかを返す。 */
    private fun isPrefetchCurrent(request: PrefetchRequest): Boolean = synchronized(lock) {
        request.generation == prefetchGeneration
    }

    /** SSML から合成 request を作る。合成不能なら null。 */
    private fun synthesisRequestOf(ssml: String?): SynthesisRequest? {
        if (ssml.isNullOrBlank()) return null

        val synthesisConfig = synthesisConfigProvider()
        return SynthesisRequest(
            cacheKey = synthesisConfig.cacheKeyOf(ssml),
            ssml = ssml,
            synthesisConfig = synthesisConfig,
        )
    }

    /** Cloud TTS API へ新規 request を出せるなら true を返す。 */
    private fun canRequestCloudTts(shouldLogMissingApiKey: Boolean = true): Boolean {
        if (sessionDisabled) return false
        if (apiKey.isBlank()) {
            if (shouldLogMissingApiKey) {
                Napier.w(tag = TAG) { "GOOGLE_CLOUD_TTS_API_KEY is blank; voice announcement disabled" }
            }
            return false
        }

        return true
    }

    /** 恒久エラーなら true を返す。 */
    private fun isPermanent(error: GoogleCloudTtsException): Boolean =
        error.statusCode in PERMANENT_STATUS_CODES

    /** セッション中の Cloud TTS 合成を無効化する。 */
    private fun disableSession(error: GoogleCloudTtsException) {
        sessionDisabled = true

        Napier.e(tag = TAG, throwable = error) {
            "voice synthesize permanently disabled: HTTP ${error.statusCode}"
        }
    }

    /** coroutine のキャンセルだけは発話失敗として握りつぶさず呼び出し元へ返す。 */
    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    /**
     * 1 件の合成要求。
     *
     * @property cacheKey 音声設定と SSML から作った cache key
     * @property ssml 合成対象 SSML
     * @property synthesisConfig 合成 request に使う音声設定
     */
    private data class SynthesisRequest(
        val cacheKey: String,
        val ssml: String,
        val synthesisConfig: GoogleCloudTtsSynthesisConfig,
    )

    /**
     * 先読みキューに積む合成要求。
     *
     * @property cacheKey 音声設定と SSML から作った cache key
     * @property ssml 合成対象 SSML
     * @property synthesisConfig 合成 request に使う音声設定
     * @property generation route attach ごとの先読み世代
     */
    private data class PrefetchRequest(
        val cacheKey: String,
        val ssml: String,
        val synthesisConfig: GoogleCloudTtsSynthesisConfig,
        val generation: Int,
    ) {

        /** 通常の合成要求に変換する。 */
        fun toSynthesisRequest(): SynthesisRequest = SynthesisRequest(
            cacheKey = cacheKey,
            ssml = ssml,
            synthesisConfig = synthesisConfig,
        )
    }

    /**
     * 進行中の合成 request。
     *
     * @property deferred 合成結果
     * @property source request を開始した入口
     */
    private class InFlightSynthesis(
        val deferred: Deferred<Result<ByteArray>>,
        val source: SynthesisSource,
    )

    /**
     * 合成 request を開始した入口。
     */
    private enum class SynthesisSource {

        /** 実発話のための合成。 */
        SPEECH,

        /** 先読みのための合成。 */
        PREFETCH,
    }

    /** 合成器内で使う定数定義。 */
    private companion object {

        /** Logcat で発話ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncement(GoogleCloudTts)"

        /** 最初の先読み世代。 */
        const val FIRST_PREFETCH_GENERATION = 0

        /** 先読み世代を進める加算値。 */
        const val PREFETCH_GENERATION_INCREMENT = 1

        /** リトライしても直らない恒久エラーの HTTP ステータス。 */
        val PERMANENT_STATUS_CODES = setOf(400, 401, 403, 404)
    }
}
