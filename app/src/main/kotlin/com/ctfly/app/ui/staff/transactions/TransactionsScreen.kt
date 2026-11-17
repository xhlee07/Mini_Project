package com.ctfly.app.ui.staff.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.StaffDateFilter
import com.ctfly.app.domain.historyDateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.domain.payment.paymentMethodLabel
import com.ctfly.app.domain.payment.paymentReferenceLabel
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.staff.history.StaffRecordFooter
import com.ctfly.app.ui.staff.history.rememberStaffRecords
import com.ctfly.app.ui.staff.navigation.StaffModuleHeader
import com.ctfly.app.ui.transactions.TransactionFilters
import org.json.JSONObject

@Composable
fun TransactionsScreen(vm: AppViewModel) {
    val category = vm.field("transactionStatus", "All")
    val records =
        rememberStaffRecords(
            vm,
            "Transactions",
            category,
            kind = vm.field("transactionKind", "All"),
        )
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StaffModuleHeader(vm, "Transactions", refresh = { records.reload() })
                AppText("Payment records, references and receipts.", color = Muted)
                StaffDateFilter(
                    vm,
                    "TransactionsHistoryDay",
                    "Payment creation date · Malaysia time",
                )
                Input(
                    "Search customer, item or reference",
                    vm.field("TransactionsHistorySearch"),
                    { vm.set("TransactionsHistorySearch", it.take(100)) },
                )
                TransactionFilters(vm)
            }
        }
        items(records.rows, key = { it.text("history_key") }) { r ->
            SurfaceCard {
                Chip(
                    r.text("status"),
                    if (r.text("status") in listOf("Succeeded", "Paid")) Aqua else Orange,
                )
                AppText(r.text("customer_name"), fontWeight = FontWeight.Bold, localize = false)
                AppText(
                    "${r.text("title")} · ${money(r.optInt("total"))}",
                    style = MaterialTheme.typography.titleLarge,
                )
                AppText(paymentMethodLabel(r.text("method")), color = Muted)
                AppText(paymentReferenceLabel(r.text("reference")), color = Muted, localize = false)
                AppText(historyDateLabel(r.text("created_at")), color = Muted)
                if (r.text("environment") == "test") {
                    Chip("Online payment", Violet)
                    TextButton(
                        onClick = {
                            val payment = JSONObject(r.getJSONObject("payment").toString())
                            r.optJSONObject("receipt")?.let { payment.put("receipt", it) }
                            vm.resumeCheckout(payment)
                        }
                    ) {
                        AppText("Payment details")
                    }
                } else
                    r.optJSONObject("receipt")?.let { receipt ->
                        TextButton(
                            onClick = {
                                vm.set("receipt", receipt.toString())
                                vm.set("dialog", "receipt")
                            }
                        ) {
                            AppText("View receipt")
                        }
                    }
            }
        }
        item { StaffRecordFooter(records) }
    }
}
