package me.matsumo.onenavi.car.vd

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.repository.AppSettingRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Android Auto template 経路で VD Activity 検証 Screen を提供する Service。 */
class CarVirtualDisplayProbeService : CarAppService(), KoinComponent {

    private val applicationScope by inject<CoroutineScope>()
    private val appSettingRepository by inject<AppSettingRepository>()

    override fun onCreateSession(): Session {
        return CarVirtualDisplayProbeSession()
    }

    // Car App API Level 6 以降の host はこちらが呼ばれる。displayType で cluster を判定し、
    // cluster Session が来たことを「実車がクラスター描画に対応している」根拠として記録する。
    // Level 5 以下の host は引数なし onCreateSession() を呼ぶため、minCarApiLevel は 5 のまま据え置ける。
    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) {
            recordClusterSessionDetected()
            return CarClusterProbeSession()
        }

        return CarVirtualDisplayProbeSession()
    }

    override fun createHostValidator(): HostValidator {
        if (isDebuggable()) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }

        return HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    private fun recordClusterSessionDetected() {
        applicationScope.launch {
            appSettingRepository.setHasDetectedClusterSession(true)
        }
    }

    private fun isDebuggable(): Boolean {
        val isDebuggableFlagSet = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
        return isDebuggableFlagSet != 0
    }
}
