package me.matsumo.onenavi.car

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.Display
import me.matsumo.onenavi.MainActivity
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher

/**
 * スマホ本体 display 上の [MainActivity] を目的地検索入口として起動する launcher。
 */
class MainActivityPhoneDestinationSearchLauncher(
    private val context: Context,
) : PhoneDestinationSearchLauncher {

    override fun launchDestinationSearch(): Result<Unit> {
        return runCatching {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(PhoneDestinationSearchLauncher.ACTION_OPEN_DESTINATION_SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY)

            context.startActivity(intent, options.toBundle())
        }
    }
}
