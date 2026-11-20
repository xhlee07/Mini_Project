package com.ctfly.app.ui.payment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.billId
import com.ctfly.app.database.remote.paymentId
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.money
import com.ctfly.app.domain.payment.paymentMethodLabel
import com.ctfly.app.domain.payment.paymentReferenceLabel
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.Ink
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.tr
import java.time.Instant

@Composable
private fun PaymentNotice() {
    SurfaceCard { AppText("No money will be charged.", color = Muted) }
}

@Composable
fun PayButton(vm: AppViewModel, kind: String, id: String) {
    if (vm.onlineCheckoutAvailable && !vm.crew)
        Button(onClick = { vm.openCheckout(kind, id) }, enabled = !vm.busy && !vm.checkoutLeaving) {
            AppText("Pay now")
        }
}

@Composable
fun HoldCountdown(vm: AppViewModel, deadline: String) {
    val seconds =
        runCatching { (Instant.parse(deadline).toEpochMilli() - vm.nowMillis) / 1000 }
            .getOrDefault(0)
            .coerceAtLeast(0)
    AppText(
        tr("Checkout hold: %s").format("%02d:%02d".format(seconds / 60, seconds % 60)),
        color = if (seconds == 0L) Orange else Muted,
        localize = false,
    )
}

@Composable
fun CheckoutScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pay = vm.currentCheckoutPayment()
    val status = pay.text("status")
    val q = if (pay.paymentId().isBlank()) vm.checkoutQuote else pay
    val pending = status in listOf("Pending", "Processing")
    val canAct = !vm.busy && !vm.checkoutLeaving
    val feedback = vm.validationError.ifBlank { vm.message }
    val expired =
        q.text("expires_at").isNotBlank() &&
            runCatching { Instant.parse(q.text("expires_at")).toEpochMilli() <= vm.nowMillis }
                .getOrDefault(true)
    LaunchedEffect(expired) { if (expired) vm.refresh(silent = true) }
    LaunchedEffect(vm.field("checkoutSource")) {
        if (pay.paymentId().isBlank() && vm.checkoutQuote.length() == 0) vm.reloadCheckout()
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !vm.checkoutLeaving) {
                AppIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            AppText("Checkout", style = MaterialTheme.typography.titleLarge)
        }
        if (vm.checkoutLeaving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime)
        if (feedback.isNotBlank()) {
            Surface(color = Orange.copy(alpha = .12f)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(feedback, Modifier.weight(1f), color = Orange)
                    IconButton(
                        onClick = {
                            vm.clearValidationError()
                            vm.dismissMessage()
                        }
                    ) {
                        AppIcon(Icons.Outlined.Close, "Close")
                    }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f)
                .widthIn(max = 680.dp)
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .testTag("checkout-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.isBlank() || pending) item { PaymentNotice() }
            if (!vm.crew && vm.field("checkoutKind") == "Order" && (status.isBlank() || pending))
                item {
                    AppText("Your order appears in Orders after payment succeeds.", color = Muted)
                }
            if (vm.crew && pay.text("customer_name").isNotBlank())
                item { AppText(pay.text("customer_name"), fontWeight = FontWeight.Bold) }
            if (q.length() > 0)
                item {
                    SurfaceCard {
                        Chip(
                            if (status.isBlank()) "Awaiting payment" else status,
                            if (status == "Succeeded") Lime else Orange,
                        )
                        q.rows("items").forEach {
                            AppText("${it.optInt("qty")} × ${it.text("name")}")
                        }
                        AmountLine("Subtotal", q.optInt("subtotal"))
                        AmountLine(
                            if (q.text("promo_code").isBlank()) "Member discount"
                            else "Promo discount",
                            -q.optInt("discount"),
                        )
                        HorizontalDivider()
                        AmountLine("Total", q.optInt("total"))
                        AppText(
                            tr("Checkout points: %s").format(q.optInt("points").toString()),
                            color = Lime,
                            localize = false,
                        )
                        if (status.isBlank() || pending) HoldCountdown(vm, q.text("expires_at"))
                        if (pay.text("reference").isNotBlank())
                            AppText(
                                paymentReferenceLabel(pay.text("reference")),
                                color = Muted,
                                localize = false,
                            )
                    }
                }
            if (status.isBlank()) {
                if (!vm.crew)
                    item {
                        SurfaceCard {
                            Input(
                                "Promo code (optional)",
                                vm.field("checkoutPromoCode"),
                                { vm.set("checkoutPromoCode", it.uppercase()) },
                            )
                            OutlinedButton(
                                onClick = {
                                    vm.set(
                                        "checkoutAppliedCode",
                                        vm.field("checkoutPromoCode").trim(),
                                    )
                                    vm.reloadCheckout()
                                },
                                enabled = !vm.busy && !vm.checkoutLeaving,
                            ) {
                                AppText("Apply / remove code")
                            }
                            if (q.text("promo_code").isNotBlank())
                                AppText(
                                    tr("Applied: {0}").replace("{0}", q.text("promo_code")),
                                    color = Lime,
                                    localize = false,
                                )
                        }
                    }

                if (vm.checkoutError.isNotBlank())
                    item {
                        SurfaceCard {
                            AppText(vm.checkoutError, color = Orange)
                            Button(onClick = { vm.reloadCheckout() }) { AppText("Reload checkout") }
                        }
                    }
                else if (q.length() == 0)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime) }
                else
                    item {
                        SurfaceCard {
                            AppText("Payment method", fontWeight = FontWeight.Bold)
                            listOf("TNG", "FPX", "Card").forEach { key ->
                                OutlinedButton(
                                    onClick = { vm.set("checkoutMethod", key) },
                                    enabled = !vm.busy && !vm.checkoutLeaving,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .testTag("method-$key"),
                                ) {
                                    if (vm.field("checkoutMethod", "TNG") == key)
                                        AppIcon(Icons.Outlined.CheckCircle, null)
                                    AppText(paymentMethodLabel(key), Modifier.padding(start = 8.dp))
                                }
                            }
                            Button(
                                onClick = { vm.startCheckoutPayment() },
                                enabled = canAct && !expired,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .testTag("start-test-payment"),
                            ) {
                                AppText("Review payment")
                            }
                            if (expired) AppText("Checkout hold expired", color = Orange)
                        }
                    }
            } else if (pending && !vm.crew)
                item {
                    SurfaceCard {
                        AppText("Review payment", fontWeight = FontWeight.Bold)
                        AppText(paymentMethodLabel(pay.text("method")), color = Muted)
                        if (expired) AppText("Checkout hold expired", color = Orange)
                        Button(
                            onClick = { vm.confirmCheckoutPayment() },
                            enabled = canAct && !expired,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .testTag("confirm-test-payment"),
                        ) {
                            AppText(
                                tr("Confirm payment · %s").format(money(pay.optInt("total"))),
                                localize = false,
                            )
                        }
                        TextButton(
                            onClick = { vm.cancelCheckoutPayment() },
                            enabled = canAct && (!expired || vm.field("checkoutKind") == "Order"),
                        ) {
                            AppText("Cancel payment")
                        }
                    }
                }
            else if (status == "Succeeded")
                item {
                    SurfaceCard {
                        AppIcon(Icons.Outlined.CheckCircle, null, tint = Lime)
                        AppText("Payment confirmed", style = MaterialTheme.typography.titleLarge)
                        AppText("No money was charged.", color = Muted)
                        val receipt =
                            pay.optJSONObject("receipt")
                                ?: vm.rows("bills").find { it.billId() == pay.text("bill_id") }
                        if (receipt != null)
                            Button(
                                onClick = {
                                    vm.set("receipt", receipt.toString())
                                    vm.set("dialog", "receipt")
                                }
                            ) {
                                AppText("View receipt")
                            }
                        if (vm.canRefundCheckoutPayment(pay))
                            OutlinedButton(
                                onClick = { vm.set("dialog", "checkoutRefund") },
                                enabled = !vm.busy && !vm.checkoutLeaving,
                            ) {
                                AppText("Request refund")
                            }
                    }
                }
            else if (!pending)
                item {
                    SurfaceCard {
                        AppText(
                            when (status) {
                                "Refunded" -> "Refund completed"
                                "Failed" -> "Payment unsuccessful"
                                "Expired" -> "Checkout hold expired"
                                "Cancelled" -> "Payment cancelled"
                                else -> status
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        AppText("No money was charged.", color = Muted)
                        if (status in listOf("Failed", "Cancelled") && !vm.crew)
                            Button(
                                onClick = {
                                    vm.openCheckout(pay.text("kind"), pay.text("source_id"))
                                },
                                enabled = !vm.busy && !vm.checkoutLeaving,
                            ) {
                                AppText("Try payment again")
                            }
                    }
                }
            item {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime)
                TextButton(onClick = onBack, enabled = !vm.checkoutLeaving) {
                    AppText("Back to your plans")
                }
            }
        }
    }
}

@Composable
private fun AmountLine(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AppText(label, color = Muted)
        AppText(money(value), fontWeight = FontWeight.Bold)
    }
}
