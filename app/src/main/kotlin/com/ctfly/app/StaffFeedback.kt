package com.ctfly.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun FeedbackInboxScreen(vm: AppViewModel) {
    val filter = vm.field("feedbackFilter", "All")
    val records = rememberStaffRecords(vm, "Feedback", filter, vm.field("feedbackHistoryRevision"))
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StaffModuleHeader(vm, "Customer feedback", refresh = { records.reload() })
                AppText("Suggestions and issues from the app.", color = Muted)
                StaffDateFilter(vm, "FeedbackHistoryDay", "Submission date · Malaysia time")
                Input(
                    "Search customer, item or reference",
                    vm.field("FeedbackHistorySearch"),
                    { vm.set("FeedbackHistorySearch", it.take(100)) },
                )
                FilterRow(listOf("All", "New", "Reviewed", "Resolved"), filter) {
                    vm.set("feedbackFilter", it)
                }
            }
        }
        items(records.rows, key = { it.text("history_key") }) { row ->
            FeedbackCard(
                JSONObject(row.toString()).put("category", row.text("feedback_category")),
                vm,
            )
        }
        item { StaffRecordFooter(records) }
    }
}
