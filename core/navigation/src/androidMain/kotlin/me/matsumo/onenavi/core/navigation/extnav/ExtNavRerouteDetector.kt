package me.matsumo.onenavi.core.navigation.extnav

/**
 * 現在地がルート形状からどれだけ外れているかを判定するデテクタ。
 *
 * Phase 1 の最小実装:
 * - [ExtNavGuidanceTracker] の最近傍 intersection 距離が [offRouteThresholdMetres] を
 *   [persistDurationMillis] 以上継続して超えたら `onOffRoute` を発火
 * - 閾値内に戻ったらカウンタ リセット
 */
class ExtNavRerouteDetector(
    private val offRouteThresholdMetres: Double = DEFAULT_OFF_ROUTE_THRESHOLD_METRES,
    private val persistDurationMillis: Long = DEFAULT_PERSIST_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var awayFromRouteSince: Long? = null

    @Volatile
    private var alreadyFired: Boolean = false

    fun reset() {
        awayFromRouteSince = null
        alreadyFired = false
    }

    /**
     * [ExtNavGuidanceTracker] の snapshot を食わせる。off-route が継続判定されれば
     * [onOffRoute] を 1 度だけ呼び出す。
     *
     * `onOffRoute` 内でユーザが再検索をトリガーしたら、成功後に [reset] を呼ぶこと。
     */
    fun onProgress(
        snapshot: ExtNavProgressSnapshot,
        onOffRoute: () -> Unit,
    ) {
        val distance = snapshot.nearestIntersectionDistanceMetres
        val now = nowMillis()
        if (distance > offRouteThresholdMetres) {
            val since = awayFromRouteSince ?: now.also { awayFromRouteSince = it }
            if (!alreadyFired && now - since >= persistDurationMillis) {
                alreadyFired = true
                onOffRoute()
            }
        } else {
            awayFromRouteSince = null
            if (alreadyFired) alreadyFired = false
        }
    }

    companion object {
        internal const val DEFAULT_OFF_ROUTE_THRESHOLD_METRES: Double = 80.0
        internal const val DEFAULT_PERSIST_MILLIS: Long = 5_000L
    }
}
