package me.matsumo.onenavi.car

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.Display
import me.matsumo.onenavi.MainActivity
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher

/**
 * スマホ本体 display 上の [MainActivity] を検索入口として起動する launcher。
 */
class MainActivityPhoneDestinationSearchLauncher(
    private val context: Context,
    private val carPhoneSessionCoordinator: CarPhoneSessionCoordinator,
) : PhoneDestinationSearchLauncher {

    override fun launchDestinationSearch(): Result<Unit> {
        return launchPhoneSearch(carPhoneSessionCoordinator::requestPhoneDestinationSearch)
    }

    override fun launchAddWaypointSearch(): Result<Unit> {
        return launchPhoneSearch(carPhoneSessionCoordinator::requestPhoneAddWaypointSearch)
    }

    private fun launchPhoneSearch(requestPhoneCommand: () -> Long): Result<Unit> {
        return runCatching {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            val commandId = requestPhoneCommand()

            runCatching {
                context.startActivity(intent, options.toBundle())
            }.onFailure {
                carPhoneSessionCoordinator.consumePhoneCommand(commandId)
            }.getOrThrow()
        }
    }
}
