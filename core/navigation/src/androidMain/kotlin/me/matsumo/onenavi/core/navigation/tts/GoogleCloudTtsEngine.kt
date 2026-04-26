package me.matsumo.onenavi.core.navigation.tts

import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Google Cloud TTS を叩いて音声合成する TtsEngine 実装。
 *
 * - 発話ごとに `SynthesizeRequest` → PCM バイト列 → `PcmAudioPlayer.playAndAwait` を直列に実行する
 * - `FLUSH` / `stop()` は現在走っている合成 + 再生 job を同期で cancel して即時反映する
 * - 401/403/400/404 は恒久エラーとしてセッション中は以後一切試さない
 * - 一時エラーが 3 回連続したら 60 秒クールダウン、期間中 `isReady = false` を返す
 */
internal class GoogleCloudTtsEngine(
    private val api: GoogleCloudTtsApi,
    private val audioPlayer: PcmAudioPlayer,
    private val audioFocusManager: AudioFocusManager,
    private val audioCache: TtsAudioCache,
    private val apiKey: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TtsEngine {

    private val requestChannel = Channel<Utterance>(capacity = Channel.UNLIMITED)

    @Volatile
    private var activeJob: Job? = null

    private val readyState = MutableStateFlow(apiKey.isNotBlank())
    private val sessionDisabled = MutableStateFlow(false)
    private val cooldownUntilMillis = MutableStateFlow(0L)

    private var consecutiveFailures: Int = 0
    private var isShutdown: Boolean = false

    override val isReady: StateFlow<Boolean> = readyState.asStateFlow()
    override var onUtteranceCompleted: ((String) -> Unit)? = null

    /**
     * 非同期合成・再生失敗時に、同じ発話内容を別経路 (Android TTS など) で話し直すためのフック。
     *
     * `speak()` はキュー投入時点で true を返すため、後続の worker で発生した HTTP / 再生失敗を
     * 上流の `FallbackTtsEngine` で拾えない。このコールバックがセットされている場合は
     * 失敗発話を完了扱いにせず、呼び出し側に再分配を委ねる。
     */
    var onSynthesisFailed: ((text: String, utteranceId: String) -> Unit)? = null

    init {
        if (apiKey.isBlank()) {
            Napier.i(tag = TAG) { "GoogleCloudTtsEngine disabled: apiKey is blank" }
        } else {
            scope.launch { worker() }
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean = enqueue(TtsInput.Plain(text), utteranceId, queueMode)

    override fun speak(
        input: TtsInput,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean = enqueue(input, utteranceId, queueMode)

    private fun enqueue(
        input: TtsInput,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean {
        if (!refreshReady()) return false
        if (input.isBlank()) return false
        if (queueMode == SpeechQueueMode.FLUSH) {
            flushInternal()
        }
        val sendResult = requestChannel.trySend(Utterance(input = input, id = utteranceId))
        return sendResult.isSuccess
    }

    private fun TtsInput.isBlank(): Boolean = when (this) {
        is TtsInput.Plain -> text.isBlank()
        is TtsInput.Ssml -> ssml.isBlank()
    }

    override fun stop() {
        flushInternal()
    }

    override fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        flushInternal()
        requestChannel.close()
        scope.cancel()
        readyState.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun worker() {
        for (utterance in requestChannel) {
            val job = scope.launch {
                audioFocusManager.request()
                try {
                    try {
                        val cacheKey = utterance.cacheKey()
                        val cached = audioCache.get(cacheKey)
                        val audio = cached ?: api.synthesize(utterance.input).also { result ->
                            audioCache.put(cacheKey, result)
                        }
                        audioPlayer.playAndAwait(audio)
                        resetFailureCount()
                        onUtteranceCompleted?.invoke(utterance.id)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        handleFailure(error)
                        val handler = onSynthesisFailed
                        if (handler != null) {
                            handler(utterance.fallbackText(), utterance.id)
                        } else {
                            onUtteranceCompleted?.invoke(utterance.id)
                        }
                    }
                } finally {
                    if (requestChannel.isEmpty) {
                        audioFocusManager.abandon()
                    }
                }
            }
            activeJob = job
            job.join()
            if (activeJob == job) activeJob = null
        }
    }

    private fun flushInternal() {
        val jobToCancel = activeJob
        activeJob = null
        jobToCancel?.cancel()
        while (true) {
            val result = requestChannel.tryReceive()
            if (!result.isSuccess) break
        }
        audioFocusManager.abandon()
    }

    private fun refreshReady(): Boolean {
        val ready = apiKey.isNotBlank() &&
            !sessionDisabled.value &&
            nowMillis() >= cooldownUntilMillis.value &&
            !isShutdown
        if (readyState.value != ready) readyState.value = ready
        return ready
    }

    private fun resetFailureCount() {
        consecutiveFailures = 0
        cooldownUntilMillis.value = 0L
        if (!sessionDisabled.value) readyState.value = apiKey.isNotBlank() && !isShutdown
    }

    private fun handleFailure(error: Throwable) {
        val permanent = isPermanentError(error)
        if (permanent) {
            sessionDisabled.value = true
            readyState.value = false
            Napier.e(tag = TAG, throwable = error) {
                "Google Cloud TTS permanently disabled for this session"
            }
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            cooldownUntilMillis.update { nowMillis() + COOLDOWN_MILLIS }
            readyState.value = false
            Napier.w(tag = TAG, throwable = error) {
                "Google Cloud TTS cooling down for ${COOLDOWN_MILLIS / 1000}s after $consecutiveFailures failures"
            }
        } else {
            Napier.w(tag = TAG, throwable = error) {
                "Google Cloud TTS transient failure ($consecutiveFailures/$FAILURE_THRESHOLD)"
            }
        }
    }

    private fun isPermanentError(error: Throwable): Boolean {
        val status = (error as? GoogleCloudTtsException)?.status ?: return false
        return status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.Forbidden ||
            status == HttpStatusCode.BadRequest ||
            status == HttpStatusCode.NotFound
    }

    /**
     * 発話キューに積むための 1 件の指示。
     */
    private data class Utterance(
        val input: TtsInput,
        val id: String,
    ) {
        fun cacheKey(): String = when (input) {
            is TtsInput.Plain -> "plain:${input.text}"
            is TtsInput.Ssml -> "ssml:${input.ssml}"
        }

        fun fallbackText(): String = when (input) {
            is TtsInput.Plain -> input.text
            is TtsInput.Ssml -> PhonemeConverter.toPlainText(input.ssml)
        }
    }

    private companion object {
        private const val TAG = "GoogleCloudTtsEngine"
        private const val FAILURE_THRESHOLD = 3
        private const val COOLDOWN_MILLIS = 60_000L
    }
}
