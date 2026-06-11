package me.matsumo.onenavi.guidance

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 端末がバックグラウンドやロック画面でも案内状態を維持する Foreground Service。 */
class GuidanceForegroundService : android.app.Service(), KoinComponent {

    private val newGuidanceManager by inject<NewGuidanceManager>()
    private lateinit var notificationFactory: GuidanceForegroundNotificationFactory
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var guidanceStateJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory = GuidanceForegroundNotificationFactory(applicationContext)
        notificationFactory.ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP_GUIDANCE) {
            newGuidanceManager.stopGuidance()
            stopForegroundService()
            return START_NOT_STICKY
        }

        val isForegroundStarted = startForegroundSafely(newGuidanceManager.state.value)
        if (!isForegroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureGuidanceStateObserver()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        guidanceStateJob?.cancel()
        guidanceStateJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureGuidanceStateObserver() {
        if (guidanceStateJob != null) {
            return
        }

        guidanceStateJob = newGuidanceManager.state
            .onEach(::handleGuidanceState)
            .launchIn(serviceScope)
    }

    private fun handleGuidanceState(state: GuidanceState) {
        if (!state.requiresForegroundService()) {
            stopForegroundService()
            return
        }

        updateNotification(state)
    }

    private fun startForegroundSafely(state: GuidanceState): Boolean {
        val notification = notificationFactory.build(state)
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        return runCatching {
            ServiceCompat.startForeground(
                this,
                GuidanceForegroundNotificationFactory.NOTIFICATION_ID,
                notification,
                foregroundServiceType,
            )
        }.onFailure { error ->
            Napier.e(tag = TAG, throwable = error) { "Failed to start guidance foreground service." }
        }.isSuccess
    }

    private fun updateNotification(state: GuidanceState) {
        val notification = notificationFactory.build(state)

        runCatching {
            NotificationManagerCompat.from(this)
                .notify(GuidanceForegroundNotificationFactory.NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to update guidance notification." }
        }
    }

    private fun stopForegroundService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun GuidanceState.requiresForegroundService(): Boolean {
        return when (this) {
            is GuidanceState.Guiding,
            is GuidanceState.Rerouting,
            -> true
            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> false
        }
    }

    /** 案内 Foreground Service の起動 API と固定値。 */
    companion object {
        /** Service 起動 action。 */
        private const val ACTION_START = "me.matsumo.onenavi.guidance.START"

        /** 通知 action から案内自体を停止する action。 */
        private const val ACTION_STOP_GUIDANCE = "me.matsumo.onenavi.guidance.STOP_GUIDANCE"

        /** logcat 抽出用タグ。 */
        private const val TAG = "GuidanceForegroundService"

        /** 案内状態に合わせて Foreground Service を起動する。 */
        fun start(context: Context) {
            val intent = Intent(context, GuidanceForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 案内状態が非 active になったとき Foreground Service を停止する。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, GuidanceForegroundService::class.java))
        }

        /** 通知の停止 action で使う Intent。 */
        fun stopGuidanceIntent(context: Context): Intent {
            return Intent(context, GuidanceForegroundService::class.java)
                .setAction(ACTION_STOP_GUIDANCE)
        }
    }
}
