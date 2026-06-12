package me.matsumo.onenavi.car.hardware

import android.location.Location
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarUnit
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.StateFlow
import me.matsumo.onenavi.core.common.car.CarHardwareConnectionStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDataStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsState
import me.matsumo.onenavi.core.common.car.CarHardwareEnergySnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationPointSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareSpeedSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareTollCardSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareValueSnapshot
import java.util.concurrent.Executor

/** Android Auto host から車両ハードウェア値を購読し、診断用 StateFlow として公開する。 */
internal class CarHardwareDataSource {

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        publishSpeed(speed)
    }

    private val tollCardListener = OnCarDataAvailableListener<TollCard> { tollCard ->
        publishTollCard(tollCard)
    }

    private val energyLevelListener = OnCarDataAvailableListener<EnergyLevel> { energyLevel ->
        publishEnergyLevel(energyLevel)
    }

    private val carHardwareLocationListener = OnCarDataAvailableListener<CarHardwareLocation> { location ->
        publishLocation(location)
    }

    private var carInfo: CarInfo? = null
    private var carSensors: CarSensors? = null
    private var mainExecutor: Executor? = null

    /** 車両ハードウェア診断の最新スナップショット。 */
    val diagnosticsSnapshot: StateFlow<CarHardwareDiagnosticsSnapshot> = CarHardwareDiagnosticsState.snapshot

    /** Car App Session に接続し、車両ハードウェア値の購読を開始する。 */
    fun attach(carContext: CarContext) {
        val executor = ContextCompat.getMainExecutor(carContext)
        runOnMain(executor) {
            attachOnMain(carContext, executor)
        }
    }

    /** Car App Session から切断し、車両ハードウェア値の購読を解除する。 */
    fun detach() {
        val executor = mainExecutor

        if (executor == null) {
            CarHardwareDiagnosticsState.reset()
            return
        }

        runOnMain(executor, ::detachOnMain)
    }

    private fun runOnMain(executor: Executor, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            executor.execute(action)
        }
    }

    private fun attachOnMain(carContext: CarContext, executor: Executor) {
        detachOnMain()

        val hardwareManager = runCatching {
            carContext.getCarService(CarHardwareManager::class.java)
        }.getOrElse { error ->
            publishAttachFailure(error)
            return
        }

        carInfo = hardwareManager.carInfo
        carSensors = hardwareManager.carSensors
        mainExecutor = executor

        CarHardwareDiagnosticsState.updateSnapshot(
            CarHardwareDiagnosticsSnapshot(
                connectionStatus = CarHardwareConnectionStatus.CONNECTED,
            ),
        )

        startListening(hardwareManager.carInfo, hardwareManager.carSensors, executor)
    }

    private fun detachOnMain() {
        removeCarInfoListeners()
        removeCarSensorListeners()

        carInfo = null
        carSensors = null
        mainExecutor = null

        CarHardwareDiagnosticsState.reset()
    }

    private fun startListening(carInfo: CarInfo, carSensors: CarSensors, executor: Executor) {
        addSpeedListener(carInfo, executor)
        addTollCardListener(carInfo, executor)
        addEnergyLevelListener(carInfo, executor)
        addCarHardwareLocationListener(carSensors, executor)
    }

    private fun addSpeedListener(carInfo: CarInfo, executor: Executor) {
        runCatching {
            carInfo.addSpeedListener(executor, speedListener)
        }.onFailure { error ->
            publishSpeedListenerFailure(error)
        }
    }

    private fun addTollCardListener(carInfo: CarInfo, executor: Executor) {
        runCatching {
            carInfo.addTollListener(executor, tollCardListener)
        }.onFailure { error ->
            publishTollCardListenerFailure(error)
        }
    }

    private fun addEnergyLevelListener(carInfo: CarInfo, executor: Executor) {
        runCatching {
            carInfo.addEnergyLevelListener(executor, energyLevelListener)
        }.onFailure { error ->
            publishEnergyLevelListenerFailure(error)
        }
    }

    private fun addCarHardwareLocationListener(carSensors: CarSensors, executor: Executor) {
        runCatching {
            carSensors.addCarHardwareLocationListener(
                CarSensors.UPDATE_RATE_UI,
                executor,
                carHardwareLocationListener,
            )
        }.onFailure { error ->
            publishLocationListenerFailure(error)
        }
    }

    private fun removeCarInfoListeners() {
        val currentCarInfo = carInfo ?: return

        runCatching {
            currentCarInfo.removeSpeedListener(speedListener)
            currentCarInfo.removeTollListener(tollCardListener)
            currentCarInfo.removeEnergyLevelListener(energyLevelListener)
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to remove car info listeners." }
        }
    }

    private fun removeCarSensorListeners() {
        val currentCarSensors = carSensors ?: return

        runCatching {
            currentCarSensors.removeCarHardwareLocationListener(carHardwareLocationListener)
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to remove car sensor listeners." }
        }
    }

    private fun publishAttachFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "Failed to attach car hardware diagnostics." }

        CarHardwareDiagnosticsState.updateSnapshot(
            CarHardwareDiagnosticsSnapshot(
                connectionStatus = CarHardwareConnectionStatus.UNAVAILABLE,
                message = "CarHardwareManager の取得に失敗: ${error.javaClass.simpleName}",
                speed = CarHardwareSpeedSnapshot.UNAVAILABLE,
                tollCard = CarHardwareTollCardSnapshot.UNAVAILABLE,
                energy = CarHardwareEnergySnapshot.UNAVAILABLE,
                location = CarHardwareLocationSnapshot.UNAVAILABLE,
            ),
        )
    }

    private fun publishSpeed(speed: Speed) {
        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(speed = speed.toSnapshot())
        }
    }

    private fun publishTollCard(tollCard: TollCard) {
        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(tollCard = tollCard.toSnapshot())
        }
    }

    private fun publishEnergyLevel(energyLevel: EnergyLevel) {
        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(energy = energyLevel.toSnapshot())
        }
    }

    private fun publishLocation(location: CarHardwareLocation) {
        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(location = location.toSnapshot())
        }
    }

    private fun publishSpeedListenerFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "Failed to add speed listener." }

        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(
                message = listenerFailureMessage(error),
                speed = CarHardwareSpeedSnapshot.UNAVAILABLE,
            )
        }
    }

    private fun publishTollCardListenerFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "Failed to add toll card listener." }

        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(
                message = listenerFailureMessage(error),
                tollCard = CarHardwareTollCardSnapshot.UNAVAILABLE,
            )
        }
    }

    private fun publishEnergyLevelListenerFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "Failed to add energy level listener." }

        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(
                message = listenerFailureMessage(error),
                energy = CarHardwareEnergySnapshot.UNAVAILABLE,
            )
        }
    }

    private fun publishLocationListenerFailure(error: Throwable) {
        Napier.w(tag = TAG, throwable = error) { "Failed to add car hardware location listener." }

        CarHardwareDiagnosticsState.updateSnapshot { snapshot ->
            snapshot.copy(
                message = listenerFailureMessage(error),
                location = CarHardwareLocationSnapshot.UNAVAILABLE,
            )
        }
    }

    private fun listenerFailureMessage(error: Throwable): String {
        return "一部の車両データリスナー登録に失敗: ${error.javaClass.simpleName}"
    }

    private fun Speed.toSnapshot(): CarHardwareSpeedSnapshot {
        return CarHardwareSpeedSnapshot(
            rawSpeedMetersPerSecond = rawSpeedMetersPerSecond.toSnapshot(),
            displaySpeedMetersPerSecond = displaySpeedMetersPerSecond.toSnapshot(),
            speedDisplayUnit = speedDisplayUnit.toSnapshot { unit -> CarUnit.toString(unit) },
        )
    }

    private fun TollCard.toSnapshot(): CarHardwareTollCardSnapshot {
        return CarHardwareTollCardSnapshot(
            cardState = cardState.toSnapshot { state -> state.toTollCardStateLabel() },
        )
    }

    private fun EnergyLevel.toSnapshot(): CarHardwareEnergySnapshot {
        return CarHardwareEnergySnapshot(
            batteryPercent = batteryPercent.toSnapshot(),
            fuelPercent = fuelPercent.toSnapshot(),
            energyIsLow = energyIsLow.toSnapshot(),
            rangeRemainingMeters = rangeRemainingMeters.toSnapshot(),
            distanceDisplayUnit = distanceDisplayUnit.toSnapshot { unit -> CarUnit.toString(unit) },
        )
    }

    private fun CarHardwareLocation.toSnapshot(): CarHardwareLocationSnapshot {
        return CarHardwareLocationSnapshot(
            location = location.toSnapshot { androidLocation -> androidLocation.toPointSnapshot() },
        )
    }

    private fun Location.toPointSnapshot(): CarHardwareLocationPointSnapshot {
        val altitudeMeters = if (hasAltitude()) altitude else null
        val accuracyMeters = if (hasAccuracy()) accuracy else null
        val bearingDegrees = if (hasBearing()) bearing else null
        val speedMetersPerSecond = if (hasSpeed()) speed else null

        return CarHardwareLocationPointSnapshot(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            accuracyMeters = accuracyMeters,
            bearingDegrees = bearingDegrees,
            speedMetersPerSecond = speedMetersPerSecond,
            provider = provider,
            locationTimeMillis = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    private fun <Value> CarValue<Value>.toSnapshot(): CarHardwareValueSnapshot<Value> {
        return toSnapshot { value -> value }
    }

    private fun <Source, Target> CarValue<Source>.toSnapshot(
        mapper: (Source) -> Target,
    ): CarHardwareValueSnapshot<Target> {
        val mappedValue = value?.let(mapper)

        return CarHardwareValueSnapshot(
            value = mappedValue,
            status = status.toDataStatus(),
            timestampMillis = timestampMillis,
        )
    }

    private fun Int.toDataStatus(): CarHardwareDataStatus {
        return when (this) {
            CarValue.STATUS_SUCCESS -> CarHardwareDataStatus.SUCCESS
            CarValue.STATUS_UNIMPLEMENTED -> CarHardwareDataStatus.UNIMPLEMENTED
            CarValue.STATUS_UNAVAILABLE -> CarHardwareDataStatus.UNAVAILABLE
            else -> CarHardwareDataStatus.UNKNOWN
        }
    }

    private fun Int.toTollCardStateLabel(): String {
        return when (this) {
            TollCard.TOLLCARD_STATE_VALID -> "VALID"
            TollCard.TOLLCARD_STATE_INVALID -> "INVALID"
            TollCard.TOLLCARD_STATE_NOT_INSERTED -> "NOT_INSERTED"
            else -> "UNKNOWN"
        }
    }

    /** logcat 用の固定値。 */
    private companion object {

        /** logcat 抽出用タグ。 */
        const val TAG = "CarHardwareDataSource"
    }
}
