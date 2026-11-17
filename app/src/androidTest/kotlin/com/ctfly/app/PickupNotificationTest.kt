package com.ctfly.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.database.remote.obj
import com.ctfly.app.notifications.PickupNotifications
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class PickupNotificationTest {
    @Test
    fun readyAlertIsReadableDeduplicatedAndClearedOnCollection() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        context
            .getSharedPreferences("ctfly_appearance", 0)
            .edit()
            .putString("language", "en")
            .commit()
        val owner = "notification-test-owner"
        val order = UUID.randomUUID().toString()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        assertTrue(PickupNotifications.allowed(context))
        PickupNotifications.publish(context, owner, order)
        Thread.sleep(500)
        val posted = manager.activeNotifications.single { it.tag == "$owner:$order" }
        assertEquals(
            "Your order is ready!",
            posted.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(
            posted.notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString()
                .contains("café counter")
        )
        assertNotNull(posted.notification.contentIntent)
        PickupNotifications.publish(context, owner, order)
        Thread.sleep(200)
        assertEquals(
            posted.postTime,
            manager.activeNotifications.single { it.tag == "$owner:$order" }.postTime,
        )
        PickupNotifications.acceptSnapshot(
            context,
            obj(
                "profile" to obj("id" to owner, "role" to "customer"),
                "orders" to
                    JSONArray().put(obj("id" to order, "paid" to true, "status" to "Received")),
            ),
        )
        Thread.sleep(200)
        assertTrue(manager.activeNotifications.none { it.tag == "$owner:$order" })
        manager.cancelAll()
    }
}
