package com.ctfly.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EquipmentScreen(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Eyebrow("LOOK AFTER THE LOADOUT", Orange)
                AppText("Equipment care.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.openForm("ticket", obj("priority" to "Normal")) }) {
                    AppText("Report an issue")
                }
            }
        }
        item { StaffDateFilter(vm, "equipmentHistoryDay", "Issue reported date · Malaysia time") }
        items(vm.rows("tickets").onStaffDay(vm, "equipmentHistoryDay", "created_at")) { t ->
            SurfaceCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t.text("priority"), Orange)
                    Chip(t.text("status"), Aqua)
                }
                AppText(t.text("asset"), fontSize = 12.sp, color = Muted)
                AppText(t.text("title"), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                AppText(historyDateLabel(t.text("created_at")), color = Muted, fontSize = 12.sp)
                if (t.text("assigned_user").isNotEmpty())
                    AppText(
                        "Assigned: ${vm.person(t.text("assigned_user"))}",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                if (t.text("resolution").isNotEmpty()) AppText(t.text("resolution"), color = Muted)
                when (t.text("status")) {
                    "Open" ->
                        TextButton(
                            onClick = { vm.action("claim", obj("id" to t.text("id"))) },
                            enabled = !vm.busy,
                        ) {
                            AppText("I’ll take this")
                        }
                    "In progress" ->
                        if (t.text("assigned_user") == vm.userId || vm.admin)
                            TextButton(
                                onClick = { vm.openForm("resolve", obj("id" to t.text("id"))) }
                            ) {
                                AppText("Resolve with note")
                            }
                    "Resolved" ->
                        if (
                            t.text("user_id") == vm.userId ||
                                t.text("assigned_user") == vm.userId ||
                                vm.admin
                        )
                            TextButton(
                                onClick = { vm.action("reopen", obj("id" to t.text("id"))) },
                                enabled = !vm.busy,
                            ) {
                                AppText("Reopen issue")
                            }
                }
            }
        }
        if (vm.rows("tickets").onStaffDay(vm, "equipmentHistoryDay", "created_at").isEmpty())
            item { EmptyState("No records found", "Try another date, category or customer.") }
    }
}
