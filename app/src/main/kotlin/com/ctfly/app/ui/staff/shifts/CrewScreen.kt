package com.ctfly.app.ui.staff.shifts

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
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.CafeZone
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.domain.dateLabel
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.staff.common.openForm
import com.ctfly.app.ui.staff.common.person
import com.ctfly.app.ui.staff.history.rememberStaffRecords
import java.time.LocalDate

@Composable
fun CrewScreen(vm: AppViewModel) {
    val today = LocalDate.now(CafeZone).toString()
    val selectedDay = vm.field("shiftHistoryDay").ifBlank { today }
    val historicalChecklist = selectedDay != today
    val checklist =
        if (historicalChecklist) rememberStaffRecords(vm, "Checklist", dayKey = "shiftHistoryDay")
        else null
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Eyebrow("YOUR DAY, SORTED", Aqua)
            AppText("My shifts & missions.", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            SectionTitle("On the roster", action = "Request leave") {
                vm.openForm("leave", obj("day" to today))
            }
        }
        item {
            StaffDateFilter(vm, "shiftHistoryDay", "Shift / mission / leave date · Malaysia time")
        }
        items(
            vm.rows("shifts")
                .filter { it.text("user_id") == vm.userId && it.text("status") == "Scheduled" }
                .onStaffDay(vm, "shiftHistoryDay", "starts_at")
                .sortedBy { it.text("starts_at") }
        ) { s ->
            SurfaceCard {
                AppText(dateLabel(s.text("starts_at")), fontWeight = FontWeight.Bold)
                AppText("Until ${dateLabel(s.text("ends_at"))}", color = Muted)
                val a = vm.rows("attendance").find { it.text("shift_id") == s.text("id") }
                if (a != null)
                    AppText(
                        "Late ${a.optInt("late_minutes")} min · Worked ${a.optInt("minutes")} min",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                Button(
                    onClick = { vm.action("clock", obj("id" to s.text("id"))) },
                    enabled = !vm.busy && (a == null || a.isNull("clock_out")),
                ) {
                    AppText(
                        if (a == null) "Clock in"
                        else if (a.isNull("clock_out")) "Clock out" else "Shift complete"
                    )
                }
            }
        }
        if (vm.rows("shifts").none { it.text("user_id") == vm.userId })
            item { EmptyState("No shifts assigned", "Your manager will add your schedule here.") }
        item { SectionTitle("Daily readiness", selectedDay) }
        if (historicalChecklist && checklist != null)
            item {
                if (checklist.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (checklist.error.isNotBlank()) {
                    AppText(checklist.error, color = Orange)
                    TextButton(onClick = { checklist.reload() }) { AppText("Retry") }
                }
                AppText("Past readiness checks are read-only.", color = Muted, fontSize = 12.sp)
            }
        item {
            SurfaceCard {
                listOf(
                        "Sanitize desks",
                        "Test headsets",
                        "Check network",
                        "Count cues & balls",
                        "Stock drinks",
                        "Closing safety check",
                    )
                    .forEach { item ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                (checklist?.rows ?: vm.rows("checklist")).any {
                                    it.text("item") == item
                                },
                                onCheckedChange = { vm.action("checklist", obj("item" to item)) },
                                enabled = !vm.busy && !historicalChecklist,
                            )
                            AppText(item, fontSize = 13.sp)
                        }
                    }
            }
        }
        item {
            SectionTitle("Missions", action = "Add mission") {
                vm.openForm("task", obj("day" to today, "user_id" to vm.userId))
            }
        }
        items(vm.rows("tasks").onStaffDay(vm, "shiftHistoryDay", "day")) { t ->
            SurfaceCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        t.optBoolean("done"),
                        onCheckedChange = { vm.action("task_done", obj("id" to t.text("id"))) },
                        enabled = !vm.busy,
                    )
                    Column {
                        AppText(t.text("title"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${t.text("day")} · ${vm.person(t.text("user_id"))}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        item { SectionTitle("Leave requests") }
        items(
            vm.rows("leave_requests")
                .filter { it.text("user_id") == vm.userId }
                .onStaffDay(vm, "shiftHistoryDay", "day")
        ) { l ->
            SurfaceCard {
                AppText(l.text("day"), fontWeight = FontWeight.Bold)
                AppText(l.text("reason"), color = Muted)
                Chip(l.text("status"), Aqua)
            }
        }
    }
}
