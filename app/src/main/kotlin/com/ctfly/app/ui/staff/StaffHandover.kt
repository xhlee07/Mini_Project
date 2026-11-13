package com.ctfly.app.ui.staff

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.domain.historyDateLabel
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.SurfaceCard

@Composable
fun HandoverScreen(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Eyebrow("KEEP EVERYONE IN THE LOOP", Aqua)
                AppText("The shift journal.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.openForm("handover", obj("category" to "General")) }) {
                    AppText("Write a handover")
                }
            }
        }
        item { StaffDateFilter(vm, "handoverHistoryDay", "Handover date · Malaysia time") }
        items(vm.rows("handovers").onStaffDay(vm, "handoverHistoryDay", "created_at")) { h ->
            SurfaceCard {
                Chip(h.text("category"), Aqua)
                AppText(h.text("title"), style = MaterialTheme.typography.titleLarge)
                AppText(h.text("note"), color = Muted)
                AppText(
                    "${vm.person(h.text("user_id"))} · ${historyDateLabel(h.text("created_at"))}",
                    fontSize = 11.sp,
                    color = Muted,
                )
                if (h.text("user_id") != vm.userId) {
                    val read =
                        vm.rows("handover_reads").any { it.text("handover_id") == h.text("id") }
                    TextButton(
                        onClick = { vm.action("ack", obj("id" to h.text("id"))) },
                        enabled = !read && !vm.busy,
                    ) {
                        AppText(if (read) "✓ Acknowledged" else "Acknowledge")
                    }
                }
            }
        }
        if (vm.rows("handovers").onStaffDay(vm, "handoverHistoryDay", "created_at").isEmpty())
            item { EmptyState("No records found", "Try another date, category or customer.") }
    }
}
