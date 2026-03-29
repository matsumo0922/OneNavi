package me.matsumo.onenavi.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.mapbox.common.MapboxOptions
import me.matsumo.onenavi.core.model.AppConfig
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Android Auto 向けの CarAppService。
 * Android Auto ホストからの接続を受け付け、[OneNaviCarSession] を生成する。
 */
class OneNaviCarAppService : CarAppService(), KoinComponent {

    private val appConfig: AppConfig by inject()

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        MapboxOptions.accessToken = appConfig.mapBoxToken
        return OneNaviCarSession()
    }
}
