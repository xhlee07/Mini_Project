@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.dialog

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.CafeZone
import com.ctfly.app.domain.VenueDirectory
import com.ctfly.app.domain.bookingInputError
import com.ctfly.app.domain.bookingPrice
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.DateFields
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Select
import com.ctfly.app.ui.customer.PickupQrContent
import com.ctfly.app.ui.payment.ReceiptContent
import com.ctfly.app.ui.staff.StaffPickupContent
import com.ctfly.app.ui.staff.StaffQrKind
import com.ctfly.app.ui.staff.confirmStaffCode
import com.ctfly.app.ui.staff.staffQrKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import org.json.JSONObject

@Composable
fun AppDialogs(vm: AppViewModel) {
    val dialog = vm.field("dialog")
    val context = LocalContext.current
    when (dialog) {
        "book" ->
            DialogFrame("Make it your time.", vm) {
                val r = vm.rows("resources").find { it.text("id") == vm.field("selectedResource") }
                if (r != null) {
                    AppText(r.text("name"), fontWeight = FontWeight.Bold)
                    AppText(r.text("description"), color = Muted)
                    val initial = run {
                        java.time.Instant.ofEpochMilli(vm.nowMillis)
                            .atZone(CafeZone)
                            .withSecond(0)
                            .withNano(0)
                    }
                    val date = vm.field("bookDate", initial.toLocalDate().toString())
                    val time = vm.field("bookTime", initial.toLocalTime().toString())
                    val durations = VenueDirectory.durations(r.text("kind"))
                    val defaultMinutes = 30
                    val mins =
                        vm.field("bookMinutes", defaultMinutes.toString())
                            .toIntOrNull()
                            .let { if (it in durations) it!! else defaultMinutes }
                            .toString()
                    val guests =
                        vm.field("bookGuests", vm.field("roomPlayers", "1"))
                            .toIntOrNull()
                            ?.coerceIn(1, r.optInt("capacity")) ?: 1
                    DateFields("Reservation date · Malaysia time", date, { vm.set("bookDate", it) })
                    TimeFields("Start time", time, { vm.set("bookTime", it) })
                    Select(
                        "Duration",
                        mins,
                        durations.map { it.toString() to "$it minutes" },
                        { vm.set("bookMinutes", it) },
                    )
                    Select(
                        "Players",
                        guests.toString(),
                        (1..r.optInt("capacity")).map { it.toString() to "$it player(s)" },
                        { vm.set("bookGuests", it) },
                    )
                    AppText(
                        money(bookingPrice(r.optInt("rate"), mins.toInt())),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Lime,
                    )
                    if (r.text("kind") == "VIPRoom")
                        AppText(
                            "Whole private room · ${r.optInt("capacity")} gaming PCs · $guests guest(s). The room price stays the same for fewer guests.",
                            color = Aqua,
                            fontSize = 12.sp,
                        )
                    AppText(
                        "Complete test checkout within 10 minutes. No real money will be charged. Arriving late does not extend your end time.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = {
                            val error =
                                bookingInputError(
                                    date,
                                    time,
                                    mins.toInt(),
                                    guests,
                                    r.optInt("capacity"),
                                    vm.nowMillis,
                                )
                            if (error != null) {
                                vm.invalid(error)
                                return@Button
                            }
                            val start =
                                LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
                                    .atZone(CafeZone)
                                    .toInstant()
                                    .toString()
                            vm.action(
                                "book",
                                obj(
                                    "resource_id" to r.optLong("id"),
                                    "starts_at" to start,
                                    "minutes" to mins.toInt(),
                                    "guests" to guests,
                                ),
                                onResult = {
                                    if (vm.testPaymentsEnabled && it.text("id").isNotBlank())
                                        vm.openCheckout("Booking", it.text("id"))
                                },
                            ) {
                                vm.set("dialog", "")
                                vm.set("page", "Bookings")
                                vm.set("bookingTab", "Spaces")
                            }
                        },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppText("Confirm reservation")
                    }
                }
            }
        "cart" ->
            DialogFrame("Your pit stop order", vm) {
                val cart = vm.cart()
                var total = 0
                cart.forEach { (id, qty) ->
                    val p = vm.rows("products").find { it.text("id") == id }
                    if (p != null) {
                        total += p.optInt("price") * qty
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                AppText(p.text("name"), fontWeight = FontWeight.Bold)
                                AppText(money(p.optInt("price") * qty), color = Orange)
                            }
                            TextButton(onClick = { vm.cartChange(id, -1) }, enabled = !vm.busy) {
                                AppText("−")
                            }
                            AppText(qty.toString())
                            TextButton(onClick = { vm.cartChange(id, 1) }, enabled = !vm.busy) {
                                AppText("+")
                            }
                        }
                    }
                }
                if (cart.isEmpty())
                    EmptyState("Your bag is empty", "Add something tasty from Café.")
                else {
                    AppText(
                        "Counter pickup · Collect with your order QR when ready",
                        color = Aqua,
                        fontSize = 13.sp,
                    )
                    AppText("Total ${money(total)}", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    AppText(
                        "Complete test checkout within 10 minutes. Unpaid orders will not be prepared.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = { vm.checkout() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppText("Continue to payment")
                    }
                }
            }
        "event" ->
            DialogFrame("Enter the tournament", vm) {
                val e = vm.rows("events").find { it.text("id") == vm.field("selectedEvent") }
                AppText(e?.text("name") ?: "Tournament", fontWeight = FontWeight.Bold)
                Input("Team / player name", vm.field("teamName"), { vm.set("teamName", it) })
                AppText(
                    "Entry fee payable at the counter. Registration closes when the bracket is generated.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = {
                        vm.action(
                            "register_event",
                            obj(
                                "event_id" to vm.field("selectedEvent"),
                                "team" to vm.field("teamName"),
                            ),
                            onResult = {
                                if (vm.testPaymentsEnabled && it.text("id").isNotBlank())
                                    vm.openCheckout("Event", it.text("id"))
                            },
                        ) {
                            vm.set("dialog", "")
                        }
                    },
                    enabled = !vm.busy && vm.field("teamName").isNotBlank(),
                ) {
                    AppText("Register team")
                }
            }
        "pickupQr" -> DialogFrame("Collect your order", vm) { PickupQrContent(vm) }
        "pickupScan" -> DialogFrame("Verify café pickup", vm) { StaffPickupContent(vm) }
        "qr" ->
            DialogFrame("Your entry pass", vm) {
                val b = vm.rows("bookings").find { it.text("id") == vm.field("selectedBooking") }
                if (b != null && b.optBoolean("paid") && b.text("status") == "Confirmed") {
                    val token = "ctfly:${b.text("token")}"
                    val bitmap =
                        remember(token) {
                            val matrix =
                                QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 640, 640)
                            Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                                .apply {
                                    setPixels(
                                        IntArray(640 * 640) { i ->
                                            if (matrix[i % 640, i / 640])
                                                android.graphics.Color.BLACK
                                            else android.graphics.Color.WHITE
                                        },
                                        0,
                                        640,
                                        0,
                                        0,
                                        640,
                                        640,
                                    )
                                }
                                .asImageBitmap()
                        }
                    Image(bitmap, "Entry QR code", Modifier.fillMaxWidth().aspectRatio(1f))
                    AppText(
                        "Show this to the crew when your session begins. This code can only be checked in once.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        AppText(token, fontSize = 11.sp)
                    }
                } else
                    AppText(
                        "This pass is no longer available. Refresh your bookings.",
                        color = Orange,
                    )
            }
        "scan" ->
            DialogFrame("Verify QR code", vm) {
                Input("Entry or pickup code", vm.field("scanToken"), { vm.set("scanToken", it) })
                AppText(
                    "Entry passes check booking time and payment. Pickup codes check payment, readiness and prior collection.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Button(onClick = { vm.confirmStaffCode() }, enabled = !vm.busy) {
                    AppText(
                        if (staffQrKind(vm.field("scanToken")) == StaffQrKind.ENTRY)
                            "Verify & check in"
                        else "Verify code"
                    )
                }
            }
        "profile" ->
            DialogFrame("Make it yours", vm) {
                Input(
                    "Name",
                    vm.field("profileName", vm.profile.text("name")),
                    { vm.set("profileName", it) },
                )
                Input(
                    "Phone",
                    vm.field("profilePhone", vm.profile.text("phone")),
                    { vm.set("profilePhone", it) },
                )
                Button(
                    onClick = {
                        vm.action(
                            "profile",
                            obj(
                                "name" to vm.field("profileName", vm.profile.text("name")),
                                "phone" to vm.field("profilePhone", vm.profile.text("phone")),
                            ),
                        ) {
                            vm.set("dialog", "")
                        }
                    },
                    enabled = !vm.busy,
                ) {
                    AppText("Save profile")
                }
            }
        "receipt" -> DialogFrame("Payment receipt", vm) { ReceiptContent(vm) }
        "testRefund" ->
            DialogFrame("Request test refund", vm) {
                AppText(
                    "This simulates a refund and releases an unused booking or order. No real money will move."
                )
                AppText(
                    "Services already started or prepared cannot be refunded here.",
                    color = Muted,
                )
                Button(
                    onClick = { vm.refundTestPayment() },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppText("Confirm test refund")
                }
            }
        "form" -> EditorDialog(vm)
        "logout",
        "cancelBooking",
        "end",
        "winner",
        "pay",
        "extendPaid" ->
            DialogFrame(
                when (dialog) {
                    "logout" -> "See you next session?"
                    "cancelBooking" -> "Cancel this reservation?"
                    "end" -> "End this session?"
                    "winner" -> "Confirm match winner?"
                    "extendPaid" -> "Add time to this session"
                    else -> "Confirm cash received"
                },
                vm,
            ) {
                if (dialog == "pay" || dialog == "extendPaid") {
                    if (dialog == "extendPaid")
                        Select(
                            "Extra time",
                            vm.field("paymentKind"),
                            listOf("Extension30" to "30 minutes", "Extension60" to "60 minutes"),
                            { vm.set("paymentKind", it) },
                        )
                    LaunchedEffect(vm.field("paymentId"), vm.field("paymentKind")) {
                        vm.quotePayment()
                    }
                    if (
                        (vm.paymentQuote.text("id") == vm.field("paymentId") &&
                            vm.paymentQuote.text("kind") == vm.field("paymentKind"))
                    ) {
                        AppText(
                            money(vm.paymentQuote.optInt("total")),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Lime,
                        )
                        AppText(
                            "Subtotal ${money(vm.paymentQuote.optInt("subtotal"))} · Tier discount ${money(vm.paymentQuote.optInt("discount"))}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                AppText(
                    when (dialog) {
                        "logout" -> "Sign out of this account."
                        "cancelBooking" -> "Your unpaid space will be released."
                        "end" -> "The prepaid package charge remains unchanged."
                        "winner" -> "${vm.field("winName")} will advance. Results are final."
                        "extendPaid" ->
                            "Confirm after receiving cash. Extra time is added only if the space remains available; otherwise no payment is recorded."
                        else ->
                            "Confirm only after receiving cash at the counter. The server applies the member’s current tier discount. This records an actual counter payment."
                    },
                    color = Muted,
                )
                Button(
                    onClick = {
                        when (dialog) {
                            "logout" -> {
                                vm.set("dialog", "")
                                vm.logout()
                            }
                            "cancelBooking" ->
                                vm.action(
                                    "cancel_booking",
                                    obj("id" to vm.field("cancelBooking")),
                                ) {
                                    vm.set("dialog", "")
                                }
                            "end" ->
                                vm.action("end_session", obj("id" to vm.field("endBooking"))) {
                                    vm.set("dialog", "")
                                }
                            "winner" ->
                                vm.action(
                                    "winner",
                                    obj(
                                        "match_id" to vm.field("winMatch"),
                                        "winner" to vm.field("winTeam"),
                                    ),
                                ) {
                                    vm.set("dialog", "")
                                }
                            "extendPaid" ->
                                vm.action(
                                    "extend_paid",
                                    obj(
                                        "id" to vm.field("paymentId"),
                                        "minutes" to
                                            if (vm.field("paymentKind") == "Extension30") 30
                                            else 60,
                                        "expected_total" to vm.paymentQuote.optInt("total"),
                                    ),
                                ) {
                                    vm.set("dialog", "")
                                }
                            else ->
                                vm.action(
                                    "pay",
                                    obj(
                                        "kind" to vm.field("paymentKind"),
                                        "id" to vm.field("paymentId"),
                                        "expected_total" to vm.paymentQuote.optInt("total"),
                                    ),
                                ) {
                                    vm.set("dialog", "")
                                }
                        }
                    },
                    enabled =
                        !vm.busy &&
                            (dialog !in listOf("pay", "extendPaid") ||
                                (vm.paymentQuote.text("id") == vm.field("paymentId") &&
                                    vm.paymentQuote.text("kind") == vm.field("paymentKind"))),
                ) {
                    AppText("Confirm")
                }
            }
    }
}
