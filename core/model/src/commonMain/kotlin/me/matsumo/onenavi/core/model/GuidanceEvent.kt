package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 音声案内の意味論レベルのイベント。
 *
 * フレーズ文字列は一切含まず、イベント種別とパラメータのみを保持する。
 * 実際の文言組み立ては `PhraseComposer` の責務で、strings.xml を通して発話文字列に変換する。
 */
@Immutable
sealed interface GuidanceEvent {
    /** このイベントの優先度。`SpeechDispatcher` の振り分けと flush 判定に使用する。 */
    val priority: GuidancePriority

    /** セッション開始直後のアナウンス。 */
    @Immutable
    data class SessionStarted(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /**
     * 出発時（DEPART ステップ）の方角案内。
     *
     * SDK の `fullInstructionText` から [CompassDirection.parse] で方角を抽出し、
     * 「〇〇方向に進みます。」の固定フレーズで発話する。セッション中 1 回のみ発火する運用。
     *
     * @property direction 抽出した方角。
     */
    @Immutable
    data class Depart(
        val direction: CompassDirection,
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** セッション終了時の締めくくりアナウンス。 */
    @Immutable
    data class SessionFinished(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** 経由地付近到着時のアナウンス。 */
    @Immutable
    data class ViaWaypointApproach(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** 最終目的地付近到着時のアナウンス。 */
    @Immutable
    data class DestinationApproach(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** ルートから外れたことの通知。 */
    @Immutable
    data class OffRoute(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** ルートに復帰したことの通知。 */
    @Immutable
    data class OnRouteRecovered(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /** 新しいルートが確定したことの通知。 */
    @Immutable
    data class Rerouted(
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /**
     * ターンバイターン／分岐／合流などのマニューバ予告。
     *
     * @property stepCounter セッション中のステップ識別子（`StepTransitionTracker` が払い出す）。
     * @property bucket 下抜けした距離バケット。
     * @property maneuverType マニューバ種別。
     * @property modifier 方向修飾子。方向を持たないマニューバでは null。
     * @property drivingSide 合流時の流入側判定などに使用する走行側。
     * @property isStandaloneAt50m `AT_50M` 単独発話モードか（`AT_100M` 未発話のまま 50m 以下に入った場合）。
     *   true のときは `PhraseComposer` が「この先すぐ、」文頭に切り替える。
     * @property followup 連続案内（次ステップが近接する場合の追加予告）。
     *   `AT_100M` バケット発話時にのみセットされる。null のときは単独フレーズで発話する。
     */
    @Immutable
    data class Maneuver(
        val stepCounter: Int,
        val bucket: DistanceBucket,
        val maneuverType: ManeuverType,
        val modifier: ManeuverModifier?,
        val drivingSide: DrivingSide?,
        val isStandaloneAt50m: Boolean,
        val followup: FollowupManeuver?,
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /**
     * レーン案内。
     *
     * @property stepCounter 対象ステップの識別子。
     * @property bucket 発話タイミングの距離バケット（v1 では `AT_500M` のみ）。
     * @property lanePosition 推奨車線の位置。
     */
    @Immutable
    data class Lane(
        val stepCounter: Int,
        val bucket: DistanceBucket,
        val lanePosition: LanePosition,
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    /**
     * 道なり案内。
     *
     * @property stepCounter 対象ステップの識別子。
     * @property level 距離に応じた段階。
     */
    @Immutable
    data class Straightforward(
        val stepCounter: Int,
        val level: StraightforwardLevel,
        override val priority: GuidancePriority,
    ) : GuidanceEvent
}
