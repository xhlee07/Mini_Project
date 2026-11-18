package com.ctfly.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.ctfly.app.BuildConfig
import com.ctfly.app.CtflyApplication
import com.ctfly.app.MainActivity
import com.ctfly.app.R
import com.ctfly.app.database.local.AppPreferences
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.profileId
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.ui.common.AppStrings
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject

object PickupNotifications {
    const val CHANNEL = "cafe_pickup"

    private fun store(context: Context) =
        context.getSharedPreferences("ctfly_pickup_notifications", Context.MODE_PRIVATE)

    fun installationId(context: Context): String {
        val preferences = store(context)
        return preferences.getString("installation", null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("installation", it).apply()
            }
    }

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE
    }

    fun createChannel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Café pickup", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Alerts when your café order is ready to collect" }
            )
    }

    suspend fun token(context: Context): String? {
        if (BuildConfig.STAFF_APP || !BuildConfig.PUSH_CONFIGURED || !allowed(context)) return null
        if (FirebaseApp.getApps(context).isEmpty())
            FirebaseApp.initializeApp(context) ?: return null
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance()
                .token
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
        }
    }

    fun acceptSnapshot(context: Context, snapshot: JSONObject) {
        if (BuildConfig.STAFF_APP) return
        val user = snapshot.optJSONObject("profile") ?: return
        if (user.text("role") != "customer") return
        val owner = user.profileId()
        snapshot.rows("orders").forEach { order ->
            if (order.text("status") == "Ready" && order.optBoolean("paid")) {
                publish(context, owner, order.text("id"))
            } else {
                context
                    .getSystemService(NotificationManager::class.java)
                    .cancel("$owner:${order.text("id")}", 0)
            }
        }
    }

    fun publish(context: Context, owner: String, order: String) {
        if (BuildConfig.STAFF_APP || owner.isBlank() || order.isBlank() || !allowed(context)) return
        val preferences = store(context)
        val key = "notified.$owner"
        val seen = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (!seen.add(order)) return
        AppStrings.load(context)
        val language =
            context.getSharedPreferences("ctfly_appearance", 0).getString("language", "en") ?: "en"
        val title = AppStrings.translate("Your order is ready!", language)
        val body =
            AppStrings.translate(
                    "Order #{0} · Come to the café counter and show your pickup QR to staff.",
                    language,
                )
                .replace("{0}", order.take(8))
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = Uri.parse("ctfly://pickup/$owner/$order")
                putExtra("pickup_user", owner)
                putExtra("pickup_order", order)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pickup_notification)
                .setColor(0xFFC6F56B.toInt())
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
        try {
            context
                .getSystemService(NotificationManager::class.java)
                .notify("$owner:$order", 0, notification)
            preferences.edit().putStringSet(key, seen.toList().takeLast(100).toSet()).apply()
        } catch (_: SecurityException) {
            /* User may revoke permission while this alert is being prepared. */
        }
    }
}

class PickupMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (BuildConfig.STAFF_APP || !PickupNotifications.allowed(this)) return
        // Always bind a rotated token to the currently signed-in account, never a saved user ID.
        val api = (application as CtflyApplication).gatewayFactory(this)
        if (
            !api.signedIn || api.cachedSnapshot.optJSONObject("profile")?.text("role") != "customer"
        )
            return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                api.rpc(
                    "ctfly_action",
                    obj(
                        "p_action" to "register_push_device",
                        "p_request" to UUID.randomUUID().toString(),
                        "p_data" to
                            obj(
                                "installation_id" to
                                    PickupNotifications.installationId(this@PickupMessagingService),
                                "token" to token,
                                "language" to AppPreferences(this@PickupMessagingService).language,
                            ),
                    ),
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "order_ready" || BuildConfig.STAFF_APP) return
        val api = (application as CtflyApplication).gatewayFactory(this)
        val profile = api.cachedSnapshot.optJSONObject("profile")
        // Data-only messages let us reject alerts for an account that logged out or switched.
        if (
            !api.signedIn ||
                profile?.text("role") != "customer" ||
                profile.profileId() != data["user_id"]
        )
            return
        PickupNotifications.publish(this, profile.profileId(), data["order_id"].orEmpty())
    }
}
