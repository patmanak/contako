package com.patmanak.contako.android.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.SyncActionReason
import com.patmanak.contako.data.sync.SyncStatusUpdate
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Physical notification-service tests. No AccountManager account or Proton session is created. */
@RunWith(AndroidJUnit4::class)
class AndroidSyncActionNotifierDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var store: RoomSyncStatusStore
    private lateinit var manager: NotificationManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Permission commands and cancellation MUST only affect the isolated test application.
        check(context.packageName.endsWith(".auditqa"))
        database = ContakoDatabase.create(context, "notifier-device-test.db")
        store = RoomSyncStatusStore(database)
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
    }

    @After fun tearDown() {
        manager.cancelAll()
        database.close()
        context.deleteDatabase("notifier-device-test.db")
    }

    @Test fun deniedPublicationRemainsDue() = runBlocking {
        check(!granted()) { "Run this case with notifications denied before instrumentation starts" }
        publishBlock()
        AndroidSyncActionNotifier(context, store).refresh(ACCOUNT, NOW + DAY)
        assertFalse(requireNotNull(store.load(ACCOUNT)).notificationClaimed)
        assertEquals(0, manager.activeNotifications.size)

    }

    @Test fun deliverySurvivesDatabaseReopenAndRecoveryCancelsActualAlert() = runBlocking {
        check(granted()) { "Run this case with notifications granted before instrumentation starts" }
        publishBlock()
        AndroidSyncActionNotifier(context, store).refresh(ACCOUNT, NOW + DAY)
        await { manager.activeNotifications.size == 1 }
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.sync_alert_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.sync_alert_body),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.contentIntent.isImmutable)
        assertTrue(requireNotNull(store.load(ACCOUNT)).notificationClaimed)
        val postedAt = manager.activeNotifications.single().postTime
        database.close()
        database = ContakoDatabase.create(context, "notifier-device-test.db")
        store = RoomSyncStatusStore(database)
        AndroidSyncActionNotifier(context, store).refresh(ACCOUNT, NOW + DAY + 1)
        assertEquals(postedAt, manager.activeNotifications.single().postTime)
        store.publish(ACCOUNT, NOW + DAY + 2, SyncStatusUpdate(SyncPassOutcome.SUCCESS, 0, 0))
        AndroidSyncActionNotifier(context, store).refresh(ACCOUNT, NOW + DAY + 2)
        await { manager.activeNotifications.isEmpty() }
    }

    private suspend fun publishBlock() {
        store.publish(ACCOUNT, NOW, SyncStatusUpdate(SyncPassOutcome.ACTION_REQUIRED, 1, 1,
            SyncActionReason.CONFLICT_RECOVERY_REQUIRED))
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Notification service did not settle" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val ACCOUNT = "synthetic-notification-scope"
        const val NOW = 1_000L
        const val DAY = 86_400_000L
    }
}
