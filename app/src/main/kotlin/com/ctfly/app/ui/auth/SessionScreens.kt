package com.ctfly.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ctfly.app.database.remote.text
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Brand
import com.ctfly.app.ui.common.Ink
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.SurfaceCard

@Composable
fun AccountRestoringScreen(vm: AppViewModel) {
    Box(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().testTag("account-restoring"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand(compact = true)
            if (vm.loading) {
                CircularProgressIndicator(Modifier.size(28.dp))
                AppText("Opening your account…", color = Muted)
            } else {
                AppText("Your account is saved", style = MaterialTheme.typography.titleLarge)
                AppText("Connect to the internet to load your account.", color = Muted)
                Button(onClick = { vm.refresh() }) { AppText("Try again") }
                TextButton(onClick = { vm.logout() }, enabled = !vm.busy) {
                    AppText("Use another account")
                }
            }
        }
    }
}

/** Optional fingerprint entry alongside the original email/password form. */
@Composable
fun StaffFingerprintSignIn(vm: AppViewModel) {
    if (!vm.staffBiometrics.available()) return
    OutlinedButton(
        onClick = { vm.requestFingerprint() },
        enabled = !vm.busy && vm.biometricRequest == null,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("staff-fingerprint-sign-in"),
    ) {
        AppIcon(Icons.Outlined.Fingerprint, "Fingerprint")
        Spacer(Modifier.width(10.dp))
        AppText("Sign in with fingerprint")
    }
}

@Composable
fun StaffFingerprintOffer(vm: AppViewModel) {
    if (!vm.offerFingerprint || vm.staffLocked || !vm.hasAccount) return
    AlertDialog(
        onDismissRequest = { vm.declineFingerprint() },
        icon = { AppIcon(Icons.Outlined.Fingerprint, "Fingerprint") },
        title = { AppText("Use fingerprint next time?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppText(
                    "Add this phone to your shared staff account. Up to five devices can be registered."
                )
                Input(
                    "Staff name",
                    vm.field("deviceStaffName"),
                    {
                        vm.set("deviceStaffName", it.take(60))
                        vm.clearValidationError()
                    },
                )
                AppText(
                    "Use a fingerprint already enrolled in this phone's system settings. Sign out keeps this device registered.",
                    color = Muted,
                )
                if (vm.validationError.isNotBlank())
                    AppText(vm.validationError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.requestFingerprint(enabling = true) }, enabled = !vm.busy) {
                AppText("Add device")
            }
        },
        dismissButton = { TextButton(onClick = { vm.declineFingerprint() }) { AppText("Not now") } },
    )
}

@Composable
fun StaffSecuritySettings(vm: AppViewModel) {
    LaunchedEffect(vm.userId) { vm.loadStaffDevices() }
    var removing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    SurfaceCard {
        SectionTitle("Staff security", "Choose how you sign in on this phone.")
        vm.fieldVersion
        AppText("Registered devices", style = MaterialTheme.typography.titleMedium)
        AppText("${vm.staffDevices.size} / 5", color = Muted, localize = false)
        AppText(
            "Each staff member adds their own phone here using the same email account.",
            color = Muted,
        )
        if (vm.staffDevicesLoading) CircularProgressIndicator(Modifier.size(24.dp))
        if (vm.staffDevicesError.isNotBlank()) {
            AppText(vm.staffDevicesError, color = MaterialTheme.colorScheme.error)
        }
        vm.staffDevices.forEach { device ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(
                    device.text("staff_name"),
                    style = MaterialTheme.typography.titleMedium,
                    localize = false,
                )
                AppText(device.text("device_name"), color = Muted, localize = false)
                if (device.text("id") == vm.staffBiometrics.deviceId)
                    AppText("This device", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { removing = device }, enabled = !vm.busy) {
                    AppText("Remove device")
                }
            }
        }
        TextButton(
            onClick = { vm.loadStaffDevices() },
            enabled = !vm.staffDevicesLoading && !vm.busy,
        ) {
            AppText("Refresh devices")
        }
        if (vm.staffBiometrics.available()) {
            val enabled = vm.staffBiometrics.enabled(vm.userId)
            AppText(
                if (enabled) "Fingerprint sign-in is on for this phone."
                else "Add this device to enable fingerprint sign-in.",
                color = Muted,
            )
            OutlinedButton(
                onClick = { vm.showFingerprintSetup() },
                enabled =
                    !enabled &&
                        !vm.busy &&
                        vm.staffDevicesLoaded &&
                        (vm.staffDevices.size < 5 ||
                            vm.staffDevices.any { it.text("id") == vm.staffBiometrics.deviceId }),
            ) {
                AppText(if (enabled) "This device is registered" else "Add device")
            }
            if (!enabled && vm.staffDevices.size >= 5)
                AppText(
                    "Five devices are already registered. Remove a device before adding another.",
                    color = Muted,
                )
        } else AppText("Use your staff account password on this device.", color = Muted)
        AppText(
            "You can use fingerprint immediately after signing out. Remove a device to cancel its fingerprint access.",
            color = Muted,
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { AppText("Remove device?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(device.text("staff_name"), localize = false)
                    AppText(
                        "Fingerprint sign-in will stop on this device. It can be added again after signing in with email and password."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeStaffDevice(device.text("id"))
                        removing = null
                    }
                ) {
                    AppText("Remove device")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { AppText("Cancel") } },
        )
    }
}
