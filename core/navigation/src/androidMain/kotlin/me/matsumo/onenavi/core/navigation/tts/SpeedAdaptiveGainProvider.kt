package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.datasource.location.VehicleSpeedState
import me.matsumo.onenavi.core.model.AppSetting

/**
 * 現在の設定と車速から、発話開始時に使うクライアント側 TTS 追加ゲインを返す provider。
 *
 * @property settingProvider 最新のアプリ設定を返す provider
 * @property speedStateProvider 最新の自車速度 state を返す provider
 */
internal class SpeedAdaptiveGainProvider(
    private val settingProvider: () -> AppSetting,
    private val speedStateProvider: () -> VehicleSpeedState,
) {

    /**
     * 発話開始時点で固定する追加ゲインを返す。
     *
     * @return PCM 書き込み前に適用する追加ゲイン
     */
    fun currentGainDb(): Double {
        val setting = settingProvider()
        val speedState = speedStateProvider()

        return SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = speedState.speedMps,
            isEnabled = setting.isSpeedAdaptiveTtsGainEnabled,
            maxGainDb = setting.speedAdaptiveTtsGainMaxDb,
        )
    }
}

/**
 * 自車速度をクライアント側 TTS 追加ゲインへ変換する mapper。
 */
internal object SpeedAdaptiveGainCalculator {

    /** 速度連動ゲインを開始する速度。 */
    private const val GAIN_START_SPEED_KMH = 60.0

    /** 速度連動ゲインが最大になる速度。 */
    private const val GAIN_MAX_SPEED_KMH = 100.0

    /** m/s から km/h への変換係数。 */
    private const val KMH_PER_MPS = 3.6

    /**
     * 自車速度と設定から追加ゲインを算出する。
     *
     * @param speedMps 自車速度
     * @param isEnabled 速度連動ゲインが有効か
     * @param maxGainDb 上限速度で掛ける最大追加ゲイン
     * @return 再生直前に追加するゲイン
     */
    fun gainDbFor(
        speedMps: Float?,
        isEnabled: Boolean,
        maxGainDb: Double,
    ): Double {
        if (!isEnabled) return 0.0

        val currentSpeedMps = speedMps ?: return 0.0
        if (!currentSpeedMps.isFinite()) return 0.0
        if (!maxGainDb.isFinite() || maxGainDb <= 0.0) return 0.0

        val speedKmh = currentSpeedMps * KMH_PER_MPS
        if (speedKmh <= GAIN_START_SPEED_KMH) return 0.0
        if (speedKmh >= GAIN_MAX_SPEED_KMH) return maxGainDb

        val gainRangeKmh = GAIN_MAX_SPEED_KMH - GAIN_START_SPEED_KMH
        val speedRatio = (speedKmh - GAIN_START_SPEED_KMH) / gainRangeKmh

        return maxGainDb * speedRatio
    }
}
