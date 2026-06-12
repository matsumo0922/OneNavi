package me.matsumo.onenavi.core.navigation.voice.debug

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * ナビゲーション中の発話予定を UI に表示するためのデバッグスナップショット。
 *
 * @property upcomingAnnouncements 現在地から近い順に並べた発話予定
 */
@Immutable
data class VoiceAnnouncementDebugSnapshot(
    val upcomingAnnouncements: ImmutableList<VoiceAnnouncementDebugItem>,
)

/**
 * 1 件の発話予定を UI 表示向けに平坦化した情報。
 *
 * @property stageId 発話段の route session 内識別子
 * @property targetIndex 発話対象の plan 内 index
 * @property text レンダリング済みの読み上げ文
 * @property remainingMeters 発話境界までの残距離
 * @property stageKind 発話段の種別
 * @property fetchState TTS 音声の取得状態
 * @property isRouteOrderBlocked route 順ゲートで現時点は抑止されているか
 * @property categories 発話段に紐づく category 名
 */
@Immutable
data class VoiceAnnouncementDebugItem(
    val stageId: String,
    val targetIndex: Int,
    val text: String,
    val remainingMeters: Double,
    val stageKind: VoiceAnnouncementDebugStageKind,
    val fetchState: VoiceAnnouncementDebugFetchState,
    val isRouteOrderBlocked: Boolean,
    val categories: ImmutableList<String>,
)

/**
 * デバッグ表示用の発話段種別。
 */
enum class VoiceAnnouncementDebugStageKind {

    /** 中間予告段。 */
    MIDDLE,

    /** 直前案内段。 */
    FINAL,
}

/**
 * TTS 音声の取得状態。
 */
enum class VoiceAnnouncementDebugFetchState {

    /** 音声ファイルキャッシュに保存済み。 */
    CACHED,

    /** 合成 request が進行中。 */
    IN_FLIGHT,

    /** キャッシュにも進行中 request にも無い。 */
    NOT_REQUESTED,
}
