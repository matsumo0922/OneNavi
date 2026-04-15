package me.matsumo.onenavi.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Android Auto 向けの CarAppService。
 * Android Auto ホストからの接続を受け付け、[OneNaviCarSession] を生成する。
 */
class OneNaviCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return OneNaviCarSession()
    }
}
