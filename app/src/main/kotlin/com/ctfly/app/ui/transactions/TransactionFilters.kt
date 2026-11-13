package com.ctfly.app.ui.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Select

val TransactionKinds =
    listOf(
        "All" to "All types",
        "Booking" to "Bookings",
        "Order" to "Orders",
        "Event" to "Competitions",
        "Extension" to "Extensions",
    )
val TransactionStatuses =
    listOf(
            "All",
            "Succeeded",
            "Paid",
            "Pending",
            "Processing",
            "Failed",
            "Cancelled",
            "Expired",
            "Refunded",
            "Void",
        )
        .map { it to if (it == "All") "All statuses" else it }

@Composable
fun TransactionFilters(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= 560.dp && LocalDensity.current.fontScale <= 1.3f
        val type: @Composable (Modifier) -> Unit = { modifier ->
            Select(
                "Transaction type",
                vm.field("transactionKind", "All"),
                TransactionKinds,
                { vm.set("transactionKind", it) },
                modifier.testTag("transaction-type"),
            )
        }
        val status: @Composable (Modifier) -> Unit = { modifier ->
            Select(
                "Payment status",
                vm.field("transactionStatus", "All"),
                TransactionStatuses,
                { vm.set("transactionStatus", it) },
                modifier.testTag("transaction-status"),
            )
        }
        if (sideBySide)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                type(Modifier.weight(1f))
                status(Modifier.weight(1f))
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                type(Modifier.fillMaxWidth())
                status(Modifier.fillMaxWidth())
            }
    }
}
