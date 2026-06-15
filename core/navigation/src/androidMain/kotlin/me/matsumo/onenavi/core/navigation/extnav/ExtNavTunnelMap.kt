package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteDetail

/**
 * route geometry 上のトンネル区間を表す状態。
 */
@Immutable
sealed interface TunnelMapStatus {

    /**
     * トンネル区間の準備が完了した状態。
     *
     * @param segments route geometry 累積距離ベースのトンネル区間
     */
    @Immutable
    data class Ready(
        val segments: ImmutableList<ExtNavTunnelSegment>,
    ) : TunnelMapStatus

    /** トンネル区間の準備に失敗し、DR を無効化する状態。 */
    data object Unavailable : TunnelMapStatus
}

/**
 * route geometry 累積距離ベースのトンネル区間。
 *
 * @param startGeometryMeters トンネル入口の累積距離
 * @param endGeometryMeters トンネル出口の累積距離
 */
@Immutable
data class ExtNavTunnelSegment(
    val startGeometryMeters: Double,
    val endGeometryMeters: Double,
) {
    init {
        require(startGeometryMeters.isFinite()) { "startGeometryMeters must be finite." }
        require(endGeometryMeters.isFinite()) { "endGeometryMeters must be finite." }
        require(endGeometryMeters >= startGeometryMeters) { "Tunnel segment must not be reversed." }
    }

    /**
     * 指定した累積距離が区間内かを返す。
     *
     * @param currentGeometryMeters 判定する route geometry 累積距離
     * @return 区間内なら true
     */
    fun contains(currentGeometryMeters: Double): Boolean =
        currentGeometryMeters >= startGeometryMeters && currentGeometryMeters < endGeometryMeters
}

/**
 * 選択 route のトンネル区間を準備する provider。
 */
fun interface ExtNavTunnelSegmentProvider {

    /**
     * 選択 route に紐づくトンネル区間を返す。
     *
     * @param route 案内開始対象の route
     * @return スキャン結果
     */
    suspend fun prepare(route: RouteDetail): TunnelMapStatus
}

/**
 * 外部ナビ API ライブラリ側の実装が未接続のときに使う provider。
 */
data object EmptyExtNavTunnelSegmentProvider : ExtNavTunnelSegmentProvider {

    override suspend fun prepare(route: RouteDetail): TunnelMapStatus = emptyTunnelMapStatus()
}

/**
 * トンネル区間が空の準備済み状態を返す。
 *
 * @return DR 対象トンネルがない ready 状態
 */
internal fun emptyTunnelMapStatus(): TunnelMapStatus = TunnelMapStatus.Ready(persistentListOf())
