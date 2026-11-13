package com.ctfly.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun staffBackDestination(page: String): String? =
    when (page) {
        "Transactions",
        "Customer history",
        "Feedback",
        "CCTV",
        "Events" -> "Modules"
        "Modules" -> "Operations"
        else -> null
    }

/** Screen and system back share the same module hierarchy. */
@Composable
fun StaffModuleHeader(vm: AppViewModel, title: String, refresh: (() -> Unit)? = null) {
    val destination = staffBackDestination(vm.field("page")) ?: "Modules"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.set("page", destination) }) {
            AppIcon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                if (destination == "Modules") "Back to modules" else "Back to operations",
            )
        }
        AppText(
            title,
            Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (refresh != null)
            IconButton(onClick = refresh) { AppIcon(Icons.Outlined.Refresh, "Refresh history") }
    }
}
