package com.ctfly.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OperationsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val tab = vm.field("opsTab", "Sessions")
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Eyebrow("CREW HQ", Aqua)
            AppText("Keep the club moving.", style = MaterialTheme.typography.headlineMedium)
            AppText("Welcome back, ${vm.profile.text("name")}", color = Muted)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(
                    vm.rows("bookings").count { it.text("status") == "Active" }.toString(),
                    "Live sessions",
                    Aqua,
                    Modifier.weight(1f),
                )
                Stat(
                    vm.rows("orders")
                        .count {
                            it.optBoolean("paid") &&
                                it.text("status") in listOf("Pending", "Preparing")
                        }
                        .toString(),
                    "In the kitchen",
                    Orange,
                    Modifier.weight(1f),
                )
            }
        }
        item {
            SurfaceCard {
                AppText(
                    "One scanner. Ready to help.",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                AppText(
                    "Scan an entry pass or café pickup QR. We’ll open the right verification step.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Button(onClick = { startStaffScan(context, vm) }, enabled = !vm.busy) {
                    AppIcon(Icons.Outlined.QrCodeScanner, null)
                    Spacer(Modifier.width(10.dp))
                    AppText("Scan QR")
                }
                TextButton(
                    onClick = {
                        vm.set("scanToken", "")
                        vm.set("dialog", "scan")
                    }
                ) {
                    AppText("Enter code manually")
                }
                OutlinedButton(onClick = { vm.set("page", "Customer history") }) {
                    AppIcon(Icons.Outlined.History, null)
                    Spacer(Modifier.width(8.dp))
                    AppText("Customer history")
                }
            }
        }
        item {
            FilterRow(listOf("Sessions", "Orders", "Entries", "Rewards"), tab) {
                vm.set("opsTab", it)
            }
        }
        when (tab) {
            "Sessions" ->
                items(
                    vm.rows("bookings").filter {
                        it.text("status") in listOf("Reserved", "Confirmed", "Active")
                    }
                ) { b ->
                    SurfaceCard {
                        AppText(
                            vm.rows("resources")
                                .find { it.text("id") == b.text("resource_id") }
                                ?.text("name") ?: "Space",
                            fontWeight = FontWeight.Bold,
                        )
                        AppText(
                            "Booking ${b.text("id").take(8)} · ${dateLabel(b.text("starts_at"))}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        Chip(b.text("status"), Aqua)
                        AppText(
                            "Until ${dateLabel(b.text("ends_at"))} · ${money(b.optInt("total"))}",
                            fontSize = 12.sp,
                        )
                        if (!b.optBoolean("paid"))
                            TextButton(
                                onClick = {
                                    vm.set("paymentKind", "Booking")
                                    vm.set("paymentId", b.text("id"))
                                    vm.set("dialog", "pay")
                                }
                            ) {
                                AppText("Collect cash payment")
                            }
                        if (
                            b.optBoolean("paid") &&
                                b.text("status") in listOf("Confirmed", "Active")
                        )
                            TextButton(
                                onClick = {
                                    vm.set("paymentKind", "Extension30")
                                    vm.set("paymentId", b.text("id"))
                                    vm.set("dialog", "extendPaid")
                                }
                            ) {
                                AppText("Add time & collect cash")
                            }
                        if (b.text("status") == "Active")
                            TextButton(
                                onClick = {
                                    vm.set("endBooking", b.text("id"))
                                    vm.set("dialog", "end")
                                }
                            ) {
                                AppText("End session early")
                            }
                        if (vm.admin && b.text("status") == "Reserved")
                            TextButton(
                                onClick = {
                                    vm.openForm(
                                        "extend_booking",
                                        obj("id" to b.text("id"), "minutes" to "30"),
                                    )
                                }
                            ) {
                                AppText("Extend reservation")
                            }
                    }
                }
            "Orders" ->
                items(
                    vm.rows("orders").filter {
                        it.text("status") !in listOf("Received", "Delivered", "Cancelled")
                    }
                ) { o ->
                    SurfaceCard {
                        AppText("Order ${o.text("id").take(8)}", fontWeight = FontWeight.Bold)
                        Chip(o.text("status"), Orange)
                        if (
                            vm.rows("test_payments").any {
                                it.text("source_id") == o.text("id") &&
                                    it.text("status") == "Succeeded"
                            }
                        )
                            Chip("Test payment", Aqua)
                        vm.rows("order_items")
                            .filter { it.text("order_id") == o.text("id") }
                            .forEach { AppText("${it.optInt("qty")} × ${it.text("name")}") }
                        AppText("Counter pickup", color = Muted)
                        if (!o.optBoolean("paid"))
                            TextButton(
                                onClick = {
                                    vm.set("paymentKind", "Order")
                                    vm.set("paymentId", o.text("id"))
                                    vm.set("dialog", "pay")
                                }
                            ) {
                                AppText("Collect ${money(o.optInt("total"))}")
                            }
                        else {
                            val next =
                                mapOf("Pending" to "Preparing", "Preparing" to "Ready")[
                                    o.text("status")]
                            if (next != null)
                                Button(
                                    onClick = {
                                        vm.action(
                                            "order_status",
                                            obj("id" to o.text("id"), "status" to next),
                                        )
                                    },
                                    enabled = !vm.busy,
                                ) {
                                    AppText("Mark $next")
                                }
                            if (o.text("status") == "Ready") {
                                AppText(
                                    "Waiting for the customer's pickup QR.",
                                    color = Lime,
                                    fontSize = 13.sp,
                                )
                                AppText(
                                    "Use Scan QR above to confirm collection.",
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            "Entries" ->
                items(vm.rows("registrations").filter { it.text("status") == "Registered" }) { r ->
                    SurfaceCard {
                        AppText(r.text("team"), fontWeight = FontWeight.Bold)
                        AppText(
                            vm.rows("events")
                                .find { it.text("id") == r.text("event_id") }
                                ?.text("name") ?: "Event",
                            color = Muted,
                        )
                        if (!r.optBoolean("paid"))
                            TextButton(
                                onClick = {
                                    vm.set("paymentKind", "Event")
                                    vm.set("paymentId", r.text("id"))
                                    vm.set("dialog", "pay")
                                }
                            ) {
                                AppText("Collect ${money(r.optInt("fee"))}")
                            }
                        else Chip("Paid", Aqua)
                    }
                }
            else ->
                items(vm.rows("redemptions").filter { it.text("status") == "Pending" }) { r ->
                    SurfaceCard {
                        AppText(r.text("name"), fontWeight = FontWeight.Bold)
                        AppText("Voucher ${r.text("id").take(8)}", color = Muted)
                        Button(
                            onClick = { vm.action("collect_reward", obj("id" to r.text("id"))) },
                            enabled = !vm.busy,
                        ) {
                            AppText("Mark collected")
                        }
                    }
                }
        }
    }
}
