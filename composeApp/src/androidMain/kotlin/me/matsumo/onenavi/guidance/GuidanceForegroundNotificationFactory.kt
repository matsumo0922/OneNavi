package me.matsumo.onenavi.guidance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.matsumo.onenavi.MainActivity
import me.matsumo.onenavi.R
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout

/** 案内中 Foreground Service と Android Auto rail 用の通知を生成する factory。 */
internal class GuidanceForegroundNotificationFactory(
    private val context: Context,
) {

    /** 案内通知用 channel を必要に応じて作成する。 */
    fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.guidance_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.guidance_notification_channel_description)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /** 現在の案内状態から通知を組み立てる。 */
    fun build(state: GuidanceState): Notification {
        val content = state.toNotificationContent()
        val contentIntent = createContentIntent()
        val stopIntent = createStopGuidanceIntent()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.guidance_notification_stop),
                stopIntent,
            )
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(content.title)
                    .setContentText(content.text)
                    .setSmallIcon(R.drawable.ic_stat_navigation)
                    .setContentIntent(contentIntent)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .build(),
            )
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            intent,
            pendingIntentFlags(),
        )
    }

    private fun createStopGuidanceIntent(): PendingIntent {
        return PendingIntent.getService(
            context,
            REQUEST_CODE_STOP_GUIDANCE,
            GuidanceForegroundService.stopGuidanceIntent(context),
            pendingIntentFlags(),
        )
    }

    private fun GuidanceState.toNotificationContent(): GuidanceNotificationContent {
        return when (this) {
            is GuidanceState.Guiding -> toNotificationContent()
            is GuidanceState.Rerouting -> GuidanceNotificationContent(
                title = context.getString(R.string.guidance_notification_title),
                text = context.getString(R.string.guidance_notification_rerouting),
            )
            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> GuidanceNotificationContent(
                title = context.getString(R.string.guidance_notification_title),
                text = context.getString(R.string.guidance_notification_waiting),
            )
        }
    }

    private fun GuidanceState.Guiding.toNotificationContent(): GuidanceNotificationContent {
        val progressText = progress.toProgressText()
        val maneuverText = presentation.nextManeuver?.toNotificationText()
        val notificationText = if (maneuverText != null) {
            "$maneuverText / $progressText"
        } else {
            progressText
        }

        return GuidanceNotificationContent(
            title = context.getString(R.string.guidance_notification_title),
            text = notificationText,
        )
    }

    private fun GuidanceProgress.toProgressText(): String {
        val distanceText = formatDistance(distanceRemainingMeters)
        val durationText = formatDuration(durationRemainingSeconds)

        return context.getString(
            R.string.guidance_notification_progress,
            distanceText,
            durationText,
        )
    }

    private fun ManeuverCallout.toNotificationText(): String {
        val instructionText = GuidanceInstructionFormatter.format(this)
        val distanceText = formatDistance(distanceToManeuverMeters)

        return context.getString(
            R.string.guidance_notification_next_maneuver,
            distanceText,
            instructionText,
        )
    }

    private fun formatDistance(distanceMeters: Int): String {
        val safeDistanceMeters = distanceMeters.coerceAtLeast(0)
        if (safeDistanceMeters < KILOMETER_IN_METERS) {
            return context.getString(R.string.guidance_distance_meters, safeDistanceMeters)
        }

        val distanceKilometers = safeDistanceMeters.toDouble() / KILOMETER_IN_METERS
        return context.getString(R.string.guidance_distance_kilometers, distanceKilometers)
    }

    private fun formatDuration(durationSeconds: Int): String {
        val safeDurationSeconds = durationSeconds.coerceAtLeast(0)
        val durationMinutes = (safeDurationSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

        return context.resources.getQuantityString(
            R.plurals.guidance_duration_minutes,
            durationMinutes,
            durationMinutes,
        )
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    /** 通知に表示する文言。 */
    @Immutable
    private data class GuidanceNotificationContent(
        val title: String,
        val text: String,
    )

    /** 通知 factory で使う固定値。 */
    companion object {
        /** 案内通知 channel ID。 */
        private const val CHANNEL_ID = "guidance_navigation"

        /** 通知 ID。 */
        const val NOTIFICATION_ID = 1001

        /** アプリを開く PendingIntent の request code。 */
        const val REQUEST_CODE_OPEN_APP = 2001

        /** 案内停止 PendingIntent の request code。 */
        const val REQUEST_CODE_STOP_GUIDANCE = 2002

        /** km 表示に切り替える距離。 */
        const val KILOMETER_IN_METERS = 1000

        /** 分換算に使う秒数。 */
        const val SECONDS_PER_MINUTE = 60
    }
}
