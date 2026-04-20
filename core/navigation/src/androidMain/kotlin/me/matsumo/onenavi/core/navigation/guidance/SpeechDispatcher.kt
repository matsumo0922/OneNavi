package me.matsumo.onenavi.core.navigation.guidance

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator

/**
 * [GuidanceEvent] を受け取り、フレーズ組み立て・strings 解決・[SpeechOrchestrator] への投入までを直列に処理するアクター。
 *
 * 設計詳細は `docs/logs/5_tts_voice_guidance_plan.md` §4.7 を参照。
 *
 * - CRITICAL 専用チャンネルと通常チャンネルの 2 系統を持つ
 * - CRITICAL 優先は `tryReceive` 先読み → `select` の二段構えで保証する
 * - [enabled] が false の間はイベントを受信しても発話せず drain する（Phase 1 の配線確認用途）
 * - [start] 冒頭で [shutdown] を呼ぶため、再起動時にチャンネルが正しく再生成される
 */
internal class SpeechDispatcher(
    private val orchestrator: SpeechOrchestrator,
    private val composer: PhraseComposer,
    private val scope: CoroutineScope,
) {

    private var criticalChannel: Channel<GuidanceEvent>? = null
    private var normalChannel: Channel<GuidanceEvent>? = null
    private var job: Job? = null

    @Volatile
    private var enabled: Boolean = false

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun start() {
        shutdown()
        val critical = Channel<GuidanceEvent>(capacity = CRITICAL_CAPACITY)
        val normal = Channel<GuidanceEvent>(capacity = NORMAL_CAPACITY)
        criticalChannel = critical
        normalChannel = normal
        job = scope.launch {
            while (isActive) {
                val event = critical.tryReceive().getOrNull()
                    ?: select<GuidanceEvent?> {
                        critical.onReceiveCatching { result -> result.getOrNull() }
                        normal.onReceiveCatching { result -> result.getOrNull() }
                    }
                    ?: break
                if (!enabled) {
                    Napier.d(tag = TAG) { "Dispatcher disabled, draining: $event" }
                    continue
                }
                runCatching {
                    val phrase = composer.compose(event)
                    val text = composer.resolve(phrase)
                    if (text.isNotBlank()) {
                        val flush = event.priority == GuidancePriority.CRITICAL ||
                            event.priority == GuidancePriority.HIGH
                        Napier.i(tag = TAG) { "[P4] SPOKE event=$event text=\"$text\" flush=$flush" }
                        orchestrator.enqueue(text = text, flush = flush)
                    }
                }.onFailure { throwable ->
                    Napier.w(tag = TAG, throwable = throwable) { "Failed to dispatch guidance event: $event" }
                }
            }
        }
    }

    fun send(event: GuidanceEvent) {
        val targetChannel = when (event.priority) {
            GuidancePriority.CRITICAL -> criticalChannel
            else -> normalChannel
        }
        if (targetChannel == null) {
            Napier.w(tag = TAG) { "Dispatcher not started, dropping: $event" }
            return
        }
        val result = targetChannel.trySend(event)
        if (result.isFailure && event.priority != GuidancePriority.CRITICAL) {
            Napier.w(tag = TAG) { "Normal channel full, coalescing oldest: $event" }
            targetChannel.tryReceive()
            targetChannel.trySend(event)
        } else if (result.isFailure) {
            Napier.e(tag = TAG) { "CRITICAL channel full (unexpected), forcing: $event" }
            targetChannel.tryReceive()
            targetChannel.trySend(event)
        }
    }

    fun shutdown() {
        criticalChannel?.close()
        normalChannel?.close()
        criticalChannel = null
        normalChannel = null
        job?.cancel()
        job = null
    }

    companion object {
        private const val NORMAL_CAPACITY = 32
        private const val CRITICAL_CAPACITY = 8
        private const val TAG = "SpeechDispatcher"
    }
}
