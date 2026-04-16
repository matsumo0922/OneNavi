package me.matsumo.onenavi.core.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

class NavigationUpdatesService : Service() {

    private lateinit var incomingMessenger: Messenger
    private lateinit var turnByTurnManager: TurnByTurnManager

    override fun onCreate() {
        super.onCreate()

        turnByTurnManager = TurnByTurnManager.createInstance()
        val thread = HandlerThread(
            "NavigationUpdatesService",
            Process.THREAD_PRIORITY_DEFAULT,
        )
        thread.start()
        incomingMessenger = Messenger(IncomingNavStepHandler(thread.looper))
    }

    override fun onBind(intent: Intent?): IBinder {
        return incomingMessenger.binder
    }

    private inner class IncomingNavStepHandler(
        looper: Looper,
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return

            TurnByTurnUpdateBus.publish(
                turnByTurnManager.readNavInfoFromBundle(msg.data),
            )
        }
    }
}
