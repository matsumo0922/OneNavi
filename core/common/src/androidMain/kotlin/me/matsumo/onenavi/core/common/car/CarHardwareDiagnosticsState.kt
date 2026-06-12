package me.matsumo.onenavi.core.common.car

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 車両ハードウェア診断の接続状態。 */
enum class CarHardwareConnectionStatus {

    /** Android Auto の Car Session に未接続。 */
    DISCONNECTED,

    /** CarHardwareManager へ接続し、購読を開始済み。 */
    CONNECTED,

    /** CarHardwareManager への接続または購読の初期化に失敗。 */
    UNAVAILABLE,
}

/** 車両ハードウェア値の取得状態。 */
enum class CarHardwareDataStatus {

    /** 値の状態が不明。 */
    UNKNOWN,

    /** 値の取得に成功。 */
    SUCCESS,

    /** 車両またはヘッドユニットが対象値を実装していない。 */
    UNIMPLEMENTED,

    /** 対象値が一時的に利用できない。 */
    UNAVAILABLE,
}

/**
 * 車両ハードウェアから届く単一値の診断スナップショット。
 *
 * @param value 取得できた値。成功以外では null
 * @param status 値の取得状態
 * @param timestampMillis 車両 API が返した経過時刻ミリ秒
 */
@Immutable
data class CarHardwareValueSnapshot<out T>(
    val value: T?,
    val status: CarHardwareDataStatus,
    val timestampMillis: Long,
) {
    /** 車両ハードウェア値の既定スナップショット。 */
    companion object {

        /** 値がまだ届いていない状態。 */
        fun <T> unknown(): CarHardwareValueSnapshot<T> {
            return CarHardwareValueSnapshot(
                value = null,
                status = CarHardwareDataStatus.UNKNOWN,
                timestampMillis = 0L,
            )
        }

        /** リスナー登録や取得処理が利用不能になった状態。 */
        fun <T> unavailable(): CarHardwareValueSnapshot<T> {
            return CarHardwareValueSnapshot(
                value = null,
                status = CarHardwareDataStatus.UNAVAILABLE,
                timestampMillis = 0L,
            )
        }
    }
}

/**
 * 車速診断のスナップショット。
 *
 * @param rawSpeedMetersPerSecond 車両側 raw speed
 * @param displaySpeedMetersPerSecond メーター表示相当の speed
 * @param speedDisplayUnit 車両設定上の速度表示単位
 */
@Immutable
data class CarHardwareSpeedSnapshot(
    val rawSpeedMetersPerSecond: CarHardwareValueSnapshot<Float>,
    val displaySpeedMetersPerSecond: CarHardwareValueSnapshot<Float>,
    val speedDisplayUnit: CarHardwareValueSnapshot<String>,
) {
    /** 車速診断の既定スナップショット。 */
    companion object {

        /** 値がまだ届いていない状態。 */
        val UNKNOWN = CarHardwareSpeedSnapshot(
            rawSpeedMetersPerSecond = CarHardwareValueSnapshot.unknown(),
            displaySpeedMetersPerSecond = CarHardwareValueSnapshot.unknown(),
            speedDisplayUnit = CarHardwareValueSnapshot.unknown(),
        )

        /** 車速リスナーが利用不能になった状態。 */
        val UNAVAILABLE = CarHardwareSpeedSnapshot(
            rawSpeedMetersPerSecond = CarHardwareValueSnapshot.unavailable(),
            displaySpeedMetersPerSecond = CarHardwareValueSnapshot.unavailable(),
            speedDisplayUnit = CarHardwareValueSnapshot.unavailable(),
        )
    }
}

/**
 * ETC カード診断のスナップショット。
 *
 * @param cardState ETC カード挿入状態
 */
@Immutable
data class CarHardwareTollCardSnapshot(
    val cardState: CarHardwareValueSnapshot<String>,
) {
    /** ETC カード診断の既定スナップショット。 */
    companion object {

        /** 値がまだ届いていない状態。 */
        val UNKNOWN = CarHardwareTollCardSnapshot(
            cardState = CarHardwareValueSnapshot.unknown(),
        )

        /** ETC カードリスナーが利用不能になった状態。 */
        val UNAVAILABLE = CarHardwareTollCardSnapshot(
            cardState = CarHardwareValueSnapshot.unavailable(),
        )
    }
}

/**
 * 燃料・電池残量診断のスナップショット。
 *
 * @param batteryPercent 電池残量
 * @param fuelPercent 燃料残量
 * @param energyIsLow 残量低下フラグ
 * @param rangeRemainingMeters 推定航続距離
 * @param distanceDisplayUnit 距離表示単位
 */
@Immutable
data class CarHardwareEnergySnapshot(
    val batteryPercent: CarHardwareValueSnapshot<Float>,
    val fuelPercent: CarHardwareValueSnapshot<Float>,
    val energyIsLow: CarHardwareValueSnapshot<Boolean>,
    val rangeRemainingMeters: CarHardwareValueSnapshot<Float>,
    val distanceDisplayUnit: CarHardwareValueSnapshot<String>,
) {
    /** 燃料・電池残量診断の既定スナップショット。 */
    companion object {

        /** 値がまだ届いていない状態。 */
        val UNKNOWN = CarHardwareEnergySnapshot(
            batteryPercent = CarHardwareValueSnapshot.unknown(),
            fuelPercent = CarHardwareValueSnapshot.unknown(),
            energyIsLow = CarHardwareValueSnapshot.unknown(),
            rangeRemainingMeters = CarHardwareValueSnapshot.unknown(),
            distanceDisplayUnit = CarHardwareValueSnapshot.unknown(),
        )

        /** 燃料・電池残量リスナーが利用不能になった状態。 */
        val UNAVAILABLE = CarHardwareEnergySnapshot(
            batteryPercent = CarHardwareValueSnapshot.unavailable(),
            fuelPercent = CarHardwareValueSnapshot.unavailable(),
            energyIsLow = CarHardwareValueSnapshot.unavailable(),
            rangeRemainingMeters = CarHardwareValueSnapshot.unavailable(),
            distanceDisplayUnit = CarHardwareValueSnapshot.unavailable(),
        )
    }
}

/**
 * 車両位置の値を Compose から安全に参照するための不変コピー。
 *
 * @param latitude 緯度
 * @param longitude 経度
 * @param altitudeMeters 高度
 * @param accuracyMeters 水平精度
 * @param bearingDegrees 方位
 * @param speedMetersPerSecond 位置情報由来の速度
 * @param provider 位置情報プロバイダ
 * @param locationTimeMillis 位置情報自体の時刻
 * @param elapsedRealtimeNanos 位置情報自体の経過時刻ナノ秒
 */
@Immutable
data class CarHardwareLocationPointSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val bearingDegrees: Float?,
    val speedMetersPerSecond: Float?,
    val provider: String?,
    val locationTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
)

/**
 * 車両位置診断のスナップショット。
 *
 * @param location 車両ハードウェア位置
 */
@Immutable
data class CarHardwareLocationSnapshot(
    val location: CarHardwareValueSnapshot<CarHardwareLocationPointSnapshot>,
) {
    /** 車両位置診断の既定スナップショット。 */
    companion object {

        /** 値がまだ届いていない状態。 */
        val UNKNOWN = CarHardwareLocationSnapshot(
            location = CarHardwareValueSnapshot.unknown(),
        )

        /** 車両位置リスナーが利用不能になった状態。 */
        val UNAVAILABLE = CarHardwareLocationSnapshot(
            location = CarHardwareValueSnapshot.unavailable(),
        )
    }
}

/**
 * 車両ハードウェア診断画面で表示する全データのスナップショット。
 *
 * @param connectionStatus CarHardwareManager の接続状態
 * @param message 接続または購読で発生した補足メッセージ
 * @param speed 車速診断
 * @param tollCard ETC カード診断
 * @param energy 燃料・電池残量診断
 * @param location 車両位置診断
 */
@Immutable
data class CarHardwareDiagnosticsSnapshot(
    val connectionStatus: CarHardwareConnectionStatus = CarHardwareConnectionStatus.DISCONNECTED,
    val message: String? = null,
    val speed: CarHardwareSpeedSnapshot = CarHardwareSpeedSnapshot.UNKNOWN,
    val tollCard: CarHardwareTollCardSnapshot = CarHardwareTollCardSnapshot.UNKNOWN,
    val energy: CarHardwareEnergySnapshot = CarHardwareEnergySnapshot.UNKNOWN,
    val location: CarHardwareLocationSnapshot = CarHardwareLocationSnapshot.UNKNOWN,
)

/** 車両ハードウェア診断値をプロセス内で共有する状態ホルダー。 */
object CarHardwareDiagnosticsState {

    private val _snapshot = MutableStateFlow(CarHardwareDiagnosticsSnapshot())

    /** 車両ハードウェア診断の最新スナップショット。 */
    val snapshot: StateFlow<CarHardwareDiagnosticsSnapshot> = _snapshot.asStateFlow()

    /** 車両ハードウェア診断の最新スナップショットを置き換える。 */
    fun updateSnapshot(snapshot: CarHardwareDiagnosticsSnapshot) {
        _snapshot.value = snapshot
    }

    /** 車両ハードウェア診断の最新スナップショットを現在値から更新する。 */
    fun updateSnapshot(transform: (CarHardwareDiagnosticsSnapshot) -> CarHardwareDiagnosticsSnapshot) {
        _snapshot.update(transform)
    }

    /** 車両ハードウェア診断の状態を未接続状態へ戻す。 */
    fun reset() {
        updateSnapshot(CarHardwareDiagnosticsSnapshot())
    }
}
