package me.matsumo.onenavi.car.vd

import android.os.SystemClock
import io.github.aakira.napier.Napier

/**
 * Android Auto VD 入力経路の処理時間を logcat へ出す logger。
 *
 * @property isEnabled ログ出力が有効な場合に true を返す provider
 * @property clockUptimeMillis 計測に使う uptime clock
 */
internal class CarVirtualDisplayProbeInputLatencyLogger(
    private val isEnabled: () -> Boolean,
    private val clockUptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {

    /** 現在の uptime millis を返す。 */
    fun now(): Long {
        return clockUptimeMillis()
    }

    /**
     * 計測開始時刻からの経過時間を返す。
     *
     * @param startedAtMillis 計測開始の uptime millis
     * @return 経過時間
     */
    fun elapsedSince(startedAtMillis: Long): Long {
        return now() - startedAtMillis
    }

    /**
     * SurfaceCallback.onClick の受信時刻を記録する。
     *
     * @param inputState click 入力 state
     */
    fun logClickReceived(inputState: CarVirtualDisplayProbeInputState) {
        if (!isEnabled()) return

        Napier.i(tag = TAG) {
            "click received seq=${inputState.sequence} received=${inputState.receivedUptimeMillis} " +
                "point=${inputState.surfacePointLabel} pan=${inputState.panModeLabel}"
        }
    }

    /**
     * semantics node 走査の所要時間を記録する。
     *
     * @param inputState click 入力 state
     * @param mergingEnabled merged semantics を走査した場合は true
     * @param nodeCount 走査対象 node 数
     * @param elapsedMillis 走査にかかった時間
     * @param didFindTarget target node が見つかった場合は true
     */
    fun logSemanticsScan(
        inputState: CarVirtualDisplayProbeInputState,
        mergingEnabled: Boolean,
        nodeCount: Int,
        elapsedMillis: Long,
        didFindTarget: Boolean,
    ) {
        if (!isEnabled()) return

        Napier.i(tag = TAG) {
            "semantics scan seq=${inputState.sequence} merged=$mergingEnabled nodes=$nodeCount " +
                "elapsedMs=$elapsedMillis target=$didFindTarget"
        }
    }

    /**
     * semantics action 実行の所要時間を記録する。
     *
     * @param inputState click 入力 state
     * @param elapsedMillis action 実行にかかった時間
     * @param didHandleClick action が click を処理した場合は true
     */
    fun logSemanticsAction(
        inputState: CarVirtualDisplayProbeInputState,
        elapsedMillis: Long,
        didHandleClick: Boolean,
    ) {
        if (!isEnabled()) return

        Napier.i(tag = TAG) {
            "semantics action seq=${inputState.sequence} elapsedMs=$elapsedMillis handled=$didHandleClick"
        }
    }

    /**
     * gesture fallback へ進んだことと ACTION_UP の予約遅延を記録する。
     *
     * @param inputState click 入力 state
     * @param upDelayMillis ACTION_UP を送るまでの遅延
     */
    fun logGestureFallback(inputState: CarVirtualDisplayProbeInputState, upDelayMillis: Long) {
        if (!isEnabled()) return

        Napier.i(tag = TAG) {
            "gesture fallback seq=${inputState.sequence} upDelayMs=$upDelayMillis"
        }
    }

    /**
     * click dispatch 全体の結果を記録する。
     *
     * @param inputState click 入力 state
     * @param path click を処理した経路
     * @param startedAtMillis dispatch 開始の uptime millis
     * @param didDispatch dispatch が処理された場合は true
     */
    fun logClickDispatchFinished(
        inputState: CarVirtualDisplayProbeInputState,
        path: String,
        startedAtMillis: Long,
        didDispatch: Boolean,
    ) {
        if (!isEnabled()) return

        val appElapsedMillis = elapsedSince(startedAtMillis)
        val sinceReceivedMillis = inputState.receivedUptimeMillis?.let { receivedAtMillis ->
            now() - receivedAtMillis
        }

        Napier.i(tag = TAG) {
            "click dispatch seq=${inputState.sequence} path=$path appElapsedMs=$appElapsedMillis " +
                "sinceReceivedMs=${sinceReceivedMillis ?: "n/a"} handled=$didDispatch"
        }
    }

    /** 入力遅延ログの logcat タグ。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVdLatency"
    }
}
