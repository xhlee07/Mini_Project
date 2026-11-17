package com.ctfly.app.ui.staff.feedback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.settings.FeedbackCard
import com.ctfly.app.ui.staff.history.StaffRecordFooter
import com.ctfly.app.ui.staff.history.rememberStaffRecords
import com.ctfly.app.ui.staff.navigation.StaffModuleHeader
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
