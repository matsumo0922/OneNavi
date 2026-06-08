package me.matsumo.onenavi.car.vd

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Android Auto template 経路で VD Activity 検証 Screen を提供する Service。 */
class CarVirtualDisplayProbeService : CarAppService() {

    override fun onCreateSession(): Session {
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

    private fun isDebuggable(): Boolean {
        val isDebuggableFlagSet = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
        return isDebuggableFlagSet != 0
    }
}
