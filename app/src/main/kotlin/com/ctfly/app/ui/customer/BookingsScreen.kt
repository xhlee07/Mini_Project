@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.dateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.notifications.PickupAlertPermission
import com.ctfly.app.ui.payment.HoldCountdown
import com.ctfly.app.ui.payment.TestModeNotice
import com.ctfly.app.ui.payment.TestPayButton
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun BookingCard(vm: AppViewModel, b: JSONObject) {
    val resource = vm.rows("resources").find { it.text("id") == b.text("resource_id") }
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
                    vm.set("selectedBooking", b.text("id"))
                    vm.set("dialog", "qr")
                }
            ) {
                AppText("Show entry QR")
            }
        if (
            vm.rows("test_payments").any {
                it.text("source_id") == b.text("id") && it.text("status") == "Succeeded"
            }
        )
            Chip("Test payment", Aqua)
        if (b.text("status") == "Reserved") {
            AppText(
                if (b.text("hold_expires_at").isNotBlank())
                    "Complete payment within the checkout hold."
                else "Pay at counter · unpaid holds expire 15 min after start",
                color = Orange,
                fontSize = 11.sp,
            )
            if (b.text("hold_expires_at").isNotBlank()) HoldCountdown(vm, b.text("hold_expires_at"))
            TestPayButton(vm, "Booking", b.text("id"))
            if (!vm.crew)
                TextButton(
                    onClick = {
                        vm.set("cancelBooking", b.text("id"))
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
    val confirmedOrderIds = cafeOrders.map { it.text("id") }.toSet()
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
                item { TestModeNotice() }
                items(payments, key = { it.text("id") }) { p ->
                    SurfaceCard {
                        Chip(p.text("status"))
                        AppText(
                            "${p.text("kind")} · ${money(p.optInt("total"))} · ${p.text("method")}"
                        )
                        AppText(p.text("reference"), color = Muted, localize = false)
                        TextButton(onClick = { vm.resumeCheckout(p) }) {
                            AppText("Payment details")
                        }
                    }
                }
                if (payments.isEmpty())
                    item {
                        EmptyState("No test transactions yet", "Test payments will appear here.")
                    }
            }
            "Spaces" -> {
                items(vm.rows("bookings"), key = { it.text("id") }) { BookingCard(vm, it) }
                if (vm.rows("bookings").isEmpty())
                    item { EmptyState("No reservations yet", "Your next session starts in Play.") }
            }
            "Orders" -> {
                item { PickupAlertPermission(vm) }
                items(cafeOrders, key = { it.text("id") }) { o ->
                    SurfaceCard {
                        Chip(o.text("status"), Orange)
                        AppText("Order #${o.text("id").take(8)}", fontWeight = FontWeight.Bold)
                        vm.rows("order_items")
                            .filter { it.text("order_id") == o.text("id") }
                            .forEach {
                                AppText("${it.optInt("qty")} × ${it.text("name")}", color = Muted)
                            }
                        AppText(
                            "${money(o.optInt("total"))} · ${if(o.optBoolean("paid"))"Paid" else if(o.text("status")=="Cancelled")"Refunded" else "Paid"}"
                        )
                        OrderPickupDetails(vm, o)
                        if (
                            vm.rows("test_payments").any {
                                it.text("source_id") == o.text("id") &&
                                    it.text("status") == "Succeeded"
                            }
                        )
                            Chip("Test payment", Aqua)
                        TextButton(
                            onClick = {
                                vm.rows("order_items")
                                    .filter { it.text("order_id") == o.text("id") }
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
                items(vm.rows("bills"), key = { it.text("id") }) { b ->
                    SurfaceCard {
                        Chip(b.text("status", "Paid"))
                        if (b.text("environment") == "test")
                            AppText("TEST MODE · NO MONEY CHARGED", color = Lime)
                        AppText(
                            "${b.text("kind")} · ${money(b.optInt("total"))}",
                            fontWeight = FontWeight.Bold,
                        )
                        AppText(dateLabel(b.text("created_at")), color = Muted)
                        AppText(
                            "Subtotal ${money(b.optInt("subtotal"))} − discount ${money(b.optInt("discount"))}",
                            fontSize = 12.sp,
                        )
                        AppText("${b.text("method")} · +${b.optInt("points")} points", color = Lime)
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
