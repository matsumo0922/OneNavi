package me.matsumo.onenavi.core.navigation.voice.config

import androidx.compose.runtime.Immutable

/**
 * 音声案内スケジューラの挙動を定数で差し替えるための設定。
 *
 * 発話の ON/OFF・距離・リードタイムを config / 定数の変更だけで調整できるようにし、N 社の
 * ナビアプリの挙動へ寄せたり OneNavi 独自 UX に振ったりする余地を残す。永続化や設定 UI は
 * 別タスク (issue #41 §Q3) のため、初期実装は in-memory な既定値を DI singleton として渡す。
 *
 * @property categoryGates 発話カテゴリごとの ON/OFF ゲート
 * @property distanceOverrides 中間段の発話開始距離の上書き設定
 * @property leadTimeSeconds 直前段を到達何秒前に発話開始するか (TTS リクエスト遅延込み)
 * @property minLeadMeters 低速時に直前段が遅れないよう保証する最小手前距離 (m)
 * @property ordering 緊急度同値時の tie-break 並び順
 */
@Immutable
internal data class VoiceAnnouncementConfig(
    val categoryGates: VoiceAnnouncementCategoryGate = VoiceAnnouncementCategoryGate.OneNaviDefault,
    val distanceOverrides: VoiceAnnouncementDistanceOverrides = VoiceAnnouncementDistanceOverrides.None,
    val leadTimeSeconds: Double = DEFAULT_LEAD_TIME_SECONDS,
    val minLeadMeters: Double = DEFAULT_MIN_LEAD_METERS,
    val ordering: VoiceAnnouncementOrdering = VoiceAnnouncementOrdering.RouteOrder,
) {

    internal companion object {

        /** 直前段の到達リードタイム既定値 (秒)。 */
        const val DEFAULT_LEAD_TIME_SECONDS: Double = 3.0

        /** 低速時の保険となる最小手前距離の既定値 (m)。実走行ログで Phase 4 に調整する。 */
        const val DEFAULT_MIN_LEAD_METERS: Double = 30.0
    }
}
