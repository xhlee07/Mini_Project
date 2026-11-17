package com.ctfly.app.ui.staff.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.domain.historyDateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.staff.navigation.StaffModuleHeader

@Composable
fun StaffHistoryScreen(vm: AppViewModel) {
    val category = vm.field("activityCategory", "All")
    val records = rememberStaffRecords(vm, "Activity", category)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 340.dp else 310.dp),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StaffModuleHeader(vm, "Customer history", refresh = { records.reload() })
                AppText(
                    "Bookings, café orders, competition entries and reward redemptions.",
                    color = Muted,
                )
                FilterRow(
                    listOf("All", "Bookings", "Orders", "Competitions", "Rewards"),
                    category,
                ) {
                    vm.set("activityCategory", it)
                }
                StaffDateFilter(
                    vm,
                    "ActivityHistoryDay",
                    when (category) {
                        "Bookings" -> "Session date · Malaysia time"
                        "Orders" -> "Order date · Malaysia time"
                        "Competitions" -> "Competition start date · Malaysia time"
                        "Rewards" -> "Redemption date · Malaysia time"
                        else -> "Activity date · Malaysia time"
                    },
                )
                Input(
                    "Search customer, item or reference",
                    vm.field("ActivityHistorySearch"),
                    { vm.set("ActivityHistorySearch", it.take(100)) },
                )
                if (category == "All")
                    AppText(
                        "Bookings and competitions use their scheduled date.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
            }
        }
        items(records.rows, key = { it.text("history_key") }) { r ->
            SurfaceCard {
                Chip(
                    r.text("category"),
                    when (r.text("category")) {
                        "Bookings" -> Aqua
                        "Orders" -> Orange
                        "Competitions" -> Violet
                        else -> Lime
                    },
                )
                AppText(r.text("customer_name"), fontWeight = FontWeight.Bold, localize = false)
                AppText(r.text("title"), style = MaterialTheme.typography.titleLarge)
                AppText(r.text("detail"), color = Muted)
                AppText(
                    "#${r.text("id").take(8)}",
                    fontSize = 12.sp,
                    color = Muted,
                    localize = false,
                )
                Chip(r.text("status"))
                if (r.text("starts_at").isNotBlank()) {
                    AppText(historyDateLabel(r.text("starts_at")), fontWeight = FontWeight.SemiBold)
                    AppText(
                        "Until ${historyDateLabel(r.text("ends_at"))}",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    if (r.has("minutes"))
                        AppText(
                            "${r.optInt("minutes")} min · ${r.optInt("guests")} guest(s)",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                } else
                    AppText(historyDateLabel(r.text("created_at")), color = Muted, fontSize = 13.sp)
                if (r.text("category") == "Rewards")
                    AppText("${r.optInt("points")} points used", color = Lime)
                else {
                    AppText(money(r.optInt("total")), fontWeight = FontWeight.Bold)
                    AppText(
                        if (r.optBoolean("paid")) "Paid" else "Unpaid",
                        color = if (r.optBoolean("paid")) Aqua else Orange,
                    )
                }
                if (r.text("received_at").isNotBlank())
                    AppText(
                        "Collected · ${historyDateLabel(r.text("received_at"))}",
                        color = Aqua,
                        fontSize = 12.sp,
                    )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { StaffRecordFooter(records) }
    }
}

@Composable
fun StaffRecordFooter(records: StaffRecordState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (records.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (records.error.isNotBlank()) {
            AppText(records.error, color = Orange)
            OutlinedButton(onClick = { records.reload() }) { AppText("Retry") }
        } else if (!records.loading && records.rows.isEmpty())
            EmptyState("No records found", "Try another date, category or customer.")
        if (records.hasMore)
            OutlinedButton(
                onClick = { records.loadMore() },
                enabled = !records.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                AppText("Load more records")
            }
    }
}
