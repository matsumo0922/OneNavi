package me.matsumo.onenavi.core.navigation.tts

import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    ): Boolean {
        if (!refreshReady()) return false
        if (text.isBlank()) return false
        if (queueMode == SpeechQueueMode.FLUSH) {
            flushInternal()
        }
        val sendResult = requestChannel.trySend(Utterance(text = text, id = utteranceId))
        return sendResult.isSuccess
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

    private suspend fun worker() {
        for (utterance in requestChannel) {
            val job = scope.launch {
                audioFocusManager.request()
                try {
                    runCatching {
                        val cached = audioCache.get(utterance.text)
                        val audio = cached ?: api.synthesize(utterance.text).also { result ->
                            audioCache.put(utterance.text, result)
                        }
                        audioPlayer.playAndAwait(audio)
                    }.onSuccess {
                        resetFailureCount()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        handleFailure(error)
                    }
                } finally {
                    if (requestChannel.isEmpty) {
                        audioFocusManager.abandon()
                    }
                    onUtteranceCompleted?.invoke(utterance.id)
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
        val text: String,
        val id: String,
    )

    private companion object {
        private const val TAG = "GoogleCloudTtsEngine"
        private const val FAILURE_THRESHOLD = 3
        private const val COOLDOWN_MILLIS = 60_000L
    }
}
