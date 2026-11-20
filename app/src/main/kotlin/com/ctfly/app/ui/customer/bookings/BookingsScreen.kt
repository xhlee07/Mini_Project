@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.customer.bookings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.database.remote.bookingId
import com.ctfly.app.database.remote.resourceId
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.billId
import com.ctfly.app.database.remote.orderId
import com.ctfly.app.database.remote.paymentId
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.dateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.domain.payment.paymentMethodLabel
import com.ctfly.app.domain.payment.paymentReferenceLabel
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.tr
import com.ctfly.app.ui.customer.cafe.OrderPickupDetails
import com.ctfly.app.ui.notifications.PickupAlertPermission
import com.ctfly.app.ui.payment.HoldCountdown
import com.ctfly.app.ui.payment.PayButton
import java.time.Instant
import org.json.JSONObject

@Composable
fun BookingCard(vm: AppViewModel, b: JSONObject) {
    val resource = vm.rows("resources").find { it.resourceId() == b.text("resource_id") }
    SurfaceCard {
        Chip(b.text("status"), if (b.text("status") == "Active") Aqua else Lime)
        AppText(
            resource?.text("name") ?: "Reserved space",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        AppText(
            "${dateLabel(b.text("starts_at"))}\nUntil ${dateLabel(b.text("ends_at"))}",
            color = Muted,
            fontSize = 12.sp,
        )
        AppText(
            "${money(b.optInt("total"))} · ${b.optInt("guests")} player(s)",
            fontWeight = FontWeight.SemiBold,
        )
        if (b.text("status") == "Active") {
            val left =
                runCatching {
                        ((Instant.parse(b.text("ends_at")).toEpochMilli() - vm.nowMillis) / 1000)
                            .coerceAtLeast(0)
                    }
                    .getOrDefault(0)
            AppText(
                "%02d:%02d:%02d remaining".format(left / 3600, (left % 3600) / 60, left % 60),
                color = Aqua,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (b.text("status") == "Confirmed")
            Button(
                onClick = {
                    vm.set("selectedBooking", b.bookingId())
                    vm.set("dialog", "qr")
                }
            ) {
                AppText("Show entry QR")
            }
        if (
            vm.rows("test_payments").any {
                it.text("source_id") == b.bookingId() && it.text("status") == "Succeeded"
            }
        )
            Chip("Online payment", Aqua)
        if (b.text("status") == "Reserved") {
            AppText(
                if (b.text("hold_expires_at").isNotBlank())
                    "Complete payment within the checkout hold."
                else "Pay at counter · unpaid holds expire 15 min after start",
                color = Orange,
                fontSize = 11.sp,
            )
            if (b.text("hold_expires_at").isNotBlank()) HoldCountdown(vm, b.text("hold_expires_at"))
            PayButton(vm, "Booking", b.bookingId())
            if (!vm.crew)
                TextButton(
                    onClick = {
                        vm.set("cancelBooking", b.bookingId())
                        vm.set("dialog", "cancelBooking")
                    }
                ) {
                    AppText("Cancel reservation")
                }
        }
    }
}

@Composable
fun BookingsScreen(vm: AppViewModel) {
    val tab = vm.field("bookingTab", "Spaces")
    val cafeOrders = vm.confirmedCafeOrders()
    val confirmedOrderIds = cafeOrders.map { it.orderId() }.toSet()
    val payments =
        vm.rows("test_payments").filter {
            it.text("kind") != "Order" || it.text("source_id") in confirmedOrderIds
        }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Eyebrow("ALL SET FOR A GOOD TIME")
            AppText("Your plans.", style = MaterialTheme.typography.headlineLarge)
            FilterRow(listOf("Spaces", "Orders", "Receipts", "Payments"), tab) {
                vm.set("bookingTab", it)
            }
        }
        when (tab) {
            "Payments" -> {
                items(payments, key = { it.paymentId() }) { p ->
                    SurfaceCard {
                        Chip(p.text("status"))
                        AppText(
                            "${tr(p.text("kind"))} · ${money(p.optInt("total"))} · ${tr(paymentMethodLabel(p.text("method")))}",
                            localize = false,
                        )
                        AppText(
                            paymentReferenceLabel(p.text("reference")),
                            color = Muted,
                            localize = false,
                        )
                        TextButton(onClick = { vm.resumeCheckout(p) }) {
                            AppText("Payment details")
                        }
                    }
                }
                if (payments.isEmpty())
                    item { EmptyState("No payments yet", "Online payments will appear here.") }
            }
            "Spaces" -> {
                items(vm.rows("bookings"), key = { it.bookingId() }) { BookingCard(vm, it) }
                if (vm.rows("bookings").isEmpty())
                    item { EmptyState("No reservations yet", "Your next session starts in Play.") }
            }
            "Orders" -> {
                item { PickupAlertPermission(vm) }
                items(cafeOrders, key = { it.orderId() }) { o ->
                    SurfaceCard {
                        Chip(o.text("status"), Orange)
                        AppText("Order #${o.orderId().take(8)}", fontWeight = FontWeight.Bold)
                        vm.rows("order_items")
                            .filter { it.text("order_id") == o.orderId() }
                            .forEach {
                                AppText("${it.optInt("qty")} × ${it.text("name")}", color = Muted)
                            }
                        AppText(
                            "${money(o.optInt("total"))} · ${if(o.optBoolean("paid"))"Paid" else if(o.text("status")=="Cancelled")"Refunded" else "Paid"}"
                        )
                        OrderPickupDetails(vm, o)
                        if (
                            vm.rows("test_payments").any {
                                it.text("source_id") == o.orderId() &&
                                    it.text("status") == "Succeeded"
                            }
                        )
                            Chip("Online payment", Aqua)
                        TextButton(
                            onClick = {
                                vm.rows("order_items")
                                    .filter { it.text("order_id") == o.orderId() }
                                    .forEach { i ->
                                        vm.cartChange(i.text("product_id"), i.optInt("qty"))
                                    }
                                vm.set("dialog", "cart")
                            }
                        ) {
                            AppText("Order again")
                        }
                    }
                }
                if (cafeOrders.isEmpty())
                    item {
                        EmptyState("Your cravings belong here", "Explore drinks and food in Café.")
                    }
            }
            else -> {
                items(vm.rows("bills"), key = { it.billId() }) { b ->
                    SurfaceCard {
                        Chip(b.text("status", "Paid"))
                        AppText(
                            "${b.text("kind")} · ${money(b.optInt("total"))}",
                            fontWeight = FontWeight.Bold,
                        )
                        AppText(dateLabel(b.text("created_at")), color = Muted)
                        AppText(
                            "Subtotal ${money(b.optInt("subtotal"))} − discount ${money(b.optInt("discount"))}",
                            fontSize = 12.sp,
                        )
                        AppText(
                            "${tr(paymentMethodLabel(b.text("method")))} · ${tr("+{0} points").replace("{0}", b.optInt("points").toString())}",
                            color = Lime,
                            localize = false,
                        )
                        TextButton(
                            onClick = {
                                vm.set("receipt", b.toString())
                                vm.set("dialog", "receipt")
                            }
                        ) {
                            AppText("View receipt")
                        }
                    }
                }
                if (vm.rows("bills").isEmpty())
                    item { EmptyState("No receipts yet", "Completed payments will appear here.") }
            }
        }
    }
}
