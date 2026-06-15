package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * tracker の進捗 snapshot ([ExtNavProgressSnapshot]) を、発話レイヤが必要とする最小入力 ([VoiceTick]) へ変換する。
 *
 * 発話レイヤを route 追従の実装詳細から切り離すための橋渡し。現累積距離・車速・発話可否だけを取り出す。
 */
internal class VoiceTickFactory {

    /**
     * 進捗 snapshot を発話 tick に変換する。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     * @return 発話判定用の tick
     */
    fun from(snapshot: ExtNavProgressSnapshot): VoiceTick = VoiceTick(
        currentCumulativeMeters = snapshot.currentCumulativeMeters,
        speedMetersPerSecond = snapshot.vehicleSpeedMps?.toDouble(),
        isRouteUsable = snapshot.routeMatchState.isUsableForVoiceAnnouncement() &&
            snapshot.positionSource == VehiclePositionSource.OBSERVED,
    )
}

/**
 * route 一致状態が発話可能かを返す。
 *
 * `ON_ROUTE` のみ発話可能とする。off-route 候補・確定中は route 上への投影が信頼できないため発話せず、
 * 発話状態を維持する (誤った地点での発話を避ける)。level トリガなので、復帰後の tick で未処理段は再評価される。
 */
internal fun RouteMatchState.isUsableForVoiceAnnouncement(): Boolean = when (this) {
    RouteMatchState.ON_ROUTE -> true
    RouteMatchState.OFF_ROUTE_CANDIDATE -> false
    RouteMatchState.OFF_ROUTE_CONFIRMED -> false
}
