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
 * @property leadTimeSeconds 直前段を到達何秒前に発話開始するか (位置 tick 間隔・チャイム・TTS 本文開始までの余裕込み)
 * @property minLeadMeters 低速時に直前段が遅れないよう保証する最小手前距離 (m)
 * @property lateFinalSkipRatio attach 時点で名目 FINAL トリガを過ぎていた場合に、距離句の破綻を避けるため skip する残距離割合
 * @property lateFinalSkipMinimumTriggerMeters 途中参加 skip の対象にする FINAL 名目手前距離の下限 (m)
 * @property queuedStaleGraceMeters ENQUEUE 済み MIDDLE をキュー消化時に窓終端からどれだけ猶予するか (m)
 * @property ordering 緊急度同値時の tie-break 並び順
 */
@Immutable
internal data class VoiceAnnouncementConfig(
    val categoryGates: VoiceAnnouncementCategoryGate = VoiceAnnouncementCategoryGate.OneNaviDefault,
    val distanceOverrides: VoiceAnnouncementDistanceOverrides = VoiceAnnouncementDistanceOverrides.None,
    val leadTimeSeconds: Double = DEFAULT_LEAD_TIME_SECONDS,
    val minLeadMeters: Double = DEFAULT_MIN_LEAD_METERS,
    val lateFinalSkipRatio: Double = DEFAULT_LATE_FINAL_SKIP_RATIO,
    val lateFinalSkipMinimumTriggerMeters: Double = DEFAULT_LATE_FINAL_SKIP_MINIMUM_TRIGGER_METERS,
    val queuedStaleGraceMeters: Double = DEFAULT_QUEUED_STALE_GRACE_METERS,
    val ordering: VoiceAnnouncementOrdering = VoiceAnnouncementOrdering.RouteOrder,
) {

    internal companion object {

        /** 直前段の到達リードタイム既定値 (秒)。 */
        const val DEFAULT_LEAD_TIME_SECONDS: Double = 5.0

        /** 低速時の保険となる最小手前距離の既定値 (m)。低速側の「まもなく」が早すぎない従来値。 */
        const val DEFAULT_MIN_LEAD_METERS: Double = 30.0

        /** attach 時に名目手前距離の半分より内側へ食い込んだ FINAL を skip する既定割合。 */
        const val DEFAULT_LATE_FINAL_SKIP_RATIO: Double = 0.5

        /** 「まもなく」相当の近接 FINAL を途中参加 skip から外すための名目手前距離下限 (m)。 */
        const val DEFAULT_LATE_FINAL_SKIP_MINIMUM_TRIGGER_METERS: Double = 100.0

        /** MIDDLE の ENQUEUE 後 stale 判定に足す既定猶予距離 (m)。 */
        const val DEFAULT_QUEUED_STALE_GRACE_METERS: Double = 100.0
    }
}
