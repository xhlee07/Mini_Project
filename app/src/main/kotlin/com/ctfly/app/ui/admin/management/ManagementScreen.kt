package com.ctfly.app.ui.admin.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.profileId
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.staffId
import com.ctfly.app.database.remote.billId
import com.ctfly.app.database.remote.categoryId
import com.ctfly.app.database.remote.eventId
import com.ctfly.app.database.remote.leaveRequestId
import com.ctfly.app.database.remote.orderId
import com.ctfly.app.database.remote.productId
import com.ctfly.app.database.remote.shiftId
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.CafeZone
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.domain.dateLabel
import com.ctfly.app.domain.historyDateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.Stat
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.staff.common.openForm
import com.ctfly.app.ui.staff.common.person
import com.ctfly.app.ui.staff.common.shareText
import com.ctfly.app.ui.staff.history.StaffRecordFooter
import com.ctfly.app.ui.staff.history.rememberStaffRecords
import java.time.LocalDate

@Composable
fun ManagementScreen(vm: AppViewModel) {
    val tab = vm.field("manageTab", "Spaces")
    val context = LocalContext.current
    val report =
        if (tab == "Reports") rememberStaffRecords(vm, "Report", dayKey = "reportHistoryDay")
        else null
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Eyebrow("MANAGER CONSOLE", Violet)
            AppText("Run a better club.", style = MaterialTheme.typography.headlineMedium)
            FilterRow(
                listOf("Spaces", "Inventory", "Events", "Staff", "Members", "Rewards", "Reports"),
                tab,
            ) {
                vm.set("manageTab", it)
            }
        }
        when (tab) {
            "Spaces" -> {
                item {
                    Button(
                        onClick = {
                            vm.openForm(
                                "save_resource",
                                obj(
                                    "kind" to "PC",
                                    "zone" to "Standard",
                                    "capacity" to "1",
                                    "status" to "Available",
                                    "rate" to "600",
                                ),
                            )
                        }
                    ) {
                        AppText("Add PC / lounge space")
                    }
                }
                items(vm.rows("resources")) { r ->
                    SurfaceCard {
                        AppText(r.text("name"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${r.text("kind")} · ${money(r.optInt("rate"))}/hr · ${r.text("status")}",
                            color = Muted,
                        )
                        TextButton(onClick = { vm.openForm("save_resource", r) }) {
                            AppText("Edit space")
                        }
                    }
                }
            }
            "Inventory" -> {
                item {
                    Row {
                        Button(
                            onClick = {
                                vm.openForm(
                                    "save_product",
                                    obj(
                                        "category_id" to
                                            (vm.rows("categories").firstOrNull()?.categoryId()
                                                ?: "1"),
                                        "threshold" to "5",
                                        "active" to "true",
                                        "image" to "iced_coffee",
                                    ),
                                )
                            }
                        ) {
                            AppText("Add product")
                        }
                        TextButton(onClick = { vm.openForm("save_category") }) {
                            AppText("Add category")
                        }
                    }
                }
                items(vm.rows("products")) { p ->
                    SurfaceCard {
                        AppText(p.text("name"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${money(p.optInt("price"))} · ${p.optInt("stock")} in stock",
                            color = Muted,
                        )
                        if (p.optInt("stock") <= p.optInt("threshold")) Chip("Low stock", Orange)
                        Row {
                            TextButton(onClick = { vm.openForm("save_product", p) }) {
                                AppText("Edit")
                            }
                            TextButton(
                                onClick = {
                                    vm.openForm(
                                        "restock",
                                        obj(
                                            "id" to p.productId(),
                                            "quantity" to "10",
                                            "reason" to "Delivery",
                                        ),
                                    )
                                }
                            ) {
                                AppText("Restock")
                            }
                        }
                    }
                }
                item { SectionTitle("Stock movements") }
                item {
                    StaffDateFilter(vm, "stockHistoryDay", "Stock movement date · Malaysia time")
                }
                items(vm.rows("stock_records").onStaffDay(vm, "stockHistoryDay", "created_at")) { s
                    ->
                    AppText(
                        "${historyDateLabel(s.text("created_at"))} · ${s.optInt("quantity")} · ${vm.rows("products").find{it.productId()==s.text("product_id")}?.text("name")} · ${s.text("reason").replace("Released test checkout hold", "Released checkout hold")}",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            "Events" -> {
                item {
                    StaffDateFilter(
                        vm,
                        "managerEventHistoryDay",
                        "Competition start date · Malaysia time",
                    )
                }
                item {
                    Button(
                        onClick = {
                            vm.openForm(
                                "save_event",
                                obj("capacity" to "8", "fee" to "1500", "status" to "Open"),
                            )
                        }
                    ) {
                        AppText("Create tournament")
                    }
                }
                items(vm.rows("events").onStaffDay(vm, "managerEventHistoryDay", "starts_at")) { e
                    ->
                    SurfaceCard {
                        AppText(e.text("name"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${dateLabel(e.text("starts_at"))} · ${e.text("status")}",
                            color = Muted,
                        )
                        Row {
                            TextButton(onClick = { vm.openForm("save_event", e) }) {
                                AppText("Edit")
                            }
                            if (e.text("status") == "Open")
                                TextButton(
                                    onClick = {
                                        vm.action("bracket", obj("event_id" to e.eventId()))
                                    },
                                    enabled = !vm.busy,
                                ) {
                                    AppText("Generate bracket")
                                }
                        }
                        vm.rows("matches")
                            .filter { it.text("event_id") == e.eventId() }
                            .forEach { m ->
                                AppText(
                                    "R${m.optInt("round")} · ${m.text("team_a","TBD")} vs ${m.text("team_b","Bye / TBD")}",
                                    fontSize = 12.sp,
                                )
                                if (m.optBoolean("done"))
                                    AppText(
                                        "Winner: ${m.text("team_winner")}",
                                        color = Lime,
                                        fontSize = 12.sp,
                                    )
                                else if (m.text("team_a_id", m.text("a")).isNotEmpty() && m.text("team_b_id", m.text("b")).isNotEmpty())
                                    Row {
                                        listOf("team_a" to "team_a_id", "team_b" to "team_b_id").forEach { (nameKey, idKey) ->
                                            val participantId = m.text(idKey, m.text(if (nameKey == "team_a") "a" else "b"))
                                            TextButton(
                                                onClick = {
                                                    vm.set("winMatch", m.text("id"))
                                                    vm.set("winTeam", participantId)
                                                    vm.set("winName", m.text(nameKey))
                                                    vm.set("dialog", "winner")
                                                }
                                            ) {
                                                AppText(
                                                    "${m.text(nameKey)} wins",
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                            }
                    }
                }
            }
            "Staff" -> {
                item {
                    Row {
                        Button(onClick = { vm.openForm("schedule") }) { AppText("Assign shift") }
                        TextButton(onClick = { vm.openForm("weekly_roster") }) {
                            AppText("Weekly roster")
                        }
                        TextButton(
                            onClick = {
                                vm.openForm(
                                    "task",
                                    obj("day" to LocalDate.now(CafeZone).toString()),
                                )
                            }
                        ) {
                            AppText("Assign mission")
                        }
                    }
                }
                item {
                    AppText(
                        "Create staff accounts through Supabase Auth, then assign their role using the setup SQL. Public signup always creates a customer.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                items(vm.rows("people").filter { it.text("role") != "customer" }) { p ->
                    SurfaceCard {
                        AppText(p.text("name"), fontWeight = FontWeight.Bold)
                        Chip(p.text("role"), Aqua)
                    }
                }
                item { SectionTitle("Roster") }
                item {
                    StaffDateFilter(
                        vm,
                        "managerStaffHistoryDay",
                        "Shift / leave date · Malaysia time",
                    )
                }
                items(vm.rows("shifts").onStaffDay(vm, "managerStaffHistoryDay", "starts_at")) { s
                    ->
                    SurfaceCard {
                        AppText(vm.person(s.staffId()), fontWeight = FontWeight.Bold)
                        AppText(
                            "${dateLabel(s.text("starts_at"))} → ${dateLabel(s.text("ends_at"))}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        Chip(s.text("status"))
                        if (s.text("status") == "Scheduled")
                            TextButton(
                                onClick = {
                                    vm.action("cancel_shift", obj("shift_id" to s.shiftId()))
                                },
                                enabled = !vm.busy,
                            ) {
                                AppText("Cancel shift")
                            }
                    }
                }
                item { SectionTitle("Leave decisions") }
                items(vm.rows("leave_requests").onStaffDay(vm, "managerStaffHistoryDay", "day")) { l
                    ->
                    SurfaceCard {
                        AppText(
                            "${vm.person(l.staffId())} · ${l.text("day")}",
                            fontWeight = FontWeight.Bold,
                        )
                        AppText(l.text("reason"), color = Muted)
                        Chip(l.text("status"))
                        if (l.text("status") == "Pending")
                            Row {
                                listOf("Approved", "Declined").forEach { status ->
                                    TextButton(
                                        onClick = {
                                            vm.action(
                                                "leave_decision",
                                                obj("leave_id" to l.leaveRequestId(), "status" to status),
                                            )
                                        },
                                        enabled = !vm.busy,
                                    ) {
                                        AppText(status)
                                    }
                                }
                            }
                    }
                }
            }
            "Members" ->
                items(vm.rows("people").filter { it.text("role") == "customer" }) { p ->
                    SurfaceCard {
                        AppText(p.text("name"), fontWeight = FontWeight.Bold)
                        AppText(p.text("phone"), color = Muted)
                        TextButton(
                            onClick = {
                                vm.openForm(
                                    "member_profile",
                                    obj(
                                        "profile_id" to p.profileId(),
                                        "name" to p.text("name"),
                                        "phone" to p.text("phone"),
                                    ),
                                )
                            }
                        ) {
                            AppText("Edit member")
                        }
                        TextButton(
                            onClick = {
                                vm.action(
                                    "member_status",
                                    obj(
                                        "profile_id" to p.profileId(),
                                        "active" to !p.optBoolean("active"),
                                    ),
                                )
                            },
                            enabled = !vm.busy,
                        ) {
                            AppText(
                                if (p.optBoolean("active")) "Deactivate account"
                                else "Reactivate account"
                            )
                        }
                    }
                }
            "Rewards" -> {
                item {
                    Button(
                        onClick = {
                            vm.openForm(
                                "save_reward",
                                obj("stock" to "10", "points" to "100", "active" to "true"),
                            )
                        }
                    ) {
                        AppText("Create reward")
                    }
                }
                items(vm.rows("rewards")) { r ->
                    SurfaceCard {
                        AppText(r.text("name"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${r.optInt("points")} points · ${r.optInt("stock")} left",
                            color = Muted,
                        )
                        TextButton(onClick = { vm.openForm("save_reward", r) }) {
                            AppText("Edit reward")
                        }
                    }
                }
            }
            else -> {
                item {
                    StaffDateFilter(
                        vm,
                        "reportHistoryDay",
                        "Payment / session / attendance date · Malaysia time",
                    )
                }
                val bills =
                    vm.rows("bills").onStaffDay(vm, "reportHistoryDay", "created_at").filter {
                        it.text("status", "Paid") == "Paid" && it.text("environment") != "test"
                    }
                val totals =
                    listOf("Booking", "Order", "Event", "Extension").map { kind ->
                        kind to
                            bills.filter { it.text("kind") == kind }.sumOf { it.optInt("total") }
                    }
                item {
                    Stat(
                        money(bills.sumOf { it.optInt("total") }),
                        if (vm.field("reportHistoryDay").isBlank()) "Paid revenue · all time"
                        else "Paid revenue · selected date",
                        Lime,
                        Modifier.fillMaxWidth(),
                    )
                }
                item {
                    SurfaceCard {
                        AppText("Revenue mix", fontWeight = FontWeight.Bold)
                        totals.forEachIndexed { i, (kind, value) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                AppText(kind, color = Muted)
                                AppText(money(value))
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (totals.sumOf { it.second } == 0) 0f
                                    else value.toFloat() / totals.sumOf { it.second }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = listOf(Lime, Orange, Violet, Aqua)[i],
                            )
                        }
                    }
                }
                item {
                    SurfaceCard {
                        AppText("Operations", fontWeight = FontWeight.Bold)
                        if (report != null) StaffRecordFooter(report)
                        AppText(
                            "${report?.rows?.firstOrNull()?.optInt("completed_sessions") ?: "—"} completed sessions"
                        )
                        AppText(
                            "${vm.rows("products").count{it.optInt("stock")<=it.optInt("threshold")}} low-stock products"
                        )
                        AppText(
                            "${report?.rows?.firstOrNull()?.optInt("worked_minutes") ?: "—"} worked minutes · ${report?.rows?.firstOrNull()?.optInt("late_minutes") ?: "—"} late minutes"
                        )
                    }
                }
                item { SectionTitle("Recent bills") }
                items(
                    vm.rows("bills").onStaffDay(vm, "reportHistoryDay", "created_at").filter {
                        it.text("environment") != "test"
                    }
                ) { b ->
                    SurfaceCard {
                        AppText(
                            "${b.text("kind")} · ${money(b.optInt("total"))}",
                            fontWeight = FontWeight.Bold,
                        )
                        Chip(b.text("status", "Paid"))
                        AppText(
                            historyDateLabel(b.text("created_at")),
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        if (
                            b.text("status", "Paid") == "Paid" &&
                                b.text("kind") != "Extension" &&
                                b.text("environment") != "test"
                        )
                            TextButton(
                                onClick = {
                                    vm.openForm("void_bill", obj("bill_id" to b.billId()))
                                }
                            ) {
                                AppText("Void unfulfilled payment")
                            }
                    }
                }
                item { SectionTitle("Best sellers") }
                items(
                    vm.rows("order_items")
                        .filter { i ->
                            vm.rows("orders").any {
                                it.orderId() == i.text("order_id") &&
                                    onStaffDay(
                                        it.text("created_at"),
                                        vm.field("reportHistoryDay"),
                                    ) &&
                                    it.optBoolean("paid") &&
                                    it.text("status") != "Cancelled" &&
                                    vm.rows("bills").none { bill ->
                                        bill.text("source_id") == it.orderId() &&
                                            bill.text("environment") == "test"
                                    }
                            }
                        }
                        .groupBy { it.text("name") }
                        .entries
                        .sortedByDescending { it.value.sumOf { it.optInt("qty") } }
                ) { entry ->
                    AppText(
                        "${entry.key} · ${entry.value.sumOf{it.optInt("qty")}} sold",
                        color = Muted,
                    )
                }
                item {
                    Button(
                        onClick = {
                            shareText(
                                context,
                                "CTFLY revenue report",
                                "Kind,Revenue (RM)\n" +
                                    totals.joinToString("\n") {
                                        "${it.first},${"%.2f".format(java.util.Locale.US,it.second/100.0)}"
                                    },
                            )
                        }
                    ) {
                        AppText("Share revenue report")
                    }
                }
            }
        }
    }
}
