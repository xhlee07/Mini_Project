package com.ctfly.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
fun PickupPermissionPrompt(vm: AppViewModel) {
    if (BuildConfig.STAFF_APP || !vm.hasAccount || vm.role != "customer") return
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                vm.syncPickupDevice(language)
                PickupNotifications.acceptSnapshot(context, vm.data)
            }
        }
    val hasPaidOrder =
        vm.rows("orders").any {
            it.optBoolean("paid") && it.text("status") in listOf("Pending", "Preparing", "Ready")
        }
    LaunchedEffect(vm.userId, hasPaidOrder, language) {
        vm.syncPickupDevice(language)
        if (hasPaidOrder && Build.VERSION.SDK_INT >= 33 && !PickupNotifications.allowed(context)) {
            val preferences = context.getSharedPreferences("ctfly_pickup_notifications", 0)
            val key = "permissionAsked.${vm.userId}"
            if (!preferences.getBoolean(key, false)) {
                preferences.edit().putBoolean(key, true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun PickupAlertPermission(vm: AppViewModel) {
    if (vm.crew || !vm.hasAccount) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(PickupNotifications.allowed(context)) }
    val language = LocalAppLanguage.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = PickupNotifications.allowed(context)
            if (allowed) vm.syncPickupDevice(language)
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = PickupNotifications.allowed(context)
    }
    LaunchedEffect(vm.userId, language, allowed) { vm.syncPickupDevice(language) }
    if (!allowed) {
        SurfaceCard {
            AppText("Don’t miss your pickup", fontWeight = FontWeight.Bold)
            AppText(
                "Allow notifications so we can let you know when your café order is ready.",
                color = Muted,
                fontSize = 13.sp,
            )
            TextButton(
                onClick = {
                    val preferences = context.getSharedPreferences("ctfly_pickup_notifications", 0)
                    val asked = preferences.getBoolean("permissionAsked.${vm.userId}", false)
                    val activity = context as? android.app.Activity
                    val mayRequest =
                        !asked ||
                            activity?.shouldShowRequestPermissionRationale(
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == true
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            mayRequest
                    ) {
                        preferences.edit().putBoolean("permissionAsked.${vm.userId}", true).apply()
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                }
            ) {
                AppText("Enable pickup alerts")
            }
        }
    }
}

@Composable
fun ReadyPickupBanner(vm: AppViewModel) {
    if (vm.crew) return
    val ready = vm.rows("orders").filter { it.optBoolean("paid") && it.text("status") == "Ready" }
    if (ready.isEmpty()) return
    Surface(
        color = Lime.copy(alpha = .12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(Icons.Outlined.NotificationsActive, null, tint = Lime)
            Column(Modifier.weight(1f)) {
                AppText(
                    "Your order is ready!",
                    color = Lime,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                AppText("Pick up at the café counter", color = Muted, fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    if (ready.size == 1) vm.openPickup(ready.first()) else vm.openCafeOrders()
                }
            ) {
                AppText(if (ready.size == 1) "Show QR" else "View orders")
            }
        }
    }
}
