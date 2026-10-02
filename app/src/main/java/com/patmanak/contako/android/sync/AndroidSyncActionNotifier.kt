package com.patmanak.contako.android.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.patmanak.contako.MainActivity
import com.patmanak.contako.R
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.SyncHealthState
import kotlinx.coroutines.CancellationException

/** Generic account-free notification, with a stable Android identity across process restart. */
internal class AndroidSyncActionNotifier(context: Context, private val store: RoomSyncStatusStore) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    suspend fun refresh(accountId: String, now: Long = System.currentTimeMillis()) {
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.sync_alert_title), NotificationManager.IMPORTANCE_DEFAULT))
            val allowed = !(!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
                (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
            store.deliverNotificationIfDue(accountId, now, cancelWhenClear = { manager.cancel(NOTIFICATION_ID) }) {
                if (!allowed) return@deliverNotificationIfDue false
                val intent = Intent(context, MainActivity::class.java).setAction(OPEN_SYNC_ACTION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_sync_notification)
                    .setContentTitle(context.getString(R.string.sync_alert_title))
                    .setContentText(context.getString(R.string.sync_alert_body))
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build()
                manager.notify(NOTIFICATION_ID, notification)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Notification service failure never changes synchronization outcome.
        }
    }

    companion object {
        const val OPEN_SYNC_ACTION = "com.patmanak.contako.OPEN_SYNC"
        const val CHANNEL = "sync_actions"
        private const val NOTIFICATION_ID = 1
    }
}
