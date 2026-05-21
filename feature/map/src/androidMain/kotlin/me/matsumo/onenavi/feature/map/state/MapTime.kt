package me.matsumo.onenavi.feature.map.state

/**
 * 地図表示の時間計算 helper。
 */
internal object MapTime {

    /** 1 秒あたりの nanosecond 数。 */
    const val NANOS_PER_SECOND = 1_000_000_000.0

    /** 1 秒あたりの millisecond 数。 */
    const val MILLIS_PER_SECOND = 1_000.0

    /**
     * 2 つの monotonic 時刻の差分を秒へ変換する。
     *
     * @param fromElapsedRealtimeNanos 開始時刻
     * @param toElapsedRealtimeNanos 終了時刻
     * @return 負値を 0 に丸めた経過秒数
     */
    fun elapsedSeconds(
        fromElapsedRealtimeNanos: Long,
        toElapsedRealtimeNanos: Long,
    ): Double = (toElapsedRealtimeNanos - fromElapsedRealtimeNanos)
        .coerceAtLeast(0L)
        .toDouble() / NANOS_PER_SECOND

    /**
     * 2 つの wall clock 時刻の差分を秒へ変換する。
     *
     * @param fromTimestampMillis 開始時刻
     * @param toTimestampMillis 終了時刻
     * @return 負値を 0 に丸めた経過秒数
     */
    fun elapsedWallClockSeconds(
        fromTimestampMillis: Long,
        toTimestampMillis: Long,
    ): Double = (toTimestampMillis - fromTimestampMillis)
        .coerceAtLeast(0L)
        .toDouble() / MILLIS_PER_SECOND
}
