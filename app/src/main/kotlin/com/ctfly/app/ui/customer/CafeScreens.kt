package com.ctfly.app.ui.customer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.dateLabel
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppIcon
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.state.tr
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Panel
import com.ctfly.app.ui.common.Photo
import org.json.JSONObject

fun AppViewModel.openCafeOrders() {
    set("page", "Bookings")
    set("bookingTab", "Orders")
}

fun AppViewModel.openPickup(order: JSONObject) {
    openCafeOrders()
    set("selectedOrder", order.text("id"))
    set("dialog", "pickupQr")
}

@Composable
fun CafeScreen(vm: AppViewModel) {
    val compactLandscape = LocalConfiguration.current.screenHeightDp < 500
    val query = vm.field("search")
    val category = vm.field("category", "All")
    val categories = listOf("All", "Favorites") + vm.rows("categories").map { it.text("name") }
    val favorites = vm.rows("favorites").map { it.text("product_id") }.toSet()
    val products =
        vm.rows("products").filter { p ->
            p.optBoolean("active", true) &&
                p.text("name").contains(query, true) &&
                (category == "All" ||
                    category == "Favorites" && p.text("id") in favorites ||
                    vm.rows("categories").any {
                        it.text("id") == p.text("category_id") && it.text("name") == category
                    })
        }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns =
                GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 190.dp else 148.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compactLandscape) {
                        Eyebrow("THE PIT STOP", Orange)
                        AppText(
                            "Play hungry? Never.",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Input("Search drinks & bites", query, { vm.set("search", it) })
                    FilterRow(categories, category) { vm.set("category", it) }
                }
            }
            items(products, key = { it.text("id") }) { p ->
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Photo(
                            p.text("image"),
                            Modifier.fillMaxWidth().height((maxWidth / 1.25f).coerceAtMost(180.dp)),
                            p.text("name"),
                        )
                        IconButton(
                            onClick = { vm.action("favorite", obj("id" to p.text("id"))) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            AppIcon(
                                if (p.text("id") in favorites) Icons.Outlined.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                "Favorite",
                                tint = Lime,
                            )
                        }
                    }
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppText(p.text("name"), fontWeight = FontWeight.Bold, minLines = 2)
                        AppText(
                            money(p.optInt("price")),
                            color = Orange,
                            fontWeight = FontWeight.Bold,
                        )
                        val qty = vm.cart()[p.text("id")] ?: 0
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (qty > 0) {
                                IconButton(onClick = { vm.cartChange(p.text("id"), -1) }) {
                                    AppIcon(Icons.Outlined.Remove, "Remove one")
                                }
                                AppText(qty.toString())
                            } else {
                                AppText(
                                    if (p.optInt("stock") == 0) "Sold out"
                                    else "${p.optInt("stock")} available",
                                    fontSize = 11.sp,
                                    color = Muted,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FilledIconButton(
                                onClick = { vm.cartChange(p.text("id"), 1) },
                                enabled = p.optInt("stock") > qty,
                            ) {
                                AppIcon(Icons.Outlined.Add, "Add ${p.text("name")}")
                            }
                        }
                    }
                }
            }
            if (products.isEmpty())
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("Nothing here yet", "Try another search or category.")
                }
        }
        CafePaymentBar(vm)
    }
}

@Composable
private fun CafePaymentBar(vm: AppViewModel) {
    val cart = vm.cart()
    val quantity = cart.values.sum()
    val total =
        cart.entries.sumOf { (id, qty) ->
            (vm.rows("products").find { it.text("id") == id }?.optInt("price") ?: 0) * qty
        }
    val activeOrders =
        vm.confirmedCafeOrders().count {
            it.text("status") in listOf("Pending", "Preparing", "Ready")
        }
    if (quantity == 0 && activeOrders == 0) return
    Surface(color = Panel, tonalElevation = 4.dp, shadowElevation = 8.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
            val summary: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText(
                        if (quantity > 0) money(total) else "Your café orders",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    AppText(
                        if (quantity > 0) "$quantity item(s) in your order"
                        else "$activeOrders order(s) in progress",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
            }
            val action: @Composable (Modifier) -> Unit = { modifier ->
                Button(
                    onClick = {
                        if (quantity > 0) vm.set("dialog", "cart") else vm.openCafeOrders()
                    },
                    enabled = !vm.busy,
                    modifier = modifier.heightIn(min = 48.dp),
                ) {
                    AppIcon(
                        if (quantity > 0) Icons.Outlined.Payments else Icons.Outlined.ReceiptLong,
                        null,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppText(if (quantity > 0) "Review & pay" else "View orders")
                }
            }
            if (stacked)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary()
                    action(Modifier.fillMaxWidth())
                }
            else
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.weight(1f)) { summary() }
                    action(Modifier)
                }
        }
    }
}

@Composable
fun OrderPickupDetails(vm: AppViewModel, order: JSONObject) {
    when (order.text("status")) {
        "Ready" -> {
            AppText("Your order is ready!", color = Lime, fontWeight = FontWeight.Bold)
            AppText(
                "Please come to the café counter. Show your pickup QR to staff to collect your order.",
                fontSize = 13.sp,
                color = Muted,
            )
            Button(
                onClick = { vm.openPickup(order) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                AppIcon(Icons.Outlined.QrCode2, null)
                Spacer(Modifier.width(8.dp))
                AppText("Show pickup QR")
            }
        }
        "Received",
        "Delivered" -> {
            AppText("Collected. Enjoy your food!", color = Aqua)
            if (order.text("received_at").isNotBlank())
                AppText(dateLabel(order.text("received_at")), color = Muted, fontSize = 12.sp)
        }
        "Preparing" ->
            AppText(
                "We’re preparing your order. We’ll let you know when it’s ready.",
                color = Muted,
                fontSize = 13.sp,
            )
    }
}

@Composable
fun PickupQrContent(vm: AppViewModel) {
    val order = vm.rows("orders").find { it.text("id") == vm.field("selectedOrder") }
    if (order == null || !order.optBoolean("paid") || order.text("status") != "Ready") {
        EmptyState(
            if (order?.text("status") in listOf("Received", "Delivered")) "Order collected"
            else "Pickup QR is not available yet",
            "Your pickup QR appears when your paid order is ready.",
        )
        return
    }
    AppText("Order #${order.text("id").take(8)}", fontWeight = FontWeight.Bold)
    AppText("Your order is ready!", color = Lime)
    val token = order.text("pickup_token")
    if (token.isBlank()) {
        AppText("Pickup is being updated. Please ask staff for help.", color = Orange)
        return
    }
    val bitmap =
        remember(token) {
            val qr = QRCodeWriter().encode("ctfly-pickup:$token", BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                .apply {
                    setPixels(
                        IntArray(512 * 512) { i ->
                            if (qr[i % 512, i / 512]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        },
                        0,
                        512,
                        0,
                        0,
                        512,
                        512,
                    )
                }
                .asImageBitmap()
        }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val qrSize = (LocalConfiguration.current.screenHeightDp - 230).coerceIn(128, 300).dp
        Image(
            bitmap,
            tr("Order pickup QR code"),
            Modifier.widthIn(max = qrSize).fillMaxWidth().aspectRatio(1f),
        )
    }
    AppText(
        "Show this QR to staff at the café counter. Staff will scan it before handing over your order. This code can only be used once.",
        color = Muted,
        fontSize = 13.sp,
    )
    vm.rows("order_items")
        .filter { it.text("order_id") == order.text("id") }
        .forEach { AppText("${it.optInt("qty")} × ${it.text("name")}") }
}
