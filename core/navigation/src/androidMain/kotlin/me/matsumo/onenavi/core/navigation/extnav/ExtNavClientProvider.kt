package me.matsumo.onenavi.core.navigation.extnav

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.drive.supporter.api.DriveSupporterClient
import me.matsumo.drive.supporter.api.DriveSupporterConfig
import me.matsumo.drive.supporter.api.core.model.DeviceUuid
import me.matsumo.drive.supporter.api.core.model.LogLevel
import me.matsumo.onenavi.core.datasource.AppSettingDataSource

/**
 * drive-supporter-api の [DriveSupporterClient] を lazy singleton として組み立てるプロバイダ。
 * DeviceUuid は [AppSettingDataSource] から取得 / 生成する。credential は
 * [ExtNavAuthGateway] 側で `signInWithCredentials` に流すため本クラスでは受け取らない。
 */
class ExtNavClientProvider(
    private val context: Context,
    private val appSettingDataSource: AppSettingDataSource,
    private val logLevel: LogLevel = LogLevel.NONE,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: DriveSupporterClient? = null

    suspend fun get(): DriveSupporterClient {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: createClient().also { cached = it }
        }
    }

    fun close() {
        cached?.close()
        cached = null
    }

    private suspend fun createClient(): DriveSupporterClient {
        val uuid = appSettingDataSource.getOrCreateExtNavDeviceUuid()
        val config = DriveSupporterConfig(
            deviceUuid = DeviceUuid(uuid),
            logLevel = logLevel,
        )
        return DriveSupporterClient(
            context = context,
            config = config,
        )
    }
}
