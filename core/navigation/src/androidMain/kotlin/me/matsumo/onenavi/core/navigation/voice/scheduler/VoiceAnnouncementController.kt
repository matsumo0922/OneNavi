package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDistanceContext
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlanBuilder

/**
 * 案内 manager から音声案内をまとめて駆動する facade。発話プラン構築・tick 変換・発話実行系の配線を 1 つに束ね、
 * [me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager] からは start / onSnapshot / stop だけで扱える。
 *
 * attach 時に payload と距離変換 context から発話プランを組み、tracker snapshot を発話 tick へ変換して実行系へ流す。
 * 距離変換 context は表示と発話で同じものを共有し、表示地点と発話地点がずれないようにする (合意条件 #4)。
 *
 * @property planBuilder payload + 距離変換 context から発話プランを構築する
 * @property tickFactory snapshot を発話 tick へ変換する
 * @property speechRunner 発話プランと tick を受けて発話を再生する実行系
 * @property config category gate / リードタイム等の発話設定
 */
internal class VoiceAnnouncementController(
    private val planBuilder: VoiceAnnouncementPlanBuilder,
    private val tickFactory: VoiceTickFactory,
    private val speechRunner: VoiceAnnouncementSpeechRunner,
    private val config: VoiceAnnouncementConfig,
) {

    /**
     * 音声案内を開始する。発話プランを構築して実行系へ attach する。
     *
     * @param payload 案内対象の payload (guidancePoints / announcementBlocks を含む)
     * @param distanceContext tracker attach 時と同一の source→geometry 距離変換 context
     */
    fun start(payload: ExtNavRoutePayload, distanceContext: ExtNavRouteDistanceContext) {
        val plan = planBuilder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = config,
        )
        speechRunner.attach(plan)
    }

    /**
     * tracker snapshot を 1 件受け取り、発話 tick へ変換して実行系へ流す。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     */
    fun onSnapshot(snapshot: ExtNavProgressSnapshot) {
        speechRunner.submit(tickFactory.from(snapshot))
    }

    /** 音声案内を停止し、発話プランと進行中の発話を破棄する。 */
    fun stop() {
        speechRunner.detach()
    }
}
