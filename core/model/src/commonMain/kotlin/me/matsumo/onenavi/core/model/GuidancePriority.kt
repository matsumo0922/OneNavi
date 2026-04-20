package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 音声案内イベントの優先度。
 *
 * `SpeechDispatcher` でのチャンネル振り分けと、`SpeechOrchestrator.enqueue()` への
 * flush 指定の判断に用いる。CRITICAL / HIGH は flush=true（既存発話を中断）、
 * NORMAL / LOW は flush=false（追記）で再生される。
 */
@Immutable
enum class GuidancePriority {
    /** セッション制御、オフルート、リルート、到着など即時割り込みが必要なイベント。 */
    CRITICAL,

    /** 50m / 100m の直前案内。 */
    HIGH,

    /** 500m / 2km の予告案内、レーン案内。 */
    NORMAL,

    /** 道なり案内などの補助的情報。 */
    LOW,
}
